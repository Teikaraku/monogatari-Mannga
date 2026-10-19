package app.monogatari.android.sources

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/** The original WK content-rule JSON is evaluated in order, including ignore-previous-rules. */
internal class SourceContentRules(json: String = "[]") {
    data class Decision(val block: Boolean = false, val blockCookies: Boolean = false, val makeHttps: Boolean = false, val selectors: List<String> = emptyList())
    private val rules = JSONArray(json).values().map { value ->
        val rule = value as? JSONObject ?: error("无效网页过滤规则")
        val trigger = rule.getJSONObject("trigger")
        val action = rule.getJSONObject("action")
        require(action.getString("type") in setOf("block", "block-cookies", "css-display-none", "ignore-previous-rules", "make-https")) { "未知网页过滤动作" }
        require(trigger.keys().asSequence().all { it in setOf("url-filter", "url-filter-is-case-sensitive", "resource-type", "if-domain", "unless-domain", "load-type", "if-top-url", "unless-top-url", "load-context") }) { "未知网页过滤条件" }
        Regex(trigger.getString("url-filter"), if (trigger.optBoolean("url-filter-is-case-sensitive")) emptySet() else setOf(RegexOption.IGNORE_CASE))
        rule
    }
    fun decision(address: String, topAddress: String, resourceType: String, mainFrame: Boolean): Decision {
        val url = address.toHttpUrlOrNull() ?: return Decision()
        val top = topAddress.toHttpUrlOrNull()
        var result = Decision()
        fun matchesDomain(pattern: String, host: String): Boolean = if (pattern.startsWith("*")) host.endsWith(pattern.drop(1), true) || host.equals(pattern.drop(1).trimStart('.'), true) else host.equals(pattern, true)
        fun strings(trigger: JSONObject, key: String) = trigger.optJSONArray(key)?.values()?.map { it.toString() }
        for (rule in rules) {
            val t = rule.getJSONObject("trigger")
            val options = if (t.optBoolean("url-filter-is-case-sensitive")) emptySet() else setOf(RegexOption.IGNORE_CASE)
            if (!Regex(t.getString("url-filter"), options).containsMatchIn(address)) continue
            if (strings(t, "resource-type")?.let { resourceType !in it } == true) continue
            val documentHost = top?.host ?: url.host
            if (strings(t, "if-domain")?.none { matchesDomain(it, documentHost) } == true) continue
            if (strings(t, "unless-domain")?.any { matchesDomain(it, documentHost) } == true) continue
            if (strings(t, "if-top-url")?.none { Regex(it, options).containsMatchIn(topAddress) } == true) continue
            if (strings(t, "unless-top-url")?.any { Regex(it, options).containsMatchIn(topAddress) } == true) continue
            val firstParty = top != null && top.scheme == url.scheme && top.host == url.host && top.port == url.port
            if (strings(t, "load-type")?.let { (if (firstParty) "first-party" else "third-party") !in it } == true) continue
            if (strings(t, "load-context")?.let { (if (mainFrame) "top-frame" else "child-frame") !in it } == true) continue
            val action = rule.getJSONObject("action")
            result = when (action.getString("type")) {
                "block" -> result.copy(block = true)
                "block-cookies" -> result.copy(blockCookies = true)
                "make-https" -> result.copy(makeHttps = true)
                "css-display-none" -> result.copy(selectors = result.selectors + action.getString("selector"))
                "ignore-previous-rules" -> Decision()
                else -> error("未知网页过滤动作")
            }
        }
        return result
    }
    /** WebResourceRequest exposes no resource kind; use Chromium headers and known file types. */
    fun resourceType(url: String, mainFrame: Boolean, headers: Map<String, String>): String {
        if (mainFrame) return "document"
        val destination = headers.entries.firstOrNull { it.key.equals("Sec-Fetch-Dest", true) }?.value
        when (destination) { "image" -> return "image"; "style" -> return "style-sheet"; "script" -> return "script"; "font" -> return "font"; "audio", "video", "track" -> return "media"; "document", "iframe", "frame" -> return "document" }
        val extension = url.toHttpUrlOrNull()?.encodedPath?.substringAfterLast('.')?.lowercase()
        return when (extension) {
            "jpg", "jpeg", "png", "gif", "webp", "avif", "ico", "bmp" -> "image"
            "svg" -> "svg-document"; "css" -> "style-sheet"; "js", "mjs" -> "script"
            "woff", "woff2", "ttf", "otf" -> "font"; "mp4", "webm", "mp3", "ogg", "wav", "m3u8" -> "media"
            "html", "htm" -> "document"
            else -> headers.entries.firstOrNull { it.key.equals("Accept", true) }?.value?.let { if (it.startsWith("image/")) "image" else "raw" } ?: "raw"
        }
    }
}
