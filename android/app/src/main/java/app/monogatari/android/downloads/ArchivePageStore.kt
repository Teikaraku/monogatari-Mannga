package app.monogatari.android.downloads

import android.content.Context
import android.net.Uri
import app.monogatari.android.data.AppRepository
import app.monogatari.android.data.ArchiveImporter
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile

/** Persistent archive URLs contain only a content hash and an archive member, so backups are portable. */
object ArchivePageStore {
    fun isArchive(url: String) = url.startsWith("aidoku-archive://")
    private fun archive(context: Context, url: String): Pair<File, String> {
        val uri = Uri.parse(url); require(uri.host == "download") { "无效归档页面" }
        val id = uri.path.orEmpty().removePrefix("/"); require(id.matches(Regex("[a-f0-9]{64}"))) { "无效归档路径" }
        val entry = uri.getQueryParameter("entry") ?: error("缺少归档成员")
        return File(context.filesDir, "downloads/$id.cbz") to entry
    }
    fun urls(context: Context, id: String): List<String> = ZipFile(File(context.filesDir, "downloads/$id.cbz")).use { zip ->
        zip.entries().asSequence().filter { !it.isDirectory && !it.name.equals("ComicInfo.xml",true) && !it.name.endsWith(".desc.txt",true) }
            .map { it.name }.toList().sortedWith(ArchiveImporter.naturalOrder).map { name ->
                Uri.Builder().scheme("aidoku-archive").authority("download").appendPath(id).appendQueryParameter("entry", name).build().toString()
            }
    }
    fun bytes(context: Context, url: String): ByteArray {
        val (file, name) = archive(context, url)
        return ZipFile(file).use { zip -> val entry = zip.getEntry(name) ?: error("归档页面缺失")
            ByteArrayOutputStream().also { output -> zip.getInputStream(entry).use { ArchiveImporter.copyBounded(it, output, 50L * 1024 * 1024) } }.toByteArray()
        }
    }
    fun cacheFile(context:Context,url:String):File {
        val (_, name) = archive(context, url)
        val extension=name.substringAfterLast('.',"image").lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "image"
        return File(context.cacheDir, "archive-pages/${AppRepository.hash(url)}.$extension")
    }
    fun materialize(context: Context, url: String): String {
        val file=cacheFile(context,url)
        if (!file.exists()) { file.parentFile?.mkdirs(); val tmp = File.createTempFile("archive-", ".writing", file.parentFile)
            try { tmp.writeBytes(bytes(context, url)); check(tmp.renameTo(file)) } finally { tmp.delete() }
        }
        app.monogatari.android.reader.ReaderPageCache.touch(context,file)
        return Uri.fromFile(file).toString()
    }
}
