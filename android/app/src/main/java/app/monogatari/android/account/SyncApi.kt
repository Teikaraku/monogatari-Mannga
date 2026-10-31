package app.monogatari.android.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** One synced item as exchanged with the sync service. [updated] is milliseconds since the epoch. */
data class SyncRecord(val kind: String, val key: String, val updated: Long, val data: JSONObject? = null, val deleted: Boolean = false) {
    fun json(): JSONObject = JSONObject().put("kind", kind).put("key", key).put("updated", updated).put("deleted", deleted)
        .apply { if (!deleted) put("data", data ?: JSONObject()) }
    companion object {
        fun parse(j: JSONObject) = SyncRecord(j.getString("kind"), j.getString("key"), j.getLong("updated"),
            if (j.optBoolean("deleted")) null else j.optJSONObject("data"), j.optBoolean("deleted"))
    }
}

data class SyncPage(val items: List<SyncRecord>, val cursor: Long, val more: Boolean, val reset: Boolean, val seq: Long?)

class SyncApiException(val code: String, val status: Int? = null) : Exception(code)

/** This install as the account's device list shows it. */
data class SyncDevice(val id: String, val name: String, val platform: String = "android") {
    val headers: Map<String, String> get() = mapOf(
        "X-Mo-Device" to id,
        // Header values must be ASCII; the name is percent-encoded.
        "X-Mo-Device-Name" to URLEncoder.encode(name, "UTF-8").replace("+", "%20"),
        "X-Mo-Platform" to platform,
    )
}

data class AccountDevice(val id: String, val name: String, val platform: String, val lastSeen: Long, val firstSeen: Long, val current: Boolean)

/** The manga sync service (manga.monogatari.top/sync). */
class SyncApi(
    private val baseUrl: String,
    private val token: suspend (forceRefresh: Boolean) -> String,
    private val device: SyncDevice?,
    client: OkHttpClient = OkHttpClient(),
) {
    private val http = client.newBuilder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).build()

    private suspend fun send(method: String, path: String, body: okhttp3.RequestBody? = null): String {
        for (attempt in 0..1) {
            val bearer = token(attempt > 0)
            val (status, text) = withContext(Dispatchers.IO) {
                val request = Request.Builder().url(baseUrl + path).header("Authorization", "Bearer $bearer").header("Accept", "application/json")
                    .apply { device?.headers?.forEach { (k, v) -> header(k, v) } }
                    .method(method, body).build()
                try { http.newCall(request).execute().use { it.code to it.body?.string().orEmpty() } } catch (e: IOException) { throw SyncApiException("network") }
            }
            if (status in 200..299) return text
            val code = runCatching { JSONObject(text).getJSONObject("error").getString("code") }.getOrNull()
            // Removed from the device list elsewhere: a new token would not help.
            if (status == 401 && code == "device_revoked") throw SyncApiException("device_revoked", 401)
            if (status == 401 && attempt == 0) continue // Expired or rotated keys: one fresh token.
            if (status == 401) throw SyncApiException("unauthorized", 401)
            if (status == 507) throw SyncApiException("quota_exceeded", 507)
            throw SyncApiException("server", status)
        }
        throw SyncApiException("unauthorized", 401)
    }

    suspend fun pull(since: Long): SyncPage {
        val body = JSONObject(send("GET", "/sync/v1/changes?since=$since&limit=$PAGE_SIZE"))
        val items = body.optJSONArray("items") ?: JSONArray()
        return SyncPage((0 until items.length()).map { SyncRecord.parse(items.getJSONObject(it)) }, body.optLong("cursor", since),
            body.optBoolean("more"), body.optBoolean("reset"), if (body.has("seq")) body.optLong("seq") else null)
    }

    /** Sends [records]; returns the account's sequence after the write. */
    suspend fun push(records: List<SyncRecord>): Long? {
        if (records.isEmpty()) return null
        val body = JSONObject().put("items", JSONArray(records.map { it.json() })).toString().toRequestBody(JSON)
        return JSONObject(send("POST", "/sync/v1/changes", body)).let { if (it.has("seq")) it.getLong("seq") else null }
    }

    suspend fun status(): JSONObject = JSONObject(send("GET", "/sync/v1/status"))

    suspend fun devices(): List<AccountDevice> {
        val items = JSONObject(send("GET", "/sync/v1/devices")).optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).map { items.getJSONObject(it) }.map {
            AccountDevice(it.getString("id"), runCatching { java.net.URLDecoder.decode(it.optString("name"), "UTF-8") }.getOrDefault(it.optString("name")),
                it.optString("platform", "other"), it.optLong("seen"), it.optLong("created"), it.optBoolean("current"))
        }
    }

    /** Signs [id] out: its next request is refused and the app there signs out. */
    suspend fun removeDevice(id: String) { send("DELETE", "/sync/v1/devices/" + URLEncoder.encode(id, "UTF-8")) }

    /** Uploads a profile picture; the server re-encodes it to a square WebP and returns its URL. */
    suspend fun uploadAvatar(bytes: ByteArray): String =
        JSONObject(send("POST", "/sync/v1/avatar", bytes.toRequestBody("application/octet-stream".toMediaType()))).getString("url")

    /** Deletes everything this app synced to the account, and its device list. */
    suspend fun erase() { send("DELETE", "/sync/v1/account") }

    companion object {
        const val PAGE_SIZE = 1000
        private val JSON = "application/json".toMediaType()
    }
}
