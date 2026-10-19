package app.monogatari.android.reader

import android.content.Context
import android.net.Uri
import app.monogatari.android.data.AppRepository
import app.monogatari.android.downloads.ArchivePageStore
import coil.ImageLoader
import coil.imageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** Only reproducible temporary reader images enter this LRU; local books and downloads do not. */
@OptIn(coil.annotation.ExperimentalCoilApi::class)
object ReaderPageCache {
    fun networkFile(context:Context,sourceId:String,url:String)=File(context.cacheDir,"reader-pages/${AppRepository.hash("$sourceId:$url")}.image")
    suspend fun materialize(context:Context,sourceId:String,url:String,load:suspend()->ByteArray):String=withContext(Dispatchers.IO) {
        val file=networkFile(context,sourceId,url)
        if(!file.exists() || file.length()==0L) {
            val bytes=load();currentCoroutineContext().ensureActive();check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
            val partial=File.createTempFile("page-",".writing",file.parentFile)
            try { partial.writeBytes(bytes);currentCoroutineContext().ensureActive();check(partial.renameTo(file)) { "Cannot cache reader page" } }
            finally { partial.delete() }
        }
        touch(context,file);Uri.fromFile(file).toString()
    }
    /** Evict only this page's reproducible data and all decoded/transformed variants; persistent inputs stay intact. */
    @Synchronized fun invalidate(context:Context,url:String,sourceId:String?=null,resolved:String?=null,loader:ImageLoader=context.imageLoader) {
        val data=linkedSetOf(url).apply { resolved?.takeIf(String::isNotBlank)?.let(::add) }
        val temporary=mutableListOf<File>()
        if(sourceId!=null && url.startsWith("http"))temporary+=networkFile(context,sourceId,url)
        if(ArchivePageStore.isArchive(url))temporary+=ArchivePageStore.cacheFile(context,url)
        temporary.forEach { data+=Uri.fromFile(it).toString() }
        val uriPaths=data.mapNotNull { value ->Uri.parse(value).takeIf { it.scheme=="file" }?.path }
        val files=uriPaths.map(::File)
        // Coil maps file URIs to File.path (optionally with :lastModified); preserve raw URI aliases as well.
        // The Windows JVM can normalize the leading slash/separators differently from Android Uri.path.
        val paths=(uriPaths+files.flatMap { listOf(it.path,it.canonicalPath) }).toSet()
        loader.memoryCache?.let { memory ->memory.keys.filter { key ->key.key in data || paths.any { key.key==it || key.key.startsWith("$it:") } }.forEach { memory.remove(it) } }
        data.forEach { loader.diskCache?.remove(it) }
        val cacheRoot=context.cacheDir.canonicalFile
        val folders=listOf("reader-pages","archive-pages").map { File(context.cacheDir,it).canonicalFile }
            .onEach { require(it.parentFile==cacheRoot) { "Reader cache escaped its root" } }.toSet()
        (temporary+files).distinctBy { it.canonicalPath }.forEach { file ->
            val canonical=file.canonicalFile
            if(canonical.parentFile?.let { it in folders }==true)check(!canonical.exists() || canonical.delete()) { "Cannot clear reader page cache" }
        }
    }
    @Synchronized fun clear(context:Context) {
        listOf("reader-pages","archive-pages","reader-ocr").forEach { name ->
            val folder=File(context.cacheDir,name).canonicalFile
            require(folder.parentFile==context.cacheDir.canonicalFile)
            folder.deleteRecursively()
        }
    }
    @Synchronized fun touch(context:Context,file:File) {
        file.setLastModified(System.currentTimeMillis())
        val files=listOf("reader-pages","archive-pages","reader-ocr").flatMap { folder ->File(context.cacheDir,folder).listFiles().orEmpty().filter { it.isFile && !it.name.endsWith(".writing") } }
        var bytes=files.sumOf{it.length()}
        files.filter { it!=file }.sortedBy{it.lastModified()}.forEach { cached ->
            if(bytes>500L*1024*1024) { val length=cached.length();if(cached.delete())bytes-=length }
        }
    }
}
