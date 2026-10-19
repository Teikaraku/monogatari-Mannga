package app.monogatari.android.library

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.AtomicFile
import app.monogatari.android.data.*
import app.monogatari.android.downloads.DownloadService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal object SourceMigrationJournal {
    const val marker = "Data.sourceMigrationCommit"
    fun file(context: Context) = AtomicFile(File(context.filesDir, "source-key-migration.json"))
    fun create(context: Context, state: AppState, source: String, migrations: Map<String, Pair<String, Map<String, String>>>, trackers: JSONObject, reader: JSONObject, vocabulary: JSONObject, queue: JSONArray): JSONObject {
        require(!file(context).baseFile.exists()) { "An interrupted source migration must be recovered before another migration" }
        val mapping = JSONObject(); migrations.forEach { (old, next) -> mapping.put(old, JSONObject().put("manga", next.first).put("chapters", JSONObject(next.second))) }
        val value = JSONObject().put("version", 1).put("id", UUID.randomUUID().toString()).put("sourceId", source).put("migrations", mapping)
            .put("state", state.json()).put("tracking", trackers).put("reader", reader).put("vocabulary", vocabulary).put("queue", queue)
        val atomic = file(context); val stream = atomic.startWrite()
        try { val bytes = value.toString().toByteArray(); require(bytes.size <= 64 * 1024 * 1024) { "Source migration metadata exceeds the journal limit" }; stream.write(bytes); atomic.finishWrite(stream) }
        catch (error: Throwable) { atomic.failWrite(stream); throw error }
        return value
    }
    fun scope(journal: JSONObject): Set<String> = journal.getJSONObject("migrations").let { mappings -> mappings.keys().asSequence().flatMap { old -> sequenceOf("${journal.getString("sourceId")}::$old", "${journal.getString("sourceId")}::${mappings.getJSONObject(old).getString("manga")}") }.toSet() }
    fun remove(context: Context) { file(context).delete() }
    /** Restore only affected series; other sources and their newer background progress are retained. */
    fun restoreState(current: AppState, original: AppState, source: String, affected: Set<String>): AppState {
        fun belongs(row: JSONObject, kind: String) = row.optString("sourceId") == source && "$source::${row.optString(if (kind == "manga") "id" else "mangaId")}" in affected
        fun restoreRows(now: String, before: String): String = JSONObject(now).also { target -> val old = JSONObject(before)
            listOf("manga", "library", "chapters", "history", "readingSessions", "updates", "vocabulary", "trackItems").forEach { name -> if (target.has(name) || old.has(name)) {
                val entries = target.optJSONArray(name).objects().filterNot { belongs(it, name) }.toMutableList(); old.optJSONArray(name).objects().withIndex().filter { belongs(it.value, name) }.forEach { entries.add(it.index.coerceAtMost(entries.size), it.value) }; target.put(name, JSONArray(entries))
            } }
        }.toString()
        val settings = JSONObject(current.settings); val old = JSONObject(original.settings)
        val known = knownMangaIds(current) + knownMangaIds(original) + affected
        fun relevant(key: String) = key == marker || mangaPreferenceOwner(key, known)?.mangaId in affected
        settings.keys().asSequence().filter(::relevant).toList().forEach(settings::remove); old.keys().asSequence().filter(::relevant).forEach { settings.put(it, old.opt(it)) }
        val manga = current.manga.filterNot { it.id in affected }.toMutableList(); original.manga.withIndex().filter { it.value.id in affected }.forEach { manga.add(it.index.coerceAtMost(manga.size), it.value) }
        val sources = current.sources.filterNot { it.id == source }.toMutableList(); original.sources.firstOrNull { it.id == source }?.let { oldSource -> val position = current.sources.indexOfFirst { it.id == source }.takeIf { it >= 0 } ?: original.sources.indexOf(oldSource); sources.add(position.coerceAtMost(sources.size), oldSource) }
        return current.copy(manga = manga, sources = sources, settings = settings.toString(),
            records = restoreRows(current.records, original.records), backupExtras = restoreRows(current.backupExtras, original.backupExtras))
    }
    fun restoreTracking(context: Context, snapshot: JSONObject, affected: Set<String>) {
        val atomic = AtomicFile(File(context.filesDir, "tracking.json")); val now = if (atomic.baseFile.exists()) JSONObject(atomic.readFully().toString(Charsets.UTF_8)) else JSONObject(snapshot.toString())
        val bindings = now.optJSONArray("bindings").objects().filterNot { it.optString("mangaId") in affected }.toMutableList()
        snapshot.optJSONArray("bindings").objects().withIndex().filter { it.value.optString("mangaId") in affected }.forEach { bindings.add(it.index.coerceAtMost(bindings.size), it.value) }; now.put("bindings", JSONArray(bindings))
        val out = atomic.startWrite(); try { out.write(now.toString().toByteArray()); atomic.finishWrite(out) } catch (error: Throwable) { atomic.failWrite(out); throw error }
    }
    fun restoreReader(context: Context, snapshot: JSONObject, affected: Set<String>) {
        check(context.getSharedPreferences("reader-options", Context.MODE_PRIVATE).edit().apply { affected.forEach { id -> remove("series:$id"); snapshot.opt("series:$id")?.let { putString("series:$id", it as String) } } }.commit())
    }
    fun restoreVocabulary(context: Context, snapshot: JSONObject, affected: Set<String>) {
        val database = context.getDatabasePath("dictionary.db"); if (!database.isFile) return
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            val old = snapshot.optJSONArray("vocabulary").objects().filter { it.optString("mangaId") in affected }.associateBy { it.getString("id") }
            val changes = mutableListOf<Pair<String, JSONObject>>()
            db.rawQuery("SELECT id,payload FROM vocabulary", null).use { rows -> while (rows.moveToNext()) { val current = JSONObject(rows.getString(1)); old[rows.getString(0)]?.let { original -> current.put("mangaId", original.optString("mangaId")).put("chapterKey", original.optString("chapterKey")); changes += rows.getString(0) to current } } }
            db.beginTransaction(); try { changes.forEach { (id, payload) -> db.update("vocabulary", ContentValues().apply { put("payload", payload.toString()) }, "id=?", arrayOf(id)) }; db.setTransactionSuccessful() } finally { db.endTransaction() }
        }
    }
    fun restoreQueue(context: Context, snapshot: JSONArray, affected: Set<String>) {
        val atomic = AtomicFile(File(context.filesDir, "download-queue.json")); val current = if (atomic.baseFile.exists()) JSONArray(atomic.readFully().toString(Charsets.UTF_8)) else JSONArray()
        val entries = current.objects().filterNot { it.optString("manga") in affected } + snapshot.objects().filter { it.optString("manga") in affected }.map { row -> JSONObject(row.toString()).also { if (it.optString("status") == "running") it.put("status", "queued") } }
        val out = atomic.startWrite(); try { out.write(JSONArray(entries).toString().toByteArray()); atomic.finishWrite(out) } catch (error: Throwable) { atomic.failWrite(out); throw error }
    }
}

/** Called before AppRepository constructs its StateFlow; never recursively resolves AppRepository.get. */
fun recoverSourceKeyMigrations(context: Context, database: AppDatabase, state: AppState): AppState {
    val atomic = SourceMigrationJournal.file(context); if (!atomic.baseFile.exists()) return state
    require(atomic.baseFile.length() <= 64L * 1024 * 1024) { "Source migration journal is too large" }
    val journal = JSONObject(atomic.readFully().toString(Charsets.UTF_8)); require(journal.getInt("version") == 1)
    val committed = JSONObject(state.settings).optString(SourceMigrationJournal.marker) == journal.getString("id")
    if (committed) { DownloadService.recoverPendingKeyFiles(context, true); SourceMigrationJournal.remove(context); return state }
    val affected = SourceMigrationJournal.scope(journal)
    DownloadService.recoverPendingKeyFiles(context, false)
    SourceMigrationJournal.restoreTracking(context, journal.getJSONObject("tracking"), affected)
    SourceMigrationJournal.restoreReader(context, journal.getJSONObject("reader"), affected)
    SourceMigrationJournal.restoreVocabulary(context, journal.getJSONObject("vocabulary"), affected)
    SourceMigrationJournal.restoreQueue(context, journal.getJSONArray("queue"), affected)
    val restored = SourceMigrationJournal.restoreState(state, AppState.parse(journal.getJSONObject("state")), journal.getString("sourceId"), affected)
    database.save(state, restored); SourceMigrationJournal.remove(context); return restored
}
