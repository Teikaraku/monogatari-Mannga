package app.monogatari.android.library

import android.net.Uri
import app.monogatari.android.data.*
import app.monogatari.android.MonogatariViewModel
import app.monogatari.android.reader.ChapterListing
import app.monogatari.android.ui.Localization
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream

data class AppLink(val sourceId: String, val mangaKey: String? = null, val chapterKey: String? = null, val action: String? = null)
object IncomingLinks {
    /** `monogatari://` is the app's own scheme; `aidoku://` stays for links produced by sources and older backups. */
    val appSchemes = setOf("monogatari", "aidoku")
    fun appLink(uri: Uri): AppLink? {
        if (uri.scheme !in appSchemes || uri.host.isNullOrBlank() || uri.host.equals("addSourceList", true)) return null
        val parts = uri.pathSegments // Decode after splitting: keys may contain encoded slashes.
        return AppLink(uri.host!!, parts.getOrNull(0), parts.getOrNull(1), uri.getQueryParameter("action")?.takeIf { it in setOf("read", "readNext", "readLatest") })
    }
    suspend fun open(vm: MonogatariViewModel, uri: Uri) {
        if (uri.scheme in appSchemes && uri.host.equals("addSourceList", true)) {
            error(Localization.string(vm.repo.context, "UNAVAILABLE"))
        }
        val direct = appLink(uri)?.takeIf { link -> vm.state.value.sources.any { it.id == link.sourceId } }
        if (direct != null) {
            val key = direct.mangaKey ?: run { vm.navigate("source", direct.sourceId); return }
            val cached = vm.state.value.manga.firstOrNull { it.sourceId == direct.sourceId && it.key == key }
            val manga = cached ?: Manga(key, direct.sourceId, "")
            val loaded = vm.repo.refresh(manga)
            vm.markOpened(loaded.id)
            app.monogatari.android.tracking.TrackingRepository.get(vm.repo.context).syncOnOpen(vm.repo, loaded.id)
            vm.navigate("details", loaded.id)
            val target = when (direct.action) {
                "read" -> loaded.chapters.firstOrNull { it.key == direct.chapterKey }
                "readNext" -> ChapterListing.next(loaded)
                "readLatest" -> ChapterListing.listed(loaded).firstOrNull()
                else -> null
            }
            if (target != null) vm.read(loaded, target)
            else direct.chapterKey?.let { vm.pendingChapterFocus = it }
            return
        }
        val link = withContext(Dispatchers.IO) { vm.repo.client.resolveDeepLink(uri.toString()) }
            ?: error(Localization.string(vm.repo.context, "UNAVAILABLE"))
        if (link.manga != null) {
            val loaded = vm.repo.refresh(link.manga)
            vm.markOpened(loaded.id)
            app.monogatari.android.tracking.TrackingRepository.get(vm.repo.context).syncOnOpen(vm.repo, loaded.id)
            vm.navigate("details", loaded.id)
            link.chapterKey?.let { key -> loaded.chapters.firstOrNull { it.key == key }?.let { vm.read(loaded, it) } }
        } else { vm.pendingSourceListing = link.listing; vm.navigate("source", link.sourceId) }
    }
    /** An unnamed ZIP may be a source, portable backup, or comic; inspect bounded entry data. */
    fun fileKind(repo: AppRepository, uri: Uri): String {
        val resolver = repo.context.contentResolver
        val name = runCatching { resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } }.getOrNull() ?: uri.lastPathSegment.orEmpty()
        val extension = name.substringAfterLast('.', "").lowercase()
        val mime = resolver.getType(uri).orEmpty()
        if (extension == "aix" || mime == "application/x-aidoku-source") return "source"
        if (extension in setOf("aib", "json", "plist") || mime in setOf("application/json", "application/x-plist")) return "backup"
        if (extension in setOf("cbz", "epub")) return "comic"
        return runCatching { resolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use zipLoop@{ zip ->
                var bytes = 0L
                repeat(32) {
                    val entry = zip.nextEntry ?: return@zipLoop "comic"
                    if (entry.name == "main.wasm" || entry.name == "source.json") return@zipLoop "source"
                    if (entry.name == "state.json") return@zipLoop "backup"
                    val buffer = ByteArray(8192); var entryBytes = 0
                    while (true) { val read = zip.read(buffer); if (read < 0) break; bytes += read; entryBytes += read; if (bytes > 1024 * 1024 || entryBytes > 256 * 1024) return@zipLoop "comic" }
                }
                "comic"
            }
        } ?: "comic" }.getOrDefault("comic")
    }
}
