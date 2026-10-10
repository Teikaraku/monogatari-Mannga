package app.monogatari.android.sources

import app.monogatari.android.data.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ConnectionPool
import okhttp3.Protocol
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZoneId
import java.util.UUID
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import java.security.cert.CertificateException

object CopySource {
    const val ID = "copy2026"
    const val TYPE = "copy"
    const val WEBSITE = "https://www.2026copy.com"
    const val MAINLAND_WEBSITE = "https://www.copy4000.com"
    const val API = "https://api.copy2000.online"
    const val API_VERSION = "3.0.9"
    const val CONTENT_API = "https://www.manga2026.xyz"
    const val CONTENT_BACKUP = "https://mapi.hotmangasg.com"
    const val CONTENT_API_VERSION = "2024.4.28"
    const val INSTALL_MARKER = "Android.Sources.copy2026.initialized"
    val configuration = SourceConfig(ID, "拷贝漫画", TYPE, WEBSITE,
        JSONObject().put("apiBase", API).put("apiVersion", API_VERSION).put("contentApiBase", CONTENT_API).put("contentApiVersion", CONTENT_API_VERSION)
            .put("languages", JSONArray(listOf("zh"))).put("contentRating", 1).toString())

    /** The site's direct CDN serves the same paths; its Cloudflare image alias is blocked on some routes. */
    fun imageUrl(address: String): String {
        val url = runCatching { address.toHttpUrl() }.getOrNull() ?: return address
        if (url.scheme != "https" || url.port != 443 || !Regex("s[0-9a-z]\\.mangafunb\\.fun").matches(url.host)) return address
        return url.newBuilder().host(url.host.removeSuffix(".mangafunb.fun") + ".mangafuna.xyz").build().toString()
    }
}

data class CopyPage<T>(val entries: List<T>, val more: Boolean)
data class CopySection(val id: String, val title: String, val entries: List<Manga>)
data class CopyFeature(val title: String, val image: String, val manga: Manga)
data class CopyTopic(val key: String, val title: String, val image: String, val subtitle: String = "", val intro: String = "")
data class CopyRanking(val period: String, val title: String, val entries: List<Manga>)
data class CopyHome(val features: List<CopyFeature>, val sections: List<CopySection>, val topics: List<CopyTopic>, val rankings: List<CopyRanking> = emptyList(),
    val notice: String? = null, val diagnostics: String? = null)
data class CopyQuery(val kind: String = "catalog", val keyword: String = "", val theme: String = "",
    val order: String = "-datetime_updated", val status: String = "", val region: String = "",
    val rankPeriod: String = "day", val audience: String = "male", val topic: String = "")

/** JSON data transport only. The app supplies every view and never loads website HTML here. */
class CopySourceClient(val source: SourceConfig, http: OkHttpClient,
    apiAddress: String = JSONObject(source.configuration).optString("apiBase", CopySource.API),
    private val version: String = JSONObject(source.configuration).optString("apiVersion", CopySource.API_VERSION),
    contentAddress: String = JSONObject(source.configuration).optString("contentApiBase", CopySource.CONTENT_API),
    private val contentVersion: String = JSONObject(source.configuration).optString("contentApiVersion", CopySource.CONTENT_API_VERSION),
    searchAddresses: List<String>? = null,
    contentAlternates: List<String>? = null,
    searchApiAddress: String? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val pause: (Long) -> Unit = Thread::sleep) {
    private val api = checkedAddress(apiAddress)
    private val contentApi = checkedAddress(contentAddress)
    private val contentBackups = (contentAlternates ?: if (contentApi.host == "www.manga2026.xyz") listOf(CopySource.CONTENT_BACKUP) else emptyList()).map(::checkedAddress)
    @Volatile private var preferredContent = if (contentAlternates == null && contentApi.host == "www.manga2026.xyz")
        checkedAddress(CopySource.CONTENT_BACKUP) else contentApi
    private val imageHttp = http.newBuilder().followRedirects(true).followSslRedirects(false)
        .callTimeout(90, java.util.concurrent.TimeUnit.SECONDS).build()
    private val http = http.newBuilder().connectTimeout(minOf(http.connectTimeoutMillis.toLong(), 8000), java.util.concurrent.TimeUnit.MILLISECONDS)
        .readTimeout(minOf(http.readTimeoutMillis.toLong(), 20_000), java.util.concurrent.TimeUnit.MILLISECONDS).callTimeout(25, java.util.concurrent.TimeUnit.SECONDS).build()
    private val http1 = this.http.newBuilder().connectionPool(ConnectionPool()).protocols(listOf(Protocol.HTTP_1_1)).build()
    @Volatile private var preferHttp1 = false
    private val website = checkedAddress(source.baseUrl.ifBlank { CopySource.WEBSITE })
    private val searchBases = (searchAddresses ?: if (website.host in setOf("www.2026copy.com", "www.copy4000.com"))
        listOf(CopySource.MAINLAND_WEBSITE, CopySource.WEBSITE) else listOf(website.toString())).map(::checkedAddress).distinct()
    private data class SearchEndpoint(val base: HttpUrl, val api: Boolean)
    private class SearchState(var endpoint: SearchEndpoint? = null)
    private val searches = LinkedHashMap<String, SearchState>(16, .75f, true)
    private val searchApi = (searchApiAddress ?: if (searchAddresses == null && website.host in setOf("www.2026copy.com", "www.copy4000.com"))
        CopySource.CONTENT_BACKUP else null)?.let(::checkedAddress)
    private val policy = CopyRequestPolicy(now, pause)
    private data class CachedPages(val until: Long, val pages: List<String>)
    private val pageCache = LinkedHashMap<String, CachedPages>(32, .75f, true)
    private var lastPageRequest = 0L

    private fun checkedAddress(address: String): HttpUrl = address.trim().trimEnd('/').toHttpUrl().also {
        require(it.scheme == "https" || it.scheme == "http") { "图源接口地址必须使用 HTTP 或 HTTPS" }
        require(it.username.isBlank() && it.password.isBlank()) { "图源地址不能包含账号密码" }
    }
    private fun apiUrl(path: String, parameters: Map<String, String> = emptyMap()): HttpUrl =
        api.newBuilder().addPathSegments(path.trimStart('/')).apply { parameters.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
    private fun contentUrl(path: String, parameters: Map<String, String> = emptyMap()): HttpUrl =
        contentApi.newBuilder().addPathSegments(path.trimStart('/')).addQueryParameter("platform", "3")
            .apply { parameters.forEach { (key, value) -> addQueryParameter(key, value) } }.build()

    private class HttpFailure(val status: Int, message: String) : IOException(message)
    private class EmptyHomeFailure(message: String) : IllegalStateException(message)

    private fun result(original: HttpUrl, label: String, content: Boolean = false, fallback: Boolean = true): JSONObject {
        val url = if (content && original.host == contentApi.host && original.port == contentApi.port)
            preferredContent.newBuilder().encodedPath(original.encodedPath).encodedQuery(original.encodedQuery).build() else original
        try { return requestWithRetry(url, label, content) }
        catch (failure: IOException) {
            if (failure is CopyRateLimitFailure) throw failure
            if (Thread.currentThread().isInterrupted) throw failure
            if (content && fallback && (failure !is HttpFailure || failure.status >= 500)) {
                val failures = mutableListOf(connectionFailure(url, label, failure))
                for (base in (listOf(contentApi) + contentBackups).distinct().filter { it.host != url.host || it.port != url.port }) {
                    val alternate = base.newBuilder().encodedPath(url.encodedPath).encodedQuery(url.encodedQuery).build()
                    try { return requestWithRetry(alternate, label, true).also { preferredContent = base } }
                    catch (secondary: Exception) {
                        if (Thread.currentThread().isInterrupted || secondary is CopyRateLimitFailure) throw secondary
                        failures += connectionFailure(alternate, label, secondary)
                    }
                }
                throw IOException(failures.joinToString("\n备用 HTTPS 接口："), failure)
            }
            val canRetry = fallback && !content && api != contentApi && url.host == api.host && url.port == api.port &&
                url.encodedPath.startsWith("/api/v3/") && (failure !is HttpFailure || failure.status >= 500)
            if (!canRetry) throw IOException(connectionFailure(url, label, failure), failure)
            // The legacy catalog ignores these modern filters; don't show an unfiltered
            // list under the finished/status/region collection the user selected.
            if (url.encodedPath == "/api/v3/comics" && listOf("top", "status").any { !url.queryParameter(it).isNullOrEmpty() }) {
                throw IOException("筛选目录暂时连接失败，备用接口不支持这个筛选条件。请稍后重试或更换网络。\n" +
                    connectionFailure(url, label, failure), failure)
            }
            val alternate = contentApi.newBuilder().encodedPath(url.encodedPath).encodedQuery(url.encodedQuery)
                .apply { if (url.queryParameter("platform") == null) addQueryParameter("platform", "3") }.build()
            try { return result(alternate, label, true) }
            catch (secondary: Exception) {
                throw IOException(connectionFailure(url, label, failure) + "\n备用 HTTPS 接口：" +
                    connectionFailure(alternate, label, secondary), secondary)
            }
        }
    }

    private fun requestWithRetry(url: HttpUrl, label: String, content: Boolean): JSONObject {
        var attempt = 0
        while (true) {
            policy.before(url)
            try { return requestTransiently(url, label, content, attempt) }
            catch (failure: CopyRateLimitFailure) {
                if (attempt++ >= 2 || failure.retryAt - now() > CopyRequestPolicy.MAX_AUTOMATIC_WAIT) throw failure
            }
        }
    }

    private fun requestTransiently(url: HttpUrl, label: String, content: Boolean, rateAttempt: Int): JSONObject {
        try { return requestResult(url, label, content, rateAttempt = rateAttempt) }
        catch (failure: IOException) {
            val certificateFailure = generateSequence<Throwable>(failure) { it.cause }
                .any { it is CertificateException || it is SSLPeerUnverifiedException }
            if (failure is HttpFailure || failure is CopyRateLimitFailure || certificateFailure || Thread.currentThread().isInterrupted) throw failure
            // Switching Wi-Fi/cellular can leave a stale pooled connection. Retry once with a fresh pool.
            // Some mobile routes stall an HTTP/2 stream. The one fresh retry still verifies HTTPS,
            // and also supports servers/routes that only carry HTTP/1.1 reliably.
            val fresh = http.newBuilder().connectionPool(ConnectionPool()).protocols(listOf(Protocol.HTTP_1_1)).build()
            try { return requestResult(url, label, content, fresh, rateAttempt).also { preferHttp1 = true } }
            finally { fresh.connectionPool.evictAll() }
        }
    }

    private fun connectionFailure(url: HttpUrl, label: String, error: Exception): String {
        val reason = when (error) {
            is SSLException -> "安全连接失败（TLS），请检查网络或稍后重试"
            else -> error.message ?: "网络请求失败"
        }
        return "拷贝漫画${label}（${url.host}）：$reason"
    }

    private fun requestResult(url: HttpUrl, label: String, content: Boolean, transport: OkHttpClient = if (preferHttp1) http1 else http, rateAttempt: Int = 0): JSONObject {
        val request = Request.Builder().url(url).header("User-Agent", "Monogatari/0.9")
            .header("Accept", "application/json").header("Accept-Language", "zh-Hant,zh;q=0.9").header("Cache-Control", "no-cache")
            .header("version", if (content) contentVersion else version).header("platform", "3")
            .apply { if (content) { header("dnts", "3"); header("webp", "1"); header("Referer", website.toString()) } else header("region", "0") }.build()
        return transport.newCall(request).execute().use { response ->
            val bytes = response.body?.byteStream()?.use { SourceHost.readBounded(it, 20 * 1024 * 1024) } ?: byteArrayOf()
            val json = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull()
            val message = json?.stringOrNull("message")?.takeIf { it.isNotBlank() }
            if (response.code == 429 || json?.optInt("code") == 429)
                throw policy.limited(url, response.header("Retry-After"), response.header("Date"), rateAttempt)
            if (!response.isSuccessful) throw HttpFailure(response.code, "拷贝漫画${label}失败：${message ?: "HTTP ${response.code}"}")
            requireNotNull(json) { "拷贝漫画${label}接口未返回 JSON 数据" }
            require(json.optInt("code", 200) == 200) { "拷贝漫画${label}：${message ?: "接口访问受限"}" }
            json.optJSONObject("results") ?: error("拷贝漫画暂未返回${label}数据，可能存在接口或客户端访问限制。")
        }
    }

    fun home(): CopyHome {
        try { return parseHome(result(apiUrl("api/v3/h5/homeIndex"), "首页", fallback = false)) }
        catch (error: Exception) {
            if (Thread.currentThread().isInterrupted) throw error
            if (error is CopyRateLimitFailure) throw error
            if (error is EmptyHomeFailure) {
                try { return parseHome(result(apiUrl("api/v3/h5/homeIndex", mapOf("_update" to "true", "_" to "${now()}")), "首页", fallback = false)) }
                catch (retry: Exception) {
                    if (Thread.currentThread().isInterrupted) throw retry
                    if (retry is CopyRateLimitFailure) throw retry
                    return alternateHome(retry)
                }
            }
            return alternateHome(error)
        }
    }

    private fun alternateHome(primary: Exception): CopyHome {
        if (api != contentApi) {
            try {
                var home = parseHome(result(contentUrl("api/v3/h5/homeIndex"), "备用首页", content = true))
                if (home.sections.none { it.id == "newComics" } || home.sections.none { it.id == "finishComics" }) {
                    try {
                        val feeds = recoverHome(primary)
                        home = home.copy(sections = home.sections + feeds.sections.filter { feed -> home.sections.none { it.id == feed.id } },
                            rankings = home.rankings + feeds.rankings.filter { feed -> home.rankings.none { it.period == feed.period } })
                    } catch (error: Exception) { if (Thread.currentThread().isInterrupted || error is CopyRateLimitFailure) throw error }
                }
                return home.copy(notice = "已使用备用首页接口。", diagnostics = "首页接口：${primary.message}\n备用首页：${contentApi.host}")
            } catch (alternate: Exception) {
                if (Thread.currentThread().isInterrupted || alternate is CopyRateLimitFailure) throw alternate
                return recoverHome(IOException("${primary.message}\n备用首页（${contentApi.host}）：${alternate.message}", alternate))
            }
        }
        return recoverHome(primary)
    }

    internal fun parseHome(json: JSONObject): CopyHome {
        val definitions = listOf("recComics" to "为你推荐", "newComics" to "新书入库", "hotComics" to "热门更新",
            "rankDayComics" to "今日热门", "finishComics" to "完结佳作")
        val sections = definitions.mapNotNull { (key, title) ->
            val entries = items(json.opt(key)).map(::manga).distinctBy { it.id }
            if (entries.isEmpty()) null else CopySection(key, title, entries)
        }.toMutableList()
        if (sections.none { it.id == "hotComics" }) {
            val updates = (items(json.opt("updateWeeklyFreeComics")) + items(json.opt("updateWeeklyChargeComics"))).map(::manga).distinctBy { it.id }
            if (updates.isNotEmpty()) sections += CopySection("hotComics", "本周更新", updates)
        }
        val features = items(json.opt("banners")).filter { it.optInt("type") == 1 && it.optJSONObject("comic") != null }.map { banner ->
            val comic = manga(banner.getJSONObject("comic"))
            CopyFeature(banner.optString("brief").ifBlank { comic.title }, webUrl(banner.optString("cover")), comic)
        }
        val topics = items(json.opt("topicsList")).ifEmpty { items(json.opt("topics")) }.map(::topic)
        val rankings = listOf("day" to "日榜", "week" to "周榜", "month" to "月榜").mapNotNull { (period, title) ->
            val key = "rank${period.replaceFirstChar(Char::uppercaseChar)}Comics"
            val values = items(json.opt(key)).ifEmpty { if (period == "week") items(json.opt("rankWeeklyFreeComics")) else emptyList() }
            values.map(::manga).distinctBy { it.id }.takeIf { it.isNotEmpty() }?.let { CopyRanking(period, title, it) }
        }
        if (sections.isEmpty() && features.isEmpty() && rankings.isEmpty())
            throw EmptyHomeFailure("首页返回成功，但没有可显示的漫画。返回结构：${homeSchema(json)}")
        return CopyHome(features, sections, topics, rankings)
    }

    private fun homeSchema(json: JSONObject): String = json.keys().asSequence().sorted().take(16).joinToString("；") { key ->
        val value = json.opt(key)
        val entries = items(value)
        val sample = entries.firstOrNull()
        val comic = sample?.optJSONObject("comic") ?: sample
        val shape = when (value) { is JSONObject -> "对象(${value.keys().asSequence().sorted().joinToString(",")})"; is JSONArray -> "数组"; is String -> "字符串"; JSONObject.NULL -> "null"; else -> "${value?.javaClass?.simpleName}" }
        val valid = entries.count { runCatching { manga(it) }.isSuccess }
        "$key=$shape，条目${entries.size}，有效漫画$valid" + (comic?.let { "，漫画字段(${it.keys().asSequence().sorted().joinToString(",")})，path_word=${it.opt("path_word")?.javaClass?.simpleName}" } ?: "")
    }.ifEmpty { "空对象 {}" }

    /** The aggregated homepage can fail independently of the catalog. Never cache that as an empty success. */
    private fun recoverHome(primary: Exception): CopyHome {
        data class Feed(val id: String, val title: String, val books: List<Manga> = emptyList(),
            val topics: List<CopyTopic> = emptyList(), val error: String? = null)
        val requests = listOf(
            "newComics" to "新书入库", "hotComics" to "热门更新", "finishComics" to "完结佳作",
            "day" to "日榜", "week" to "周榜", "month" to "月榜", "topics" to "精选专题")
        val executor = Executors.newFixedThreadPool(3)
        val feeds = try {
            executor.invokeAll(requests.map { (id, title) -> Callable {
                try {
                    if (id == "topics") Feed(id, title, topics = topics(1).entries)
                    else {
                        val query = when (id) {
                            "newComics" -> CopyQuery(order = "-datetime_created")
                            "hotComics" -> CopyQuery(order = "-datetime_updated")
                            "finishComics" -> CopyQuery(kind = "finished")
                            else -> CopyQuery(kind = "ranking", rankPeriod = id)
                        }
                        Feed(id, title, books = catalog(query).entries)
                    }
                } catch (failure: Exception) { Feed(id, title, error = "$title：${failure.message}") }
            } }).map { it.get() }
        } finally { executor.shutdownNow() }
        val sections = feeds.filter { it.id.endsWith("Comics") && it.books.isNotEmpty() }.map { CopySection(it.id, it.title, it.books) }
        val rankings = feeds.filter { it.id in setOf("day", "week", "month") && it.books.isNotEmpty() }.map { CopyRanking(it.id, it.title, it.books) }
        val diagnostics = (listOf("首页接口：${primary.message}") + feeds.mapNotNull { it.error }).joinToString("\n")
        require(sections.isNotEmpty() || rankings.isNotEmpty()) { "首页加载失败，请稍后重试。\n$diagnostics" }
        return CopyHome(emptyList(), sections, feeds.first { it.id == "topics" }.topics, rankings,
            notice = "首页接口暂不可用，已从漫画目录恢复内容。", diagnostics = diagnostics)
    }

    fun catalog(query: CopyQuery, page: Int = 1): CopyPage<Manga> {
        require(page in 1..100_000) { "无效页码" }
        val limit = 30
        val parameters = linkedMapOf("limit" to limit.toString(), "offset" to ((page - 1) * limit).toString())
        val json = when (query.kind) {
            "search" -> {
                if (query.keyword.isBlank()) return CopyPage(emptyList(), false)
                search(query.keyword.trim(), page, limit)
            }
            "ranking" -> {
                require(query.rankPeriod in setOf("day", "week", "month", "total") && query.audience in setOf("male", "female")) { "无效排行榜选项" }
                parameters.putAll(mapOf("type" to "1", "audience_type" to query.audience, "date_type" to query.rankPeriod))
                result(apiUrl("api/v3/ranks", parameters), "排行榜")
            }
            "topic" -> result(apiUrl("api/v3/topic/${pathKey(query.topic)}/contents", parameters), "专题漫画")
            else -> {
                parameters["ordering"] = query.order
                if (query.theme.isNotBlank()) parameters["theme"] = query.theme
                if (query.status.isNotBlank()) parameters["status"] = query.status
                if (query.region.isNotBlank()) parameters["top"] = query.region
                if (query.kind == "finished") parameters["top"] = "finish"
                result(apiUrl("api/v3/comics", parameters), "漫画目录")
            }
        }
        val entries = items(json).map(::manga).distinctBy { it.id }
        return CopyPage(entries, hasMore(json, entries.size, page, limit))
    }

    private fun search(keyword: String, page: Int, limit: Int): JSONObject {
        val state = synchronized(searches) {
            if (page == 1) SearchState().also { searches[keyword] = it; while (searches.size > 16) searches.remove(searches.keys.first()) }
            else searches[keyword] ?: SearchState()
        }
        val selected = synchronized(searches) { state.endpoint }
        if (page > 1 && searchApi != null && selected == null) error("搜索状态已失效，请重新搜索。")
        val defaults = listOfNotNull(searchApi?.let { SearchEndpoint(it, true) }) + searchBases.map { SearchEndpoint(it, false) }
        val choices = if (page > 1 && selected != null) listOf(selected) + defaults.filter { it != selected && it.api == selected.api } else defaults
        val failures = mutableListOf<String>()
        for ((index, endpoint) in choices.withIndex()) {
            val url = endpoint.base.newBuilder().addPathSegments(if (endpoint.api) "api/v3/search/comic" else "api/kb/web/searchcl/comics")
                .addQueryParameter("q", keyword).addQueryParameter("q_type", "").addQueryParameter("platform", if (endpoint.api) "3" else "2")
                .addQueryParameter("limit", "$limit").addQueryParameter("offset", "${(page - 1) * limit}").build()
            try {
                val data = requestWithRetry(url, "搜索", endpoint.api)
                synchronized(searches) { if (searches[keyword] === state) state.endpoint = endpoint }
                return data
            }
            catch (error: IOException) {
                if (error is CopyRateLimitFailure || Thread.currentThread().isInterrupted ||
                    error is HttpFailure && error.status !in setOf(404, 408) && error.status < 500) throw error
                failures += connectionFailure(url, "搜索", error)
                if (index == choices.lastIndex) throw IOException(failures.joinToString("\n"), error)
            }
        }
        error("没有可用的搜索地址")
    }

    fun topics(page: Int): CopyPage<CopyTopic> {
        require(page in 1..100_000)
        val limit = 20
        val json = result(apiUrl("api/v3/topics", mapOf("limit" to "$limit", "offset" to "${(page - 1) * limit}")), "专题")
        val entries = items(json).map(::topic)
        return CopyPage(entries, hasMore(json, entries.size, page, limit))
    }
    fun topicDetail(key: String): CopyTopic = topic(result(apiUrl("api/v3/topic/${pathKey(key)}"), "专题详情"))

    fun details(existing: Manga): Manga {
        val json = result(contentUrl("api/v3/comic2/${pathKey(existing.key)}"), "漫画详情", content = true)
        val comic = json.optJSONObject("comic") ?: error("拷贝漫画没有返回漫画详情")
        val groups = json.optJSONObject("groups")?.let { value -> value.keys().asSequence().sortedWith(compareBy<String> { it != "default" }.thenBy { it })
            .map { key -> key to (value.optJSONObject(key) ?: JSONObject().put("name", key)) }.toList() }
            ?: listOf("default" to JSONObject().put("name", ""))
        val chapters = mutableListOf<Chapter>()
        for ((key, group) in groups) {
            var offset = 0
            val seen = mutableSetOf<String>()
            while (true) {
                val data = result(contentUrl("api/v3/comic/${pathKey(existing.key)}/group/${pathKey(key)}/chapters",
                    mapOf("limit" to "100", "offset" to "$offset", "_update" to "true")), "章节列表", content = true)
                val raw = items(data)
                val additions = raw.filter { seen.add(it.optString("uuid").ifBlank { it.optString("id") }) }
                require(raw.isEmpty() || additions.isNotEmpty()) { "拷贝章节分页重复，已停止加载" }
                additions.forEach { row ->
                    val id = row.optString("uuid").ifBlank { row.optString("id") }
                    require(id.isNotBlank()) { "章节缺少标识" }
                    val chapterType = row.optInt("type", 1)
                    val title = row.optString("name")
                    val caption = captionNumbers(title, chapterType)
                    val number = if (chapterType == 3) null else (row.value("chapter") as? Number)?.toDouble() ?: caption.first
                    val volume = (row.value("volume") as? Number)?.toDouble() ?: caption.second
                    val metadata = JSONObject(row.toString()).put("copyMangaKey", existing.key).put("copyGroup", key).put("chapterNumber", number ?: JSONObject.NULL)
                    chapters += Chapter(id, title, number = number ?: 0.0,
                        volume = volume, language = "zh", scanlator = group.optString("name"),
                        dateUploaded = timestamp(row.optString("datetime_created")).takeIf { it > 0 } ?: uuidTimestamp(id),
                        url = website.newBuilder().addPathSegments("comic/${pathKey(existing.key)}/chapter/${pathKey(id)}").build().toString(),
                        sourceOrder = chapters.size, locked = row.optBoolean("is_locked", row.optBoolean("is_lock")),
                        metadata = metadata.toString())
                }
                offset += raw.size
                if (raw.isEmpty() || offset >= data.optInt("total", offset) || chapters.size >= 100_000) break
            }
        }
        // The provider's index preserves volume series (01..18, then D01..D18) and extras.
        val groupOrder = groups.mapIndexed { index, pair -> pair.first to index }.toMap()
        val unique = chapters.distinctBy { it.key }
        val positions = unique.associate { chapter ->
            val meta = JSONObject(chapter.metadata)
            chapter.key to ((groupOrder[meta.optString("copyGroup")] ?: 0) to
                ((meta.value("index") as? Number)?.toDouble() ?: chapter.sourceOrder.toDouble()))
        }
        val ordered = unique.sortedWith(compareByDescending<Chapter> { it.dateUploaded }
            .thenBy { positions.getValue(it.key).first }.thenByDescending { positions.getValue(it.key).second })
            .mapIndexed { index, chapter -> chapter.copy(sourceOrder = index) }
        return manga(comic).copy(chapters = ordered)
    }
    @Synchronized fun pages(chapter: Chapter): List<String> {
        val key = JSONObject(chapter.metadata).optString("copyMangaKey")
        require(key.isNotBlank()) { "章节缺少漫画标识，请先刷新漫画详情" }
        val url = contentUrl("api/v3/comic/${pathKey(key)}/chapter/${pathKey(chapter.key)}")
        pageCache[url.toString()]?.takeIf { it.until > now() }?.let { return it.pages }
        policy.before(url)
        val spacing = lastPageRequest + 500 - now()
        if (spacing > 0) pause(spacing)
        lastPageRequest = now()
        val data = result(url, "阅读图片", content = true)
        require(!data.optBoolean("is_lock")) { "该章节需要拷贝账号的阅读权限" }
        val content = data.optJSONObject("chapter") ?: error("拷贝漫画未返回章节图片")
        val pages = orderedPages(content).map(::webUrl)
        pageCache[url.toString()] = CachedPages(now() + 300_000, pages)
        while (pageCache.size > 32) pageCache.remove(pageCache.keys.first())
        return pages
    }

    fun manga(raw: JSONObject): Manga {
        if (raw.has("sourceId") && raw.has("key")) return Manga.parse(raw)
        val json = raw.optJSONObject("comic") ?: raw
        val key = json.optString("path_word").ifBlank { json.optString("uuid") }
        require(key.isNotBlank()) { "漫画缺少标识" }
        val status = json.optJSONObject("status")?.optInt("value") ?: (json.value("status") as? Number)?.toInt()
        val meta = JSONObject(json.toString())
        raw.value("sort")?.let { meta.put("copyRank", it) }
        raw.value("sort_last")?.let { meta.put("copyRankLast", it) }
        raw.value("rise_num")?.let { meta.put("copyRankHeat", it) }
        // Update feeds wrap the comic with the chapter that was just published.
        if (raw.has("comic")) raw.optString("name").takeIf { it.isNotBlank() }?.let { meta.put("copyLatestChapter", it) }
        return Manga(key, source.id, json.optString("name"), cover = webUrl(json.optString("cover")),
            author = json.optJSONArray("author").objects().joinToString("、") { it.optString("name") },
            description = json.optString("brief"), tags = json.optJSONArray("theme").objects().map { it.optString("name") },
            status = when (status) { 0 -> 1; 1, 2 -> 2; else -> 0 }, language = "zh", contentRating = 1,
            url = website.newBuilder().addPathSegments("comic/${pathKey(key)}").build().toString(), metadata = meta.toString())
    }
    private fun topic(json: JSONObject) = CopyTopic(json.optString("path_word").ifBlank { json.optString("path") },
        json.optString("title"), webUrl(json.optString("cover")), listOf(json.optString("period"), json.optString("datetime_created")).filter { it.isNotBlank() }.joinToString(" · "),
        json.optString("intro").ifBlank { json.optString("brief") })

    fun imageRequest(url: String): Request {
        val target = checkedAddress(CopySource.imageUrl(url))
        return Request.Builder().url(target).header("User-Agent", "Monogatari/0.9").header("Accept", "image/*")
            .header("Referer", website.toString()).build()
    }

    fun imageBytes(address: String): ByteArray {
        val request = imageRequest(address)
        var attempt = 0
        while (true) {
            policy.before(request.url)
            val transport = if (attempt == 0) imageHttp else imageHttp.newBuilder().connectionPool(ConnectionPool()).protocols(listOf(Protocol.HTTP_1_1)).build()
            try {
                return transport.newCall(request).execute().use { response ->
                    if (response.code == 429) throw policy.limited(request.url, response.header("Retry-After"), response.header("Date"), attempt)
                    if (!response.isSuccessful) throw HttpFailure(response.code, "拷贝漫画图片下载失败：HTTP ${response.code}")
                    SourceHost.readBounded(response.body?.byteStream() ?: error("服务器没有返回图片"), 50 * 1024 * 1024)
                }
            } catch (error: IOException) {
                val untrusted = generateSequence<Throwable>(error) { it.cause }.any { it is CertificateException || it is SSLPeerUnverifiedException }
                if (attempt++ >= 2 || Thread.currentThread().isInterrupted || untrusted || error is HttpFailure ||
                    error is CopyRateLimitFailure && error.retryAt - now() > CopyRequestPolicy.MAX_AUTOMATIC_WAIT) throw error
            } finally { if (attempt > 0 && transport !== imageHttp) transport.connectionPool.evictAll() }
        }
    }
    private fun webUrl(address: String): String = address.takeIf { it.isNotBlank() }?.let { CopySource.imageUrl(website.resolve(it)?.toString() ?: error("无效漫画资源地址")) }.orEmpty()
    private fun pathKey(key: String): String = key.also { require(it.isNotBlank() && it.length <= 256 && '/' !in it && '\\' !in it && it !in setOf(".", "..")) { "无效漫画标识" } }
    private fun items(value: Any?): List<JSONObject> {
        val array = when (value) { is JSONArray -> value; is JSONObject -> value.optJSONArray("list"); else -> null }
            ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }
    private fun hasMore(data: JSONObject, count: Int, page: Int, limit: Int): Boolean {
        val offset = data.optInt("offset", (page - 1) * limit)
        val actualLimit = data.optInt("limit", limit)
        return count > 0 && if (data.has("total")) offset + count < data.optInt("total") else count >= actualLimit
    }
    private fun timestamp(value: String): Long = runCatching { LocalDateTime.parse(value.replace(' ', 'T')).toInstant(ZoneOffset.ofHours(8)).toEpochMilli() }
        .getOrElse { runCatching { LocalDate.parse(value).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrDefault(0) }

    companion object {
        private fun uuidTimestamp(key: String): Long = runCatching {
            val uuid = UUID.fromString(key)
            when (uuid.version()) { 1 -> (uuid.timestamp() - 122192928000000000L) / 10_000; 7 -> uuid.mostSignificantBits ushr 16; else -> 0L }
        }.getOrDefault(0)
        internal fun captionNumbers(title: String, type: Int): Pair<Double?, Double?> {
            if (type == 3) return null to null
            fun number(raw: String): Double? {
                raw.toDoubleOrNull()?.let { return it }
                val digits = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '兩' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
                val units = mapOf('十' to 10, '百' to 100, '千' to 1000, '万' to 10000, '萬' to 10000)
                if (raw.none { it in units }) return raw.map { digits[it] ?: return null }.joinToString("").toDoubleOrNull()
                var total = 0; var section = 0; var digit = 0
                raw.forEach { char ->
                    if (char in digits) digit = digits.getValue(char)
                    else { val unit = units[char] ?: return null
                        if (unit == 10000) { total += (section + digit).coerceAtLeast(1) * unit; section = 0 }
                        else section += digit.coerceAtLeast(1) * unit
                        digit = 0
                    }
                }
                return (total + section + digit).toDouble()
            }
            var chapter: Double? = null; var volume: Double? = null
            Regex("""(?:第\s*)?([0-9]+(?:\.[0-9]+)?|[〇零一二两兩三四五六七八九十百千万萬]+)\s*([话話回章卷巻册冊])""")
                .findAll(title).forEach { match -> if (match.groupValues[2] in listOf("卷", "巻", "册", "冊")) volume = number(match.groupValues[1]) else chapter = number(match.groupValues[1]) }
            if (chapter == null && volume == null) title.trim().toDoubleOrNull()?.let { if (type == 2) volume = it else chapter = it }
            return chapter to volume
        }
        /** The API supplies a permutation, not a list already in reading order. */
        internal fun orderedPages(chapter: JSONObject): List<String> {
            val contents = chapter.optJSONArray("contents").objects().map { it.getString("url") }
            require(contents.isNotEmpty()) { "拷贝漫画暂未返回阅读图片" }
            val words = chapter.optJSONArray("words") ?: return contents
            require(words.length() == contents.size) { "拷贝图片顺序数据不完整" }
            val order = (0 until words.length()).map { index ->
                val value = words.opt(index) as? Number ?: error("拷贝图片顺序无效")
                require(value.toDouble().isFinite() && value.toDouble() == value.toInt().toDouble()) { "拷贝图片顺序无效" }
                value.toInt()
            }
            require(order.toSet() == contents.indices.toSet()) { "拷贝图片顺序无效" }
            return contents.indices.sortedBy { order[it] }.map(contents::get)
        }
    }
}
