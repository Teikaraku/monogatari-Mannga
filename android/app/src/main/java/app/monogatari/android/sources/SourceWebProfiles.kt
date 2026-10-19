package app.monogatari.android.sources

import android.webkit.CookieManager
import android.webkit.WebView
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.webkit.ProfileStore
import androidx.webkit.CookieManagerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object SourceWebProfiles {
    private const val ephemeralPrefix = "monogatari-ephemeral-"
    private val activeEphemeral = mutableSetOf<String>()
    private var recoveredEphemeral = false
    private val cleanup by lazy {
        val handler = Handler(Looper.getMainLooper())
        DeferredProfileCleanup(
            schedule = { delay, action -> handler.postDelayed({ action() }, delay) },
            delete = { name -> ProfileStore.getInstance().deleteProfile(name); Unit },
            reportFailure = { name, error -> android.util.Log.w("MonogatariSourceProfiles", "Profile deletion deferred: $name", error) }
        )
    }
    fun profileName(sourceId: String, ephemeral: Boolean = false) =
        "${if (ephemeral) ephemeralPrefix else "monogatari-"}${SourceRuntimeManager.digest(sourceId)}"
    fun configure(view: WebView, sourceId: String, ephemeral: Boolean = false) {
        check(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) { "此设备的 Android System WebView 不支持独立网站数据，请更新 Android System WebView" }
        recoverAbandonedEphemeral()
        val name = profileName(sourceId, ephemeral)
        cleanup.cancel(name)
        WebViewCompat.setProfile(view, name)
        if (ephemeral) activeEphemeral.add(name)
    }
    internal fun recoverAbandonedEphemeral() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!recoveredEphemeral && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            try {
                ProfileStore.getInstance().allProfileNames.filter { it.startsWith(ephemeralPrefix) && it !in activeEphemeral }.forEach { cleanup.enqueue(it, immediately = true) }
                recoveredEphemeral = true
            } catch (error: Exception) {
                android.util.Log.w("MonogatariSourceProfiles", "Unable to enumerate abandoned temporary profiles", error)
            }
        }
    }
    fun manager(view: WebView): CookieManager = WebViewCompat.getProfile(view).cookieManager
    fun records(view: WebView, urls: Collection<String>): List<Cookie> {
        val manager = manager(view)
        return urls.flatMap { address ->
            val url = address.toHttpUrlOrNull() ?: return@flatMap emptyList()
            val raw = if (WebViewFeature.isFeatureSupported(WebViewFeature.GET_COOKIE_INFO)) CookieManagerCompat.getCookieInfo(manager, address)
                else manager.getCookie(address).orEmpty().split(';').map(String::trim)
            raw.mapNotNull { Cookie.parse(url, it) }
        }.distinctBy { "${it.name}|${it.domain}|${it.path}" }
    }
    /** Clear only this source's browser storage, including HTTP cache and local storage. */
    fun clear(context: Context, sourceId: String) {
        // Unsupported providers have never been allowed to create source browsers, so
        // there is no isolated source profile to clear. Do not touch unrelated defaults.
        if(!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))return
        clearViewData(context,profileName(sourceId))
    }
    /** Explicit whole-app maintenance may clear the default provider on older Android. */
    fun clearAll(context:Context) {
        if(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            recoverAbandonedEphemeral()
            val names=ProfileStore.getInstance().allProfileNames
            names.forEach { clearViewData(context,it) }
        } else clearViewData(context,null)
    }
    private fun clearViewData(context:Context,profileName:String?) {
        val finished = java.util.concurrent.CountDownLatch(1)
        var failure: Throwable? = null
        val action = Runnable { try {
            val view = WebView(context)
            val manager=if(profileName!=null) {
                WebViewCompat.setProfile(view,profileName);val profile=WebViewCompat.getProfile(view)
                profile.webStorage.deleteAllData();profile.geolocationPermissions.clearAll();profile.cookieManager
            } else {
                android.webkit.WebStorage.getInstance().deleteAllData();android.webkit.GeolocationPermissions.getInstance().clearAll();CookieManager.getInstance()
            }
            view.clearCache(true)
            manager.removeAllCookies { manager.flush();view.destroy();finished.countDown() }
        } catch (e: Throwable) { failure = e; finished.countDown() } }
        if (Looper.myLooper() == Looper.getMainLooper()) action.run() else {
            Handler(Looper.getMainLooper()).post(action)
            check(finished.await(30, java.util.concurrent.TimeUnit.SECONDS)) { "清除网站数据超时" }
        }
        failure?.let { throw it }
    }
    fun removeEphemeral(sourceId: String) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            val name = profileName(sourceId, ephemeral = true)
            activeEphemeral.remove(name)
            cleanup.enqueue(name)
        }
    }
    /** Clear private data before destroying a temporary view, regardless of provider unload support. */
    internal fun clearAndDestroyTemporary(view: WebView, sourceId: String, complete: () -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        var destroyed = false
        fun destroyView() { if (!destroyed) { destroyed = true; view.destroy() } }
        fun finish() { try { removeEphemeral(sourceId) } finally { complete() } }
        try {
            val profile = WebViewCompat.getProfile(view)
            val storage = profile.webStorage
            val geolocation = profile.geolocationPermissions
            val cookies = profile.cookieManager
            view.clearCache(true)
            // Stop the document before clearing its stores, so scripts cannot race
            // the purge and recreate a cookie/localStorage value while closing.
            destroyView()
            storage.deleteAllData()
            geolocation.clearAll()
            cookies.removeAllCookies {
                try { cookies.flush() } catch (error: Exception) {
                    android.util.Log.w("MonogatariSourceProfiles", "Temporary cookie flush failed", error)
                } finally { finish() }
            }
        } catch (error: Exception) {
            android.util.Log.w("MonogatariSourceProfiles", "Temporary browser data clear failed", error)
            try { destroyView() } catch (destroyError: Exception) {
                android.util.Log.w("MonogatariSourceProfiles", "Temporary WebView destruction failed", destroyError)
            } finally { finish() }
        }
    }
}
