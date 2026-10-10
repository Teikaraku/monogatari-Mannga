package app.monogatari.android.sources

import app.monogatari.android.data.SourceConfig
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** All four auth modes and automatic session recovery from the original SuwayomiHelper. */
class SuwayomiClient(private val http: OkHttpClient, private val credentials: SourcePreferenceBackend, private val preferences: SourcePreferences?) {
    enum class LoginType { NONE, BASIC, SIMPLE, UI }
    private fun url(source: SourceConfig, path: String) = (source.baseUrl.trimEnd('/') + "/").toHttpUrl().resolve(path) ?: error("无效Suwayomi地址")
    private data class Response(val code: Int, val text: String, val cookies: String?, val location: String?)
    private fun send(source: SourceConfig, path: String = "", body: RequestBody? = null, authorize: Boolean = true, basic: String? = null): Response {
        val builder = Request.Builder().url(url(source, path)).header("Accept", "application/json")
        if (body != null) builder.post(body)
        if (basic != null) builder.header("Authorization", basic) else if (authorize) {
            credentials.get("${source.id}.cookie")?.let { builder.header("Cookie", it) }
                ?: credentials.get(source.id)?.let { builder.header("Authorization", it) }
        }
        return http.newCall(builder.build()).execute().use { response ->
            val cookies = Cookie.parseAll(response.request.url, response.headers).takeIf { it.isNotEmpty() }?.joinToString("; ") { "${it.name}=${it.value}" }
            Response(response.code, response.body?.byteStream()?.use { SourceHost.readBounded(it, 50 * 1024 * 1024) }?.toString(Charsets.UTF_8).orEmpty(), cookies, response.header("Location"))
        }
    }
    private fun jsonBody(value: JSONObject) = value.toString().toRequestBody("application/json".toMediaType())
    private fun unauthorized(response: Response): Boolean = response.code == 401 || runCatching {
        JSONObject(response.text).optJSONArray("errors")?.values()?.filterIsInstance<JSONObject>()?.any { it.optString("message").contains("unauthorized", true) } == true
    }.getOrDefault(false)
    fun loginType(source: SourceConfig): LoginType? {
        val response = send(source, authorize = false)
        if (response.code == 401) return LoginType.BASIC
        if (response.code in 300..399 && response.location?.contains("/login.html") == true) return LoginType.SIMPLE
        if (response.code in 200..299) {
            val mode = send(source, "api/graphql", jsonBody(JSONObject().put("operationName", "AUTH_MODE").put("query", "query AUTH_MODE { settings { authMode } }")), authorize = false)
            if (unauthorized(mode)) return LoginType.UI
            when (runCatching { JSONObject(mode.text).optJSONObject("data")?.optJSONObject("settings")?.stringOrNull("authMode") }.getOrNull()) {
                "NONE" -> return LoginType.NONE; "BASIC" -> return LoginType.BASIC; "SIMPLE" -> return LoginType.SIMPLE; "UI" -> return LoginType.UI
            }
        }; return null
    }
    fun authenticate(source: SourceConfig, username: String, password: String) {
        val type = loginType(source) ?: error("无法确定Suwayomi登录方式")
        when (type) {
            LoginType.NONE -> { credentials.remove(source.id); credentials.remove("${source.id}.cookie"); credentials.remove("${source.id}.refreshToken") }
            LoginType.BASIC -> {
                val basic = Credentials.basic(username, password)
                check(send(source, authorize = false, basic = basic).code in 200..299) { "Suwayomi登录失败" }
                credentials.set(source.id, basic); credentials.remove("${source.id}.cookie"); credentials.remove("${source.id}.refreshToken")
            }
            LoginType.SIMPLE -> {
                val result = send(source, "login.html", FormBody.Builder().add("user", username).add("password", password).build(), authorize = false)
                check(result.code in 200..399 && !result.cookies.isNullOrBlank()) { "Suwayomi登录失败" }
                credentials.set("${source.id}.cookie", result.cookies!!); credentials.remove(source.id); credentials.remove("${source.id}.refreshToken")
            }
            LoginType.UI -> {
                val result = send(source, "api/graphql", jsonBody(JSONObject().put("operationName", "USER_LOGIN").put("variables", JSONObject().put("username", username).put("password", password))
                    .put("query", "mutation USER_LOGIN(\$password: String!, \$username: String!) { login(input:{password:\$password,username:\$username}) { accessToken refreshToken __typename } }")), authorize = false)
                val login = JSONObject(result.text).optJSONObject("data")?.optJSONObject("login") ?: error("Suwayomi登录失败")
                credentials.set(source.id, "Bearer " + (login.stringOrNull("accessToken") ?: error("Suwayomi登录失败")))
                credentials.set("${source.id}.refreshToken", login.stringOrNull("refreshToken") ?: error("Suwayomi未返回刷新令牌")); credentials.remove("${source.id}.cookie")
            }
        }
        preferences?.set("login.username", username); preferences?.set("login.password", password)
        preferences?.set("login", if (type == LoginType.NONE) null else "logged_in")
    }
    fun request(source: SourceConfig, body: JSONObject): String {
        val initial = credentials.get(source.id); val initialCookie = credentials.get("${source.id}.cookie")
        var result = send(source, "api/graphql", jsonBody(body))
        if (unauthorized(result)) {
            synchronized(locks.getOrPut(source.id) { Any() }) {
                check(credentials.get(source.id) != initial || credentials.get("${source.id}.cookie") != initialCookie || recover(source)) { "Suwayomi登录已失效，请重新登录" }
            }
            result = send(source, "api/graphql", jsonBody(body))
        }
        check(result.code in 200..299) { "Suwayomi HTTP ${result.code}" }
        val json = JSONObject(result.text)
        json.optJSONArray("errors")?.takeIf { it.length() > 0 }?.let { error(it.optJSONObject(0)?.optString("message") ?: it.toString()) }
        return result.text
    }
    fun recover(source: SourceConfig): Boolean {
        val refresh = credentials.get("${source.id}.refreshToken")
        if (refresh != null) {
            val response = runCatching { send(source, "api/graphql", jsonBody(JSONObject().put("operationName", "USER_REFRESH").put("variables", JSONObject().put("refreshToken", refresh))
                .put("query", "mutation USER_REFRESH(\$refreshToken: String!) { refreshToken(input:{refreshToken:\$refreshToken}) { accessToken __typename } }"))) }.getOrNull()
            val token = response?.let { runCatching { JSONObject(it.text).optJSONObject("data")?.optJSONObject("refreshToken")?.stringOrNull("accessToken") }.getOrNull() }
            if (token != null) { credentials.set(source.id, "Bearer $token"); return true }
        }
        val username = preferences?.get("login.username")?.toString() ?: return false
        val password = preferences?.get("login.password")?.toString() ?: return false
        return runCatching { authenticate(source, username, password); true }.getOrDefault(false)
    }
    companion object { private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>() }
}
