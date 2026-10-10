package app.monogatari.android.sources

import app.monogatari.android.data.Manga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Draft objects must not change the query whose pages are already displayed. */
internal data class SourceBrowseRequest private constructor(
    val query: String,
    private val filterJson: List<String>,
    private val listingJson: String?
) {
    fun filters() = filterJson.map(::JSONObject)
    fun listing() = listingJson?.let(::JSONObject)
    fun withFilters(filters: List<JSONObject>) = copy(filterJson = filters.map(JSONObject::toString))
    fun fetch(session: SourceSession, page: Int): Pair<List<Manga>, Boolean> =
        listing()?.let { session.listing(it, page) } ?: session.search(query, page, filters())
    companion object {
        fun capture(query: String, filters: List<JSONObject>, listing: JSONObject?) =
            SourceBrowseRequest(query, filters.map(JSONObject::toString), listing?.toString())
    }
}

/** Owned by a cached page, with work launched in the ViewModel's lifetime. */
internal class SourceBrowseWork {
    var task: Job? = null
        private set
    private var generation = 0L
    fun accepts(token: Long) = token == generation
    fun cancel() { generation++; task?.cancel(); task = null }
    fun launch(
        scope: CoroutineScope,
        replace: Boolean = false,
        onBusy: (Boolean) -> Unit,
        onError: (Exception?) -> Unit,
        block: suspend (Long) -> Unit
    ): Boolean {
        if (!replace && task?.isActive == true) return false
        val token = ++generation
        task?.cancel()
        onBusy(true); onError(null)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try { block(token) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (accepts(token)) onError(error) }
            finally { if (accepts(token)) { task = null; onBusy(false) } }
        }
        task = job
        job.start()
        return true
    }
}
