package app.monogatari.android.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The signed-in user as the identity center has them. */
data class AccountProfile(
    val id: String, val name: String?, val avatar: String?, val email: String?, val hasPassword: Boolean,
    val createdAt: Long?,
    /** Linked social accounts by provider (google, github), with the account's email or name. */
    val identities: Map<String, String>,
) {
    companion object {
        fun parse(j: JSONObject): AccountProfile {
            val identities = linkedMapOf<String, String>()
            j.optJSONObject("identities")?.let { raw ->
                raw.keys().forEach { target ->
                    val details = raw.optJSONObject(target)?.optJSONObject("details")
                    identities[target] = details?.text("email") ?: details?.text("name") ?: ""
                }
            }
            return AccountProfile(j.getString("id"), j.text("name"), j.text("avatar"), j.text("primaryEmail"),
                j.optBoolean("hasPassword"), if (j.has("createdAt")) j.optLong("createdAt") else null, identities)
        }
    }
}

/**
 * The identity center's self-service Account API (`/api/my-account`), with the user's own access token.
 * Sensitive changes (email, password, linked accounts) need a fresh verification: the current
 * password, or a code sent to the email.
 */
class MyAccountApi(
    private val origin: String,
    private val token: suspend (forceRefresh: Boolean) -> String,
    client: OkHttpClient = OkHttpClient(),
) {
    private val http = client.newBuilder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    private suspend fun call(method: String, path: String, body: JSONObject? = null, verification: String? = null): JSONObject {
        for (attempt in 0..1) {
            val bearer = token(attempt > 0)
            val (status, text) = withContext(Dispatchers.IO) {
                val request = Request.Builder().url(origin + path)
                    .header("Authorization", "Bearer $bearer").header("Accept-Language", "zh-CN").header("Accept", "application/json")
                    .apply { verification?.let { header("logto-verification-id", it) } }
                    .method(method, body?.toString()?.toRequestBody(JSON) ?: if (method == "POST" || method == "PATCH") ByteArray(0).toRequestBody(null) else null)
                    .build()
                try { http.newCall(request).execute().use { it.code to it.body?.string().orEmpty() } } catch (e: IOException) { throw AuthFailure("network") }
            }
            if (status == 401 && attempt == 0) continue
            val json = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            if (status in 200..299) return json
            throw AuthFailure(json.text("code") ?: "http_$status", status, json.opt("data"))
        }
        throw AuthFailure("auth.unauthorized", 401)
    }

    suspend fun profile() = AccountProfile.parse(call("GET", "/api/my-account"))

    /** Name and avatar change without re-verifying. An empty avatar removes it. */
    suspend fun update(name: String? = null, avatar: String? = null) = AccountProfile.parse(call("PATCH", "/api/my-account", JSONObject().apply {
        name?.let { put("name", it) }
        avatar?.let { put("avatar", if (it.isEmpty()) JSONObject.NULL else it) }
    }))

    /** Proves it is the user; the verification is valid for a few minutes. */
    suspend fun verifyPassword(password: String): String =
        call("POST", "/api/verifications/password", JSONObject().put("password", password)).getString("verificationRecordId")

    /** Emails a code to [email]; returns the verification to confirm with [confirmCode]. */
    suspend fun sendCode(email: String): String = call("POST", "/api/verifications/verification-code",
        JSONObject().put("identifier", JSONObject().put("type", "email").put("value", email))).getString("verificationRecordId")

    suspend fun confirmCode(email: String, verification: String, code: String): String = call("POST", "/api/verifications/verification-code/verify",
        JSONObject().put("identifier", JSONObject().put("type", "email").put("value", email)).put("verificationId", verification).put("code", code))
        .getString("verificationRecordId")

    suspend fun changePassword(password: String, verification: String) {
        call("POST", "/api/my-account/password", JSONObject().put("password", password), verification)
    }

    /** Replaces the email: [verification] proves the user, [newEmailVerification] the new address. */
    suspend fun changeEmail(email: String, verification: String, newEmailVerification: String) {
        call("POST", "/api/my-account/primary-email", JSONObject().put("email", email).put("newIdentifierVerificationRecordId", newEmailVerification), verification)
    }

    suspend fun unlinkSocial(target: String, verification: String) {
        call("DELETE", "/api/my-account/identities/$target", verification = verification)
    }

    private companion object { val JSON = "application/json".toMediaType() }
}
