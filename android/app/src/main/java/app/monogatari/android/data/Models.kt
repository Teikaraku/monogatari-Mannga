package app.monogatari.android.data

import org.json.JSONArray
import org.json.JSONObject

data class Chapter(
    val key: String, val title: String, val number: Double = 0.0,
    val pages: List<String> = emptyList(), val text: String = "",
    val page: Int = 0, val total: Int = 0, val completed: Boolean = false,
    val readAt: Long = 0, val downloaded: Boolean = false,
    val volume: Double? = null, val language: String = "", val scanlator: String = "",
    val dateUploaded: Long = 0, val description: String = "", val html: String = "",
    val url: String = "", val thumbnail: String = "", val sourceOrder: Int = 0,
    val locked: Boolean = false, val metadata: String = "{}",
) {
    fun json() = JSONObject().put("key", key).put("title", title).put("number", number)
        .put("pages", JSONArray(pages)).put("text", text).put("page", page).put("total", total)
        .put("completed", completed).put("readAt", readAt).put("downloaded", downloaded)
        .put("volume", volume).put("language", language).put("scanlator", scanlator).put("dateUploaded", dateUploaded)
        .put("description", description).put("html", html).put("url", url).put("thumbnail", thumbnail)
        .put("sourceOrder", sourceOrder).put("locked", locked).put("metadata", JSONObject(metadata))
    companion object {
        fun parse(j: JSONObject) = Chapter(j.getString("key"), j.optString("title"), j.optDouble("number", 0.0),
            j.optJSONArray("pages").strings(), j.optString("text"), j.optInt("page"), j.optInt("total"),
            j.optBoolean("completed"), j.optLong("readAt"), j.optBoolean("downloaded"),
            if (j.isNull("volume") || !j.has("volume")) null else j.getDouble("volume"),
            j.optString("language"), j.optString("scanlator"), j.optLong("dateUploaded"), j.optString("description"), j.optString("html"),
            j.optString("url"), j.optString("thumbnail"), j.optInt("sourceOrder"), j.optBoolean("locked"), j.optJSONObject("metadata")?.toString() ?: "{}")
    }
}
data class Manga(
    val key: String, val sourceId: String, val title: String,
    val cover: String = "", val author: String = "", val description: String = "",
    val tags: List<String> = emptyList(), val status: Int = 0,
    val chapters: List<Chapter> = emptyList(), val inLibrary: Boolean = false,
    val categories: List<String> = emptyList(), val pinned: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
    val artist: String = "", val url: String = "", val viewer: String = "auto",
    val contentRating: Int = 0, val language: String = "", val metadata: String = "{}",
) {
    val id get() = "$sourceId::$key"
    val lastRead get() = maxOf(chapters.maxOfOrNull { it.readAt } ?: 0, JSONObject(metadata).optLong("lastRead"))
    val unread get() = ChapterMetrics.unreadCount(this)
    fun json() = JSONObject().put("key", key).put("sourceId", sourceId).put("title", title).put("cover", cover)
        .put("author", author).put("description", description).put("tags", JSONArray(tags)).put("status", status)
        .put("chapters", JSONArray(chapters.map { it.json() })).put("inLibrary", inLibrary)
        .put("categories", JSONArray(categories)).put("pinned", pinned).put("addedAt", addedAt)
        .put("artist", artist).put("url", url).put("viewer", viewer).put("contentRating", contentRating).put("language", language).put("metadata", JSONObject(metadata))
    companion object {
        fun parse(j: JSONObject) = Manga(j.getString("key"), j.getString("sourceId"), j.getString("title"),
            j.optString("cover"), j.optString("author"), j.optString("description"), j.optJSONArray("tags").strings(),
            j.optInt("status"), j.optJSONArray("chapters").objects().map(Chapter::parse), j.optBoolean("inLibrary"),
            j.optJSONArray("categories").strings(), j.optBoolean("pinned"), j.optLong("addedAt"),
            j.optString("artist"), j.optString("url"), j.optString("viewer", "auto"), j.optInt("contentRating"), j.optString("language"), j.optJSONObject("metadata")?.toString() ?: "{}")
    }
}
data class SourceConfig(val id: String, val name: String, val type: String, val baseUrl: String = "", val configuration: String = "{}") {
    fun json() = JSONObject().put("id", id).put("name", name).put("type", type).put("baseUrl", baseUrl).put("configuration", JSONObject(configuration))
    companion object {
        val local = SourceConfig("local", "本地文件", "local")
        fun parse(j: JSONObject) = SourceConfig(j.getString("id"), j.getString("name"), j.getString("type"), j.optString("baseUrl"), j.optJSONObject("configuration")?.toString() ?: "{}")
    }
}
data class CatalogSource(val id: String, val name: String, val languages: List<String>, val version: Int, val icon: String, val downloadUrl: String = "", val contentRating: Int = 0,
    val minAppVersion: String = "", val maxAppVersion: String = "", val altNames: List<String> = emptyList(), val baseUrl: String = "", val metadata: String = "{}")
data class SourceList(val url: String, val name: String, val entries: List<CatalogSource>) {
    fun json() = JSONObject().put("url", url).put("name", name).put("entries", JSONArray(entries.map {
        JSONObject().put("id", it.id).put("name", it.name).put("languages", JSONArray(it.languages)).put("version", it.version).put("icon", it.icon).put("downloadUrl", it.downloadUrl).put("contentRating", it.contentRating)
            .put("minAppVersion", it.minAppVersion).put("maxAppVersion", it.maxAppVersion).put("altNames", JSONArray(it.altNames)).put("baseUrl", it.baseUrl).put("metadata", JSONObject(it.metadata))
    }))
    companion object {
        fun parse(j: JSONObject) = SourceList(j.getString("url"), j.getString("name"), j.optJSONArray("entries").objects().map {
            CatalogSource(it.getString("id"), it.getString("name"), it.optJSONArray("languages").strings(), it.optInt("version"), it.optString("icon"), it.optString("downloadUrl"), it.optInt("contentRating"),
                it.optString("minAppVersion"), it.optString("maxAppVersion"), it.optJSONArray("altNames").strings(), it.optString("baseUrl"), it.optJSONObject("metadata")?.toString() ?: "{}")
        })
    }
}
data class Preferences(
    val theme: String = "system", val layout: String = "standard", val listView: Boolean = false,
    val mode: String = "rtl", val incognito: Boolean = false, val keepScreenOn: Boolean = true,
    val showPageNumbers: Boolean = true, val fontSize: Int = 18, val sort: String = "title",
) {
    fun json() = JSONObject().put("theme", theme).put("layout", layout).put("listView", listView)
        .put("mode", mode).put("incognito", incognito).put("keepScreenOn", keepScreenOn)
        .put("showPageNumbers", showPageNumbers).put("fontSize", fontSize).put("sort", sort)
    companion object {
        fun parse(j: JSONObject) = Preferences(j.optString("theme", "system"), j.optString("layout", "standard"),
            j.optBoolean("listView"), j.optString("mode", "rtl"), j.optBoolean("incognito"),
            j.optBoolean("keepScreenOn", true), j.optBoolean("showPageNumbers", true), j.optInt("fontSize", 18), j.optString("sort", "title"))
    }
}
data class AppState(
    val manga: List<Manga> = emptyList(), val sources: List<SourceConfig> = listOf(SourceConfig.local, app.monogatari.android.sources.CopySource.configuration),
    val sourceLists: List<SourceList> = emptyList(), val categories: List<String> = emptyList(),
    val preferences: Preferences = Preferences(), val backupExtras: String = "{}",
    val settings: String = "{}",
    val categoryMetadata: String = "{}", val records: String = "{}",
) {
    fun json() = JSONObject().put("format", "aidoku-android").put("version", 1)
        .put("manga", JSONArray(manga.map { it.json() })).put("sources", JSONArray(sources.map { it.json() }))
        .put("sourceLists", JSONArray(sourceLists.map { it.json() })).put("categories", JSONArray(categories))
        .put("preferences", preferences.json()).put("backupExtras", JSONObject(backupExtras)).put("settings", JSONObject(settings))
        .put("categoryMetadata", JSONObject(categoryMetadata)).put("records", JSONObject(records))
    companion object {
        fun parse(j: JSONObject): AppState {
            require(j.optString("format") == "aidoku-android" && j.optInt("version") == 1) { "不支持的 Android 数据版本" }
            return AppState(j.optJSONArray("manga").objects().map(Manga::parse),
                (listOf(SourceConfig.local) + j.optJSONArray("sources").objects().map(SourceConfig::parse)).distinctBy { it.id },
                j.optJSONArray("sourceLists").objects().map(SourceList::parse), j.optJSONArray("categories").strings(),
                Preferences.parse(j.optJSONObject("preferences") ?: JSONObject()), j.optJSONObject("backupExtras")?.toString() ?: "{}",
                j.optJSONObject("settings")?.toString() ?: "{}", j.optJSONObject("categoryMetadata")?.toString() ?: "{}", j.optJSONObject("records")?.toString() ?: "{}")
        }
    }
}
fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }
