package app.monogatari.android.tracking

import android.net.Uri
import kotlinx.coroutines.runBlocking
import okhttp3.RequestBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrackingTest {
    private fun body(body: RequestBody?): String = Buffer().also { body?.writeTo(it) }.readUtf8()
    @Test fun rfc7636PkceVectorAndProviderSpecificMethods() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", TrackerOAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk", "S256"))
        assertEquals("verifier", TrackerOAuth.challenge("verifier", "plain"))
        val account = TrackerAccount("mal", TrackerProvider.MYANIMELIST)
        val url = Uri.parse(TrackerOAuth.authorize(account, "state1", "verifier"))
        assertEquals("state1", url.getQueryParameter("state")); assertEquals("plain", url.getQueryParameter("code_challenge_method")); assertEquals("verifier", url.getQueryParameter("code_challenge"))
        assertTrue(TrackerOAuth.random().length >= 43); assertNotEquals(TrackerOAuth.random(), TrackerOAuth.random())
    }
    @Test fun oauthFragmentsAndExactCallbackOriginAreHandled() {
        val uri = Uri.parse("aidoku://anilist-auth#access_token=abc&state=s%26x&expires_in=100")
        assertEquals("s&x", TrackerOAuth.parameters(uri)["state"])
        assertTrue(TrackerOAuth.callbackMatches("aidoku://anilist-auth", uri))
        assertFalse(TrackerOAuth.callbackMatches("aidoku://anilist-auth", Uri.parse("aidoku://anilist-auth.evil")))
        assertFalse(TrackerOAuth.callbackMatches("aidoku://anilist-auth", Uri.parse("https://anilist-auth")))
    }
    @Test fun aniListUsesRawScoreAndSeparateFuzzyDates() = runBlocking {
        var payload = JSONObject()
        val api = TrackerApi { _, _, method, body -> assertEquals("POST", method); payload = JSONObject(body(body)); JSONObject("""{"data":{"SaveMediaListEntry":{"id":555}}}""") }
        val state = api.update(TrackerAccount("al", TrackerProvider.ANILIST), "123", TrackState(TrackStatus.COMPLETED, score = 85.0, chapter = 12.9, volume = 3, startDate = "2026-01-02", finishDate = "2026-10-08"))
        val vars = payload.getJSONObject("variables")
        assertEquals(85, vars.getInt("score")); assertEquals(12, vars.getInt("progress")); assertEquals(1, vars.getJSONObject("started").getInt("month")); assertEquals(10, vars.getJSONObject("finished").getInt("month")); assertEquals("555", state.entryId)
    }
    @Test fun malNormalizesScoreAndRereadingWithoutChangingStatus() = runBlocking {
        var patch = ""; var url = ""
        val api = TrackerApi { _, u, method, b -> url = u; assertEquals("PATCH", method); patch = body(b); JSONObject() }
        api.update(TrackerAccount("mal", TrackerProvider.MYANIMELIST), "7", TrackState(TrackStatus.REREADING, score = 70.0, chapter = 3.5, startDate = "2026-10-01", finishDate = ""))
        assertTrue(url.endsWith("manga/7/my_list_status")); assertTrue(patch.contains("is_rereading=true")); assertTrue(patch.contains("status=reading")); assertTrue(patch.contains("score=7")); assertTrue(patch.contains("num_chapters_read=3")); assertTrue(patch.contains("finish_date="))
    }
    @Test fun bangumiUsesCollectionCodesAndDoesNotLoseVolumeProgress() = runBlocking {
        var body = JSONObject()
        val api = TrackerApi { _, _, method, b -> assertEquals("POST", method); body = JSONObject(body(b)); JSONObject() }
        api.update(TrackerAccount("bgm", TrackerProvider.BANGUMI), "7", TrackState(TrackStatus.PAUSED, 90.0, 12.0, 4, registered = true))
        assertEquals(4, body.getInt("type")); assertEquals(9, body.getInt("rate")); assertEquals(12, body.getInt("ep_status")); assertEquals(4, body.getInt("vol_status"))
    }
    @Test fun shikimoriImportedRateIdResolvesTargetAndPatchesTheRate() = runBlocking {
        val requests = mutableListOf<String>()
        val api = TrackerApi { _, url, _, _ -> requests += url; if (url.contains("user_rates")) JSONObject("""{"id":9,"target_id":33,"status":"watching","chapters":5,"volumes":1,"score":8}""") else JSONObject("""{"chapters":20,"volumes":5}""") }
        val account = TrackerAccount("shiki", TrackerProvider.SHIKIMORI)
        val s = api.read(account, "9", "9")
        assertTrue(requests[0].endsWith("/api/v2/user_rates/9")); assertTrue(requests[1].endsWith("/api/mangas/33")); assertEquals(80.0, s.score, 0.0)
        api.update(account, "9", s.copy(score = 50.0)); assertTrue(requests.last().endsWith("/api/v2/user_rates/9"))
    }
    @Test fun mangaBakaFinishDateIsNotWrittenOverStartDate() {
        val payload = TrackerApi.mangaBakaPayload(TrackState(startDate = "2026-01-02", finishDate = "2026-10-08"))
        assertEquals("2026-01-02T00:00:00.000Z", payload.getString("start_date")); assertEquals("2026-10-08T00:00:00.000Z", payload.getString("finish_date"))
        assertTrue(TrackerApi.mangaBakaPayload(TrackState()).isNull("finish_date"))
    }
    @Test fun allStatusesRoundTripAndOffsetCapsProgress() {
        listOf(TrackerProvider.ANILIST, TrackerProvider.SHIKIMORI, TrackerProvider.MANGABAKA).forEach { p -> TrackStatus.entries.forEach { s -> assertEquals(s, TrackerApi.status(p, TrackerApi.statusName(p, s))) } }
        assertEquals(0.0, TrackingRepository.applyOffset(3.0, -4), 0.0); assertEquals(10.0, TrackingRepository.applyOffset(9.0, 3, 10), 0.0); assertEquals(11.5, TrackingRepository.applyOffset(10.5, 1), 0.0)
    }
    @Test fun scoreFormatsMatchNativeTrackerScalesAndAnilistOptions() {
        val mal = TrackerAccount("mal", TrackerProvider.MYANIMELIST)
        assertEquals("8", TrackingScores.display(mal, 80.0)); assertEquals(80.0, TrackingScores.parse(mal, "8"), 0.0)
        val decimal = TrackerAccount("al", TrackerProvider.ANILIST, scoreFormat = "POINT_10_DECIMAL")
        assertEquals("8.5", TrackingScores.display(decimal, 85.0)); assertEquals(85.0, TrackingScores.parse(decimal, "8.5"), 0.0)
        val stars = TrackerAccount("al", TrackerProvider.ANILIST, scoreFormat = "POINT_5")
        assertEquals(listOf(0.0, 10.0, 30.0, 50.0, 70.0, 90.0), TrackingScores.options(stars).map { it.second })
        assertEquals(100, TrackingScores.scale(TrackerAccount("mb", TrackerProvider.MANGABAKA)))
    }
    @Test fun komgaVolumeModeUsesVolumeFieldsAndRawNumberSort() = runBlocking {
        var sent = JSONObject()
        val api = TrackerApi { _, _, method, body -> if (method == "GET") JSONObject("""{"lastReadContinuousNumberSort":3.0,"maxNumberSort":8.0}""") else { sent = JSONObject(body(body)); JSONObject() } }
        val account = TrackerAccount("k", TrackerProvider.KOMGA, baseUrl = "https://server", useVolumes = true)
        val state = api.read(account, "series")
        assertEquals(3, state.volume); assertEquals(8, state.totalVolumes); assertEquals(0.0, state.chapter, 0.0)
        api.update(account, "series", state.copy(volume = 4)); assertEquals(4.0, sent.getDouble("lastBookNumberSortRead"), 0.0)
    }
    @Test fun serverPageAdaptersPreserveZeroBasedLocalProgress() = runBlocking {
        var body = JSONObject(); var path = ""
        val api = TrackerApi { _, u, _, b -> path = u; body = if (b == null) JSONObject() else JSONObject(body(b)); if (u.contains("chapter-info")) JSONObject("""{"libraryId":1,"volumeId":2,"pages":20}""") else if (u.contains("graphql")) JSONObject("""{"data":{"updateChapter":{"chapter":{"id":7}}}}""") else JSONObject() }
        api.page(TrackerAccount("komga", TrackerProvider.KOMGA, baseUrl = "https://server"), "1", ChapterRemoteProgress("7", 4, 20, false))
        assertTrue(path.endsWith("books/7/read-progress")); assertEquals(5, body.getInt("page"))
        api.page(TrackerAccount("kavita", TrackerProvider.KAVITA, baseUrl = "https://server"), "1", ChapterRemoteProgress("7", 4, 20, true)); assertEquals(20, body.getInt("pageNum"))
        api.page(TrackerAccount("suwayomi", TrackerProvider.SUWAYOMI, baseUrl = "https://server"), "1", ChapterRemoteProgress("7", 4, 20, false)); assertEquals(4, body.getJSONObject("variables").getJSONObject("patch").getInt("lastPageRead"))
    }
    @Test fun graphQlErrorCannotBeReportedAsSuccessfulUpdate() = runBlocking {
        val api = TrackerApi { _, _, _, _ -> JSONObject("""{"errors":[{"message":"invalid token"}]}""") }
        try { api.update(TrackerAccount("al", TrackerProvider.ANILIST), "123", TrackState()); fail("Expected API failure") } catch (e: java.io.IOException) { assertTrue(e.message!!.contains("invalid token")) }
    }
    @Test fun serverProgressRoundTripsDoNotRegressKavitaOrEraseKomgaFirstPageHistory() = runBlocking {
        var payload = JSONObject(); var method = ""
        val api = TrackerApi { account, _, verb, body -> method = verb; if (verb != "GET") payload = JSONObject(body(body).ifBlank { "{}" })
            when {
                account.provider == TrackerProvider.KAVITA && verb == "GET" -> JSONObject("""{"_array":[{"chapters":[{"id":7,"pagesRead":4,"pages":20}]}]}""")
                account.provider == TrackerProvider.SUWAYOMI -> JSONObject("""{"data":{"chapters":{"nodes":[{"id":7,"lastPageRead":0,"pageCount":20,"isRead":true}]}}}""")
                else -> JSONObject()
            }
        }
        assertEquals(4, api.pages(TrackerAccount("kavita", TrackerProvider.KAVITA, baseUrl = "https://server"), "1").single().page)
        assertEquals(19, api.pages(TrackerAccount("suwayomi", TrackerProvider.SUWAYOMI, baseUrl = "https://server"), "1").single().page)
        val komga = TrackerAccount("komga", TrackerProvider.KOMGA, baseUrl = "https://server")
        api.page(komga, "1", ChapterRemoteProgress("7", 0, 20, false, readAt = 1000)); assertEquals("PATCH", method); assertEquals(1, payload.getInt("page"))
        api.page(komga, "1", ChapterRemoteProgress("7", 0, 20, false)); assertEquals("DELETE", method)
    }
}
