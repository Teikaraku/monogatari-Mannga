package app.monogatari.android.sources

import android.net.Uri
import android.util.Base64
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/** Mirrors source-defined OAuth settings, including PKCE and the original defaults key/suffixes. */
class SourceOAuthSession(private val runtime: SourceSession, private val definition: JSONObject) {
    val key = definition.getString("key")
    val callbackScheme: String = definition.optString("callbackScheme", "aidoku")
    private val originalUrl = definition.stringOrNull("url") ?: runtime.preferences.get(definition.getString("urlKey"))?.toString() ?: error("登录地址未设置")
    private val original = originalUrl.toHttpUrl()
    private val verifier = if (definition.optBoolean("pkce")) generateVerifier() else null
    private val state = original.queryParameter("state") ?: generateVerifier().take(32)
    val authorizationUrl: String = original.newBuilder().apply {
        if (original.queryParameter("state") == null) addQueryParameter("state", state)
        if (verifier != null) {
            setQueryParameter("code_challenge", challenge(verifier)); setQueryParameter("code_challenge_method", "S256"); setQueryParameter("response_type", "code")
        }
    }.build().toString()
    init {
        if (verifier != null) runtime.preferences.set("$key.codeVerifier", verifier)
        runtime.preferences.set("__oauthPending", JSONObject().put("setting", definition).put("state", state).put("originalUrl", originalUrl))
    }
    fun accepts(address: String) = Uri.parse(address).scheme == callbackScheme
    fun complete(address: String) {
        check(accepts(address)) { "OAuth回调地址无效" }
        complete(runtime, definition, originalUrl, state, address)
    }
    companion object {
        private fun generateVerifier(): String { val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~"; val random = SecureRandom(); return buildString { repeat(128) { append(alphabet[random.nextInt(alphabet.length)]) } } }
        fun challenge(verifier: String): String = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)), Base64.NO_PADDING or Base64.NO_WRAP or Base64.URL_SAFE)
        fun resume(runtime: SourceSession, address: String): Boolean {
            val pending = runtime.preferences.get("__oauthPending") as? JSONObject ?: return false
            val definition = pending.getJSONObject("setting")
            if (Uri.parse(address).scheme != definition.optString("callbackScheme", "aidoku")) return false
            complete(runtime, definition, pending.getString("originalUrl"), pending.getString("state"), address); return true
        }
        private fun complete(runtime: SourceSession, definition: JSONObject, originalUrl: String, state: String, address: String) {
            val uri = Uri.parse(address)
            val fragment = uri.fragment?.let { Uri.parse("https://callback.invalid/?$it") }
            fun parameter(name: String) = uri.getQueryParameter(name) ?: fragment?.getQueryParameter(name)
            check(parameter("state") == state) { "OAuth状态校验失败，请重新登录" }
            parameter("error")?.let { error(parameter("error_description") ?: it) }
            val key = definition.getString("key")
            if (definition.optBoolean("pkce") && definition.has("tokenUrl")) {
                val verifier = runtime.preferences.get("$key.codeVerifier")?.toString() ?: error("缺少PKCE校验值")
                val original = originalUrl.toHttpUrl()
                val body = FormBody.Builder().add("grant_type", "authorization_code").add("code", parameter("code") ?: error("登录没有返回授权码")).add("code_verifier", verifier)
                    .apply { original.queryParameter("client_id")?.let { add("client_id", it) }; original.queryParameter("redirect_uri")?.let { add("redirect_uri", it) } }.build()
                val result = runtime.fetch(Request.Builder().url(definition.getString("tokenUrl")).post(body).build())
                check(result.code in 200..299) { "令牌交换失败：HTTP ${result.code}" }
                val token = result.bytes.toString(Charsets.UTF_8); JSONObject(token).stringOrNull("error")?.let { error(it) }
                runtime.preferences.set(key, token)
            } else runtime.preferences.set(key, address)
            runtime.preferences.set("__oauthPending", null); runtime.preferences.set("$key.codeVerifier", null)
            definition.stringOrNull("notification")?.let(runtime::notify)
        }
    }
}
