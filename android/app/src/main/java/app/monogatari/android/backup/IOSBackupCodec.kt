package app.monogatari.android.backup

import app.monogatari.android.AppBrand

import android.util.Base64
import app.monogatari.android.data.*
import com.dd.plist.NSObject
import com.dd.plist.PropertyListParser
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.util.Date

/** Lossless sidecar keeps unknown original fields while native models expose supported values. */
object IOSBackupCodec {
    private fun array(j: JSONObject, key: String) = j.optJSONArray(key).objects()
    private fun mangaId(j: JSONObject, key: String = "mangaId") = "${j.optString("sourceId")}::${j.optString(key)}"
    private fun chapterId(source: String, manga: String, chapter: String) = JSONArray(listOf(source, manga, chapter)).toString()
    private fun chapterId(j: JSONObject, key: String = "chapterId") = chapterId(j.optString("sourceId"), j.optString("mangaId"), j.optString(key))
    private fun rawRows(j: JSONObject, key: String, id: (JSONObject) -> String): Map<String, JSONObject> = array(j, key).associateBy(id)
    private fun mergeRow(raw: JSONObject?, block: (JSONObject) -> Unit): JSONObject = JSONObject(raw?.toString() ?: "{}").also(block)
    fun toIOS(state: AppState): JSONObject {
        val result = JSONObject(state.backupExtras)
        val now = System.currentTimeMillis() / 1000.0
        val originalManga = rawRows(result, "manga") { mangaId(it, "id") }
        val originalLibrary = rawRows(result, "library") { mangaId(it) }
        val originalChapters = rawRows(result, "chapters") { chapterId(it, "id") }
        val originalHistory = rawRows(result, "history") { chapterId(it) } + rawRows(JSONObject(state.records), "history") { chapterId(it) }
        result.put("date", now).put("name", AppBrand.NAME).put("automatic", false).put("version", app.monogatari.android.BuildConfig.VERSION_NAME)
        result.put("manga", JSONArray(state.manga.map { m -> mergeRow(originalManga[m.id]) { j ->
            JSONObject(m.metadata).keys().forEach { key -> if (key !in setOf("lastOpened", "lastUpdated", "lastUpdatedChapters", "newChapterCount", "_androidMissingDetails", "_androidMissingChapterDetails")) j.put(key, JSONObject(m.metadata).opt(key)) }
            j.put("id", m.key).put("sourceId", m.sourceId).put("title", m.title).put("author", m.author).put("artist", m.artist).put("desc", m.description).put("tags", JSONArray(m.tags))
                .put("cover", m.cover.takeUnless { it.startsWith("file:") }).put("url", m.url).put("status", m.status).put("nsfw", m.contentRating)
                .put("viewer", when (m.viewer) { "rtl" -> 1; "ltr" -> 2; "vertical" -> 3; "webtoon", "continuous" -> 4; else -> 0 })
        } }))
        result.put("library", JSONArray(state.manga.filter { it.inLibrary }.map { m -> mergeRow(originalLibrary[m.id]) { j ->
            j.put("mangaId", m.key).put("sourceId", m.sourceId).put("categories", JSONArray(m.categories)).put("dateAdded", m.addedAt / 1000.0)
                .put("lastOpened", JSONObject(m.metadata).opt("lastOpened")?.let { (it as? Number)?.toDouble()?.div(1000.0) } ?: j.optDouble("lastOpened", m.lastRead / 1000.0))
                .put("lastUpdated", JSONObject(m.metadata).opt("lastUpdated")?.let { (it as? Number)?.toDouble()?.div(1000.0) } ?: j.optDouble("lastUpdated", now))
                .put("lastUpdatedChapters", JSONObject(m.metadata).opt("lastUpdatedChapters")?.let { (it as? Number)?.toDouble()?.div(1000.0) } ?: j.optDouble("lastUpdatedChapters", now))
        } }))
        result.put("chapters", JSONArray(state.manga.flatMap { m -> m.chapters.mapIndexed { index, c -> mergeRow(originalChapters[chapterId(m.sourceId, m.key, c.key)]) { j ->
            val metadata = JSONObject(c.metadata); metadata.keys().asSequence().filter { it !in setOf("_androidMissingDetails", "_androidMissingChapterDetails") }.forEach { j.put(it, metadata.opt(it)) }
            j.put("id", c.key).put("sourceId", m.sourceId).put("mangaId", m.key).put("title", c.title).put("chapter", app.monogatari.android.reader.ChapterListing.number(c)).put("volume", c.volume)
                .put("lang", c.language).put("scanlator", c.scanlator).put("url", c.url).put("thumbnail", c.thumbnail.takeUnless { it.startsWith("file:") }).put("locked", c.locked).put("dateUploaded", c.dateUploaded.takeIf { it != 0L }?.div(1000.0)).put("sourceOrder", c.sourceOrder.takeIf { it != 0 } ?: index)
        } } }))
        val nativeChapterIds = state.manga.flatMap { m -> m.chapters.map { chapterId(m.sourceId, m.key, it.key) } }.toSet()
        val generated = state.manga.flatMap { m -> m.chapters.filter { it.readAt != 0L || it.completed }.map { c -> mergeRow(originalHistory[chapterId(m.sourceId, m.key, c.key)]) { j ->
            val unread = originalHistory[chapterId(m.sourceId, m.key, c.key)]?.let { it.optInt("progress", 0) < 0 && c.page == 0 && !c.completed && (it.optDouble("dateRead", 0.0) * 1000).toLong() == c.readAt } == true
            j.put("sourceId", m.sourceId).put("mangaId", m.key).put("chapterId", c.key).put("progress", if (unread) -1 else Math.addExact(c.page, 1))
                .put("total", c.total).put("completed", c.completed).put("dateRead", c.readAt / 1000.0)
            JSONObject(c.metadata).opt("scrollPosition")?.let { j.put("scrollPosition", it) }
        } } }
        result.put("history", JSONArray(generated + originalHistory.values.filter { chapterId(it) !in nativeChapterIds }))
        val rawCategories = result.optJSONArray("categories")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
        result.put("categories", JSONArray(state.categories.mapIndexed { index, title ->
            val raw = JSONObject(state.categoryMetadata).optJSONObject(title) ?: rawCategories.firstOrNull { it.optString("title") == title } ?: JSONObject().put("group", false)
            JSONObject(raw.toString()).put("title", title).put("sort", index).also { category -> category.optJSONArray("filters")?.let { category.put("data", Base64.encodeToString(it.toString().toByteArray(), Base64.NO_WRAP)) } }
        }))
        result.put("settings", JSONObject(state.settings)).put("sourceLists", JSONArray(state.sourceLists.map { it.url }))
        val rawSources = result.optJSONArray("sources") ?: JSONArray()
        val sourceRows = (0 until rawSources.length()).mapNotNull { rawSources.optJSONObject(it) }.associateBy { it.optString("id") }
        val sources = state.sources.filter { it.type in setOf("demo", "local", "komga", "kavita", "suwayomi") }.map { source -> mergeRow(sourceRows[source.id]) { it.put("id", source.id).put("apiVersion", sourceRows[source.id]?.optString("apiVersion") ?: "0.6").put("config", Base64.encodeToString(CustomSourceCodec.encode(source), Base64.NO_WRAP)) } }
        val external = state.sources.filter { it.type == "wasm" }.map { sourceRows[it.id] ?: it.id }
        result.put("sources", JSONArray(sources + external + (0 until rawSources.length()).mapNotNull { i -> rawSources.opt(i).takeIf { value -> val key = if (value is JSONObject) value.optString("id") else value.toString(); state.sources.none { it.id == key } } }))
        val records = JSONObject(state.records)
        listOf("readingSessions", "updates").forEach { key -> records.optJSONArray(key)?.let { result.put(key, it) } }
        return result
    }
    fun fromIOS(j: JSONObject): AppState {
        require(listOf("manga", "library", "history", "chapters", "settings", "vocabulary", "trackItems", "sources").any(j::has)) { "不是${AppBrand.NAME}备份" }
        validate(j)
        val library = rawRows(j, "library") { mangaId(it) }; val history = rawRows(j, "history") { chapterId(it) }
        val chapterRows = array(j, "chapters").toMutableList()
        val knownChapters = chapterRows.map { chapterId(it, "id") }.toSet()
        array(j, "history").filter { chapterId(it) !in knownChapters }.forEach { h -> chapterRows += JSONObject().put("id", h.getString("chapterId")).put("mangaId", h.getString("mangaId")).put("sourceId", h.getString("sourceId")).put("title", h.getString("chapterId")).put("lang", "").put("sourceOrder", 0).put("_androidMissingChapterDetails", true) }
        val chapters = chapterRows.groupBy { mangaId(it) }
        val mangaRows = array(j, "manga").toMutableList(); val knownManga = mangaRows.map { mangaId(it, "id") }.toSet()
        (array(j, "library") + chapterRows).distinctBy { mangaId(it) }.filter { mangaId(it) !in knownManga }.forEach { row -> mangaRows += JSONObject().put("id", row.getString("mangaId")).put("sourceId", row.getString("sourceId")).put("title", row.getString("mangaId")).put("_androidMissingDetails", true) }
        val manga = mangaRows.map { m ->
            val id = mangaId(m, "id"); val l = library[id]
            Manga(m.getString("id"), m.getString("sourceId"), m.getString("title"), m.optString("cover").nullString(), m.optString("author").nullString(), m.optString("desc").nullString(), m.optJSONArray("tags").strings(), m.optInt("status"),
                chapters[id].orEmpty().sortedBy { it.optInt("sourceOrder") }.map { c -> val h = history[chapterId(c, "id")]
                    Chapter(c.getString("id"), c.optString("title").nullString(), c.optDouble("chapter", 0.0).finite(), page = ((h?.optInt("progress") ?: 0) - 1).coerceAtLeast(0), total = h?.optInt("total") ?: 0,
                        completed = h?.optBoolean("completed") ?: false, readAt = h?.optDouble("dateRead")?.millis() ?: 0, volume = c.opt("volume").asFinite(), language = c.optString("lang").nullString(), scanlator = c.optString("scanlator").nullString(),
                        dateUploaded = c.opt("dateUploaded").asFinite()?.millis() ?: 0, url = c.optString("url").nullString(), thumbnail = c.optString("thumbnail").nullString(), sourceOrder = c.optInt("sourceOrder"), locked = c.optBoolean("locked"), metadata = JSONObject(c.toString()).also { metadata -> h?.opt("scrollPosition")?.let { metadata.put("scrollPosition", it) } }.toString())
                }, inLibrary = l != null, categories = l?.optJSONArray("categories").strings(), addedAt = l?.optDouble("dateAdded")?.millis() ?: 0,
                artist = m.optString("artist").nullString(), url = m.optString("url").nullString(), viewer = listOf("auto", "rtl", "ltr", "vertical", "webtoon").getOrElse(m.optInt("viewer")) { "auto" }, contentRating = m.optInt("nsfw"), metadata = JSONObject(m.toString()).also { metadata -> listOf("lastOpened", "lastUpdated", "lastUpdatedChapters").forEach { key -> (l?.opt(key) as? Number)?.toDouble()?.let { metadata.put(key, it.millis()) } } }.toString())
        }
        val categoryMetadata = JSONObject(); val categories = j.optJSONArray("categories")?.let { a -> (0 until a.length()).map { i ->
            val objectValue = a.optJSONObject(i); val title = objectValue?.getString("title") ?: a.getString(i)
            categoryMetadata.put(title, JSONObject(objectValue?.toString() ?: "{}").put("title", title).also { metadata ->
                if (metadata.optBoolean("group") && !metadata.has("filters")) metadata.optString("data").takeIf { it.isNotBlank() }?.let { data -> runCatching { JSONArray(Base64.decode(data, Base64.DEFAULT).toString(Charsets.UTF_8)) }.getOrNull()?.let { metadata.put("filters", it) } }
            }); title
        } }.orEmpty()
        val sources = j.optJSONArray("sources")?.let { a -> (0 until a.length()).map { i ->
            val row = a.optJSONObject(i); val id = row?.getString("id") ?: a.getString(i)
            row?.optString("config")?.takeIf { it.isNotBlank() }?.let { config -> CustomSourceCodec.decode(Base64.decode(config, Base64.DEFAULT)).copy(id = id) } ?: SourceConfig(id, id, "wasm")
        } }.orEmpty()
        val sourceLists = j.optJSONArray("sourceLists").strings().map { url -> val uri = java.net.URI(url); require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()) { "源列表地址无效" }; SourceList(url, uri.host, emptyList()) }
        val records = JSONObject(); listOf("history", "readingSessions", "updates").forEach { key -> j.optJSONArray(key)?.let { records.put(key, it) } }
        return AppState(manga = manga, sources = (listOf(SourceConfig.local) + sources).distinctBy { it.id }, sourceLists = sourceLists, categories = categories,
            backupExtras = j.toString(), settings = j.optJSONObject("settings")?.toString() ?: "{}", categoryMetadata = categoryMetadata.toString(), records = records.toString())
    }
    fun validate(j: JSONObject) {
        listOf("manga", "library", "chapters", "history", "readingSessions", "updates", "vocabulary", "trackItems", "categories", "sources", "sourceLists").forEach { key -> require(!j.has(key) || j.isNull(key) || j.opt(key) is JSONArray) { "备份 $key 类型无效" } }
        require(!j.has("settings") || j.isNull("settings") || j.opt("settings") is JSONObject)
        fun finite(value: Any?) { if (value != null && value != JSONObject.NULL) require(value is Number && value.toDouble().isFinite()) { "备份包含无效数值" } }
        fun small(value: Any?, min: Int = -32768, max: Int = 32767) { if (value != null && value != JSONObject.NULL) { require(value is Number && value.toDouble().isFinite() && value.toDouble() >= min && value.toDouble() <= max && value.toDouble() == value.toLong().toDouble()) { "备份整数超出原数据库范围" } } }
        array(j, "manga").forEach { m -> listOf("id", "sourceId", "title").forEach { require(m.has(it) && m.get(it) is String) }; listOf("status", "nsfw", "viewer", "chapterFlags").forEach { small(m.opt(it)) }; small(m.opt("editedKeys"), Int.MIN_VALUE, Int.MAX_VALUE) }
        array(j, "chapters").forEach { c -> listOf("id", "sourceId", "mangaId", "lang").forEach { require(c.has(it) && c.get(it) is String) }; small(c.opt("sourceOrder")); finite(c.opt("chapter")); finite(c.opt("volume")) }
        array(j, "history").forEach { h -> listOf("sourceId", "mangaId", "chapterId").forEach { require(h.get(it) is String) }; small(h.opt("progress")); small(h.opt("total"), 0); finite(h.opt("dateRead")) }
        array(j, "readingSessions").forEach { s -> small(s.opt("pagesRead"), 0); require(s.optDouble("endDate") > s.optDouble("startDate")) { "阅读会话时间无效" } }
        array(j, "vocabulary").forEach { v -> require(v.optString("word").isNotBlank()); small(v.opt("page"), 0); small(v.opt("clozeOffset")) }
        array(j, "trackItems").forEach { small(it.opt("chapterOffset")) }
        fun date(value: Any?) { if (value != null && value != JSONObject.NULL) { finite(value); (value as Number).toDouble().millis() } }
        date(j.opt("date"))
        mapOf("manga" to listOf("nextUpdateTime"), "library" to listOf("lastOpened", "lastUpdated", "lastUpdatedChapters", "lastChapter", "lastRead", "dateAdded"), "history" to listOf("dateRead"), "chapters" to listOf("dateUploaded"), "readingSessions" to listOf("startDate", "endDate"), "vocabulary" to listOf("createdDate"), "updates" to listOf("date")).forEach { (section, keys) -> array(j, section).forEach { row -> keys.forEach { date(row.opt(it)) } } }
        j.optJSONArray("categories")?.let { a -> repeat(a.length()) { i -> a.optJSONObject(i)?.let { require(!it.isNull("title") && it.get("title") is String); small(it.opt("sort")) } } }
    }
    fun writeBinary(j: JSONObject, output: OutputStream) = PropertyListParser.saveAsBinary(NSObject.fromJavaObject(toJava(j, "", j.optJSONObject("_aidokuPlistTypes") ?: JSONObject())), output)
    fun decode(input: ByteArray): JSONObject = if (input.take(6).toByteArray().toString(Charsets.US_ASCII) == "bplist" || input.toString(Charsets.UTF_8).trimStart().startsWith("<")) {
        val types = JSONObject(); val value = fromJava(PropertyListParser.parse(input).toJavaObject(), "", types) as? JSONObject ?: error("属性列表根对象无效")
        if (types.length() > 0) value.put("_aidokuPlistTypes", types); value
    } else JSONObject(input.toString(Charsets.UTF_8))
    private val dateKeys = setOf("date", "dateAdded", "dateRead", "lastOpened", "lastUpdated", "lastUpdatedChapters", "lastChapter", "lastRead", "nextUpdateTime", "dateUploaded", "startDate", "endDate", "createdDate")
    private fun toJava(value: Any?, path: String, types: JSONObject): Any? = when {
        types.optString(path) == "date" && value is Number -> Date(value.toDouble().millis())
        types.optString(path) == "data" && value is String -> Base64.decode(value, Base64.DEFAULT)
        else -> when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> {
            val nestedTypes = JSONObject(types.toString()); value.optJSONObject("_aidokuPlistFieldTypes")?.let { local -> local.keys().forEach { relative -> nestedTypes.put(if (path.isEmpty()) relative else "$path.$relative", local.opt(relative)) } }
            value.keys().asSequence().filter { it !in setOf("_aidokuPlistTypes", "_aidokuPlistFieldTypes") }.mapNotNull { key -> val v = value.opt(key); if (v == JSONObject.NULL) null else key to when {
            key in dateKeys && v is Number && (path.isEmpty() && key == "date" || Regex("^(manga|library|chapters|history|readingSessions|updates|vocabulary)\\[\\d+]$").matches(path)) -> Date(v.toDouble().millis())
            key == "config" && Regex("^sources\\[\\d+]$").matches(path) && v is String -> Base64.decode(v, Base64.DEFAULT)
            key == "data" && Regex("^categories\\[\\d+]$").matches(path) && v is String -> Base64.decode(v, Base64.DEFAULT)
            else -> toJava(v, if (path.isEmpty()) key else "$path.$key", nestedTypes)
        } }.toMap() }
        is JSONArray -> (0 until value.length()).map { toJava(value.opt(it), "$path[$it]", types) }.filterNotNull().toTypedArray()
        else -> value
    } }
    private fun fromJava(v: Any?, path: String, types: JSONObject): Any = when (v) {
        null -> JSONObject.NULL
        is Date -> (v.time / 1000.0).also { types.put(path, "date") }
        is ByteArray -> Base64.encodeToString(v, Base64.NO_WRAP).also { types.put(path, "data") }
        is Map<*, *> -> JSONObject().also { j ->
            val local = JSONObject()
            fun localTypes(value: Any?, name: String) { when (value) { is Date -> local.put(name, "date"); is ByteArray -> local.put(name, "data"); is Array<*> -> value.forEachIndexed { index, item -> localTypes(item, "$name[$index]") }; is Collection<*> -> value.forEachIndexed { index, item -> localTypes(item, "$name[$index]") } } }
            v.forEach { (k, value) -> localTypes(value, k.toString()); j.put(k.toString(), fromJava(value, if (path.isEmpty()) k.toString() else "$path.$k", types)) }
            if (local.length() > 0) j.put("_aidokuPlistFieldTypes", local)
        }
        is Array<*> -> JSONArray(v.mapIndexed { index, value -> fromJava(value, "$path[$index]", types) })
        is Collection<*> -> JSONArray(v.mapIndexed { index, value -> fromJava(value, "$path[$index]", types) })
        else -> v
    }
    private fun String.nullString() = takeUnless { it == "null" }.orEmpty()
    private fun Any?.asFinite(): Double? = (this as? Number)?.toDouble()?.takeIf { it.isFinite() }
    private fun Double.finite() = if (isFinite()) this else 0.0
    private fun Double.millis(): Long { require(isFinite() && this <= Long.MAX_VALUE / 1000.0 && this >= Long.MIN_VALUE / 1000.0) { "备份日期超出范围" }; return (this * 1000).toLong() }
}
