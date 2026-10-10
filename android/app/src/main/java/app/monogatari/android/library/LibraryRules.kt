package app.monogatari.android.library

import android.util.Base64
import app.monogatari.android.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.Collator
import java.util.Locale

data class LibraryRule(val type: Int, val value: String? = null, val exclude: Boolean = false) {
    fun json() = JSONObject().put("type", type).put("value", value).put("exclude", exclude)
    companion object {
        fun parse(j: JSONObject) = LibraryRule(j.getInt("type"), j.optString("value").takeUnless { it.isBlank() || it == "null" }, j.optBoolean("exclude"))
    }
}
object LibraryRules {
    fun parse(raw: Any?): List<LibraryRule> = try {
        val array = when (raw) {
            is JSONArray -> raw
            is String -> if (raw.trimStart().startsWith('[')) JSONArray(raw) else JSONArray(Base64.decode(raw, Base64.DEFAULT).toString(Charsets.UTF_8))
            else -> JSONArray()
        }; array.objects().map(LibraryRule::parse)
    } catch (_: Exception) { emptyList() }
    fun toggle(rules: List<LibraryRule>, type: Int, value: String? = null): List<LibraryRule> {
        val old = rules.firstOrNull { it.type == type && it.value == value }
        val others = rules.filterNot { it.type == type && it.value == value }
        return when { old == null -> others + LibraryRule(type, value); !old.exclude -> others + old.copy(exclude = true); else -> others }
    }
    fun matches(manga: Manga, rules: List<LibraryRule>, tracked: Set<String> = emptySet(), hasRemovedDownloads: Boolean = false): Boolean {
        fun match(rule: LibraryRule) = when (rule.type) {
            0 -> manga.chapters.any { it.downloaded } || hasRemovedDownloads
            1 -> manga.id in tracked
            2 -> manga.unread > 0
            3 -> manga.lastRead > 0 || manga.chapters.any { it.completed }
            4 -> manga.status == 2
            5 -> manga.sourceId == rule.value
            6 -> manga.contentRating == when (rule.value) { "unknown" -> 0; "safe" -> 1; "suggestive" -> 2; "nsfw" -> 3; else -> rule.value?.toIntOrNull() ?: -1 }
            7 -> rule.value in manga.categories
            else -> true
        }
        if (rules.any { it.exclude && match(it) }) return false
        return rules.filterNot { it.exclude }.groupBy { it.type }.values.all { group -> group.any(::match) }
    }
    fun select(state: AppState, settings: SettingsStore, category: String?, query: String, tracked: Set<String> = emptySet()): List<Manga> {
        val categoryMeta = JSONObject(state.categoryMetadata)
        val group = category?.let { categoryMeta.optJSONObject(it) }?.takeIf { it.optBoolean("group") }
        val filters = parse(settings.value("Library.filters")) + parse(group?.opt("filters"))
        val removedDownloads = JSONObject(state.records).optJSONArray("history").objects().filter { it.optJSONObject("_chapter")?.optBoolean("downloaded") == true }.map { it.optString("sourceId") to it.optString("mangaId") }.toSet()
        val collator = Collator.getInstance(Locale.getDefault())
        val method = settings.int("Library.sortOption")
        val ascending = settings.bool("Library.sortAscending")
        val pin = settings.string("Library.pinTitles")
        fun metadata(manga: Manga, key: String) = JSONObject(manga.metadata).optLong(key)
        fun pinned(manga: Manga) = manga.pinned || pin == "unread" && manga.unread > 0 || pin == "updatedChapters" && metadata(manga, "lastUpdatedChapters") > metadata(manga, "lastOpened")
        fun number(manga: Manga): Long = when (method) {
            1 -> manga.lastRead; 2 -> metadata(manga, "lastOpened"); 3 -> metadata(manga, "lastUpdated")
            4 -> manga.addedAt; 5 -> manga.chapters.maxOfOrNull { it.dateUploaded } ?: 0L; 6 -> manga.unread.toLong(); 7 -> manga.chapters.size.toLong(); else -> 0
        }
        return state.manga.filter { m ->
            m.inLibrary && (category == null || group != null || category == "" && m.categories.isEmpty() || category in m.categories)
                && (fuzzy(m.title, query) || fuzzy(m.author, query)) && matches(m, filters, tracked, (m.sourceId to m.key) in removedDownloads)
        }.sortedWith { a, b ->
            val pins = pinned(b).compareTo(pinned(a))
            if (pins != 0) pins else {
                var order = if (method == 6 && ascending && (a.unread == 0 || b.unread == 0)) {
                    if (a.unread == 0 && b.unread == 0) 0 else if (a.unread == 0) 1 else -1
                } else {
                    val sorted = if (method == 0) collator.compare(a.title, b.title) else number(b).compareTo(number(a))
                    if (ascending) -sorted else sorted
                }
                if (order != 0) order else collator.compare(a.title, b.title)
            }
        }
    }
    private fun fuzzy(text: String, query: String): Boolean {
        if (query.isBlank()) return true
        var index = 0
        val search = query.lowercase().filterNot(Char::isWhitespace)
        for (character in text.lowercase()) if (index < search.length && character == search[index]) index++
        return index == search.length
    }
}
