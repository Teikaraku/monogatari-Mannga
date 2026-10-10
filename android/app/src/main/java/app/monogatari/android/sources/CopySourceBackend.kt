package app.monogatari.android.sources

import app.monogatari.android.data.*
import okhttp3.Cookie
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** Native source registration for search, source management, library refresh and downloads. */
class CopySourceBackend(private val repo: AppRepository, private val source: SourceConfig, private val client: SourceClient) : SourceSession {
    override val sourceId = source.id
    override val legacy = false
    override val exports = setOf("get_home", "get_search_manga_list", "get_manga_list", "get_settings")
    override val preferences = SourcePreferences(repo.context, sourceId)
    private val copy get() = client.copy(source)
    override fun search(query: String, page: Int, filters: List<JSONObject>): Pair<List<Manga>, Boolean> {
        val result = copy.catalog(CopyQuery(kind = if (query.isBlank()) "catalog" else "search", keyword = query), page)
        return result.entries to result.more
    }
    override fun listing(listing: JSONObject, page: Int): Pair<List<Manga>, Boolean> {
        val result = copy.catalog(CopyQuery(kind = listing.optString("id", "catalog")), page)
        return result.entries to result.more
    }
    override fun listings() = listOf("catalog" to "全部漫画", "finished" to "已完结", "ranking" to "排行榜").map { (key, title) ->
        JSONObject().put("id", key).put("name", title).put("kind", 0)
    }
    override fun home(): JSONObject = JSONObject().put("components", JSONArray(copy.home().sections.map { section ->
        JSONObject().put("type", 2).put("title", section.title).put("entries", JSONArray(section.entries.map { manga ->
            JSONObject().put("title", manga.title).put("imageUrl", manga.cover).put("subtitle", manga.author)
                .put("value", JSONObject().put("type", 2).put("data", manga.json()))
        }))
    }))
    override fun filters() = emptyList<JSONObject>()
    override fun manga(raw: JSONObject) = copy.manga(raw)
    override fun settings(): List<JSONObject> {
        val config = JSONObject(source.configuration)
        fun text(key: String, title: String, value: String) = JSONObject().put("key", key).put("title", title).put("type", "text")
            .put("default", value).put("notification", "copy_config_changed")
        val settings = listOf(text("apiServer", "API 地址", config.optString("apiBase", CopySource.API)),
            text("apiVersion", "接口兼容版本", config.optString("apiVersion", CopySource.API_VERSION)),
            text("contentApiServer", "详情与阅读 API 地址", config.optString("contentApiBase", CopySource.CONTENT_API)),
            text("contentApiVersion", "阅读接口兼容版本", config.optString("contentApiVersion", CopySource.CONTENT_API_VERSION)))
        preferences.initialize(JSONArray(settings))
        return settings
    }
    override fun notify(key: String) {
        if (key != "copy_config_changed") return
        val api = preferences.get("apiServer")?.toString().orEmpty().ifBlank { CopySource.API }
        val version = preferences.get("apiVersion")?.toString().orEmpty().ifBlank { CopySource.API_VERSION }
        // Constructing the transport validates the address before persisting it.
        val contentApi = preferences.get("contentApiServer")?.toString().orEmpty().ifBlank { CopySource.CONTENT_API }
        val contentVersion = preferences.get("contentApiVersion")?.toString().orEmpty().ifBlank { CopySource.CONTENT_API_VERSION }
        CopySourceClient(source, client.http, api, version, contentApi, contentVersion)
        val config = JSONObject(source.configuration).put("apiBase", api).put("apiVersion", version).put("contentApiBase", contentApi).put("contentApiVersion", contentVersion)
        kotlinx.coroutines.runBlocking { repo.change { state -> state.copy(sources = state.sources.map { if (it.id == sourceId) it.copy(configuration = config.toString()) else it }) } }
    }
    override fun baseUrl() = source.baseUrl
    override fun basicLogin(key: String, username: String, password: String) = false
    override fun webLogin(key: String, cookies: Map<String, String>) = false
    override fun importBrowserCookies(url: String, userAgent: String, cookies: String) = Unit
    override fun importCookieRecords(url: String, cookies: List<Cookie>) = Unit
    override fun clearCookies() { client.http.cache?.evictAll() }
    override fun resetSettings() {
        preferences.keys().forEach { preferences.set(it, null) }
        preferences.set("apiServer", CopySource.API)
        preferences.set("apiVersion", CopySource.API_VERSION)
        preferences.set("contentApiServer", CopySource.CONTENT_API)
        preferences.set("contentApiVersion", CopySource.CONTENT_API_VERSION)
        settings()
        notify("copy_config_changed")
    }
    override fun fetch(request: Request): HostResponse = client.http.newCall(request).execute().use { response ->
        HostResponse(response.code, response.body?.byteStream()?.use { SourceHost.readBounded(it, 50 * 1024 * 1024) } ?: byteArrayOf(),
            response.headers.names().associateWith { response.header(it).orEmpty() }, response.request.url.toString(),
            request.headers.names().associateWith { request.header(it).orEmpty() })
    }
}
