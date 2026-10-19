package app.monogatari.android.data

import app.monogatari.android.AppBrand

import android.content.Context
import android.net.Uri
import app.monogatari.android.backup.*
import app.monogatari.android.dictionary.DictionaryRepository
import app.monogatari.android.reader.ReaderOptionStore
import app.monogatari.android.sources.SourcePreferences
import app.monogatari.android.sources.SourceRuntimeManager
import app.monogatari.android.tracking.TrackingData
import app.monogatari.android.tracking.TrackingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object BackupCodec {
    private val restoreMutex = app.monogatari.android.library.libraryMigrationMutex
    private val secretWords = listOf("login", "username", "password", "token", "auth", "cookie", "apikey", "api_key", "verifier", "secret", ".keys", ".values", ".ls.", ".__")
    private fun entityKey(row: JSONObject, vararg fields: String) = JSONArray(fields.map { row.opt(it) ?: JSONObject.NULL }).toString()
    fun sensitive(key: String) = key.startsWith("__") || secretWords.any { key.lowercase().contains(it) }
    internal fun toIOS(state: AppState): JSONObject = IOSBackupCodec.toIOS(state)
    internal fun fromIOS(j: JSONObject): AppState = IOSBackupCodec.fromIOS(j)
    suspend fun exportAndroid(repo: AppRepository, destination: Uri, options: BackupOptions = BackupOptions(), name: String = "", automatic: Boolean = false) = withContext(Dispatchers.IO) {
        repo.context.contentResolver.openOutputStream(destination, "wt")?.use { write(repo, it, BackupFormat.ANDROID_ZIP, options, name, automatic) } ?: error("无法创建备份")
    }
    suspend fun exportIOS(repo: AppRepository, destination: Uri, options: BackupOptions = BackupOptions(), name: String = "", binary: Boolean = true) = withContext(Dispatchers.IO) {
        repo.context.contentResolver.openOutputStream(destination, "wt")?.use { write(repo, it, if (binary) BackupFormat.IOS_BINARY else BackupFormat.IOS_JSON, options, name) } ?: error("无法创建备份")
    }
    suspend fun write(repo: AppRepository, output: OutputStream, format: BackupFormat, options: BackupOptions = BackupOptions(), name: String = "", automatic: Boolean = false) = withContext(Dispatchers.IO) {
        val state = repo.state.value; val context = repo.context
        val dictionary = DictionaryRepository.get(context); dictionary.load(); val tracking = TrackingRepository.get(context)
        val ios = toIOS(state).put("name", AppBrand.displayText(name.ifBlank { AppBrand.NAME })).put("automatic", automatic)
        fun preserved(key: String, incoming: JSONArray, identity: (JSONObject) -> String): JSONArray {
            val old = ios.optJSONArray(key).objects().associateBy(identity)
            val updated = incoming.objects().map { row -> JSONObject(old[identity(row)]?.toString() ?: "{}").also { replaceObject(it, row) } }
            val opaque = if (key == "trackItems") old.values.filter { item -> app.monogatari.android.tracking.TrackerProvider.entries.none { it.id == item.optString("trackerId") } } else emptyList()
            return JSONArray(updated + opaque)
        }
        ios.put("trackItems", preserved("trackItems", tracking.exportIosTrackItems()) { entityKey(it, "sourceId", "mangaId", "trackerId") })
            .put("vocabulary", preserved("vocabulary", dictionary.exportIosVocabulary()) { entityKey(it, "sourceId", "mangaId", "chapterId", "word", "reading") })
        val settings = collectSettings(repo, options.sensitiveSettings)
        ios.put("settings", filterSettings(settings, options.sensitiveSettings, ios = true))
        listOf(BackupSection.LIBRARY, BackupSection.HISTORY, BackupSection.CHAPTERS, BackupSection.TRACKING, BackupSection.SESSIONS, BackupSection.VOCABULARY, BackupSection.UPDATES, BackupSection.CATEGORIES, BackupSection.SOURCE_LISTS).filter { !options.has(it) }.forEach { ios.put(it.key, JSONArray()); if (it == BackupSection.LIBRARY) ios.put("manga", JSONArray()) }
        if (!options.has(BackupSection.SETTINGS)) ios.remove("settings")
        if (!options.has(BackupSection.SOURCES)) ios.remove("sources")
        if (format != BackupFormat.ANDROID_ZIP) { IOSBackupCodec.validate(ios); if (format == BackupFormat.IOS_BINARY) IOSBackupCodec.writeBinary(ios, output) else output.write(ios.toString(2).toByteArray()); return@withContext }
        val files = PortableFiles(context)
        val native = selectNative(state.copy(settings = filterSettings(settings, options.sensitiveSettings, ios = false).toString()), options)
        val nativeJson = native.json()
        val dictionaryJson = if (options.has(BackupSection.VOCABULARY)) dictionary.exportBackup() else JSONObject().put("version", 1).put("vocabulary", JSONArray())
        if (!options.has(BackupSection.SETTINGS)) dictionaryJson.remove("preferences")
        if (options.has(BackupSection.MEDIA)) {
            val packed = native.copy(manga = native.manga.map { m -> val metadata = JSONObject(m.metadata); metadata.optString("originalCover").takeIf { it.isNotBlank() }?.let { metadata.put("originalCover", files.portable(it)) }; m.copy(cover = files.portable(m.cover), metadata = metadata.toString(), chapters = m.chapters.map { c ->
                files.collectInline(c.html); c.copy(pages = c.pages.map { files.portable(it) }, html = files.portable(c.html, epub = true), thumbnail = files.portable(c.thumbnail))
            }) })
            replaceObject(nativeJson, packed.json())
            nativeJson.optJSONObject("records")?.optJSONArray("history")?.objects()?.forEach { row -> row.optJSONObject("_chapter")?.let { c ->
                c.put("pages", JSONArray(c.optJSONArray("pages").strings().map { files.portable(it) })); c.optString("html").takeIf { it.isNotBlank() }?.let { c.put("html", files.portable(it, epub = true)) }; c.optString("thumbnail").takeIf { it.isNotBlank() }?.let { c.put("thumbnail", files.portable(it)) }
            } }
            dictionaryJson.optJSONArray("vocabulary")?.objects()?.forEach { entry -> if (entry.optString("imagePath").isNotEmpty()) entry.put("imagePath", files.absolute(entry.getString("imagePath"))) }
            File(context.filesDir, "models").listFiles().orEmpty().filter { it.isFile && it.name.startsWith("custom-") }.forEach(files::add)
            File(context.filesDir, "source-content").takeIf { it.isDirectory }?.let(files::directory)
            context.filesDir.listFiles().orEmpty().filter { it.name.startsWith("source-pages-") && it.extension == "json" }.forEach(files::add)
        } else {
            nativeJson.getJSONArray("manga").objects().forEach { m -> if (m.optString("cover").startsWith("file:")) m.put("cover", ""); m.getJSONArray("chapters").objects().forEach { c -> c.put("pages", JSONArray(c.optJSONArray("pages").strings().filter { it.startsWith("http") })); if (c.optString("html").startsWith("file:")) c.put("html", ""); if (c.optString("thumbnail").startsWith("file:")) c.put("thumbnail", ""); c.put("downloaded", false) } }
            dictionaryJson.optJSONArray("vocabulary")?.objects()?.forEach { it.put("imagePath", "") }
            nativeJson.optJSONObject("records")?.optJSONArray("history")?.objects()?.forEach { row -> row.optJSONObject("_chapter")?.let { c -> c.put("pages", JSONArray()); c.put("html", "").put("thumbnail", "").put("downloaded", false) } }
        }
        if (options.has(BackupSection.SOURCES)) File(context.filesDir, "sources").listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith('.') }.forEach(files::directory)
        if (options.has(BackupSection.DICTIONARIES)) File(context.filesDir, "dictionaries").listFiles().orEmpty().filter { it.isDirectory }.forEach(files::directory)
        val manifest = files.manifest().put("format", "aidoku-complete").put("version", 2).put("name", ios.getString("name")).put("date", ios.getDouble("date")).put("automatic", automatic)
            .put("appVersion", app.monogatari.android.BuildConfig.VERSION_NAME).put("options", options.json()).put("counts", counts(ios)).put("iosMetadata", ios)
        manifest.getJSONObject("counts").put("media", files.files.size).put("dictionaries", if (options.has(BackupSection.DICTIONARIES)) dictionary.dictionaries.value.size else 0)
        val temporaryDatabase = File.createTempFile("dictionary-backup-", ".db", context.cacheDir)
        try {
            if (options.has(BackupSection.DICTIONARIES)) dictionary.exportDatabase(temporaryDatabase)
            ZipOutputStream(object : FilterOutputStream(output.buffered()) { override fun close() { flush() } }).use { zip ->
                fun json(name: String, value: JSONObject) { zip.putNextEntry(ZipEntry(name)); zip.write(value.toString().toByteArray()); zip.closeEntry() }
                json("state.json", nativeJson)
                if (options.has(BackupSection.TRACKING)) json("tracking.json", if (options.sensitiveSettings) tracking.data.value.json() else tracking.exportBackup())
                if (options.has(BackupSection.VOCABULARY)) json("dictionary.json", dictionaryJson)
                if (options.has(BackupSection.SETTINGS)) json("reader-options.json", ReaderOptionStore(context).export())
                if (options.has(BackupSection.SETTINGS)) json("source-settings.json", JSONObject().also { values -> state.sources.forEach { source -> values.put(source.id, SourcePreferences(context, source.id).exportEncoded { options.sensitiveSettings || !sourceSensitive(context, source.id, it) }) } })
                if (options.sensitiveSettings) json("credentials.json", credentials(context))
                if (options.has(BackupSection.DICTIONARIES)) { zip.putNextEntry(ZipEntry("dictionary.db")); temporaryDatabase.inputStream().use { PortableFiles.copy(it, zip, 8L * 1024 * 1024 * 1024) }; zip.closeEntry() }
                manifest.put("files", files.write(zip)); json("manifest.json", manifest)
            }
        } finally { temporaryDatabase.delete() }
    }
    private fun selectNative(s: AppState, options: BackupOptions): AppState {
        val content = options.selected.any { it in setOf(BackupSection.LIBRARY, BackupSection.HISTORY, BackupSection.CHAPTERS) }
        val records = JSONObject(s.records); if (!options.has(BackupSection.SESSIONS)) records.remove("readingSessions"); if (!options.has(BackupSection.UPDATES)) records.remove("updates"); if (!options.has(BackupSection.HISTORY)) records.remove("history")
        val extras = JSONObject(s.backupExtras)
        BackupSection.entries.filter { !options.has(it) }.forEach { extras.remove(it.key) }; if (!options.has(BackupSection.LIBRARY)) extras.remove("manga")
        if (options.has(BackupSection.SETTINGS)) extras.put("settings", JSONObject(s.settings))
        if (!options.sensitiveSettings) listOf("credentials", "Android.Tracking.credentials", "Android.Source.credentials").forEach(extras::remove)
        return s.copy(manga = if (!content) emptyList() else s.manga.map { m -> m.copy(inLibrary = options.has(BackupSection.LIBRARY) && m.inLibrary, categories = if (options.has(BackupSection.CATEGORIES)) m.categories else emptyList(), chapters = if (!options.has(BackupSection.CHAPTERS) && !options.has(BackupSection.HISTORY)) emptyList() else m.chapters.map { c -> if (options.has(BackupSection.HISTORY)) c else c.copy(page = 0, completed = false, readAt = 0) }) },
            sources = if (options.has(BackupSection.SOURCES)) s.sources else listOf(SourceConfig.local), sourceLists = if (options.has(BackupSection.SOURCE_LISTS)) s.sourceLists else emptyList(), categories = if (options.has(BackupSection.CATEGORIES)) s.categories else emptyList(),
            categoryMetadata = if (options.has(BackupSection.CATEGORIES)) s.categoryMetadata else "{}", settings = if (options.has(BackupSection.SETTINGS)) s.settings else "{}", records = records.toString(), backupExtras = extras.toString())
    }
    fun counts(j: JSONObject): JSONObject = JSONObject().also { result -> BackupSection.entries.forEach { s -> result.put(s.key, when (val value = j.opt(s.key)) { is JSONArray -> value.length(); is JSONObject -> value.length(); else -> 0 }) } }
    suspend fun preview(context: Context, uri: Uri): BackupSummary = withContext(Dispatchers.IO) { val temporary = cacheInput(context, uri); try { preview(temporary) } finally { temporary.delete() } }
    fun preview(file: File): BackupSummary {
        val signature = file.inputStream().use { signature(it, 6) }
        if (signature.size >= 2 && signature[0] == 0x50.toByte() && signature[1] == 0x4b.toByte()) ZipFile(file).use { zip ->
            val entry = zip.getEntry("manifest.json") ?: zip.getEntry("state.json") ?: error("备份缺少元数据"); val j = zip.getInputStream(entry).use(::metadata)
            val c = j.optJSONObject("counts") ?: counts(j.optJSONObject("backupExtras") ?: j)
            return BackupSummary(file, AppBrand.displayText(j.optString("name", file.name)), (j.optDouble("date", file.lastModified() / 1000.0) * 1000).toLong(), j.optString("appVersion", "1"), j.optBoolean("automatic"), BackupSection.entries.associateWith { c.optInt(it.key) }, file.length(), BackupFormat.ANDROID_ZIP)
        }
        val j = file.inputStream().use { IOSBackupCodec.decode(readBounded(it)) }; IOSBackupCodec.validate(j)
        return BackupSummary(file, AppBrand.displayText(j.optString("name", file.name)), (j.optDouble("date", file.lastModified() / 1000.0) * 1000).toLong(), j.optString("version"), j.optBoolean("automatic"), BackupSection.entries.associateWith { counts(j).optInt(it.key) }, file.length(), if (signature.toString(Charsets.US_ASCII) == "bplist") BackupFormat.IOS_BINARY else BackupFormat.IOS_JSON)
    }
    suspend fun import(repo: AppRepository, uri: Uri): Int = restore(repo, uri).manga
    suspend fun restore(repo: AppRepository, uri: Uri, options: RestoreOptions = RestoreOptions()): RestoreResult = withContext(Dispatchers.IO) { restoreMutex.withLock {
        val context = repo.context; val temporary = cacheInput(context, uri); val files = RestoreFiles(context)
        val rollbackDatabase = File.createTempFile("dictionary-rollback-", ".db", context.cacheDir)
        val tracking = TrackingRepository.get(context); val dictionary = DictionaryRepository.get(context); dictionary.load()
        tracking.migrationTransaction {
        val oldTracking = tracking.data.value.json(); val oldWords = dictionary.exportBackup(); val oldReader = ReaderOptionStore(context).export()
        var dataChanged = false; var dictionaryChanged = false; var committed = false; val oldState = repo.state.value
        val encryptedPreferences = context.getSharedPreferences("credentials", Context.MODE_PRIVATE).all.toMap()
        val dictionaryPreferences = context.getSharedPreferences("dictionary", Context.MODE_PRIVATE).all.toMap()
        var journal: JSONObject? = null; var durableCommitted = false
        try {
            val isZip = temporary.inputStream().use { val b = signature(it, 2); b.size == 2 && b[0] == 0x50.toByte() && b[1] == 0x4b.toByte() }
            val ios: JSONObject; var incoming: AppState; val selected: Set<BackupSection>
            if (isZip) {
                temporary.inputStream().use(files::extract); val stateJson = files.metadata.getValue("state.json"); validateNative(stateJson); incoming = AppState.parse(stateJson)
                val manifest = files.metadata["manifest.json"] ?: JSONObject(); ios = manifest.optJSONObject("iosMetadata") ?: JSONObject(incoming.backupExtras)
                selected = options.selected.intersect(manifest.optJSONObject("options")?.let(BackupOptions::parse)?.selected ?: BackupSection.entries.toSet())
                files.selected = selected
                files.metadata["tracking.json"]?.let { data ->
                    data.optJSONArray("bindings").objects().forEach { binding -> storageInteger(binding.opt("chapterOffset")); (listOfNotNull(binding.optJSONObject("pendingPage")) + binding.optJSONArray("pendingPages").objects()).forEach { storageInteger(it.opt("page"), 0); storageInteger(it.opt("total"), 0); storageInteger(it.opt("updatedAt"), Long.MIN_VALUE, Long.MAX_VALUE) } }
                    TrackingData.parse(data)
                }
                files.metadata["dictionary.json"]?.optJSONArray("vocabulary")?.objects()?.forEach { require(it.optString("word").isNotBlank()); storageInteger(it.opt("page"), 0); app.monogatari.android.dictionary.VocabularyEntry.parse(it) }
                if (BackupSection.MEDIA in selected) incoming = incoming.copy(manga = incoming.manga.map { m -> val metadata = JSONObject(m.metadata); metadata.optString("originalCover").takeIf { it.isNotBlank() }?.let { metadata.put("originalCover", files.restore(it)) }; m.copy(cover = files.restore(m.cover), metadata = metadata.toString(), description = files.rewrite(m.description), chapters = m.chapters.map { c -> c.copy(pages = c.pages.map { files.restore(it) }, html = files.restore(files.rewrite(c.html)), description = files.rewrite(c.description), thumbnail = files.restore(c.thumbnail)) }) })
                else incoming = incoming.copy(manga = incoming.manga.map { m -> m.copy(cover = m.cover.takeUnless { it.startsWith("file:") || it.startsWith("backup://") }.orEmpty(), chapters = m.chapters.map { c -> c.copy(pages = c.pages.filter { it.startsWith("http") }, html = c.html.takeUnless { it.startsWith("file:") || it.startsWith("backup://") }.orEmpty(), thumbnail = c.thumbnail.takeUnless { it.startsWith("file:") || it.startsWith("backup://") }.orEmpty(), downloaded = false) }) })
                val records = JSONObject(incoming.records); records.optJSONArray("history")?.objects()?.forEach { row -> row.optJSONObject("_chapter")?.let { c ->
                    if (BackupSection.MEDIA in selected) { c.put("pages", JSONArray(c.optJSONArray("pages").strings().map { files.restore(it) })); c.put("html", files.restore(c.optString("html"))); c.put("thumbnail", files.restore(c.optString("thumbnail"))) }
                    else { c.put("pages", JSONArray()); c.put("html", "").put("thumbnail", "").put("downloaded", false) }
                } }; incoming = incoming.copy(records = records.toString())
                files.metadata["dictionary.json"]?.optJSONArray("vocabulary")?.objects()?.forEach { e -> e.put("imagePath", if (BackupSection.MEDIA in selected) files.restore(e.optString("imagePath"), plainPath = true) else "") }
                if (BackupSection.DICTIONARIES in selected && File(files.staging, "dictionary.db").exists()) dictionary.exportDatabase(rollbackDatabase)
            } else {
                val decoded = temporary.inputStream().use { IOSBackupCodec.decode(readBounded(it)) }
                if (decoded.optString("format") == "aidoku-android") { validateNative(decoded); incoming = AppState.parse(decoded); ios = JSONObject(incoming.backupExtras) } else { ios = decoded; incoming = fromIOS(ios) }
                selected = options.selected.filter { s -> when (s) { BackupSection.LIBRARY -> ios.has("library") || decoded.has("manga"); BackupSection.MEDIA, BackupSection.DICTIONARIES -> false; else -> ios.has(s.key) || decoded.optString("format") == "aidoku-android" } }.toSet()
            }
            files.selected = selected
            val warnings = mutableListOf<String>()
            if (options.installMissingSources && BackupSection.SOURCES in selected) incoming = prepareMissingSources(repo, incoming, files, warnings)
            if (BackupSection.DICTIONARIES in selected || BackupSection.VOCABULARY in selected) dictionary.exportDatabase(rollbackDatabase, includeVocabulary = true)
            journal = DataRestoreJournal.create(context, oldState, oldTracking, oldReader, rollbackDatabase.takeIf { it.length() > 0 })
            if (isZip || File(files.staging, "files/sources").exists()) files.commit(journal)
            dataChanged = true
            if (options.restoreSensitive && BackupSection.SETTINGS in selected) files.metadata["credentials.json"]?.let { restoreCredentials(context, it) }
            if (BackupSection.TRACKING in selected) {
                val data = files.metadata["tracking.json"]
                data?.optJSONArray("accounts")?.objects()?.forEach { account ->
                    val credentials = files.metadata["credentials.json"]; val id = account.optString("id"); val source = account.optString("sourceId")
                    if (credentials?.has("tracking.token.$id") != true && (source.isBlank() || credentials?.has(source) != true)) account.put("connected", false)
                }
                if (data != null) tracking.importBackup(if (options.merge) mergeTracking(oldTracking, data) else data, restoreAuthenticated = options.restoreSensitive && BackupSection.SETTINGS in selected) else tracking.importIosTrackItems(if (options.merge) mergeArray(tracking.exportIosTrackItems(), ios.optJSONArray("trackItems") ?: JSONArray()) { "${it.optString("sourceId")}::${it.optString("mangaId")}::${it.optString("trackerId")}" } else ios.optJSONArray("trackItems") ?: JSONArray())
            }
            if (BackupSection.VOCABULARY in selected) {
                val words = files.metadata["dictionary.json"]?.let { JSONObject(it.toString()).also { value -> value.remove("preferences") } }
                if (words != null) dictionary.importBackup(if (options.merge) JSONObject(words.toString()).put("vocabulary", mergeArray(oldWords.optJSONArray("vocabulary") ?: JSONArray(), words.optJSONArray("vocabulary") ?: JSONArray()) { "${it.optString("word")}::${it.optString("reading")}" }) else words)
                else dictionary.importIosVocabulary(if (options.merge) mergeArray(dictionary.exportIosVocabulary(), ios.optJSONArray("vocabulary") ?: JSONArray()) { "${it.optString("word")}::${it.optString("reading")}" } else ios.optJSONArray("vocabulary") ?: JSONArray())
            }
            if (BackupSection.DICTIONARIES in selected && File(files.destination, "dictionary.db").exists()) { dictionary.importDictionariesDatabase(File(files.destination, "dictionary.db"), options.merge); dictionaryChanged = true }
            if (BackupSection.SETTINGS in selected) files.metadata["reader-options.json"]?.let { ReaderOptionStore(context).restore(it) }
            val importedSettings = repo.settings.normalizeLegacy(JSONObject(incoming.settings))
            val sources = (incoming.sources + repo.state.value.sources).distinctBy { it.id }
            fun privateKey(key: String) = sensitive(key) || sources.any { source -> key.startsWith("${source.id}.") && sourceSensitive(context, source.id, key.removePrefix("${source.id}.")) }
            if (BackupSection.SETTINGS in selected && options.restoreSensitive) {
                val secretSettings = JSONObject(repo.secrets.get("backup.sensitiveSettings") ?: "{}")
                importedSettings.keys().asSequence().filter(::privateKey).forEach { key -> secretSettings.put(key, importedSettings.opt(key)) }
                if (secretSettings.length() > 0) repo.secrets.set("backup.sensitiveSettings", secretSettings.toString())
            }
            val sanitized = JSONObject(importedSettings.toString()); sanitized.keys().asSequence().filter(::privateKey).toList().forEach(sanitized::remove)
            val incomingExtras = JSONObject(incoming.backupExtras); incomingExtras.optJSONObject("settings")?.let { values -> values.keys().asSequence().filter(::privateKey).toList().forEach(values::remove) }
            listOf("credentials", "Android.Tracking.credentials", "Android.Source.credentials").forEach(incomingExtras::remove)
            incoming = incoming.copy(settings = sanitized.toString(), backupExtras = incomingExtras.toString())
            repo.change { current -> combine(current, incoming, options.copy(selected = selected)).let { result -> if (BackupSection.SETTINGS in selected) repo.settings.normalizeLegacy(JSONObject(result.settings)).let { normalized ->
                normalized.keys().asSequence().filter(::privateKey).toList().forEach(normalized::remove)
                val extras = JSONObject(result.backupExtras); extras.optJSONObject("settings")?.let { values -> values.keys().asSequence().filter(::privateKey).toList().forEach(values::remove) }
                result.copy(settings = normalized.toString(), backupExtras = extras.toString(), preferences = repo.settings.effectivePreferences(normalized, result.preferences))
            } else result } }; committed = true
            if (BackupSection.SOURCES in selected) repo.state.value.sources.forEach { source -> oldState.sources.firstOrNull { it.id == source.id && it.baseUrl != source.baseUrl }?.let {
                // A changed server origin cannot inherit the old server's authentication.
                repo.secrets.keys().filter { key -> (key == source.id || key.startsWith("${source.id}.") || key.startsWith("source:${source.id}:")) && (!options.restoreSensitive || files.metadata["credentials.json"]?.has(key) != true) }.forEach(repo.secrets::remove)
            } }
            if (BackupSection.SOURCES in selected) repo.state.value.sources.filter { it.type == "wasm" }.forEach { SourceRuntimeManager.get(context).invalidate(it.id) }
            if (BackupSection.SETTINGS in selected) {
                val workingSettings = JSONObject(repo.state.value.settings); if (options.restoreSensitive) replaceObject(workingSettings, importedSettings)
                restoreSourceSettings(repo, options.restoreSensitive, workingSettings)
                files.metadata["source-settings.json"]?.let { values -> values.keys().forEach { id ->
                    if (repo.state.value.sources.any { it.id == id }) {
                        val safe = JSONObject(); val encoded = values.getJSONObject(id)
                        encoded.keys().forEach { key -> if (options.restoreSensitive || !sourceSensitive(context, id, key)) safe.put(key, encoded.getJSONObject(key)) }
                        SourcePreferences(context, id).importEncoded(safe)
                    }
                } }
                if (options.restoreSensitive) { restoreLegacyCredentials(repo, workingSettings); tracking.importLegacyCredentials(workingSettings) }
                workingSettings.optString("Dictionary.Android.preferences").takeIf { it.isNotBlank() }?.let { dictionary.importPreferences(JSONObject(it), options.restoreSensitive && files.metadata["credentials.json"]?.has("anki.key") == true) }
            }
            repo.change { state -> state.copy(settings = JSONObject(state.settings).put(DataRestoreJournal.marker, journal!!.getString("id")).toString()) }
            durableCommitted = true
            files.finish(); DataRestoreJournal.remove(context, journal!!); RestoreResult(incoming.manga.size, warnings + if (isZip) emptyList() else listOf("iOS backups contain metadata. Local books and downloads must already exist on this device or be imported separately."))
        } catch (e: Exception) {
            if (durableCommitted) throw e
            fun failure(block: () -> Unit) { try { block() } catch (rollback: Exception) { e.addSuppressed(rollback) } }
            failure { files.rollback() }
            if (dataChanged) try { tracking.restoreSnapshot(oldTracking); dictionary.importBackup(oldWords); context.getSharedPreferences("reader-options", Context.MODE_PRIVATE).edit().clear().commit(); ReaderOptionStore(context).restore(oldReader) } catch (rollback: Exception) { e.addSuppressed(rollback) }
            if (dictionaryChanged && rollbackDatabase.exists()) try { dictionary.importDictionariesDatabase(rollbackDatabase) } catch (rollback: Exception) { e.addSuppressed(rollback) }
            if (committed) try { repo.change { oldState } } catch (rollback: Exception) { e.addSuppressed(rollback) }
            failure { restorePreferences(context, "credentials", encryptedPreferences); restorePreferences(context, "dictionary", dictionaryPreferences) }
            if (e.suppressed.isEmpty()) journal?.let { DataRestoreJournal.remove(context, it) }; throw e
        } finally { files.close(); temporary.delete(); rollbackDatabase.delete() }
    } } }
    internal fun combine(current: AppState, incoming: AppState, options: RestoreOptions): AppState {
        val selected = options.selected; val old = current.manga.associateBy { it.id }; val newer = incoming.manga.associateBy { it.id }
        val manga = (old.keys + newer.keys).map { id ->
            val before = old[id]; val after = newer[id]; var base = if (BackupSection.LIBRARY in selected) after ?: before!! else before ?: after!!
            if (BackupSection.LIBRARY in selected) base = base.copy(inLibrary = if (options.merge) before?.inLibrary == true || after?.inLibrary == true else after?.inLibrary == true)
            else base = base.copy(inLibrary = before?.inLibrary ?: false, categories = before?.categories ?: emptyList())
            val chapters = when { BackupSection.CHAPTERS !in selected -> before?.chapters ?: if (BackupSection.HISTORY in selected) after?.chapters.orEmpty() else emptyList(); options.merge -> (after?.chapters.orEmpty() + before?.chapters.orEmpty()).distinctBy { it.key }; else -> after?.chapters.orEmpty() }.map { c ->
                val previous = before?.chapters?.firstOrNull { it.key == c.key }; val next = after?.chapters?.firstOrNull { it.key == c.key }
                val h = if (BackupSection.HISTORY !in selected) previous else if (options.merge && previous != null && (next?.readAt ?: 0) < previous.readAt) previous else next
                c.copy(page = h?.page ?: 0, total = maxOf(c.total, h?.total ?: 0), readAt = h?.readAt ?: 0, completed = h?.completed ?: false,
                    pages = if (BackupSection.MEDIA in selected) c.pages else previous?.pages ?: c.pages.filter { it.startsWith("http") }, downloaded = if (BackupSection.MEDIA in selected) c.downloaded else previous?.downloaded ?: false,
                    html = if (BackupSection.MEDIA in selected) c.html else previous?.html ?: c.html.takeUnless { it.startsWith("file:") }.orEmpty(), text = if (BackupSection.MEDIA in selected) c.text else previous?.text ?: c.text)
            }
            base.copy(chapters = chapters, cover = if (BackupSection.MEDIA in selected) base.cover else before?.cover ?: base.cover.takeUnless { it.startsWith("file:") }.orEmpty(), categories = if (BackupSection.CATEGORIES in selected) base.categories else before?.categories.orEmpty())
        }
        val records = JSONObject(current.records); val nextRecords = JSONObject(incoming.records)
        if (BackupSection.MEDIA !in selected) {
            val existing = records.optJSONArray("history").objects().associateBy { entityKey(it, "sourceId", "mangaId", "chapterId") }
            nextRecords.optJSONArray("history").objects().forEach { row -> existing[entityKey(row, "sourceId", "mangaId", "chapterId")]?.optJSONObject("_chapter")?.let { original ->
                val chapter = row.optJSONObject("_chapter") ?: JSONObject(original.toString()).also { row.put("_chapter", it) }
                listOf("pages", "html", "thumbnail", "downloaded", "text").forEach { key -> if (original.has(key)) chapter.put(key, original.opt(key)) }
            } }
        }
        mapOf(BackupSection.HISTORY to "history", BackupSection.SESSIONS to "readingSessions", BackupSection.UPDATES to "updates").forEach { (section, key) -> if (section in selected) records.put(key, if (options.merge) mergeArray(records.optJSONArray(key) ?: JSONArray(), nextRecords.optJSONArray(key) ?: JSONArray()) {
            val fields = if (key == "history") arrayOf("sourceId", "mangaId", "chapterId") else arrayOf("sourceId", "mangaId", "chapterId", if (key == "readingSessions") "startDate" else "date")
            entityKey(it, *fields)
        } else nextRecords.optJSONArray(key) ?: JSONArray()) }
        val settings = JSONObject(current.settings); if (BackupSection.SETTINGS in selected) filterSettings(JSONObject(incoming.settings), options.restoreSensitive, ios = false).let { values -> values.keys().forEach { key -> settings.put(key, values.opt(key)) } }
        val categories = if (BackupSection.CATEGORIES !in selected) current.categories else if (options.merge) (incoming.categories + current.categories).distinct() else incoming.categories
        val categoryMetadata = JSONObject(current.categoryMetadata); if (BackupSection.CATEGORIES in selected) { if (!options.merge) categoryMetadata.keys().asSequence().toList().forEach(categoryMetadata::remove); replaceObject(categoryMetadata, JSONObject(incoming.categoryMetadata)) }
        val extras = JSONObject(current.backupExtras); JSONObject(incoming.backupExtras).let { values -> values.keys().forEach { key ->
            val section = if (key == "manga") BackupSection.LIBRARY else BackupSection.entries.firstOrNull { it.key == key }
            if (section == null || section in selected) extras.put(key, if (key == "settings") filterSettings(values.optJSONObject(key) ?: JSONObject(), options.restoreSensitive, ios = false) else values.opt(key))
        } }
        if (!options.restoreSensitive) listOf("credentials", "Android.Tracking.credentials", "Android.Source.credentials").forEach(extras::remove)
        return current.copy(manga = manga, sources = if (BackupSection.SOURCES in selected) (listOf(SourceConfig.local) + incoming.sources.map { source -> if (source.type == "wasm" && source.baseUrl.isBlank()) current.sources.firstOrNull { it.id == source.id } ?: source else source } + if (options.merge) current.sources else emptyList()).distinctBy { it.id } else current.sources,
            sourceLists = if (BackupSection.SOURCE_LISTS in selected) (incoming.sourceLists + if (options.merge) current.sourceLists else emptyList()).distinctBy { it.url } else current.sourceLists,
            categories = categories, categoryMetadata = categoryMetadata.toString(), records = records.toString(), settings = settings.toString(), preferences = if (BackupSection.SETTINGS in selected) incoming.preferences else current.preferences, backupExtras = extras.toString())
    }
    private fun collectSettings(repo: AppRepository, includeSensitive: Boolean): JSONObject = repo.settings.exportWithDefaults().also { settings ->
        val dictionaryPreferences = runCatching { JSONObject(settings.optString("Dictionary.Android.preferences", "{}")) }.getOrDefault(JSONObject())
        replaceObject(dictionaryPreferences, DictionaryRepository.get(repo.context).exportPreferences()); settings.put("Dictionary.Android.preferences", dictionaryPreferences.toString())
        if (includeSensitive) repo.secrets.get("backup.sensitiveSettings")?.let { replaceObject(settings, JSONObject(it)) }
        if (!includeSensitive) repo.state.value.sources.forEach { source -> settings.keys().asSequence().filter { it.startsWith("${source.id}.") && sourceSensitive(repo.context, source.id, it.removePrefix("${source.id}.")) }.toList().forEach(settings::remove) }
        repo.state.value.sources.forEach { source -> SourcePreferences(repo.context, source.id).exportEncoded { key -> includeSensitive || !sourceSensitive(repo.context, source.id, key) }.let { encoded -> encoded.keys().forEach { key ->
            val wrapped = encoded.getJSONObject(key); val fullKey = "${source.id}.$key"
            if (wrapped.optString("kind") != "data" && (key != "disableTracking" || !settings.has(fullKey))) settings.put(fullKey, wrapped.opt("value"))
        } } }
        if (includeSensitive) repo.state.value.sources.filter { it.type in setOf("komga", "kavita", "suwayomi") }.forEach { source ->
            repo.secrets.get(source.id)?.let { authorization ->
                if (authorization.startsWith("Basic ")) { val decoded = android.util.Base64.decode(authorization.removePrefix("Basic "), android.util.Base64.DEFAULT).toString(Charsets.UTF_8).split(':', limit = 2); settings.put("${source.id}.username", decoded.first()).put("${source.id}.password", decoded.getOrElse(1) { "" }) }
                if (authorization.startsWith("Bearer ")) settings.put("${source.id}.token", authorization.removePrefix("Bearer "))
            }
            listOf("apiKey", "refreshToken").forEach { key -> repo.secrets.get("${source.id}.$key")?.let { settings.put("${source.id}.$key", it) } }
        }
    }
    private fun filterSettings(settings: JSONObject, includeSensitive: Boolean, ios: Boolean): JSONObject = JSONObject().also { filtered -> settings.keys().forEach { key ->
        if ((!includeSensitive && sensitive(key)) || key.startsWith("Flag") || key == DataRestoreJournal.marker || key == "Data.sourceMigrationCommit" || key == "General.icloudSync" || key == "Browse.sourceLists" || (ios && key.startsWith("Data"))) return@forEach
        if (settings.opt(key) != JSONObject.NULL) filtered.put(key, settings.opt(key))
    } }
    private fun credentials(context: Context): JSONObject = JSONObject().also { result -> val store = SecretStore(context); context.getSharedPreferences("credentials", Context.MODE_PRIVATE).all.keys.filter { !it.startsWith("tracking.pending.") && !it.endsWith("__oauthPending") }.forEach { key -> store.get(key)?.let { result.put(key, it) } } }
    private fun restoreCredentials(context: Context, incoming: JSONObject): JSONObject { val store = SecretStore(context); val old = JSONObject(); incoming.keys().forEach { key -> old.put(key, store.get(key) ?: JSONObject.NULL); if (incoming.isNull(key)) store.remove(key) else store.set(key, incoming.getString(key)) }; return old }
    private fun restorePreferences(context: Context, name: String, values: Map<String, *>) { check(context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().apply { values.forEach { (key, value) -> when (value) { is String -> putString(key, value); is Boolean -> putBoolean(key, value); is Int -> putInt(key, value); is Long -> putLong(key, value); is Float -> putFloat(key, value) } } }.commit()) }
    private fun sourceSensitive(context: Context, sourceId: String, key: String): Boolean {
        if (sensitive(key)) return true
        val packageInfo = runCatching { SourceRuntimeManager.get(context).installed(sourceId) }.getOrNull() ?: return false
        val secure = mutableSetOf<String>(); packageInfo.info.optJSONObject("login")?.optString("key")?.takeIf { it.isNotBlank() }?.let(secure::add)
        fun visit(value: Any?) { when (value) { is JSONObject -> { if (value.optBoolean("secure")) value.optString("key").takeIf { it.isNotBlank() }?.let(secure::add); value.keys().forEach { visit(value.opt(it)) } }; is JSONArray -> repeat(value.length()) { visit(value.opt(it)) } } }
        visit(packageInfo.jsonArray("settings")); return secure.any { key == it || key.startsWith("$it.") }
    }
    private fun restoreSourceSettings(repo: AppRepository, includeSensitive: Boolean, settings: JSONObject) { repo.state.value.sources.forEach { source -> settings.keys().asSequence().filter { it.startsWith("${source.id}.") }.forEach { key -> if (includeSensitive || !sourceSensitive(repo.context, source.id, key.removePrefix("${source.id}."))) SourcePreferences(repo.context, source.id).set(key.removePrefix("${source.id}."), settings.opt(key)) } } }
    private fun restoreLegacyCredentials(repo: AppRepository, settings: JSONObject) {
        repo.state.value.sources.filter { it.type in setOf("komga", "kavita", "suwayomi") }.forEach { source ->
            if (source.type == "kavita") { settings.optString("${source.id}.token").takeIf { it.isNotEmpty() }?.let { repo.secrets.set(source.id, "Bearer $it") }; listOf("apiKey", "refreshToken").forEach { key -> settings.optString("${source.id}.$key").takeIf { it.isNotEmpty() }?.let { repo.secrets.set("${source.id}.$key", it) } } }
            else settings.optString("${source.id}.username").takeIf { it.isNotEmpty() }?.let { repo.secrets.set(source.id, okhttp3.Credentials.basic(it, settings.optString("${source.id}.password"))) }
        }
    }
    private fun mergeTracking(old: JSONObject, next: JSONObject) = JSONObject(next.toString()).put("accounts", mergeArray(old.optJSONArray("accounts") ?: JSONArray(), next.optJSONArray("accounts") ?: JSONArray()) { it.optString("id") }).put("bindings", mergeArray(old.optJSONArray("bindings") ?: JSONArray(), next.optJSONArray("bindings") ?: JSONArray()) { entityKey(it, "mangaId", "accountId") })
    private fun prepareMissingSources(repo: AppRepository, state: AppState, files: RestoreFiles, warnings: MutableList<String>): AppState {
        val manager = SourceRuntimeManager.get(repo.context)
        val missing = state.sources.filter { it.type == "wasm" && !manager.directory(it.id).exists() && !File(files.staging, "files/sources/${AppRepository.hash(it.id)}").exists() }
        if (missing.isEmpty()) return state
        val lists = state.sourceLists.map { list -> if (list.entries.isNotEmpty()) list else try { repo.client.sourceList(list.url) } catch (e: Exception) { warnings += "${list.url}: ${e.message}"; list } }
        val installed = missing.mapNotNull { source ->
            val match = lists.flatMap { it.entries }.firstOrNull { it.id == source.id && it.downloadUrl.isNotBlank() }
            if (match == null) { warnings += "Source package unavailable: ${source.id}"; null } else try {
                okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url(match.downloadUrl).build()).execute().use { response -> require(response.isSuccessful) { "HTTP ${response.code}" }; response.body?.byteStream()?.use { files.stageSource(it, source.id).config } ?: error("Empty source package") }
            } catch (e: Exception) { warnings += "${source.id}: ${e.message}"; null }
        }.associateBy { it.id }
        return state.copy(sources = state.sources.map { installed[it.id] ?: it }, sourceLists = lists)
    }
    private fun mergeArray(old: JSONArray, incoming: JSONArray, key: (JSONObject) -> String): JSONArray = JSONArray((incoming.objects() + old.objects()).distinctBy(key))
    private fun replaceObject(target: JSONObject, values: JSONObject) { values.keys().forEach { target.put(it, values.opt(it)) } }
    private fun metadata(input: InputStream) = JSONObject(readBounded(input).toString(Charsets.UTF_8))
    private fun validateNative(j: JSONObject) {
        val ids = mutableSetOf<String>(); j.optJSONArray("manga").objects().forEach { m ->
            require(ids.add("${m.getString("sourceId")}::${m.getString("key")}")) { "Backup contains duplicate manga identifiers" }
            storageInteger(m.opt("status")); storageInteger(m.opt("contentRating")); storageInteger(m.opt("addedAt"), Long.MIN_VALUE, Long.MAX_VALUE)
            val chapters = mutableSetOf<String>(); m.optJSONArray("chapters").objects().forEach { c ->
                require(chapters.add(c.getString("key"))); storageInteger(c.opt("page"), 0); storageInteger(c.opt("total"), 0); storageInteger(c.opt("sourceOrder")); storageInteger(c.opt("readAt"), Long.MIN_VALUE, Long.MAX_VALUE)
                listOf("number", "volume").forEach { key -> val value = c.opt(key); if (value != null && value != JSONObject.NULL) require(value is Number && value.toDouble().isFinite()) }
            }
        }
    }
    private fun storageInteger(value: Any?, minimum: Long = Int.MIN_VALUE.toLong(), maximum: Long = Int.MAX_VALUE.toLong()) { if (value != null && value != JSONObject.NULL) {
        require(value is Number && value.toDouble().isFinite()) { "Backup contains an invalid integer" }
        val decimal = java.math.BigDecimal(value.toString()).stripTrailingZeros(); require(decimal.scale() <= 0 && decimal >= java.math.BigDecimal.valueOf(minimum) && decimal <= java.math.BigDecimal.valueOf(maximum)) { "Backup integer exceeds storage range" }
    } }
    private fun signature(input: InputStream, count: Int): ByteArray { val buffer = ByteArray(count); var offset = 0; while (offset < count) { val n = input.read(buffer, offset, count - offset); if (n < 0) break; offset += n }; return buffer.copyOf(offset) }
    private fun readBounded(input: InputStream): ByteArray = ByteArrayOutputStream().also { PortableFiles.copy(input, it, 64L * 1024 * 1024) }.toByteArray()
    private fun cacheInput(context: Context, uri: Uri): File = File.createTempFile("backup-input-", ".tmp", context.cacheDir).also { file -> try { context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { PortableFiles.copy(input, it, 32L * 1024 * 1024 * 1024) } } ?: error("无法打开备份") } catch (e: Exception) { file.delete(); throw e } }
}
