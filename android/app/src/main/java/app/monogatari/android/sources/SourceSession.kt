package app.monogatari.android.sources

import app.monogatari.android.data.Manga
import okhttp3.Cookie
import okhttp3.Request
import org.json.JSONObject
import org.json.JSONArray

/** Common source UI contract; native server sources and Wasm sources supply the same home/filters/settings model. */
interface SourceSession {
    val sourceId: String
    val legacy: Boolean
    val exports: Set<String>
    val preferences: SourcePreferences
    fun search(query: String, page: Int = 1, filters: List<JSONObject> = emptyList()): Pair<List<Manga>, Boolean>
    fun listing(listing: JSONObject, page: Int = 1): Pair<List<Manga>, Boolean>
    fun home(): JSONObject
    fun filters(): List<JSONObject>
    fun settings(): List<JSONObject>
    fun listings(): List<JSONObject>
    fun manga(raw: JSONObject): Manga
    fun notify(key: String)
    fun basicLogin(key: String, username: String, password: String): Boolean
    fun webLogin(key: String, cookies: Map<String, String>): Boolean
    fun baseUrl(): String
    fun importBrowserCookies(url: String, userAgent: String, cookies: String)
    fun importCookieRecords(url: String, cookies: List<Cookie>)
    fun clearCookies()
    fun fetch(request: Request): HostResponse
    fun resetSettings() { preferences.keys().forEach { preferences.set(it, null) }; preferences.initialize(JSONArray(settings())) }
    fun listingHome(listing: JSONObject): JSONObject? = null
}
