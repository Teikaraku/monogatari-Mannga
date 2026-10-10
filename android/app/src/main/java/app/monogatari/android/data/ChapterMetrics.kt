package app.monogatari.android.data

import org.json.JSONArray
import org.json.JSONObject

/** Library counts use language/scanlator selection, independently of chapter-list display filters. */
object ChapterMetrics {
    fun selected(manga: Manga, includeLocked: Boolean = false): List<Chapter> {
        val meta = JSONObject(manga.metadata)
        val language = meta.optString("langFilter").takeUnless { it.isEmpty() || it == "null" }
        val scanlators = meta.optJSONArray("scanlatorFilter")?.strings()
            ?: runCatching { JSONArray(meta.optString("scanlatorFilter")).strings() }.getOrDefault(emptyList())
        return manga.chapters.filter { chapter ->
            val groups = JSONObject(chapter.metadata).optJSONArray("scanlators")?.strings()?.takeIf { it.isNotEmpty() }
                ?: chapter.scanlator.split(',').map(String::trim).ifEmpty { listOf("") }
            (includeLocked || !chapter.locked) && (language == null || chapter.language == language) && (scanlators.isEmpty() || groups.any { it in scanlators })
        }
    }
    fun unreadCount(manga: Manga) = selected(manga).count { !it.completed }
    fun readCount(manga: Manga) = selected(manga, includeLocked = true).count { it.completed }
}
