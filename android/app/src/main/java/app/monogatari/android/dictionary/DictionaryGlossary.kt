package app.monogatari.android.dictionary

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLConnection

/** Yomitan structured content is rendered as data; executable HTML and remote file access are never accepted. */
object DictionaryGlossary {
    const val host = "dictionary.monogatari.invalid"
    internal class DocumentSession(val dictionaryId: String) {
        private data class Document(val url: String, val bytes: ByteArray)
        private val current = java.util.concurrent.atomic.AtomicReference<Document?>()
        fun bind(html: String): String {
            val bytes = html.toByteArray(Charsets.UTF_8); require(bytes.size <= 16 * 1024 * 1024)
            val url = origin(dictionaryId) + "?monogatariDocument=" + java.util.UUID.randomUUID()
            current.set(Document(url, bytes)); return url
        }
        fun matches(uri: Uri) = current.get()?.url == uri.toString()
        fun response(uri: Uri, mainFrame: Boolean, method: String): WebResourceResponse? {
            val document = current.get() ?: return null
            if (!mainFrame || method != "GET" || document.url != uri.toString()) return null
            return WebResourceResponse("text/html", "utf-8", 200, "OK", mapOf("Cache-Control" to "no-store", "Content-Security-Policy" to "default-src 'none'; img-src https://$host data:; media-src https://$host; style-src 'unsafe-inline' https://$host; font-src https://$host; script-src 'none'; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'"), ByteArrayInputStream(document.bytes))
        }
    }
    private val documents = java.util.Collections.synchronizedMap(java.util.WeakHashMap<WebView, DocumentSession>())
    fun loadDocument(view: WebView, dictionaryId: String, document: String) {
        val session = requireNotNull(documents[view]) { "Create this glossary view through its safe factory" }
        require(session.dictionaryId == dictionaryId) { "Glossary dictionary origin changed" }
        view.loadUrl(session.bind(document))
    }
    fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
    fun origin(dictionaryId: String): String { require(dictionaryId.matches(Regex("[A-Za-z0-9_-]{1,128}"))); return "https://$host/$dictionaryId/" }
    fun document(glossary: Any?, dictionaryId: String, css: String = "", textColor: String = "#222222", background: String = "transparent"): String {
        val base = origin(dictionaryId)
        val userCss = css.take(1024 * 1024).replace("<", "\\3c ").replace(">", "\\3e ")
        val content = content(glossary, base)
        return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src https://$host data:; media-src https://$host; style-src 'unsafe-inline' https://$host; font-src https://$host; script-src 'none'; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'"><style>body{margin:0;color:${escape(textColor)};background:${escape(background)};font:16px sans-serif;overflow-wrap:anywhere}img{max-width:100%;height:auto}table{border-collapse:collapse;max-width:100%}td,th{padding:4px;vertical-align:top}ul,ol{padding-left:20px}$userCss</style></head><body><div class="yomitan-glossary">$content</div></body></html>"""
    }
    private val tags = setOf("ruby", "rt", "rp", "div", "span", "br", "strong", "b", "em", "i", "u", "s", "small", "sub", "sup", "table", "thead", "tbody", "tfoot", "tr", "td", "th", "ol", "ul", "li", "p", "a", "details", "summary")
    private val styles = listOf("fontStyle", "fontWeight", "fontSize", "color", "background", "backgroundColor", "textDecorationLine", "textDecorationStyle", "textDecorationColor", "borderColor", "borderStyle", "borderRadius", "borderWidth", "clipPath", "verticalAlign", "textAlign", "textEmphasis", "textShadow", "margin", "marginTop", "marginLeft", "marginRight", "marginBottom", "padding", "paddingTop", "paddingLeft", "paddingRight", "paddingBottom", "wordBreak", "whiteSpace", "cursor", "listStyleType").associateWith { it.replace(Regex("[A-Z]")) { letter -> "-" + letter.value.lowercase() } }
    fun content(value: Any?, base: String, depth: Int = 0): String {
        require(depth <= 64) { "Glossary nesting exceeds the limit" }
        return when (value) {
            null, JSONObject.NULL -> ""
            is JSONArray -> (0 until value.length()).joinToString("") { content(value.opt(it), base, depth + 1) }
            is JSONObject -> when (if (value.optString("tag") == "img") "image" else value.optString("type")) {
                "image", "audio" -> {
                    val path = value.optString("path"); val uri = mediaUri(base, path)
                    if (uri == null) escape(value.optString("description")) else if (value.optString("type") == "audio") "<audio controls preload=\"none\" src=\"${escape(uri)}\"></audio>" else {
                        val units = value.optString("sizeUnits", "px").takeIf { it in setOf("px", "em", "rem", "%") } ?: "px"
                        val dimensions = listOf("width", "height").mapNotNull { key -> (value.opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() && it > 0 && it <= 10000 }?.let { "$key:${it}$units" } }.joinToString(";")
                        val imageStyles = buildString { append(dimensions); value.optString("verticalAlign").takeIf { it in setOf("baseline", "sub", "super", "text-top", "text-bottom", "middle", "top", "bottom") }?.let { append(";vertical-align:$it") }; append(";image-rendering:"); append(value.optString("imageRendering").takeIf { it in setOf("auto", "pixelated", "crisp-edges") } ?: if (value.optBoolean("pixelated")) "pixelated" else "auto"); listOf("border", "borderRadius").forEach { key -> value.optString(key).takeIf { it.isNotBlank() }?.let { append(';'); append(if (key == "border") "border:" else "border-radius:"); append(it.replace(Regex("[;{}<>]"), "")) } } }
                        val image = "<img class=\"gloss-image\" src=\"${escape(uri)}\" alt=\"${escape(value.optString("alt", value.optString("description")))}\" title=\"${escape(value.optString("title"))}\" style=\"${escape(imageStyles + if (value.optString("appearance") == "monochrome") ";opacity:0" else "")}\">"
                        val display = if (value.optString("appearance") == "monochrome") "<span style=\"display:inline-block;background-color:currentColor;mask-image:url('${escape(uri)}');-webkit-mask-image:url('${escape(uri)}');mask-size:contain;-webkit-mask-size:contain;mask-repeat:no-repeat;-webkit-mask-repeat:no-repeat\">$image</span>" else image
                        if (value.optBoolean("collapsible") || value.optBoolean("collapsed")) "<details${if (!value.optBoolean("collapsed")) " open" else ""}><summary>${escape(value.optString("description", "▧"))}</summary>$display</details>" else display
                    }
                }
                "structured-content" -> content(value.opt("content"), base, depth + 1)
                else -> {
                    val tag = value.optString("tag").takeIf { it in tags }
                    if (tag == null) content(value.opt("content") ?: value.opt("text") ?: value.opt("value"), base, depth + 1) else {
                        val attributes = buildString {
                            append(" class=\"gloss-sc-$tag\"")
                            value.optString("lang").takeIf { it.matches(Regex("[A-Za-z0-9-]{1,35}")) }?.let { append(" lang=\"${escape(it)}\"") }
                            val style = value.optJSONObject("style")
                            style?.let { append(" style=\""); it.keys().asSequence().filter { key -> key in styles }.forEach { key -> append(styles.getValue(key)); append(':'); val raw = it.opt(key); val text = if (raw is JSONArray) (0 until raw.length()).joinToString(" ") { i -> raw.optString(i) } else raw.toString() + if (raw is Number && key.startsWith("margin")) "em" else ""; append(escape(text.take(512).replace(Regex("[;{}<>]"), ""))); append(';') }; append('"') }
                            value.optString("title").takeIf { it.isNotBlank() }?.let { append(" title=\"${escape(it)}\"") }
                            if (tag == "details" && value.optBoolean("open")) append(" open")
                            if (tag == "a") link(base, value.optString("href"))?.let { append(" href=\"${escape(it)}\" rel=\"noreferrer noopener\"") }
                            value.optJSONObject("data")?.let { data -> data.keys().asSequence().filter { it.matches(Regex("[A-Za-z][A-Za-z0-9_-]{0,80}")) }.forEach { append(" data-sc-${it.lowercase()}=\"${escape(data.opt(it).toString().take(1000))}\"") } }
                            listOf("colSpan", "rowSpan").forEach { key -> (value.opt(key) as? Number)?.toInt()?.takeIf { it in 1..1000 }?.let { append(" ${key.lowercase()}=\"$it\"") } }
                        }
                        if (tag == "br") "<br>" else "<$tag$attributes>${content(value.opt("content"), base, depth + 1)}</$tag>"
                    }
                }
            }
            else -> escape(value.toString()).replace("\n", "<br>")
        }
    }
    private fun mediaUri(base: String, path: String): String? {
        if (path.isBlank() || path.startsWith('/') || path.contains('\\') || path.split('/').any { it == "." || it == ".." } || runCatching { java.net.URI(path).isAbsolute }.getOrDefault(true)) return null
        return base + Uri.encode(path, "/")
    }
    fun link(base: String, target: String): String? = when {
        target.startsWith('?') -> base + target
        else -> runCatching { java.net.URI(target) }.getOrNull()?.takeIf { it.scheme in setOf("http", "https") && !it.host.isNullOrBlank() && it.userInfo == null }?.toString()
    }
    fun dispatchLink(dictionaryId: String, uri: Uri, userGesture: Boolean, mainFrame: Boolean, lookup: (String) -> Unit, external: (Uri) -> Unit): Boolean {
        if (!userGesture || !mainFrame) return false
        if (uri.scheme == "https" && uri.host == host && uri.path == "/$dictionaryId/") { val query = uri.getQueryParameter("query"); if (!query.isNullOrBlank()) { lookup(query.take(256)); return true }; return false }
        if (link(origin(dictionaryId), uri.toString()) != null && uri.host != host) { external(uri); return true }; return false
    }
    fun response(context: Context, dictionaryId: String, uri: Uri): WebResourceResponse {
        fun denied() = WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))
        if (uri.scheme != "https" || uri.host != host || uri.encodedAuthority.orEmpty().contains('@') || uri.port !in listOf(-1, 443) || uri.query != null) return denied()
        if (!dictionaryId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) return denied()
        val path = uri.path.orEmpty(); val prefix = "/$dictionaryId/"; if (!path.startsWith(prefix)) return denied()
        val relative = path.removePrefix(prefix); if (relative.isBlank() || relative.contains('\\') || relative.split('/').any { it == "." || it == ".." }) return denied()
        val dictionaries = File(context.filesDir, "dictionaries").canonicalFile; if (!dictionaries.toPath().startsWith(context.filesDir.canonicalFile.toPath())) return denied()
        val root = File(dictionaries, dictionaryId).canonicalFile; if (!root.toPath().startsWith(dictionaries.toPath()) || root == dictionaries) return denied()
        val file = File(root, relative).canonicalFile
        if (!file.isFile || !file.toPath().startsWith(root.toPath()) || file.length() > 32L * 1024 * 1024) return denied()
        return WebResourceResponse(URLConnection.guessContentTypeFromName(file.name) ?: when (file.extension.lowercase()) { "css" -> "text/css"; "svg" -> "image/svg+xml"; "woff" -> "font/woff"; "woff2" -> "font/woff2"; else -> "application/octet-stream" }, "utf-8", 200, "OK", mapOf("Content-Security-Policy" to "default-src 'none'; script-src 'none'", "X-Content-Type-Options" to "nosniff"), file.inputStream())
    }
    fun css(context: Context, dictionaryId: String): String {
        require(dictionaryId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        val dictionaries = File(context.filesDir, "dictionaries").canonicalFile; require(dictionaries.toPath().startsWith(context.filesDir.canonicalFile.toPath()))
        val root = File(dictionaries, dictionaryId).canonicalFile; require(root.toPath().startsWith(dictionaries.toPath()) && root != dictionaries); val file = File(root, "styles.css").canonicalFile
        require(file.toPath().startsWith(root.toPath())); return if (file.isFile && file.length() <= 1024 * 1024) file.readText() else ""
    }
    fun rich(json: String): Boolean {
        fun contains(value: Any?): Boolean = when (value) { is JSONObject -> value.optString("type") in setOf("image", "audio", "structured-content") || value.has("tag") || value.keys().asSequence().any { contains(value.opt(it)) }; is JSONArray -> (0 until value.length()).any { contains(value.opt(it)) }; else -> false }
        return runCatching { contains(org.json.JSONTokener(json).nextValue()) }.getOrDefault(false)
    }
    fun createView(context: Context, dictionaryId: String, heightChanged: (Int) -> Unit = {}, lookup: (String) -> Unit = {}, external: (Uri) -> Unit = { uri -> runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }, diagnostics: ((String) -> Unit)? = null): WebView = object : WebView(context) {
        private var touchX = 0f
        private var touchY = 0f
        private var reportedHeight = 0
        private var heightReportGeneration = 0L
        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            // Older providers finish navigation before their first content layout. Observe actual
            // painted geometry, including later image/details reflow, rather than fixing height at 0.
            if (contentHeight <= 0 || scale <= 0) return
            val measured = (contentHeight * scale / context.resources.displayMetrics.density).toInt().coerceIn(64, 320)
            if (measured == reportedHeight) return
            reportedHeight = measured
            val generation = ++heightReportGeneration
            post { if (generation == heightReportGeneration && isAttachedToWindow) heightChanged(measured) }
        }
        override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
            // AndroidView forwards this request to Compose's pointer interop. A bounded glossary
            // owns a drag while its document can scroll; at an edge the outer result list resumes.
            val keepGesture = when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    touchX = event.x; touchY = event.y
                    canScrollVertically(-1) || canScrollVertically(1) || canScrollHorizontally(-1) || canScrollHorizontally(1)
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = touchX - event.x; val dy = touchY - event.y
                    touchX = event.x; touchY = event.y
                    when {
                        kotlin.math.abs(dx) < 1 && kotlin.math.abs(dy) < 1 -> null
                        kotlin.math.abs(dy) >= kotlin.math.abs(dx) -> canScrollVertically(if (dy > 0) 1 else -1)
                        else -> canScrollHorizontally(if (dx > 0) 1 else -1)
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> false
                else -> null
            }
            val handled = super.dispatchTouchEvent(event)
            keepGesture?.let { parent?.requestDisallowInterceptTouchEvent(it) }
            return handled
        }
    }.apply {
        origin(dictionaryId)
        val document = DocumentSession(dictionaryId); documents[this] = document
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        settings.javaScriptEnabled = false; settings.domStorageEnabled = false; settings.allowFileAccess = false; settings.allowContentAccess = false
        settings.allowFileAccessFromFileURLs = false; settings.allowUniversalAccessFromFileURLs = false; settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean { if (request.isForMainFrame && document.matches(request.url)) return false; dispatchLink(dictionaryId, request.url, request.hasGesture(), request.isForMainFrame, lookup, external); return true }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse = (document.response(request.url, request.isForMainFrame, request.method) ?: response(context, dictionaryId, request.url)).also { diagnostics?.invoke("request=${request.url} mainFrame=${request.isForMainFrame} status=${it.statusCode} mime=${it.mimeType}") }
            override fun onPageFinished(view: WebView, url: String) {
                diagnostics?.invoke("finished=$url view=${view.width}x${view.height} contentHeight=${view.contentHeight} scale=${view.scale}")
                view.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) { view.postInvalidateOnAnimation() }
                })
            }
        }
    }
}
