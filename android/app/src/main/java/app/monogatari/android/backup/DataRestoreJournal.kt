package app.monogatari.android.backup

import android.content.Context
import android.util.AtomicFile
import app.monogatari.android.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Durable undo log spanning SQLite, encrypted preferences and staged file renames. */
internal object DataRestoreJournal {
    const val marker = "Data.restoreCommit"
    fun file(context: Context) = AtomicFile(File(context.filesDir, "data-restore-journal.json"))
    fun create(context: Context, state: AppState, tracking: JSONObject, reader: JSONObject, dictionarySnapshot: File? = null): JSONObject {
        val atomic = file(context); require(!atomic.baseFile.exists()) { "Recover the interrupted restore before starting another one" }
        val id = UUID.randomUUID().toString(); val snapshots = File(context.filesDir, ".restore-undo-$id").apply { check(mkdirs()) }
        try {
            dictionarySnapshot?.let { source -> source.inputStream().use { input -> File(snapshots, "dictionary.db").outputStream().use { output -> PortableFiles.copy(input, output, 8L * 1024 * 1024 * 1024) } } }
            val preferences = JSONObject(); listOf("credentials", "dictionary").forEach { name -> preferences.put(name, JSONObject(context.getSharedPreferences(name, Context.MODE_PRIVATE).all)) }
            return JSONObject().put("version", 1).put("id", id).put("snapshots", snapshots.name).put("state", state.json()).put("tracking", tracking).put("reader", reader).put("preferences", preferences).put("operations", JSONArray()).also { write(context, it) }
        } catch (error: Throwable) { snapshots.deleteRecursively(); throw error }
    }
    fun write(context: Context, value: JSONObject) {
        val bytes = value.toString().toByteArray(); require(bytes.size <= 64 * 1024 * 1024)
        val atomic = file(context); val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream); check(atomic.readFully().contentEquals(bytes)) { "Restore journal could not be committed" } } catch (error: Throwable) { atomic.failWrite(stream); throw error }
    }
    fun planFiles(context: Context, journal: JSONObject, staging: File, destination: File, roots: List<String>) {
        val operations = JSONArray(roots.map { name -> JSONObject().put("name", name).put("existed", File(context.filesDir, name.removePrefix("files/")).exists()) })
        journal.put("staging", staging.name).put("destination", destination.relativeTo(context.filesDir).invariantSeparatorsPath).put("operations", operations); write(context, journal)
    }
    private fun owned(context: Context, path: String): File {
        require(path.isNotBlank() && !path.contains('\\') && !File(path).isAbsolute)
        return File(context.filesDir, path).canonicalFile.also { require(it.toPath().startsWith(context.filesDir.canonicalFile.toPath()) && it != context.filesDir.canonicalFile) }
    }
    fun rollbackFiles(context: Context, journal: JSONObject) {
        val destination = journal.optString("destination").takeIf { it.isNotBlank() }?.let { require(it.matches(Regex("restored/[0-9a-f-]{36}"))); owned(context, it) }
        if (destination != null && destination.exists()) journal.getJSONArray("operations").objects().asReversed().forEach { operation ->
            val name = operation.getString("name"); require(name.startsWith("files/") && name.substringAfter("files/").let { it.startsWith("sources/") || it.startsWith("dictionaries/") || it.startsWith("downloads/") || it.startsWith("models/custom-") || it.startsWith("source-pages-") })
            val relative = name.removePrefix("files/"); val live = owned(context, relative); val old = owned(context, "${journal.getString("destination")}/.previous/$relative")
            if (old.exists()) { live.deleteRecursively(); live.parentFile?.mkdirs(); check(old.renameTo(live)) }
            else if (!operation.getBoolean("existed") && !File(destination, name).exists()) live.deleteRecursively()
        }
        destination?.deleteRecursively()
        journal.optString("staging").takeIf { it.isNotBlank() }?.let { require(it.matches(Regex("\\.restore-stage-[0-9a-f-]{36}"))); owned(context, it).deleteRecursively() }
    }
    fun finishFiles(context: Context, journal: JSONObject) {
        journal.optString("destination").takeIf { it.isNotBlank() }?.let { path -> val destination = owned(context, path); File(destination, ".previous").deleteRecursively(); destination.listFiles().orEmpty().filter { it.name != "files" }.forEach { it.deleteRecursively() }; File(destination, "files/sources").deleteRecursively(); File(destination, "files/dictionaries").deleteRecursively(); if (!journal.optBoolean("keepMedia", true)) File(destination, "files").deleteRecursively() }
        journal.optString("staging").takeIf { it.isNotBlank() }?.let { owned(context, it).deleteRecursively() }
    }
    fun restorePreferences(context: Context, name: String, values: JSONObject) {
        check(context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().apply { values.keys().forEach { key -> when (val value = values.opt(key)) { is String -> putString(key, value); is Boolean -> putBoolean(key, value); is Int -> putInt(key, value); is Long -> putLong(key, value); is Number -> putFloat(key, value.toFloat()) } } }.commit())
    }
    fun remove(context: Context, journal: JSONObject) { val snapshots = journal.getString("snapshots"); require(snapshots.matches(Regex("\\.restore-undo-[0-9a-f-]{36}"))); owned(context, snapshots).deleteRecursively(); file(context).delete() }
    fun dictionary(context: Context, journal: JSONObject) = owned(context, journal.getString("snapshots") + "/dictionary.db")
    fun undoState(current: AppState, journal: JSONObject): AppState {
        val original = AppState.parse(journal.getJSONObject("state")); val affected = journal.optJSONArray("affectedManga").strings().toSet()
        if (affected.isEmpty()) return original
        var result = current
        affected.map { it.substringBefore("::") }.distinct().forEach { source -> result = app.monogatari.android.library.SourceMigrationJournal.restoreState(result, original, source, affected.filter { it.startsWith("$source::") }.toSet()) }
        val settings = JSONObject(result.settings); settings.remove(marker); JSONObject(original.settings).opt(marker)?.let { settings.put(marker, it) }
        return result.copy(settings = settings.toString())
    }
}

/** Run before repository StateFlows or DictionaryRepository open their databases. */
fun recoverDataRestores(context: Context, database: AppDatabase, state: AppState): AppState {
    val atomic = DataRestoreJournal.file(context); if (!atomic.baseFile.exists()) return state
    require(atomic.baseFile.length() <= 64L * 1024 * 1024); val journal = JSONObject(atomic.readFully().toString(Charsets.UTF_8)); require(journal.getInt("version") == 1)
    if (JSONObject(state.settings).optString(DataRestoreJournal.marker) == journal.getString("id")) { DataRestoreJournal.finishFiles(context, journal); DataRestoreJournal.remove(context, journal); return state }
    DataRestoreJournal.rollbackFiles(context, journal)
    val snapshot = DataRestoreJournal.dictionary(context, journal)
    if (snapshot.isFile) {
        val target = context.getDatabasePath("dictionary.db"); target.parentFile?.mkdirs(); val staged = File(target.parentFile, "dictionary-recover.db")
        snapshot.inputStream().use { input -> staged.outputStream().use { output -> PortableFiles.copy(input, output, 8L * 1024 * 1024 * 1024) } }
        // A completed checkpoint snapshot supersedes every uncommitted WAL page.
        listOf(target, File(target.path + "-wal"), File(target.path + "-shm")).forEach { if (it.exists()) check(it.delete()) }; check(staged.renameTo(target))
    }
    val affected = journal.optJSONArray("affectedManga").strings().toSet()
    if (affected.isNotEmpty()) {
        app.monogatari.android.library.SourceMigrationJournal.restoreTracking(context, journal.getJSONObject("tracking"), affected)
        app.monogatari.android.library.SourceMigrationJournal.restoreReader(context, journal.getJSONObject("reader"), affected)
    } else {
        val trackers = AtomicFile(File(context.filesDir, "tracking.json")); val stream = trackers.startWrite(); try { stream.write(journal.getJSONObject("tracking").toString().toByteArray()); trackers.finishWrite(stream) } catch (error: Throwable) { trackers.failWrite(stream); throw error }
        DataRestoreJournal.restorePreferences(context, "reader-options", journal.getJSONObject("reader"))
        journal.getJSONObject("preferences").let { values -> values.keys().forEach { DataRestoreJournal.restorePreferences(context, it, values.getJSONObject(it)) } }
    }
    val original = DataRestoreJournal.undoState(state, journal); database.save(state, original); DataRestoreJournal.remove(context, journal); return original
}
