package app.monogatari.android.library

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.monogatari.android.MonogatariViewModel
import app.monogatari.android.data.AppRepository
import app.monogatari.android.data.AppState
import app.monogatari.android.data.Chapter
import app.monogatari.android.data.Manga
import app.monogatari.android.data.SourceConfig
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [app.monogatari.android.backup.PosixAtomicFileShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class NavigationRestoreTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    @Before fun initializeWorkers() {
        if (runCatching { androidx.work.WorkManager.getInstance(context) }.isFailure)
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
    }
    private fun awaitMain(message: String, diagnostics: () -> String = { "" }, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("$message ${diagnostics()}")
    }
    @Test fun explicitVocabularyPageOpensItsVisiblePageWithoutRewritingTheSavedBookmark() = runBlocking {
        val repo = AppRepository(context)
        val chapter = Chapter("entry", "Cached vocabulary source", pages = List(10) { "file:///cached-$it.png" },
            total = 10, page = 7, readAt = 1000, downloaded = true,
            metadata = JSONObject().put("scrollPosition", .75).put("splitIndex", 1).put("splitSourceIndex", 7).toString())
        val manga = Manga("vocabulary-source", "local", "Vocabulary source", chapters = listOf(chapter))
        repo.change { AppState(manga = listOf(manga), sources = listOf(SourceConfig.local)) }
        val model = MonogatariViewModel(repo)
        model.read(manga, chapter, 2)
        awaitMain("The explicit vocabulary source page did not open") { model.screen == "reader" && !model.busy }
        assertEquals(2, model.readerVisiblePage); assertEquals(2, model.readerChapter!!.page)
        assertFalse(JSONObject(model.readerChapter!!.metadata).has("scrollPosition"))
        assertFalse(JSONObject(model.readerChapter!!.metadata).has("splitIndex"))
        assertEquals(chapter, repo.state.value.manga.single().chapters.single())
        model.back(); model.read(manga, chapter)
        awaitMain("Ordinary reading did not keep the saved bookmark") { model.screen == "reader" && !model.busy }
        assertEquals(chapter, model.readerChapter); assertEquals(7, model.readerVisiblePage)
    }
    @Test fun completedChapterReopensAtTheBeginningWhileAnExplicitSourcePageStillWins() = runBlocking {
        val repo = AppRepository(context)
        val chapter = Chapter("completed", "Reread source", pages = List(10) { "file:///reread-$it.png" },
            total = 10, page = 9, completed = true, downloaded = true, readAt = 1000,
            metadata = JSONObject().put("scrollPosition", 1.0).put("splitIndex", 1).put("splitSourceIndex", 9).toString())
        val manga = Manga("reread-source", "local", "Reread source", chapters = listOf(chapter))
        repo.change { AppState(manga = listOf(manga), sources = listOf(SourceConfig.local)) }
        val model = MonogatariViewModel(repo, scheduleBackground = false)
        model.read(manga, chapter)
        awaitMain("The completed chapter did not reopen at its beginning") { model.screen == "reader" && !model.busy }
        assertEquals(0, model.readerVisiblePage); assertEquals(0, model.readerChapter!!.page)
        assertFalse(JSONObject(model.readerChapter!!.metadata).has("scrollPosition"))
        assertFalse(JSONObject(model.readerChapter!!.metadata).has("splitIndex"))
        assertFalse(JSONObject(model.readerChapter!!.metadata).has("splitSourceIndex"))
        assertEquals(chapter, repo.state.value.manga.single().chapters.single())
        model.back(); model.read(manga, chapter, 2)
        awaitMain("The explicit page must take precedence over completed-chapter restart") { model.screen == "reader" && !model.busy }
        assertEquals(2, model.readerVisiblePage); assertEquals(2, model.readerChapter!!.page)
        assertEquals(chapter, repo.state.value.manga.single().chapters.single())
    }
    @Test fun delayedRestoredPagesCannotReplaceANewBookWithTheSameChapterKeyOrItsSavedPosition() = runBlocking {
        val arrived = CountDownLatch(1); val release = CountDownLatch(1)
        val requestPaths = java.util.concurrent.CopyOnWriteArrayList<String>()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requestPaths.add(request.path.orEmpty())
                    if (request.path != "/api/v1/books/shared/pages") return MockResponse().setResponseCode(404)
                    arrived.countDown()
                    if (!release.await(10, TimeUnit.SECONDS)) return MockResponse().setResponseCode(504)
                    return MockResponse().setBody(JSONArray((1..20).map { JSONObject().put("number", it).put("mediaType", "image/png") }).toString())
                }
            }
            server.start()
            val repo = AppRepository(context)
            // The real server backend persists its default name/URL through AndroidKeyStore.
            // Host Robolectric supplies no AndroidKeyStore; only this platform preference backend is substituted.
            val preferenceValues = java.util.concurrent.ConcurrentHashMap<String, String>()
            val preferences = object : app.monogatari.android.sources.SourcePreferenceBackend {
                override fun get(key: String): String? = preferenceValues[key]
                override fun set(key: String, value: String) { preferenceValues[key] = value }
                override fun remove(key: String) { preferenceValues.remove(key) }
                override fun keys(): Set<String> = preferenceValues.keys.toSet()
            }
            repo.client.builtInSessionFactory = { source -> app.monogatari.android.sources.BuiltInBackend(repo, source, repo.client,
                app.monogatari.android.sources.SourcePreferences(context, source.id, preferences)) }
            val remoteSource = SourceConfig("restore-server", "Restore server", "komga", server.url("/").toString())
            val remote = Manga("remote-a", remoteSource.id, "Remote A", chapters = listOf(Chapter("shared", "Remote chapter A", total = 20)))
            val image = File(context.cacheDir, "navigation-cached-b.png").apply {
                writeBytes(java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg=="))
            }
            val cachedChapter = Chapter("shared", "Cached chapter B", pages = List(12) { Uri.fromFile(image).toString() }, page = 3, total = 12,
                downloaded = true, readAt = 1000, metadata = JSONObject().put("scrollPosition", .75).toString())
            val cached = Manga("local-b", "local", "Cached B", chapters = listOf(cachedChapter))
            repo.change { AppState(manga = listOf(remote, cached), sources = listOf(SourceConfig.local, remoteSource)) }
            val model = MonogatariViewModel(repo)
            val restored = Bundle().apply {
                putString("monogatari.screen", "reader"); putString("monogatari.key", remote.id); putString("monogatari.chapter", "shared")
                putInt("monogatari.reader.page", 7); putInt("monogatari.reader.total", 20); putDouble("monogatari.reader.position", .2)
                putStringArrayList("monogatari.stack.screens", arrayListOf("main", "details"))
                putStringArrayList("monogatari.stack.keys", arrayListOf("", remote.id))
            }
            try {
                model.restoreNavigation(restored)
                awaitMain("The real remote page request did not reach MockWebServer", diagnostics = {
                    "screen=${model.screen} key=${model.key} chapter=${model.readerChapter} busy=${model.busy} message=${model.message} loadError=${repo.loadError} requests=$requestPaths"
                }) { arrived.count == 0L }
                assertEquals(remote.id, model.key); assertEquals(7, model.readerChapter!!.page)
                model.back(); model.read(cached, cachedChapter)
                awaitMain("The cached book did not become the current reader") { model.screen == "reader" && model.key == cached.id && model.readerChapter?.title == cachedChapter.title }
                model.readerVisiblePosition = .8
                release.countDown()
                awaitMain("The delayed remote response did not finish through the real repository") { repo.state.value.manga.first { it.id == remote.id }.chapters.single().pages.size == 20 && !model.busy }
                assertEquals("reader", model.screen); assertEquals(cached.id, model.key); assertEquals("shared", model.chapterKey)
                assertEquals(cachedChapter, model.readerChapter)
                assertEquals(3, model.readerVisiblePage); assertEquals(12, model.readerVisibleTotal); assertEquals(.8, model.readerVisiblePosition!!, 0.0)
                val saved = Bundle(); model.saveNavigation(saved)
                assertEquals(cached.id, saved.getString("monogatari.key")); assertEquals(3, saved.getInt("monogatari.reader.page")); assertEquals(.8, saved.getDouble("monogatari.reader.position"), 0.0)
                assertEquals(3, repo.state.value.manga.first { it.id == cached.id }.chapters.single().page)
                assertEquals(.75, JSONObject(repo.state.value.manga.first { it.id == cached.id }.chapters.single().metadata).getDouble("scrollPosition"), 0.0)
            } finally { release.countDown() }
        }
    }
    @Test fun recentHistoryContinuesTheNextUnreadChapterAndFallsBackToDetailsWhenAllReadOrSourceIsMissing() = runBlocking {
        val repo = AppRepository(context)
        val image = File(context.cacheDir, "history-cached-page.png").apply {
            writeBytes(java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg=="))
        }
        val first = Chapter("one", "Completed chapter 1", number = 1.0, completed = true, readAt = 1000,
            downloaded = true, pages = listOf(Uri.fromFile(image).toString()), total = 1)
        val second = Chapter("two", "Unread chapter 2", number = 2.0,
            downloaded = true, pages = listOf(Uri.fromFile(image).toString()), total = 1)
        val recent = Manga("recent", "local", "Recent book", chapters = listOf(first, second))
        val older = Manga("older", "local", "Older book", chapters = listOf(second.copy(readAt = 500)))
        suspend fun historyModel(book: Manga): MonogatariViewModel {
            repo.change { AppState(manga = listOf(older, book), sources = listOf(SourceConfig.local)) }
            return MonogatariViewModel(repo).also { it.selectTab(2); it.continueRecentHistory() }
        }
        val continuing = historyModel(recent)
        awaitMain("History reselect did not open the next unread chapter") { continuing.screen == "reader" && !continuing.busy }
        assertEquals(recent.id, continuing.key); assertEquals(second.key, continuing.chapterKey)
        assertEquals(second, continuing.readerChapter)
        assertTrue(repo.state.value.manga.first { it.id == recent.id }.chapters.first().completed)
        assertFalse(repo.state.value.manga.first { it.id == recent.id }.chapters[1].completed)

        val completed = recent.copy(chapters = listOf(first, second.copy(completed = true, readAt = 2000)))
        val allRead = historyModel(completed)
        awaitMain("All-read history should open manga details") { allRead.screen == "details" && !allRead.busy }
        assertEquals(completed.id, allRead.key); assertNull(allRead.readerChapter)

        val missing = recent.copy(key = "missing", sourceId = "uninstalled-source", title = "Cached title without its source")
        val noSource = historyModel(missing)
        awaitMain("Missing-source history should display the cached manga details") { noSource.screen == "details" && !noSource.busy }
        assertEquals(missing.id, noSource.key); assertNull(noSource.readerChapter)
        assertEquals(missing.title, repo.state.value.manga.first { it.id == noSource.key }.title)
    }
}
