package app.monogatari.android.tracking

import android.net.Uri
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import org.json.JSONObject

data class OAuthToken(val access: String, val refresh: String = "", val expiresAt: Long = Long.MAX_VALUE, val type: String = "Bearer") {
    fun json() = JSONObject().put("access", access).put("refresh", refresh).put("expiresAt", expiresAt).put("type", type)
    companion object {
        fun stored(j: JSONObject) = OAuthToken(j.getString("access"), j.optString("refresh"), j.optLong("expiresAt", Long.MAX_VALUE), j.optString("type", "Bearer"))
        fun response(j: JSONObject, previousRefresh: String = "") = OAuthToken(j.getString("access_token"), j.optString("refresh_token", previousRefresh),
            if (j.has("expires_in")) System.currentTimeMillis() + j.getLong("expires_in") * 1000 else Long.MAX_VALUE, j.optString("token_type", "Bearer"))
    }
}
object TrackerOAuth {
    fun random(): String = ByteArray(32).also(SecureRandom()::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    fun challenge(verifier: String, method: String): String = if (method == "S256") Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))) else verifier
    fun authorize(account: TrackerAccount, state: String, verifier: String): String = account.provider.authUrl.toHttpUrl().newBuilder()
        .addQueryParameter("client_id", account.clientId).addQueryParameter("redirect_uri", account.redirectUri)
        .addQueryParameter("response_type", if (account.provider == TrackerProvider.ANILIST) "token" else "code")
        .addQueryParameter("state", state).apply {
            if (account.provider.pkce.isNotEmpty()) { addQueryParameter("code_challenge", challenge(verifier, account.provider.pkce)); addQueryParameter("code_challenge_method", account.provider.pkce) }
            if (account.provider.scope.isNotEmpty()) addQueryParameter("scope", account.provider.scope)
        }.build().toString()
    fun parameters(uri: Uri): Map<String, String> {
        val encoded = uri.encodedQuery.orEmpty() + "&" + uri.encodedFragment.orEmpty()
        return encoded.split('&').filter { it.contains('=') }.associate { val p = it.split('=', limit = 2); Uri.decode(p[0]) to Uri.decode(p[1]) }
    }
    fun callbackMatches(expected: String, actual: Uri): Boolean {
        val e = Uri.parse(expected)
        return e.scheme == actual.scheme && e.host == actual.host && e.port == actual.port && e.path.orEmpty() == actual.path.orEmpty()
    }
    fun tokenForm(account: TrackerAccount, secret: String, code: String? = null, verifier: String = "", refresh: String? = null) = FormBody.Builder()
        .add("grant_type", if (refresh != null) "refresh_token" else "authorization_code").add("client_id", account.clientId)
        .add("redirect_uri", account.redirectUri).apply {
            if (secret.isNotEmpty()) add("client_secret", secret)
            if (refresh != null) add("refresh_token", refresh) else { add("code", code.orEmpty()); if (account.provider.pkce.isNotEmpty()) add("code_verifier", verifier) }
        }.build()
}
