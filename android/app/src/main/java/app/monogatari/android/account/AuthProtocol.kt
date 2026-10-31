package app.monogatari.android.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * A failure the sign-in screens explain to the user. [code] is Logto's error code
 * (`session.invalid_credentials`, ...) or one of: gate_rate, gate_pow, gate_unavailable, network,
 * session_lost, invalid_state, cancelled.
 */
class AuthFailure(val code: String, val status: Int? = null, val data: Any? = null, val retryAfter: Int? = null) : Exception(code) {
    /** Logto's password policy findings (`password_rejected.too_short`, ...). */
    val passwordIssues: List<String> get() = (data as? JSONArray)?.let { list ->
        (0 until list.length()).mapNotNull { (list.opt(it) as? JSONObject)?.optString("code")?.takeIf(String::isNotEmpty) }
    }.orEmpty()
    override fun toString() = "AuthFailure($code${status?.let { ", HTTP $it" } ?: ""})"
}

internal fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
internal fun randomUrlSafe(random: SecureRandom, bytes: Int) = base64Url(ByteArray(bytes).also(random::nextBytes))
internal fun pkceChallenge(verifier: String) = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

/**
 * The identity center's proof of work: the first decimal nonce whose SHA-256(salt + nonce) starts with
 * [bits] zero bits. Around 2^bits hashes, so callers run it off the main thread.
 */
fun solveProofOfWork(salt: String, bits: Int, limit: Long = 100_000_000): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val prefix = salt.toByteArray(Charsets.UTF_8)
    for (nonce in 0 until limit) {
        digest.update(prefix)
        val hash = digest.digest(nonce.toString().toByteArray(Charsets.US_ASCII))
        if (leadingZeroBits(hash, bits)) return nonce.toString()
    }
    throw AuthFailure("gate_pow")
}

internal fun leadingZeroBits(hash: ByteArray, bits: Int): Boolean {
    var need = bits
    for (byte in hash) {
        if (need <= 0) return true
        val value = byte.toInt() and 0xFF
        if (need >= 8) { if (value != 0) return false; need -= 8 }
        else return value shr (8 - need) == 0
    }
    return true
}

/** Reads Logto's error code (or the gate's refusal) from a failed response. */
internal fun failureOf(response: Response, body: String): AuthFailure {
    val status = response.code
    response.header("X-Gate-Reason")?.let { gate ->
        val code = when (gate) { "rate" -> "gate_rate"; "unavailable" -> "gate_unavailable"; else -> "gate_pow" }
        return AuthFailure(code, status, retryAfter = response.header("Retry-After")?.trim()?.toIntOrNull())
    }
    val json = runCatching { JSONObject(body) }.getOrNull()
    val code = json?.optString("code")?.takeIf(String::isNotEmpty)
    if (code == "session.not_found") return AuthFailure("session_lost", status)
    return AuthFailure(code ?: if (status == 429) "request.too_many_requests" else "http_$status", status, json?.opt("data"))
}

/**
 * One sign-in, registration or password reset against the identity center, through the same
 * Experience API its own page uses (so the same bot check, rate limits and lockouts apply).
 *
 * It opens an authorization request (code + PKCE), drives the Experience API with the interaction
 * cookies that request set, submits, and follows Logto's redirects until they reach this app's redirect
 * URI with an authorization code.
 */
class AuthInteraction(
    private val issuer: String = AccountConfig.ISSUER,
    private val clientId: String = AccountConfig.CLIENT_ID,
    private val resource: String = AccountConfig.RESOURCE,
    private val redirectUri: String = AccountConfig.REDIRECT_URI,
    private val scopes: List<String> = AccountConfig.SCOPES,
    client: OkHttpClient = OkHttpClient(),
    private val random: SecureRandom = SecureRandom(),
    private val solve: suspend (String, Int) -> String = { salt, bits -> withContext(Dispatchers.Default) { solveProofOfWork(salt, bits) } },
) {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false).cookieJar(okhttp3.CookieJar.NO_COOKIES)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val origin = URI(issuer).let { "${it.scheme}://${it.authority}" }
    /** Name to value, sent on every request of this interaction (Logto scopes them by path; one host is involved). */
    private val cookies = linkedMapOf<String, String>()
    private var started = false
    var codeVerifier: String = ""; private set
    private var state: String = ""

    private fun keepCookies(response: Response) {
        for (header in response.headers("Set-Cookie")) {
            val pair = header.substringBefore(';')
            val at = pair.indexOf('=')
            if (at <= 0) continue
            val name = pair.substring(0, at).trim(); val value = pair.substring(at + 1).trim()
            // Logto clears a cookie by setting it empty with an expiry in the past.
            if (value.isEmpty() || header.lowercase().contains("expires=thu, 01 jan 1970")) cookies.remove(name) else cookies[name] = value
        }
    }

    private suspend fun raw(method: String, url: String, body: JSONObject? = null, headers: Map<String, String> = emptyMap()): Pair<Response, String> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url)
                .header("Accept", "application/json").header("Accept-Language", "zh-CN")
                .apply { if (cookies.isNotEmpty()) header("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }) }
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .method(method, when {
                    body != null -> body.toString().toRequestBody(JSON)
                    method == "POST" || method == "PUT" -> ByteArray(0).toRequestBody(null)
                    else -> null
                }).build()
            try {
                http.newCall(request).execute().use { response -> keepCookies(response); response to (response.body?.string().orEmpty()) }
            } catch (e: IOException) { throw AuthFailure("network") }
        }

    /** Calls the Experience API; throws [AuthFailure] with Logto's error code on failure. */
    private suspend fun call(method: String, path: String, body: JSONObject? = null, headers: Map<String, String> = emptyMap()): JSONObject {
        val (response, text) = raw(method, origin + path, body, headers)
        if (response.code in 200..299) return runCatching { JSONObject(text) }.getOrDefault(JSONObject())
        throw failureOf(response, text)
    }

    private suspend fun proofOfWork(email: String): Map<String, String> {
        val challenge = call("GET", "/gate/challenge")
        val nonce = solve(challenge.getString("salt"), challenge.getInt("bits"))
        return mapOf("X-Mo-Pow" to "${challenge.getString("challenge")}:$nonce", "X-Mo-Email" to email)
    }

    /** The authorization request this interaction runs under. */
    fun authorizeUrl(extra: Map<String, String> = emptyMap()): String = authorizeUrl(issuer, clientId, redirectUri, scopes, resource, state, pkceChallenge(codeVerifier), extra)

    /** Opens the authorization request; Logto answers with the interaction cookies. */
    suspend fun start(event: String) {
        codeVerifier = randomUrlSafe(random, 48)
        state = randomUrlSafe(random, 24)
        val (response, _) = raw("GET", authorizeUrl())
        if (response.code >= 400 || "_interaction" !in cookies) throw AuthFailure("network", response.code)
        call("PUT", "/api/experience", JSONObject().put("interactionEvent", event))
        started = true
    }

    suspend fun switchEvent(event: String) { call("PUT", "/api/experience/interaction-event", JSONObject().put("interactionEvent", event)) }

    private fun email(value: String) = JSONObject().put("type", "email").put("value", value)

    /** Checks [email] and [password]; returns the verification to identify with. */
    suspend fun verifyPassword(email: String, password: String): String =
        call("POST", "/api/experience/verification/password", JSONObject().put("identifier", email(email)).put("password", password), proofOfWork(email))
            .getString("verificationId")

    /** Emails a 6-digit code for [event] (Register, SignIn, ForgotPassword); returns its verification. */
    suspend fun sendCode(event: String, email: String): String =
        call("POST", "/api/experience/verification/verification-code", JSONObject().put("interactionEvent", event).put("identifier", email(email)), proofOfWork(email))
            .getString("verificationId")

    suspend fun verifyCode(email: String, verificationId: String, code: String): String =
        call("POST", "/api/experience/verification/verification-code/verify",
            JSONObject().put("identifier", email(email)).put("verificationId", verificationId).put("code", code)).getString("verificationId")

    /** Signs in (or completes registration) with [verificationId]; none after registration's profile steps. */
    suspend fun identify(verificationId: String? = null) {
        call("POST", "/api/experience/identification", JSONObject().apply { verificationId?.let { put("verificationId", it) } })
    }

    suspend fun addEmail(verificationId: String) { call("POST", "/api/experience/profile", JSONObject().put("type", "email").put("verificationId", verificationId)) }
    suspend fun setPassword(password: String) { call("POST", "/api/experience/profile", JSONObject().put("type", "password").put("value", password)) }
    suspend fun resetPassword(password: String) { call("PUT", "/api/experience/profile/password", JSONObject().put("password", password)) }

    /** Completes the interaction and returns the authorization code for this app. */
    suspend fun submit(): String {
        check(started) { "Interaction not started" }
        var location: String? = call("POST", "/api/experience/submit").optString("redirectTo").takeIf(String::isNotEmpty)
        // Logto walks through its own pages (consent) before it returns to the app.
        var hop = 0
        while (hop < 8 && location != null && !location.startsWith(redirectUri)) {
            val url = if (URI(location).isAbsolute) location else URI("$issuer/").resolve(location).toString()
            val (response, _) = raw("GET", url)
            location = response.header("Location")?.let { next -> if (URI(next).isAbsolute) next else URI(url).resolve(next).toString() }
            hop++
        }
        if (location == null || !location.startsWith(redirectUri)) throw AuthFailure("session_lost")
        return authorizationCode(location, state)
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        fun authorizeUrl(issuer: String, clientId: String, redirectUri: String, scopes: List<String>, resource: String,
                         state: String, challenge: String, extra: Map<String, String> = emptyMap()): String =
            "$issuer/auth".toHttpUrl().newBuilder()
                .addQueryParameter("client_id", clientId).addQueryParameter("redirect_uri", redirectUri)
                .addQueryParameter("response_type", "code").addQueryParameter("scope", scopes.joinToString(" "))
                .addQueryParameter("resource", resource).addQueryParameter("state", state)
                .addQueryParameter("code_challenge", challenge).addQueryParameter("code_challenge_method", "S256")
                .addQueryParameter("prompt", "consent")
                .apply { extra.forEach { (k, v) -> addQueryParameter(k, v) } }
                .build().toString()

        /** The code in a redirect back to the app, after checking it answers this request. */
        fun authorizationCode(location: String, expectedState: String): String {
            val query = URI(location).rawQuery.orEmpty().split('&').filter(String::isNotEmpty).associate {
                val name = it.substringBefore('='); val value = it.substringAfter('=', "")
                java.net.URLDecoder.decode(name, "UTF-8") to java.net.URLDecoder.decode(value, "UTF-8")
            }
            if (query["state"] != expectedState) throw AuthFailure("invalid_state")
            query["error"]?.let { throw AuthFailure(if (it == "access_denied") "cancelled" else it) }
            return query["code"]?.takeIf(String::isNotEmpty) ?: throw AuthFailure("session_lost")
        }
    }
}
