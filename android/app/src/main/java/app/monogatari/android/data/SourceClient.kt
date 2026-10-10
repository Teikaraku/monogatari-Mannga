package app.monogatari.android.data

import android.content.Context
import app.monogatari.android.sources.SourceRuntimeManager
import app.monogatari.android.sources.KavitaClient
import app.monogatari.android.sources.SourcePreferences
import app.monogatari.android.sources.SourcePreferenceBackend
import app.monogatari.android.sources.BuiltInBackend
import app.monogatari.android.sources.CopySource
import app.monogatari.android.sources.CopySourceClient

import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class SourceClient(private val secrets: SecretStore, private val context: Context? = null, providedCredentials: SourcePreferenceBackend? = null) {
    private val credentials = providedCredentials ?: object : SourcePreferenceBackend {
        override fun get(key: String) = secrets.get(key)
        override fun set(key: String, value: String) = secrets.set(key, value)
        override fun remove(key: String) = secrets.remove(key)
    }
    fun kavita(timeoutSeconds: Long? = null) = KavitaClient(secrets, requestHttp(timeoutSeconds), credentials)
    fun copy(source: SourceConfig): CopySourceClient {
        val preferences = context?.let { SourcePreferences(it, source.id) }
        val configuration = JSONObject(source.configuration)
        return CopySourceClient(source, http,
            preferences?.get("apiServer")?.toString() ?: configuration.optString("apiBase", CopySource.API),
            preferences?.get("apiVersion")?.toString() ?: configuration.optString("apiVersion", CopySource.API_VERSION),
            preferences?.get("contentApiServer")?.toString() ?: configuration.optString("contentApiBase", CopySource.CONTENT_API),
            preferences?.get("contentApiVersion")?.toString() ?: configuration.optString("contentApiVersion", CopySource.CONTENT_API_VERSION))
    }
    fun suwayomi(source: SourceConfig, preferences: SourcePreferences? = null) = app.monogatari.android.sources.SuwayomiClient(http, credentials, preferences ?: context?.let { SourcePreferences(it, source.id) })
    var builtInSessionFactory: ((SourceConfig) -> app.monogatari.android.sources.SourceSession)? = null
    var installedSources: (() -> List<SourceConfig>)? = null
    fun session(source: SourceConfig): app.monogatari.android.sources.SourceSession = if (source.type == "wasm") runtimes.runtime(source.id)
        else builtInSessionFactory?.invoke(source) ?: error("源后端尚未注册：${source.type}")
    val runtimes: SourceRuntimeManager get() = SourceRuntimeManager.get(context ?: error("漫画源运行器需要应用上下文"))
    val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    fun headers(source: SourceConfig): Map<String, String> {
        if (source.type == "suwayomi") credentials.get("${source.id}.cookie")?.let { return mapOf("Cookie" to it) }
        return credentials.get(source.id)?.let { mapOf("Authorization" to it) }
            ?: credentials.get("${source.id}.cookie")?.let { mapOf("Cookie" to it) } ?: emptyMap()
    }
    fun clearAuthentication(sourceId: String) { credentials.remove(sourceId); listOf("cookie", "apiKey", "refreshToken").forEach { credentials.remove("$sourceId.$it") } }
    fun saveCookie(sourceId: String, cookie: String) { if (cookie.isEmpty()) credentials.remove("$sourceId.cookie") else credentials.set("$sourceId.cookie", cookie) }
    fun url(source: SourceConfig, path: String): String = (source.baseUrl.trimEnd('/') + "/").toHttpUrl().resolve(path)?.toString() ?: error("无效地址")
    private fun requestHttp(timeoutSeconds:Long?):OkHttpClient = if (timeoutSeconds==null) http else http.newBuilder().connectTimeout(timeoutSeconds,TimeUnit.SECONDS).readTimeout(timeoutSeconds,TimeUnit.SECONDS).callTimeout(timeoutSeconds,TimeUnit.SECONDS).build()
    fun request(source: SourceConfig, path: String, body: JSONObject? = null, timeoutSeconds:Long?=null): String {
        val request = Request.Builder().url(url(source, path)).header("Accept", "application/json")
        val target = url(source, path).toHttpUrl(); val base = source.baseUrl.toHttpUrl()
        if (target.scheme == base.scheme && target.host == base.host && target.port == base.port) headers(source).forEach { (k, v) -> request.header(k, v) }
        if (body != null) request.post(body.toString().toRequestBody("application/json".toMediaType()))
        return requestHttp(timeoutSeconds).newCall(request.build()).execute().use { response ->
            require(response.isSuccessful) { if (response.code == 401) "登录失败，请检查账号和密码" else "服务器返回 HTTP ${response.code}" }
            val bytes = response.body?.byteStream()?.use { app.monogatari.android.sources.SourceHost.readBounded(it, 20 * 1024 * 1024) } ?: error("服务器没有返回数据")
            bytes.toString(Charsets.UTF_8)
        }
    }
    fun authenticate(source: SourceConfig, username: String, password: String) {
        if (source.type == "kavita") { kavita().authenticate(source, username, password)
            context?.let { SourcePreferences(it, source.id).apply { if (username.isBlank()) set("login_key", password) else { set("login", "logged_in"); set("login.username", username); set("login.password", password) } } }; return }
        if (source.type == "suwayomi") { suwayomi(source).authenticate(source, username, password); return }
        val previous = credentials.get(source.id)
        if (username.isNotBlank()) credentials.set(source.id, Credentials.basic(username, password)) else credentials.remove(source.id)
        try {
            when (source.type) {
                "komga" -> require(JSONObject(request(source, "api/v2/users/me")).optString("email") == username) { "Komga 登录账号与请求不符" }
                "suwayomi" -> graphql(source, "query { mangas(condition: {inLibrary: true}) { totalCount } }")
                else -> error("尚未适配此漫画源")
            }
        } catch (e: Exception) { if (previous == null) credentials.remove(source.id) else credentials.set(source.id, previous); throw e }
        context?.let { SourcePreferences(it, source.id).apply { set("login", "logged_in"); set("login.username", username); set("login.password", password) } }
    }
    private fun graphql(source: SourceConfig, query: String, variables: JSONObject = JSONObject()): JSONObject {
        val result = JSONObject(request(source, "api/graphql", JSONObject().put("query", query).put("variables", variables)))
        require(result.optJSONArray("errors")?.length().let { it == null || it == 0 }) { result.optJSONArray("errors")?.optJSONObject(0)?.optString("message") ?: "GraphQL 请求失败" }
        return result.getJSONObject("data")
    }
    fun search(source: SourceConfig, query: String, page: Int = 0): Pair<List<Manga>, Boolean> {
        if (source.type == CopySource.TYPE) return copy(source).catalog(app.monogatari.android.sources.CopyQuery(kind = if (query.isBlank()) "catalog" else "search", keyword = query), page + 1).let { it.entries to it.more }
        if (source.type in setOf("local", "komga", "kavita", "suwayomi") && builtInSessionFactory != null) return session(source).search(query, page + 1)
        return when (source.type) {
        "wasm" -> runtimes.runtime(source.id).search(query, page + 1)
        "kavita" -> kavita().search(source, query, page)
        "komga" -> {
            val body = JSONObject().put("condition", JSONObject().put("allOf", JSONArray().put(JSONObject().put("deleted", JSONObject().put("operator", "isFalse")))))
            if (query.isNotBlank()) body.put("fullTextSearch", query)
            val result = JSONObject(request(source, "api/v1/series/list?page=$page&size=40&sort=metadata.titleSort%2Casc", body))
            result.getJSONArray("content").objects().map { komgaManga(source, it) } to (page + 1 < result.optInt("totalPages"))
        }
        "suwayomi" -> {
            val result = graphql(source, "query { mangas(condition: {inLibrary: true}, orderBy: TITLE, orderByType: ASC) { nodes { id title author description genre thumbnailUrl } } }")
            result.getJSONObject("mangas").getJSONArray("nodes").objects().map { suwayomiManga(source, it) }
                .filter { it.title.contains(query, true) || it.author.contains(query, true) } to false
        }
        else -> error("尚未适配此漫画源")
    } }
    private fun komgaManga(source: SourceConfig, j: JSONObject): Manga {
        val metadata = j.optJSONObject("metadata") ?: JSONObject()
        val status = when (metadata.optString("status")) { "ONGOING" -> 1; "ENDED" -> 2; "ABANDONED" -> 3; "HIATUS" -> 4; else -> 0 }
        return Manga(j.getString("id"), source.id, metadata.optString("title").ifBlank { j.optString("name") },
            url(source, "api/v1/series/${j.getString("id")}/thumbnail"),
            metadata.optJSONArray("authors").objects().map { it.optString("name") }.distinct().joinToString(", "),
            metadata.optString("summary"), (metadata.optJSONArray("genres").strings() + metadata.optJSONArray("tags").strings()).distinct(), status)
    }
    private fun suwayomiManga(source: SourceConfig, j: JSONObject) = Manga(j.get("id").toString(), source.id, j.getString("title"),
        j.optString("thumbnailUrl").takeIf { it != "null" && it.isNotEmpty() }?.let { url(source, it) }.orEmpty(),
        j.optString("author").takeUnless { it == "null" }.orEmpty(), j.optString("description").takeUnless { it == "null" }.orEmpty(),
        j.optJSONArray("genre").strings())
    fun details(source: SourceConfig, manga: Manga): Manga {
        if (source.type == CopySource.TYPE) return copy(source).details(manga)
        if (source.type in setOf("komga", "kavita", "suwayomi") && builtInSessionFactory != null) return (session(source) as BuiltInBackend).details(manga)
        return when (source.type) {
        "wasm" -> runtimes.runtime(source.id).details(manga)
        "kavita" -> kavita().details(source, manga)
        "komga" -> {
            val details = komgaManga(source, JSONObject(request(source, "api/v1/series/${manga.key}")))
            val body = JSONObject().put("condition", JSONObject().put("allOf", JSONArray()
                .put(JSONObject().put("seriesId", JSONObject().put("operator", "is").put("value", manga.key)))
                .put(JSONObject().put("deleted", JSONObject().put("operator", "isFalse")))))
            val chapters = JSONObject(request(source, "api/v1/books/list?unpaged=true&sort=metadata.numberSort%2Cdesc", body))
                .getJSONArray("content").objects().filter { it.optJSONObject("media")?.optString("mediaProfile") != "EPUB" }.map { book ->
                    val metadata = book.optJSONObject("metadata") ?: JSONObject()
                    Chapter(book.getString("id"), metadata.optString("title").ifBlank { book.optString("name") }, metadata.optDouble("numberSort", 0.0),
                        total = book.optJSONObject("media")?.optInt("pagesCount") ?: 0)
                }
            details.copy(chapters = chapters)
        }
        "suwayomi" -> {
            val result = graphql(source, """query GetMangaUpdate(${ '$' }mangaId: Int!) {
                manga(id: ${ '$' }mangaId) { id title author description genre thumbnailUrl }
                chapters(condition: {mangaId: ${ '$' }mangaId}, orderBy: SOURCE_ORDER, orderByType: DESC) { nodes { id name chapterNumber } }
            }""", JSONObject().put("mangaId", manga.key.toInt()))
            suwayomiManga(source, result.getJSONObject("manga")).copy(chapters = result.getJSONObject("chapters").getJSONArray("nodes").objects().map {
                Chapter(it.get("id").toString(), it.optString("name"), it.optDouble("chapterNumber", 0.0))
            })
        }
        else -> manga
    } }
    fun pages(source: SourceConfig, chapter: Chapter): List<String> {
        if (source.type == CopySource.TYPE) return copy(source).pages(chapter)
        if (source.type in setOf("komga", "kavita", "suwayomi") && builtInSessionFactory != null) return (session(source) as BuiltInBackend).pages(chapter)
        return when (source.type) {
        "wasm" -> runtimes.runtime(source.id).pages(chapter)
        "kavita" -> kavita().pages(source, chapter)
        "komga" -> JSONArray(request(source, "api/v1/books/${chapter.key}/pages")).objects().map {
            val convert = if (it.optString("mediaType") in setOf("image/jpeg", "image/png", "image/gif", "image/webp")) "" else "?convert=png"
            url(source, "api/v1/books/${chapter.key}/pages/${it.getInt("number")}$convert")
        }
        "suwayomi" -> graphql(source, "mutation GetPages(${ '$' }chapterId: Int!) { fetchChapterPages(input: {chapterId: ${ '$' }chapterId}) { pages } }",
            JSONObject().put("chapterId", chapter.key.toInt())).getJSONObject("fetchChapterPages").getJSONArray("pages").strings().map { url(source, it) }
        else -> chapter.pages
    } }
    fun sourceList(address: String): SourceList {
        val requested = address.trim().toHttpUrl()
        val isJson = requested.encodedPath.endsWith(".json", true)
        val base = if (isJson) requested.resolve("./")!! else requested.newBuilder().encodedPath(requested.encodedPath.trimEnd('/') + "/").build()
        var response: String? = null; var failure: String? = null
        for (target in if (isJson) listOf(requested) else listOf(requested, base.resolve("index.min.json")!!, base.resolve("index.json")!!)) {
            http.newBuilder().followRedirects(true).build().newCall(Request.Builder().url(target).build()).execute().use {
                if (it.isSuccessful) { val bytes = app.monogatari.android.sources.SourceHost.readBounded(it.body?.byteStream() ?: error("空源列表"), 10 * 1024 * 1024); val candidate = bytes.toString(Charsets.UTF_8); if (runCatching { val json = org.json.JSONTokener(candidate).nextValue(); json is JSONArray || json is JSONObject && json.has("sources") }.getOrDefault(false)) response = candidate }
                else failure = "源列表返回 HTTP ${it.code}"
            }; if (response != null) break
        }
        val content = response ?: error(failure ?: "空源列表")
        val parsed = if (content.trimStart().startsWith("[")) JSONObject().put("name", "源列表").put("sources", JSONArray(content)) else JSONObject(content)
        return SourceList(address, parsed.optString("name", "源列表"), parsed.getJSONArray("sources").objects().map {
            val icon = it.optString("iconURL").ifBlank { it.optString("icon").takeIf(String::isNotEmpty)?.let { name -> "icons/$name" }.orEmpty() }
            val download = it.optString("downloadURL").ifBlank { it.optString("file").takeIf(String::isNotEmpty)?.let { name -> "sources/$name" }.orEmpty() }
            CatalogSource(it.getString("id"), it.getString("name"), it.optJSONArray("languages").strings().ifEmpty { listOf(it.optString("lang", "multi")) },
                it.optInt("version"), if (icon.isNotEmpty()) base.resolve(icon)?.toString().orEmpty() else "", if (download.isNotEmpty()) base.resolve(download)?.toString().orEmpty() else "", it.optInt("contentRating", it.optInt("nsfw")),
                it.optString("minAppVersion"), it.optString("maxAppVersion"), it.optJSONArray("altNames").strings(), it.optString("baseURL"), it.toString())
        })
    }
    fun imageRequest(source: SourceConfig, url: String): Request {
        if (source.type == CopySource.TYPE) return copy(source).imageRequest(url)
        if (source.type == "wasm") return runtimes.runtime(source.id).imageRequest(url)
        val request = Request.Builder().url(url)
        request.header("Accept", "image/*")
        val preference = context?.let { SourcePreferences(it, source.id) }
        val bases = (listOf(source.baseUrl, preference?.get("server")?.toString().orEmpty()) +
            (preference?.get("mirrors") as? JSONArray)?.strings().orEmpty()).filter { it.isNotEmpty() }.mapNotNull { runCatching { it.toHttpUrl() }.getOrNull() }
        val target = url.toHttpUrl()
        if (bases.any { target.scheme == it.scheme && target.host == it.host && target.port == it.port }) headers(source).forEach { (k, v) -> request.header(k, v) }
        return request.build()
    }
    fun imageBytes(source: SourceConfig, url: String): ByteArray {
        if (url.startsWith("file:")) return File(android.net.Uri.parse(url).path ?: error("无效本地页面")).readBytes()
        if (source.type == "wasm") return runtimes.runtime(source.id).imageBytes(url)
        fun load(): Pair<Int, ByteArray> {
            var address = url
            repeat(10) { http.newCall(imageRequest(source, address)).execute().use { response ->
                if (response.code in 300..399 && response.header("Location") != null) address = response.request.url.resolve(response.header("Location")!!)!!.toString()
                else return response.code to if (response.isSuccessful) app.monogatari.android.sources.SourceHost.readBounded(response.body?.byteStream() ?: error("空图片"), 50 * 1024 * 1024) else ByteArray(0)
            } }
            error("图片重定向次数过多")
        }
        var result = load()
        if (result.first == 401) { val target = url.toHttpUrl(); val base = source.baseUrl.toHttpUrl()
            if (target.scheme == base.scheme && target.host == base.host && target.port == base.port) {
                val recovered = when (source.type) { "kavita" -> kavita().refreshToken(source); "suwayomi" -> suwayomi(source).recover(source); else -> false }
                if (recovered) result = load()
            }
        }
        require(result.first in 200..299) { "下载失败：HTTP ${result.first}" }; return result.second
    }
    fun imageBytes(source: SourceConfig, chapter: Chapter, url: String): ByteArray = imageBytes(source, url)
    fun coverBytes(source: SourceConfig, url: String): ByteArray = if (source.type == "wasm") runtimes.runtime(source.id).imageBytes(url, cover = true) else imageBytes(source, url)
    fun resolveDeepLink(address: String): app.monogatari.android.sources.SourceDeepLink? {
        val target = address.toHttpUrlOrNull() ?: return null
        installedSources?.invoke().orEmpty().filter { it.type == CopySource.TYPE }.forEach { source ->
            val base = source.baseUrl.toHttpUrlOrNull()
            val sameProvider = target.host == base?.host || target.host in setOf("www.2026copy.com", "2026copy.com", "www.copy4000.com", "copy4000.com")
            val parts = target.pathSegments
            if (sameProvider && parts.firstOrNull() == "comic" && parts.getOrNull(1)?.isNotBlank() == true) {
                return app.monogatari.android.sources.SourceDeepLink(source.id, Manga(parts[1], source.id, "", url = address),
                    chapterKey = if (parts.getOrNull(2) == "chapter") parts.getOrNull(3) else null)
            }
        }
        val sources = runtimes.installed().filter { installed ->
            (installed.info.optJSONArray("urls")?.strings().orEmpty() + installed.baseUrl).any { runCatching { it.toHttpUrl().host == target.host }.getOrDefault(false) }
        }
        for (source in sources) runtimes.runtime(source.id).deepLink(address)?.let { return it }
        return null
    }
    fun handleOAuthCallback(address: String): Boolean {
        for (source in runtimes.installed()) if (app.monogatari.android.sources.SourceOAuthSession.resume(runtimes.runtime(source.id), address)) return true
        return false
    }
    fun download(source: SourceConfig, pageUrl: String, destination: File) {
        if (source.type == "wasm") { destination.writeBytes(imageBytes(source, pageUrl)); return }
        destination.writeBytes(imageBytes(source, pageUrl))
    }
}
