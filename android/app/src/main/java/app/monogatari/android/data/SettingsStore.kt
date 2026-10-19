package app.monogatari.android.data

import org.json.JSONArray
import org.json.JSONObject

/** Uses exactly the original UserDefaults keys; platform adapters interpret individual values. */
class SettingsStore(val repo: AppRepository) {
    private val defaults by lazy { JSONObject(repo.context.assets.open("ios/defaults.json").bufferedReader().use { it.readText() }) }
    fun value(key: String): Any? = JSONObject(repo.state.value.settings).opt(key)?.takeUnless { it == JSONObject.NULL }
        ?: if (key == "Browse.languages") JSONArray((0 until repo.context.resources.configuration.locales.size()).map { repo.context.resources.configuration.locales[it].language }.distinct() + "multi")
        else if (key == "Appearance.customPortraitRows") { if (repo.context.resources.configuration.smallestScreenWidthDp >= 600) 5 else 2 }
        else if (key == "Appearance.customLandscapeRows") { if (repo.context.resources.configuration.smallestScreenWidthDp >= 600) 6 else 4 }
        else defaults.opt(key)?.takeUnless { it == JSONObject.NULL }
    fun bool(key: String) = when (val value = value(key)) { is Boolean -> value; is Number -> value.toInt() != 0; else -> false }
    fun int(key: String) = (value(key) as? Number)?.toInt() ?: 0
    fun double(key: String) = (value(key) as? Number)?.toDouble() ?: 0.0
    fun string(key: String) = value(key)?.toString().orEmpty()
    fun strings(key: String) = (value(key) as? JSONArray).strings()
    fun libraryCategory(): String? = JSONObject(repo.state.value.settings).let { settings ->
        if (!settings.has("Library.currentCategory") || settings.isNull("Library.currentCategory")) null else settings.optString("Library.currentCategory")
    }
    fun normalizeLegacy(data: JSONObject): JSONObject = JSONObject(data.toString()).apply {
        if (optBoolean("Browse.showNsfwSources")) { put("Browse.contentRatings", JSONArray(listOf(0, 1, 2))); remove("Browse.showNsfwSources") }
        if (optBoolean("Library.pinManga")) { put("Library.pinTitles", when (optInt("Library.pinMangaType")) { 0 -> "unread"; 1 -> "updatedChapters"; else -> "none" }); remove("Library.pinManga"); remove("Library.pinMangaType") }
        if (optBoolean("downloadChapterSortAscending")) { put("Flags.downloadChapterSortAscending", true); remove("downloadChapterSortAscending") }
        listOf("enabledModelFile", "downloadQueueState", "chaptersToBeDeleted").forEach { key -> if (has(key)) { put("Data.$key", get(key)); remove(key) } }
        val tablet = repo.context.resources.configuration.smallestScreenWidthDp >= 600
        listOf(Triple("General.portraitRows", "Appearance.customPortraitRows", if (tablet) 5 else 2), Triple("General.landscapeRows", "Appearance.customLandscapeRows", if (tablet) 6 else 4)).forEach { (old, new, default) ->
            val rows = optInt(old); if (rows > 0 && rows != default) { put("Appearance.layout", "custom"); put(new, rows.coerceIn(1, 15)); remove(old) }
        }
        keys().asSequence().toList().filter { it.startsWith("Token.") }.forEach { key -> put(key.replaceFirst("Token.", "Tracker."), get(key)); remove(key) }
    }
    suspend fun upgradeLegacy() {
        val raw = JSONObject(repo.state.value.settings); val upgraded = normalizeLegacy(raw)
        if (upgraded.toString() != raw.toString()) repo.change { s -> val j = normalizeLegacy(JSONObject(s.settings)); s.copy(settings = j.toString(), preferences = effectivePreferences(j, s.preferences)) }
    }
    suspend fun put(key: String, value: Any?) {
        repo.change { state ->
        val settings = JSONObject(state.settings).put(key, value)
        state.copy(settings = settings.toString(), preferences = effectivePreferences(settings, state.preferences))
        }
        if (key.startsWith("Library.")) app.monogatari.android.library.LibraryRefresh.schedule(repo)
        if (key == "Logs.logServer") app.monogatari.android.sources.SourceLogStore.get(repo.context).setStreamUrl(value?.toString())
    }
    fun effectivePreferences(settings: JSONObject, old: Preferences = Preferences()): Preferences {
        fun v(key: String) = settings.opt(key)?.takeUnless { it == JSONObject.NULL } ?: defaults.opt(key)
        return old.copy(incognito = v("General.incognitoMode") == true,
            theme = if (v("General.useSystemAppearance") != false) "system" else if ((v("General.appearance") as? Number)?.toInt() == 1) "dark" else "light",
            layout = v("Appearance.layout")?.toString() ?: "standard", listView = v("Library.listView") == true,
            mode = v("Reader.readingMode")?.toString() ?: "auto", fontSize = (v("Reader.textFontSize") as? Number)?.toInt() ?: old.fontSize)
    }
    fun export(): JSONObject = JSONObject(repo.state.value.settings)
    fun exportWithDefaults(): JSONObject = JSONObject(defaults.toString()).apply {
        defaults.keys().asSequence().toList().forEach { put(it, value(it)) }
        val saved = export(); saved.keys().forEach { put(it, saved.get(it)) }
    }
    suspend fun import(data: JSONObject) { val upgraded = normalizeLegacy(data); repo.change { it.copy(settings = upgraded.toString(), preferences = effectivePreferences(upgraded, it.preferences)) }; app.monogatari.android.library.LibraryRefresh.schedule(repo) }
    fun requires(expression: String): Boolean {
        if (expression.isBlank()) return true
        return expression.split("&&").all { part ->
            val terms = part.trim().split("==", limit = 2)
            if (terms.size == 2) string(terms[0].trim()) == terms[1].trim() else if (part.trim().startsWith('!')) !bool(part.trim().drop(1)) else bool(part.trim())
        }
    }
}
