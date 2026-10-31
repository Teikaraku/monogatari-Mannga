package app.monogatari.android.account

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Where the session is kept between launches. */
interface AccountVault {
    fun read(): String?
    fun write(value: String)
    fun clear()
}

/** The Android Keystore-backed store the app already keeps its credentials in. */
class KeystoreVault(context: Context, private val key: String = "account.session") : AccountVault {
    private val store = app.monogatari.android.data.SecretStore(context)
    override fun read(): String? = runCatching { store.get(key) }.getOrNull()
    override fun write(value: String) = store.set(key, value)
    override fun clear() = store.remove(key)
}

class AccountException(val code: String, message: String? = null) : Exception(message ?: code) {
    override fun toString() = "AccountException($code${message?.takeIf { it != code }?.let { ": $it" } ?: ""})"
}

/** The signed-in user as the app shows them. */
data class AccountUser(val id: String, val name: String? = null, val email: String? = null, val picture: String? = null) {
    /** The name, else the part of the email before @, else a short id. */
    val displayName: String get() = name?.trim()?.takeIf(String::isNotEmpty)
        ?: email?.takeIf { '@' in it }?.substringBefore('@') ?: id.take(8)
    fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("email", email).put("picture", picture)
    companion object {
        fun parse(j: JSONObject) = AccountUser(j.getString("id"), j.text("name"), j.text("email"), j.text("picture"))
        /** The ID token came straight from the token endpoint over TLS for this app's verifier; nothing on a server trusts it. */
        fun fromIdToken(token: String): AccountUser {
            val claims = jwtClaims(token)
            val sub = claims.text("sub") ?: throw AccountException("rejected", "ID token has no subject")
            return AccountUser(sub, claims.text("name") ?: claims.text("username"), claims.text("email"), claims.text("picture"))
        }
    }
}

data class AccountTokens(val accessToken: String, val expiresAt: Long, val refreshToken: String?, val idToken: String?) {
    /** Refreshed a minute early so a request never leaves with a token about to lapse. */
    fun expiresSoon(now: Long) = now >= expiresAt - 60_000
    fun json(): JSONObject = JSONObject().put("access_token", accessToken).put("expires_at", expiresAt)
        .put("refresh_token", refreshToken).put("id_token", idToken)
    companion object {
        fun parse(j: JSONObject) = AccountTokens(j.getString("access_token"), j.getLong("expires_at"), j.text("refresh_token"), j.text("id_token"))
        fun fromResponse(j: JSONObject, now: Long, previousRefresh: String?): AccountTokens {
            val access = j.text("access_token") ?: throw AccountException("rejected", "No access token")
            // Logto rotates refresh tokens; keep the old one only when no new one was issued.
            return AccountTokens(access, now + j.optLong("expires_in", 3600) * 1000, j.text("refresh_token") ?: previousRefresh, j.text("id_token"))
        }
    }
}

internal fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).takeIf(String::isNotEmpty)

internal fun jwtClaims(token: String): JSONObject {
    val parts = token.split('.')
    if (parts.size != 3) throw AccountException("rejected", "Not a JWT")
    return JSONObject(String(Base64.getUrlDecoder().decode(parts[1].padEnd((parts[1].length + 3) / 4 * 4, '=')), Charsets.UTF_8))
}

/** Opens the authorization URL in the system browser and returns the redirect it ended on. */
typealias BrowserLauncher = suspend (url: String) -> String

/**
 * The 物语 account: OIDC authorization code with PKCE against Logto, the session in the Keystore,
 * access tokens for the sync service refreshed as needed.
 *
 * Refresh tokens rotate, so every refresh (for the sync resource, or the resource-less one the
 * Account API needs) runs under one lock and saves the new refresh token before the next starts.
 */
class AccountService(
    private val vault: AccountVault,
    private val client: OkHttpClient = OkHttpClient(),
    private val browser: BrowserLauncher? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val issuer: String = AccountConfig.ISSUER,
    private val clientId: String = AccountConfig.CLIENT_ID,
    private val resource: String = AccountConfig.RESOURCE,
    private val redirectUri: String = AccountConfig.REDIRECT_URI,
) {
    private val http = client.newBuilder().followRedirects(false).connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val refreshLock = Mutex()
    @Volatile private var tokens: AccountTokens? = null
    private val userFlow = MutableStateFlow<AccountUser?>(null)
    val user = userFlow.asStateFlow()
    val signedIn get() = tokens != null
    val issuerOrigin: String get() = java.net.URI(issuer).let { "${it.scheme}://${it.authority}" }
    @Volatile private var accountToken: Pair<String, Long>? = null

    init {
        runCatching { vault.read()?.let { raw ->
            val json = JSONObject(raw)
            tokens = AccountTokens.parse(json.getJSONObject("tokens"))
            userFlow.value = AccountUser.parse(json.getJSONObject("user"))
        } }.onFailure { tokens = null; userFlow.value = null; vault.clear() }
    }

    private fun save() {
        val t = tokens; val u = userFlow.value
        if (t == null || u == null) vault.clear() else vault.write(JSONObject().put("tokens", t.json()).put("user", u.json()).toString())
    }

    fun interaction() = AuthInteraction(issuer, clientId, resource, redirectUri, client = client, random = random)

    /**
     * Google or GitHub: their page must open in a browser. Logto sends the browser straight to the
     * provider and back to [redirectUri]; a first sign-in creates the account.
     */
    suspend fun signInWithBrowser(social: String): AccountUser {
        val launch = browser ?: throw AccountException("not_configured")
        val verifier = randomUrlSafe(random, 48); val state = randomUrlSafe(random, 24)
        val url = AuthInteraction.authorizeUrl(issuer, clientId, redirectUri, AccountConfig.SCOPES, resource, state, pkceChallenge(verifier),
            mapOf("direct_sign_in" to "social:$social-universal"))
        val returned = launch(url)
        val code = try { AuthInteraction.authorizationCode(returned, state) } catch (e: AuthFailure) {
            throw AccountException(if (e.code == "cancelled") "cancelled" else if (e.code == "invalid_state") "invalid_state" else "rejected", e.code)
        }
        return completeSignIn(code, verifier)
    }

    /** Exchanges the code a sign-in ended with for the session, and keeps it. */
    suspend fun completeSignIn(code: String, codeVerifier: String): AccountUser = refreshLock.withLock {
        // With the resource the first access token is already the sync service's JWT (without it, an opaque one).
        val next = tokenRequest(mapOf("grant_type" to "authorization_code", "code" to code, "redirect_uri" to redirectUri,
            "code_verifier" to codeVerifier, "client_id" to clientId, "resource" to resource), null)
        val id = next.idToken ?: throw AccountException("rejected", "No ID token")
        val user = AccountUser.fromIdToken(id)
        tokens = next; accountToken = null; userFlow.value = user
        save(); user
    }

    /** Shows edits made in the app (name, email, avatar) until the next ID token. */
    fun updateProfile(name: String? = null, email: String? = null, picture: String? = null, clearPicture: Boolean = false) {
        val user = userFlow.value ?: return
        userFlow.value = user.copy(name = name ?: user.name, email = email ?: user.email, picture = if (clearPicture) null else picture ?: user.picture)
        save()
    }

    private suspend fun tokenRequest(form: Map<String, String>, previousRefresh: String?): AccountTokens = withContext(Dispatchers.IO) {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val request = Request.Builder().url("$issuer/token").post(body).header("Accept", "application/json").build()
        val (status, text) = try { http.newCall(request).execute().use { it.code to it.body?.string().orEmpty() } }
            catch (e: IOException) { throw AccountException("network", e.message) }
        if (status in 400..499) throw AccountException("rejected", runCatching { JSONObject(text).optString("error") }.getOrNull() ?: "HTTP $status")
        if (status !in 200..299) throw AccountException("network", "HTTP $status")
        try { AccountTokens.fromResponse(JSONObject(text), now(), previousRefresh) } catch (e: org.json.JSONException) { throw AccountException("rejected", e.message) }
    }

    /** A current access token for the sync service. A refresh the server refuses signs the user out. */
    suspend fun accessToken(forceRefresh: Boolean = false): String {
        val current = tokens ?: throw AccountException("signed_out")
        if (!forceRefresh && !current.expiresSoon(now())) return current.accessToken
        return refreshLock.withLock {
            val latest = tokens ?: throw AccountException("signed_out")
            // Another caller refreshed while this one waited.
            if (latest !== current && !latest.expiresSoon(now())) return@withLock latest.accessToken
            val refresh = latest.refreshToken ?: run { forget(); throw AccountException("signed_out") }
            val next = refreshing { tokenRequest(mapOf("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to clientId, "resource" to resource), refresh) }
            // A refresh for the API resource returns no ID token; keep the profile we have.
            tokens = next.copy(idToken = next.idToken ?: latest.idToken)
            next.idToken?.let { runCatching { userFlow.value = AccountUser.fromIdToken(it).let { fresh -> fresh.copy(picture = fresh.picture ?: userFlow.value?.picture) } } }
            save(); next.accessToken
        }
    }

    /**
     * A token for the identity center's own Account API (profile, password, email). That API takes only
     * tokens issued without a resource, so this is a separate refresh, kept in memory.
     */
    suspend fun accountApiToken(forceRefresh: Boolean = false): String {
        accountToken?.let { (token, expires) -> if (!forceRefresh && now() < expires - 60_000) return token }
        return refreshLock.withLock {
            val latest = tokens ?: throw AccountException("signed_out")
            val refresh = latest.refreshToken ?: run { forget(); throw AccountException("signed_out") }
            val next = refreshing { tokenRequest(mapOf("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to clientId), refresh) }
            // Keep the sync token; only the rotated refresh token moves on.
            tokens = latest.copy(refreshToken = next.refreshToken, idToken = next.idToken ?: latest.idToken)
            save()
            accountToken = next.accessToken to next.expiresAt
            next.accessToken
        }
    }

    private suspend fun <T> refreshing(block: suspend () -> T): T = try { block() } catch (e: AccountException) {
        if (e.code == "rejected") { forget(); throw AccountException("signed_out") }
        throw e
    }

    private fun forget() {
        tokens = null; accountToken = null; userFlow.value = null; vault.clear()
    }

    /** Signs out here; the refresh token is revoked so it cannot be used again. */
    suspend fun signOut() {
        val refresh = refreshLock.withLock { val token = tokens?.refreshToken; forget(); token } ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                val body = FormBody.Builder().add("token", refresh).add("token_type_hint", "refresh_token").add("client_id", clientId).build()
                http.newCall(Request.Builder().url("$issuer/token/revocation").post(body).build()).execute().close()
            }
        }
    }

    companion object {
        @Volatile private var instance: AccountService? = null
        /** Set by the activity: the system browser for Google and GitHub. */
        @Volatile var systemBrowser: BrowserLauncher? = null
        fun get(context: Context): AccountService = instance ?: synchronized(this) {
            instance ?: AccountService(KeystoreVault(context.applicationContext), browser = { url ->
                (systemBrowser ?: throw AccountException("not_configured"))(url)
            }).also { instance = it }
        }
    }
}

/** Waits for the redirect back from the system browser (delivered to the activity as an intent). */
object BrowserCallback {
    @Volatile private var pending: CompletableDeferred<String>? = null
    fun expect(): CompletableDeferred<String> = CompletableDeferred<String>().also { pending?.cancel(); pending = it }
    /** Returns whether the URI belonged to a sign-in. */
    fun deliver(uri: String): Boolean {
        if (!uri.startsWith(AccountConfig.REDIRECT_URI)) return false
        pending?.complete(uri); pending = null
        return true
    }
    /** The user came back without finishing (closed the browser). */
    fun abandon() { pending?.completeExceptionally(AccountException("cancelled")); pending = null }
}
