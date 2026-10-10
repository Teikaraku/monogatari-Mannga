package app.monogatari.android.sources

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import app.monogatari.android.data.Manga
import com.dylibso.chicory.runtime.ByteBufferMemory
import com.dylibso.chicory.wasm.types.MemoryLimits
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SourceRuntimeTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun prefs(id: String = "test"): SourcePreferences = SourcePreferences(context, id, object : SourcePreferenceBackend {
        val data = mutableMapOf<String, String>()
        override fun get(key: String) = data[key]
        override fun set(key: String, value: String) { data[key] = value }
        override fun remove(key: String) { data.remove(key) }
    })
    private fun fixture(): InstalledSource {
        val directory = File(context.cacheDir, "fixture-${UUID.randomUUID()}").apply { mkdirs() }
        for (file in listOf("main.wasm", "source.json")) javaClass.classLoader!!.getResourceAsStream("source-fixture/$file")!!.use { File(directory, file).writeBytes(it.readBytes()) }
        return InstalledSource(directory, JSONObject(File(directory, "source.json").readText()))
    }
    private fun host(): SourceHost = SourceHost(context, "test", OkHttpClient(), prefs()).apply { memory = ByteBufferMemory(MemoryLimits(2)) }
    private fun SourceHost.write(value: String, offset: Long = 1024): LongArray { val data = value.toByteArray(); memory.write(offset.toInt(), data); return longArrayOf(offset, data.size.toLong()) }

    @Test fun browserCredentialsFollowActualRedirectedOrigin() {
        assertTrue(browserCredentialsAllowed("same-origin","https://example.test/path","https://example.test/other"))
        assertFalse(browserCredentialsAllowed("same-origin","http://example.test/","https://example.test/"))
        assertFalse(browserCredentialsAllowed("same-origin","https://example.test/","https://example.test:8443/"))
        assertFalse(browserCredentialsAllowed("same-origin","https://example.test/","https://other.test/"))
        assertFalse(browserCredentialsAllowed("omit","https://example.test/","https://example.test/"))
        assertTrue(browserCredentialsAllowed("include","https://example.test/","https://other.test/"))
        assertFalse(browserCredentialsAllowed("same-origin","null","https://example.test/"))
    }
    @Test fun globalSearchLanguagesUseSelectedSourceDefaultsIncludingEmptySelection() {
        val source=fixture();source.info.put("languages",JSONArray().put("en").put("ja"));source.manifest.put("config",JSONObject().put("languageSelectType","single"))
        val preferences=prefs();preferences.set("language","ja");assertEquals(listOf("ja"),sourceSelectedLanguages(context,source,preferences))
        source.manifest.getJSONObject("config").put("languageSelectType","multiple");preferences.set("languages",JSONArray().put("en"));assertEquals(listOf("en"),sourceSelectedLanguages(context,source,preferences))
        preferences.set("languages",JSONArray());assertTrue(sourceSelectedLanguages(context,source,preferences).isEmpty())
    }
    @Test fun legacyLanguageSelectionKeepsSdkRawValuesAndMapsSearchCodes() {
        val source=fixture();source.info.remove("languages");source.info.put("lang","multi");source.manifest.put("languageSelectType","single").put("languages",JSONArray().put(JSONObject().put("code","ja").put("value","jp-raw").put("isDefault",true)).put(JSONObject().put("code","ko").put("value","kr-raw")))
        val preferences=prefs();val setting=sourceLanguageSettings(context,source,true).single().getJSONArray("items").getJSONObject(0)
        assertEquals("multi-single-select",setting.getString("type"));assertEquals("languages",setting.getString("key"));assertEquals("languageChange",setting.getString("notification"))
        assertEquals(listOf("ja"),sourceSelectedLanguages(context,source,preferences));assertEquals("jp-raw",(preferences.get("languages") as JSONArray).getString(0))
        preferences.set("languages",JSONArray().put("kr-raw"));assertEquals(listOf("ko"),sourceSelectedLanguages(context,source,preferences))
    }
    @Test fun browseMetadataUsesLegacyCodesRatingAndDefaultField() {
        val legacy=JSONObject("""{"info":{"id":"legacy","name":"Legacy","lang":"multi","nsfw":2},"languages":[{"code":"zz","value":"z-raw"},{"code":"xy","value":"x-raw","default":true}]}""")
        assertEquals(listOf("zz","xy"),sourceSupportedLanguages(legacy));assertEquals(2,sourceContentRating(legacy))
        val source=InstalledSource(File(context.filesDir,"metadata-fixture"),legacy)
        assertEquals(listOf("xy"),sourceSelectedLanguages(context,source,prefs()))
        val flattened=JSONObject("""{"languages":[{"code":"ja","value":"jp-raw"},{"code":"ko","value":"kr-raw"}],"nsfw":1}""")
        assertEquals(listOf("ja","ko"),sourceSupportedLanguages(flattened));assertEquals(1,sourceContentRating(flattened))
        val modern=JSONObject("""{"info":{"languages":["en","ja"],"contentRating":0,"nsfw":2}}""")
        assertEquals(listOf("en","ja"),sourceSupportedLanguages(modern));assertEquals(0,sourceContentRating(modern))
    }
    @Test fun logsReloadRecentMultilineEntriesAndDoNotReuseAnotherApplicationContext() {
        val folder=File(context.cacheDir,"logs-${UUID.randomUUID()}").apply { mkdirs() };val file=File(folder,"Logs/current.txt").apply { parentFile!!.mkdirs() }
        file.writeText((0 until 5001).joinToString("\n") { "[01/01 01:00:00.000] [INFO] entry-$it" }+"\n[01/01 01:00:01.000] [source-name] plain\ncontinued\n")
        val rows=SourceLogStore.readRecent(file);assertEquals(5000,rows.size);assertEquals("entry-2",rows.first().message);assertEquals("[source-name] plain\ncontinued",rows.last().message);assertEquals("",rows.last().level)
        fun wrapped(directory:File):Context=object:android.content.ContextWrapper(context) { override fun getApplicationContext():Context=this;override fun getFilesDir():File=directory }
        val first=wrapped(folder);val store=SourceLogStore.get(first);assertEquals(rows.last().message,store.entries.value.last().message)
        val other=wrapped(File(context.cacheDir,"logs-other-${UUID.randomUUID()}").apply { mkdirs() });val different=SourceLogStore.get(other);assertNotSame(store,different);assertTrue(different.entries.value.isEmpty())
        assertNotSame(store,SourceLogStore.get(first));assertEquals(rows.last().message,SourceLogStore.get(first).entries.value.last().message)
    }

    @Test fun postcardMatchesIndependentKnownVectors() {
        assertArrayEquals(byteArrayOf(0, 1, 2, 0x80.toByte(), 1, 0x81.toByte(), 1), PostcardWriter().signed(0).signed(-1).signed(1).signed(64).signed(-65).bytes())
        val r = PostcardReader(byteArrayOf(3, 0xE6.toByte(), 0xBA.toByte(), 0x90.toByte(), 1, 0, 0, 0xC0.toByte(), 0x3F, 2, 1, 65, 1, 66))
        assertEquals("源", r.string()); assertEquals(1.5f, r.option { r.float() }); assertEquals(listOf("A", "B"), r.strings()); r.end()
        for (n in listOf(Long.MIN_VALUE, -1000000L, 0L, Long.MAX_VALUE)) assertEquals(n, PostcardReader(PostcardWriter().signed(n).bytes()).signed())
        assertThrows(IllegalArgumentException::class.java) { PostcardReader(byteArrayOf(4, 65)).string() }
        assertThrows(IllegalArgumentException::class.java) { PostcardReader(ByteArray(10) { 0xFF.toByte() }).unsigned() }
    }

    @Test fun originalPinnedWasmExecutesHomeAndReportsTrap() {
        val source = fixture()
        WasmSourceRuntime(context, source, OkHttpClient(), prefs()).use { runtime ->
            assertFalse(runtime.legacy)
            assertEquals(-1,runtime.host.memory.grow(Int.MAX_VALUE))
            val home = runtime.home()
            assertTrue(home.has("components"))
            assertThrows(SourceRuntimeException::class.java) { runtime.details(Manga("", "test", "")) }
        }
        // A fresh VM can run after a trap, matching Source.restart().
        WasmSourceRuntime(context, source, OkHttpClient(), prefs()).use { assertTrue(it.home().has("components")) }
    }
    @Test fun independentLegacyWasmExecutesOriginalImportsSearchDetailsAndPages() {
        val directory = File(context.cacheDir, "legacy-${UUID.randomUUID()}").apply { mkdirs() }
        for(file in listOf("main.wasm", "source.json")) javaClass.classLoader!!.getResourceAsStream("legacy-fixture/$file")!!.use { File(directory,file).writeBytes(it.readBytes()) }
        val preferences = prefs("legacy.integration")
        WasmSourceRuntime(context, InstalledSource(directory, JSONObject(File(directory,"source.json").readText())), OkHttpClient(), preferences).use { runtime ->
            assertTrue(runtime.legacy); assertEquals(43, (preferences.get("legacy.initialized") as Number).toInt())
            assertEquals(1L, runtime.javaClass.getDeclaredMethod("invoke", String::class.java, LongArray::class.java).apply { isAccessible=true }.invoke(runtime,"verify_legacy_host",longArrayOf()))
            val search = runtime.search("query",1); assertTrue(search.second); val manga = search.first.single(); assertEquals("Legacy 漫画",manga.title); assertEquals("manga/key",manga.key); assertEquals("Writer",manga.author); assertEquals("Artist",manga.artist); assertEquals("ltr",manga.viewer); assertEquals(1,manga.contentRating)
            assertFalse(runtime.search("query",2).second)
            val detail = runtime.details(manga); assertEquals("ltr",detail.viewer); val chapter=detail.chapters.single(); assertEquals("chapter/key",chapter.key); assertEquals(7.0,chapter.number,0.0); assertEquals(2.0,chapter.volume!!,0.0); assertEquals("Scanlator",chapter.scanlator); assertEquals(1234500L,chapter.dateUploaded)
            val pages=runtime.pages(chapter); assertEquals("https://example.test/chapter/1.jpg",pages[0]); assertEquals("Legacy text page",File(android.net.Uri.parse(pages[1]).path!!).readText())
        }
    }

    @Test fun hostParsesHtmlAndResolvesRelativeUrls() {
        val h = host()
        val html = h.write("<article><a href='../chapter'>漫画</a><a>other</a></article>")
        val uri = h.write("https://example.test/manga/one", 2048)
        val document = h.call("html", "parse_with_uri", html + uri)
        val query = h.write("article a", 3072)
        val selected = h.call("html", "select", longArrayOf(document) + query)
        assertEquals(2, h.call("html", "size", longArrayOf(selected)))
        val first = h.call("html", "first", longArrayOf(selected))
        val attr = h.write("abs:href", 4096)
        assertEquals("https://example.test/chapter", h.get(h.call("html", "attr", longArrayOf(first) + attr).toInt()))
        assertEquals("漫画 other", h.get(h.call("html", "text", longArrayOf(selected)).toInt()))
        assertEquals("", h.string(-1, 0))
    }

    @Test fun legacyMissingHandlesNullsAndJsonMatchOriginalAbi() {
        val host=host().apply { legacy=true }
        assertEquals(-1L,host.call("std","copy",longArrayOf(-1)))
        val array=host.store(listOf(null,42L)).toLong()
        assertEquals(-1L,host.call("std","array_get",longArrayOf(array,-1)))
        assertEquals(-1L,host.call("std","array_get",longArrayOf(array,2)))
        val nullValue=host.call("std","array_get",longArrayOf(array,0));assertTrue(nullValue>=0);assertEquals(0L,host.call("std","typeof",longArrayOf(nullValue)))
        val objectId=host.store(JSONObject().put("present",JSONObject.NULL)).toLong()
        assertEquals(-1L,host.call("std","object_get",longArrayOf(objectId)+host.write("missing")))
        val present=host.call("std","object_get",longArrayOf(objectId)+host.write("present"));assertTrue(present>=0);assertEquals(0L,host.call("std","typeof",longArrayOf(present)))
        assertEquals(-1L,host.call("std","object_keys",longArrayOf(array)))
        assertEquals(-1L,host.call("json","parse",host.write("123")))
        assertTrue(host.call("json","parse",host.write("{\"a\":[null,1]}"))>=0)
        val trueId=host.store(true).toLong();assertEquals(1.0,java.lang.Double.longBitsToDouble(host.call("std","read_float",longArrayOf(trueId))),0.0)
        val invalidDate=host.store("invalid-date").toLong();assertEquals(-1.0,java.lang.Double.longBitsToDouble(host.call("std","read_date_string",longArrayOf(invalidDate)+host.write("yyyy-MM-dd",2048)+longArrayOf(0,0,0,0))),0.0)
    }

    @Test fun defaultsPreserveTypesAndHostPostcardEncoding() {
        val h = host(); val preferences = h.preferences
        preferences.set("flag", true); preferences.set("integer", -42L); preferences.set("float", 1.25f); preferences.set("list", JSONArray(listOf("a", "b")))
        fun read(key: String): PostcardReader = PostcardReader(h.get(h.call("defaults", "get", h.write(key)).toInt()) as ByteArray)
        assertTrue(read("flag").bool()); assertEquals(-42, read("integer").signed()); assertEquals(1.25f, read("float").float(), 0f); assertEquals(listOf("a", "b"), read("list").strings())
        assertEquals(-2, h.call("defaults", "get", h.write("unknown")))
        preferences.initialize(JSONArray("[{\"type\":\"group\",\"items\":[{\"key\":\"flag\",\"default\":false},{\"key\":\"nested\",\"default\":\"ok\"}]}]"))
        assertEquals(true, preferences.get("flag")); assertEquals("ok", preferences.get("nested"))
    }

    @Test fun networkHeadersCookiesAndLegacyReadCursor() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("abcdef").addHeader("Set-Cookie", "token=one; Path=/"))
            server.enqueue(MockResponse().setBody("second"))
            val h = host().apply { legacy = true }
            val descriptor = h.call("net", "init", longArrayOf(0)); val url = h.write(server.url("/first").toString())
            assertEquals(0, h.call("net", "set_url", longArrayOf(descriptor) + url)); assertEquals(0, h.call("net", "send", longArrayOf(descriptor)))
            assertEquals(6, h.call("net", "get_data_size", longArrayOf(descriptor))); assertEquals(0, h.call("net", "get_data", longArrayOf(descriptor, 5000, 2)))
            assertEquals("ab", h.memory.readString(5000, 2)); assertEquals(4, h.call("net", "get_data_size", longArrayOf(descriptor)))
            assertEquals(0, h.call("net", "get_data", longArrayOf(descriptor, 5000, 4))); assertEquals("cdef", h.memory.readString(5000, 4))
            h.fetch(Request.Builder().url(server.url("/second")).build())
            assertTrue(server.takeRequest().getHeader("User-Agent")!!.contains("Mozilla")); assertEquals("token=one", server.takeRequest().getHeader("Cookie"))
            assertNull(h.withBrowserHeaders(Request.Builder().url("https://unrelated.test/image").build()).header("Cookie"))
        }
    }

    @Test fun canvasCopiesAndReordersRealImagePixels() {
        val h = host()
        val image = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888).apply { setPixel(0, 0, Color.RED); setPixel(1, 0, Color.BLUE) }
        val id = h.store(image).toLong()
        fun f(value: Float) = java.lang.Float.floatToRawIntBits(value).toLong()
        val surface = h.call("canvas", "new_context", longArrayOf(f(2f), f(1f)))
        assertEquals(0, h.call("canvas", "copy_image", longArrayOf(surface, id, f(1f), f(0f), f(1f), f(1f), f(0f), f(0f), f(1f), f(1f))))
        assertEquals(0, h.call("canvas", "copy_image", longArrayOf(surface, id, f(0f), f(0f), f(1f), f(1f), f(1f), f(0f), f(1f), f(1f))))
        val output = h.get(h.call("canvas", "get_image", longArrayOf(surface)).toInt()) as Bitmap
        assertEquals(Color.BLUE, output.getPixel(0, 0)); assertEquals(Color.RED, output.getPixel(1, 0))
    }

    @Test fun sourcePackagesRejectTraversalAndDoNotReplaceWrongIdentity() {
        val source = fixture(); val manager = SourceRuntimeManager(context)
        fun zip(extra: String? = null): ByteArray = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
            for (name in listOf("source.json", "main.wasm")) { zip.putNextEntry(ZipEntry("Payload/$name")); zip.write(File(source.directory, name).readBytes()); zip.closeEntry() }
            if (extra != null) { zip.putNextEntry(ZipEntry(extra)); zip.write(byteArrayOf(1)); zip.closeEntry() }
        } }.toByteArray()
        assertThrows(IllegalArgumentException::class.java) { manager.install(zip("../../escape").inputStream()) }
        val installed = manager.install(zip().inputStream(), expectedId = "test", expectedVersion = 1)
        val original = File(installed.directory, "main.wasm").readBytes()
        assertThrows(IllegalArgumentException::class.java) { manager.install(zip().inputStream(), expectedId = "wrong") }
        assertArrayEquals(original, File(manager.installed("test").directory, "main.wasm").readBytes())
        val interrupted=manager.install(zip().inputStream(),retainPrevious=true)
        assertNotNull(interrupted.rollbackDirectory)
        SourceRuntimeManager(context).recoverPendingInstalls(mapOf("test" to 0))
        assertArrayEquals(original,File(manager.installed("test").directory,"main.wasm").readBytes())
        val committed=manager.install(zip().inputStream(),retainPrevious=true)
        SourceRuntimeManager(context).recoverPendingInstalls(mapOf("test" to 1))
        assertFalse(committed.rollbackDirectory!!.exists())
    }
}
