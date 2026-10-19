package app.monogatari.android.sources

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceError
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import okhttp3.Cookie
import okhttp3.Request
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedQueue

/** Fetch credentials use an origin (scheme, host and port), including after redirects. */
internal fun browserCredentialsAllowed(mode:String,origin:String?,address:String):Boolean {
    if(mode=="omit")return false
    if(mode=="include")return true
    if(mode!="same-origin")return false
    val first=origin?.toHttpUrlOrNull()?:return false;val next=address.toHttpUrlOrNull()?:return false
    return first.scheme==next.scheme && first.host==next.host && first.port==next.port
}

/** Chromium supplies the same DOM, promise and JavaScript capabilities as WKWebView. */
class SourceBrowser(private val context: Context, private val sourceId: String, private val ephemeral: Boolean = false, transportClient:OkHttpClient?=null) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val profileSourceId = if (ephemeral) "$sourceId:${UUID.randomUUID()}" else sourceId
    internal val webProfileName get() = SourceWebProfiles.profileName(profileSourceId, ephemeral)
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    private data class Pending(val latch: CountDownLatch = CountDownLatch(1), var value: String? = null, var error: String? = null)
    private val pending = ConcurrentHashMap<String, Pending>()
    private var load = CountDownLatch(1)
    private var loadError: String? = null
    private val visitedUrls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var rules = SourceContentRules()
    @Volatile private var documentUrl = "about:blank"
    private val resourceKinds = ConcurrentHashMap<String, String>()
    private data class CapturedBody(val bytes: ByteArray, val contentType: String?, val headers: Map<String, String>, val method:String?=null,val credentials:String="include",val origin:String?=null,val time: Long = System.currentTimeMillis(),val token:String=UUID.randomUUID().toString())
    private val requestBodies = ConcurrentHashMap<String, CapturedBody>()
    private val forcedRequests = ConcurrentHashMap<String,CapturedBody>()
    private val bodyMonitor=java.lang.Object()
    private fun bodyKey(url: String, method: String) = "${method.uppercase()} ${url.substringBefore('#')}"
    private fun rememberBody(url: String, method: String, bytes: ByteArray, contentType: String?, headers: Map<String, String> = emptyMap(),credentials:String="same-origin",origin:String?=null): String {
        check(!closed.get()) { "Source browser closed" }
        require(bytes.size <= 50 * 1024 * 1024) { "Web request body is too large" }
        synchronized(bodyMonitor) {
            val key=bodyKey(url,method);val deadline=System.currentTimeMillis()+90_000
            // A request cannot leave Chromium until its body has been captured. Waiting here
            // prevents a later request to the same URL from overtaking the first interception.
            while(requestBodies[key]?.let { System.currentTimeMillis()-it.time<90_000 }==true) {
                val remaining=deadline-System.currentTimeMillis();check(remaining>0) { "Previous web request body was not consumed" };bodyMonitor.wait(remaining)
            }
            check(!closed.get()) { "Source browser closed" }
            requestBodies.entries.removeIf { System.currentTimeMillis()-it.value.time>=90_000 }
            require(requestBodies.size<128) { "Too many pending web request bodies" }
            require(credentials in setOf("omit","same-origin","include")) { "Invalid browser credentials mode" }
            val captured=CapturedBody(bytes,contentType,headers,credentials=credentials,origin=origin)
            requestBodies[key]=captured
            return captured.token
        }
    }
    private fun takeBody(url:String,method:String):CapturedBody?=synchronized(bodyMonitor) {
        requestBodies.remove(bodyKey(url,method)).also { bodyMonitor.notifyAll() }?.takeIf { System.currentTimeMillis()-it.time<90_000 }
    }
    private val transport = (transportClient?.newBuilder()?:OkHttpClient.Builder()).followRedirects(false).followSslRedirects(false).callTimeout(90, TimeUnit.SECONDS).build()
    private val view: WebView = onMain { create() }
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface") private fun create(): WebView = WebView(context).apply {
        SourceWebProfiles.configure(this, profileSourceId, ephemeral)
        settings.javaScriptEnabled = true; settings.domStorageEnabled = true
        settings.allowFileAccess = false; settings.allowContentAccess = false
        settings.javaScriptCanOpenWindowsAutomatically = false; settings.setSupportMultipleWindows(false)
        addJavascriptInterface(object {
            @JavascriptInterface fun complete(id: String, result: String, failed: Boolean) {
                pending[id]?.let { if (failed) it.error = result else it.value = result; it.latch.countDown() }
            }
            @JavascriptInterface fun css(url: String, topUrl: String): String = rules.decision(url, topUrl, "document", url == topUrl).selectors.joinToString("\n") { "$it { display:none !important; }" }
            @JavascriptInterface fun resource(url: String, kind: String) { resourceKinds[url] = kind }
            @JavascriptInterface fun needsBody(url: String, method: String, mainFrame: Boolean): Boolean {
                // Navigations use browser credentials automatically. Scripted GETs also
                // need their fetch/XHR credentials mode when content rules proxy them.
                if (mainFrame && method.uppercase() in setOf("GET", "HEAD")) return false
                val d = rules.decision(url, documentUrl, resourceKinds[url] ?: "raw", mainFrame)
                return !d.block && (d.blockCookies || d.makeHttps && url.startsWith("http:"))
            }
            @JavascriptInterface fun body(url: String, method: String, data: String, contentType: String, headers: String,credentials:String,origin:String) {
                require(data.length <= 70 * 1024 * 1024) { "Web request body is too large" }
                val values = JSONObject(headers)
                rememberBody(url, method, android.util.Base64.decode(data, android.util.Base64.DEFAULT), contentType.ifEmpty { null }, values.keys().asSequence().associateWith { values.getString(it) },credentials,origin)
            }
            @JavascriptInterface fun beacon(url:String,data:String,contentType:String,origin:String):String = try {
                require(data.length<=70*1024*1024) { "Web request body is too large" }
                JSONObject().put("token",rememberBody(url,"POST",android.util.Base64.decode(data,android.util.Base64.DEFAULT),contentType.ifEmpty { null },credentials="include",origin=origin)).toString()
            } catch(error:Exception) { JSONObject().put("error",error.message?:"Beacon body capture failed").toString() }
            @JavascriptInterface fun cancelBeacon(url:String,token:String) {
                synchronized(bodyMonitor) {
                    val key=bodyKey(url,"POST")
                    if(requestBodies[key]?.token==token) { requestBodies.remove(key);bodyMonitor.notifyAll() }
                }
            }
        }, "__monogatariBridge")
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) { url?.let { visitedUrls.add(it); documentUrl = it } }
            override fun onPageFinished(view: WebView, url: String?) { url?.let { visitedUrls.add(it) }; load.countDown() }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) { if (request.isForMainFrame) { loadError = error.description.toString(); load.countDown() } }
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                visitedUrls.add(url)
                val kind = resourceKinds[url] ?: rules.resourceType(url, request.isForMainFrame, request.requestHeaders)
                val decision = rules.decision(url, documentUrl, kind, request.isForMainFrame)
                val forced=if(request.isForMainFrame) forcedRequests.remove(url.substringBefore('#')) else null
                if (decision.block) return WebResourceResponse("text/plain", "utf-8", ByteArray(0).inputStream())
                return if (forced!=null || decision.blockCookies || decision.makeHttps && request.url.scheme == "http") filteredRequest(request, decision,forced) else null
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) WebViewCompat.addDocumentStartJavaScript(this, """
            (function(){
              function mark(url,kind){try{__monogatariBridge.resource(new URL(String(url),document.baseURI).href,kind)}catch(e){}}
              function b64(bytes){let result='';for(let i=0;i<bytes.length;i+=8192)result+=String.fromCharCode.apply(null,bytes.subarray(i,i+8192));return btoa(result)}
              function needs(url,method,mainFrame){return __monogatariBridge.needsBody(url,method,!!mainFrame)}
              async function capture(req,mainFrame){if(!needs(req.url,req.method,mainFrame))return;let bytes=new Uint8Array(await req.clone().arrayBuffer());let headers={};req.headers.forEach((v,k)=>headers[k]=v);__monogatariBridge.body(req.url,req.method,b64(bytes),req.headers.get('Content-Type')||'',JSON.stringify(headers),req.credentials,location.origin)}
              let originalFetch=window.fetch;window.fetch=function(input,init){let address=new URL(input&&input.url||String(input),document.baseURI).href,method=init&&init.method||input&&input.method||'GET';mark(address,'raw');if(!needs(address,method))return originalFetch.apply(this,arguments);let req=new Request(input,init),self=this;return capture(req).then(()=>originalFetch.call(self,req))};
              let open=XMLHttpRequest.prototype.open,send=XMLHttpRequest.prototype.send,setHeader=XMLHttpRequest.prototype.setRequestHeader;
              XMLHttpRequest.prototype.open=function(method,url,async){mark(url,'raw');this.__monogatariRequest={method:String(method).toUpperCase(),url:new URL(String(url),document.baseURI).href,async:async!==false,headers:{}};return open.apply(this,arguments)};
              XMLHttpRequest.prototype.setRequestHeader=function(name,value){let r=this.__monogatariRequest;if(r)r.headers[name]=r.headers[name]?r.headers[name]+', '+value:String(value);return setHeader.apply(this,arguments)};
              function syncBytes(body){if(body==null)return new Uint8Array();if(typeof body==='string')return new TextEncoder().encode(body);if(body instanceof URLSearchParams)return new TextEncoder().encode(body.toString());if(body instanceof ArrayBuffer)return new Uint8Array(body);if(ArrayBuffer.isView(body))return new Uint8Array(body.buffer,body.byteOffset,body.byteLength);if(body instanceof Blob){let xhr=new XMLHttpRequest(),url=URL.createObjectURL(body);try{open.call(xhr,'GET',url,false);xhr.overrideMimeType('text/plain; charset=x-user-defined');send.call(xhr);return Uint8Array.from(xhr.responseText,c=>c.charCodeAt(0)&255)}finally{URL.revokeObjectURL(url)}}throw new TypeError('Unsupported XMLHttpRequest body')}
              function beaconBytes(body,contentType){
                if(!(body instanceof FormData))return syncBytes(body==null?null:(typeof body==='string'||body instanceof URLSearchParams||body instanceof ArrayBuffer||ArrayBuffer.isView(body)||body instanceof Blob?body:String(body)));
                let match=/boundary=(?:"([^"]+)"|([^;]+))/i.exec(contentType);if(!match)throw new Error('Beacon multipart boundary is missing');let boundary=match[1]||match[2],parts=[],encode=s=>new TextEncoder().encode(s),normalize=s=>String(s).replace(/\r\n|\r|\n/g,'\r\n'),escape=s=>normalize(s).replace(/\r/g,'%0D').replace(/\n/g,'%0A').replace(/"/g,'%22');
                body.forEach((value,key)=>{parts.push(encode('--'+boundary+'\r\nContent-Disposition: form-data; name="'+escape(key)+'"'+(value instanceof Blob?'; filename="'+escape(value.name||'blob')+'"\r\nContent-Type: '+(value.type||'application/octet-stream'):'')+'\r\n\r\n'),value instanceof Blob?syncBytes(value):encode(normalize(value)),encode('\r\n'))});parts.push(encode('--'+boundary+'--\r\n'));let bytes=new Uint8Array(parts.reduce((n,p)=>n+p.length,0)),offset=0;parts.forEach(p=>{bytes.set(p,offset);offset+=p.length});return bytes;
              }
              let originalBeacon=navigator.sendBeacon;if(originalBeacon)navigator.sendBeacon=function(url,body){
                let address=new URL(String(url),document.baseURI).href;mark(address,'raw');if(!needs(address,'POST'))return originalBeacon.apply(this,arguments);
                // Keep the original native call and synchronous boolean quota decision.
                // Request extracts its MIME/boundary; synchronous bytes are saved first.
                let req=new Request(address,{method:'POST',body:body,credentials:'include',keepalive:true}),contentType=req.headers.get('Content-Type')||'',bytes=beaconBytes(body,contentType),saved=JSON.parse(__monogatariBridge.beacon(address,b64(bytes),contentType,location.origin));if(saved.error)throw new Error(saved.error);if(!saved.token)throw new Error('Beacon body capture returned no token');
                try{let queued=originalBeacon.apply(this,arguments);if(!queued)__monogatariBridge.cancelBeacon(address,saved.token);return queued}catch(error){__monogatariBridge.cancelBeacon(address,saved.token);throw error}
              };
              XMLHttpRequest.prototype.send=function(body){let r=this.__monogatariRequest;if(!r||!needs(r.url,r.method))return send.apply(this,arguments);let self=this;let req=new Request(r.url,{method:r.method,headers:r.headers,body:['GET','HEAD'].includes(r.method)?undefined:body,credentials:this.withCredentials?'include':'same-origin'});if(r.async){capture(req).then(()=>send.call(self,body)).catch(e=>{self.dispatchEvent(new Event('error'));self.dispatchEvent(new Event('loadend'))});return}
                let bytes,contentType=req.headers.get('Content-Type')||'';if(['GET','HEAD'].includes(r.method))bytes=new Uint8Array();else if(body instanceof FormData){let boundary='----Monogatari'+Math.random().toString(36).slice(2),parts=[];let encode=s=>new TextEncoder().encode(s),escape=s=>String(s).replace(/\r/g,'%0D').replace(/\n/g,'%0A').replace(/"/g,'%22');body.forEach((value,key)=>{parts.push(encode('--'+boundary+'\r\nContent-Disposition: form-data; name="'+escape(key)+'"'+(value instanceof Blob?'; filename="'+escape(value.name||'blob')+'"\r\nContent-Type: '+(value.type||'application/octet-stream'):'')+'\r\n\r\n'),syncBytes(value),encode('\r\n'))});parts.push(encode('--'+boundary+'--\r\n'));bytes=new Uint8Array(parts.reduce((n,p)=>n+p.length,0));let offset=0;parts.forEach(p=>{bytes.set(p,offset);offset+=p.length});contentType='multipart/form-data; boundary='+boundary}else bytes=syncBytes(body);__monogatariBridge.body(r.url,r.method,b64(bytes),contentType,JSON.stringify(r.headers),req.credentials,location.origin);return send.call(self,body)};
              let submit=HTMLFormElement.prototype.submit;
              async function submitForm(form,submitter){let url=submitter&&submitter.hasAttribute('formaction')?submitter.formAction:form.action;let method=(submitter&&submitter.hasAttribute('formmethod')?submitter.formMethod:form.method).toUpperCase();mark(url,'document');if(!needs(url,method,true)){submit.call(form);return}let data=new FormData(form,submitter||undefined),enctype=submitter&&submitter.hasAttribute('formenctype')?submitter.formEnctype:form.enctype,body=data;if(enctype==='application/x-www-form-urlencoded')body=new URLSearchParams(Array.from(data,([k,v])=>[k,typeof v==='string'?v:v.name]));else if(enctype==='text/plain')body=Array.from(data,([k,v])=>k+'='+ (typeof v==='string'?v:v.name)).join('\r\n')+'\r\n';let req=new Request(url,{method,body,credentials:'include',headers:enctype==='text/plain'?{'Content-Type':'text/plain'}:{}});await capture(req,true);let old={action:form.getAttribute('action'),method:form.getAttribute('method'),enctype:form.getAttribute('enctype'),target:form.getAttribute('target')};form.action=url;form.method=method;form.enctype=enctype;if(submitter&&submitter.hasAttribute('formtarget'))form.target=submitter.formTarget;try{submit.call(form)}finally{Object.keys(old).forEach(k=>old[k]==null?form.removeAttribute(k):form.setAttribute(k,old[k]))}}
              HTMLFormElement.prototype.submit=function(){let url=this.action,method=this.method.toUpperCase();if(!needs(url,method,true))return submit.apply(this,arguments);return submitForm(this,null)};
              window.addEventListener('submit',function(e){let form=e.target,button=e.submitter;if(e.defaultPrevented||!(form instanceof HTMLFormElement))return;let url=button&&button.hasAttribute('formaction')?button.formAction:form.action,method=(button&&button.hasAttribute('formmethod')?button.formMethod:form.method).toUpperCase();if(needs(url,method,true)){e.preventDefault();submitForm(form,button).catch(err=>console.error(err))}});
              [[HTMLImageElement,'src','image'],[HTMLScriptElement,'src','script'],[HTMLLinkElement,'href','style-sheet'],[HTMLIFrameElement,'src','document'],[HTMLMediaElement,'src','media']].forEach(function(pair){let p=pair[0].prototype,d=Object.getOwnPropertyDescriptor(p,pair[1]);if(d&&d.set)Object.defineProperty(p,pair[1],Object.assign({},d,{set:function(url){mark(url,pair[2]);return d.set.call(this,url)}}))});
              let set=Element.prototype.setAttribute;Element.prototype.setAttribute=function(name,value){if(name==='src'||name==='href'){let kinds={IMG:'image',SCRIPT:'script',LINK:'style-sheet',IFRAME:'document',VIDEO:'media',AUDIO:'media'};if(kinds[this.tagName])mark(value,kinds[this.tagName])}return set.apply(this,arguments)};
              let css=__monogatariBridge.css(location.href,window===window.top?location.href:document.referrer);if(css){let style=document.createElement('style');style.textContent=css;function apply(){(document.head||document.documentElement).appendChild(style)}if(document.documentElement)apply();else document.addEventListener('DOMContentLoaded',apply)}
            })();
        """.trimIndent(), setOf("*"))
        loadDataWithBaseURL(null, "<!doctype html><html><head></head><body></body></html>", "text/html", "UTF-8", null)
    }
    init { if (Looper.myLooper() != Looper.getMainLooper()) waitForLoad() }
    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: Result<T>? = null; val latch = CountDownLatch(1)
        handler.post { result = runCatching(block); latch.countDown() }
        check(latch.await(30, TimeUnit.SECONDS)) { "WebView main thread timed out" }; return result!!.getOrThrow()
    }
    fun load(request: Request) {
        load = CountDownLatch(1); loadError = null
        documentUrl = request.url.toString(); resourceKinds.clear()
        if(request.method!="GET") {
            val buffer = okio.Buffer(); request.body?.writeTo(buffer); val bytes = buffer.readByteArray()
            require(bytes.size<=50*1024*1024) { "Web request body is too large" }
            forcedRequests[request.url.toString().substringBefore('#')]=CapturedBody(bytes,request.body?.contentType()?.toString(),request.headers.toMap(),request.method)
        }
        onMain { view.loadUrl(request.url.toString(),request.headers.toMap()) }
    }
    fun loadHtml(html: String, url: String) { load = CountDownLatch(1); loadError = null; documentUrl = url; resourceKinds.clear(); onMain { view.loadDataWithBaseURL(url.ifEmpty { null }, html, "text/html", "utf-8", null) } }
    fun waitForLoad() { check(Looper.myLooper() != Looper.getMainLooper()) { "Source WebView must run off the main thread" }; check(load.await(90, TimeUnit.SECONDS)) { "WebView load timed out" }; loadError?.let { error(it) } }
    fun evaluate(script: String, asynchronous: Boolean = false): String {
        check(Looper.myLooper() != Looper.getMainLooper()) { "JavaScript execution must run off the main thread" }
        waitForLoad()
        val id = UUID.randomUUID().toString(); val task = Pending(); pending[id] = task
        try {
            val quoted = JSONObject.quote(script); val token = JSONObject.quote(id)
            val wrapper = if (asynchronous) "(async function(){try{__monogatariBridge.complete($token,String(await (0,eval)($quoted)),false)}catch(e){__monogatariBridge.complete($token,String(e&&e.message||e),true)}})();"
                else "(function(){try{__monogatariBridge.complete($token,String((0,eval)($quoted)),false)}catch(e){__monogatariBridge.complete($token,String(e&&e.message||e),true)}})();"
            onMain { view.evaluateJavascript(wrapper, null) }
            check(task.latch.await(90, TimeUnit.SECONDS)) { "JavaScript timed out" }
            task.error?.let { error(it) }; return task.value ?: "undefined"
        } finally { pending.remove(id) }
    }
    fun setRules(json: String) {
        val parsed = SourceContentRules(json)
        if (json.contains("css-display-none") || json.contains("block-cookies") || json.contains("make-https")) check(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) { "此设备的 Android System WebView 不支持页面加载前过滤，请更新 Android System WebView" }
        rules = parsed
    }
    private fun filteredRequest(request: WebResourceRequest, decision: SourceContentRules.Decision, forced:CapturedBody?=null): WebResourceResponse {
        fun failure(message: String) = WebResourceResponse("text/plain", "utf-8", 502, "Source browser request failed", emptyMap(), message.toByteArray().inputStream())
        val captured = forced ?: takeBody(request.url.toString(),request.method)
        if (forced==null && request.method !in setOf("GET", "HEAD") && captured == null) return failure("浏览器生成的请求没有可读取的请求体；请由网页 fetch、XMLHttpRequest、sendBeacon 或表单发起此过滤请求")
        return try {
            var address = request.url.toString().toHttpUrlOrNull() ?: return failure("无效请求地址")
            if (decision.makeHttps && address.scheme == "http") address = address.newBuilder().scheme("https").port(if (address.port == 80) 443 else address.port).build()
            var method = captured?.method ?: request.method
            var body = if(method in setOf("GET","HEAD")) null else captured?.bytes?.toRequestBody(captured.contentType?.toMediaTypeOrNull())
            var headers = (request.requestHeaders + captured?.headers.orEmpty()).filterKeys { !it.equals("Cookie", true) && !it.equals("Cookie2", true) && !it.equals("Content-Length", true) }
            captured?.contentType?.let { headers = headers.filterKeys { !it.equals("Content-Type", true) } + ("Content-Type" to it) }
            repeat(10) {
                val builder = Request.Builder().url(address).method(method, body)
                headers.forEach { (name, value) -> builder.header(name, value) }
                val cookiesAllowed=!decision.blockCookies && browserCredentialsAllowed(captured?.credentials?:"include",captured?.origin,address.toString())
                if (cookiesAllowed) {
                    val explicit=if(captured?.method!=null && browserCredentialsAllowed("same-origin",request.url.toString(),address.toString())) captured.headers.entries.firstOrNull { it.key.equals("Cookie",true) }?.value else null
                    (explicit?:onMain { SourceWebProfiles.manager(view).getCookie(address.toString()) })?.let { builder.header("Cookie",it) }
                }
                transport.newCall(builder.build()).execute().use { response ->
                    // Redirect responses can set cookies as well as the final response.
                    if(cookiesAllowed) onMain { response.headers.values("Set-Cookie").forEach { SourceWebProfiles.manager(view).setCookie(address.toString(),it) } }
                    if (response.code in 300..399 && response.header("Location") != null) {
                        val next = address.resolve(response.header("Location")!!) ?: return failure("无效重定向地址")
                        if (next.host != address.host || next.scheme != address.scheme || next.port != address.port) headers = headers.filterKeys { !it.equals("Authorization", true) }
                        if (response.code == 303 && method != "HEAD" || response.code in setOf(301, 302) && method == "POST") { method = "GET"; body = null; headers = headers.filterKeys { !it.equals("Content-Type", true) } }
                        address = next
                    } else {
                        val content = response.header("Content-Type").orEmpty()
                        val mime = content.substringBefore(';').ifBlank { "application/octet-stream" }
                        val encoding = Regex("charset=([^; ]+)", RegexOption.IGNORE_CASE).find(content)?.groupValues?.get(1)?.trim('"') ?: "utf-8"
                        val bytes = response.body?.byteStream()?.use { SourceHost.readBounded(it, 50 * 1024 * 1024) } ?: ByteArray(0)
                        return WebResourceResponse(mime, encoding, response.code, response.message.ifEmpty { "Response" }, response.headers.toMap().filterKeys { !it.equals("Set-Cookie", true) }, bytes.inputStream())
                    }
                }
            }; failure("网页重定向次数过多")
        } catch (e: Exception) { failure(e.message ?: "网页请求失败") }
    }
    fun addScript(script: String, atEnd: Boolean, mainFrameOnly: Boolean) { onMain {
        check(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) { "此设备的 Android System WebView 不支持页面加载前脚本，请更新 Android System WebView" }
        val source = if (atEnd) "document.addEventListener('DOMContentLoaded',function(){ $script });" else script
        WebViewCompat.addDocumentStartJavaScript(view, if (mainFrameOnly) "if(window===window.top){ $source }" else source, setOf("*"))
    } }
    fun cookies(): List<Cookie> = onMain { SourceWebProfiles.records(view, visitedUrls) }
    fun currentUrl(): String = onMain { view.url.orEmpty() }
    fun deleteCookie(name: String?, value: String?, domain: String?): Boolean {
        val complete = CountDownLatch(1); var success = false
        onMain {
            val manager = SourceWebProfiles.manager(view)
            if (name == null) { manager.removeAllCookies { success = true; manager.flush(); complete.countDown() }; view.clearCache(true); WebViewCompat.getProfile(view).webStorage.deleteAllData() }
            else { val cookie = SourceWebProfiles.records(view, visitedUrls).firstOrNull { it.name == name && (value == null || it.value == value) && (domain == null || it.domain == domain.trimStart('.')) }
                if (cookie == null) complete.countDown() else {
                    val address = "https://${cookie.domain}${cookie.path}"
                    // Chromium's cookie-info API does not expose hostOnly. Delete both cookie keys.
                    val expired = "${cookie.name}=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0; Path=${cookie.path}"
                    manager.setCookie(address, "$expired; Domain=${cookie.domain}") {
                        manager.setCookie(address, expired) { ok -> success = ok; manager.flush(); complete.countDown() }
                    }
                }
            }
        }
        check(complete.await(30, TimeUnit.SECONDS)) { "Cookie deletion timed out" }; return success
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(bodyMonitor) { requestBodies.clear(); bodyMonitor.notifyAll() }
        forcedRequests.clear()
        loadError = "Source browser closed"; load.countDown()
        pending.values.forEach { it.error = "Source browser closed"; it.latch.countDown() }
        val destroyed = CountDownLatch(1)
        onMain {
            try {
                view.stopLoading()
                view.removeJavascriptInterface("__monogatariBridge")
            } finally {
                if (ephemeral) SourceWebProfiles.clearAndDestroyTemporary(view, profileSourceId) { destroyed.countDown() }
                else try { view.destroy() } finally { destroyed.countDown() }
            }
        }
        if (Looper.myLooper() != Looper.getMainLooper()) check(destroyed.await(30, TimeUnit.SECONDS)) { "Temporary browser close timed out" }
    }
}
