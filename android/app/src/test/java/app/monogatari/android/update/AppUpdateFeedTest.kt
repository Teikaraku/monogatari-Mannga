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
    private val feed = """{"android":{"version":"0.9.3","build":6,"minBuild":5,"url":"https://updates.example.invalid/releases/0.9.3/Monogatari-0.9.3.apk",
        "mirrors":["https://github.com/Teikaraku/monogatari-Mannga/releases/download/v0.9.3/Monogatari-0.9.3.apk","http://insecure.example/a.apk"],
        "size":1024,"sha256":"ABCDEF","notes":"修复"},"ios":{"version":"0.9.3","build":6,"minBuild":0,"url":"https://github.com/Teikaraku/monogatari-Mannga/releases/download/v0.9.3/Monogatari-0.9.3.ipa"}}"""

    private val githubDownload = "https://github.com/Teikaraku/monogatari-Mannga/releases/download/v0.9.3/Monogatari-0.9.3.apk"
    private val externalDownload = "https://updates.example.invalid/releases/v0.9.3/reader.apk"

    private fun release(primary: String, vararg mirrors: String): AppRelease? {
        val entry = JSONObject().put("version", "0.9.3").put("build", 6).put("url", primary)
            .put("mirrors", org.json.JSONArray(mirrors.toList()))
        return AppUpdateFeed.parse(JSONObject().put("android", entry), "android")
    }

    @Test fun publicBuildUsesOnlyTheGitHubReleaseFeed() {
        assertEquals(listOf("https://github.com/Teikaraku/monogatari-Mannga/releases/latest/download/latest.json"), AppUpdateFeed.addresses)
    }

    @Test fun eachPlatformReadsItsOwnEntryAndOnlyKeepsProjectDownloads() {
        val android = AppUpdateFeed.parse(JSONObject(feed), "android")!!
        assertEquals(6, android.build)
        assertEquals(listOf(githubDownload), android.urls)
        assertEquals("abcdef", android.sha256)
        assertEquals(0, AppUpdateFeed.parse(JSONObject(feed), "ios")!!.minBuild)
        assertNull(AppUpdateFeed.parse(JSONObject("""{"ios":{}}"""), "android"))
        assertNull(AppUpdateFeed.parse(JSONObject("""{"android":{"build":6,"url":"http://insecure.example/a.apk"}}"""), "android"))
    }

    @Test fun githubPrimaryIgnoresExternalMirrors() {
        assertEquals(listOf(githubDownload), release(githubDownload, externalDownload, githubDownload)!!.urls)
    }

    @Test fun externalPrimaryStillKeepsAValidGithubMirror() {
        assertEquals(listOf(githubDownload), release(externalDownload, githubDownload)!!.urls)
    }

    @Test fun allExternalCandidatesProduceNoRelease() {
        assertNull(release(externalDownload, "https://another.example.invalid/reader.apk"))
    }

    @Test fun currentAndHistoricalRepositoryNamesAreCaseInsensitive() {
        for (repository in listOf("monogatari-Mannga", "monogatari-mannga", "MONOGATARI-MANNGA", "monogatari-reader", "MONOGATARI-READER")) {
            val url = githubDownload.replace("Teikaraku/monogatari-Mannga", "TEIKARAKU/$repository")
            assertEquals(listOf(url), release(url)!!.urls)
        }
    }

    @Test fun malformedAuthoritiesAndOtherRepositoriesAreRejected() {
        val invalid = listOf(
            githubDownload.replace("https://", "http://"),
            githubDownload.replace("github.com", "github.com.evil.invalid"),
            githubDownload.replace("github.com", "user@github.com"),
            githubDownload.replace("github.com", "@github.com"),
            githubDownload.replace("github.com", "user:password@github.com"),
            githubDownload.replace("github.com", "github.com:8443"),
            githubDownload.replace("Teikaraku", "OtherOwner"),
            githubDownload.replace("monogatari-Mannga", "another-repo"),
            githubDownload.replace("monogatari-Mannga", "monogatari-reader-other"),
            githubDownload.replace("monogatari-Mannga", "monogatari-Mannga-other"),
            githubDownload.replace("releases/download", "releases/latest/download"),
            githubDownload.replace("v0.9.3/Monogatari-0.9.3.apk", "v0.9.3/"),
            githubDownload.replace("v0.9.3/Monogatari-0.9.3.apk", "../reader.apk"),
            githubDownload.replace("v0.9.3/Monogatari-0.9.3.apk", "%2e%2e/reader.apk"),
            githubDownload.replace("Monogatari-0.9.3.apk", "bad%2Freader.apk"),
            githubDownload.replace("Monogatari-0.9.3.apk", "bad%5Creader.apk"),
        )
        invalid.forEach { assertNull(it, release(it)) }
    }

    @Test fun defaultHttpsPortAndEncodedAssetNamesAreAllowed() {
        val allowed = githubDownload.replace("github.com", "github.com:443")
            .replace("Monogatari-0.9.3.apk", "%E7%89%A9%E8%AF%AD.apk")
        assertEquals(listOf(allowed), release(allowed)!!.urls)
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
