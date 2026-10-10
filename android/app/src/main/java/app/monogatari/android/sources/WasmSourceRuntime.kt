package app.monogatari.android.sources

import app.monogatari.android.AppBrand

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.AtomicFile
import app.monogatari.android.data.Chapter
import app.monogatari.android.data.Manga
import com.dylibso.chicory.runtime.ByteBufferMemory
import com.dylibso.chicory.runtime.HostFunction
import com.dylibso.chicory.runtime.ImportValues
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.wasm.Parser
import com.dylibso.chicory.wasm.types.FunctionImport
import com.dylibso.chicory.wasm.types.MemoryLimits
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

class SourceRuntimeException(message: String, val sourceId: String, val challengeUrl: String? = null, cause: Throwable? = null) : RuntimeException(message, cause)
data class SourceDeepLink(val sourceId: String, val manga: Manga? = null, val chapterKey: String? = null, val listing: JSONObject? = null)

/** A source has one serialized VM, matching the original Interpreter actor. Both original ABIs are supported. */
class WasmSourceRuntime(val context: Context, val source: InstalledSource, http: OkHttpClient, providedPreferences: SourcePreferences? = null) : SourceSession, AutoCloseable {
    override val sourceId get() = source.id
    override val preferences = providedPreferences ?: SourcePreferences(context, source.id)
    val host = SourceHost(context, source.id, http, preferences, source.manifest.optJSONObject("config")?.optInt("maximumParallelRequests", 4) ?: 4)
    private val module = Parser.parse(File(source.directory, "main.wasm"))
    override val exports = (0 until module.exportSection().exportCount()).map { module.exportSection().getExport(it).name() }.toSet()
    override val legacy = "get_search_manga_list" !in exports
    private var deadline = Long.MAX_VALUE
    private var ticks = 0
    private var disabled: Throwable? = null
    private val instance: Instance
    private val pageMetaFile = AtomicFile(File(context.filesDir, "source-pages-${SourceRuntimeManager.digest(source.id)}.json"))
    private val pageMetadata: JSONObject = runCatching { JSONObject(pageMetaFile.readFully().toString(Charsets.UTF_8)) }.getOrDefault(JSONObject())
    init {
        preferences.initialize(JSONArray(extraSettings(includeDynamicUrl = false) + source.jsonArray("settings").values()))
        if(legacy && preferences.get("languages")==null)preferences.set("languages",JSONArray())
        if (preferences.get("url") == null && source.baseUrl.isNotEmpty()) preferences.set("url", source.baseUrl)
        host.legacy = legacy
        val imports = (0 until module.importSection().importCount()).map { module.importSection().getImport(it) }.map { import ->
            require(import is FunctionImport) { "Unsupported non-function source import ${import.module()}.${import.name()}" }
            val type = module.typeSection().getType(import.typeIndex())
            HostFunction(import.module(), import.name(), type) { vm, args ->
                host.memory = vm.memory()
                val result = host.call(import.module(), import.name(), args)
                if (type.returns().isEmpty()) longArrayOf() else longArrayOf(result)
            }
        }
        validateMemory(module)
        instance = Instance.builder(module).withMemoryFactory { limits ->
            require(limits.initialPages() <= maximumMemoryPages()) { "Source initial memory exceeds the device budget" }
            ByteBufferMemory(MemoryLimits(limits.initialPages(), minOf(limits.maximumPages(), maximumMemoryPages()), limits.shared()))
        }
            .withImportValues(ImportValues.builder().withFunctions(imports).build())
            .withUnsafeExecutionListener { _, _ -> if (++ticks and 1023 == 0) {
                check(!Thread.currentThread().isInterrupted) { "Source execution cancelled" }
                check(System.nanoTime() <= deadline) { "Source execution timed out" }
            } }.withStart(false).build()
        host.memory = instance.memory()
        if ("start" in exports) invoke("start")
        if (legacy && "initialize" in exports) invoke("initialize")
    }
    @Synchronized private fun invoke(name: String, vararg args: Long): Long {
        disabled?.let { throw SourceRuntimeException("漫画源执行已停止，请重新启动漫画源：${it.message}", source.id, cause = it) }
        deadline = System.nanoTime() + 180_000_000_000L
        try { return instance.export(name).apply(*args)?.firstOrNull() ?: 0L }
        catch (e: Exception) { disabled = e; throw SourceRuntimeException(e.message ?: "Source execution failed", source.id, host.challengeUrl, e) }
        finally { deadline = Long.MAX_VALUE }
    }
    private fun result(name: String, vararg args: Long): PostcardReader {
        val pointer = invoke(name, *args).toInt()
        if (pointer < 0) throw SourceRuntimeException(host.lastError ?: when (pointer) { -2 -> "源未实现此功能"; -3 -> "漫画源网络请求失败"; -4 -> "漫画源 HTML 解析失败"; -5 -> "漫画源 JavaScript 执行失败"; -6 -> "漫画源图片处理失败"; -8 -> "漫画源 JSON 解析失败"; -9 -> "漫画源数据解码失败"; else -> "漫画源没有返回数据" }, source.id, host.challengeUrl)
        val memory = instance.memory(); val size = memory.readInt(pointer)
        try {
            if (size == -1) { val length = memory.readInt(pointer + 8) - 12; require(length in 0..1_000_000); throw SourceRuntimeException(memory.readString(pointer + 12, length), source.id, host.challengeUrl) }
            require(size in 8..50 * 1024 * 1024) { "Invalid source result length $size" }
            return PostcardReader(memory.readBytes(pointer + 8, size - 8))
        } finally { if ("free_result" in exports) invoke("free_result", pointer.toLong()) }
    }
    private fun <T> withValues(values: List<Any?>, block: (List<Long>) -> T): T {
        val handles = values.map { host.store(it) }; return try { block(handles.map(Int::toLong)) } finally { handles.forEach(host::destroy) }
    }
    private fun encoded(write: (PostcardWriter) -> Unit) = PostcardWriter().also(write).bytes()
    private fun legacyResult(name: String, vararg args: Long): Any? {
        val handle = invoke(name, *args).toInt()
        if (handle < 0) throw SourceRuntimeException(host.lastError ?: "漫画源返回错误 $handle", source.id, host.challengeUrl)
        return try { host.get(handle) } finally { host.destroy(handle) }
    }
    @Synchronized override fun search(query: String, page: Int, filters: List<JSONObject>): Pair<List<Manga>, Boolean> {
        require(page >= 1)
        return if (legacy) {
            val values = filters.map { legacyFilter(it) }.toMutableList(); if (query.isNotEmpty()) values.add(JSONObject().put("type", 7).put("name", "Title").put("value", query))
            withValues(listOf(values)) { handles -> val raw = legacyResult("get_manga_list", handles[0], page.toLong()) as? JSONObject ?: error(host.lastError ?: "漫画源没有返回搜索结果"); mangas(raw.getJSONArray("entries")) to raw.optBoolean("hasNextPage") }
        } else withValues(listOf(query, encoded { SourceCodec.filterValues(it, filters) })) { handles ->
            val r = result("get_search_manga_list", handles[0], page.toLong(), handles[1]); val entries = r.list { SourceCodec.manga(r) }; val more = r.bool(); r.end(); entries.map(::manga) to more
        }
    }
    @Synchronized override fun listing(listing: JSONObject, page: Int): Pair<List<Manga>, Boolean> = if (legacy) withValues(listOf(listing)) { handles ->
        val raw = legacyResult("get_manga_listing", handles[0], page.toLong()) as? JSONObject ?: error("漫画源没有返回列表"); mangas(raw.getJSONArray("entries")) to raw.optBoolean("hasNextPage")
    } else withValues(listOf(encoded { SourceCodec.listing(it, listing) })) { handles ->
        val r = result("get_manga_list", handles[0], page.toLong()); val entries = r.list { SourceCodec.manga(r) }; val more = r.bool(); r.end(); entries.map(::manga) to more
    }
    @Synchronized fun details(manga: Manga): Manga {
        val original = rawManga(manga)
        val raw = if (legacy) withValues(listOf(legacyManga(original))) { handles ->
            host.currentManga = manga.key
            val detail = legacyResult("get_manga_details", handles[0]) as? JSONObject ?: original
            val chapters = legacyResult("get_chapter_list", handles[0])
            detail.put("chapters", JSONArray(host.list(chapters)))
        } else withValues(listOf(encoded { SourceCodec.manga(it, original) })) { handles -> SourceCodec.manga(result("get_manga_update", handles[0], 1, 1)) }
        return manga(raw).copy(inLibrary = manga.inLibrary, addedAt = manga.addedAt, categories = manga.categories, pinned = manga.pinned)
    }
    @Synchronized fun pages(chapter: Chapter): List<String> {
        val rawChapter = rawChapter(chapter)
        val rawManga = rawChapter.optJSONObject("_manga") ?: JSONObject().put("key", rawChapter.optString("mangaId")).put("title", "")
        if (legacy) return withValues(listOf(legacyChapter(rawChapter))) { handles ->
            val pages = host.list(legacyResult("get_page_list", handles[0])).filterIsInstance<JSONObject>().sortedBy { it.optInt("index") }.map { j ->
                val record = JSONObject().put("legacy", true).put("index", j.optInt("index")).put("thumbnail", j.value("thumbnail")).put("hasDescription", j.optBoolean("hasDescription")).put("description", j.value("description"))
                val token = when { j.optString("imageURL").isNotBlank() -> { val url = j.getString("imageURL"); val pageContext = j.optJSONObject("context"); record.put("kind", 0).put("url", url).put("context", pageContext); if (pageContext == null) url else url.toHttpUrl().newBuilder().fragment("monogatari-context-${SourceRuntimeManager.digest(pageContext.toString())}").build().toString() }
                    j.optString("text").isNotBlank() -> { val text = j.getString("text"); record.put("kind", 1).put("text", text); materialize(text.toByteArray(), "txt") }
                    j.optString("base64").isNotBlank() -> { record.put("kind", 2); materialize(android.util.Base64.decode(j.getString("base64").substringAfter(','), android.util.Base64.DEFAULT), "png") }
                    else -> { record.put("kind",1).put("text","Invalid URL");materialize("Invalid URL".toByteArray(),"txt") }
                }; pageMetadata.put(token, record); token
            }; savePageMetadata(); pages
        }
        return withValues(listOf(encoded { SourceCodec.manga(it, rawManga) }, encoded { SourceCodec.chapter(it, rawChapter) })) { handles ->
            val r = result("get_page_list", handles[0], handles[1]); val pages = r.list {
                val kind = r.byte()
                val record = JSONObject().put("kind", kind)
                val value = when (kind) {
                    0 -> { val url = r.string(); val metadata = r.option { JSONObject(r.map()) }; record.put("url", url).put("context", metadata); if (metadata != null) {
                        val token = url.toHttpUrl().newBuilder().fragment("monogatari-context-${SourceRuntimeManager.digest(metadata.toString())}").build().toString()
                        token
                    } else url }
                    1 -> { val text = r.string(); record.put("text", text); materialize(text.toByteArray(), "txt") }
                    2 -> { val ref = r.signed().toInt(); val bytes = host.imageBytes(host.get(ref)); host.destroy(ref); materialize(bytes, "png") }
                    3 -> { val url = r.string(); val path = r.string(); record.put("archiveUrl", url).put("filePath", path); val archive = host.fetch(Request.Builder().url(url).build()).bytes; var bytes: ByteArray? = null
                        ZipInputStream(archive.inputStream()).use { zip -> while (true) { val entry = zip.nextEntry ?: break; if (entry.name == path) { bytes = SourceHost.readBounded(zip, 50 * 1024 * 1024); break } } }; materialize(bytes ?: error("压缩包未包含页面 $path"), if (path.endsWith(".txt", true)) "txt" else "image") }
                    else -> error("Unknown page type $kind")
                }
                val thumbnail = r.option { r.string() }; val hasDescription = r.bool(); val description = r.option { r.string() }
                record.put("thumbnail", thumbnail).put("hasDescription", hasDescription).put("description", description); pageMetadata.put(value, record)
                value
            }; r.end(); savePageMetadata(); pages
        }
    }
    @Synchronized fun imageRequest(url: String): Request {
        val record = pageMetadata.optJSONObject(url)
        val actualUrl = record?.optString("url")?.takeIf { it.isNotEmpty() } ?: url
        val context = pageContext(url)
        if (legacy) {
            val request = HostRequest("GET", actualUrl); val ref = host.storeRequest(request)
            try { if ("modify_image_request" in exports) invoke("modify_image_request", ref.toLong()); return host.withBrowserHeaders(request.build()) } finally { host.destroyRequest(ref) }
        }
        if ("get_image_request" !in exports) return host.withBrowserHeaders(Request.Builder().url(actualUrl).build())
        return withValues(listOf(encoded { it.string(actualUrl) }, context?.let { c -> encoded { it.map(c) } })) { handles ->
            val r = result("get_image_request", handles[0], if (context == null) -1 else handles[1]); val ref = r.signed().toInt()
            try { host.withBrowserHeaders((host.get(ref) as? HostRequest)?.build() ?: error("漫画源返回无效图片请求")) } finally { host.destroy(ref) }
        }
    }
    fun imageBytes(url: String, cover: Boolean = false): ByteArray {
        if (url.startsWith("file:")) return File(Uri.parse(url).path ?: error("无效本地图片")).readBytes()
        val response = host.fetch(imageRequest(url)); val processing = if (cover) "process_cover_image" else "process_page_image"
        if (processing !in exports) return response.bytes
        return synchronized(this) { processImage(url, cover, response, processing) }
    }
    private fun processImage(url: String, cover: Boolean, response: HostResponse, processing: String): ByteArray {
        val image = host.store(response.bytes)
        try {
            val context = pageContext(url)
            val raw = encoded { w -> w.unsigned(response.code.toLong()); w.map(response.headers); w.option(response.url) { w.string(it) }; w.map(response.requestHeaders); w.signed(image.toLong()) }
            return withValues(listOf(raw, context?.let { c -> encoded { it.map(c) } })) { handles ->
                val r = if (cover) result(processing, handles[0]) else result(processing, handles[0], if (context == null) -1 else handles[1])
                val ref = r.signed().toInt(); try { host.imageBytes(host.get(ref)) } finally { host.destroy(ref) }
            }
        } finally { host.destroy(image) }
    }
    private fun pageContext(url: String): Map<String, String>? = pageMetadata.optJSONObject(url)?.let { record ->
        val j = record.optJSONObject("context") ?: if (record.has("url") || record.has("kind")) return@let null else record
        j.keys().asSequence().associateWith { j.optString(it) }
    }
    @Synchronized fun pageDescription(url: String): String? = pageMetadata.optJSONObject(url)?.stringOrNull("description")
    @Synchronized fun hasPageDescription(url: String): Boolean = pageMetadata.optJSONObject(url)?.let {
        it.optBoolean("hasDescription") || !it.stringOrNull("description").isNullOrBlank()
    } ?: false
    @Synchronized fun pageThumbnail(url: String): String? = pageMetadata.optJSONObject(url)?.stringOrNull("thumbnail")
    @Synchronized override fun home(): JSONObject {
        host.partialResults.clear()
        val home = SourceCodec.home(result("get_home"))
        if (home.getJSONArray("components").length() != 0) return home
        var components = JSONArray()
        for (data in host.partialResults) { val r = PostcardReader(data); when (r.byte()) {
            0 -> components = SourceCodec.home(r).getJSONArray("components")
            1 -> { val component = SourceCodec.component(r); val index = components.values().indexOfFirst { (it as? JSONObject)?.optString("title") == component.optString("title") }; if (index >= 0) components.put(index, component) else components.put(component) }
        } }; return JSONObject().put("components", components)
    }
    @Synchronized override fun filters(): List<JSONObject> = if (!legacy && "get_filters" in exports) { val r = result("get_filters"); r.list { SourceCodec.filter(r) } } else source.jsonArray("filters").values().filterIsInstance<JSONObject>()
    @Synchronized override fun settings(): List<JSONObject> {
        val dynamic = if (!legacy && "get_settings" in exports) { val r = result("get_settings"); r.list { SourceCodec.setting(r) } } else emptyList()
        val all = extraSettings() + source.jsonArray("settings").values().filterIsInstance<JSONObject>() + dynamic
        preferences.initialize(JSONArray(all)); return all
    }
    private fun extraSettings(includeDynamicUrl: Boolean = true): List<JSONObject> {
        val result = sourceLanguageSettings(context,source,legacy).toMutableList()
        val urls = (listOf(source.baseUrl) + source.info.optJSONArray("urls")?.values()?.map { it.toString() }.orEmpty() +
            if (includeDynamicUrl && "get_base_url" in exports) listOf(baseUrl()) else emptyList()).filter { it.isNotBlank() }.distinct()
        if (source.manifest.optJSONObject("config")?.optBoolean("allowsBaseUrlSelect") == true && urls.size > 1) {
            val setting = JSONObject().put("type", "select").put("key", "url").put("title", "BASE_URL").put("values", JSONArray(urls)).put("default", urls.first())
                .put("refreshes", JSONArray().put("content"))
            result += JSONObject().put("type", "group").put("title", "BASE_URL").put("items", JSONArray().put(setting))
        }
        return result
    }
    @Synchronized override fun listings(): List<JSONObject> = if (!legacy && "get_listings" in exports) { val r = result("get_listings"); r.list { SourceCodec.listing(r) } } else source.manifest.optJSONArray("listings")?.values()?.map { if (it is JSONObject) it else JSONObject().put("id", it).put("name", it) } ?: emptyList()
    @Synchronized override fun notify(key: String) { if ("handle_notification" in exports) withValues(listOf(if (legacy) key else encoded { it.string(key) })) { invoke("handle_notification", it[0]) } }
    @Synchronized override fun basicLogin(key: String, username: String, password: String): Boolean {
        val success = if ("handle_basic_login" in exports) withValues(listOf(key, username, password).map { encoded { w -> w.string(it) } }) { handles -> result("handle_basic_login", *handles.toLongArray()).bool() } else true
        if (success) { preferences.set("$key.username", username); preferences.set("$key.password", password); preferences.set(key, "logged_in") }; return success
    }
    @Synchronized override fun webLogin(key: String, cookies: Map<String, String>): Boolean {
        val success = if ("handle_web_login" in exports) withValues(listOf(encoded { it.string(key) }, encoded { it.strings(cookies.keys.toList()) }, encoded { it.strings(cookies.values.toList()) })) { handles -> result("handle_web_login", *handles.toLongArray()).bool() } else true
        if (success) { preferences.set("$key.keys", JSONArray(cookies.keys)); preferences.set("$key.values", JSONArray(cookies.values)); preferences.set(key, if (cookies.isEmpty()) null else "logged_in") }; return success
    }
    @Synchronized fun alternateCovers(manga: Manga): List<String> = withValues(listOf(encoded { SourceCodec.manga(it, rawManga(manga)) })) { result("get_alternate_covers", it[0]).strings() }
    @Synchronized override fun baseUrl(): String = if ("get_base_url" in exports) result("get_base_url").string() else (preferences.get("url") as? String) ?: source.baseUrl
    override fun importBrowserCookies(url: String, userAgent: String, cookies: String) = host.importBrowserCookies(url, userAgent, cookies)
    override fun importCookieRecords(url: String, cookies: List<okhttp3.Cookie>) = host.cookies.saveFromResponse(url.toHttpUrl(), cookies)
    override fun clearCookies() { host.cookies.clear(); SourceWebProfiles.clear(context, source.id); host.http.cache?.evictAll() }
    override fun fetch(request: Request): HostResponse = host.fetch(request)
    @Synchronized fun deepLink(url: String): SourceDeepLink? {
        if (legacy && "handle_url" in exports) return withValues(listOf(url)) { handles ->
            val j = legacyResult("handle_url", handles[0]) as? JSONObject ?: return@withValues null
            SourceDeepLink(source.id, j.optJSONObject("manga")?.let(::manga), j.optJSONObject("chapter")?.optString("key"))
        }
        if ("handle_deep_link" !in exports) return null
        return withValues(listOf(encoded { it.string(url) })) { handles ->
            val r = result("handle_deep_link", handles[0]); r.option {
                val key = r.option { r.string() }; val chapter = r.option { r.string() }; val listing = r.option { SourceCodec.listing(r) }
                SourceDeepLink(source.id, key?.let { Manga(it, source.id, "", metadata = JSONObject().put("key", it).put("title", "").toString()) }, chapter, listing)
            }
        }
    }
    @Synchronized fun migrateKey(mangaKey: String, chapterKey: String? = null): String {
        check("handle_key_migration" in exports) { "源没有提供键迁移函数" }
        return withValues(listOf(encoded { it.string(mangaKey) }, chapterKey?.let { key -> encoded { it.string(key) } })) { handles -> result("handle_key_migration", if (chapterKey == null) 0 else 1, handles[0], if (chapterKey == null) -1 else handles[1]).string() }
    }
    @Synchronized fun getPageDescription(url: String): String? {
        pageDescription(url)?.let { return it }
        val record = pageMetadata.optJSONObject(url) ?: return null
        if (legacy || !record.optBoolean("hasDescription") || "get_page_description" !in exports) return null
        val context = pageContext(url)
        var imageRef: Int? = null
        val raw = encoded { w ->
            when (record.optInt("kind", if (url.startsWith("file:") && url.endsWith(".txt")) 1 else 0)) {
                0 -> { w.byte(0).string(record.optString("url", url)); w.option(context) { map(it) } }
                1 -> w.byte(1).string(record.stringOrNull("text") ?: File(Uri.parse(url).path!!).readText())
                2 -> { imageRef = host.store(File(Uri.parse(url).path!!).readBytes()); w.byte(2).signed(imageRef!!.toLong()) }
                3 -> w.byte(3).string(record.getString("archiveUrl")).string(record.getString("filePath"))
                else -> error("无效页面类型")
            }
            w.option(record.stringOrNull("thumbnail")) { string(it) }; w.bool(true); w.option<String>(null) { string(it) }
        }
        return try { withValues(listOf(raw)) { result("get_page_description", it[0]).string() }.also { record.put("description", it); savePageMetadata() } }
        finally { imageRef?.let(host::destroy) }
    }
    private fun materialize(bytes: ByteArray, extension: String): String {
        val directory = File(context.filesDir, "source-content/${SourceRuntimeManager.digest(source.id)}").apply { mkdirs() }
        val name = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val file = File(directory, "$name.$extension"); if (!file.exists()) { val atomic = AtomicFile(file); val out = atomic.startWrite(); try { out.write(bytes); atomic.finishWrite(out) } catch (e: Exception) { atomic.failWrite(out); throw e } }; return Uri.fromFile(file).toString()
    }
    private fun savePageMetadata() { val out = pageMetaFile.startWrite(); try { out.write(pageMetadata.toString().toByteArray()); pageMetaFile.finishWrite(out) } catch (e: Exception) { pageMetaFile.failWrite(out); throw e } }
    override fun manga(j: JSONObject): Manga {
        val authors = j.optJSONArray("authors")?.values()?.map { it.toString() } ?: listOfNotNull(j.stringOrNull("author"))
        val artists = j.optJSONArray("artists")?.values()?.map { it.toString() } ?: listOfNotNull(j.stringOrNull("artist"))
        val chapters = j.optJSONArray("chapters")?.values()?.filterIsInstance<JSONObject>()?.mapIndexed { index, c ->
            c.put("_manga", JSONObject(j.toString()).also { it.remove("chapters") }); chapter(c).copy(sourceOrder = index)
        } ?: emptyList()
        return Manga(j.optString("key", j.optString("id")), source.id, j.optString("title"), j.optString("cover"), authors.joinToString(", "), j.optString("description"), j.optJSONArray("tags")?.values()?.map { it.toString() } ?: emptyList(), j.optInt("status"), chapters = chapters,
            artist = artists.joinToString(", "), url = j.optString("url"), viewer = listOf("auto", "ltr", "rtl", "vertical", "webtoon").getOrElse(j.optInt("viewer")) { "auto" }, contentRating = j.optInt("contentRating"), metadata = j.toString())
    }
    private fun chapter(j: JSONObject) = Chapter(j.optString("key", j.optString("id")), j.optString("title"), j.optDouble("chapterNumber", 0.0), volume = (j.value("volumeNumber") as? Number)?.toDouble(), language = j.optString("language"), scanlator = j.optJSONArray("scanlators")?.values()?.joinToString(", ") ?: j.optString("scanlator"), dateUploaded = ((j.value("dateUploaded") as? Number)?.toDouble()?.times(1000))?.toLong() ?: 0L, url = j.optString("url"), thumbnail = j.optString("thumbnail"), locked = j.optBoolean("locked"), metadata = j.toString())
    private fun mangas(j: JSONArray) = j.values().filterIsInstance<JSONObject>().map(::manga)
    private fun rawManga(m: Manga) = runCatching { JSONObject(m.metadata) }.getOrDefault(JSONObject()).apply {
        put("key", m.key); put("title", m.title); if (!has("cover") && m.cover.isNotEmpty()) put("cover", m.cover)
        if (!has("authors") && m.author.isNotEmpty()) put("authors", JSONArray().put(m.author)); if (!has("artists") && m.artist.isNotEmpty()) put("artists", JSONArray().put(m.artist))
        if (!has("description") && m.description.isNotEmpty()) put("description", m.description); if (!has("url") && m.url.isNotEmpty()) put("url", m.url); if (!has("tags") && m.tags.isNotEmpty()) put("tags", JSONArray(m.tags))
        put("status", m.status); put("contentRating", m.contentRating)
    }
    private fun rawChapter(c: Chapter) = runCatching { JSONObject(c.metadata) }.getOrDefault(JSONObject()).apply { put("key", c.key); if (!has("title") && c.title.isNotEmpty()) put("title", c.title); if (!has("chapterNumber")) put("chapterNumber", c.number); if (!has("volumeNumber")) put("volumeNumber", c.volume); put("locked", c.locked) }
    private fun legacyManga(j: JSONObject) = JSONObject(j.toString()).put("_legacyNativeKind", "manga").put("id", j.optString("key")).put("cover_url", j.optString("cover")).put("author", j.optJSONArray("authors")?.values()?.joinToString(", ").orEmpty()).put("artist", j.optJSONArray("artists")?.values()?.joinToString(", ").orEmpty()).put("nsfw", j.optInt("nsfw", (j.optInt("contentRating") - 1).coerceIn(0, 2))).put("legacyViewer", j.optInt("legacyViewer", when (j.optInt("viewer")) { 1 -> 2; 2 -> 1; else -> j.optInt("viewer") })).put("legacyStatus", j.optInt("legacyStatus", j.optInt("status")))
    private fun legacyChapter(j: JSONObject) = JSONObject(j.toString()).put("_legacyNativeKind", "chapter").put("id", j.optString("key")).put("name", j.optString("title")).put("chapterNum", j.value("chapterNumber")).put("volumeNum", j.value("volumeNumber")).put("lang", j.optString("language", "en")).put("scanlator", j.optJSONArray("scanlators")?.values()?.joinToString(", ").orEmpty())
    private fun legacyFilter(j: JSONObject): JSONObject {
        val raw = JSONObject(j.toString())
        val kind = j.opt("type")
        val type = if (kind is Number) listOf(2, 5, 3, 4, 1, 0).getOrElse(kind.toInt()) { 0 } else mapOf("text" to 2, "sort" to 5, "check" to 3, "select" to 4, "group" to 1, "title" to 7, "author" to 8, "genre" to 9)[kind] ?: 0
        raw.put("type", type).put("name", j.optString("name", j.optString("id")))
        if (type == 5) raw.put("value", JSONObject().put("index", j.optLong("index")).put("ascending", j.optBoolean("ascending")))
        if (type == 3 || type == 9) raw.put("value", when (j.optInt("value")) { 1 -> true; 2 -> false; else -> JSONObject.NULL })
        return raw
    }
    override fun close() { host.close() }
    companion object {
        private fun maximumMemoryPages() = (Runtime.getRuntime().maxMemory() / 3 / 65536).coerceIn(256, 4096).toInt()
        private fun validateMemory(module: com.dylibso.chicory.wasm.WasmModule) {
            val memories=module.memorySection().orElseThrow { IllegalArgumentException("Source has no linear memory") }
            require(memories.memoryCount()==1 && memories.getMemory(0).limits().initialPages()<=maximumMemoryPages()) { "Source memory exceeds the device budget" }
        }
        fun validateModule(bytes: ByteArray) { val module = Parser.parse(bytes); validateMemory(module); val names = (0 until module.exportSection().exportCount()).map { module.exportSection().getExport(it).name() }; require("get_page_list" in names && ("get_search_manga_list" in names || "get_manga_list" in names)) { "Invalid ${AppBrand.NAME} WebAssembly module" } }
    }
}
