package app.monogatari.android.library

import app.monogatari.android.data.AppRepository
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** The original reader records distinct visible pages per foreground/chapter session. */
class ReadingSessions(private val repo: AppRepository) {
    private data class Active(val mangaId: String, val chapterId: String, val start: Long, val pages: MutableSet<Int>, var interacted: Boolean = false)
    private var active: Active? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Synchronized fun begin(manga: String, chapter: String, page: Int) {
        if (active?.mangaId == manga && active?.chapterId == chapter) return
        end()
        active = Active(manga, chapter, System.currentTimeMillis(), mutableSetOf(page))
    }
    @Synchronized fun pageChanged(manga: String, chapter: String, pages: Set<Int>) {
        if (active?.mangaId != manga || active?.chapterId != chapter) begin(manga, chapter, pages.firstOrNull() ?: 0)
        active?.let { it.pages += pages; it.interacted = true }
    }
    @Synchronized fun initialPages(manga: String, chapter: String, pages: Set<Int>) {
        val visible = pages.filter { it >= 0 }.toSet()
        if (visible.isEmpty()) return
        if (active?.mangaId != manga || active?.chapterId != chapter) begin(manga, chapter, visible.first())
        active?.takeUnless { it.interacted }?.let { it.pages.clear(); it.pages += visible }
    }
    @Synchronized fun end() {
        val current = active ?: return
        active = null
        if (!current.interacted || current.pages.isEmpty() || repo.settings.bool("General.incognitoMode") || repo.state.value.preferences.incognito) return
        val ended = maxOf(System.currentTimeMillis(), current.start + 1)
        scope.launch {
            try {
                repo.change { state ->
                    val manga = state.manga.firstOrNull { it.id == current.mangaId } ?: return@change state
                    val record = JSONObject().put("sourceId", manga.sourceId).put("mangaId", manga.key).put("chapterId", current.chapterId)
                        .put("startDate", current.start / 1000.0).put("endDate", ended / 1000.0).put("pagesRead", current.pages.size)
                    val data = JSONObject(state.records); val sessions = data.optJSONArray("readingSessions") ?: JSONArray()
                    sessions.put(record); data.put("readingSessions", sessions)
                    state.copy(records = data.toString())
                }
            } catch (e: Exception) { app.monogatari.android.sources.SourceLogStore.get(repo.context).add("Reading session: ${e.message}", "ERROR") }
        }
    }
}
