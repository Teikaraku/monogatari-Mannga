package app.monogatari.android.sources

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.*
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ProfileStore
import androidx.webkit.ProcessGlobalConfig
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Debug-only IPC fixture. Its private process and provider directory never touch user browser data. */
class ProfileRecoveryFixtureService : Service() {
    private val directorySuffix = "aidoku-source-profile-recovery-fixture"
    private val main = Handler(Looper.getMainLooper())
    private val incoming = Messenger(Handler(Looper.getMainLooper()) { message ->
        val reply = message.replyTo ?: return@Handler true
        val command = message.what
        if (command == 3) {
            reply.send(Message.obtain().apply { data = Bundle().apply { putInt("pid", Process.myPid()) } })
            main.postDelayed({
                check(currentProcessName() == "$packageName:sourceProfileRecovery")
                Process.killProcess(Process.myPid())
            }, 100)
        } else Thread({
            val result = runCatching {
                when (command) { 1 -> seed(); 2 -> recover(); else -> error("Unknown profile fixture command") }
            }
            reply.send(Message.obtain().apply { data = Bundle().apply {
                putInt("pid", Process.myPid())
                result.fold({ putString("result", it.toString()) }, { putString("error", it.stackTraceToString()) })
            } })
        }, "profile-recovery-fixture").start()
        true
    })

    override fun onCreate() {
        super.onCreate()
        check(currentProcessName() == "$packageName:sourceProfileRecovery")
        check(WebViewFeature.isStartupFeatureSupported(this, WebViewFeature.STARTUP_FEATURE_SET_DATA_DIRECTORY_SUFFIX))
        ProcessGlobalConfig.apply(ProcessGlobalConfig().setDataDirectorySuffix(this, directorySuffix))
    }
    override fun onBind(intent: Intent?) = incoming.binder

    private fun currentProcessName(): String = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else {
        val processes = (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).runningAppProcesses
        processes?.firstOrNull { it.pid == Process.myPid() }?.processName ?: error("Unable to identify fixture process")
    }

    private fun <T> onMain(block: () -> T): T {
        val done = CountDownLatch(1)
        var result: Result<T>? = null
        main.post { result = runCatching(block); done.countDown() }
        check(done.await(30, TimeUnit.SECONDS))
        return result!!.getOrThrow()
    }
    private val preferences get() = getSharedPreferences("source-profile-recovery-fixture", MODE_PRIVATE)
    private val origin = "https://temporary-profile.aidoku.local/"
    private val providerDirectory get() = File(applicationInfo.dataDir, "app_webview_$directorySuffix")

    private fun nativeProfile(value: Any?, name: String): JSONObject? {
        when (value) {
            is JSONObject -> {
                if (value.optString("name") == name && value.optString("path").isNotBlank()) return value
                for (key in value.keys()) nativeProfile(value.opt(key), name)?.let { return it }
            }
            is JSONArray -> for (index in 0 until value.length()) nativeProfile(value.opt(index), name)?.let { return it }
        }
        return null
    }

    private fun awaitNativeProfilePersistence(name: String, present: Boolean): JSONObject? {
        // Chromium 124 stores the name/path list in pref_store through a ten-second
        // ImportantFileWriter timer. Killing this hidden service after only flushing
        // cookies does not commit those preferences. Observe the real file, never
        // manufacture a native preference or assume the in-memory list is durable.
        val store = File(providerDirectory, "pref_store")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        var failure: Throwable? = null
        do {
            try {
                check(store.isFile && store.length() in 1..(1024L * 1024))
                val profile = nativeProfile(JSONObject(store.readText()), name)
                if (present == (profile != null)) return profile
            } catch (error: Exception) { failure = error }
            Thread.sleep(50)
        } while (System.nanoTime() < deadline)
        error("Provider profile metadata did not persist present=$present for $name in $store: ${failure?.message}")
    }

    private fun diagnostics(phase: String, result: JSONObject) {
        val file = File(filesDir, "diagnostics/source-profile-recovery.json").apply { parentFile!!.mkdirs() }
        val previous = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
        previous.put(phase, JSONObject(result.toString()).put("pid", Process.myPid()).put("time", System.currentTimeMillis()))
        file.writeText(previous.toString(2))
    }

    private fun seed(): JSONObject {
        val browser = SourceBrowser(this, "cold-process-fixture", ephemeral = true)
        val name = browser.webProfileName
        browser.loadHtml("<html><script>document.cookie='temporary=private; Path=/';localStorage.setItem('temporaryValue','private')</script></html>", origin)
        browser.waitForLoad()
        check(browser.cookies().any { it.name == "temporary" })
        browser.close(); browser.close()
        check(onMain { ProfileStore.getInstance().getProfile(name)?.cookieManager?.getCookie(origin).isNullOrBlank() })

        // Probe the actual origin's DOM storage after close, rather than assuming that
        // deleteAllData returning means the provider has completed the operation.
        val loaded = CountDownLatch(1)
        var storage: String? = null
        val probe = onMain {
            WebView(this).apply {
                WebViewCompat.setProfile(this, name)
                settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        view.evaluateJavascript("localStorage.getItem('temporaryValue')") { storage = it; loaded.countDown() }
                    }
                }
                loadDataWithBaseURL(origin, "<html><body>Storage verification</body></html>", "text/html", "UTF-8", null)
            }
        }
        try { check(loaded.await(30, TimeUnit.SECONDS)); check(storage == "null") { "Temporary DOM storage survived close: $storage" } }
        finally { onMain { probe.destroy() } }
        val native = checkNotNull(awaitNativeProfilePersistence(name, present = true))
        val relativePath = native.getString("path")
        check(File(providerDirectory, relativePath).isDirectory) { "Persisted fixture profile directory is absent" }
        check(onMain { name in ProfileStore.getInstance().allProfileNames })
        check(preferences.edit().putString("profile", name).putString("profilePath", relativePath).commit())
        return JSONObject().put("name", name).put("cookiesCleared", true).put("storageCleared", true)
            .put("retained", true).put("nativePersisted", true).also { diagnostics("seed", it) }
    }

    private fun recover(): JSONObject {
        val name = preferences.getString("profile", null) ?: error("Missing previous process profile fixture")
        val relativePath = preferences.getString("profilePath", null) ?: error("Missing persisted profile path fixture")
        checkNotNull(awaitNativeProfilePersistence(name, present = true))
        val before = onMain { name in ProfileStore.getInstance().allProfileNames }
        check(before) { "Durably seeded profile is absent before cold-start cleanup" }
        // No getProfile/setProfile is allowed before the production cold-start cleanup.
        onMain { SourceWebProfiles.recoverAbandonedEphemeral() }
        val after = onMain { name in ProfileStore.getInstance().allProfileNames }
        check(!after) { "Previous process temporary profile was not reclaimed" }
        awaitNativeProfilePersistence(name, present = false)
        val directory = File(providerDirectory, relativePath)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (directory.exists() && System.nanoTime() < deadline) Thread.sleep(50)
        check(!directory.exists()) { "Reclaimed profile directory remains on disk" }
        check(preferences.edit().remove("profile").remove("profilePath").commit())
        return JSONObject().put("presentBefore", before).put("reclaimed", !after).put("nativeDeletionPersisted", true)
            .put("directoryRemoved", true).also { diagnostics("recover", it) }
    }
}
