package app.monogatari.android.library

import app.monogatari.android.data.*
import org.json.JSONArray
import org.json.JSONObject

/** Keep saved filters, category preferences and membership consistent with category edits. */
object CategoryMaintenance {
    fun change(state: AppState, old: String, replacement: String?): AppState {
        require(replacement == null || replacement.isNotBlank() && (replacement == old || replacement !in state.categories))
        val metadata = JSONObject(state.categoryMetadata)
        val original = metadata.optJSONObject(old)
        metadata.remove(old)
        if (replacement != null) metadata.put(replacement, (original ?: JSONObject()).put("title", replacement))
        fun rules(raw: Any?) = JSONArray(LibraryRules.parse(raw).mapNotNull { rule ->
            if (rule.type == 7 && rule.value == old) replacement?.let { rule.copy(value = it).json() } else rule.json()
        })
        metadata.keys().forEach { name -> metadata.optJSONObject(name)?.let { group -> if (group.has("filters")) group.put("filters", rules(group.opt("filters"))) } }
        val settings = JSONObject(state.settings)
        if (settings.has("Library.filters")) settings.put("Library.filters", rules(settings.opt("Library.filters")))
        listOf("Library.defaultCategory", "Library.currentCategory").forEach { key ->
            if (old.isNotBlank() && settings.optString(key) == old) {
                if (replacement != null) settings.put(key, replacement) else settings.remove(key)
            }
        }
        listOf("Library.lockedCategories", "Library.excludedUpdateCategories").forEach { key ->
            settings.optJSONArray(key)?.let { settings.put(key, JSONArray(it.strings().mapNotNull { name -> if (name == old) replacement else name }.distinct())) }
        }
        return state.copy(
            categories = if (old.isEmpty()) state.categories + listOfNotNull(replacement) else state.categories.mapNotNull { if (it == old) replacement else it }.distinct(),
            manga = state.manga.map { m -> m.copy(categories = m.categories.mapNotNull { if (it == old) replacement else it }.distinct()) },
            categoryMetadata = metadata.toString(), settings = settings.toString())
    }
}
