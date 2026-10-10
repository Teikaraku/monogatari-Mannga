package app.monogatari.android.sources

import app.monogatari.android.data.Manga
import app.monogatari.android.data.SourceConfig
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import app.monogatari.android.data.Chapter
import app.monogatari.android.reader.ChapterListing
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.net.ssl.SSLHandshakeException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CopySourceClientTest {
    @Test fun readingConnectionFailureUsesVerifiedContentServiceAndKeepsTheHealthyRoute() {
        MockWebServer().use { primary -> MockWebServer().use { backup ->
            val attempts = AtomicInteger()
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                if (chain.request().url.port == primary.port) { attempts.incrementAndGet(); throw SSLHandshakeException("fixture route failure") }
                chain.proceed(chain.request())
            }.build()
            repeat(2) { backup.enqueue(response("""{"code":200,"results":{"chapter":{"contents":[{"url":"https://images.example/page.webp"}]}}}""")) }
            val source = SourceConfig("copy-fixture", "Fixture", CopySource.TYPE, primary.url("/").toString())
            val client = CopySourceClient(source, http, primary.url("/").toString(), contentAddress = primary.url("/").toString(),
                contentAlternates = listOf(backup.url("/").toString()))
            for (id in listOf("188", "187", "188")) assertEquals(listOf("https://images.example/page.webp"), client.pages(Chapter(id, id, metadata = """{"copyMangaKey":"manga"}""")))
            assertEquals(2, attempts.get())
            assertEquals(2, backup.requestCount)
            val request = backup.takeRequest()
            assertEquals("/api/v3/comic/manga/chapter/188", request.requestUrl!!.encodedPath)
            assertEquals("3", request.requestUrl!!.queryParameter("platform"))
            assertEquals("2024.4.28", request.getHeader("version"))
            assertEquals("3", request.getHeader("dnts"))
            assertNull(request.getHeader("Authorization"))
        } }
    }

    @Test fun capturedPrimaryAndAlternateHomePayloadsRetainBannersAndRecommendations() {
        MockWebServer().use { server ->
            val client = client(server)
            for (name in listOf("primary", "alternate")) {
                val fixture = javaClass.getResourceAsStream("/copy-home-$name.json")!!.bufferedReader().use { it.readText() }
                val home = client.parseHome(JSONObject(fixture).getJSONObject("results"))
                assertEquals(5, home.features.size)
                assertTrue(home.sections.any { it.id == "recComics" })
                assertTrue(home.topics.isNotEmpty())
                assertTrue(home.rankings.isNotEmpty())
                assertNull(home.notice)
                if (name == "primary") assertEquals(5, home.sections.size)
                else assertEquals("本周更新", home.sections.first { it.id == "hotComics" }.title)
            }
        }
    }

    @Test fun persistentlyEmptyPrimaryUsesARealAlternateHomepageAndCompletesItsMissingShelves() {
        MockWebServer().use { primary -> MockWebServer().use { content ->
            val fixture = javaClass.getResourceAsStream("/copy-home-alternate.json")!!.bufferedReader().use { it.readText() }
            primary.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = response(if (request.requestUrl!!.encodedPath.endsWith("homeIndex"))
                    """{"code":200,"results":{"recComics":{"list":[]},"banners":[]}}"""
                    else """{"code":200,"results":{"list":[${comic("feed")}],"total":1}}""")
            }
            content.enqueue(response(fixture))
            val source = SourceConfig("copy-fixture", "Fixture", CopySource.TYPE, primary.url("/").toString())
            val home = CopySourceClient(source, OkHttpClient(), primary.url("/").toString(), contentAddress = content.url("/").toString()).home()
            assertEquals(5, home.features.size)
            assertTrue(home.sections.any { it.id == "newComics" } && home.sections.any { it.id == "finishComics" })
            assertEquals(setOf("day", "week", "month"), home.rankings.map { it.period }.toSet())
            assertTrue(home.diagnostics!!.contains("条目0"))
            assertEquals("已使用备用首页接口。", home.notice)
            assertEquals(1, content.requestCount)
            assertEquals(9, primary.requestCount)
        } }
    }
    @Test fun realToLoveVolumesKeepProviderSeriesOrderAndReaderSequence() {
        MockWebServer().use { server ->
            val fixture = javaClass.getResourceAsStream("/copy-tolove-chapters.json")!!.bufferedReader().use { it.readText() }
            server.enqueue(response("""{"code":200,"results":{"comic":${comic("chubaowangnv")},"groups":{"default":{"name":"默認"}}}}"""))
            server.enqueue(response(fixture))
            val manga = client(server).details(Manga("chubaowangnv", "copy-fixture", "出包王女"))
            val actual = manga.chapters.filter { it.volume != null }.map { it.title }
            val expected = (18 downTo 1).map { "D%02d卷".format(it) } + (18 downTo 1).map { "第%02d卷".format(it) }
            assertEquals(expected, actual)
            assertEquals("第01卷", ChapterListing.next(manga)?.title)
            val byNumber = manga.copy(metadata = """{"chapterFlags":3}""")
            val numbered = ChapterListing.sorted(byNumber).filter { it.volume != null }.map { it.title }
            assertEquals(expected.asReversed(), numbered)
            assertEquals(expected, ChapterListing.sorted(byNumber.copy(metadata = """{"chapterFlags":2}""")).filter { it.volume != null }.map { it.title })
            val byDate = manga.copy(metadata = """{"chapterFlags":4}""")
            assertEquals(manga.chapters.map { it.key }, ChapterListing.sorted(byDate).map { it.key })
        }
    }

    @Test fun searchNetworkFailureTriesVerifiedAlternateWithTheSameKeywordAndPage() {
        MockWebServer().use { primary -> MockWebServer().use { backup ->
            backup.enqueue(response("""{"code":200,"results":{"list":[${comic("one")}],"total":32,"offset":30,"limit":30}}"""))
            val calls = AtomicInteger()
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                if (chain.request().url.port == primary.port) { calls.incrementAndGet(); throw java.net.SocketTimeoutException("fixture connection timeout") }
                chain.proceed(chain.request())
            }.build()
            val source = SourceConfig("copy-fixture", "Fixture", CopySource.TYPE, primary.url("/").toString())
            val client = CopySourceClient(source, http, primary.url("/").toString(), contentAddress = primary.url("/").toString(),
                searchAddresses = listOf(primary.url("/").toString(), backup.url("/").toString()))
            val page = client.catalog(CopyQuery(kind = "search", keyword = " 金牌得主 & 漫画 "), 2)
            assertTrue(page.more)
            assertEquals(2, calls.get())
            val request = backup.takeRequest()
            assertEquals("/api/kb/web/searchcl/comics", request.requestUrl!!.encodedPath)
            assertEquals("金牌得主 & 漫画", request.requestUrl!!.queryParameter("q"))
            assertEquals("30", request.requestUrl!!.queryParameter("offset"))
            assertNull(request.getHeader("Authorization"))
        } }
    }

    @Test fun reading429WaitsThenRecoversAndRepeatedRequestsUseTheShortCache() {
        MockWebServer().use { server ->
            var clock = 100_000L
            val waits = mutableListOf<Long>()
            val source = SourceConfig("copy-fixture", "Fixture", CopySource.TYPE, server.url("/").toString())
            val client = CopySourceClient(source, OkHttpClient(), server.url("/").toString(), contentAddress = server.url("/").toString(),
                now = { clock }, pause = { waits += it; clock += it })
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "2").setBody("{}"))
            server.enqueue(response("""{"code":200,"results":{"chapter":{"contents":[{"url":"https://images.example/page.webp"}]}}}"""))
            val chapter = Chapter("188", "第188话", metadata = """{"copyMangaKey":"modujingbingdenuli"}""")
            assertEquals(listOf("https://images.example/page.webp"), client.pages(chapter))
            assertEquals(client.pages(chapter), client.pages(chapter))
            assertEquals(listOf(2000L), waits)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun longRetryAfterStopsRequestsAcrossChaptersAndDoesNotChangeIpOrService() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "90").setBody("{}"))
            val source = SourceConfig("copy-fixture", "Fixture", CopySource.TYPE, server.url("/").toString())
            val client = CopySourceClient(source, OkHttpClient(), server.url("/").toString(), contentAddress = server.url("/").toString(),
                now = { 100_000 }, pause = { error("A long cooldown must not block the reader") })
            for (key in listOf("188", "187")) {
                val error = runCatching { client.pages(Chapter(key, key, metadata = """{"copyMangaKey":"manga"}""")) }.exceptionOrNull()
                assertTrue(error is CopyRateLimitFailure)
                assertTrue(error!!.message!!.contains("90 秒"))
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun unlabelledRateLimitsHaveBoundedBackoffAndJson429IsNotAnEmptySuccess() {
        MockWebServer().use { server ->
            var clock = 100_000L
            val waits = mutableListOf<Long>()
            repeat(3) { server.enqueue(response("""{"code":429,"results":null}""")) }
            val source = SourceConfig("copy-fixture", "Fixture", CopySource.TYPE, server.url("/").toString())
            val client = CopySourceClient(source, OkHttpClient(), server.url("/").toString(), contentAddress = server.url("/").toString(),
                now = { clock }, pause = { waits += it; clock += it })
            assertTrue(runCatching { client.pages(Chapter("188", "188", metadata = """{"copyMangaKey":"manga"}""")) }.exceptionOrNull() is CopyRateLimitFailure)
            assertEquals(listOf(2000L, 4000L), waits)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun retryAfterHttpDateUsesTheServersClockAndNeverBusyLoopsOnZero() {
        assertEquals(90_000L, CopyRequestPolicy.retryDelay("Sat, 10 Oct 2026 11:01:30 GMT", "Sat, 10 Oct 2026 11:00:00 GMT", 0))
        assertEquals(1000L, CopyRequestPolicy.retryDelay("0", null, 0))
        assertNull(CopyRequestPolicy.retryDelay("not-a-date", null, 0))
    }

    @Test fun pageCacheExpiresAndConcurrentReadersShareOneSuccessfulRequest() {
        MockWebServer().use { server ->
            var clock = 100_000L
            repeat(2) { server.enqueue(response("""{"code":200,"results":{"chapter":{"contents":[{"url":"https://images.example/page.webp"}]}}}""")) }
            val source = SourceConfig("copy-fixture", "Fixture", CopySource.TYPE, server.url("/").toString())
            val client = CopySourceClient(source, OkHttpClient(), server.url("/").toString(), contentAddress = server.url("/").toString(), now = { clock })
            val chapter = Chapter("188", "188", metadata = """{"copyMangaKey":"manga"}""")
            val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
            try {
                val results = pool.invokeAll((1..4).map { java.util.concurrent.Callable { client.pages(chapter) } }).map { it.get() }
                assertTrue(results.all { it == results.first() })
                assertEquals(1, server.requestCount)
                clock += 300_001
                client.pages(chapter)
                assertEquals(2, server.requestCount)
            } finally { pool.shutdownNow() }
        }
    }

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
    @Test fun emptyAndUnrecognizedHomeResponsesCannotBecomeBlankSuccess() {
        MockWebServer().use { server ->
            val client = client(server)
            for (json in listOf("{}", """{"recComics":{"list":[]}}""", """{"unexpected":{"list":[${comic("one")}]}}""")) {
                assertNotNull(runCatching { client.parseHome(JSONObject(json)) }.exceptionOrNull())
            }
        }
    }
    @Test fun emptyHomeRecoversFromIndependentCatalogAndRankingEndpoints() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    return when (path) {
                        "/api/v3/h5/homeIndex" -> response("""{"code":200,"results":{}}""")
                        "/api/v3/topics" -> response("""{"code":200,"results":{"list":[],"total":0}}""")
                        else -> response("""{"code":200,"results":{"list":[${comic("recovered")}],"total":1}}""")
                    }
                }
            }
            val home = client(server).home()
            assertEquals(listOf("newComics", "hotComics", "finishComics"), home.sections.map { it.id })
            assertEquals(listOf("day", "week", "month"), home.rankings.map { it.period })
            assertTrue(home.diagnostics!!.contains("空对象"))
            assertNotNull(home.notice)
            assertEquals(9, server.requestCount)
        }
    }
    @Test fun unavailableHomeAndCatalogShowAnErrorInsteadOfAnEmptyPage() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = response("""{"code":200,"results":{}}""")
            }
            val failure = runCatching { client(server).home() }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(failure!!.message!!.contains("首页加载失败"))
        }
    }
    @Test fun nullListEntriesDoNotDiscardOtherValidHomeCards() {
        MockWebServer().use { server ->
            val home = client(server).parseHome(JSONObject("""{"recComics":{"list":[null,${comic("one")},4]}}"""))
            assertEquals("one", home.sections.single().entries.single().key)
        }
    }
    @Test fun transientEmptyHomeRetriesBeforeLosingTheOriginalFeeds() {
        MockWebServer().use { server ->
            server.enqueue(response("""{"code":200,"results":{}}"""))
            server.enqueue(response("""{"code":200,"results":{"recComics":{"list":[${comic("one")}]}}}"""))
            val home = client(server).home()
            assertEquals("one", home.sections.single().entries.single().key)
            assertNull(home.notice)
            assertEquals(2, server.requestCount)
            server.takeRequest()
            val retry = server.takeRequest().requestUrl!!
            assertEquals("true", retry.queryParameter("_update"))
            assertNotNull(retry.queryParameter("_"))
        }
    }
    @Test fun transientTlsFailureRetriesWithFreshConnectionsBeforeChangingService() {
        MockWebServer().use { server ->
            server.enqueue(response("""{"code":200,"results":{"list":[${comic("one")}],"total":1}}"""))
            val calls = AtomicInteger()
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                if (calls.incrementAndGet() == 1) throw SSLHandshakeException("temporary handshake failure")
                chain.proceed(chain.request())
            }.build()
            val source = SourceConfig("copy-fixture", "Fixture", CopySource.TYPE, server.url("/").toString())
            val client = CopySourceClient(source, http, server.url("/").toString(), contentAddress = server.url("/").toString())
            assertEquals("one", client.catalog(CopyQuery()).entries.single().key)
            assertEquals(2, calls.get())
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun tlsFailuresUseKnownHttpsServiceAndPreserveQueryAndHeaders() {
        MockWebServer().use { browse -> MockWebServer().use { content ->
            content.enqueue(response("""{"code":200,"results":{"list":[${comic("one")}],"total":1}}"""))
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                if (chain.request().url.port == browse.port) throw SSLHandshakeException("fixture TLS failure")
                chain.proceed(chain.request())
            }.build()
            val source = SourceConfig("copy-fixture", "Fixture", CopySource.TYPE, browse.url("/").toString())
            val client = CopySourceClient(source, http, browse.url("/").toString(), contentAddress = content.url("/").toString())
            assertEquals("one", client.catalog(CopyQuery(kind = "ranking", rankPeriod = "month", audience = "female")).entries.single().key)
            val request = content.takeRequest()
            assertEquals("month", request.requestUrl!!.queryParameter("date_type"))
            assertEquals("female", request.requestUrl!!.queryParameter("audience_type"))
            assertEquals("2024.4.28", request.getHeader("version"))
            assertEquals("no-cache", request.getHeader("Cache-Control"))
            assertNull(request.getHeader("Authorization"))
        } }
    }
    @Test fun twoTlsFailuresRemainVisibleAndDoNotDisableCertificateVerification() {
        MockWebServer().use { browse -> MockWebServer().use { content ->
            val http = OkHttpClient.Builder().addInterceptor { throw SSLHandshakeException("fixture TLS failure") }.build()
            val source = SourceConfig("copy-fixture", "Fixture", CopySource.TYPE, browse.url("/").toString())
            val client = CopySourceClient(source, http, browse.url("/").toString(), contentAddress = content.url("/").toString())
            val failure = runCatching { client.catalog(CopyQuery()) }.exceptionOrNull()
            assertTrue(failure!!.message!!.contains("安全连接失败（TLS）"))
            assertTrue(failure.message!!.contains("备用 HTTPS"))
        } }
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
