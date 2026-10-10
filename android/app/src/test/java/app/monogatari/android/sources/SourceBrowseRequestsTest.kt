package app.monogatari.android.sources

import app.monogatari.android.data.Manga
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SourceBrowseRequestsTest {
    @Test fun paginationUsesSubmittedQueryAndDeeplyCapturedFilters() {
        val filter = JSONObject("""{"id":"genre","value":{"included":["one"]}}""")
        val request = SourceBrowseRequest.capture("query A", listOf(filter), null)
        filter.getJSONObject("value").getJSONArray("included").put("draft B")
        val calls = mutableListOf<Triple<String, Int, String>>()
        val session = Proxy.newProxyInstance(SourceSession::class.java.classLoader, arrayOf(SourceSession::class.java)) { _, method, args ->
            check(method.name == "search")
            @Suppress("UNCHECKED_CAST") val filters = args!![2] as List<JSONObject>
            calls += Triple(args[0] as String, args[1] as Int, filters.single().toString())
            filters.single().getJSONObject("value").getJSONArray("included").put("backend mutation")
            listOf(Manga("fixture::${args[1]}", "fixture", "Page ${args[1]}")) to true
        } as SourceSession
        request.fetch(session, 1); request.fetch(session, 2)
        assertEquals(listOf(1, 2), calls.map { it.second })
        assertTrue(calls.all { it.first == "query A" })
        assertTrue(calls.all { JSONObject(it.third).getJSONObject("value").getJSONArray("included").length() == 1 })
    }

    @Test fun listingSnapshotRetainsContextAcrossDraftAndBackendMutation() {
        val listing = JSONObject("""{"id":"library","context":{"libraryId":"original"}}""")
        val request = SourceBrowseRequest.capture("", emptyList(), listing)
        listing.getJSONObject("context").put("libraryId", "edited")
        val contexts = mutableListOf<String>()
        val session = Proxy.newProxyInstance(SourceSession::class.java.classLoader, arrayOf(SourceSession::class.java)) { _, method, args ->
            check(method.name == "listing")
            val captured = args!![0] as JSONObject
            contexts += captured.getJSONObject("context").getString("libraryId")
            captured.getJSONObject("context").put("libraryId", "backend mutation")
            emptyList<Manga>() to false
        } as SourceSession
        request.fetch(session, 1); request.fetch(session, 2)
        assertEquals(listOf("original", "original"), contexts)
    }

    @Test fun retainedPageWorkSurvivesCompositionExitAndRejectsOldResponse() = runBlocking {
        val viewModel = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val composition = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val work = SourceBrowseWork()
        val first = CompletableDeferred<Unit>(); val second = CompletableDeferred<Unit>()
        val published = mutableListOf<String>(); var busy = false
        work.launch(viewModel, onBusy = { busy = it }, onError = { assertNull(it) }) { token ->
            withContext(NonCancellable) { first.await() }
            if (work.accepts(token)) published += "A"
        }
        val firstJob = work.task!!
        composition.cancel()
        assertTrue(firstJob.isActive)
        work.launch(viewModel, replace = true, onBusy = { busy = it }, onError = { assertNull(it) }) { token ->
            second.await()
            if (work.accepts(token)) published += "B"
        }
        val secondJob = work.task!!
        first.complete(Unit); firstJob.join()
        assertTrue(busy); assertTrue(published.isEmpty())
        second.complete(Unit); secondJob.join()
        assertEquals(listOf("B"), published); assertFalse(busy)
        viewModel.cancel()
    }

    @Test fun replacingSourceConfigurationCancelsCachedJobAndInvalidatesPublication() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val work = SourceBrowseWork(); val response = CompletableDeferred<Unit>(); var published = false
        work.launch(scope, onBusy = {}, onError = { assertNull(it) }) { token ->
            withContext(NonCancellable) { response.await() }
            if (work.accepts(token)) published = true
        }
        val job = work.task!!; work.cancel()
        assertTrue(job.isCancelled); assertNull(work.task)
        response.complete(Unit); job.join()
        assertFalse(published)
        scope.cancel()
    }
}
