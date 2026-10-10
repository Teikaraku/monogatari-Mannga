package app.monogatari.android.sources

import android.graphics.BitmapFactory
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.monogatari.android.reader.ChapterListing
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in real-service check, using an explicit route without changing device settings or library data. */
@RunWith(AndroidJUnit4::class)
class CopyNetworkInstrumentedTest {
    @Test fun bookstoreVolumesAndReadingWorkOnTheSelectedNetwork() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Live-service tests are opt-in", arguments.getString("copyNetwork") == "true")
        val route = arguments.getString("copyRoute") ?: "unspecified"
        val proxyPort = arguments.getString("copyProxyPort")?.toIntOrNull()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val responses = AtomicInteger()
        val http = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .apply { if (proxyPort != null) proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("10.0.2.2", proxyPort))) }.build()
            .newBuilder().addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (chain.request().url.encodedPath.endsWith("homeIndex")) {
                    val wire = response.peekBody(1_000_000).bytes()
                    val decoded = if (response.header("Content-Encoding") == "gzip")
                        java.util.zip.GZIPInputStream(wire.inputStream()).use { SourceHost.readBounded(it, 20 * 1024 * 1024) } else wire
                    val record = JSONObject().put("protocol", response.protocol.toString()).put("http", response.code).put("url", chain.request().url.toString())
                        .put("body", decoded.toString(Charsets.UTF_8))
                    val headers = JSONObject()
                    for (name in listOf("User-Agent", "Accept", "Accept-Language", "Accept-Encoding", "Cache-Control", "version", "platform", "region"))
                        headers.put(name, chain.request().header(name))
                    record.put("requestHeaders", headers)
                    File(context.cacheDir, "copy-home-$route-${responses.incrementAndGet()}.json").writeText(record.toString(2))
                }
                response
            }.build()
        val client = CopySourceClient(CopySource.configuration, http)
        val evidence = JSONObject().put("sdk", Build.VERSION.SDK_INT).put("model", Build.MODEL).put("route", route)
        val output = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "copy-network-$route.json")
        fun progress(stage: String) { evidence.put("stage", stage); output.writeText(evidence.toString(2)); android.util.Log.i("CopyNetwork", "$route: $stage") }
        try {
            val trace = http.newCall(Request.Builder().url("https://www.cloudflare.com/cdn-cgi/trace").build()).execute().use {
                assertEquals(200, it.code); it.body!!.string()
            }
            evidence.put("exitIp", trace.lineSequence().first { it.startsWith("ip=") }.removePrefix("ip="))
            progress("exit IP verified")
            val home = client.home()
            assertTrue(home.sections.isNotEmpty())
            evidence.put("homeSections", home.sections.size).put("homeFeatures", home.features.size).put("homeNotice", home.notice).put("homeDiagnostics", home.diagnostics)
            progress("homepage parsed")
            val results = client.catalog(CopyQuery(kind = "search", keyword = "金牌得主"))
            assertTrue(results.entries.isNotEmpty())
            evidence.put("searchResults", JSONArray(results.entries.map { it.title }))
            progress("search returned manga")
            val title = client.catalog(CopyQuery(kind = "search", keyword = "出包王女")).entries.first { it.key == "chubaowangnv" }
            val manga = client.details(title)
            val volumes = manga.chapters.filter { it.volume != null }.map { it.title }
            val expected = (18 downTo 1).map { "D%02d卷".format(it) } + (18 downTo 1).map { "第%02d卷".format(it) }
            assertEquals(expected, volumes)
            assertEquals("第01卷", ChapterListing.next(manga)?.title)
            evidence.put("chapters", manga.chapters.size).put("volumesDescending", JSONArray(volumes))
            progress("41 chapters and volume order checked")
            val slave = client.catalog(CopyQuery(kind = "search", keyword = "魔都精兵")).entries.first { it.key == "modujingbingdenuli" }
            val chapters = client.details(slave).chapters
            val chapter = chapters.first { it.title == "第188话" }
            val pages = client.pages(chapter)
            assertEquals(21, pages.size)
            progress("chapter 188 returned 21 pages")
            val images = JSONArray()
            for (url in listOf(pages.first(), pages.last())) {
                val bytes = http.newCall(client.imageRequest(url)).execute().use {
                    assertEquals(200, it.code)
                    SourceHost.readBounded(it.body!!.byteStream(), 20 * 1024 * 1024)
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                assertTrue("The reader must receive a real image", bounds.outWidth > 0 && bounds.outHeight > 0)
                images.put(JSONObject().put("bytes", bytes.size).put("width", bounds.outWidth).put("height", bounds.outHeight))
            }
            evidence.put("readPages", pages.size).put("images", images).put("passed", true)
        } catch (failure: Throwable) {
            evidence.put("passed", false).put("error", failure.toString())
            throw failure
        } finally {
            output.writeText(evidence.toString(2))
            http.connectionPool.evictAll()
        }
    }
}
