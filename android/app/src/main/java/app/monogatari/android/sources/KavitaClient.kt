package app.monogatari.android.sources

import app.monogatari.android.AppBrand

import app.monogatari.android.data.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Ports the same Kavita v2 filter and plugin/account authentication used by KavitaSource.swift. */
class KavitaClient(secrets: SecretStore, private val http: OkHttpClient, providedCredentials: SourcePreferenceBackend? = null) {
    private val secrets = providedCredentials ?: object : SourcePreferenceBackend {
        override fun get(key: String) = secrets.get(key)
        override fun set(key: String, value: String) = secrets.set(key, value)
        override fun remove(key: String) = secrets.remove(key)
    }
    private fun url(source: SourceConfig, path: String) = (source.baseUrl.trimEnd('/') + "/").toHttpUrl().resolve(path)?.toString() ?: error("无效的 Kavita 地址")
    fun apiKey(source: SourceConfig) = secrets.get("${source.id}.apiKey").orEmpty()
    fun request(source: SourceConfig, path: String, body: JSONObject? = null, post: Boolean = false, authorize: Boolean = true): String {
        val target = url(source, path).toHttpUrl(); val base = source.baseUrl.toHttpUrl()
        val scoped = authorize && target.scheme == base.scheme && target.host == base.host && target.port == base.port
        val initialToken = secrets.get(source.id)
        val initialCookie = secrets.get("${source.id}.cookie")
        var response = execute(source, path, body, post, scoped)
        if (scoped && response.first == 401) {
            synchronized(refreshLocks.getOrPut(source.id) { Any() }) {
                val changed = secrets.get(source.id) != initialToken || secrets.get("${source.id}.cookie") != initialCookie
                check(changed || refreshToken(source)) { "Kavita 登录已失效，请重新登录" }
            }
            response = execute(source, path, body, post, scoped)
        }
        require(response.first in 200..299) { if (response.first == 401) "Kavita 登录已失效，请重新登录" else runCatching { JSONObject(response.second).optString("title") }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Kavita HTTP ${response.first}" }
        return response.second
    }
    private fun execute(source: SourceConfig, path: String, body: JSONObject? = null, post: Boolean = false, authorize: Boolean = true, cookie: String? = null): Pair<Int, String> {
        val request = Request.Builder().url(url(source, path)).header("Accept", "application/json")
        val target = url(source, path).toHttpUrl(); val base = source.baseUrl.toHttpUrl()
        val scoped = target.scheme == base.scheme && target.host == base.host && target.port == base.port
        if (cookie != null && scoped) request.header("Cookie", cookie)
        else if (authorize && scoped) secrets.get(source.id)?.let { request.header("Authorization", it) }
            ?: secrets.get("${source.id}.cookie")?.let { request.header("Cookie", it) }
        if (body != null || post) request.post((body?.toString() ?: "").toRequestBody("application/json".toMediaType()))
        return http.newCall(request.build()).execute().use { response ->
            response.code to (response.body?.byteStream()?.use { SourceHost.readBounded(it, 50 * 1024 * 1024) } ?: error("Kavita 返回空内容")).toString(Charsets.UTF_8)
        }
    }
    private fun responseApiKey(response: JSONObject): String? = response.optJSONArray("authKeys").objects().firstOrNull { it.optString("name") == "opds" }?.stringOrNull("key")
        ?: response.stringOrNull("apiKey")
    private fun saveTokens(source: SourceConfig, response: JSONObject, saveApiKey: Boolean = true) {
        val token = response.stringOrNull("token")?.takeIf { it.isNotBlank() } ?: error("Kavita 登录未返回 Token")
        secrets.set(source.id, "Bearer $token")
        response.stringOrNull("refreshToken")?.takeIf { it.isNotBlank() }?.let { secrets.set("${source.id}.refreshToken", it) }
        if (saveApiKey) responseApiKey(response)?.takeIf { it.isNotBlank() }?.let { secrets.set("${source.id}.apiKey", it) }
    }
    fun refreshToken(source: SourceConfig): Boolean {
        val token = secrets.get(source.id)?.removePrefix("Bearer ")
        val refresh = secrets.get("${source.id}.refreshToken")
        if (token != null && refresh != null) {
            val result = runCatching { execute(source, "api/account/refresh-token", JSONObject().put("token", token).put("refreshToken", refresh), authorize = false) }.getOrNull()
            if (result != null && result.first in 200..299 && runCatching { saveTokens(source, JSONObject(result.second), false) }.isSuccess) return true
        }
        val key = apiKey(source).takeIf { it.isNotBlank() } ?: return false
        return runCatching { authenticate(source, "", key); true }.getOrDefault(false)
    }
    fun webAuthenticate(source: SourceConfig, cookies: Map<String, String>): Boolean {
        if (cookies[".AspNetCore.Cookies"].isNullOrBlank()) return false
        val cookie = cookies.filterKeys { it == ".AspNetCore.Cookies" || it.startsWith(".AspNetCore.CookiesC") }.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val response = execute(source, "api/account", authorize = false, cookie = cookie)
        if (response.first !in 200..299) return false
        val key = responseApiKey(JSONObject(response.second))?.takeIf { it.isNotBlank() } ?: return false
        secrets.remove(source.id); secrets.remove("${source.id}.refreshToken")
        secrets.set("${source.id}.cookie", cookie); secrets.set("${source.id}.apiKey", key)
        return true
    }
    /** An empty username treats password as the user's Kavita OPDS/plugin API key. */
    fun authenticate(source: SourceConfig, username: String, password: String) {
        val response = if (username.isBlank()) {
            require(password.isNotBlank()) { "请输入 Kavita API Key 或用户名和密码" }
            val login = (source.baseUrl.trimEnd('/') + "/").toHttpUrl().resolve("api/Plugin/authenticate")!!.newBuilder()
                .addQueryParameter("apiKey", password).addQueryParameter("pluginName", AppBrand.NAME).build()
            val path = login.encodedPath.removePrefix((source.baseUrl.trimEnd('/') + "/").toHttpUrl().encodedPath) + "?" + login.encodedQuery
            JSONObject(request(source, path, post = true, authorize = false))
        } else JSONObject(request(source, "api/account/login", JSONObject().put("username", username).put("password", password).put("apiKey", ""), authorize = false))
        if (username.isNotBlank()) require(response.stringOrNull("username") == username) { "Kavita 登录账号与请求不符" }
        val key = responseApiKey(response) ?: password.takeIf { username.isBlank() }.orEmpty()
        require(key.isNotBlank()) { "Kavita 账号没有 OPDS/API Key，无法读取图片" }
        saveTokens(source, response); secrets.set("${source.id}.apiKey", key); secrets.remove("${source.id}.cookie")
    }
    fun search(source: SourceConfig, query: String, page: Int = 0): Pair<List<Manga>, Boolean> {
        val filter = JSONObject().put("statements", JSONArray().apply { if (query.isNotBlank()) put(JSONObject().put("comparison", 7).put("field", 1).put("value", query)) })
            .put("combination", 1).put("sortOptions", JSONObject().put("sortField", 1).put("isAscending", true)).put("limitTo", 0)
        return JSONArray(request(source, "api/series/v2", filter)).objects().map { manga(source, it) } to false
    }
    fun manga(source: SourceConfig, series: JSONObject, metadata: JSONObject = JSONObject()): Manga {
        val id = series.get("id").toString()
        val key = apiKey(source)
        val cover = (url(source, "api/image/series-cover")).toHttpUrl().newBuilder().addQueryParameter("seriesId", id).addQueryParameter("apiKey", key).build().toString()
        val authors = metadata.optJSONArray("writers").objects().map { it.optString("name") }
        val artists = metadata.optJSONArray("pencillers").objects().map { it.optString("name") }
        val rating = metadata.optInt("ageRating", 0)
        val status = when (metadata.optInt("publicationStatus", -1)) { 0 -> 1; 1 -> 4; 2, 4 -> 2; 3 -> 3; else -> 0 }
        return Manga(id, source.id, series.optString("name"), cover, authors.joinToString(", "), metadata.optString("summary"),
            (metadata.optJSONArray("genres").objects() + metadata.optJSONArray("tags").objects()).map { it.optString("title") }, status,
            artist = artists.joinToString(", "), url = url(source, "library/${series.optInt("libraryId")}/series/$id"),
            contentRating = when { rating >= 10 -> 3; rating >= 8 -> 2; rating <= 1 -> 0; else -> 1 },
            metadata = JSONObject().put("series", series).put("metadata", metadata).put("authors", JSONArray(authors)).put("artists", JSONArray(artists)).toString())
    }
    fun details(source: SourceConfig, manga: Manga): Manga {
        val series = JSONObject(request(source, "api/Series/${manga.key}"))
        val metadata = JSONObject(request(source, "api/Series/metadata?seriesId=${manga.key}"))
        val volumes = JSONArray(request(source, "api/Series/volumes?seriesId=${manga.key}"))
        val chapters = volumes.objects().flatMap { volume ->
            val number = volume.optDouble("number", -1.0).takeIf { it >= 0 && it < 100000 }
            volume.optJSONArray("chapters").objects().filter { c -> c.optJSONArray("files").objects().none { it.optInt("format") == 3 } }.map { chapter ->
                val id = chapter.get("id").toString(); val title = chapter.optString("titleName").takeUnless { it.isBlank() || it == "null" }.orEmpty()
                val chapterNumber = chapter.optString("number").toDoubleOrNull()?.takeIf { it >= 0 && it < 100000 }
                val thumbnail = url(source, "api/image/chapter-cover").toHttpUrl().newBuilder().addQueryParameter("chapterId", id).addQueryParameter("apiKey", apiKey(source)).build().toString()
                Chapter(id, title, chapterNumber ?: 0.0, total = chapter.optInt("pages"), volume = number,
                    language = chapter.optString("language").takeUnless { it == "null" }.orEmpty(), dateUploaded = timestamp(chapter.optString("createdUtc")),
                    url = url(source, "library/${series.optInt("libraryId")}/series/${manga.key}/chapter/$id"), thumbnail = thumbnail, metadata = JSONObject(chapter.toString()).put("chapterNumber", chapterNumber).put("volumeNumber", number).toString())
            }
        }.sortedWith(compareByDescending<Chapter> { it.volume ?: Double.MAX_VALUE }.thenByDescending { it.number })
        return manga(source, series, metadata).copy(chapters = chapters)
    }
    fun pages(source: SourceConfig, chapter: Chapter): List<String> {
        val info = JSONObject(request(source, "api/Series/chapter?chapterId=${chapter.key}"))
        return (0 until info.getInt("pages")).map { index -> url(source, "api/Reader/image").toHttpUrl().newBuilder()
            .addQueryParameter("chapterId", chapter.key).addQueryParameter("page", index.toString()).addQueryParameter("apiKey", apiKey(source))
            .addQueryParameter("extractPdf", "true").build().toString() }
    }
    fun libraries(source: SourceConfig) = JSONArray(request(source, "api/library/libraries"))
    fun filterMetadata(source: SourceConfig, name: String): JSONArray {
        require(name in setOf("genres", "tags", "languages", "age-rating", "people", "publishers")) { "Unknown Kavita filter" }
        return JSONArray(request(source, "api/metadata/$name"))
    }
    fun searchFiltered(source: SourceConfig, filter: JSONObject) = JSONArray(request(source, "api/series/v2", filter)).objects().map { manga(source, it) }
    fun dashboard(source: SourceConfig) = JSONArray(request(source, "api/stream/dashboard?visibleOnly=true"))
    private fun timestamp(s: String): Long = try { Instant.parse(if (s.endsWith('Z')) s else s + "Z").toEpochMilli() } catch (_: Exception) { 0 }
    companion object { private val refreshLocks = java.util.concurrent.ConcurrentHashMap<String, Any>() }
}
