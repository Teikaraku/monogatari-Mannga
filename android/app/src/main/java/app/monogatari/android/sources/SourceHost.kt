package app.monogatari.android.sources

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.webkit.CookieManager
import com.dylibso.chicory.runtime.Memory
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.*
import org.jsoup.select.Elements
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

data class HostResponse(val code: Int, val bytes: ByteArray, val headers: Map<String, String>, val url: String, val requestHeaders: Map<String, String>)
class HostRequest(var method: String, var url: String = "") {
    val headers = linkedMapOf<String, String>()
    var body: ByteArray? = null
    var timeout = 60.0
    var response: HostResponse? = null
    var readOffset = 0
    fun build(): Request { val builder = Request.Builder().url(url); headers.forEach { (k, v) -> builder.header(k, v) }; val data = if (method in setOf("POST", "PUT", "PATCH", "PROPPATCH", "REPORT")) (body ?: ByteArray(0)).toRequestBody() else body?.toRequestBody(); return builder.method(method, data).build() }
}
private data class HostDate(val seconds: Double)

class SourceCookieJar(private val preferences: SourcePreferences) : CookieJar {
    private val cookies = runCatching { (preferences.get("__cookies") as? JSONArray)?.values()?.filterIsInstance<JSONObject>()?.mapNotNull { j ->
        Cookie.parse(HttpUrl.Builder().scheme(if (j.optBoolean("secure")) "https" else "http").host(j.getString("domain").trimStart('.')).build(), j.getString("raw"))
    }?.toMutableList() }.getOrNull() ?: mutableListOf()
    @Synchronized override fun saveFromResponse(url: HttpUrl, incoming: List<Cookie>) { incoming.forEach { c -> cookies.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }; if (c.expiresAt > System.currentTimeMillis()) cookies += c }; save() }
    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
    @Synchronized fun importWebView(url: HttpUrl, raw: String) { val incoming = raw.split(';').mapNotNull { Cookie.parse(url, it.trim()) }; saveFromResponse(url, incoming) }
    @Synchronized fun clear() { cookies.clear(); save() }
    private fun save() { preferences.set("__cookies", JSONArray(cookies.map { JSONObject().put("domain", it.domain).put("secure", it.secure).put("raw", it.toString()) })) }
}

/** Host functions follow the pinned Swift import implementations, including their negative error codes. */
class SourceHost(val context: Context, val sourceId: String, http: OkHttpClient, val preferences: SourcePreferences, maximumParallelRequests: Int = 4) : AutoCloseable {
    private val networkSlots = java.util.concurrent.Semaphore(maximumParallelRequests.coerceIn(1, 64), true)
    lateinit var memory: Memory
    var legacy = false
    var currentManga: String = ""
    private var descriptor = 0
    private val values = mutableMapOf<Int, Any?>()
    private var requestDescriptor = 0
    private val requests = mutableMapOf<Int, HostRequest>()
    val partialResults = mutableListOf<ByteArray>()
    var lastError: String? = null
    var challengeUrl: String? = null
    val cookies = SourceCookieJar(preferences)
    val http: OkHttpClient = http.newBuilder().followRedirects(true).followSslRedirects(true).cookieJar(cookies)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).addInterceptor { chain ->
            val request = chain.request(); val builder = request.newBuilder()
            if (request.header("User-Agent") == null) builder.header("User-Agent", (preferences.get("__userAgent") as? String) ?: "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36")
            chain.proceed(builder.build())
        }.build()
    private var permits = 0
    private var periodMillis = 0L
    private var window = 0L
    private var sent = 0
    private val images = SourceCanvas(this)
    fun store(value: Any?): Int { require(values.size < 100_000) { "Source descriptor limit exceeded" }; val id = descriptor++; values[id] = value; return id }
    fun get(id: Int): Any? = values[id]
    fun destroy(id: Int) { (values.remove(id) as? SourceBrowser)?.close() }
    fun storeRequest(request: HostRequest): Int = if (legacy) requestDescriptor++.also { requests[it] = request } else store(request)
    private fun request(id: Int) = if (legacy) requests[id] else get(id) as? HostRequest
    fun destroyRequest(id: Int) { if (legacy) requests.remove(id) else destroy(id) }
    fun list(value: Any?): List<Any?> = when (value) { is JSONArray -> value.values(); is List<*> -> value; is Elements -> value.toList(); else -> emptyList() }
    fun string(offset: Long, length: Long): String { if (length == 0L) return ""; require(offset >= 0 && length in 0..50 * 1024 * 1024); return memory.readString(offset.toInt(), length.toInt()) }
    fun data(offset: Long, length: Long): ByteArray { if (length == 0L) return ByteArray(0); require(offset >= 0 && length in 0..50 * 1024 * 1024); return memory.readBytes(offset.toInt(), length.toInt()) }
    fun withBrowserHeaders(request: Request): Request = request.newBuilder().apply {
        if (request.header("User-Agent") == null) header("User-Agent", (preferences.get("__userAgent") as? String) ?: "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36")
        if (request.header("Cookie") == null) cookies.loadForRequest(request.url).takeIf { it.isNotEmpty() }?.let { header("Cookie", it.joinToString("; ") { c -> "${c.name}=${c.value}" }) }
    }.build()
    fun resultData(pointer: Int): ByteArray { val length = memory.readInt(pointer); require(length in 8..50 * 1024 * 1024); return memory.readBytes(pointer + 8, length - 8) }
    private fun f64(n: Double) = java.lang.Double.doubleToRawLongBits(n)
    private fun f32(n: Float) = java.lang.Float.floatToRawIntBits(n).toLong()
    private fun bytes(value: Any?): ByteArray? = when (value) { is ByteArray -> value; is String -> value.toByteArray(Charsets.UTF_8); else -> null }
    fun imageBytes(value: Any?): ByteArray = when (value) { is ByteArray -> value; is Bitmap -> ByteArrayOutputStream().also { check(value.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray(); else -> error("Invalid source image") }
    fun fetch(request: Request, timeout: Double = 60.0): HostResponse {
        require(request.url.scheme in listOf("http", "https"))
        networkSlots.acquire()
        try { throttle()
        http.newBuilder().callTimeout(timeout.coerceIn(1.0, 180.0).toLong(), TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
            val bytes = response.body?.byteStream()?.use { readBounded(it, 50 * 1024 * 1024) } ?: ByteArray(0)
            if (response.code in setOf(403, 503) && (response.header("cf-mitigated") == "challenge" || bytes.toString(Charsets.UTF_8).let { "cf-chl-" in it || "Just a moment..." in it })) {
                challengeUrl = response.request.url.toString()
                throw SourceRuntimeException("网站需要在浏览器中完成验证", sourceId, challengeUrl)
            }
            return HostResponse(response.code, bytes, response.headers.toMap(), response.request.url.toString(), response.request.headers.toMap())
        }
        } finally { networkSlots.release() }
    }
    @Synchronized private fun throttle() { if (permits <= 0 || periodMillis <= 0) return; val now = System.currentTimeMillis(); if (now - window >= periodMillis) { window = now; sent = 0 }; if (sent >= permits) { Thread.sleep((window + periodMillis - now).coerceAtLeast(0)); window = System.currentTimeMillis(); sent = 0 }; sent++ }
    fun importBrowserCookies(url: String, userAgent: String, raw: String) { val parsed = url.toHttpUrl(); cookies.importWebView(parsed, raw); preferences.set("__userAgent", userAgent); challengeUrl = null; lastError = null }
    fun call(namespace: String, name: String, a: LongArray): Long {
        return try { when (namespace) {
            "env" -> env(name, a)
            "std" -> std(name, a)
            "defaults" -> defaults(name, a)
            "net" -> net(name, a)
            "html" -> html(name, a)
            "json" -> when (name) { "parse" -> { val parsed = JSONTokener(string(a[0], a[1])).nextValue(); if (parsed is JSONObject || parsed is JSONArray) store(parsed).toLong() else -1L }; else -> error("Unknown json import $name") }
            "javascript", "js" -> javascript(name, a)
            "canvas" -> images.call(name, a)
            "aidoku" -> aidoku(name, a)
            else -> error("Unsupported source import $namespace.$name")
        } } catch (e: SourceRuntimeException) { challengeUrl = e.challengeUrl; lastError = e.message; -10L }
        catch (e: Exception) { lastError = "$namespace.$name: ${e.message}"; SourceLogStore.get(context).add("[$sourceId] $lastError", "WARNING"); -1L }
    }
    private fun env(name: String, a: LongArray): Long = when (name) {
        "abort" -> 0
        "print" -> { SourceLogStore.get(context).add("[$sourceId] ${string(a[0], a[1])}"); 0 }
        "sleep" -> { Thread.sleep(a[0].coerceIn(0, 180) * 1000); 0 }
        "send_partial_result" -> { partialResults += resultData(a[0].toInt()); 0 }
        else -> error("Unknown env import $name")
    }
    private fun std(name: String, a: LongArray): Long {
        val id = a.firstOrNull()?.toInt() ?: -1; val value = get(id)
        fun put(value: Any?) = store(value).toLong()
        fun key() = string(a[1], a[2])
        return when (name) {
            "destroy" -> { destroy(id); 0 }; "copy" -> if (id >= 0 && values.containsKey(id)) put(value) else -1
            "buffer_len", "string_len" -> (bytes(value)?.size ?: -1).toLong()
            "read_buffer", "read_string" -> { val b = bytes(value) ?: return -1; val n = a[2].toInt(); if (n !in 0..b.size) return -2; memory.write(a[1].toInt(), b.copyOf(n)); 0 }
            "current_date" -> f64(System.currentTimeMillis() / 1000.0)
            "utc_offset" -> -(TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000L)
            "parse_date" -> f64(parseDate(string(a[0], a[1]), string(a[2], a[3]), string(a[4], a[5]), string(a[6], a[7])))
            "create_null" -> put(null); "create_int" -> put(a[0]); "create_float" -> put(java.lang.Double.longBitsToDouble(a[0])); "create_string" -> put(string(a[0], a[1])); "create_bool" -> put(a[0] != 0L)
            "create_array" -> put(mutableListOf<Any?>()); "create_object" -> put(JSONObject()); "create_date" -> put(HostDate(java.lang.Double.longBitsToDouble(a[0]).let { if (it < 0) System.currentTimeMillis() / 1000.0 else it }))
            "typeof" -> when (value) { null, JSONObject.NULL -> 0; is Boolean -> 4; is Float, is Double -> 2; is Number -> 1; is String -> 3; is JSONArray, is List<*> -> 5; is JSONObject -> if (value.optString("_legacyNativeKind") in setOf("page", "result", "deeplink")) 9 else 6; is HostRequest, is HostResponse -> 6; is HostDate -> 7; is Node, is Elements -> 8; else -> 9 }.toLong()
            "read_int" -> when (value) { is Number -> value.toLong(); is Boolean -> if (value) 1 else 0; is String -> value.toLongOrNull() ?: -1; else -> -1 }
            "read_float" -> f64(when (value) { is Number -> value.toDouble(); is Boolean -> if (value) 1.0 else 0.0; is String -> value.toDoubleOrNull() ?: -1.0; else -> -1.0 })
            "read_bool" -> if (value == true || value is Number && value.toInt() != 0) 1 else 0
            "read_date" -> f64((value as? HostDate)?.seconds ?: -1.0)
            "read_date_string" -> f64(if (value is String && a[2] > 0) parseDate(value, string(a[1], a[2]), string(a[3], a[4]), string(a[5], a[6])).let { if (it == -5.0) -1.0 else it } else -1.0)
            "array_len" -> list(value).size.toLong()
            "array_get" -> { val array = list(value); val index = a[1].toInt(); if (index in array.indices) put(array[index]) else -1 }
            "array_set", "array_append", "array_remove" -> {
                if (value !is JSONArray && value !is List<*>) return 0
                val array = list(value).toMutableList()
                when (name) { "array_set" -> { val index = a[1].toInt(); if (index !in array.indices) return -1; array[index] = get(a[2].toInt()) }; "array_append" -> array.add(get(a[1].toInt())); else -> { val index = a[1].toInt(); if (index !in array.indices) return -1; array.removeAt(index) } }; values[id] = array; 0
            }
            "object_len" -> ((value as? JSONObject)?.length() ?: 0).toLong()
            "object_get" -> { if (id < 0 || a[2] <= 0) return -1; val property = key(); val found = when (value) {
                is JSONObject -> {
                    val kind = value.optString("_legacyNativeKind")
                    val allowed = when (kind) {
                        "manga" -> setOf("id", "title", "author", "artist", "description", "tags", "cover", "url", "status", "nsfw", "viewer", "chapterFlags", "langFilter", "scanlatorFilter")
                        "chapter" -> setOf("id", "mangaId", "title", "scanlator", "chapterNum", "volumeNum")
                        "page", "result", "deeplink" -> emptySet()
                        else -> null
                    }
                    if (allowed != null && property !in allowed) return -1
                    if (!value.has(property)) return -1
                    if (kind == "manga" && property == "viewer") value.value("legacyViewer")
                    else if (kind == "manga" && property == "status") value.value("legacyStatus")
                    else value.value(property)
                }
                is HostRequest -> when (key()) { "url" -> value.url; "method" -> listOf("GET", "POST", "HEAD", "PUT", "DELETE", "PATCH", "OPTIONS", "CONNECT", "TRACE").indexOf(value.method); "headers" -> JSONObject(value.headers); "body" -> value.body; "response" -> value.response; else -> null }
                is HostResponse -> when (key()) { "headers" -> JSONObject(value.headers); "status_code" -> value.code; "data" -> value.bytes.map { it.toInt() and 255 }; else -> null }; else -> null
            }; if (found == null && value !is JSONObject) -1 else put(found) }
            "object_set" -> { (value as? JSONObject)?.takeUnless { it.has("_legacyNativeKind") }?.put(key(), get(a[3].toInt())); 0 }
            "object_remove" -> { (value as? JSONObject)?.takeUnless { it.has("_legacyNativeKind") }?.remove(key()); 0 }
            "object_keys" -> (value as? JSONObject)?.takeUnless { it.has("_legacyNativeKind") }?.let { put(it.keys().asSequence().toList()) } ?: -1
            "object_values" -> (value as? JSONObject)?.takeUnless { it.has("_legacyNativeKind") }?.let { j -> put(j.keys().asSequence().map { j.value(it) }.toList()) } ?: -1
            else -> error("Unknown std import $name")
        }
    }
    private fun defaults(name: String, a: LongArray): Long {
        val key = string(a[0], a[1])
        if (name == "get") {
            val value = preferences.get(key) ?: return -2
            if (legacy) return store(value).toLong()
            val w = PostcardWriter(); when (value) { is Boolean -> w.bool(value); is Int, is Long -> w.signed((value as Number).toLong()); is Double, is Float -> w.float((value as Number).toFloat()); is JSONArray -> w.strings(value.values().map { it.toString() }); is ByteArray -> return store(value).toLong(); else -> w.string(value.toString()) }; return store(w.bytes()).toLong()
        }
        require(name == "set")
        if (legacy) { preferences.set(key, get(a[2].toInt())); return 0 }
        val kind = a[2].toInt(); if (kind == 6) { preferences.set(key, null); return 0 }
        val bytes = resultData(a[3].toInt()); val r = PostcardReader(bytes)
        val value: Any = when (kind) { 0 -> bytes; 1 -> r.bool(); 2 -> r.signed(); 3 -> r.float(); 4 -> r.string(); 5 -> JSONArray(r.strings()); else -> return -2 }
        preferences.set(key, value); return 0
    }
    private fun net(name: String, a: LongArray): Long {
        if (name == "init") { val methods = if (legacy) listOf("GET", "POST", "HEAD", "PUT", "DELETE", "PATCH", "OPTIONS", "CONNECT", "TRACE") else listOf("GET", "POST", "PUT", "HEAD", "DELETE", "PATCH", "OPTIONS", "CONNECT", "TRACE"); return storeRequest(HostRequest(methods.getOrNull(a[0].toInt()) ?: return -3)).toLong() }
        if (name == "set_rate_limit") { permits = a[0].toInt(); if (a.size > 1) periodMillis = a[1] * when (a.getOrElse(2) { 0 }) { 0L -> 1000; 1L -> 60_000; 2L -> 3_600_000; else -> 1000 }; else if (periodMillis == 0L) periodMillis = 60_000; return 0 }
        if (name == "set_rate_limit_period") { periodMillis = a[0] * 1000; return 0 }
        if (name == "send_all") {
            require(a[0] >= 0 && a[1] in 1..4096)
            val descriptors = List(a[1].toInt()) { memory.readInt(a[0].toInt() + it * 4) }
            val tasks = descriptors.map { descriptor -> java.util.concurrent.CompletableFuture.supplyAsync {
                val req = request(descriptor) ?: return@supplyAsync -1
                try { req.response = fetch(req.build(), req.timeout); req.readOffset = 0; 0 }
                catch (e: Exception) { synchronized(this) { lastError = e.message; if (e is SourceRuntimeException) challengeUrl = e.challengeUrl }; -10 }
            } }
            return store(PostcardWriter().list(tasks.map { it.join() }) { signed(it.toLong()) }.bytes()).toLong()
        }
        val id = a[0].toInt(); val request = request(id) ?: return -1
        return when (name) {
            "close" -> { if (legacy) requests.remove(id) else destroy(id); 0 }
            "send" -> { request.response = fetch(request.build(), request.timeout); request.readOffset = 0; 0 }
            "set_url" -> { request.url = string(a[1], a[2]); 0 }
            "set_header" -> { request.headers[string(a[1], a[2])] = string(a[3], a[4]); 0 }
            "set_body" -> { request.body = data(a[1], a[2]); 0 }
            "set_timeout" -> { request.timeout = java.lang.Double.longBitsToDouble(a[1]); 0 }
            "get_url" -> store(request.response?.url ?: request.url).toLong()
            "get_status_code" -> (request.response?.code ?: -8).toLong()
            "get_header" -> { val key = string(a[1], a[2]); request.response?.headers?.entries?.firstOrNull { it.key.equals(key, true) }?.value?.let { store(it).toLong() } ?: -8 }
            "data_len", "get_data_size" -> ((request.response?.bytes?.size ?: return -7) - if (legacy) request.readOffset else 0).toLong()
            "read_data", "get_data" -> { val bytes = request.response?.bytes ?: return -7; val offset = if (legacy) request.readOffset else 0; val n = a[2].toInt(); if (n < 0 || offset + n > bytes.size) return -6; memory.write(a[1].toInt(), bytes.copyOfRange(offset, offset + n)); request.readOffset += n; 0 }
            "html" -> store(Jsoup.parse((request.response?.bytes ?: return -7).toString(Charsets.UTF_8), request.response?.url ?: request.url)).toLong()
            "json" -> store(JSONTokener((request.response?.bytes ?: return -7).toString(Charsets.UTF_8)).nextValue()).toLong()
            "get_image" -> store(BitmapFactory.decodeByteArray(request.response?.bytes ?: return -7, 0, request.response!!.bytes.size) ?: return -12).toLong()
            else -> error("Unknown net import $name")
        }
    }
    private fun html(name: String, a: LongArray): Long {
        fun textArg(index: Int) = string(a[index], a[index + 1])
        fun put(value: Any?) = store(value).toLong()
        if (name in setOf("parse", "parse_fragment", "parse_with_uri", "parse_fragment_with_uri")) {
            val base = if (a.size >= 4) textArg(2) else ""; return put(if ("fragment" in name) Jsoup.parseBodyFragment(textArg(0), base) else Jsoup.parse(textArg(0), base))
        }
        if (name == "escape") return put(Entities.escape(textArg(0)))
        if (name == "unescape") return put(Entities.unescape(textArg(0)))
        val value = get(a[0].toInt()) ?: return -1
        val nodes: List<Node> = when (value) { is Node -> listOf(value); is Elements -> value.toList(); is List<*> -> value.filterIsInstance<Node>(); else -> return -1 }
        val node = nodes.firstOrNull(); val elements = Elements(nodes.filterIsInstance<Element>())
        return when (name) {
            "kind" -> when (value) { is Document -> 7; is Elements -> 6; is Element -> 5; is Comment -> 4; is DataNode -> 3; is TextNode -> 2; is Node -> 1; else -> 0 }.toLong()
            "select" -> put(elements.select(textArg(1))); "select_first" -> elements.select(textArg(1)).first()?.let(::put) ?: -5
            "first" -> nodes.firstOrNull()?.let(::put) ?: -5; "last" -> nodes.lastOrNull()?.let(::put) ?: -5; "get" -> nodes.getOrNull(a[1].toInt())?.let(::put) ?: -5
            "size" -> nodes.size.toLong(); "array" -> put(nodes); "children" -> put(Elements(elements.flatMap { it.children() })); "child_nodes" -> put(nodes.flatMap { it.childNodes() })
            "attr" -> put(if (value is Elements) value.attr(textArg(1)) else node?.attr(textArg(1)).orEmpty())
            "has_attr" -> if (nodes.any { it.hasAttr(textArg(1)) }) 1 else 0
            "set_attr" -> { nodes.forEach { it.attr(textArg(1), textArg(3)) }; 0 }; "remove_attr" -> { nodes.forEach { it.removeAttr(textArg(1)) }; 0 }
            "text" -> put(elements.text()); "untrimmed_text" -> put(elements.joinToString(" ") { it.wholeText() }); "own_text" -> put(elements.joinToString(" ") { it.ownText() })
            "html" -> put(elements.html()); "outer_html" -> put(nodes.joinToString("\n") { it.outerHtml() }); "data" -> put(elements.joinToString("") { it.data() })
            "body" -> put((node as? Document)?.body()); "base_uri" -> put(node?.baseUri().orEmpty()); "id" -> put(elements.firstOrNull()?.id().orEmpty()); "tag_name" -> put(elements.firstOrNull()?.tagName().orEmpty()); "class_name" -> put(elements.firstOrNull()?.className().orEmpty())
            "has_class" -> if (elements.any { it.hasClass(textArg(1)) }) 1 else 0
            "set_text", "set_html", "prepend", "append", "add_class", "remove_class" -> { val text = textArg(1); elements.forEach { when (name) { "set_text" -> it.text(text); "set_html" -> it.html(text); "prepend" -> it.prepend(text); "append" -> it.append(text); "add_class" -> it.addClass(text); else -> it.removeClass(text) } }; 0 }
            "parent" -> node?.parent()?.let(::put) ?: -5
            "siblings" -> put(if (node is Element) node.siblingElements() else node?.siblingNodes().orEmpty())
            "next" -> (if (node is Element) node.nextElementSibling() else node?.nextSibling())?.let(::put) ?: -5
            "previous" -> (if (node is Element) node.previousElementSibling() else node?.previousSibling())?.let(::put) ?: -5
            "remove" -> { nodes.forEach { it.remove() }; 0 }
            else -> error("Unknown html import $name")
        }
    }
    private fun javascript(name: String, a: LongArray): Long {
        if (name == "context_create" || name == "webview_create") return store(SourceBrowser(context, if (name == "context_create") "$sourceId:context:${java.util.UUID.randomUUID()}" else sourceId, name == "context_create")).toLong()
        val browser = get(a[0].toInt()) as? SourceBrowser ?: return -2
        return when (name) {
            "context_eval", "webview_eval", "context_eval_async", "webview_eval_async" -> store(browser.evaluate(string(a[1], a[2]), name.endsWith("async"))).toLong()
            "context_get" -> store(browser.evaluate("globalThis[${JSONObject.quote(string(a[1], a[2]))}]")).toLong()
            "webview_load" -> { val request = request(a[1].toInt()) ?: return -5; browser.load(request.build()); 0 }
            "webview_load_html" -> { browser.loadHtml(string(a[1], a[2]), string(a[3], a[4])); 0 }
            "webview_wait_for_load" -> { browser.waitForLoad(); 0 }
            "webview_set_rule_list" -> { browser.setRules(string(a[1], a[2])); 0 }
            "webview_add_user_script" -> { browser.addScript(string(a[1], a[2]), a[3] != 0L, a[4] != 0L); 0 }
            "webview_get_cookies" -> {
                val url = browser.currentUrl(); val parsed = browser.cookies(); if (url.startsWith("http")) cookies.saveFromResponse(url.toHttpUrl(), parsed)
                val w = PostcardWriter().list(parsed) { c -> string(c.name); string(c.value); option(if (c.persistent) c.expiresAt / 1000 else null) { signed(it) }; string(c.domain); string(c.path); bool(c.secure); bool(c.httpOnly) }; store(w.bytes()).toLong()
            }
            "webview_delete_cookie" -> if (browser.deleteCookie(if (a[2] > 0) string(a[1], a[2]) else null, if (a[4] > 0) string(a[3], a[4]) else null, if (a[6] > 0) string(a[5], a[6]) else null)) 0 else -8
            else -> error("Unknown javascript import $name")
        }
    }
    private fun aidoku(name: String, a: LongArray): Long {
        fun s(index: Int) = string(a[index], a[index + 1])
        fun optional(index:Int):String?=if(a[index+1]>0) s(index) else null
        if(name=="create_manga" && a[1]<=0)return -1
        if(name=="create_manga_result" && (get(a[0].toInt()) !is List<*> && get(a[0].toInt()) !is JSONArray || list(get(a[0].toInt())).any { (it as? JSONObject)?.optString("_legacyNativeKind")!="manga" }))return -1
        return store(when (name) {
            "create_manga" -> {
                val tags = List(a[16].toInt()) { i -> string(memory.readInt(a[14].toInt() + i * 4).toLong(), memory.readInt(a[15].toInt() + i * 4).toLong()) }
                JSONObject().put("_legacyNativeKind", "manga").put("key", s(0)).put("id", s(0)).put("cover", optional(2)).put("cover_url", optional(2)).put("title", optional(4)).put("author", optional(6)).put("artist", optional(8)).put("authors", optional(6)?.let { JSONArray().put(it) }).put("artists", optional(8)?.let { JSONArray().put(it) }).put("description", optional(10)).put("url", optional(12)).put("tags", JSONArray(tags)).put("status", if (a[17] == 5L) 0 else a[17]).put("legacyStatus", a[17]).put("contentRating", a[18] + 1).put("nsfw", a[18]).put("viewer", when(a[19]) { 1L -> 2; 2L -> 1; else -> a[19] }).put("legacyViewer", a[19])
            }
            "create_manga_result" -> JSONObject().put("_legacyNativeKind", "result").put("entries", JSONArray(list(get(a[0].toInt())))).put("hasNextPage", a[1] != 0L)
            "create_chapter" -> JSONObject().put("_legacyNativeKind", "chapter").put("key", s(0)).put("id", s(0)).put("mangaId", currentManga).put("title", optional(2)).put("name", optional(2)).put("volumeNumber", java.lang.Float.intBitsToFloat(a[4].toInt()).takeIf { it >= 0 }).put("chapterNumber", java.lang.Float.intBitsToFloat(a[5].toInt()).takeIf { it >= 0 }).put("volumeNum", java.lang.Float.intBitsToFloat(a[4].toInt()).takeIf { it >= 0 }).put("chapterNum", java.lang.Float.intBitsToFloat(a[5].toInt()).takeIf { it >= 0 }).put("dateUploaded", java.lang.Double.longBitsToDouble(a[6]).takeIf { it>0 }).put("scanlators", JSONArray().put(s(7))).put("scanlator", s(7)).put("url", s(9)).put("language", s(11).ifBlank { "en" }).put("lang", s(11).ifBlank { "en" })
            "create_page" -> JSONObject().put("_legacyNativeKind", "page").put("index", a[0]).put("imageURL", optional(1)).put("base64", optional(3)).put("text", optional(5))
            "create_deeplink" -> JSONObject().put("_legacyNativeKind", "deeplink").put("manga", get(a[0].toInt())).put("chapter", get(a[1].toInt()))
            else -> error("Unknown aidoku import $name")
        }).toLong()
    }
    private fun parseDate(value: String, format: String, locale: String, timeZone: String): Double = runCatching {
        val formatter = SimpleDateFormat(format.replace("YYYY", "yyyy"), if (locale == "current" || locale.isEmpty()) Locale.getDefault() else Locale.forLanguageTag(locale.replace('_', '-')))
        if (timeZone.isNotEmpty()) formatter.timeZone = if (timeZone == "current") TimeZone.getDefault() else TimeZone.getTimeZone(timeZone)
        formatter.parse(value)?.time?.div(1000.0) ?: -5.0
    }.getOrDefault(-5.0)
    override fun close() { values.values.filterIsInstance<SourceBrowser>().forEach { it.close() }; values.clear(); requests.clear() }
    companion object {
        fun readBounded(input: InputStream, limit: Int): ByteArray { val out = ByteArrayOutputStream(); val buffer = ByteArray(32 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size() <= limit - n) { "Source response too large" }; out.write(buffer, 0, n) }; return out.toByteArray() }
    }
}
