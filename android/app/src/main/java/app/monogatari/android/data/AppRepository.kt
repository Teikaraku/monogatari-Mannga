package app.monogatari.android.data

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class AppRepository internal constructor(val context: Context) {
    val secrets = SecretStore(context)
    val client = SourceClient(secrets, context).apply { builtInSessionFactory = { source ->
        if (source.type == app.monogatari.android.sources.CopySource.TYPE) app.monogatari.android.sources.CopySourceBackend(this@AppRepository, source, this)
        else app.monogatari.android.sources.BuiltInBackend(this@AppRepository, source, this)
    } }
    private val file = AtomicFile(File(context.filesDir, "state.json"))
    private val database = AppDatabase(context)
    private val mutex = Mutex()
    @Volatile private var resetGeneration = 0L
    private val progressSequence = java.util.concurrent.atomic.AtomicLong()
    private val progressRequests = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, Long>()
    internal fun reserveProgress(manga: String, chapter: String): Long = progressSequence.incrementAndGet().also { progressRequests[manga to chapter] = it }
    internal fun currentProgress(manga: String, chapter: String, ticket: Long) = progressRequests[manga to chapter] == ticket
    internal fun finishProgress(manga: String, chapter: String, ticket: Long) { progressRequests.remove(manga to chapter, ticket) }
    var loadError: String? = null; private set
    private val mutable = MutableStateFlow(try {
        val loaded = if (database.hasState()) database.load()
        else if (file.baseFile.exists()) AppState.parse(JSONObject(file.readFully().toString(Charsets.UTF_8))).also {
            database.save(null, it)
            file.baseFile.renameTo(File(context.filesDir, "state-v1-migrated.json"))
        } else AppState()
        app.monogatari.android.library.ResetRequest.recover(context, database, app.monogatari.android.library.recoverSourceKeyMigrations(context, database, app.monogatari.android.backup.recoverDataRestores(context, database, loaded)))
    }
        catch (e: Exception) { loadError = "数据加载失败，已保留原文件：${e.message}"; AppState() })
    val state = mutable.asStateFlow()
    val work by lazy { WorkManager.getInstance(context) }
    suspend fun change(block: (AppState) -> AppState) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(loadError == null) { loadError.orEmpty() }
            check(!app.monogatari.android.library.ResetRequest.pending(context)) { "Application reset is in progress" }
            val next = block(mutable.value)
            database.save(mutable.value, next)
            mutable.value = next
        }
    }
    suspend fun upsert(manga: Manga) = change { s -> s.copy(manga = s.manga.filter { it.id != manga.id } + manga) }
    internal suspend fun commitReset() = withContext(Dispatchers.IO) { mutex.withLock {
        check(app.monogatari.android.library.ResetRequest.pending(context))
        val next = AppState(); database.save(mutable.value, next); resetGeneration++; progressRequests.clear(); mutable.value = next
    } }
    suspend fun updateManga(id: String, block: (Manga) -> Manga) = change { s -> s.copy(manga = s.manga.map { if (it.id == id) block(it) else it }) }
    val settings by lazy { SettingsStore(this) }
    init {
        client.installedSources = { state.value.sources }
        if (loadError == null) try {
            client.runtimes.recoverPendingInstalls(mutable.value.sources.associate { source ->
                val configuration = JSONObject(source.configuration)
                source.id to (configuration.optJSONObject("info")?.optInt("version") ?: configuration.optInt("version"))
            })
            val old = mutable.value; val original = JSONObject(old.settings); val migrated = settings.normalizeLegacy(original)
            val addCopy = !migrated.optBoolean(app.monogatari.android.sources.CopySource.INSTALL_MARKER)
            if (addCopy) migrated.put(app.monogatari.android.sources.CopySource.INSTALL_MARKER, true)
            if (addCopy || migrated.toString() != original.toString()) {
                val sources = if (addCopy && old.sources.none { it.id == app.monogatari.android.sources.CopySource.ID }) old.sources + app.monogatari.android.sources.CopySource.configuration else old.sources
                val next = old.copy(sources = sources, settings = migrated.toString(), preferences = settings.effectivePreferences(migrated, old.preferences))
                database.save(old, next); mutable.value = next
            }
        } catch (e: Exception) { loadError = "设置迁移失败，已保留原数据：${e.message}" }
    }
    suspend fun import(uri: Uri) = app.monogatari.android.local.LocalLibrary.import(this, uri)
    suspend fun addSource(name: String, type: String, address: String, username: String, password: String) = withContext(Dispatchers.IO) {
        val generation = resetGeneration
        val parsed = java.net.URI(address.trim())
        require(parsed.scheme in setOf("http", "https") && !parsed.host.isNullOrBlank() && parsed.userInfo == null) { "请输入完整的 HTTP 或 HTTPS 服务器地址" }
        val source = SourceConfig("$type-${UUID.randomUUID()}", name.ifBlank { type.replaceFirstChar(Char::uppercase) }, type, address.trim().trimEnd('/'))
        client.authenticate(source, username, password)
        try { change { check(generation == resetGeneration) { "Application was reset" }; it.copy(sources = it.sources + source) } } catch (e: Exception) { secrets.remove(source.id); throw e }
        source
    }
    suspend fun addSourceList(url: String) = withContext(Dispatchers.IO) {
        val generation = resetGeneration
        val list = client.sourceList(url.trim()); change { s ->
            check(generation == resetGeneration) { "Application was reset" }
            val lists = s.sourceLists.filter { old -> old.url != list.url } + list
            s.copy(sourceLists = lists, settings = JSONObject(s.settings).put("Browse.sourceLists", org.json.JSONArray(lists.map { it.url })).toString())
        }
    }
    suspend fun refresh(manga: Manga, refreshMetadata: Boolean = true): Manga = withContext(Dispatchers.IO) {
        val generation = resetGeneration
        val current = state.value.manga.firstOrNull { it.id == manga.id } ?: manga
        val source = state.value.sources.firstOrNull { it.id == current.sourceId } ?: error("漫画源未安装，无法获取章节")
        if (source.type == "local") return@withContext current
        val updated = client.details(source, current)
        var merged = updated
        change { s ->
            check(generation == resetGeneration) { "Application was reset" }
            val old = s.manga.firstOrNull { it.id == manga.id } ?: current
            val newChapters = if (old.chapters.isEmpty()) emptyList() else updated.chapters.filter { c -> old.chapters.none { it.key == c.key } || !c.locked && old.chapters.any { it.key == c.key && it.locked } }
            val updateChapters = newChapters.filter { app.monogatari.android.library.LibraryRefreshPolicy.versionMatches(old, it) }
            val meta = JSONObject(old.metadata)
            meta.remove("_androidMissingDetails")
            val updatedMeta = JSONObject(updated.metadata)
            val internal = setOf("lastOpened", "lastRead", "lastUpdated", "lastUpdatedChapters", "newChapterCount", "editedFields", "neverUpdate", "archiveFolder", "originalCover")
            updatedMeta.keys().forEach { if (it !in internal) meta.put(it, updatedMeta.get(it)) }
            app.monogatari.android.library.LibraryRefreshPolicy.applySourceSchedule(meta, updatedMeta)
            val now = System.currentTimeMillis()
            meta.put("lastRead", old.lastRead)
            meta.put("lastUpdated", now)
            if (newChapters.isNotEmpty()) meta.put("newChapterCount", meta.optInt("newChapterCount") + updateChapters.size).put("lastUpdatedChapters", now)
            val records = JSONObject(s.records)
            fun historyKey(source: String, manga: String, chapter: String) = org.json.JSONArray(listOf(source, manga, chapter)).toString()
            val history = records.optJSONArray("history").objects().associateBy { historyKey(it.optString("sourceId"), it.optString("mangaId"), it.optString("chapterId")) }.toMutableMap()
            old.chapters.filter { c -> (c.readAt > 0 || c.completed || c.downloaded) && updated.chapters.none { it.key == c.key } }.forEach { c ->
                history[historyKey(old.sourceId, old.key, c.key)] = historyRow(old, c)
            }
            records.put("history", org.json.JSONArray(history.values.toList()))
            val updates = records.optJSONArray("updates") ?: org.json.JSONArray()
            val existingUpdates = updates.objects().map { historyKey(it.optString("sourceId"), it.optString("mangaId"), it.optString("chapterId")) }.toMutableSet()
            updateChapters.forEach { c -> if (existingUpdates.add(historyKey(old.sourceId, old.key, c.key))) updates.put(JSONObject().put("sourceId", old.sourceId).put("mangaId", old.key).put("chapterId", c.key).put("date", now / 1000.0).put("viewed", false)) }
            records.put("updates", updates)
            val edited = meta.optJSONObject("editedFields")
            val details = if (refreshMetadata) updated else old
            merged = details.copy(inLibrary = old.inLibrary, categories = old.categories, pinned = old.pinned, addedAt = old.addedAt, metadata = meta.toString(),
                title = edited?.optString("title") ?: details.title,
                author = edited?.optString("author") ?: details.author, description = edited?.optString("description") ?: details.description,
                artist = edited?.optString("artist") ?: details.artist, cover = edited?.optString("cover") ?: details.cover,
                tags = edited?.optJSONArray("tags")?.strings() ?: details.tags,
                status = if (edited?.has("status") == true) edited.optInt("status") else details.status,
                viewer = edited?.optString("viewer") ?: details.viewer, language = edited?.optString("language") ?: details.language,
                url = edited?.optString("url") ?: details.url,
                chapters = updated.chapters.map { c -> old.chapters.firstOrNull { it.key == c.key }?.let {
                    val chapterMeta = JSONObject(c.metadata); val oldMeta = JSONObject(it.metadata)
                    oldMeta.keys().forEach { key -> if (key in setOf("pageDescriptions", "archiveFolder", "resources", "toc", "epub", "downloadFile", "bookmarks", "scrollPosition", "deleteAfterReading", "pageFlags")) chapterMeta.put(key, oldMeta.get(key)) }
                    c.copy(pages = it.pages, page = it.page, total = maxOf(it.total, c.total), readAt = it.readAt, completed = it.completed,
                        downloaded = it.downloaded, text = it.text.ifBlank { c.text }, html = it.html.ifBlank { c.html }, metadata = chapterMeta.toString())
                } ?: history[historyKey(old.sourceId, old.key, c.key)]?.let { h ->
                    val saved = h.optJSONObject("_chapter")?.let(Chapter::parse)
                    val metadata = JSONObject(c.metadata); h.opt("scrollPosition")?.takeUnless { it == JSONObject.NULL }?.let { metadata.put("scrollPosition", it) }
                    c.copy(page = (h.optInt("progress") - 1).coerceAtLeast(0), total = h.optInt("total"), completed = h.optBoolean("completed"), readAt = (h.optDouble("dateRead") * 1000).toLong(),
                        downloaded = saved?.downloaded == true, pages = if (saved?.downloaded == true) saved.pages else emptyList(), text = saved?.text.orEmpty(), html = saved?.html.orEmpty(), metadata = metadata.toString())
                } ?: c })
            s.copy(manga = s.manga.filter { it.id != manga.id } + merged, records = records.toString())
        }; merged
    }
    suspend fun pages(manga: Manga, chapter: Chapter): Chapter = withContext(Dispatchers.IO) {
        val generation = resetGeneration
        val current = state.value.manga.firstOrNull { it.id == manga.id }?.chapters?.firstOrNull { it.key == chapter.key } ?: chapter
        val source = state.value.sources.firstOrNull { it.id == manga.sourceId }
        if ((current.downloaded || source?.type == "local" || source == null) && (current.pages.isNotEmpty() || current.text.isNotBlank() || current.html.isNotBlank())) return@withContext current
        requireNotNull(source) { "漫画源未安装" }
        val pages = client.pages(source, current)
        require(pages.isNotEmpty()) { "章节没有页面" }
        var result = current.copy(pages = pages, total = pages.size)
        updateChapter(manga.id, chapter.key) { old -> check(generation == resetGeneration) { "Application was reset" }; (if (old.downloaded && old.pages.isNotEmpty()) old else old.copy(pages = pages, total = pages.size)).also { result = it } }
        result
    }
    private fun historyRow(manga: Manga, c: Chapter) = JSONObject().put("sourceId", manga.sourceId).put("mangaId", manga.key).put("chapterId", c.key)
        .put("progress", c.page + 1).put("total", c.total).put("completed", c.completed).put("dateRead", c.readAt / 1000.0).put("scrollPosition", JSONObject(c.metadata).opt("scrollPosition"))
        .put("_chapter", c.json())
    suspend fun updateChapter(mangaId: String, key: String, block: (Chapter) -> Chapter) = change { s ->
        val manga = s.manga.firstOrNull { it.id == mangaId }
        if (manga != null && manga.chapters.none { it.key == key }) {
            val records = JSONObject(s.records); val history = records.optJSONArray("history").objects()
            history.filter { it.optString("sourceId") == manga.sourceId && it.optString("mangaId") == manga.key && it.optString("chapterId") == key }.forEach { h ->
                h.optJSONObject("_chapter")?.let { raw -> val changed = historyRow(manga, block(Chapter.parse(raw))); changed.keys().forEach { h.put(it, changed.get(it)) } }
            }
            val lastRead = maxOf(manga.chapters.maxOfOrNull { it.readAt } ?: 0, history.filter { it.optString("sourceId") == manga.sourceId && it.optString("mangaId") == manga.key }.maxOfOrNull { (it.optDouble("dateRead") * 1000).toLong() } ?: 0)
            s.copy(manga = s.manga.map { if (it.id == manga.id) it.copy(metadata = JSONObject(it.metadata).put("lastRead", lastRead).toString()) else it }, records = records.toString())
        } else s.copy(manga = s.manga.map { m -> if (m.id == mangaId) m.copy(chapters = m.chapters.map { if (it.key == key) block(it) else it }) else m })
    }
    suspend fun progress(mangaId: String, chapterKey: String, page: Int, total: Int, position: Double? = null, completed: Boolean? = null, isCurrent: (() -> Boolean)? = null) {
        if (settings.bool("General.incognitoMode") || state.value.preferences.incognito || total <= 0) return
        val current = state.value.manga.firstOrNull { it.id == mangaId }?.chapters?.firstOrNull { it.key == chapterKey }
        if (current?.readAt == 0L && page == 0 && completed != true && (total > 1 || completed == false) && (position == null || position <= 0.0)) return
        updateChapter(mangaId, chapterKey) {
            if (isCurrent?.invoke() == false) return@updateChapter it
            val metadata = JSONObject(it.metadata)
            if (position?.isFinite() == true) metadata.put("scrollPosition", position.coerceIn(0.0, 1.0))
            it.copy(page = page.coerceIn(0, total - 1), total = total, completed = it.completed || (completed ?: (page >= total - 1)), readAt = System.currentTimeMillis(), metadata = metadata.toString())
        }
    }
    fun enqueue(mangaId: String, chapterKey: String) {
        app.monogatari.android.downloads.DownloadService.get(context).enqueue(mangaId, chapterKey)
    }
    fun cancelDownload(mangaId: String, key: String) {
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { app.monogatari.android.downloads.DownloadService.get(context).cancel(chapterDownloadId(mangaId, key)) }
    }
    suspend fun removeDownload(mangaId: String, key: String) = withContext(Dispatchers.IO) {
        app.monogatari.android.downloads.DownloadService.get(context).remove(mangaId, key)
    }
    companion object {
        @Volatile private var singleton: AppRepository? = null
        fun get(context: Context): AppRepository {
            val app = context.applicationContext
            return singleton?.takeIf { it.context === app } ?: synchronized(this) { singleton?.takeIf { it.context === app } ?: AppRepository(app).also { singleton = it } }
        }
        fun hash(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        fun chapterDownloadId(mangaId: String, chapterKey: String) = hash(org.json.JSONArray(listOf(mangaId, chapterKey)).toString())
    }
}
