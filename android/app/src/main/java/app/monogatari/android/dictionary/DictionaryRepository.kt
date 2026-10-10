package app.monogatari.android.dictionary

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.UUID
import java.util.zip.ZipInputStream
import app.monogatari.android.data.SettingsStore
import app.monogatari.android.data.AppRepository
import androidx.work.*
import java.util.concurrent.TimeUnit

class DictionaryRepository private constructor(val context: Context) {
    private val helper = DictionaryDatabase(context)
    private val mutex = Mutex()
    private val mutableDictionaries = MutableStateFlow<List<DictionaryInfo>>(emptyList())
    private val mutableVocabulary = MutableStateFlow<List<VocabularyEntry>>(emptyList())
    val dictionaries = mutableDictionaries.asStateFlow()
    val vocabulary = mutableVocabulary.asStateFlow()
    val preferences = context.getSharedPreferences("dictionary", Context.MODE_PRIVATE)
    fun exportPreferences(): JSONObject = JSONObject(preferences.all.filterValues { it is String || it is Boolean || it is Int || it is Long || it is Float })
    fun importPreferences(values: JSONObject, preserveAuthentication: Boolean = false) {
        val endpoint = values.optString("anki.endpoint").takeIf { it.isNotBlank() }
        if (endpoint != null && endpoint != preferences.getString("anki.endpoint", "http://127.0.0.1:8765") && !preserveAuthentication) app.monogatari.android.data.SecretStore(context).remove("anki.key")
        check(preferences.edit().apply { values.keys().asSequence().filter { it in setOf("ocrLanguage", "anki.endpoint", "anki.deck", "anki.model", "anki.mapping") }.forEach { putString(it, values.getString(it)) } }.commit())
    }
    suspend fun resetSettings() = withContext(Dispatchers.IO) { mutex.withLock { check(preferences.edit().clear().commit()) } }
    private val http = OkHttpClient()
    private val hoshi by lazy { HoshiDeinflector(context) }
    suspend fun load() = withContext(Dispatchers.IO) {
        val app = AppRepository.get(context)
        recoverLegacyVocabulary(JSONObject(app.state.value.backupExtras).optJSONArray("vocabulary") ?: JSONArray())
        refresh()
        if (SettingsStore(app).string("Dictionary.updateInterval") != "never") {
            val request = PeriodicWorkRequestBuilder<DictionaryUpdateWorker>(12, TimeUnit.HOURS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("dictionary-update", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
    private fun refresh() {
        mutableDictionaries.value = helper.readableDatabase.rawQuery("SELECT * FROM dictionaries ORDER BY priority,id", null).use { c -> buildList { while (c.moveToNext()) add(DictionaryInfo(c.s("id"), c.s("title"), c.s("revision"), c.i("terms"), c.i("enabled") == 1, c.i("priority"), c.s("category"), c.s("author"), c.s("description"), c.s("index_url"), c.s("download_url"), c.s("kinds"))) } }
        mutableVocabulary.value = helper.readableDatabase.rawQuery("SELECT payload FROM vocabulary ORDER BY created_at DESC", null).use { c -> buildList { while (c.moveToNext()) add(VocabularyEntry.parse(JSONObject(c.getString(0)))) } }
    }
    suspend fun importDictionary(uri: Uri, progress: (Int) -> Unit = {}): DictionaryInfo = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { importDictionary(it, progress) } ?: error("无法打开词典文件")
    }
    suspend fun importDictionary(stream: InputStream, progress: (Int) -> Unit = {}): DictionaryInfo = withContext(Dispatchers.IO) { mutex.withLock {
        val db = helper.writableDatabase
        val id = UUID.randomUUID().toString(); var index: JSONObject? = null; var count = 0; var expanded = 0L; var entries = 0
        val kinds = linkedSetOf<String>(); var oldIds: List<String> = emptyList(); var committed = false
        val mediaDir = File(context.filesDir, "dictionaries/$id").apply { mkdirs() }
        db.beginTransaction()
        try {
            ZipInputStream(stream.buffered()).use { zip ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.nextEntry ?: break
                    require(++entries <= 20000) { "词典包含过多文件" }
                    val name = entry.name.replace('\\', '/')
                    require(!name.startsWith('/') && name.split('/').none { it == ".." } && !Regex("^[A-Za-z]:").containsMatchIn(name)) { "词典包含不安全路径" }
                    if (entry.isDirectory) { zip.closeEntry(); continue }
                    val limited = object : java.io.FilterInputStream(zip) {
                        var total = 0L
                        override fun read(): Int { val b = super.read(); if (b >= 0) consumed(1); return b }
                        override fun read(b: ByteArray, off: Int, len: Int): Int { val n = `in`.read(b, off, len); if (n > 0) consumed(n); return n }
                        fun consumed(n: Int) { total += n; expanded += n; require(total <= 64L * 1024 * 1024 && expanded <= 768L * 1024 * 1024) { "词典解压后过大" } }
                        override fun close() { }
                    }
                    when {
                        name == "index.json" -> { val text = limited.readBytes().toString(Charsets.UTF_8); require(text.length < 1024 * 1024); index = JSONObject(text); require(index!!.has("title")) { "词典索引缺少标题" } }
                        Regex("term_bank_\\d+\\.json").matches(name) || Regex("kanji_bank_\\d+\\.json").matches(name) -> {
                            val kanji = name.startsWith("kanji")
                            kinds += if (kanji) "kanji" else "term"
                            val reader = JsonReader(InputStreamReader(limited, Charsets.UTF_8)); reader.beginArray()
                            while (reader.hasNext()) {
                                currentCoroutineContext().ensureActive()
                                val row = readJson(reader, 0) as? JSONArray ?: error("词典条目格式错误")
                                val expression = row.getString(0); val reading = if (kanji) row.optString(1) + " " + row.optString(2) else row.optString(1)
                                val glossary = if (kanji) row.opt(4) else row.opt(5)
                                val definition = structuredText(glossary).take(100000)
                                db.insertOrThrow("terms", null, ContentValues().apply { put("dictionary_id", id); put("expression", expression); put("reading", reading.trim()); put("definition", definition); put("tags", if (kanji) row.optString(3) else row.optString(2)); put("rules", if (kanji) "kanji" else row.optString(3)); put("sequence", if (kanji) -1 else row.optLong(6, -1)) })
                                val originalGlossary = if (glossary is JSONArray) glossary else JSONArray().put(glossary)
                                db.insertOrThrow("metadata", null, ContentValues().apply { put("dictionary_id", id); put("expression", expression); put("mode", glossaryKey(expression, reading.trim(), definition)); put("payload", originalGlossary.toString()) })
                                if (++count % 1000 == 0) progress(count)
                            }; reader.endArray(); require(reader.peek() == JsonToken.END_DOCUMENT) { "词典 JSON 尾部存在无效数据" }
                        }
                        Regex("term_meta_bank_\\d+\\.json").matches(name) || Regex("kanji_meta_bank_\\d+\\.json").matches(name) -> {
                            val reader = JsonReader(InputStreamReader(limited, Charsets.UTF_8)); reader.beginArray()
                            while (reader.hasNext()) {
                                val row = readJson(reader, 0) as? JSONArray ?: error("词典元数据格式错误")
                                if (row.optString(1) == "freq") kinds += "frequency"
                                if (row.optString(1) == "pitch") kinds += "pitch"
                                db.insertOrThrow("metadata", null, ContentValues().apply { put("dictionary_id", id); put("expression", row.getString(0)); put("mode", row.getString(1)); put("payload", row.opt(2)?.toString().orEmpty()) })
                            }; reader.endArray(); require(reader.peek() == JsonToken.END_DOCUMENT) { "词典 JSON 尾部存在无效数据" }
                        }
                        else -> {
                            val out = File(mediaDir, name).canonicalFile
                            require(out.toPath().startsWith(mediaDir.canonicalFile.toPath()))
                            out.parentFile?.mkdirs(); out.outputStream().use { limited.copyTo(it) }
                        }
                    }
                    zip.closeEntry()
                }
            }
            val idx = index ?: error("请选择包含 index.json 的 Yomitan ZIP 词典")
            val title = idx.getString("title").take(500)
            require(count > 0 || db.rawQuery("SELECT count(*) FROM metadata WHERE dictionary_id=?", arrayOf(id)).use { it.moveToFirst(); it.getInt(0) } > 0) { "词典没有可导入的词条" }
            oldIds = db.rawQuery("SELECT id FROM dictionaries WHERE title=?", arrayOf(title)).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            oldIds.forEach { old -> deleteDictionary(db, old) }
            val info = DictionaryInfo(id, title, idx.optString("revision"), count, priority = mutableDictionaries.value.size, author = idx.optString("author"), description = idx.optString("description"), indexUrl = idx.optString("indexUrl"), downloadUrl = idx.optString("downloadUrl"), kinds = kinds.joinToString(","))
            db.insertOrThrow("dictionaries", null, ContentValues().apply { put("id", id); put("title", title); put("revision", info.revision); put("terms", count); put("enabled", 1); put("priority", info.priority); put("category", "none"); put("author", info.author); put("description", info.description); put("index_url", info.indexUrl); put("download_url", info.downloadUrl); put("kinds", info.kinds) })
            db.setTransactionSuccessful(); committed = true; info
        } catch (e: Exception) { mediaDir.deleteRecursively(); throw e }
        finally { db.endTransaction(); if (committed) oldIds.forEach { File(context.filesDir, "dictionaries/$it").deleteRecursively() }; refresh() }
    } }
    suspend fun recoverLegacyVocabulary(original: JSONArray) = withContext(Dispatchers.IO) { mutex.withLock {
        val rows = (0 until original.length()).mapNotNull { original.optJSONObject(it) }
        val db = helper.writableDatabase; val changes = mutableListOf<Pair<String, VocabularyEntry>>()
        db.rawQuery("SELECT id,payload FROM vocabulary", null).use { stored -> while (stored.moveToNext()) {
            val json = JSONObject(stored.getString(1)); if (json.has("clozeOffset") || json.has("iosFields")) continue
            val entry = VocabularyEntry.parse(json)
            val raw = rows.firstOrNull { row ->
                "${row.optString("sourceId")}::${row.optString("mangaId")}" == entry.mangaId && row.optString("chapterId") == entry.chapterKey && row.optString("word") == entry.word
                    && (if (row.isNull("reading")) "" else row.optString("reading")) == entry.reading && (row.optDouble("createdDate") * 1000).toLong() == entry.createdAt
            } ?: continue
            if (runCatching { app.monogatari.android.backup.IOSBackupCodec.validate(JSONObject().put("vocabulary", JSONArray().put(raw))) }.isFailure) continue
            val sameContext = (if (raw.isNull("sentence")) "" else raw.optString("sentence")) == entry.sentence && (if (raw.isNull("clozeText")) "" else raw.optString("clozeText")) == entry.cloze
            changes += stored.getString(0) to entry.copy(iosFields = raw.toString(),
                clozeOffset = if (sameContext && !raw.isNull("clozeOffset")) raw.getInt("clozeOffset") else null,
                page = if (entry.page == raw.optInt("page") && entry.page > 0) entry.page - 1 else entry.page)
        } }
        if (changes.isNotEmpty()) { db.beginTransaction(); try { changes.forEach { (id, entry) -> db.update("vocabulary", ContentValues().apply { put("payload", entry.json().toString()) }, "id=?", arrayOf(id)) }; db.setTransactionSuccessful() } finally { db.endTransaction() }; refresh() }
    } }
    suspend fun configure(info: DictionaryInfo, enabled: Boolean = info.enabled, priority: Int = info.priority, category: String = info.category) = withContext(Dispatchers.IO) {
        helper.writableDatabase.update("dictionaries", ContentValues().apply { put("enabled", if (enabled) 1 else 0); put("priority", priority); put("category", category) }, "id=?", arrayOf(info.id)); refresh()
    }
    suspend fun move(info: DictionaryInfo, delta: Int) = withContext(Dispatchers.IO) { mutex.withLock {
        val ordered = mutableDictionaries.value.toMutableList(); val position = ordered.indexOfFirst { it.id == info.id }; if (position < 0) return@withLock
        val target = (position + delta).coerceIn(0, ordered.lastIndex); java.util.Collections.swap(ordered, position, target)
        val db = helper.writableDatabase; db.beginTransaction(); try { ordered.forEachIndexed { i, d -> db.execSQL("UPDATE dictionaries SET priority=? WHERE id=?", arrayOf(i, d.id)) }; db.setTransactionSuccessful() } finally { db.endTransaction(); refresh() }
    } }
    suspend fun removeDictionary(id: String) = withContext(Dispatchers.IO) { mutex.withLock { deleteDictionary(helper.writableDatabase, id); File(context.filesDir, "dictionaries/$id").deleteRecursively(); refresh() } }
    private fun deleteDictionary(db: SQLiteDatabase, id: String) { db.delete("terms", "dictionary_id=?", arrayOf(id)); db.delete("metadata", "dictionary_id=?", arrayOf(id)); db.delete("dictionaries", "id=?", arrayOf(id)) }
    suspend fun lookup(text: String, prefix: Boolean = false): List<DictionaryEntry> = withContext(Dispatchers.IO) {
        val normalized = text.trim().take(256); if (normalized.isBlank()) return@withContext emptyList()
        val candidates = hoshi.candidates(normalized)
        data class Ranked(val entry: DictionaryEntry, val candidate: HoshiDeinflector.Candidate, val direct: Boolean, val priority: Int, val frequency: List<Int>)
        val results = mutableListOf<Ranked>()
        val metadataCache = mutableMapOf<Pair<String, String>, Pair<String, String>>()
        val frequencyCache = mutableMapOf<Pair<String, String>, List<Int>>()
        for (candidate in candidates) {
            val term = candidate.text
            val exact = if (prefix && term == normalized) "LIKE ? ESCAPE '\\'" else "= ?"
            val parameter = if (prefix && term == normalized) term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%" else term
            helper.readableDatabase.rawQuery("SELECT t.*, d.title,d.priority FROM terms t JOIN dictionaries d ON t.dictionary_id=d.id WHERE d.enabled=1 AND d.category!='exclude' AND (t.expression $exact OR t.reading $exact) ORDER BY d.priority,t.sequence LIMIT 512", arrayOf(parameter, parameter)).use { c ->
                while (c.moveToNext()) {
                    if (!hoshi.accepts(candidate, c.s("rules"))) continue
                    val expression = c.s("expression"); val reading = c.s("reading")
                    val identity = expression to reading
                    val metadata = metadataCache.getOrPut(identity) { metadata(expression, reading) }
                    val dictionaryId = c.s("dictionary_id"); val definition = c.s("definition")
                    val glossary = helper.readableDatabase.rawQuery("SELECT payload FROM metadata WHERE dictionary_id=? AND expression=? AND mode=? LIMIT 1", arrayOf(dictionaryId, expression, glossaryKey(expression, reading, definition))).use { rows -> if (rows.moveToFirst()) rows.getString(0) else "" }
                    results += Ranked(DictionaryEntry(expression, reading, definition, c.s("title"), c.s("tags"), metadata.first, metadata.second, candidate.reason, dictionaryId, glossary), candidate, expression == term, c.getInt(c.getColumnIndexOrThrow("priority")), frequencyCache.getOrPut(identity) { frequencyOrder(expression, reading) })
                }
            }
        }
        results.sortedWith { a, b ->
            var comparison = a.candidate.steps.compareTo(b.candidate.steps)
            if (comparison == 0) comparison = a.candidate.trace.size.compareTo(b.candidate.trace.size)
            if (comparison == 0) comparison = b.direct.compareTo(a.direct)
            if (comparison == 0) for (i in 0 until maxOf(a.frequency.size, b.frequency.size)) { comparison = (a.frequency.getOrNull(i) ?: Int.MAX_VALUE).compareTo(b.frequency.getOrNull(i) ?: Int.MAX_VALUE); if (comparison != 0) break }
            if (comparison == 0) comparison = a.priority.compareTo(b.priority)
            comparison
        }.map { it.entry }.distinctBy { listOf(it.expression, it.reading, it.dictionary, it.definition) }.take(80)
    }
    suspend fun lookupAt(sentence: String, offset: Int): Pair<String, List<DictionaryEntry>> = withContext(Dispatchers.IO) {
        val remainder = sentence.drop(offset.coerceIn(0, sentence.length)); val points = remainder.codePoints().toArray().take(16)
        var matched = ""; val results = mutableListOf<DictionaryEntry>()
        for (length in points.size downTo 1) { val word = String(points.take(length).toIntArray(), 0, length).trimEnd(); if (word.isBlank()) continue; val entries = lookup(word); if (entries.isNotEmpty()) { if (matched.isBlank()) matched = word; results += entries } }
        matched.ifBlank { points.firstOrNull()?.let { String(Character.toChars(it)) }.orEmpty() } to results.distinctBy { listOf(it.expression, it.reading, it.dictionary, it.definition) }.take(16)
    }
    private fun frequencyOrder(expression: String, reading: String): List<Int> {
        fun number(value: Any?): Int? = when (value) { is Number -> value.toInt().takeIf { it >= 0 }; is JSONObject -> number(value.opt("frequency") ?: value.opt("value")); else -> null }
        return mutableDictionaries.value.filter { it.enabled && "frequency" in it.kinds.split(',') }.sortedBy { it.priority }.map { dictionary ->
            val values = mutableListOf<Int>(); helper.readableDatabase.rawQuery("SELECT payload FROM metadata WHERE dictionary_id=? AND expression=? AND mode='freq'", arrayOf(dictionary.id, expression)).use { rows -> while (rows.moveToNext()) {
                val payload = rows.getString(0); val value = runCatching { org.json.JSONTokener(payload).nextValue() }.getOrNull()
                if (value is JSONObject && value.optString("reading").isNotEmpty() && reading.isNotEmpty() && value.optString("reading") != reading) continue
                number(value)?.let(values::add)
            } }; values.minOrNull() ?: Int.MAX_VALUE
        }
    }
    private fun glossaryKey(expression: String, reading: String, definition: String) = "glossary:" + AppRepository.hash(JSONArray(listOf(expression, reading, definition)).toString())
    private fun metadata(expression: String, reading: String): Pair<String, String> {
        val frequencies = mutableListOf<String>(); val pitches = mutableListOf<String>()
        helper.readableDatabase.rawQuery("SELECT m.mode,m.payload,d.title FROM metadata m JOIN dictionaries d ON d.id=m.dictionary_id WHERE d.enabled=1 AND m.expression=? ORDER BY d.priority", arrayOf(expression)).use { c ->
            while (c.moveToNext()) {
                val payload = c.getString(1); val data = runCatching { JSONObject(payload) }.getOrNull()
                if (data != null && data.optString("reading").isNotEmpty() && reading.isNotEmpty() && data.optString("reading") != reading) continue
                if (c.getString(0) == "freq") frequencies += "${c.getString(2)}: ${data?.opt("frequency")?.let(::structuredText) ?: payload.trim('"')}"
                if (c.getString(0) == "pitch") pitches += "${c.getString(2)}: " + data?.optJSONArray("pitches")?.let { j -> (0 until j.length()).joinToString(", ") { j.getJSONObject(it).optInt("position").toString() } }.orEmpty()
            }
        }
        return frequencies.joinToString(" · ") to pitches.joinToString(" · ")
    }
    suspend fun saveVocabulary(entry: VocabularyEntry): Boolean = withContext(Dispatchers.IO) { mutex.withLock {
        require(entry.word.isNotBlank()) { "单词不能为空" }
        require(entry.page >= 0) { "Invalid vocabulary page" }
        require(entry.clozeOffset == null || entry.clozeOffset in -32768..32767) { "Vocabulary cloze offset exceeds the original Int16 range" }
        require(entry.id.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Invalid vocabulary identifier" }
        val exists = helper.readableDatabase.rawQuery("SELECT id FROM vocabulary WHERE word=? AND reading=?", arrayOf(entry.word, entry.reading)).use { it.moveToFirst() }
        if (exists) return@withLock false
        helper.writableDatabase.insertOrThrow("vocabulary", null, ContentValues().apply { put("id", entry.id); put("word", entry.word); put("reading", entry.reading); put("created_at", entry.createdAt); put("payload", entry.json().toString()) }); refresh(); true
    } }
    suspend fun captureContext(entry: VocabularyEntry, imageUri: Uri, region: OcrRegion? = null): VocabularyEntry = withContext(Dispatchers.IO) {
        require(entry.id.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Invalid vocabulary identifier" }
        val source = OcrEngine.decodeImage(context, imageUri)
        var image = source
        try {
            region?.let { r ->
                val left = ((r.left - 0.03f) * source.width).toInt().coerceIn(0, source.width - 1); val top = ((r.top - 0.02f) * source.height).toInt().coerceIn(0, source.height - 1)
                val right = ((r.right + 0.03f) * source.width).toInt().coerceIn(left + 1, source.width); val bottom = ((r.bottom + 0.02f) * source.height).toInt().coerceIn(top + 1, source.height)
                image = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
            }
            val destination = File(context.filesDir, "vocabulary-media/${entry.id}.png").apply { parentFile?.mkdirs() }
            destination.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) { "保存上下文图像失败" } }
            entry.copy(imagePath = destination.path)
        } finally { if (image !== source) image.recycle(); source.recycle() }
    }
    suspend fun updateVocabulary(entry: VocabularyEntry) = withContext(Dispatchers.IO) { mutex.withLock {
        require(entry.id.matches(Regex("[A-Za-z0-9._-]{1,128}")) && entry.word.isNotBlank() && entry.page >= 0 && (entry.clozeOffset == null || entry.clozeOffset in -32768..32767))
        helper.writableDatabase.update("vocabulary", ContentValues().apply { put("word", entry.word); put("reading", entry.reading); put("payload", entry.json().toString()) }, "id=?", arrayOf(entry.id)); refresh()
    } }
    suspend fun updateContext(id: String, sentence: String, offset: Int?): VocabularyEntry = updateCurrentVocabulary(id) { current ->
        require(offset == null || offset in -32768..32767) { "Vocabulary cloze offset exceeds the original Int16 range" }
        current.copy(sentence = sentence, clozeOffset = offset)
    }
    suspend fun updateNote(id: String, noteId: Long): VocabularyEntry = updateCurrentVocabulary(id) { it.copy(noteId = noteId) }
    private suspend fun updateCurrentVocabulary(id: String, update: (VocabularyEntry) -> VocabularyEntry): VocabularyEntry = withContext(Dispatchers.IO) { mutex.withLock {
        val db = helper.writableDatabase
        val current = db.rawQuery("SELECT payload FROM vocabulary WHERE id=?", arrayOf(id)).use { rows -> require(rows.moveToFirst()) { "Vocabulary entry was removed" }; VocabularyEntry.parse(JSONObject(rows.getString(0))) }
        val next = update(current)
        db.update("vocabulary", ContentValues().apply { put("payload", next.json().toString()) }, "id=?", arrayOf(id)); refresh(); next
    } }
    suspend fun removeVocabulary(id: String) = withContext(Dispatchers.IO) { require(id.matches(Regex("[A-Za-z0-9._-]{1,128}"))); helper.writableDatabase.delete("vocabulary", "id=?", arrayOf(id)); File(context.filesDir, "vocabulary-media/$id.png").delete(); refresh() }
    suspend fun clearVocabulary() = withContext(Dispatchers.IO) { helper.writableDatabase.delete("vocabulary", null, null); File(context.filesDir, "vocabulary-media").deleteRecursively(); refresh() }
    fun exportBackup(): JSONObject = JSONObject().put("version", 1).put("vocabulary", JSONArray(mutableVocabulary.value.map { it.json() })).put("preferences", JSONObject(preferences.all.filterValues { it is String || it is Boolean || it is Int || it is Long || it is Float }))
    suspend fun importBackup(j: JSONObject) = withContext(Dispatchers.IO) { mutex.withLock {
        val arr = j.optJSONArray("vocabulary") ?: JSONArray()
        val entries = (0 until arr.length()).map { VocabularyEntry.parse(arr.getJSONObject(it)).let { e ->
            require(e.word.isNotBlank() && e.page >= 0)
            require(e.id.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Invalid vocabulary identifier" }
            if (e.imagePath.isBlank()) e else {
                val image = File(if (e.imagePath.startsWith("file:")) Uri.parse(e.imagePath).path ?: error("图片引用无效") else e.imagePath).canonicalFile
                require(image.toPath().startsWith(context.filesDir.canonicalFile.toPath()) && image.isFile) { "备份图片引用必须位于应用数据目录中" }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(image.path, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "备份图片引用不是可解码的图像" }
                e.copy(imagePath = image.path)
            }
        } }
        val db = helper.writableDatabase; db.beginTransaction()
        try { db.delete("vocabulary", null, null); entries.forEach { e -> db.insertOrThrow("vocabulary", null, ContentValues().apply { put("id", e.id); put("word", e.word); put("reading", e.reading); put("created_at", e.createdAt); put("payload", e.json().toString()) }) }; db.setTransactionSuccessful() }
        finally { db.endTransaction(); refresh() }
        j.optJSONObject("preferences")?.let { importPreferences(it) }
    } }
    fun exportIosVocabulary(): JSONArray = JSONArray(mutableVocabulary.value.map { e ->
        val parts = e.mangaId.split("::", limit = 2)
        val raw = JSONObject(e.iosFields)
        val unspecifiedPage = parts.size != 2 || e.page == 0 && raw.has("page") && raw.optInt("page") == 0
        val page = if (unspecifiedPage) 0 else Math.addExact(e.page, 1)
        require(page in 0..32767 && (e.clozeOffset == null || e.clozeOffset in -32768..32767)) { "Vocabulary numeric fields exceed the original Int16 range" }
        raw.put("sourceId", parts.first()).put("mangaId", parts.getOrElse(1) { "" }).put("chapterId", e.chapterKey).put("word", e.word)
            .put("reading", e.reading.takeIf { it.isNotEmpty() } ?: JSONObject.NULL).put("sentence", e.sentence.takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
            .put("clozeOffset", e.clozeOffset ?: JSONObject.NULL).put("clozeText", e.cloze.takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
            .put("page", page).put("createdDate", e.createdAt / 1000.0)
    })
    suspend fun importIosVocabulary(items: JSONArray) = withContext(Dispatchers.IO) {
        app.monogatari.android.backup.IOSBackupCodec.validate(JSONObject().put("vocabulary", items))
        val native = JSONArray((0 until items.length()).map { i -> val j = items.getJSONObject(i)
            fun string(key: String) = if (j.isNull(key)) "" else j.optString(key)
            VocabularyEntry(word = j.getString("word"), reading = string("reading"), sentence = string("sentence"), mangaId = "${string("sourceId")}::${string("mangaId")}",
                chapterKey = string("chapterId"), page = (j.optInt("page") - 1).coerceAtLeast(0), cloze = string("clozeText"), createdAt = (j.optDouble("createdDate", 0.0) * 1000).toLong(),
                clozeOffset = if (j.isNull("clozeOffset")) null else j.getInt("clozeOffset"), iosFields = j.toString()).json()
        })
        importBackup(JSONObject().put("vocabulary", native))
    }
    suspend fun migrateSourceKeys(sourceId: String, mangaKeys: Map<String, String>, chapterKeys: Map<String, Map<String, String>>) = withContext(Dispatchers.IO) { mutex.withLock {
        val db = helper.writableDatabase; val prefix = "$sourceId::"; db.beginTransaction()
        try {
            db.rawQuery("SELECT id,payload FROM vocabulary", null).use { rows -> while (rows.moveToNext()) {
                val entry = VocabularyEntry.parse(JSONObject(rows.getString(1)))
                if (entry.mangaId.startsWith(prefix)) {
                    val oldKey = entry.mangaId.removePrefix(prefix); val next = mangaKeys[oldKey] ?: oldKey
                    val payload = entry.copy(mangaId = "$prefix$next", chapterKey = chapterKeys[oldKey]?.get(entry.chapterKey) ?: entry.chapterKey)
                    db.update("vocabulary", ContentValues().apply { put("payload", payload.json().toString()) }, "id=?", arrayOf(rows.getString(0)))
                }
            } }; db.setTransactionSuccessful()
        } finally { db.endTransaction(); refresh() }
    } }
    suspend fun restoreSourceSnapshot(snapshot: JSONObject, affectedMangaIds: Set<String>) = withContext(Dispatchers.IO) { mutex.withLock {
        app.monogatari.android.library.SourceMigrationJournal.restoreVocabulary(context, snapshot, affectedMangaIds); refresh()
    } }
    suspend fun checkUpdates(): List<DictionaryInfo> = withContext(Dispatchers.IO) { mutableDictionaries.value.filter { it.indexUrl.startsWith("https://") && it.downloadUrl.startsWith("https://") }.filter {
        http.newCall(Request.Builder().url(it.indexUrl).build()).execute().use { response -> require(response.isSuccessful) { "检查更新失败：HTTP ${response.code}" }; JSONObject(response.body!!.string()).optString("revision") != it.revision }
    } }
    suspend fun updateDictionary(info: DictionaryInfo): DictionaryInfo = withContext(Dispatchers.IO) {
        require(info.downloadUrl.startsWith("https://")); http.newCall(Request.Builder().url(info.downloadUrl).build()).execute().use { response -> require(response.isSuccessful); importDictionary(response.body!!.byteStream()) }
    }
    suspend fun exportDatabase(destination: File, includeVocabulary: Boolean = false) = withContext(Dispatchers.IO) { mutex.withLock {
        val db = helper.writableDatabase
        db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { c -> if (c.moveToFirst()) require(c.getInt(0) == 0) { "词典数据库正在使用，请重试备份" } }
        context.getDatabasePath("dictionary.db").inputStream().use { input -> destination.outputStream().use { input.copyTo(it) } }
        // Dictionary backups must not silently include a vocabulary list the user excluded.
        if (!includeVocabulary) SQLiteDatabase.openDatabase(destination.path, null, SQLiteDatabase.OPEN_READWRITE).use { copy -> copy.rawQuery("PRAGMA secure_delete=ON", null).use { it.moveToFirst() }; copy.delete("vocabulary", null, null); copy.execSQL("VACUUM") }
    } }
    suspend fun importDictionariesDatabase(source: File, merge: Boolean = false) = withContext(Dispatchers.IO) { mutex.withLock {
        SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READONLY).use { incoming ->
            require(incoming.version in 1..2) { "不支持的词典数据库版本" }
            incoming.rawQuery("PRAGMA integrity_check", null).use { require(it.moveToFirst() && it.getString(0) == "ok") { "词典数据库损坏" } }
            val tables = listOf("dictionaries", "terms", "metadata")
            val db = helper.writableDatabase; db.beginTransaction()
            try {
                if (!merge) tables.forEach { db.delete(it, null, null) }
                if (merge) incoming.rawQuery("SELECT id FROM dictionaries", null).use { c -> while (c.moveToNext()) deleteDictionary(db, c.getString(0)) }
                for (table in tables) incoming.rawQuery("SELECT * FROM $table", null).use { c -> while (c.moveToNext()) {
                    val row = ContentValues(); for (i in 0 until c.columnCount) when (c.getType(i)) { Cursor.FIELD_TYPE_NULL -> row.putNull(c.getColumnName(i)); Cursor.FIELD_TYPE_INTEGER -> row.put(c.getColumnName(i), c.getLong(i)); Cursor.FIELD_TYPE_FLOAT -> row.put(c.getColumnName(i), c.getDouble(i)); Cursor.FIELD_TYPE_BLOB -> row.put(c.getColumnName(i), c.getBlob(i)); else -> row.put(c.getColumnName(i), c.getString(i)) }
                    if (table == "dictionaries" && !row.containsKey("kinds")) row.put("kinds", "term")
                    db.insertOrThrow(table, null, row)
                } }
                db.setTransactionSuccessful()
            } finally { db.endTransaction(); refresh() }
        }
    } }
    companion object {
        @Volatile private var instance: DictionaryRepository? = null
        fun get(context: Context): DictionaryRepository = instance?.takeIf { it.context === context.applicationContext } ?: synchronized(this) { instance?.takeIf { it.context === context.applicationContext } ?: DictionaryRepository(context.applicationContext).also { instance = it } }
        fun structuredText(value: Any?, depth: Int = 0): String {
            if (depth > 32 || value == null || value == JSONObject.NULL) return ""
            return when (value) {
                is JSONArray -> (0 until value.length()).joinToString("\n") { structuredText(value.opt(it), depth + 1) }
                is JSONObject -> when {
                    value.optString("type") == "image" -> "[${value.optString("description", "image")}]"
                    value.has("content") -> structuredText(value.opt("content"), depth + 1)
                    value.has("value") -> structuredText(value.opt("value"), depth + 1)
                    value.has("text") -> value.optString("text")
                    else -> value.keys().asSequence().filter { it !in setOf("tag", "type", "style", "lang", "data") }.joinToString("; ") { "$it: ${structuredText(value.opt(it), depth + 1)}" }
                }
                else -> value.toString()
            }
        }
        private fun readJson(r: JsonReader, depth: Int): Any {
            require(depth < 64) { "词典条目嵌套过深" }
            return when (r.peek()) {
                JsonToken.BEGIN_ARRAY -> JSONArray().apply { r.beginArray(); while (r.hasNext()) { require(length() < 20000); put(readJson(r, depth + 1)) }; r.endArray() }
                JsonToken.BEGIN_OBJECT -> JSONObject().apply { r.beginObject(); while (r.hasNext()) put(r.nextName(), readJson(r, depth + 1)); r.endObject() }
                JsonToken.STRING -> r.nextString(); JsonToken.NUMBER -> r.nextString().let { it.toLongOrNull() ?: it.toDouble() }; JsonToken.BOOLEAN -> r.nextBoolean()
                JsonToken.NULL -> { r.nextNull(); JSONObject.NULL }; else -> error("词典 JSON 无效")
            }
        }
    }
}
class DictionaryUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val settings = SettingsStore(AppRepository.get(applicationContext))
        val seconds = when (settings.string("Dictionary.updateInterval")) { "daily" -> 86400; "weekly" -> 604800; "monthly" -> 2592000; else -> 0 }
        if (seconds > 0 && System.currentTimeMillis() / 1000.0 - settings.double("Dictionary.lastUpdate") >= seconds) {
            val repo = DictionaryRepository.get(applicationContext); repo.load(); repo.checkUpdates().forEach { repo.updateDictionary(it) }
            settings.put("Dictionary.lastUpdate", System.currentTimeMillis() / 1000.0)
        }
        Result.success()
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { Result.retry() }
}
private fun Cursor.s(name: String): String = getString(getColumnIndexOrThrow(name)).orEmpty()
private fun Cursor.i(name: String): Int = getInt(getColumnIndexOrThrow(name))
private class DictionaryDatabase(context: Context) : SQLiteOpenHelper(context, "dictionary.db", null, 2) {
    override fun onConfigure(db: SQLiteDatabase) { super.onConfigure(db); db.setForeignKeyConstraintsEnabled(true); db.enableWriteAheadLogging() }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE dictionaries(id TEXT PRIMARY KEY,title TEXT NOT NULL,revision TEXT,terms INTEGER,enabled INTEGER,priority INTEGER,category TEXT,author TEXT,description TEXT,index_url TEXT,download_url TEXT,kinds TEXT NOT NULL DEFAULT 'term')")
        db.execSQL("CREATE TABLE terms(dictionary_id TEXT NOT NULL,expression TEXT NOT NULL,reading TEXT,definition TEXT,tags TEXT,rules TEXT,sequence INTEGER)")
        db.execSQL("CREATE INDEX terms_expression ON terms(expression)"); db.execSQL("CREATE INDEX terms_reading ON terms(reading)"); db.execSQL("CREATE INDEX terms_dictionary ON terms(dictionary_id)")
        db.execSQL("CREATE TABLE metadata(dictionary_id TEXT NOT NULL,expression TEXT,mode TEXT,payload TEXT)"); db.execSQL("CREATE INDEX metadata_expression ON metadata(expression)")
        db.execSQL("CREATE TABLE vocabulary(id TEXT PRIMARY KEY,word TEXT,reading TEXT,created_at INTEGER,payload TEXT)"); db.execSQL("CREATE INDEX vocabulary_word ON vocabulary(word,reading)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { if (oldVersion < 2) db.execSQL("ALTER TABLE dictionaries ADD COLUMN kinds TEXT NOT NULL DEFAULT 'term'") }
}
