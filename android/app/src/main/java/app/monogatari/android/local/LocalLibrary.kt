package app.monogatari.android.local

import android.net.Uri
import app.monogatari.android.data.*
import app.monogatari.android.downloads.ChapterExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

object LocalLibrary {
    suspend fun import(repo: AppRepository, uri: Uri, options: ImportOptions = ImportOptions()): Manga = withContext(Dispatchers.IO) {
        val imported = ArchiveImporter.import(repo.context, uri, options)
        var result = imported
        try {
            repo.change { state ->
                val old = state.manga.firstOrNull { it.sourceId == "local" &&
                    (options.mangaId?.let { id -> it.key == id } ?: it.title.equals(imported.title, true)) }
                val used=old?.chapters.orEmpty().mapNotNull(app.monogatari.android.reader.ChapterListing::number).toMutableSet()
                result = if (old == null) imported else old.copy(
                    chapters = old.chapters + imported.chapters.map { c ->
                        val metadata=JSONObject(c.metadata)
                        val number=if(metadata.optBoolean("localNumberAuto")){var next=1.0;while(next in used)next++;used+=next;metadata.put("chapterNumber",next);next}else c.number
                        c.copy(key = java.util.UUID.randomUUID().toString(), number=number,metadata=metadata.toString(),sourceOrder = old.chapters.size + c.sourceOrder)
                    },
                    cover = old.cover.ifBlank { imported.cover }, description = old.description.ifBlank { imported.description },
                    author = old.author.ifBlank { imported.author }, tags = (old.tags + imported.tags).distinct())
                state.copy(manga = state.manga.filterNot { it.id == result.id } + result)
            }
            result
        } catch (e: Exception) {
            cleanupUnreferenced(repo, imported.chapters, imported.cover); throw e
        }
    }
    private fun ownedRoot(repo: AppRepository, value: String): File? {
        val root = File(repo.context.filesDir, "local").canonicalFile
        val uri = Uri.parse(value); if (uri.scheme != "file") return null
        val path = uri.path ?: return null; val file = File(path).canonicalFile
        if (!file.path.startsWith(root.path + File.separator)) return null
        val component = file.path.removePrefix(root.path + File.separator).substringBefore(File.separator)
        return File(root, component).takeIf { it.canonicalFile.parentFile == root }
    }
    private fun refs(chapters: List<Chapter>, cover: String = "",metadata:String="{}") = chapters.flatMap { it.pages + listOf(it.html) } + listOf(cover,JSONObject(metadata).optString("originalCover"))
    private fun cleanupUnreferenced(repo: AppRepository, chapters: List<Chapter>, cover: String = "",metadata:String="{}") {
        val remaining = repo.state.value.manga.flatMap { refs(it.chapters,it.cover,it.metadata) }.mapNotNull { ownedRoot(repo, it) }.toSet()
        refs(chapters, cover,metadata).mapNotNull { ownedRoot(repo, it) }.distinct().filterNot { it in remaining }.forEach { it.deleteRecursively() }
    }
    suspend fun cleanupCover(repo:AppRepository,value:String)=withContext(Dispatchers.IO) {
        val uri=Uri.parse(value);if(uri.scheme!="file")return@withContext
        val root=File(repo.context.filesDir,"covers").canonicalFile;val target=uri.path?.let { File(it).canonicalFile } ?: return@withContext
        if(target.parentFile!=root)return@withContext
        if(repo.state.value.manga.none { manga -> refs(manga.chapters,manga.cover,manga.metadata).any { it==value } })target.delete()
    }
    suspend fun deleteChapter(repo: AppRepository, mangaId: String, key: String) = withContext(Dispatchers.IO) {
        val manga = repo.state.value.manga.firstOrNull { it.id == mangaId && it.sourceId == "local" } ?: return@withContext
        val chapter = manga.chapters.firstOrNull { it.key == key } ?: return@withContext
        repo.change { state -> state.copy(manga = state.manga.mapNotNull { m -> if (m.id != mangaId) m else {
            val chapters = m.chapters.filterNot { it.key == key }
            if (chapters.isEmpty()) null else m.copy(chapters = chapters, cover = if (m.cover in chapter.pages) chapters.asSequence().flatMap{it.pages.asSequence()}.firstOrNull { page ->
                Uri.parse(page).path?.substringAfterLast('.')?.lowercase() in setOf("jpg","jpeg","png","webp","gif","bmp","avif","heic","heif")
            }.orEmpty() else m.cover)
        } }) }
        cleanupUnreferenced(repo, listOf(chapter), manga.cover,manga.metadata);cleanupCover(repo,manga.cover);cleanupCover(repo,JSONObject(manga.metadata).optString("originalCover"))
    }
    suspend fun deleteManga(repo: AppRepository, mangaId: String) = withContext(Dispatchers.IO) {
        val manga = repo.state.value.manga.firstOrNull { it.id == mangaId && it.sourceId == "local" } ?: return@withContext
        repo.change { it.copy(manga = it.manga.filterNot { m -> m.id == mangaId }) }
        cleanupUnreferenced(repo, manga.chapters, manga.cover,manga.metadata);cleanupCover(repo,manga.cover);cleanupCover(repo,JSONObject(manga.metadata).optString("originalCover"))
    }
    suspend fun deleteAll(repo: AppRepository) = withContext(Dispatchers.IO) {
        val old = repo.state.value.manga.filter { it.sourceId == "local" }
        repo.change { it.copy(manga = it.manga.filterNot { m -> m.sourceId == "local" }) }
        old.forEach { cleanupUnreferenced(repo,it.chapters,it.cover,it.metadata);cleanupCover(repo,it.cover);cleanupCover(repo,JSONObject(it.metadata).optString("originalCover")) }
    }
    suspend fun export(repo: AppRepository, mangaId: String, key: String, uri: Uri) = withContext(Dispatchers.IO) {
        val manga = repo.state.value.manga.first { it.id == mangaId }; val chapter = manga.chapters.first { it.key == key }
        repo.context.contentResolver.openOutputStream(uri, "wt")?.use { ChapterExporter.write(repo.context, manga, chapter, it) } ?: error("无法创建文件")
    }
}
