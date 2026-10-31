package app.monogatari.android.account

import app.monogatari.android.data.AppState
import app.monogatari.android.data.Chapter
import app.monogatari.android.data.Manga
import app.monogatari.android.data.objects
import app.monogatari.android.data.strings
import org.json.JSONArray
import org.json.JSONObject

/*
 * Record shapes, shared with the iOS app (times in milliseconds since the epoch):
 *   library   sha256(source \n manga)            {source, manga, title, cover, categories: [title], added}
 *   progress  sha256(source \n manga \n chapter) {source, manga, chapter, page (0-based), total, completed, read, position?}
 *   category  sha256(title)                      {title, sort}
 *   pref      the setting's name                 {value}
 * Local files stay on the device: their titles and progress are not synced.
 */

private const val LOCAL_SOURCE = "local"

private fun Manga.syncable() = sourceId != LOCAL_SOURCE

/** Titles on the bookshelf and their categories. */
object LibraryAdapter : SyncAdapter {
    override val kind = "library"
    override val dependsOn = listOf("category")

    override fun scan(state: AppState) = state.manga.filter { it.inLibrary && it.syncable() }.map { m ->
        LocalItem(syncKey(m.sourceId, m.key), JSONObject().put("source", m.sourceId).put("manga", m.key).put("title", m.title)
            .put("cover", m.cover).put("categories", JSONArray(m.categories.sorted())).put("added", m.addedAt), null)
    }

    override fun apply(state: AppState, record: SyncRecord): AppState? {
        val data = record.data ?: return null
        val source = data.optString("source"); val key = data.optString("manga")
        if (source.isEmpty() || key.isEmpty() || source == LOCAL_SOURCE || syncKey(source, key) != record.key) return state
        val categories = data.optJSONArray("categories").strings()
        val added = data.optLong("added").takeIf { it > 0 } ?: record.updated
        val existing = state.manga.firstOrNull { it.sourceId == source && it.key == key }
        val manga = existing?.copy(inLibrary = true, categories = categories, addedAt = added)
            // Shown from the record until the source is refreshed, which fills in chapters and details.
            ?: Manga(key, source, data.optString("title").ifEmpty { key }, cover = data.optString("cover"), inLibrary = true, categories = categories, addedAt = added)
        val missing = categories.filter { it !in state.categories }
        return state.copy(manga = if (existing == null) state.manga + manga else state.manga.map { if (it === existing) manga else it },
            categories = state.categories + missing)
    }

    override fun remove(state: AppState, key: String) = state.copy(manga = state.manga.map {
        if (it.inLibrary && syncKey(it.sourceId, it.key) == key) it.copy(inLibrary = false, categories = emptyList(), pinned = false) else it
    })
}

/** Reading progress per chapter, of every title read here (in the library or only in history). */
object ProgressAdapter : SyncAdapter {
    override val kind = "progress"
    override val dependsOn = listOf("library")

    private fun item(source: String, manga: String, chapter: String, page: Int, total: Int, completed: Boolean, read: Long, position: Any?) =
        LocalItem(syncKey(source, manga, chapter), JSONObject().put("source", source).put("manga", manga).put("chapter", chapter)
            .put("page", page).put("total", total).put("completed", completed).put("read", read)
            .apply { (position as? Number)?.toDouble()?.takeIf { it.isFinite() }?.let { put("position", it) } }, read)

    override fun scan(state: AppState): List<LocalItem> {
        val items = mutableListOf<LocalItem>()
        val seen = HashSet<String>()
        for (m in state.manga) {
            if (!m.syncable()) continue
            for (c in m.chapters) {
                if (c.readAt <= 0 && !c.completed) continue
                items += item(m.sourceId, m.key, c.key, c.page, c.total, c.completed, c.readAt, JSONObject(c.metadata).opt("scrollPosition"))
                seen += "${m.id}\n${c.key}"
            }
        }
        // Chapters read before the source dropped or renamed them live in the history records.
        for (h in JSONObject(state.records).optJSONArray("history").objects()) {
            val source = h.optString("sourceId"); val manga = h.optString("mangaId"); val chapter = h.optString("chapterId")
            val read = (h.optDouble("dateRead") * 1000).toLong()
            if (source == LOCAL_SOURCE || source.isEmpty() || (read <= 0 && !h.optBoolean("completed")) || !seen.add("$source::$manga\n$chapter")) continue
            items += item(source, manga, chapter, (h.optInt("progress") - 1).coerceAtLeast(0), h.optInt("total"), h.optBoolean("completed"), read, h.opt("scrollPosition"))
        }
        return items
    }

    override fun apply(state: AppState, record: SyncRecord): AppState? {
        val data = record.data ?: return null
        val source = data.optString("source"); val key = data.optString("manga"); val chapterKey = data.optString("chapter")
        if (source == LOCAL_SOURCE || syncKey(source, key, chapterKey) != record.key) return state
        // The title is not on this device yet: wait for its library record or for it to be opened.
        val manga = state.manga.firstOrNull { it.sourceId == source && it.key == key } ?: return null
        val page = data.optInt("page").coerceAtLeast(0); val total = data.optInt("total").coerceAtLeast(0)
        val completed = data.optBoolean("completed"); val read = data.optLong("read")
        val position = if (data.has("position")) data.optDouble("position").takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) else null
        val chapter = manga.chapters.firstOrNull { it.key == chapterKey }
        if (chapter != null) {
            val metadata = JSONObject(chapter.metadata).apply { if (position != null) put("scrollPosition", position) else remove("scrollPosition") }
            val updated = chapter.copy(page = if (total > 0) page.coerceAtMost(total - 1) else page, total = maxOf(total, 0), completed = completed, readAt = read, metadata = metadata.toString())
            val lastRead = maxOf(JSONObject(manga.metadata).optLong("lastRead"), read)
            return state.copy(manga = state.manga.map { m ->
                if (m !== manga) m else m.copy(chapters = m.chapters.map { if (it.key == chapterKey) updated else it }, metadata = JSONObject(m.metadata).put("lastRead", lastRead).toString())
            })
        }
        // Chapters not loaded yet (a title just added from the account): kept as history, merged on refresh.
        val records = JSONObject(state.records)
        val rows = records.optJSONArray("history").objects().filterNot { it.optString("sourceId") == source && it.optString("mangaId") == key && it.optString("chapterId") == chapterKey }
        val row = JSONObject().put("sourceId", source).put("mangaId", key).put("chapterId", chapterKey).put("progress", page + 1).put("total", total)
            .put("completed", completed).put("dateRead", read / 1000.0).apply { if (position != null) put("scrollPosition", position) }
        records.put("history", JSONArray(rows + row))
        val lastRead = maxOf(JSONObject(manga.metadata).optLong("lastRead"), read)
        return state.copy(records = records.toString(), manga = state.manga.map { if (it !== manga) it else it.copy(metadata = JSONObject(it.metadata).put("lastRead", lastRead).toString()) })
    }

    override fun remove(state: AppState, key: String): AppState {
        val manga = state.manga.map { m ->
            if (!m.syncable() || m.chapters.none { syncKey(m.sourceId, m.key, it.key) == key }) m
            else m.copy(chapters = m.chapters.map { c ->
                if (syncKey(m.sourceId, m.key, c.key) != key) c
                else c.copy(page = 0, completed = false, readAt = 0, metadata = JSONObject(c.metadata).apply { remove("scrollPosition") }.toString())
            })
        }
        val records = app.monogatari.android.library.HistoryRecords.clear(state.records) {
            syncKey(it.optString("sourceId"), it.optString("mangaId"), it.optString("chapterId")) == key
        }
        return state.copy(manga = manga, records = records)
    }
}

/** Library categories and their order. Category filters and groups stay on the device. */
object CategoryAdapter : SyncAdapter {
    override val kind = "category"

    private fun isGroup(state: AppState, title: String) = JSONObject(state.categoryMetadata).optJSONObject(title)?.optBoolean("group") == true

    override fun scan(state: AppState) = state.categories.filterNot { isGroup(state, it) }.mapIndexed { index, title ->
        LocalItem(syncKey(title), JSONObject().put("title", title).put("sort", index), null)
    }

    override fun apply(state: AppState, record: SyncRecord): AppState? {
        val title = record.data?.optString("title")?.takeIf { it.isNotBlank() && syncKey(it) == record.key } ?: return state
        val sort = record.data.optInt("sort")
        val others = state.categories.filter { it != title }
        // Group entries keep their place; the category goes to its position among the plain ones.
        val plain = others.filterNot { isGroup(state, it) }
        val before = plain.getOrNull(sort.coerceIn(0, plain.size) - 1)
        val at = if (before == null) 0 else others.indexOf(before) + 1
        return state.copy(categories = others.toMutableList().apply { add(at.coerceIn(0, size), title) })
    }

    override fun remove(state: AppState, key: String): AppState {
        val title = state.categories.firstOrNull { syncKey(it) == key && !isGroup(state, it) } ?: return state
        return app.monogatari.android.library.CategoryMaintenance.change(state, title, null)
    }
}

/**
 * Settings that follow the account, by their shared iOS and Android names. Everything else stays on the
 * device: credentials and tokens, tracker and source logins, locks, network and download behaviour,
 * notifications, performance options, layout sizes and internal state.
 */
val syncedSettings = setOf(
    "General.appearance", "General.useSystemAppearance", "Appearance.layout",
    "Library.opensReaderView", "Library.resumeLastOpenedChapter", "Library.continueReadingOnReselect",
    "Library.unreadChapterBadges", "Library.downloadedChapterBadges", "Library.pinTitles", "Library.showUncategorizedCategory",
    "Library.sortOption", "Library.sortAscending", "Library.listView", "Library.skipTitles", "Library.refreshMetadata",
    "Reader.readingMode", "Reader.skipDuplicateChapters", "Reader.markDuplicateChapters", "Reader.cropBorders",
    "Reader.disableQuickActions", "Reader.disableDoubleTap", "Reader.liveText", "Reader.hideBarsOnSwipe", "Reader.backgroundColor",
    "Reader.tapZones", "Reader.invertTapZones", "Reader.animatePageTransitions", "Reader.pagesToPreload", "Reader.pagedPageLayout",
    "Reader.pagedPageOffset", "Reader.splitWideImages", "Reader.reverseSplitOrder", "Reader.verticalInfiniteScroll",
    "Reader.autoScrollSpeed", "Reader.autoScrollPosition", "Reader.pillarbox", "Reader.pillarboxAmount", "Reader.pillarboxOrientation",
    "Reader.textReaderStyle", "Reader.textFontSize", "Reader.textLineSpacing", "Reader.textHorizontalPadding", "Reader.textTheme",
    "Reader.textAppearance", "Tracking.updateAfterReading", "Tracking.autoSyncFromTracker",
)

/** One record per synced setting the user has set; a setting back at its default is a deletion. */
class PrefAdapter(private val effective: (JSONObject, app.monogatari.android.data.Preferences) -> app.monogatari.android.data.Preferences) : SyncAdapter {
    override val kind = "pref"

    override fun scan(state: AppState): List<LocalItem> {
        val settings = JSONObject(state.settings)
        return syncedSettings.sorted().filter { settings.has(it) && !settings.isNull(it) }.map { LocalItem(it, JSONObject().put("value", settings.get(it)), null) }
    }

    override fun apply(state: AppState, record: SyncRecord): AppState? {
        if (record.key !in syncedSettings) return state
        val value = record.data?.opt("value") ?: return state
        if (value !is Boolean && value !is Number && value !is String && value !is JSONArray) return state
        val settings = JSONObject(state.settings).put(record.key, value)
        return state.copy(settings = settings.toString(), preferences = effective(settings, state.preferences))
    }

    override fun remove(state: AppState, key: String): AppState {
        if (key !in syncedSettings) return state
        val settings = JSONObject(state.settings).apply { remove(key) }
        return state.copy(settings = settings.toString(), preferences = effective(settings, state.preferences))
    }
}
