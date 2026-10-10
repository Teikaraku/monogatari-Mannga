package app.monogatari.android.library

import app.monogatari.android.data.*
import app.monogatari.android.sources.*
import app.monogatari.android.tracking.TrackingRepository
import coil.imageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

object Maintenance {
    suspend fun clearHistory(repo: AppRepository, excludingLibrary: Boolean = false) = repo.change { s ->
        val keep = s.manga.filter { excludingLibrary && it.inLibrary }.map { it.id }.toSet()
        fun cleaned(raw: String): String {
            return HistoryRecords.clear(raw) { (it.optString("sourceId") + "::" + it.optString("mangaId")) !in keep }
        }
        s.copy(manga = s.manga.map { m -> if (m.id in keep) m else m.copy(chapters = m.chapters.map { val meta = JSONObject(it.metadata); meta.remove("scrollPosition"); it.copy(page = 0, readAt = 0, completed = false, metadata = meta.toString()) }, metadata = JSONObject(m.metadata).put("lastRead", 0).toString()) },
            records = cleaned(s.records), backupExtras = cleaned(s.backupExtras))
    }
    suspend fun clearNetwork(repo: AppRepository) = withContext(Dispatchers.IO) {
        repo.client.http.cache?.evictAll()
        val loader = repo.context.imageLoader; loader.diskCache?.clear(); loader.memoryCache?.clear()
        app.monogatari.android.reader.ReaderPageCache.clear(repo.context)
        repo.state.value.sources.filter { it.type != "local" }.forEach { source ->
            runCatching { repo.client.session(source).clearCookies() }.onFailure { SourceLogStore.get(repo.context).add("Clear source cache ${source.id}: ${it.message}", "ERROR") }
            if (source.type != "wasm") SourceWebProfiles.clear(repo.context, source.id)
        }
        SourceWebProfiles.clearAll(repo.context)
        repo.secrets.keys().filter { it.endsWith(".cookie") }.forEach(repo.secrets::remove)
    }
    suspend fun resetSettings(repo: AppRepository) {
        repo.context.getSharedPreferences("reader-options", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        repo.settings.import(JSONObject())
        TrackingRepository.get(repo.context).resetSettings()
        app.monogatari.android.dictionary.DictionaryRepository.get(repo.context).resetSettings()
        repo.secrets.clear()
        SourceLogStore.get(repo.context).setStreamUrl(null)
    }
    suspend fun action(repo: AppRepository, key: String) {
        val tracks = TrackingRepository.get(repo.context)
        when (key) {
            "Advanced.clearReadHistory" -> clearHistory(repo)
            "Advanced.clearExcludingLibrary" -> clearHistory(repo, true)
            "Advanced.clearNetworkCache" -> clearNetwork(repo)
            "Advanced.clearTrackedManga" -> tracks.restoreSnapshot(tracks.data.value.json().put("bindings", JSONArray()))
            "Advanced.resetSettings" -> resetSettings(repo)
            "Advanced.reset" -> {
                libraryMigrationMutex.withLock { withContext(Dispatchers.IO) {
                    ResetRequest.begin(repo.context)
                    app.monogatari.android.downloads.DownloadService.get(repo.context).cancelAll()
                    repo.work.cancelUniqueWork("library-refresh")
                    clearNetwork(repo)
                    repo.state.value.sources.filter { it.type == "wasm" }.forEach { repo.client.runtimes.remove(it.id) }
                    tracks.restoreSnapshot(app.monogatari.android.tracking.TrackingData().json())
                    app.monogatari.android.dictionary.DictionaryRepository.get(repo.context).resetSettings()
                    SourceLogStore.get(repo.context).setStreamUrl(null)
                    ResetRequest.clearFilesAndPreferences(repo.context)
                    repo.commitReset()
                    ResetRequest.finish(repo.context)
                    LibraryRefresh.schedule(repo)
                    app.monogatari.android.backup.CompleteBackupManager.schedule(repo)
                } }
            }
            else -> error("Unknown maintenance action: $key")
        }
    }
}
