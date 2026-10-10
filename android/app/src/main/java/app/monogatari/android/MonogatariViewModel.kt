package app.monogatari.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.monogatari.android.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MonogatariViewModel(val repo: AppRepository, scheduleBackground: Boolean = true) : ViewModel() {
    val state = repo.state
    var screen by mutableStateOf("main")
    var key by mutableStateOf("")
    var chapterKey by mutableStateOf("")
    var tab by mutableStateOf(0)
    var busy by mutableStateOf(false)
    var message by mutableStateOf(repo.loadError)
    var results by mutableStateOf<List<Manga>>(emptyList())
    var hasNextPage by mutableStateOf(false)
    var searchPage = 0
    var searchQuery = ""
    var readerChapter by mutableStateOf<Chapter?>(null)
    var readerVisiblePage = 0
    var readerVisiblePosition: Double? = null
    var readerVisibleTotal = 0
    var readerVisibleCompleted: Boolean? = null
    var pendingSourceQuery by mutableStateOf<String?>(null)
    var pendingSourceListing by mutableStateOf<org.json.JSONObject?>(null)
    var pendingChapterFocus by mutableStateOf<String?>(null)
    var navigationDirection = 1
    private val stack = mutableListOf<Pair<String, String>>()
    private data class TabNavigation(val screen: String, val key: String, val stack: List<Pair<String, String>>)
    private val tabNavigation = mutableMapOf<Int, TabNavigation>()
    private val pageMemories = mutableMapOf<String, Any>()
    @Suppress("UNCHECKED_CAST") fun <T : Any> pageMemory(id: String, initializer: () -> T): T = pageMemories.getOrPut(id, initializer) as T
    var historyReselected: (() -> Unit)? = null
    var historyAccess = false
    var uiEpoch by mutableStateOf(0)
        private set
    private var runningOperations = 0
    private var navigationGeneration = 0L
    private var readGeneration = 0L
    private val progressJob = kotlinx.coroutines.SupervisorJob()
    private val progressScope = kotlinx.coroutines.CoroutineScope(progressJob + Dispatchers.IO)
    private var acceptsProgress = true
    private val sessions = app.monogatari.android.library.ReadingSessions(repo)
    init {
        if (repo.loadError == null) {
            if (scheduleBackground) {
                app.monogatari.android.library.LibraryRefresh.schedule(repo)
                app.monogatari.android.backup.CompleteBackupManager.schedule(repo)
            }
            viewModelScope.launch {
                try {
                    repo.settings.upgradeLegacy()
                    app.monogatari.android.dictionary.DictionaryRepository.get(repo.context).load()
                    app.monogatari.android.tracking.TrackingRepository.get(repo.context).bindEnhancedSources(repo.state.value.manga, repo.state.value.sources)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { app.monogatari.android.sources.SourceLogStore.get(repo.context).add("Startup: ${e.message}", "ERROR") }
            }
        }
    }
    fun saveNavigation(bundle: android.os.Bundle) {
        bundle.putString("monogatari.screen", screen); bundle.putString("monogatari.key", key)
        bundle.putInt("monogatari.uiEpoch", uiEpoch)
        bundle.putString("monogatari.chapter", chapterKey); bundle.putInt("monogatari.tab", tab)
        bundle.putInt("monogatari.reader.page", readerVisiblePage)
        readerVisiblePosition?.let { bundle.putDouble("monogatari.reader.position", it) }
        bundle.putInt("monogatari.reader.total", readerVisibleTotal)
        readerVisibleCompleted?.let { bundle.putBoolean("monogatari.reader.completed", it) }
        bundle.putStringArrayList("monogatari.stack.screens", ArrayList(stack.map { it.first }))
        bundle.putStringArrayList("monogatari.stack.keys", ArrayList(stack.map { it.second }))
        bundle.putString("monogatari.tab.navigation", org.json.JSONObject().apply {
            tabNavigation.forEach { (tab, navigation) -> put(tab.toString(), org.json.JSONObject().put("screen", navigation.screen).put("key", navigation.key)
                .put("stack", org.json.JSONArray(navigation.stack.map { org.json.JSONArray(listOf(it.first, it.second)) }))) }
        }.toString())
    }
    fun restoreNavigation(bundle: android.os.Bundle) {
        navigationGeneration++
        val restoredRequest = ++readGeneration
        uiEpoch = bundle.getInt("monogatari.uiEpoch")
        screen = bundle.getString("monogatari.screen", "main"); key = bundle.getString("monogatari.key", "")
        chapterKey = bundle.getString("monogatari.chapter", ""); tab = bundle.getInt("monogatari.tab").coerceIn(0, 4)
        readerVisiblePage = bundle.getInt("monogatari.reader.page")
        readerVisiblePosition = if (bundle.containsKey("monogatari.reader.position")) bundle.getDouble("monogatari.reader.position").takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) else null
        readerVisibleTotal = bundle.getInt("monogatari.reader.total")
        readerVisibleCompleted = if (bundle.containsKey("monogatari.reader.completed")) bundle.getBoolean("monogatari.reader.completed") else null
        stack.clear(); stack += bundle.getStringArrayList("monogatari.stack.screens").orEmpty().zip(bundle.getStringArrayList("monogatari.stack.keys").orEmpty())
        tabNavigation.clear()
        runCatching { org.json.JSONObject(bundle.getString("monogatari.tab.navigation", "{}")).let { tabs -> tabs.keys().forEach { index ->
            val value = tabs.getJSONObject(index); val routes = value.optJSONArray("stack")
            tabNavigation[index.toInt()] = TabNavigation(value.optString("screen", "main"), value.optString("key"), (0 until (routes?.length() ?: 0)).map { routes!!.getJSONArray(it).let { route -> route.getString(0) to route.getString(1) } })
        } } }
        if (screen == "reader") {
            val manga = repo.state.value.manga.firstOrNull { it.id == key }
            readerChapter = manga?.let { m -> (m.chapters + app.monogatari.android.reader.ChapterListing.orphans(repo.state.value, m)).firstOrNull { it.key == chapterKey } }
            if (readerChapter == null) { screen = "main"; key = "" }
            else {
                val restoredPage = readerVisiblePage; val restoredPosition = readerVisiblePosition; val restoredTotal = readerVisibleTotal
                val restoredNavigation = navigationGeneration; val restoredChapter = readerChapter!!
                fun visible(chapter: Chapter): Chapter { val metadata = org.json.JSONObject(chapter.metadata); restoredPosition?.let { metadata.put("scrollPosition", it) }; return chapter.copy(page = restoredPage.coerceAtLeast(0), total = restoredTotal.takeIf { it > 0 } ?: chapter.total, metadata = metadata.toString()) }
                readerChapter = visible(readerChapter!!)
                if (restoredChapter.pages.isEmpty() && restoredChapter.text.isBlank() && restoredChapter.html.isBlank()) run {
                    val loaded = repo.pages(manga!!, restoredChapter)
                    if (restoredRequest == readGeneration && restoredNavigation == navigationGeneration && screen == "reader" && key == manga.id && chapterKey == restoredChapter.key) readerChapter = visible(loaded)
                }
            }
        }
    }
    fun beginReadingSession(mangaId: String, chapterKey: String, page: Int) = sessions.begin(mangaId, chapterKey, page)
    fun readingInteraction(mangaId: String, chapterKey: String, pages: Set<Int>) = sessions.pageChanged(mangaId, chapterKey, pages)
    fun initialReadingPages(mangaId: String, chapterKey: String, pages: Set<Int>) = sessions.initialPages(mangaId, chapterKey, pages)
    fun endReadingSession() = sessions.end()
    override fun onCleared() { acceptsProgress = false; sessions.end(); progressJob.complete(); super.onCleared() }
    fun run(block: suspend () -> Unit) {
        viewModelScope.launch {
            runningOperations++; busy = true
            try { block() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "操作失败" }
            finally { runningOperations--; busy = runningOperations > 0 }
        }
    }
    fun navigate(screen: String, key: String = "") {
        if (screen != this.screen || key != this.key) stack += this.screen to this.key
        navigationGeneration++
        navigationDirection = 1
        this.screen = screen; this.key = key
    }
    fun back() {
        val previous = stack.removeLastOrNull() ?: ("main" to "")
        navigationGeneration++
        navigationDirection = -1
        screen = previous.first; key = previous.second
    }
    fun selectTab(index: Int) {
        require(index in 0..4)
        if (screen == "reader") return
        if (tab == index) {
            if (screen != "main") { navigationGeneration++; navigationDirection = -1; stack.clear(); screen = "main"; key = "" }
            return
        }
        tabNavigation[tab] = TabNavigation(screen, key, stack.toList())
        val target = tabNavigation[index] ?: TabNavigation("main", "", emptyList())
        navigationGeneration++; navigationDirection = 0; tab = index
        stack.clear(); stack += target.stack; screen = target.screen; key = target.key
    }
    fun showTabRoot(index: Int) {
        selectTab(index)
        navigationGeneration++; navigationDirection = 0; stack.clear(); screen = "main"; key = ""
    }
    suspend fun resetNavigation() {
        val current = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.children?.filter { it !== current }?.forEach { it.cancel() }
        navigationGeneration++; readGeneration++; endReadingSession()
        pageMemories.clear(); tabNavigation.clear(); stack.clear(); historyReselected = null; historyAccess = false
        screen = "main"; key = ""; chapterKey = ""; tab = 0; searchQuery = ""; results = emptyList(); readerChapter = null
        readerVisiblePage = 0; readerVisibleTotal = 0; readerVisiblePosition = null; readerVisibleCompleted = null
        pendingSourceQuery = null; pendingSourceListing = null; pendingChapterFocus = null; uiEpoch++
    }
    fun continueRecentHistory() {
        if ((repo.settings.bool("History.lockHistoryTab") && !historyAccess) || screen != "main" || tab != 2) return
        val recent = repo.state.value.manga.filter { it.lastRead > 0 }.maxByOrNull { it.lastRead } ?: return
        val navigation = navigationGeneration
        run {
            val source = repo.state.value.sources.firstOrNull { it.id == recent.sourceId }
            val manga = if (source != null && (recent.chapters.isEmpty() || org.json.JSONObject(recent.metadata).optBoolean("_androidMissingChapterDetails"))) repo.refresh(recent) else recent
            if (navigation != navigationGeneration || screen != "main" || tab != 2) return@run
            val next = app.monogatari.android.reader.ChapterListing.next(manga)
            if (next != null && source != null) read(manga, next)
            else if (source != null || manga.title.isNotBlank()) open(manga)
        }
    }
    fun open(manga: Manga) {
        val current = repo.state.value.manga.firstOrNull { it.id == manga.id } ?: manga
        navigate("details", current.id)
        run {
            if (repo.state.value.manga.none { it.id == current.id }) repo.upsert(current)
            markOpened(current.id)
            val opened = repo.state.value.manga.first { it.id == current.id }
            repo.refresh(opened)
            app.monogatari.android.tracking.TrackingRepository.get(repo.context).syncOnOpen(repo, current.id)
        }
    }
    suspend fun markOpened(mangaId: String) {
        if (repo.settings.bool("General.incognitoMode")) return
        repo.change { state ->
            val data = org.json.JSONObject(state.records)
            val manga = state.manga.firstOrNull { it.id == mangaId }
            if (manga != null) data.optJSONArray("updates").objects().forEach { item -> if (item.optString("sourceId") == manga.sourceId && item.optString("mangaId") == manga.key) item.put("viewed", true) }
            state.copy(manga = state.manga.map { if (it.id == mangaId) it.copy(metadata = org.json.JSONObject(it.metadata).put("lastOpened", System.currentTimeMillis()).put("newChapterCount", 0).toString()) else it }, records = data.toString())
        }
    }
    suspend fun setChaptersRead(mangaId: String, chapterKeys: Set<String>, completed: Boolean) {
        repo.change { s ->
            val target = s.manga.firstOrNull { it.id == mangaId }
            fun belongs(row: org.json.JSONObject) = target != null && row.optString("sourceId") == target.sourceId && row.optString("mangaId") == target.key && row.optString("chapterId") in chapterKeys
            val now = System.currentTimeMillis()
            fun changedRecords(raw: String): org.json.JSONObject {
            val records = org.json.JSONObject(raw)
            records.optJSONArray("history").objects().filter(::belongs).filter { !completed || !it.optBoolean("completed") }.forEach { row ->
                row.put("completed", completed).put("progress", if (completed) row.optInt("total").coerceAtLeast(1) else 0).put("dateRead", if (completed) now / 1000.0 else 0)
                if (!completed) row.remove("scrollPosition")
                row.optJSONObject("_chapter")?.let { data ->
                    val old = Chapter.parse(data); val metadata = org.json.JSONObject(old.metadata); if (!completed) metadata.remove("scrollPosition")
                    row.put("_chapter", old.copy(completed = completed, page = if (completed) (old.total - 1).coerceAtLeast(0) else 0, readAt = if (completed) now else 0, metadata = metadata.toString()).json())
                }
            }
            if (!completed) records.optJSONArray("readingSessions")?.let { list -> records.put("readingSessions", org.json.JSONArray(list.objects().filterNot(::belongs))) }
            return records
            }
            val records = changedRecords(s.records); val extras = changedRecords(s.backupExtras)
            s.copy(manga = s.manga.map { m -> if (m.id != mangaId) m else {
                val chapters = m.chapters.map { if (it.key in chapterKeys && (!completed || !it.completed)) { val metadata = org.json.JSONObject(it.metadata); if (!completed) metadata.remove("scrollPosition"); it.copy(completed = completed, page = if (completed) (it.total - 1).coerceAtLeast(0) else 0, readAt = if (completed) now else 0, metadata = metadata.toString()) } else it }
                val historyLast = records.optJSONArray("history").objects().filter { it.optString("sourceId") == m.sourceId && it.optString("mangaId") == m.key }.maxOfOrNull { (it.optDouble("dateRead") * 1000).toLong() } ?: 0
                m.copy(chapters = chapters, metadata = org.json.JSONObject(m.metadata).put("lastRead", maxOf(chapters.maxOfOrNull { it.readAt } ?: 0, historyLast)).toString())
            } }, records = records.toString(), backupExtras = extras.toString())
        }
        val manga = repo.state.value.manga.firstOrNull { it.id == mangaId } ?: return
        (manga.chapters + app.monogatari.android.reader.ChapterListing.orphans(repo.state.value, manga)).filter { it.key in chapterKeys }.forEach { app.monogatari.android.tracking.TrackingRepository.get(repo.context).autoSync(manga, it, repo.state.value.sources) }
    }
    fun read(manga: Manga, chapter: Chapter) = read(manga, chapter, null)
    fun read(manga: Manga, chapter: Chapter, startPage: Int?) {
        val request = ++readGeneration
        val navigation = navigationGeneration
        run {
            val loaded = repo.pages(manga, chapter)
            if (request == readGeneration && navigation == navigationGeneration) {
                val requestedPage = startPage ?: if (loaded.completed) 0 else null
                val visible = if (requestedPage == null) loaded else {
                    val knownTotal = maxOf(loaded.total, loaded.pages.size)
                    val page = if (knownTotal > 0) requestedPage.coerceIn(0, knownTotal - 1) else requestedPage.coerceAtLeast(0)
                    val metadata = org.json.JSONObject(loaded.metadata).apply {
                        remove("scrollPosition"); remove("splitIndex"); remove("splitSourceIndex")
                    }
                    loaded.copy(page = page, metadata = metadata.toString())
                }
                readerChapter = visible; readerVisiblePage = visible.page; readerVisibleTotal = visible.total; readerVisiblePosition = null; readerVisibleCompleted = null; chapterKey = chapter.key; navigate("reader", manga.id)
            }
        }
    }
    fun search(sourceId: String?, query: String, more: Boolean = false) {
        run {
            val page = if (more) searchPage + 1 else 0
            var next = false
            val found = mutableListOf<Manga>()
            val errors = mutableListOf<String>()
            val sources = repo.state.value.sources.filter { sourceId == null || it.id == sourceId }
            if (page == 0 && (sourceId == null || sourceId == "local")) found += repo.state.value.manga.filter {
                (sourceId == null || it.sourceId == "local") && (it.title.contains(query, true) || it.author.contains(query, true))
            }
            sources.filter { it.type != "local" }.forEach { source ->
                try {
                    val (entries, hasMore) = withContext(Dispatchers.IO) { repo.client.search(source, query, page) }
                    found += entries; next = next || hasMore
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { errors += "${source.name}：${e.message}" }
            }
            results = ((if (more) results else emptyList()) + found).distinctBy { it.id }
            hasNextPage = next; searchPage = page; searchQuery = query
            if (errors.isNotEmpty()) message = errors.joinToString("\n")
        }
    }
    fun progress(mangaId: String, chapter: String, page: Int, total: Int, position: Double? = null, completed: Boolean? = null) {
        if (!acceptsProgress) return
        if (screen == "reader" && key == mangaId && chapterKey == chapter) {
            readerVisiblePage = page
            readerVisibleTotal = total; readerVisibleCompleted = completed
            if (position?.isFinite() == true) readerVisiblePosition = position.coerceIn(0.0, 1.0)
        }
        val request = repo.reserveProgress(mangaId, chapter)
        val write = progressScope.launch {
            try {
                repo.progress(mangaId, chapter, page, total, position, completed) { repo.currentProgress(mangaId, chapter, request) }
                if (!repo.currentProgress(mangaId, chapter, request)) return@launch
                if (!repo.state.value.preferences.incognito) {
                    val manga = repo.state.value.manga.firstOrNull { it.id == mangaId }
                    val current = manga?.let { m -> (m.chapters + app.monogatari.android.reader.ChapterListing.orphans(repo.state.value, m)).firstOrNull { it.key == chapter } }
                    if (manga != null && current != null) app.monogatari.android.tracking.TrackingRepository.get(repo.context).autoSync(manga, current, repo.state.value.sources)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { withContext(Dispatchers.Main.immediate) { message = e.message } }
        }
        write.invokeOnCompletion { repo.finishProgress(mangaId, chapter, request) }
    }
}
