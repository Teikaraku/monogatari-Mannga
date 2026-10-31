package app.monogatari.android.account

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end check against the live identity center and manga sync service. Skipped unless MO_E2E_EMAIL
 * and MO_E2E_PASSWORD name a throwaway account (server/deploy/logto-account-center.py --test-user create).
 * Never enters a wrong password: Logto locks an account after five failures in an hour.
 *   MO_E2E_EMAIL=... MO_E2E_PASSWORD=... ./gradlew testDebugUnitTest --tests '*AccountLiveTest*'
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccountLiveTest {
    private val email = System.getenv("MO_E2E_EMAIL")
    private val password = System.getenv("MO_E2E_PASSWORD")

    @Test fun signsInNativelyEditsTheProfileSyncsAndSignsOut() = runBlocking {
        assumeTrue("set MO_E2E_EMAIL and MO_E2E_PASSWORD", email != null && password != null)
        val vault = MemoryVault()
        val account = AccountService(vault, browser = { fail("browser opened"); "" })
        val interaction = account.interaction()
        interaction.start("SignIn")
        interaction.identify(interaction.verifyPassword(email!!, password!!))
        val user = account.completeSignIn(interaction.submit(), interaction.codeVerifier)
        assertEquals(email, user.email)

        // The sync token is a JWT for this app's resource only.
        val claims = jwtClaims(account.accessToken())
        assertEquals(AccountConfig.RESOURCE, claims.optString("aud"))
        assertEquals(AccountConfig.ISSUER, claims.optString("iss"))

        val api = MyAccountApi(account.issuerOrigin, { force -> account.accountApiToken(force) })
        val before = api.profile()
        assertEquals(email, before.email); assertTrue(before.hasPassword)
        assertEquals("E2E Manga", api.update(name = "E2E Manga").name)
        api.update(name = before.name ?: "E2E Test")
        assertTrue(api.verifyPassword(password).isNotEmpty())

        // Sync and Account API refreshes share the rotating refresh token.
        assertTrue(account.accessToken(forceRefresh = true).isNotEmpty())
        assertTrue(account.accountApiToken(forceRefresh = true).isNotEmpty())
        assertTrue(account.accessToken(forceRefresh = true).isNotEmpty())

        val phone = SyncDevice("e2e-phone-0001", "E2E 手机")
        val tablet = SyncDevice("e2e-tablet-0002", "E2E Tablet", "ios")
        val sync = SyncApi(AccountConfig.SYNC_BASE, { account.accessToken(it) }, phone)
        val other = SyncApi(AccountConfig.SYNC_BASE, { account.accessToken(it) }, tablet)

        // Records round-trip; a novel kind is refused by the manga service.
        val key = syncKey("copy2026", "e2e-manga")
        val seq = sync.push(listOf(SyncRecord("library", key, 1_000, JSONObject().put("source", "copy2026").put("manga", "e2e-manga").put("title", "E2E"))))!!
        val page = other.pull(seq - 1)
        assertEquals(listOf(key), page.items.map { it.key })
        assertEquals("server", (runCatching { sync.push(listOf(SyncRecord("book", "x", 1, JSONObject()))) }.exceptionOrNull() as SyncApiException).code)

        // Avatar: uploaded here, set on the shared profile, removed again.
        val png = java.io.ByteArrayOutputStream().also { out ->
            android.graphics.Bitmap.createBitmap(640, 400, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFFF2D55.toInt()) }
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }.toByteArray()
        val url = sync.uploadAvatar(png)
        assertTrue(url, url.startsWith("https://manga.monogatari.top/sync/avatars/"))
        assertEquals(url, api.update(avatar = url).avatar)
        assertNull(api.update(avatar = "").avatar)

        // Devices: both listed with readable names; removing the tablet signs it out.
        other.status()
        val devices = sync.devices()
        assertTrue(devices.any { it.id == "e2e-phone-0001" && it.name == "E2E 手机" && it.current })
        assertTrue(devices.any { it.id == "e2e-tablet-0002" && !it.current })
        sync.removeDevice("e2e-tablet-0002")
        assertEquals("device_revoked", (runCatching { other.status() }.exceptionOrNull() as SyncApiException).code)

        // Deleting the app's data empties the account here; the next pull from an old cursor resets.
        sync.erase()
        assertTrue(sync.pull(seq).reset)
        sync.erase()
        account.signOut()
        assertFalse(account.signedIn)
        assertNull(vault.value)
    }
}
