package app.monogatari.android.tracking

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [app.monogatari.android.backup.PosixAtomicFileShadow::class])
class TrackingQueueTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    @Before fun initializeWorkManager() { if (runCatching { androidx.work.WorkManager.getInstance(context) }.isFailure) androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build()) }
    @Test fun multipleOfflineChaptersSurviveRetryAndOnlySuccessfulPagesAreRemoved() = runBlocking {
        MockWebServer().use { server ->
            val account = TrackerAccount("server", TrackerProvider.KOMGA, baseUrl = server.url("/").toString(), connected = true)
            val binding = TrackBinding("fixture::book", account.id, "series", "Book")
            val repo = TrackingRepository.get(context); repo.restoreSnapshot(TrackingData(listOf(account), listOf(binding)).json())
            repeat(2) { server.enqueue(MockResponse().setResponseCode(500)) }
            for (page in listOf(ChapterRemoteProgress("one", 2, 10, false, updatedAt = 10), ChapterRemoteProgress("two", 4, 10, false, updatedAt = 20))) try { repo.update(binding.copy(pendingPage = page), TrackState(chapter = 3.0)) } catch (_: TrackerHttpException) { }
            assertEquals(listOf("one", "two"), repo.data.value.bindings.single().queuedPages().map { it.key })
            server.enqueue(MockResponse().setBody("{}")); server.enqueue(MockResponse().setBody("{}")); server.enqueue(MockResponse().setResponseCode(500)); repo.retryPending()
            assertEquals(listOf("two"), repo.data.value.bindings.single().queuedPages().map { it.key }); assertTrue(repo.data.value.bindings.single().pending)
            server.enqueue(MockResponse().setBody("{}")); server.enqueue(MockResponse().setBody("{}")); repo.retryPending()
            assertFalse(repo.data.value.bindings.single().pending); assertTrue(repo.data.value.bindings.single().queuedPages().isEmpty())
            val requests = (0 until 7).map { server.takeRequest() }; assertEquals(1, requests.count { it.path!!.contains("books/one/") }); assertEquals(2, requests.count { it.path!!.contains("books/two/") })
        }
    }
    @Test fun legacyQueueUpgradesAndSameChapterKeepsLatestUnreadReset() {
        val b = TrackBinding("source::m", "a", "1", "M", pendingPages = listOf(ChapterRemoteProgress("c", 8, 10, true, updatedAt = 10), ChapterRemoteProgress("d", 4, 10, false, updatedAt = 11)), pendingPage = ChapterRemoteProgress("c", 0, 10, false, updatedAt = 20))
        val restored = TrackBinding.parse(b.json()); assertEquals(listOf("c", "d"), restored.queuedPages().map { it.key }); assertFalse(restored.queuedPages().first().completed); assertEquals(0, restored.queuedPages().first().page)
        assertEquals(0, mergeQueuedPages(restored.queuedPages(), listOf(ChapterRemoteProgress("c", 9, 10, true, updatedAt = 1))).first().page)
    }
    @Test fun resetSettingsLogsOutAndClearsQueueWithoutDeletingBindings() = runBlocking {
        val repo = TrackingRepository.get(context); val account = TrackerAccount("public", TrackerProvider.ANILIST, connected = true, includeAdult = true)
        val binding = TrackBinding("source::m", "public", "123", "M", chapterOffset = 4, pending = true, pendingPages = listOf(ChapterRemoteProgress("c", 4, 10, false)))
        repo.restoreSnapshot(TrackingData(listOf(account), listOf(binding)).json()); repo.resetSettings()
        assertFalse(repo.data.value.accounts.single().connected); assertFalse(repo.data.value.accounts.single().includeAdult); assertEquals(4, repo.data.value.bindings.single().chapterOffset); assertEquals("123", repo.data.value.bindings.single().remoteId); assertTrue(repo.data.value.bindings.single().queuedPages().isEmpty())
    }
    @Test fun changingServerOriginDisconnectsAndCannotReuseTheOldTokenOrPendingAuthorization() = runBlocking {
        val repo = TrackingRepository.get(context); val old = TrackerAccount("server", TrackerProvider.KOMGA, baseUrl = "https://old.invalid", connected = true)
        repo.restoreSnapshot(TrackingData(listOf(old)).json()); val preferences = context.getSharedPreferences("credentials", 0)
        preferences.edit().putString("tracking.token.server", "old encrypted fixture").putString("tracking.pending.server", "old pending fixture").commit()
        repo.saveAccount(old.copy(baseUrl = "https://new.invalid"))
        assertFalse(repo.data.value.accounts.single().connected); assertFalse(preferences.contains("tracking.token.server")); assertFalse(preferences.contains("tracking.pending.server"))
    }
    @Test fun originalEnhancedTrackingFlagDisablesAutomaticBindingAndOfflineRetry() = runBlocking {
        val appRepo = app.monogatari.android.data.AppRepository.get(context); val source = app.monogatari.android.data.SourceConfig("komga-fixture", "Server", "komga", "https://fixture.invalid")
        appRepo.change { it.copy(sources = it.sources + source) }; appRepo.settings.put("${source.id}.disableTracking", true)
        val repo = TrackingRepository.get(context); repo.syncSourceAccounts(appRepo.state.value.sources)
        val account = repo.data.value.accounts.first { it.sourceId == source.id }; assertFalse(account.autoSync)
        repo.restoreSnapshot(TrackingData(listOf(account), listOf(TrackBinding("${source.id}::book", account.id, "book", "Book", pending = true, pendingPages = listOf(ChapterRemoteProgress("one", 2, 10, false))))).json())
        repo.retryPending(); assertEquals("", repo.data.value.bindings.single().error); assertTrue(repo.data.value.bindings.single().pending)
        repo.saveAccount(account); assertTrue(repo.data.value.bindings.isEmpty()); assertTrue(appRepo.settings.bool("${source.id}.disableTracking"))
    }
}
