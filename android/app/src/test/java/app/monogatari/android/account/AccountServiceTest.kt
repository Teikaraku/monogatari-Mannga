package app.monogatari.android.account

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class MemoryVault(var value: String? = null) : AccountVault {
    override fun read() = value
    override fun write(value: String) { this.value = value }
    override fun clear() { value = null }
}

private fun idToken(sub: String = "user-1", email: String = "a@example.com", name: String? = "Alice"): String {
    val enc = Base64.getUrlEncoder().withoutPadding()
    val claims = JSONObject().put("sub", sub).put("email", email).apply { name?.let { put("name", it) } }
    return enc.encodeToString("{\"alg\":\"none\"}".toByteArray()) + "." + enc.encodeToString(claims.toString().toByteArray()) + ".sig"
}

private fun form(request: RecordedRequest): Map<String, String> = request.body.readUtf8().split('&').filter(String::isNotEmpty).associate {
    URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccountServiceTest {
    private lateinit var server: MockWebServer
    private val tokenForms = CopyOnWriteArrayList<Map<String, String>>()
    private val revoked = CopyOnWriteArrayList<Map<String, String>>()
    private val rotation = AtomicInteger()
    @Volatile private var refuseRefresh = false
    private var clock = 1_000_000L

    private val issuer get() = server.url("/oidc").toString().trimEnd('/')

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/oidc/token" -> {
                    val body = form(request); tokenForms += body
                    when {
                        body["grant_type"] == "authorization_code" -> MockResponse().setBody(JSONObject().put("access_token", "sync-0").put("expires_in", 3600)
                            .put("refresh_token", "r0").put("id_token", idToken()).toString())
                        refuseRefresh -> MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}""")
                        else -> {
                            // Rotation: only the newest refresh token is accepted.
                            val expected = "r${rotation.get()}"
                            if (body["refresh_token"] != expected) MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}""")
                            else {
                                val next = rotation.incrementAndGet()
                                Thread.sleep(30)
                                val access = if (body.containsKey("resource")) "sync-$next" else "opaque-$next"
                                MockResponse().setBody(JSONObject().put("access_token", access).put("expires_in", 3600).put("refresh_token", "r$next").toString())
                            }
                        }
                    }
                }
                "/oidc/token/revocation" -> { revoked += form(request); MockResponse().setResponseCode(200) }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun service(vault: AccountVault = MemoryVault(), browser: BrowserLauncher? = null) =
        AccountService(vault, browser = browser, now = { clock }, issuer = issuer, clientId = "client-1", resource = "https://manga.example/sync", redirectUri = "app.test://callback")

    @Test fun signInStoresTheSessionAndRestoresItOnNextLaunch() = runBlocking {
        val vault = MemoryVault()
        val user = service(vault).completeSignIn("code-1", "verifier-1")
        assertEquals("Alice", user.displayName)
        val exchange = tokenForms.single()
        assertEquals(mapOf("grant_type" to "authorization_code", "code" to "code-1", "redirect_uri" to "app.test://callback",
            "code_verifier" to "verifier-1", "client_id" to "client-1", "resource" to "https://manga.example/sync"), exchange)
        val restored = service(vault)
        assertTrue(restored.signedIn)
        assertEquals("user-1", restored.user.value?.id)
        assertEquals("sync-0", restored.accessToken())
    }

    @Test fun syncAndAccountApiRefreshesRunOneAfterAnotherAndKeepTheRotatedToken() = runBlocking {
        val vault = MemoryVault()
        val account = service(vault)
        account.completeSignIn("c", "v")
        clock += 3_600_000 // The sync token has expired.
        val results = (0 until 4).map { i -> async(kotlinx.coroutines.Dispatchers.IO) { if (i % 2 == 0) account.accessToken() else account.accountApiToken() } }.awaitAll()
        assertTrue(account.signedIn)
        // Every refresh presented the refresh token the previous one returned.
        val refreshes = tokenForms.filter { it["grant_type"] == "refresh_token" }
        refreshes.forEachIndexed { i, f -> assertEquals("r$i", f["refresh_token"]) }
        // Sync refreshes carry the resource; the Account API one does not.
        assertTrue(refreshes.any { it.containsKey("resource") } && refreshes.any { !it.containsKey("resource") })
        assertTrue(results.filterIndexed { i, _ -> i % 2 == 0 }.all { it.startsWith("sync-") })
        assertTrue(results.filterIndexed { i, _ -> i % 2 == 1 }.all { it.startsWith("opaque-") })
        assertEquals("r${rotation.get()}", JSONObject(vault.value!!).getJSONObject("tokens").getString("refresh_token"))
    }

    @Test fun aRefusedRefreshSignsOut() = runBlocking {
        val vault = MemoryVault()
        val account = service(vault)
        account.completeSignIn("c", "v")
        refuseRefresh = true
        val error = runCatching { account.accessToken(forceRefresh = true) }.exceptionOrNull()
        assertEquals("signed_out", (error as AccountException).code)
        assertFalse(account.signedIn)
        assertNull(account.user.value)
        assertNull(vault.value)
    }

    @Test fun signOutRevokesTheRefreshToken() = runBlocking {
        val vault = MemoryVault()
        val account = service(vault)
        account.completeSignIn("c", "v")
        account.signOut()
        assertEquals(mapOf("token" to "r0", "token_type_hint" to "refresh_token", "client_id" to "client-1"), revoked.single())
        assertNull(vault.value)
        assertFalse(account.signedIn)
    }

    @Test fun browserSignInUsesPkceAndChecksState() = runBlocking {
        var opened: String? = null
        val account = service(browser = { url ->
            opened = url
            val query = URI(url).rawQuery.split('&').associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            "app.test://callback?code=browser-code&state=${query["state"]}"
        })
        account.signInWithBrowser("github")
        val query = URI(opened!!).rawQuery.split('&').associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertEquals("social:github-universal", query["direct_sign_in"])
        assertEquals("S256", query["code_challenge_method"])
        assertEquals("openid profile email offline_access", query["scope"])
        assertEquals("https://manga.example/sync", query["resource"])
        val verifier = tokenForms.single()["code_verifier"]!!
        assertTrue(verifier.length >= 43)
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        assertEquals(challenge, query["code_challenge"])

        // A redirect answering another request is refused.
        val forged = service(browser = { "app.test://callback?code=x&state=someone-else" })
        assertEquals("invalid_state", (runCatching { forged.signInWithBrowser("google") }.exceptionOrNull() as AccountException).code)
        val cancelled = service(browser = { url -> val state = URI(url).rawQuery.split('&').first { it.startsWith("state=") }.substringAfter('=')
            "app.test://callback?error=access_denied&state=$state" })
        assertEquals("cancelled", (runCatching { cancelled.signInWithBrowser("google") }.exceptionOrNull() as AccountException).code)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AuthInteractionTest {
    @Test fun proofOfWorkFindsTheFirstNonceWithEnoughZeroBits() {
        val nonce = solveProofOfWork("salt-abc", 12)
        val hash = MessageDigest.getInstance("SHA-256").digest("salt-abc$nonce".toByteArray())
        assertTrue(leadingZeroBits(hash, 12))
        // It is the first such nonce.
        for (n in 0 until nonce.toInt()) assertFalse(leadingZeroBits(MessageDigest.getInstance("SHA-256").digest("salt-abc$n".toByteArray()), 12))
        assertTrue(leadingZeroBits(byteArrayOf(0, 0x0F), 12))
        assertFalse(leadingZeroBits(byteArrayOf(0, 0x10), 12))
    }

    @Test fun signInDrivesTheExperienceApiWithCookiesProofOfWorkAndRedirects() = runBlocking {
        val server = MockWebServer()
        val seen = CopyOnWriteArrayList<RecordedRequest>()
        lateinit var state: String
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                val path = request.path!!
                return when {
                    path.startsWith("/oidc/auth?") -> {
                        state = URLDecoder.decode(path.substringAfter("state=").substringBefore('&'), "UTF-8")
                        MockResponse().setResponseCode(303).setHeader("Location", "/sign-in")
                            .addHeader("Set-Cookie", "_interaction=abc; path=/; httponly").addHeader("Set-Cookie", "_interaction.sig=sig; path=/")
                    }
                    path == "/api/experience" -> MockResponse().setResponseCode(204)
                    path == "/gate/challenge" -> MockResponse().setBody("""{"challenge":"ch1","salt":"s1","bits":8}""")
                    path == "/api/experience/verification/password" ->
                        if (request.getHeader("X-Mo-Pow") == null) MockResponse().setResponseCode(403).setHeader("X-Gate-Reason", "pow")
                        else MockResponse().setBody("""{"verificationId":"ver-1"}""")
                    path == "/api/experience/identification" -> MockResponse().setResponseCode(204)
                    path == "/api/experience/submit" -> MockResponse().setBody("""{"redirectTo":"${server.url("/oidc/auth/xyz")}"}""")
                    path == "/oidc/auth/xyz" -> MockResponse().setResponseCode(303).setHeader("Location", "/oidc/consent")
                        .addHeader("Set-Cookie", "_interaction=; expires=Thu, 01 Jan 1970 00:00:00 GMT")
                    path == "/oidc/consent" -> MockResponse().setResponseCode(303).setHeader("Location", "app.test://callback?code=the-code&state=$state")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        try {
            val issuer = server.url("/oidc").toString().trimEnd('/')
            var solved: Pair<String, Int>? = null
            val interaction = AuthInteraction(issuer, "client-1", "https://manga.example/sync", "app.test://callback",
                solve = { salt, bits -> solved = salt to bits; solveProofOfWork(salt, bits) })
            interaction.start("SignIn")
            val verification = interaction.verifyPassword("a@example.com", "pw")
            interaction.identify(verification)
            assertEquals("the-code", interaction.submit())
            assertEquals("s1" to 8, solved)

            val byPath = seen.associateBy { it.path!!.substringBefore('?') }
            assertEquals("""{"interactionEvent":"SignIn"}""", byPath["/api/experience"]!!.body.readUtf8())
            val password = byPath["/api/experience/verification/password"]!!
            assertEquals("a@example.com", password.getHeader("X-Mo-Email"))
            assertTrue(password.getHeader("X-Mo-Pow")!!.startsWith("ch1:"))
            assertEquals("_interaction=abc; _interaction.sig=sig", password.getHeader("Cookie"))
            assertEquals("""{"verificationId":"ver-1"}""", byPath["/api/experience/identification"]!!.body.readUtf8())
            // The cleared cookie is no longer sent on the next hop.
            assertEquals("_interaction.sig=sig", byPath["/oidc/consent"]!!.getHeader("Cookie"))
            assertTrue(byPath["/oidc/auth"]!!.path!!.contains("prompt=consent"))
        } finally { server.shutdown() }
    }

    @Test fun failuresCarryLogtoCodesAndGateReasons() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path!!.substringBefore('?')) {
                "/oidc/auth" -> MockResponse().setResponseCode(303).addHeader("Set-Cookie", "_interaction=abc")
                "/api/experience" -> MockResponse().setResponseCode(204)
                "/gate/challenge" -> MockResponse().setResponseCode(429).setHeader("X-Gate-Reason", "rate").setHeader("Retry-After", "42")
                "/api/experience/verification/verification-code/verify" -> MockResponse().setResponseCode(400).setBody("""{"code":"verification_code.code_mismatch"}""")
                "/api/experience/profile" -> MockResponse().setResponseCode(422).setBody("""{"code":"password.rejected","data":[{"code":"password_rejected.too_short"}]}""")
                "/api/experience/submit" -> MockResponse().setBody("""{"redirectTo":"app.test://callback?code=c&state=wrong"}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        try {
            val interaction = AuthInteraction(server.url("/oidc").toString().trimEnd('/'), "c", "r", "app.test://callback")
            interaction.start("Register")
            val gate = runCatching { interaction.sendCode("Register", "a@b.c") }.exceptionOrNull() as AuthFailure
            assertEquals("gate_rate", gate.code); assertEquals(42, gate.retryAfter)
            assertEquals("verification_code.code_mismatch", (runCatching { interaction.verifyCode("a@b.c", "v", "000000") }.exceptionOrNull() as AuthFailure).code)
            val weak = runCatching { interaction.setPassword("x") }.exceptionOrNull() as AuthFailure
            assertEquals(listOf("password_rejected.too_short"), weak.passwordIssues)
            assertEquals("invalid_state", (runCatching { interaction.submit() }.exceptionOrNull() as AuthFailure).code)
        } finally { server.shutdown() }
    }
}
