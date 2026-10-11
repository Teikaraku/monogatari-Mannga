package app.monogatari.android.update

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppUpdateFeedTest {
    private val feed = """{"android":{"version":"0.9.3","build":6,"minBuild":5,"url":"https://manga.monogatari.top/app-update/releases/0.9.3/Monogatari-0.9.3.apk",
        "mirrors":["https://github.com/Teikaraku/monogatari-reader/releases/download/v0.9.3/Monogatari-0.9.3.apk","http://insecure.example/a.apk"],
        "size":1024,"sha256":"ABCDEF","notes":"修复"},"ios":{"version":"0.9.3","build":6,"minBuild":0,"url":"https://manga.monogatari.top/app-update/releases/0.9.3/Monogatari-0.9.3.ipa"}}"""

    @Test fun eachPlatformReadsItsOwnEntryAndOnlyKeepsHttpsDownloads() {
        val android = AppUpdateFeed.parse(JSONObject(feed), "android")!!
        assertEquals(6, android.build)
        assertEquals(2, android.urls.size)
        assertTrue(android.urls.all { it.startsWith("https://") })
        assertEquals("abcdef", android.sha256)
        assertEquals(0, AppUpdateFeed.parse(JSONObject(feed), "ios")!!.minBuild)
        assertNull(AppUpdateFeed.parse(JSONObject("""{"ios":{}}"""), "android"))
        assertNull(AppUpdateFeed.parse(JSONObject("""{"android":{"build":6,"url":"http://insecure.example/a.apk"}}"""), "android"))
    }

    @Test fun onlyBuildsBelowTheMinimumMustUpdate() {
        val release = AppUpdateFeed.parse(JSONObject(feed), "android")!!
        assertTrue(release.forced(4))
        assertFalse(release.forced(5))
        assertFalse(AppUpdateFeed.parse(JSONObject(feed), "ios")!!.forced(1))
    }

    @Test fun anUnreachableServerFallsBackToTheNextAddress() {
        MockWebServer().use { primary -> MockWebServer().use { backup ->
            primary.enqueue(MockResponse().setResponseCode(502))
            backup.enqueue(MockResponse().setBody(feed))
            val release = AppUpdateFeed.fetch(OkHttpClient(), "android", listOf(primary.url("/latest.json").toString(), backup.url("/latest.json").toString()))
            assertEquals("0.9.3", release!!.version)
            assertEquals("no-cache", backup.takeRequest().getHeader("Cache-Control"))
        } }
    }

    @Test fun noReachableAddressIsAnErrorRatherThanUpToDate() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            assertTrue(runCatching { AppUpdateFeed.fetch(OkHttpClient(), "android", listOf(server.url("/latest.json").toString())) }.exceptionOrNull() is IOException)
        }
    }

    @Test fun packageChecksumIsLowercaseSha256() {
        val file = File(Files.createTempDirectory("update").toFile(), "a.apk").apply { writeText("monogatari") }
        assertEquals("b94eeb1329de816478ac0ea0e301d4380547d63708c8dcefffab659e7ce9eec0", AppUpdateController.sha256(file))
    }
}
