package app.monogatari.android.reader

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.monogatari.android.data.AppRepository
import app.monogatari.android.downloads.ArchivePageStore
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@OptIn(coil.annotation.ExperimentalCoilApi::class)
class ReaderPageCacheTest {
    private val context:Context get()=ApplicationProvider.getApplicationContext()
    @Test fun reloadEvictsAllPageVariantsAndMakesARealFreshHttpRequest()=runBlocking {
        val server=MockWebServer();server.start()
        val folder=File(context.cacheDir,"reader-cache-test-${UUID.randomUUID()}")
        val memory=MemoryCache.Builder(context).maxSizeBytes(1024*1024).build()
        val disk=DiskCache.Builder().directory(folder).maxSizeBytes(1024*1024).build()
        val loader=ImageLoader.Builder(context).memoryCache(memory).diskCache(disk).build()
        val source=UUID.randomUUID().toString();val url=server.url("/page").toString()
        try {
            server.enqueue(MockResponse().setBody("first actual response"));server.enqueue(MockResponse().setBody("second actual response"))
            val client=OkHttpClient()
            suspend fun page()=ReaderPageCache.materialize(context,source,url) { client.newCall(Request.Builder().url(url).build()).execute().use { it.body!!.bytes() } }
            val resolved=page();val path=Uri.parse(resolved).path!!
            assertEquals("first actual response",File(path).readText());assertEquals(resolved,page());assertEquals(1,server.requestCount)
            val bitmap=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888)
            val full=MemoryCache.Key(path);val left=MemoryCache.Key(path,mapOf("coil#transformation_0" to "left"));val right=MemoryCache.Key("$path:${File(path).lastModified()}",mapOf("coil#transformation_0" to "right"))
            val mapped=File(path)
            val mappedFull=MemoryCache.Key(mapped.path);val mappedHalf=MemoryCache.Key("${mapped.path}:${mapped.lastModified()}",mapOf("coil#transformation_0" to "right"))
            val pageKeys=listOf(full,left,right,mappedFull,mappedHalf).distinct()
            val other=MemoryCache.Key("another page")
            (pageKeys+other).forEach { memory[it]=MemoryCache.Value(bitmap) }
            val editor=disk.openEditor(url)!!;File(editor.metadata.toString()).writeText("headers");File(editor.data.toString()).writeText("old HTTP cache");editor.commit()
            assertNotNull(disk.openSnapshot(url)?.also{it.close()})
            ReaderPageCache.invalidate(context,url,source,resolved,loader)
            assertFalse(File(path).exists());pageKeys.forEach { assertNull("Page cache key survived: $it",memory[it]) };assertNotNull(memory[other]);assertNull(disk.openSnapshot(url))
            assertEquals(resolved,page());assertEquals("second actual response",File(path).readText());assertEquals(2,server.requestCount)
        } finally { ReaderPageCache.invalidate(context,url,source,loader=loader);loader.shutdown();folder.deleteRecursively();server.shutdown() }
    }
    @Test fun archiveReloadReextractsChangedMemberAndNeverDeletesCompletedArchive() {
        val id=AppRepository.hash(UUID.randomUUID().toString());val archive=File(context.filesDir,"downloads/$id.cbz").apply { parentFile!!.mkdirs() }
        fun write(value:String) { ZipOutputStream(archive.outputStream()).use { zip ->zip.putNextEntry(ZipEntry("0001.txt"));zip.write(value.toByteArray());zip.closeEntry() } }
        try {
            write("before");val url=ArchivePageStore.urls(context,id).single();val resolved=ArchivePageStore.materialize(context,url)
            assertEquals("before",File(Uri.parse(resolved).path!!).readText())
            write("after");ReaderPageCache.invalidate(context,url,resolved=resolved)
            assertTrue(archive.isFile);assertFalse(File(Uri.parse(resolved).path!!).exists())
            assertEquals("after",File(Uri.parse(ArchivePageStore.materialize(context,url)).path!!).readText())
            assertTrue(archive.isFile)
        } finally { ReaderPageCache.invalidate(context,ArchivePageStore.urls(context,id).single());archive.delete() }
    }
    @Test fun reloadOnlyClearsDecodedCacheForLocalBooksAndDownloads() {
        val local=File(context.filesDir,"local/cache-safety-${UUID.randomUUID()}/page.png").apply { parentFile!!.mkdirs();writeText("original book") }
        val downloaded=File(context.filesDir,"downloads/cache-safety-${UUID.randomUUID()}/0001.png").apply { parentFile!!.mkdirs();writeText("complete download") }
        try {
            for(file in listOf(local,downloaded)) { ReaderPageCache.invalidate(context,Uri.fromFile(file).toString());assertTrue(file.isFile) }
            assertEquals("original book",local.readText());assertEquals("complete download",downloaded.readText())
            val escape="file://${context.cacheDir}/reader-pages/../../files/local/${local.parentFile!!.name}/page.png"
            ReaderPageCache.invalidate(context,escape,resolved=Uri.fromFile(local).toString());assertEquals("original book",local.readText())
        } finally { local.parentFile!!.deleteRecursively();downloaded.parentFile!!.deleteRecursively() }
    }
}
