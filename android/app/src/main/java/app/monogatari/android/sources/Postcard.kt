package app.monogatari.android.sources

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONArray
import org.json.JSONObject

/** Aidoku 0.7 uses serde/postcard, not JSON, across its host boundary. */
class PostcardReader(private val bytes: ByteArray) {
    var position = 0; private set
    fun byte(): Int { require(position < bytes.size) { "Truncated postcard" }; return bytes[position++].toInt() and 255 }
    fun unsigned(): Long {
        var result = 0L
        for (shift in 0..63 step 7) { val b = byte(); require(shift != 63 || b <= 1) { "Invalid varint" }; result = result or ((b and 127).toLong() shl shift); if (b < 128) return result }
        error("Invalid varint")
    }
    fun signed(): Long { val n = unsigned(); return (n ushr 1) xor -(n and 1) }
    fun bool(): Boolean = when (val n = byte()) { 0 -> false; 1 -> true; else -> error("Invalid boolean $n") }
    fun raw(n: Int): ByteArray { require(n >= 0 && n <= bytes.size - position) { "Truncated postcard buffer" }; return bytes.copyOfRange(position, position + n).also { position += n } }
    fun string(): String = raw(count()).toString(Charsets.UTF_8)
    fun count(): Int = unsigned().also { require(it in 0..1_000_000) { "Postcard collection too large" } }.toInt()
    fun float(): Float = ByteBuffer.wrap(raw(4)).order(ByteOrder.LITTLE_ENDIAN).float
    fun double(): Double = ByteBuffer.wrap(raw(8)).order(ByteOrder.LITTLE_ENDIAN).double
    fun <T> option(read: () -> T): T? = when (byte()) { 0 -> null; 1 -> read(); else -> error("Invalid option") }
    fun <T> list(read: () -> T): List<T> = List(count()) { read() }
    fun strings() = list { string() }
    fun map(): Map<String, String> = List(count()) { string() to string() }.toMap()
    fun end() { require(position == bytes.size) { "Unexpected postcard trailing data (${bytes.size - position})" } }
}
class PostcardWriter {
    private val output = ByteArrayOutputStream()
    fun byte(n: Int) = apply { output.write(n) }
    fun raw(b: ByteArray) = apply { output.write(b) }
    fun unsigned(n: Long) = apply { var value = n; do { val b = (value and 127).toInt(); value = value ushr 7; output.write(b or if (value != 0L) 128 else 0) } while (value != 0L) }
    fun signed(n: Long) = unsigned((n shl 1) xor (n shr 63))
    fun bool(b: Boolean) = byte(if (b) 1 else 0)
    fun string(s: String) = apply { val b = s.toByteArray(Charsets.UTF_8); unsigned(b.size.toLong()); raw(b) }
    fun float(n: Float) = raw(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(n).array())
    fun double(n: Double) = raw(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(n).array())
    fun <T> option(value: T?, write: PostcardWriter.(T) -> Unit) = apply { byte(if (value == null) 0 else 1); if (value != null) write(value) }
    fun <T> list(values: List<T>, write: PostcardWriter.(T) -> Unit) = apply { unsigned(values.size.toLong()); values.forEach { write(it) } }
    fun strings(values: List<String>) = list(values) { string(it) }
    fun map(values: Map<String, String>) = list(values.entries.toList()) { string(it.key); string(it.value) }
    fun bytes() = output.toByteArray()
}

internal fun JSONObject.value(key: String): Any? = opt(key).takeUnless { it == JSONObject.NULL }
internal fun JSONObject.stringOrNull(key: String): String? = value(key)?.toString()
internal fun JSONArray.values(): List<Any?> = (0 until length()).map { opt(it).takeUnless { v -> v == JSONObject.NULL } }

/** Mirrors the field order and enum representations at AidokuRunner cc4d06f. */
object SourceCodec {
    fun manga(r: PostcardReader): JSONObject = JSONObject().put("key", r.string()).put("title", r.string())
        .put("cover", r.option { r.string() }).put("artists", r.option { JSONArray(r.strings()) })
        .put("authors", r.option { JSONArray(r.strings()) }).put("description", r.option { r.string() })
        .put("url", r.option { r.string() }).put("tags", r.option { JSONArray(r.strings()) })
        .put("status", r.byte()).put("contentRating", r.byte()).put("viewer", r.byte()).put("updateStrategy", r.byte())
        .put("nextUpdateTime", r.option { r.signed() }).put("chapters", r.option { JSONArray(r.list { chapter(r) }) })
    fun manga(w: PostcardWriter, j: JSONObject) {
        w.string(j.optString("key", j.optString("id"))).string(j.optString("title"))
        w.option(j.stringOrNull("cover")) { w.string(it) }
        for (key in listOf("artists", "authors")) w.option(j.optJSONArray(key)) { w.strings(it.values().map(Any?::toString)) }
        w.option(j.stringOrNull("description")) { w.string(it) }; w.option(j.stringOrNull("url")) { w.string(it) }
        w.option(j.optJSONArray("tags")) { w.strings(it.values().map(Any?::toString)) }
        w.byte(j.optInt("status")).byte(j.optInt("contentRating")).byte(j.optInt("viewer")).byte(j.optInt("updateStrategy"))
        w.option((j.value("nextUpdateTime") as? Number)?.toLong()) { w.signed(it) }
        w.option(j.optJSONArray("chapters")) { w.list(it.values()) { item -> mangaChapter(w, item as JSONObject) } }
    }
    private fun mangaChapter(w: PostcardWriter, j: JSONObject) = chapter(w, j)
    fun chapter(r: PostcardReader): JSONObject = JSONObject().put("key", r.string()).put("title", r.option { r.string() })
        .put("chapterNumber", r.option { r.float() }).put("volumeNumber", r.option { r.float() })
        .put("dateUploaded", r.option { r.signed() }).put("scanlators", r.option { JSONArray(r.strings()) })
        .put("url", r.option { r.string() }).put("language", r.option { r.string() })
        .put("thumbnail", r.option { r.string() }).put("locked", r.bool())
    fun chapter(w: PostcardWriter, j: JSONObject) {
        w.string(j.optString("key", j.optString("id"))); w.option(j.stringOrNull("title")) { w.string(it) }
        for (key in listOf("chapterNumber", "volumeNumber")) w.option((j.value(key) as? Number)?.toFloat()) { w.float(it) }
        w.option((j.value("dateUploaded") as? Number)?.toLong()) { w.signed(it) }
        w.option(j.optJSONArray("scanlators")) { w.strings(it.values().map(Any?::toString)) }
        for (key in listOf("url", "language", "thumbnail")) w.option(j.stringOrNull(key)) { w.string(it) }
        w.bool(j.optBoolean("locked"))
    }
    fun listing(r: PostcardReader) = JSONObject().put("id", r.string()).put("name", r.string()).put("kind", r.byte())
    fun listing(w: PostcardWriter, j: JSONObject) { w.string(j.optString("id")).string(j.optString("name", j.optString("id"))).byte(j.optInt("kind")) }
    fun filterValues(w: PostcardWriter, values: List<JSONObject>) { w.list(values) { j ->
        val kind = j.optInt("type"); w.byte(kind).string(j.getString("id"))
        when (kind) {
            0, 3 -> w.string(j.optString("value"))
            1 -> w.signed(j.optLong("index")).bool(j.optBoolean("ascending"))
            2 -> w.signed(j.optLong("value"))
            4 -> { w.strings(j.optJSONArray("included")?.values()?.map(Any?::toString) ?: emptyList()); w.strings(j.optJSONArray("excluded")?.values()?.map(Any?::toString) ?: emptyList()) }
            5 -> for (key in listOf("from", "to")) w.option((j.value(key) as? Number)?.toFloat()) { w.float(it) }
            else -> error("Unknown filter value $kind")
        }
    } }
    fun filterValue(r: PostcardReader): JSONObject {
        val kind = r.byte(); val j = JSONObject().put("type", kind).put("id", r.string())
        when (kind) { 0, 3 -> j.put("value", r.string()); 1 -> j.put("index", r.signed()).put("ascending", r.bool()); 2 -> j.put("value", r.signed()); 4 -> j.put("included", JSONArray(r.strings())).put("excluded", JSONArray(r.strings())); 5 -> j.put("from", r.option { r.float() }).put("to", r.option { r.float() }); else -> error("Unknown filter type") }
        return j
    }
    fun filter(r: PostcardReader): JSONObject {
        val j = JSONObject().put("id", r.option { r.string() }).put("title", r.option { r.string() }).put("hideFromHeader", r.option { r.bool() })
        val type = r.string(); j.put("type", type)
        fun optional(key: String, read: () -> Any) { j.put(key, r.option(read)) }
        when (type) {
            "text" -> optional("placeholder") { r.string() }
            "sort" -> { j.put("canAscend", r.bool()).put("options", JSONArray(r.strings())); optional("default") { JSONObject().put("index", r.signed()).put("ascending", r.bool()) } }
            "check" -> { optional("name") { r.string() }; optional("canExclude") { r.bool() }; optional("default") { r.bool() } }
            "select", "multi-select" -> {
                j.put("isGenre", r.bool()); if (type == "multi-select") j.put("canExclude", r.bool())
                j.put("usesTagStyle", r.bool()).put("options", JSONArray(r.strings())); optional("ids") { JSONArray(r.strings()) }
                if (type == "select") optional("default") { r.string() } else { optional("defaultIncluded") { JSONArray(r.strings()) }; optional("defaultExcluded") { JSONArray(r.strings()) } }
            }
            "note" -> j.put("text", r.string())
            "range" -> { optional("min") { r.float() }; optional("max") { r.float() }; j.put("decimal", r.bool()) }
            else -> error("Unknown filter definition $type")
        }
        return j
    }
    fun setting(r: PostcardReader): JSONObject {
        val j = JSONObject().put("type", r.string()).put("key", r.option { r.string() }).put("title", r.string())
            .put("notification", r.option { r.string() }).put("requires", r.option { r.string() }).put("requiresFalse", r.option { r.string() })
            .put("refreshes", r.option { JSONArray(r.strings()) })
        val kind = r.byte()
        fun opt(key: String, read: () -> Any) { j.put(key, r.option(read)) }
        when (kind) {
            0 -> { opt("footer") { r.string() }; j.put("items", JSONArray(r.list { setting(r) })) }
            1, 2, 13 -> { j.put("values", JSONArray(r.strings())); opt("titles") { JSONArray(r.strings()) }; if (kind != 13) opt("authToOpen") { r.bool() }; opt("default") { if (kind == 2) JSONArray(r.strings()) else r.string() } }
            3 -> { opt("subtitle") { r.string() }; opt("authToDisable") { r.bool() }; j.put("default", r.bool()) }
            4 -> { j.put("minimumValue", r.double()).put("maximumValue", r.double()); opt("stepValue") { r.double() }; opt("default") { r.double() } }
            5 -> { j.put("options", JSONArray(r.strings())); opt("default") { r.signed() } }
            6 -> { opt("placeholder") { r.string() }; for (key in listOf("autocapitalizationType", "keyboardType", "returnKeyType")) opt(key) { r.signed() }; for (key in listOf("autocorrectionDisabled", "secure")) opt(key) { r.bool() }; opt("default") { r.string() } }
            7 -> { opt("destructive") { r.bool() }; opt("confirmTitle") { r.string() }; opt("confirmText") { r.string() } }
            8 -> { j.put("url", r.string()); opt("external") { r.bool() } }
            9 -> { j.put("method", r.string()); for (key in listOf("url", "urlKey", "logoutTitle")) opt(key) { r.string() }; opt("pkce") { r.bool() }; opt("tokenUrl") { r.string() }; opt("callbackScheme") { r.string() }; opt("useEmail") { r.bool() }; opt("localStorageKeys") { JSONArray(r.strings()) }; opt("clearCookiesOnLogOut") { r.bool() } }
            10 -> { j.put("items", JSONArray(r.list { setting(r) })); opt("inlineTitle") { r.bool() }; opt("authToOpen") { r.bool() }; opt("icon") { val icon = JSONObject().put("type", r.string()); if (icon.optString("type") == "system") icon.put("name", r.string()).put("color", r.string()).put("inset", r.option { r.signed() }) else icon.put("url", r.string()); icon }; opt("info") { r.string() } }
            11 -> { opt("lineLimit") { r.signed() }; opt("inline") { r.bool() }; opt("placeholder") { r.string() }; opt("default") { JSONArray(r.strings()) } }
            12 -> Unit
            else -> error("Unknown setting $kind")
        }
        return j
    }
    fun home(r: PostcardReader) = JSONObject().put("components", JSONArray(r.list { component(r) }))
    fun component(r: PostcardReader): JSONObject {
        val j = JSONObject().put("title", r.option { r.string() }).put("subtitle", r.option { r.string() }).put("type", r.byte())
        when (j.getInt("type")) {
            0 -> { j.put("entries", JSONArray(r.list { link(r) })); j.put("autoScrollInterval", r.option { r.float() }).put("width", r.option { r.signed() }).put("height", r.option { r.signed() }) }
            1 -> j.put("manga", JSONArray(r.list { manga(r) })).put("autoScrollInterval", r.option { r.float() })
            2 -> j.put("entries", JSONArray(r.list { link(r) })).put("listing", r.option { listing(r) })
            3 -> j.put("ranking", r.bool()).put("pageSize", r.option { r.signed() }).put("entries", JSONArray(r.list { link(r) })).put("listing", r.option { listing(r) })
            4 -> j.put("pageSize", r.option { r.signed() }).put("entries", JSONArray(r.list { JSONObject().put("manga", manga(r)).put("chapter", chapter(r)) })).put("listing", r.option { listing(r) })
            5 -> j.put("filters", JSONArray(r.list { JSONObject().put("title", r.string()).put("values", r.option { JSONArray(r.list { filterValue(r) }) }) }))
            6 -> j.put("entries", JSONArray(r.list { link(r) }))
            else -> error("Unknown home component")
        }; return j
    }
    private fun link(r: PostcardReader): JSONObject = JSONObject().put("title", r.string()).put("subtitle", r.option { r.string() }).put("imageUrl", r.option { r.string() }).put("value", r.option {
        val type = r.byte(); JSONObject().put("type", type).put("data", when (type) { 0 -> r.string(); 1 -> listing(r); 2 -> manga(r); else -> error("Unknown home link") })
    })
}
