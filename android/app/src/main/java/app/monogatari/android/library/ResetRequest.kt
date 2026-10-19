package app.monogatari.android.library

import android.content.Context
import android.util.AtomicFile
import app.monogatari.android.data.*
import org.json.JSONObject
import java.io.File

/** A reset is an explicit destructive action. Persist its intent so a restart finishes the same action. */
object ResetRequest {
    private fun file(context: Context) = AtomicFile(File(context.filesDir, "reset-request.json"))
    fun pending(context: Context) = file(context).let { it.baseFile.exists() || File(it.baseFile.path + ".bak").exists() }
    fun begin(context: Context) {
        val atomic = file(context); val out = atomic.startWrite()
        try { out.write(JSONObject().put("action", "reset").put("version", 1).toString().toByteArray()); atomic.finishWrite(out) }
        catch (error: Throwable) { atomic.failWrite(out); throw error }
    }
    fun clearFilesAndPreferences(context: Context) {
        val root = context.filesDir.canonicalFile
        listOf("downloads", "local", "covers", "sources").forEach { name ->
            val target = File(root, name).canonicalFile
            check(target.parentFile == root) { "Reset path is outside application storage" }
            if (target.exists()) check(target.deleteRecursively()) { "Cannot reset $name" }
        }
        listOf("download-queue.json", "tracking.json").forEach { AtomicFile(File(root, it)).delete() }
        listOf("credentials", "reader-options", "dictionary", "monogatari-logs").forEach { name ->
            check(context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()) { "Cannot reset $name preferences" }
        }
        app.monogatari.android.reader.ReaderPageCache.clear(context)
    }
    fun finish(context: Context) = file(context).delete()
    fun recover(context: Context, database: AppDatabase, state: AppState): AppState {
        val atomic = file(context)
        if (!pending(context)) return state
        val intent = JSONObject(atomic.readFully().toString(Charsets.UTF_8))
        check(intent.optString("action") == "reset" && intent.optInt("version") == 1) { "Unknown pending maintenance action" }
        clearFilesAndPreferences(context)
        val empty = AppState(); database.save(state, empty)
        finish(context)
        return empty
    }
}
