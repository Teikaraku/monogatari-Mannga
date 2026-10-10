package app.monogatari.android.library

import app.monogatari.android.data.*
import app.monogatari.android.downloads.DownloadService
import app.monogatari.android.reader.ReaderOptionStore
import app.monogatari.android.tracking.TrackingRepository
import app.monogatari.android.dictionary.DictionaryRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal val libraryMigrationMutex = Mutex()
internal fun isMangaPreference(key: String) = key.startsWith("Reader.") || key.startsWith("Manga.")
internal data class MangaPreferenceOwner(val mangaId: String, val suffix: String)
internal fun mangaPreferenceOwner(key: String, knownIds: Collection<String>): MangaPreferenceOwner? {
    if (!isMangaPreference(key)) return null
    val matches = knownIds.distinct().flatMap { id -> listOf(id, id.replaceFirst("::", ".")).distinct().mapNotNull { suffix ->
        if (key.endsWith(".$suffix")) MangaPreferenceOwner(id, suffix) else null
    } }
    val length = matches.maxOfOrNull { it.suffix.length } ?: return null
    val longest = matches.filter { it.suffix.length == length }
    return longest.singleOrNull() ?: longest.firstOrNull().takeIf { longest.map { it.mangaId }.distinct().size == 1 }
}
internal fun knownMangaIds(state: AppState): Set<String> = buildSet {
    addAll(state.manga.map { it.id })
    for (raw in listOf(state.records, state.backupExtras)) {
        val data = JSONObject(raw)
        listOf("manga", "library", "chapters", "history", "readingSessions", "updates", "trackItems", "vocabulary").forEach { section -> data.optJSONArray(section).objects().forEach { row ->
            val source = row.optString("sourceId"); val manga = row.optString(if (section == "manga") "id" else "mangaId")
            if (source.isNotBlank() && manga.isNotBlank()) add("$source::$manga")
        } }
    }
}

/** Pause files first; restore every participant if a later persistent write fails. */
suspend fun AppRepository.migrateSourceKeys(
    sourceId: String, mangaKeys: Map<String, String>, chapterKeys: Map<String, Map<String, String>>,
    downloads: DownloadService = DownloadService.get(context),
    afterStateCommit: suspend () -> Unit = {},
    updatedSource: SourceConfig? = null,
) = libraryMigrationMutex.withLock {
    val original = state.value; val reader = ReaderOptionStore(context); val oldReader = reader.export()
    val tracking = TrackingRepository.get(context)
    val dictionary = DictionaryRepository.get(context); dictionary.load(); val oldVocabulary = dictionary.exportBackup()
    val knownChapters = mutableMapOf<String, MutableSet<String>>()
    original.manga.filter { it.sourceId == sourceId }.forEach { m -> knownChapters.getOrPut(m.key) { linkedSetOf() }.addAll(m.chapters.map { it.key }) }
    listOf(JSONObject(original.records), JSONObject(original.backupExtras)).forEach { data ->
        listOf("history", "chapters", "readingSessions", "updates", "vocabulary").forEach { name -> data.optJSONArray(name).objects().filter { it.optString("sourceId") == sourceId }.forEach { row ->
            val chapter = row.optString(if (name == "chapters") "id" else "chapterId"); if (chapter.isNotBlank()) knownChapters.getOrPut(row.optString("mangaId")) { linkedSetOf() }.add(chapter)
        } }
    }
    val keys = (mangaKeys.keys + chapterKeys.keys).filter { key -> mangaKeys[key]?.let { it != key } == true || chapterKeys[key].orEmpty().any { it.key != it.value } }.toSet()
    val migrations = keys.associateWith { old -> (mangaKeys[old] ?: old) to (knownChapters[old].orEmpty().associateWith { chapterKeys[old]?.get(it) ?: it } + chapterKeys[old].orEmpty()) }
    if (migrations.isEmpty()) { if (updatedSource != null) change { current -> current.copy(sources = replacementSource(current.sources, updatedSource)) }; return@withLock }
    require(migrations.keys.all { it.isNotBlank() } && migrations.values.all { it.first.isNotBlank() && it.second.values.all(String::isNotBlank) }) { "Migration identifiers cannot be empty" }
    val identifiers = original.manga.filter { it.sourceId == sourceId }.map { it.key }.toSet() + migrations.keys
    require(identifiers.map { migrations[it]?.first ?: it }.distinct().size == identifiers.size) { "Migration would merge different manga identifiers" }
    migrations.values.forEach { require(it.second.values.distinct().size == it.second.size) { "Migration would merge different chapter identifiers" } }
    val queueSnapshot = JSONArray(downloads.entries.value.map { it.json() })
    tracking.migrationTransaction {
    val oldTracking = tracking.data.value.json()
    val journal = SourceMigrationJournal.create(context, original, sourceId, migrations, oldTracking, oldReader, oldVocabulary, queueSnapshot)
    val affected = SourceMigrationJournal.scope(journal)
    var filesChanged = false; var stateChanged = false; var trackerChanged = false; var vocabularyChanged = false
    try {
        downloads.migrateKeys(sourceId, migrations, resume = false); filesChanged = true
        tracking.migrateSourceBindings(sourceId, mangaKeys, migrations.mapValues { it.value.second }); trackerChanged = true
        dictionary.migrateSourceKeys(sourceId, mangaKeys, migrations.mapValues { it.value.second }); vocabularyChanged = true
        SourceMigrationJournal.restoreReader(context, migratedReaderOptions(oldReader, sourceId, migrations.mapValues { it.value.first }), affected)
        change { current -> migrateStateKeys(current, sourceId, migrations, java.io.File(context.filesDir, "downloads")).let { changed -> changed.copy(sources = updatedSource?.let { replacementSource(changed.sources, it) } ?: changed.sources, settings = JSONObject(changed.settings).put(SourceMigrationJournal.marker, journal.getString("id")).toString()) } }; stateChanged = true
        afterStateCommit()
        downloads.resumeMigrated()
        SourceMigrationJournal.remove(context)
    } catch (error: Throwable) {
        withContext(NonCancellable) {
            suspend fun attempt(block: suspend () -> Unit) { try { block() } catch (rollback: Throwable) { error.addSuppressed(rollback) } }
            attempt { downloads.restoreQueueSnapshot(queueSnapshot, affected, resumeWork = false) }
            if (filesChanged) attempt { DownloadService.recoverPendingKeyFiles(context, committed = false) }
            if (filesChanged || stateChanged) attempt { change { current -> SourceMigrationJournal.restoreState(current, original, sourceId, affected) } }
            if (trackerChanged) attempt { tracking.restoreSourceSnapshot(oldTracking, affected) }
            if (vocabularyChanged) attempt { dictionary.restoreSourceSnapshot(oldVocabulary, affected) }
            attempt { SourceMigrationJournal.restoreReader(context, oldReader, affected) }
            if (error.suppressed.isEmpty()) { attempt { downloads.restoreQueueSnapshot(queueSnapshot, affected, resumeWork = true) }; if (error.suppressed.isEmpty()) SourceMigrationJournal.remove(context) }
        }
        throw error
    }
    }
}
private fun replacementSource(sources: List<SourceConfig>, updated: SourceConfig): List<SourceConfig> = if (sources.any { it.id == updated.id }) sources.map { if (it.id == updated.id) updated else it } else sources + updated

internal fun migratedReaderOptions(original: JSONObject, sourceId: String, mangaKeys: Map<String, String>): JSONObject = JSONObject(original.toString()).also { next ->
    mangaKeys.keys.forEach { next.remove("series:$sourceId::$it") }
    mangaKeys.forEach { (old, key) -> original.opt("series:$sourceId::$old")?.let { next.put("series:$sourceId::$key", it) } }
}
internal fun restoreReaderOptions(context: android.content.Context, values: JSONObject) {
    check(context.getSharedPreferences("reader-options", android.content.Context.MODE_PRIVATE).edit().clear().apply { values.keys().forEach { key -> putString(key, values.getString(key)) } }.commit()) { "Cannot persist reader options" }
}
internal fun migrateStateKeys(state: AppState, sourceId: String, migrations: Map<String, Pair<String, Map<String, String>>>, downloadsDirectory: java.io.File? = null): AppState {
    val hashes = migrations.flatMap { (old, next) -> next.second.map { (chapter, key) -> AppRepository.chapterDownloadId("$sourceId::$old", chapter) to AppRepository.chapterDownloadId("$sourceId::${next.first}", key) } }.toMap()
    fun downloadUrl(value: String): String {
        val uri = android.net.Uri.parse(value)
        if (uri.scheme == "aidoku-archive" && uri.host == "download") return hashes[uri.path.orEmpty().trim('/') ]?.let { uri.buildUpon().path("/$it").build().toString() } ?: value
        if (uri.scheme == "file" && downloadsDirectory != null) {
            val prefix = downloadsDirectory.absolutePath + java.io.File.separator; val path = uri.path.orEmpty()
            if (path.startsWith(prefix)) { val relative = path.removePrefix(prefix); val old = relative.substringBefore(java.io.File.separator); return hashes[old]?.let { android.net.Uri.fromFile(java.io.File(downloadsDirectory, it + relative.removePrefix(old))).toString() } ?: value }
        }
        return value
    }
    fun metadataKeys(value: JSONObject, next: String, chapters: Map<String, String>) {
        if (value.optString("sourceId", sourceId) != sourceId) return
        if (value.has("mangaId")) value.put("mangaId", next)
        chapters[value.optString("chapterId")]?.let { value.put("chapterId", it) }
        value.optJSONObject("_manga")?.let { parent -> parent.put("key", next); if (parent.has("id")) parent.put("id", next); if (parent.has("mangaId")) parent.put("mangaId", next) }
        value.optJSONArray("chapters")?.objects()?.forEach { chapter -> chapters[chapter.optString("key")]?.let { chapter.put("key", it) }; chapters[chapter.optString("id")]?.let { chapter.put("id", it) }; metadataKeys(chapter, next, chapters) }
    }
    fun remap(data: JSONObject): JSONObject {
        listOf("manga", "library", "chapters", "readingSessions", "updates", "history", "trackItems", "vocabulary").forEach { name -> data.optJSONArray(name).objects().forEach { item ->
            if (item.optString("sourceId") == sourceId) {
                val mangaField = if (name == "manga") "id" else "mangaId"
                val oldManga = item.optString(mangaField)
                migrations[oldManga]?.let { (next, chapters) ->
                    val chapterField = if (name == "chapters") "id" else "chapterId"; val oldChapter = item.optString(chapterField)
                    item.put(mangaField, next); chapters[oldChapter]?.let { item.put(chapterField, it) }
                    if (name == "manga" && item.has("key")) item.put("key", next)
                    if (name == "trackItems" && item.optString("trackerId") in setOf("komga", "kavita", "suwayomi") && item.optString("id") == "$sourceId|$oldManga") item.put("id", "$sourceId|$next")
                    item.optJSONObject("_chapter")?.let { c ->
                        val key = c.optString("key"); chapters[key]?.let { c.put("key", it) }; c.optJSONObject("metadata")?.let { meta -> metadataKeys(meta, next, chapters); if (meta.has("key")) meta.put("key", chapters[key] ?: key); if (meta.optString("id") == key) meta.put("id", chapters[key] ?: key) }
                        c.optJSONArray("pages")?.let { c.put("pages", JSONArray(it.strings().map(::downloadUrl))) }
                    }
                }
            }
        } }; return data
    }
    val settings = JSONObject(state.settings); val snapshot = JSONObject(state.settings)
    val known = knownMangaIds(state) + migrations.keys.map { "$sourceId::$it" }
    val preferences = snapshot.keys().asSequence().mapNotNull { key -> mangaPreferenceOwner(key, known)?.let { key to it } }.toList()
    val affected = migrations.keys.map { "$sourceId::$it" }.toSet()
    preferences.filter { it.second.mangaId in affected }.forEach { settings.remove(it.first) }
    preferences.filter { it.second.mangaId in affected }.forEach { (key, owner) ->
        val next = migrations.getValue(owner.mangaId.removePrefix("$sourceId::")).first
        settings.put(key.removeSuffix(owner.suffix) + "$sourceId.$next", snapshot.get(key))
    }
    return state.copy(manga = state.manga.map { m -> if (m.sourceId != sourceId) m else migrations[m.key]?.let { (next, chapters) ->
        val metadata = JSONObject(m.metadata); metadataKeys(metadata, next, chapters); if (metadata.optString("id") == m.key) metadata.put("id", next); if (metadata.has("key")) metadata.put("key", next)
        m.copy(key = next, metadata = metadata.toString(), chapters = m.chapters.map { c -> val meta = JSONObject(c.metadata); metadataKeys(meta, next, chapters); if (meta.optString("id") == c.key) meta.put("id", chapters[c.key] ?: c.key); if (meta.has("key")) meta.put("key", chapters[c.key] ?: c.key); c.copy(key = chapters[c.key] ?: c.key, metadata = meta.toString()) })
    } ?: m }, records = remap(JSONObject(state.records)).toString(), backupExtras = remap(JSONObject(state.backupExtras)).toString(), settings = settings.toString())
}
