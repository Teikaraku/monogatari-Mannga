package app.monogatari.android.sources

import app.monogatari.android.data.Manga
import app.monogatari.android.data.SourceConfig
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CopySourceClientTest {
    private fun client(server: MockWebServer): CopySourceClient = CopySourceClient(
        SourceConfig("copy-fixture", "Fixture", CopySource.TYPE, server.url("/").toString()),
        OkHttpClient.Builder().followRedirects(false).build(), server.url("/").toString(), contentAddress = server.url("/").toString())
    private fun response(json: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(json)
    private fun comic(id: String) = """{"name":"Title $id","path_word":"$id","cover":"https://images.example/$id.jpg","author":[{"name":"Author"}]}"""

    @Test fun homeUnwrapsApiSectionsAndExcludesUnrelatedExternalBanners() {
        MockWebServer().use { server ->
            server.enqueue(response("""{"code":200,"results":{
                "recComics":{"list":[{"comic":${comic("one")}}]},
                "newComics":[{"comic":${comic("two")}}],
                "topicsList":{"list":[{"title":"Topic","path_word":"topic-one","cover":"/topic.jpg"}]},
                "banners":[{"type":6,"cover":"/advertisement.jpg"},{"type":1,"cover":"/feature.jpg","comic":${comic("one")}}]
            }}"""))
            val home = client(server).home()
            assertEquals(listOf("one", "two"), home.sections.flatMap { it.entries }.map { it.key })
            assertEquals(1, home.features.size); assertEquals("one", home.features.single().manga.key)
            assertEquals("topic-one", home.topics.single().key)
            assertEquals("/api/v3/h5/homeIndex", server.takeRequest().requestUrl!!.encodedPath)
        }
    }
    @Test fun searchEncodesKeywordsAndUsesReturnedPaginationCounts() {
        MockWebServer().use { server ->
            server.enqueue(response("""{"code":200,"results":{"list":[${comic("one")}],"total":32,"offset":30,"limit":30}}"""))
            val page = client(server).catalog(CopyQuery(kind = "search", keyword = "漫画 & author"), 2)
            assertTrue(page.more)
            val request = server.takeRequest().requestUrl!!
            assertEquals("漫画 & author", request.queryParameter("q"))
            assertEquals("30", request.queryParameter("offset"))
            assertEquals("/api/kb/web/searchcl/comics", request.encodedPath)
        }
    }
    @Test fun rankingKeepsPeriodAndAudienceSeparateFromSearch() {
        MockWebServer().use { server ->
            server.enqueue(response("""{"code":200,"results":{"list":[{"sort":1,"comic":${comic("one")}}],"total":1}}"""))
            val page = client(server).catalog(CopyQuery(kind = "ranking", rankPeriod = "month", audience = "female"))
            assertFalse(page.more)
            assertEquals(1, JSONObject(page.entries.single().metadata).getInt("copyRank"))
            val request = server.takeRequest().requestUrl!!
            assertEquals("month", request.queryParameter("date_type")); assertEquals("female", request.queryParameter("audience_type"))
        }
    }
    @Test fun topicComicPagesUseContentsEndpoint() {
        MockWebServer().use { server ->
            server.enqueue(response("""{"code":200,"results":{"list":[${comic("one")}],"total":1}}"""))
            val page = client(server).catalog(CopyQuery(kind = "topic", topic = "special"))
            assertEquals("one", page.entries.single().key)
            assertEquals("/api/v3/topic/special/contents", server.takeRequest().requestUrl!!.encodedPath)
        }
    }
    @Test fun finishedCatalogUsesProviderCollection() {
        MockWebServer().use { server ->
            server.enqueue(response("""{"code":200,"results":{"list":[],"total":0}}"""))
            client(server).catalog(CopyQuery(kind = "finished"))
            assertEquals("finish", server.takeRequest().requestUrl!!.queryParameter("top"))
        }
    }
    @Test fun restrictionsAndNullDataCannotBecomeSuccessfulEmptyDetails() {
        MockWebServer().use { server ->
            server.enqueue(response("""{"code":210,"message":"Official client required","results":{}}"""))
            server.enqueue(response("""{"code":200,"results":null}"""))
            repeat(2) {
                val error = runCatching { client(server).details(Manga("one", "copy-fixture", "Title")) }.exceptionOrNull()
                assertNotNull(error)
            }
        }
    }
    @Test fun chapterGroupsAreMergedByUploadTimeAndKeepSourceMetadata() {
        MockWebServer().use { server ->
            server.enqueue(response("""{"code":200,"results":{"comic":${comic("one")},"groups":{"default":{"name":"Edition A"},"extra":{"name":"Edition B"}}}}"""))
            server.enqueue(response("""{"code":200,"results":{"list":[{"uuid":"old","type":1,"name":"第1话","datetime_created":"2025-01-01"}],"total":1}}"""))
            server.enqueue(response("""{"code":200,"results":{"list":[{"uuid":"new","type":1,"name":"第2话","datetime_created":"2026-01-01","is_locked":true}],"total":1}}"""))
            val manga = client(server).details(Manga("one", "copy-fixture", "Title"))
            assertEquals(listOf("new", "old"), manga.chapters.map { it.key })
            assertEquals(listOf(0, 1), manga.chapters.map { it.sourceOrder })
            assertTrue(manga.chapters.first().locked)
            assertEquals("Edition B", manga.chapters.first().scanlator)
            assertEquals("one", JSONObject(manga.chapters.first().metadata).getString("copyMangaKey"))
        }
    }
    @Test fun repeatedChapterPageFailsInsteadOfLoopingForever() {
        MockWebServer().use { server ->
            server.enqueue(response("""{"code":200,"results":{"comic":${comic("one")},"groups":{"default":{}}}}"""))
            val repeated = """{"code":200,"results":{"list":[{"uuid":"same","name":"第1话"}],"total":100}}"""
            server.enqueue(response(repeated)); server.enqueue(response(repeated))
            assertNotNull(runCatching { client(server).details(Manga("one", "copy-fixture", "Title")) }.exceptionOrNull())
            assertEquals(3, server.requestCount)
        }
    }
    @Test fun pagePermutationRestoresReadingOrderAndRejectsDuplicates() {
        val content = JSONObject().put("contents", JSONArray(listOf("c", "a", "b").map { JSONObject().put("url", it) }))
            .put("words", JSONArray(listOf(2, 0, 1)))
        assertEquals(listOf("a", "b", "c"), CopySourceClient.orderedPages(content))
        content.put("words", JSONArray(listOf(0, 0, 1)))
        assertNotNull(runCatching { CopySourceClient.orderedPages(content) }.exceptionOrNull())
        content.put("words", JSONArray(listOf(0.5, 1, 2)))
        assertNotNull(runCatching { CopySourceClient.orderedPages(content) }.exceptionOrNull())
    }
    @Test fun chapterCaptionNumbersDistinguishVolumesAndExtras() {
        assertEquals(12.5 to null, CopySourceClient.captionNumbers("第12.5话", 1))
        assertEquals(null to 1.0, CopySourceClient.captionNumbers("全一卷", 2))
        assertEquals(123.0 to 2.0, CopySourceClient.captionNumbers("第2卷 第一百二十三话", 1))
        assertEquals(null to null, CopySourceClient.captionNumbers("番外2026", 3))
    }
    @Test fun imageRequestsDoNotSendApiHeadersOrAccountTokensToCdn() {
        MockWebServer().use { server ->
            val request = client(server).imageRequest("https://images.example/page.jpg")
            assertEquals(server.url("/").toString(), request.header("Referer"))
            assertNull(request.header("Authorization")); assertNull(request.header("version"))
        }
    }
    @Test fun chapterImagesUseTheWebDataServiceWithoutOfficialClientIdentity() {
        MockWebServer().use { browse -> MockWebServer().use { content ->
            content.enqueue(response("""{"code":200,"results":{"is_lock":false,"chapter":{"contents":[{"url":"https://images.example/1.webp"}]}}}"""))
            val source = SourceConfig("copy-fixture", "Fixture", CopySource.TYPE, browse.url("/").toString())
            val client = CopySourceClient(source, OkHttpClient(), browse.url("/").toString(), contentAddress = content.url("/").toString())
            val chapter = app.monogatari.android.data.Chapter("chapter-one", "Chapter", metadata = """{"copyMangaKey":"manga-one"}""")
            assertEquals(listOf("https://images.example/1.webp"), client.pages(chapter))
            val request = content.takeRequest()
            assertEquals("/api/v3/comic/manga-one/chapter/chapter-one", request.requestUrl!!.encodedPath)
            assertEquals("2024.4.28", request.getHeader("version"))
            assertEquals("Monogatari/0.9", request.getHeader("User-Agent"))
            assertNull(request.getHeader("x-auth-signature")); assertNull(request.getHeader("Authorization"))
            assertEquals(0, browse.requestCount)
        } }
    }
    @Test fun dateOnlyUploadsPreserveTheDisplayedCalendarDay() {
        MockWebServer().use { server ->
            server.enqueue(response("""{"code":200,"results":{"comic":${comic("one")},"groups":{"default":{}}}}"""))
            server.enqueue(response("""{"code":200,"results":{"list":[{"uuid":"chapter","name":"第1话","datetime_created":"2026-09-29"}],"total":1}}"""))
            val date = client(server).details(Manga("one", "copy-fixture", "Title")).chapters.single().dateUploaded
            assertEquals(java.time.LocalDate.of(2026, 9, 29), java.time.Instant.ofEpochMilli(date).atZone(java.time.ZoneId.systemDefault()).toLocalDate())
        }
    }
}
