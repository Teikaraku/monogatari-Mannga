package app.monogatari.android.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.dd.plist.PropertyListParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    @Test fun iosBackupPreservesIdentifiersProgressAndCategories() {
        val fixture = JSONObject("""{
            "date": 1720000000, "manga": [{"id":"series-1","sourceId":"en.example","title":"Title","status":1,"nsfw":0,"viewer":0}],
            "library":[{"mangaId":"series-1","sourceId":"en.example","categories":["Favorites"],"dateAdded":1700000000}],
            "chapters":[{"id":"chapter-1","mangaId":"series-1","sourceId":"en.example","title":"Chapter 1","chapter":1,"lang":"en","sourceOrder":0}],
            "history":[{"chapterId":"chapter-1","mangaId":"series-1","sourceId":"en.example","progress":7,"total":20,"completed":false,"dateRead":1720000000}],
            "categories":[{"title":"Favorites","sort":0}], "trackItems":[{"trackerId":"anilist","id":"123"}]
        }""")
        val state = BackupCodec.fromIOS(fixture)
        val manga = state.manga.single()
        assertEquals("en.example::series-1", manga.id); assertTrue(manga.inLibrary)
        assertEquals(listOf("Favorites"), manga.categories)
        assertEquals(6, manga.chapters.single().page); assertEquals(20, manga.chapters.single().total)
        assertEquals(1720000000000, manga.chapters.single().readAt)
        val exported = BackupCodec.toIOS(state)
        assertEquals("123", exported.getJSONArray("trackItems").getJSONObject(0).getString("id"))
        assertEquals("en.example", exported.getJSONArray("library").getJSONObject(0).getString("sourceId"))
        assertEquals(7, exported.getJSONArray("history").getJSONObject(0).getInt("progress"))
        val iosRequired = listOf("lastOpened", "lastUpdated", "dateAdded", "mangaId", "sourceId")
        iosRequired.forEach { assertTrue(exported.getJSONArray("library").getJSONObject(0).has(it)) }
    }
    @Test fun binaryPropertyListCanBeDecoded() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?><plist version="1.0"><dict><key>manga</key><array/><key>date</key><date>2026-10-08T00:00:00Z</date></dict></plist>"""
        val objectValue = PropertyListParser.parse(xml.toByteArray())
        val bytes = ByteArrayOutputStream(); PropertyListParser.saveAsBinary(objectValue, bytes)
        assertTrue(bytes.toByteArray().toString(Charsets.US_ASCII).startsWith("bplist"))
        assertTrue(PropertyListParser.parse(bytes.toByteArray()).toJavaObject() is Map<*, *>)
    }
    @Test fun naturalOrderHandlesLargeNumbersAndPageNumbers() {
        val sorted = listOf("10.png", "2.png", "1.png", "12345678901234567890.png").sortedWith(ArchiveImporter.naturalOrder)
        assertEquals(listOf("1.png", "2.png", "10.png", "12345678901234567890.png"), sorted)
    }
    @Test fun archiveNamesCannotEscapeImportDirectory() {
        val zip = File(context.cacheDir, "sample.cbz")
        ZipOutputStream(zip.outputStream()).use {
            listOf("../../escaped.png", "pages/10.png", "pages/2.png").forEach { name ->
                it.putNextEntry(ZipEntry(name)); it.write(byteArrayOf(1, 2, 3)); it.closeEntry()
            }
        }
        val manga = ArchiveImporter.import(context, Uri.fromFile(zip))
        assertEquals(3, manga.chapters.single().pages.size)
        manga.chapters.single().pages.forEach { assertTrue(File(Uri.parse(it).path!!).canonicalPath.startsWith(File(context.filesDir, "local").canonicalPath)) }
        assertFalse(File(context.filesDir.parentFile, "escaped.png").exists())
    }
    @Test fun importSizeLimitStopsStreaming() {
        try { ArchiveImporter.copyBounded(ByteArrayInputStream(ByteArray(100)), ByteArrayOutputStream(), 50); fail("Expected rejection") }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("限制")) }
    }
    @Test fun komgaClientMatchesOriginalSearchAndChapterEndpoints() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"content":[{"id":"s1","name":"Folder","metadata":{"title":"Comic","status":"ONGOING","summary":"Summary","authors":[{"name":"Author"}],"genres":["Drama"]}}],"totalPages":2}"""))
            val client = SourceClient(SecretStore(context))
            val source = SourceConfig("test", "Test", "komga", server.url("/").toString())
            val (manga, next) = client.search(source, "Comic")
            assertTrue(next); assertEquals("Comic", manga.single().title)
            val search = server.takeRequest()
            assertEquals("/api/v1/series/list?page=0&size=40&sort=metadata.titleSort%2Casc", search.path)
            assertEquals("Comic", JSONObject(search.body.readUtf8()).getString("fullTextSearch"))
            server.enqueue(MockResponse().setBody("""[{"number":1,"mediaType":"image/jpeg"},{"number":2,"mediaType":"image/tiff"}]"""))
            val pages = client.pages(source, Chapter("b1", "Chapter"))
            assertTrue(pages[0].endsWith("/api/v1/books/b1/pages/1")); assertTrue(pages[1].endsWith("/pages/2?convert=png"))
            assertEquals("/api/v1/books/b1/pages", server.takeRequest().path)
        }
    }
    @Test fun failedAuthenticationDoesNotAddASource() = runBlocking {
        val repo = AppRepository(context)
        repo.change { AppState() }
        val initialSources = repo.state.value.sources
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            try { repo.addSource("Broken", "komga", server.url("/").toString(), "", ""); fail("Expected authentication failure") }
            catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("登录失败")) }
            assertEquals(initialSources, repo.state.value.sources)
        }
    }
    @Test fun readerProgressIsSavedAndIncognitoSuppressesIt() = runBlocking {
        val repo = AppRepository(context)
        repo.change { AppState(manga = listOf(Manga("book", "local", "Book", chapters = listOf(Chapter("c1", "Chapter", total = 20)), inLibrary = true))) }
        repo.progress("local::book", "c1", 7, 20)
        val persisted = AppDatabase(context).use { it.load() }
        assertEquals(7, persisted.manga.single().chapters.single().page)
        repo.change { it.copy(preferences = it.preferences.copy(incognito = true)) }
        repo.progress("local::book", "c1", 19, 20)
        assertEquals(7, repo.state.value.manga.single().chapters.single().page)
        assertFalse(repo.state.value.manga.single().chapters.single().completed)
    }
}
