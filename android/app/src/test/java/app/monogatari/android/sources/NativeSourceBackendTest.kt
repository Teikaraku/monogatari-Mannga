package app.monogatari.android.sources

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.monogatari.android.data.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NativeSourceBackendTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private class Memory : SourcePreferenceBackend {
        val values = mutableMapOf<String, String>()
        override fun get(key: String) = values[key]
        override fun set(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
        override fun keys() = values.keys.toSet()
    }
    private fun http() = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
    private fun source(server: MockWebServer, type: String) = SourceConfig("test-$type", type, type, server.url("/base/").toString().trimEnd('/'))
    private fun backend(source: SourceConfig, memory: Memory = Memory()): BuiltInBackend {
        val client = SourceClient(SecretStore(context), providedCredentials = memory)
        return BuiltInBackend(AppRepository(context), source, client, SourcePreferences(context, source.id, memory))
    }
    @Test fun nativeSettingsKeepOriginalLocalizationKeysAndPreferenceDefaults() {
        val source = SourceConfig("settings-contract", "Fixture", "komga", "")
        val session = backend(source)
        val groups = session.settings()
        assertEquals("SOURCE_NAME", groups.first().getString("title"))
        val toggle = groups.flatMap { it.optJSONArray("items").objects() }.single { it.optString("key") == "useChapters" }
        assertEquals("USE_CHAPTERS", toggle.getString("title"))
        assertEquals("USE_CHAPTERS_TEXT", toggle.getString("subtitle"))
        assertEquals(false, toggle.getBoolean("default")); assertEquals(false, session.preferences.get("useChapters"))
        assertEquals("chapters_changed", toggle.getString("notification"))
        assertEquals("LOCAL_FILE_IMPORT_TEXT", backend(source.copy(id = "local-contract", type = "local")).settings().single().getString("text"))
    }
    @Test fun kavitaPluginAuthenticationAndRefreshRetainBasePathAndApiKey() {
        MockWebServer().use { server ->
            val s = source(server, "kavita"); val memory = Memory(); val client = KavitaClient(SecretStore(context), http(), memory)
            server.enqueue(MockResponse().setBody("""{"token":"old","refreshToken":"refresh","authKeys":[{"name":"opds","key":"image-key"}]}"""))
            client.authenticate(s, "", "key with + / ?")
            val login = server.takeRequest(); assertEquals("POST", login.method); assertEquals("/base/api/Plugin/authenticate", login.requestUrl!!.encodedPath); assertEquals("key with + / ?", login.requestUrl!!.queryParameter("apiKey")); assertNull(login.getHeader("Authorization"))
            server.enqueue(MockResponse().setResponseCode(401).setBody("{}")); server.enqueue(MockResponse().setBody("""{"token":"new","refreshToken":"refresh2"}""")); server.enqueue(MockResponse().setBody("[]"))
            assertEquals("[]", client.request(s, "api/library/libraries"))
            assertEquals("Bearer old", server.takeRequest().getHeader("Authorization")); val refresh = server.takeRequest(); assertEquals("/base/api/account/refresh-token", refresh.path); assertNull(refresh.getHeader("Authorization")); assertEquals("refresh", JSONObject(refresh.body.readUtf8()).getString("refreshToken")); assertEquals("Bearer new", server.takeRequest().getHeader("Authorization")); assertEquals("image-key", client.apiKey(s))
        }
    }
    @Test fun kavitaOidcUsesAccountValidationAndCookieChunksWithoutBearer() {
        MockWebServer().use { server ->
            val s = source(server, "kavita"); val memory = Memory().apply { set(s.id, "Bearer stale") }; val client = KavitaClient(SecretStore(context), http(), memory)
            server.enqueue(MockResponse().setBody("""{"authKeys":[{"name":"opds","key":"oidc-key"}]}"""))
            assertTrue(client.webAuthenticate(s, mapOf(".AspNetCore.Cookies" to "chunks-1", ".AspNetCore.CookiesC1" to "part", "foreign" to "secret")))
            val req = server.takeRequest(); assertEquals("GET", req.method); assertEquals("/base/api/account", req.path); assertNull(req.getHeader("Authorization")); assertEquals(".AspNetCore.Cookies=chunks-1; .AspNetCore.CookiesC1=part", req.getHeader("Cookie")); assertNull(memory.get(s.id)); assertEquals("oidc-key", client.apiKey(s))
            server.enqueue(MockResponse().setBody("[]")); client.libraries(s); assertEquals(req.getHeader("Cookie"), server.takeRequest().getHeader("Cookie"))
        }
    }
    @Test fun suwayomiJwtLoginAndUnauthorizedGraphqlRecoverAccessToken() {
        MockWebServer().use { server ->
            val s = source(server, "suwayomi"); val memory = Memory(); val preferences = SourcePreferences(context, s.id, memory); val client = SuwayomiClient(http(), memory, preferences)
            server.enqueue(MockResponse().setBody("<html></html>")); server.enqueue(MockResponse().setBody("""{"data":{"settings":{"authMode":"UI"}}}""")); server.enqueue(MockResponse().setBody("""{"data":{"login":{"accessToken":"old","refreshToken":"refresh"}}}"""))
            client.authenticate(s, "reader", "pass"); server.takeRequest(); server.takeRequest(); val login = server.takeRequest(); assertEquals("USER_LOGIN", JSONObject(login.body.readUtf8()).getString("operationName")); assertNull(login.getHeader("Authorization")); assertEquals("reader", preferences.get("login.username"))
            server.enqueue(MockResponse().setBody("""{"errors":[{"message":"Unauthorized"}]}""")); server.enqueue(MockResponse().setBody("""{"data":{"refreshToken":{"accessToken":"new"}}}""")); server.enqueue(MockResponse().setBody("""{"data":{"mangas":{"nodes":[]}},"errors":[]}"""))
            client.request(s, JSONObject().put("query", "query { mangas { nodes { id } } }")); assertEquals("Bearer old", server.takeRequest().getHeader("Authorization")); val refresh = JSONObject(server.takeRequest().body.readUtf8()); assertEquals("USER_REFRESH", refresh.getString("operationName")); assertEquals("refresh", refresh.getJSONObject("variables").getString("refreshToken")); assertEquals("Bearer new", server.takeRequest().getHeader("Authorization"))
        }
    }
    @Test fun suwayomiSupportsBasicSimpleAndNoAuthentication() {
        for (mode in listOf("BASIC", "SIMPLE", "NONE")) MockWebServer().use { server ->
            val s = source(server, "suwayomi"); val memory = Memory(); val client = SuwayomiClient(http(), memory, null)
            when(mode) { "BASIC" -> { server.enqueue(MockResponse().setResponseCode(401)); server.enqueue(MockResponse().setBody("OK")) }; "SIMPLE" -> { server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/login.html")); server.enqueue(MockResponse().setResponseCode(302).addHeader("Set-Cookie", "session=one; Path=/")) }; else -> { server.enqueue(MockResponse().setBody("OK")); server.enqueue(MockResponse().setBody("""{"data":{"settings":{"authMode":"NONE"}}}""")) } }
            client.authenticate(s, "user", "pass"); server.takeRequest(); val login = server.takeRequest()
            when(mode) { "BASIC" -> assertTrue(login.getHeader("Authorization")!!.startsWith("Basic ")); "SIMPLE" -> { assertEquals("/base/login.html", login.path); assertEquals("user=user&password=pass", login.body.readUtf8()); assertEquals("session=one", memory.get("${s.id}.cookie")); assertNull(memory.get(s.id)) }; else -> assertNull(memory.get(s.id)) }
        }
    }
    @Test fun suwayomiSearchSendsAllFiltersAndSortsUnreadLocally() {
        MockWebServer().use { server ->
            val s = source(server, "suwayomi"); val b = backend(s)
            server.enqueue(MockResponse().setBody("""{"data":{"mangas":{"nodes":[{"id":1,"title":"Low","unreadCount":1,"genre":[],"status":"ON_HIATUS"},{"id":2,"title":"High","unreadCount":8,"genre":[]}]}}}"""))
            val filters = listOf(JSONObject("""{"id":"sort","type":1,"index":0,"ascending":false}"""), JSONObject("""{"id":"artist","type":0,"value":"artist"}"""), JSONObject("""{"id":"category","type":3,"value":"0"}"""), JSONObject("""{"id":"genre","type":4,"included":["Action"],"excluded":["Horror"]}"""), JSONObject("""{"id":"status","type":4,"included":["ONGOING"],"excluded":["CANCELLED"]}"""))
            val result = b.search("find", 1, filters); assertEquals(listOf("High", "Low"), result.first.map { it.title }); assertEquals(4, result.first.last().status)
            val request = JSONObject(server.takeRequest().body.readUtf8()); val variables = request.getJSONObject("variables"); val text = variables.getJSONObject("filter").toString(); assertTrue(text.contains("description")); assertTrue(text.contains("artist")); assertTrue(text.contains("isNull")); assertTrue(text.contains("notIncludesInsensitive")); assertTrue(text.contains("notEqualToAny")); assertFalse(variables.has("orderBy"))
        }
    }
    @Test fun komgaAuthorAndExcludedReleaseYearPreserveLastDayOfYear() {
        MockWebServer().use { server ->
            val b = backend(source(server, "komga"))
            server.enqueue(MockResponse().setBody("""{"content":[{"name":"Writer","role":"writer"}]}""")); server.enqueue(MockResponse().setBody("""{"content":[],"totalPages":0}"""))
            b.search("title", 1, listOf(JSONObject("""{"id":"author","type":0,"value":"Writer"}"""), JSONObject("""{"id":"release_date","type":4,"included":["2025"],"excluded":["2024"]}""")))
            val authors = server.takeRequest(); assertEquals("Writer", authors.requestUrl!!.queryParameter("search")); assertEquals("writer", authors.requestUrl!!.queryParameter("role"))
            val list = server.takeRequest(); val body = JSONObject(list.body.readUtf8()); assertEquals("title", body.getString("fullTextSearch")); val conditions = body.toString(); assertTrue(conditions.contains("2026-01-01")); assertTrue(conditions.contains("2024-12-31")); assertTrue(conditions.contains("Writer")); assertTrue(conditions.contains("isNull"))
        }
    }
    @Test fun encryptedPreferenceBackupAndRequiresPreserveTypesAndUnknownKeys() {
        val memory = Memory(); val preferences = SourcePreferences(context, "one", memory)
        preferences.set("unlisted.source.value", 1.5f); preferences.set("bytes", byteArrayOf(1, 2, 3)); preferences.set("flag", false); preferences.set("login", "logged_in")
        val exported = preferences.exportEncoded { !it.startsWith("login") }; val copy = SourcePreferences(context, "two", memory); copy.importEncoded(exported, true)
        assertEquals(1.5f, copy.get("unlisted.source.value")); assertArrayEquals(byteArrayOf(1, 2, 3), copy.get("bytes") as ByteArray); assertEquals(false, copy.get("flag")); assertNull(copy.get("login")); assertEquals(3, copy.keys().size)
        assertFalse(app.monogatari.android.ui.settingRequires(copy, "flag")); assertTrue(app.monogatari.android.ui.settingRequires(copy, "flag == 0 && true"))
        assertThrows(IllegalArgumentException::class.java) { copy.importEncoded(JSONObject("""{"invalid":{"kind":"wrong","value":1}}"""), true) }; assertEquals(3, copy.keys().size)
    }
    @Test fun catalogRetainsUnknownFieldsAndChecksThePortedAppContractVersion() {
        MockWebServer().use { server ->
            val input = """{"name":"Catalog","sources":[{"id":"source","name":"Source","lang":"en","nsfw":2,"version":3,"file":"source.aix","minAppVersion":"0.8.9","maxAppVersion":"0.9.1","altNames":["Alias"],"baseURL":"https://example.test","futureValue":{"enabled":true}}]}"""
            server.enqueue(MockResponse().setBody(input)); val list = SourceClient(SecretStore(context), providedCredentials = Memory()).sourceList(server.url("/list/index.json").toString()); val source = list.entries.single()
            assertEquals(2, source.contentRating); assertEquals(listOf("Alias"), source.altNames); assertEquals(server.url("/list/sources/source.aix").toString(), source.downloadUrl); assertTrue(SourceCompatibility.supports(source)); assertTrue(JSONObject(source.metadata).getJSONObject("futureValue").getBoolean("enabled"))
            val copy = SourceList.parse(list.json()).entries.single(); assertEquals(source, copy); assertFalse(SourceCompatibility.supports(source.copy(minAppVersion="0.10"))); assertFalse(SourceCompatibility.supports(source.copy(maxAppVersion="0.8"))); assertTrue(SourceCompatibility.compare("0.9", "0.9.0") == 0)
        }
    }
}
