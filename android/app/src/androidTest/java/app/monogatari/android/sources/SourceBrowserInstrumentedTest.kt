package app.monogatari.android.sources

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

@RunWith(AndroidJUnit4::class)
class SourceBrowserInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun verifyTemporaryProfileAcrossColdProcess() {
        if (!androidx.webkit.WebViewFeature.isStartupFeatureSupported(context, androidx.webkit.WebViewFeature.STARTUP_FEATURE_SET_DATA_DIRECTORY_SUFFIX)) {
            android.util.Log.i("AidokuSourceFixture", "This provider lacks the public data-directory suffix startup feature; ordinary source-browser assertions still ran")
            return
        }
        fun phase(command: Int): Pair<Int, JSONObject> {
            val connected = java.util.concurrent.CountDownLatch(1)
            val died = java.util.concurrent.CountDownLatch(1)
            val responses = LinkedBlockingQueue<android.os.Bundle>()
            var remote: android.os.Messenger? = null
            val connection = object : android.content.ServiceConnection {
                override fun onServiceConnected(name: android.content.ComponentName, binder: android.os.IBinder) {
                    binder.linkToDeath({ died.countDown() }, 0)
                    remote = android.os.Messenger(binder); connected.countDown()
                }
                override fun onServiceDisconnected(name: android.content.ComponentName) {}
            }
            val reply = android.os.Messenger(android.os.Handler(android.os.Looper.getMainLooper()) { message ->
                responses.offer(android.os.Bundle(message.data)); true
            })
            val intent = android.content.Intent().setClassName(context.packageName, "app.monogatari.android.sources.ProfileRecoveryFixtureService")
            assertTrue(context.bindService(intent, connection, android.content.Context.BIND_AUTO_CREATE))
            var stopped = false
            val response: android.os.Bundle
            try {
                assertTrue("Profile fixture service did not bind", connected.await(30, TimeUnit.SECONDS))
                remote!!.send(android.os.Message.obtain().apply { what = command; replyTo = reply })
                response = responses.poll(60, TimeUnit.SECONDS) ?: error("Profile fixture response timed out")
                response.getString("error")?.let { error(it) }
                remote!!.send(android.os.Message.obtain().apply { what = 3; replyTo = reply })
                check(responses.poll(10, TimeUnit.SECONDS) != null)
                context.unbindService(connection); stopped = true
                assertTrue("Fixture provider process must actually terminate", died.await(15, TimeUnit.SECONDS))
            } finally { if (!stopped) context.unbindService(connection) }
            return response.getInt("pid") to JSONObject(response.getString("result")!!)
        }
        val seeded = phase(1)
        assertTrue(seeded.second.getBoolean("cookiesCleared")); assertTrue(seeded.second.getBoolean("storageCleared"))
        assertTrue("Fixture must commit real provider metadata before terminating its process", seeded.second.getBoolean("nativePersisted"))
        assertTrue(seeded.second.getBoolean("retained"))
        val recovered = phase(2)
        assertNotEquals("Recovery must use a fresh provider process", seeded.first, recovered.first)
        assertTrue(recovered.second.getBoolean("reclaimed"))
        assertTrue(recovered.second.getBoolean("presentBefore"))
        assertTrue(recovered.second.getBoolean("nativeDeletionPersisted"))
        assertTrue(recovered.second.getBoolean("directoryRemoved"))
    }
    @Test fun chromiumExecutesSyncPromiseDomAndDocumentStartScript() {
        SourceBrowser(context, "instrumentation:browser").use { browser ->
            assertEquals("2", browser.evaluate("1+1"))
            assertEquals("async test", browser.evaluate("new Promise(resolve=>resolve('async test'))", true))
            assertEquals("1000000000000000000001", browser.evaluate("({deep:{value:1000000000000000000000n}})?.deep?.value + 1n"))
            assertEquals("async:modern", browser.evaluate("(async()=>{let value=await Promise.resolve({result:'modern'}); return 'async:'+(value?.result??'missing')})()", true))
            browser.addScript("window.injected='before-inline';", false, false)
            browser.loadHtml("<!doctype html><html><body><script>window.captured=window.injected;document.cookie='testCookie=one; Path=/; SameSite=Lax';</script></body></html>", "https://source-test.aidoku.local/")
            browser.waitForLoad()
            assertEquals("before-inline", browser.evaluate("window.captured"))
            assertEquals("one", browser.cookies().first { it.name == "testCookie" }.value)
            assertTrue(browser.deleteCookie("testCookie", "one", "source-test.aidoku.local"))
            assertFalse(browser.cookies().any { it.name == "testCookie" })
        }
    }
    @Test fun webViewCookiesAreIsolatedPerSourceAndRuleListsBlockRequests() {
        SourceBrowser(context, "instrumentation:isolated-one").use { one ->
            one.loadHtml("<html><body><script>document.cookie='isolated=private; Path=/';</script></body></html>", "https://same-domain.aidoku.local/"); one.waitForLoad()
            SourceBrowser(context, "instrumentation:isolated-two").use { two ->
                two.setRules("[{\"trigger\":{\"url-filter\":\".*blocked-image.*\",\"resource-type\":[\"image\"]},\"action\":{\"type\":\"block\"}}]")
                two.loadHtml("<html><body><script>window.result='pending';let img=new Image();img.onerror=()=>window.result='blocked';img.onload=()=>window.result='loaded';img.src='https://same-domain.aidoku.local/blocked-image.png';document.body.appendChild(img)</script></body></html>", "https://same-domain.aidoku.local/"); two.waitForLoad()
                assertFalse(two.cookies().any { it.name == "isolated" })
                assertEquals("blocked", two.evaluate("window.result"))
            }
            val temporary = SourceBrowser(context, "instrumentation:isolated-one", ephemeral = true)
            val temporaryName = temporary.webProfileName
            try {
                temporary.loadHtml("<html><body><script>document.cookie='temporary=secret; Path=/';</script></body></html>", "https://same-domain.aidoku.local/")
                temporary.waitForLoad()
                assertFalse(temporary.cookies().any { it.name == "isolated" })
                assertFalse(one.cookies().any { it.name == "temporary" })
            } finally { temporary.close(); temporary.close() }
            var retainedCookies: String? = null
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                retainedCookies = androidx.webkit.ProfileStore.getInstance().getProfile(temporaryName)?.cookieManager?.getCookie("https://same-domain.aidoku.local/")
            }
            assertTrue("Closed temporary profile must immediately lose cookies even if this provider retains the loaded profile", retainedCookies.isNullOrBlank())
            assertTrue(one.cookies().any { it.name == "isolated" })
            verifyTemporaryProfileAcrossColdProcess()
        }
    }
    @Test fun originalWasmFixtureRunsOnActualAndroidRuntime() {
        val directory = File(context.cacheDir, "source-instrumentation-fixture").apply { mkdirs() }
        for (file in listOf("source.json", "main.wasm")) InstrumentationRegistry.getInstrumentation().context.assets.open("source-fixture/$file").use { File(directory, file).writeBytes(it.readBytes()) }
        val source = InstalledSource(directory, JSONObject(File(directory, "source.json").readText()))
        WasmSourceRuntime(context, source, OkHttpClient()).use { assertTrue(it.home().has("components")) }
    }
    @Test fun independentLegacyAbiRunsNativeMathJsonMangaChapterAndPageImports() {
        val directory = File(context.cacheDir, "legacy-instrumentation-fixture").apply { mkdirs() }
        for(file in listOf("source.json","main.wasm")) InstrumentationRegistry.getInstrumentation().context.assets.open("legacy-fixture/$file").use { File(directory,file).writeBytes(it.readBytes()) }
        WasmSourceRuntime(context,InstalledSource(directory,JSONObject(File(directory,"source.json").readText())),OkHttpClient()).use { runtime ->
            assertTrue(runtime.legacy);assertEquals(43,(runtime.preferences.get("legacy.initialized") as Number).toInt());val result=runtime.search("legacy",1);assertEquals("Legacy 漫画",result.first.single().title)
            val chapter=runtime.details(result.first.single()).chapters.single();assertEquals(7.0,chapter.number,0.0);val pages=runtime.pages(chapter);assertEquals(2,pages.size);assertEquals("Legacy text page",File(android.net.Uri.parse(pages[1]).path!!).readText())
        }
    }
    /** A real socket server verifies what leaves Chromium rather than inspecting JS wrapper state. */
    private class BrowserServer(ssl:javax.net.ssl.SSLContext?=null,private val cookieByPath:Boolean=false) : AutoCloseable {
        data class Incoming(val path: String, val headers: Map<String,String>, val body: ByteArray)
        val requests = LinkedBlockingQueue<Incoming>()
        private val socket = ssl?.serverSocketFactory?.createServerSocket(0)?:ServerSocket(0)
        val base = "http://127.0.0.1:${socket.localPort}/"
        private val workers = Executors.newCachedThreadPool()
        init { workers.execute { while (!socket.isClosed) try { val client=socket.accept(); workers.execute { runCatching { handle(client) } } } catch (_: Exception) {} } }
        private fun handle(client: Socket) = client.use { s ->
            s.soTimeout=15000; val input=s.getInputStream()
            fun line():String { val output=java.io.ByteArrayOutputStream(); while(true){val n=input.read();if(n<0||n==10)break;if(n!=13)output.write(n)};return output.toString("UTF-8") }
            val first=line(); if(first.isEmpty())return@use
            val headers=mutableMapOf<String,String>();while(true){val next=line();if(next.isEmpty())break;val pair=next.split(':',limit=2);if(pair.size==2)headers[pair[0].lowercase()]=pair[1].trim()}
            val size=headers["content-length"]?.toIntOrNull()?:0;val body=ByteArray(size);var position=0;while(position<size){val n=input.read(body,position,size-position);if(n<0)break;position+=n}
            requests.put(Incoming(first.split(' ')[1],headers,body))
            val path=first.split(' ')[1];val payload="OK".toByteArray();val cookieHeader=if(path.startsWith("/filtered-")) "Set-Cookie: responseCookie=${if(cookieByPath)path.substringAfterLast('/') else "blocked"}; Path=/\r\n" else "";s.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${payload.size}\r\n${cookieHeader}Connection: close\r\n\r\n").toByteArray()+payload)
        }
        fun take(path: String):Incoming { repeat(12){val request=requests.poll(10,TimeUnit.SECONDS)?:error("No browser request for $path");if(request.path==path)return request};error("Browser did not request $path") }
        override fun close(){socket.close();workers.shutdownNow()}
    }
    @Test fun contentRulesForwardFetchXhrAndFormPostBodiesWithoutCookies() {
        BrowserServer().use { server -> SourceBrowser(context, "instrumentation:post-filter:${java.util.UUID.randomUUID()}", ephemeral=true).use { browser ->
            browser.setRules("""[{"trigger":{"url-filter":".*filtered.*"},"action":{"type":"block-cookies"}}]""")
            browser.loadHtml("""<html><body><script>document.cookie='private=secret; Path=/';</script><form id='form' method='post' action='${server.base}filtered-form'><input name='title' value='中文 + test'><button id='submit' name='button' value='yes'>submit</button></form></body></html>""", server.base)
            browser.waitForLoad()
            assertEquals("OK",browser.evaluate("fetch('${server.base}filtered-fetch',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({title:'中文 + test'})}).then(r=>r.text())",true))
            val fetch=server.take("/filtered-fetch");assertNull(fetch.headers["cookie"]);assertEquals("中文 + test",JSONObject(fetch.body.toString(Charsets.UTF_8)).getString("title"));assertTrue(fetch.headers["content-type"]!!.startsWith("application/json"))
            assertEquals("OK,OK",browser.evaluate("Promise.all(['first','second'].map(value=>fetch('${server.base}filtered-concurrent',{method:'POST',body:value}).then(r=>r.text()))).then(values=>values.join(','))",true))
            val bodies=setOf(server.take("/filtered-concurrent").body.toString(Charsets.UTF_8),server.take("/filtered-concurrent").body.toString(Charsets.UTF_8));assertEquals(setOf("first","second"),bodies)
            assertEquals("OK",browser.evaluate("new Promise((resolve,reject)=>{let xhr=new XMLHttpRequest();xhr.open('POST','${server.base}filtered-xhr');xhr.onload=()=>resolve(xhr.responseText);xhr.onerror=()=>reject('xhr error');let data=new FormData();data.append('title','中文 + test');data.append('file',new Blob(['binary test']),'test.txt');xhr.send(data)})",true))
            val xhr=server.take("/filtered-xhr");assertNull(xhr.headers["cookie"]);assertTrue(xhr.headers["content-type"]!!.startsWith("multipart/form-data; boundary="));assertTrue(xhr.body.toString(Charsets.UTF_8).contains("binary test"));assertTrue(xhr.body.toString(Charsets.UTF_8).contains("中文 + test"))
            browser.evaluate("document.getElementById('form').requestSubmit(document.getElementById('submit')); 'submitted'")
            val form=server.take("/filtered-form");assertNull(form.headers["cookie"]);val decoded=java.net.URLDecoder.decode(form.body.toString(Charsets.UTF_8),"UTF-8");assertTrue(decoded.contains("title=中文 + test"));assertTrue(decoded.contains("button=yes"))
            assertFalse(browser.cookies().any { it.name == "responseCookie" })
            browser.load(okhttp3.Request.Builder().url(server.base+"source-post").header("X-Source-Test","custom-header").header("Cookie","explicit-source=one").post("source JSON".toByteArray().toRequestBody("application/json".toMediaType())).build());browser.waitForLoad()
            val sourcePost=server.take("/source-post");assertEquals("source JSON",sourcePost.body.toString(Charsets.UTF_8));assertEquals("custom-header",sourcePost.headers["x-source-test"]);assertEquals("explicit-source=one",sourcePost.headers["cookie"]);assertTrue(sourcePost.headers["content-type"]!!.startsWith("application/json"))
        } }
    }
    @Test fun upgradedFetchAndXhrPreserveCredentialsMode() {
        // This certificate is confined to this fixture's OkHttp transport. Production
        // trust and Android WebView SSL handling retain their normal certificate checks.
        val store=java.security.KeyStore.getInstance("PKCS12")
        InstrumentationRegistry.getInstrumentation().context.assets.open("browser-test.p12").use { store.load(it,"testpassword".toCharArray()) }
        val keys=javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()).apply { init(store,"testpassword".toCharArray()) }
        val trusts=javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        val trust=trusts.trustManagers.filterIsInstance<javax.net.ssl.X509TrustManager>().single()
        val ssl=javax.net.ssl.SSLContext.getInstance("TLS").apply { init(keys.keyManagers,arrayOf(trust),java.security.SecureRandom()) }
        val client=OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory,trust).build()
        BrowserServer(ssl,cookieByPath=true).use { server -> SourceBrowser(context,"instrumentation:credentials:${java.util.UUID.randomUUID()}",ephemeral=true,transportClient=client).use { browser ->
            browser.setRules("""[{"trigger":{"url-filter":".*filtered-.*"},"action":{"type":"make-https"}}]""")
            browser.loadHtml("<html><body><script>document.cookie='private=secret; Path=/';</script></body></html>",server.base);browser.waitForLoad()
            fun fetch(path:String,credentials:String):BrowserServer.Incoming {
                assertEquals("OK",browser.evaluate("fetch('${server.base}$path',{credentials:'$credentials'}).then(r=>r.text())",true));return server.take("/$path")
            }
            assertNull(fetch("filtered-same-origin","same-origin").headers["cookie"])
            assertFalse(browser.cookies().any { it.name=="responseCookie" })
            assertNull(fetch("filtered-omit","omit").headers["cookie"])
            assertFalse(browser.cookies().any { it.name=="responseCookie" })
            assertTrue(fetch("filtered-include","include").headers["cookie"].orEmpty().contains("private=secret"))
            assertEquals("filtered-include",browser.cookies().single { it.name=="responseCookie" }.value)
            assertNull(fetch("filtered-omit-after-include","omit").headers["cookie"])
            assertEquals("filtered-include",browser.cookies().single { it.name=="responseCookie" }.value)
            fun xhr(path:String,include:Boolean):BrowserServer.Incoming {
                assertEquals("OK",browser.evaluate("new Promise((resolve,reject)=>{let x=new XMLHttpRequest();x.open('GET','${server.base}$path');x.withCredentials=$include;x.onload=()=>resolve(x.responseText);x.onerror=()=>reject('xhr failed');x.send()})",true));return server.take("/$path")
            }
            assertNull(xhr("filtered-xhr-same-origin",false).headers["cookie"])
            assertEquals("filtered-include",browser.cookies().single { it.name=="responseCookie" }.value)
            assertTrue(xhr("filtered-xhr-include",true).headers["cookie"].orEmpty().contains("private=secret"))
            assertEquals("filtered-xhr-include",browser.cookies().single { it.name=="responseCookie" }.value)
        } }
    }
    @Test fun beaconQueuesNativeBooleanAndForwardsBodyInitBytesWithoutCookies() {
        BrowserServer().use { server -> SourceBrowser(context,"instrumentation:beacon:${java.util.UUID.randomUUID()}",ephemeral=true).use { browser ->
            browser.setRules("""[{"trigger":{"url-filter":".*filtered-beacon.*"},"action":{"type":"block-cookies"}}]""")
            browser.loadHtml("<html><body><script>document.cookie='private=secret; Path=/';</script></body></html>",server.base);browser.waitForLoad()
            fun beacon(path:String,body:String):BrowserServer.Incoming {
                assertEquals("true",browser.evaluate("navigator.sendBeacon('${server.base}$path',$body)"))
                return server.take("/$path").also { assertNull(it.headers["cookie"]) }
            }
            val text=beacon("filtered-beacon-string","'中文 + 😀\\n下一行'")
            assertArrayEquals("中文 + 😀\n下一行".toByteArray(Charsets.UTF_8),text.body);assertTrue(text.headers["content-type"].orEmpty().startsWith("text/plain"))
            val parameters=beacon("filtered-beacon-params","new URLSearchParams([['title','中文 + 😀'],['line','first\\nsecond']])")
            assertTrue(parameters.headers["content-type"].orEmpty().startsWith("application/x-www-form-urlencoded"));assertEquals("title=中文 + 😀&line=first\nsecond",java.net.URLDecoder.decode(parameters.body.toString(Charsets.UTF_8),"UTF-8"))
            val blob=beacon("filtered-beacon-blob","new Blob([new Uint8Array([0,128,255]),'汉字'],{type:'application/octet-stream'})")
            assertArrayEquals(byteArrayOf(0,128.toByte(),255.toByte())+"汉字".toByteArray(Charsets.UTF_8),blob.body);assertEquals("application/octet-stream",blob.headers["content-type"])
            assertArrayEquals(byteArrayOf(0,128.toByte(),255.toByte()),beacon("filtered-beacon-view","new DataView(new Uint8Array([9,0,128,255,9]).buffer,1,3)").body)
            assertArrayEquals(byteArrayOf(0,128.toByte(),255.toByte()),beacon("filtered-beacon-buffer","new Uint8Array([0,128,255]).buffer").body)
            val form=beacon("filtered-beacon-form","(()=>{let f=new FormData();f.append('title','中文 + 😀');f.append('lines','第一行\\n第二行');f.append('file',new Blob([new Uint8Array([0,128,255])],{type:'application/octet-stream'}),'测试.bin');return f})()")
            val multipart=form.body.toString(Charsets.UTF_8);val boundary=form.headers.getValue("content-type").substringAfter("boundary=").trim('"')
            assertTrue(multipart.startsWith("--$boundary\r\n"));assertTrue(multipart.endsWith("--$boundary--\r\n"));assertTrue(multipart.contains("中文 + 😀"));assertTrue(multipart.contains("第一行\r\n第二行"));assertTrue(multipart.contains("filename=\"测试.bin\""))
            assertTrue(form.body.asList().windowed(3).any { it==listOf(0.toByte(),128.toByte(),255.toByte()) })
            assertTrue(beacon("filtered-beacon-empty","null").body.isEmpty())
            // Use Chromium's actual shared keepalive quota and clear only the rejected token.
            val queued=JSONObject(browser.evaluate("JSON.stringify({large:navigator.sendBeacon('${server.base}filtered-beacon-quota','x'.repeat(131072)),small:navigator.sendBeacon('${server.base}filtered-beacon-quota','after-full')})"))
            assertFalse(queued.getBoolean("large"));assertTrue(queued.getBoolean("small"));assertEquals("after-full",server.take("/filtered-beacon-quota").body.toString(Charsets.UTF_8))
            browser.evaluate("window.savedBlobUrl=URL.createObjectURL;URL.createObjectURL=()=>{throw new Error('explicit beacon capture failure')};'ready'")
            try {
                val failure=runCatching { browser.evaluate("navigator.sendBeacon('${server.base}filtered-beacon-failure',new Blob(['retry']))") }.exceptionOrNull()
                assertTrue(failure?.message.orEmpty().contains("explicit beacon capture failure"))
            } finally { browser.evaluate("URL.createObjectURL=window.savedBlobUrl;delete window.savedBlobUrl;'restored'") }
            assertEquals("retry",beacon("filtered-beacon-failure","new Blob(['retry'])").body.toString(Charsets.UTF_8))
            assertFalse(browser.cookies().any { it.name=="responseCookie" })
        } }
    }
    @Test fun upgradedBeaconKeepsIncludeCredentialsAndExactPostBytes() {
        val store=java.security.KeyStore.getInstance("PKCS12")
        InstrumentationRegistry.getInstrumentation().context.assets.open("browser-test.p12").use { store.load(it,"testpassword".toCharArray()) }
        val keys=javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()).apply { init(store,"testpassword".toCharArray()) }
        val trusts=javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        val trust=trusts.trustManagers.filterIsInstance<javax.net.ssl.X509TrustManager>().single();val ssl=javax.net.ssl.SSLContext.getInstance("TLS").apply { init(keys.keyManagers,arrayOf(trust),java.security.SecureRandom()) }
        val client=OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory,trust).build()
        BrowserServer(ssl).use { server -> SourceBrowser(context,"instrumentation:beacon-https:${java.util.UUID.randomUUID()}",ephemeral=true,transportClient=client).use { browser ->
            browser.setRules("""[{"trigger":{"url-filter":".*filtered-beacon-upgrade.*"},"action":{"type":"make-https"}}]""")
            browser.loadHtml("<html><body><script>document.cookie='private=secret; Path=/';</script></body></html>",server.base);browser.waitForLoad()
            assertEquals("true",browser.evaluate("navigator.sendBeacon('${server.base}filtered-beacon-upgrade','中文 + 😀')"))
            val request=server.take("/filtered-beacon-upgrade");assertArrayEquals("中文 + 😀".toByteArray(Charsets.UTF_8),request.body);assertTrue(request.headers["cookie"].orEmpty().contains("private=secret"));assertTrue(request.headers["content-type"].orEmpty().startsWith("text/plain"))
        } }
    }
}
