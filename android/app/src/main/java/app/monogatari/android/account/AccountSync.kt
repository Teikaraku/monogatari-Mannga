package app.monogatari.android.account

import android.content.Context
import android.os.Build
import app.monogatari.android.data.AppRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom

enum class SyncPhase { IDLE, SYNCING, FAILED }

/** What the account page shows about sync. [error] is quota_exceeded, network, unauthorized, device_revoked or server. */
data class AccountSyncState(val phase: SyncPhase = SyncPhase.IDLE, val lastSynced: Long? = null, val error: String? = null)

/**
 * Runs sync rounds for the signed-in account: on launch, when the app goes to the background and
 * comes back, a few seconds after local changes, and on demand. Rounds never overlap.
 */
class AccountSync private constructor(private val repo: AppRepository, val account: AccountService) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ledger = SqliteSyncLedger(repo.context)
    private val running = Mutex()
    private var debounce: Job? = null
    private val stateFlow = MutableStateFlow(AccountSyncState(lastSynced = ledger.lastSynced))
    val state = stateFlow.asStateFlow()

    val device: SyncDevice by lazy { SyncDevice(deviceId(repo.context), deviceName()) }

    val api: SyncApi by lazy { SyncApi(AccountConfig.SYNC_BASE, { force -> account.accessToken(force) }, device) }
    fun myAccount() = MyAccountApi(account.issuerOrigin, { force -> account.accountApiToken(force) })

    private val adapters = listOf(CategoryAdapter, LibraryAdapter, ProgressAdapter, PrefAdapter { settings, old -> repo.settings.effectivePreferences(settings, old) })

    private var watching: Job? = null

    /** Restores the session, syncs once, and syncs again after each burst of local changes. Safe to call again (activity recreated). */
    fun start() {
        if (watching?.isActive == true) return
        if (account.signedIn) syncSoon(0)
        watching = scope.launch {
            // Settles first (a page turn saves progress), then only synced content counts as a change.
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            repo.state.debounce(1_500).map { s -> adapters.sumOf { a -> a.scan(s).hashCode() } }.distinctUntilChanged().drop(1).collect {
                if (!running.isLocked) schedule()
            }
        }
    }

    /** Syncs a few seconds after the last local change, so a burst of edits is one round. */
    fun schedule(delayMs: Long = 8_000) {
        if (!account.signedIn) return
        debounce?.cancel()
        // Only the wait is cancelled by a newer schedule; a round once started runs to the end.
        debounce = scope.launch { delay(delayMs); scope.launch { syncNow() } }
    }

    fun syncSoon(delayMs: Long = 0) = schedule(delayMs)

    /** Runs a round now, or right after the one in progress. */
    suspend fun syncNow(): SyncReport? {
        debounce?.cancel()
        if (!account.signedIn) return null
        return running.withLock { run() }
    }

    private suspend fun run(): SyncReport? {
        val user = account.user.value ?: return null
        stateFlow.value = stateFlow.value.copy(phase = SyncPhase.SYNCING, error = null)
        return try {
            val engine = SyncEngine(ledger, object : SyncRemote {
                override suspend fun pull(since: Long) = api.pull(since)
                override suspend fun push(records: List<SyncRecord>) = api.push(records)
            }, user.id, adapters, { repo.state.value }, { block -> var result = repo.state.value; repo.change { s -> block(s).also { result = it } }; result })
            val report = engine.sync()
            val now = System.currentTimeMillis()
            ledger.lastSynced = now
            stateFlow.value = AccountSyncState(lastSynced = now)
            report
        } catch (e: SyncApiException) {
            if (e.code == "device_revoked") {
                // Signed out from another device's list. Local data stays; signing in again works.
                account.signOut()
                stateFlow.value = AccountSyncState(SyncPhase.FAILED, error = "device_revoked")
            } else stateFlow.value = stateFlow.value.copy(phase = SyncPhase.FAILED, error = e.code)
            null
        } catch (e: AccountException) {
            stateFlow.value = stateFlow.value.copy(phase = SyncPhase.FAILED, error = if (e.code == "signed_out") "unauthorized" else "network")
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            // A round cut short (sign-out, a newer schedule) leaves the last result shown, not "syncing".
            stateFlow.value = stateFlow.value.copy(phase = if (stateFlow.value.error == null) SyncPhase.IDLE else SyncPhase.FAILED)
            throw e
        } catch (e: Exception) {
            app.monogatari.android.sources.SourceLogStore.get(repo.context).add("Sync: ${e.message}", "ERROR")
            stateFlow.value = stateFlow.value.copy(phase = SyncPhase.FAILED, error = "server")
            null
        }
    }

    /** Called once a sign-in returned its authorization code. */
    suspend fun completeSignIn(code: String, verifier: String) { account.completeSignIn(code, verifier); syncNow() }

    suspend fun signInWithBrowser(provider: String) { account.signInWithBrowser(provider); syncNow() }

    /** Signs out here, sending what is still pending first; local data stays. */
    suspend fun signOut() {
        if (account.signedIn) withTimeoutOrNull(15_000) { runCatching { syncNow() } }
        debounce?.cancel()
        account.signOut()
        stateFlow.value = AccountSyncState()
    }

    /**
     * Deletes this app's data from the account (synced records and the device list) and signs out here.
     * The 物语 account itself, used by the website and the other apps, is kept.
     */
    suspend fun deleteAppData() {
        api.erase()
        // Forget what was agreed, so this device does not push it all back on the next sign-in.
        ledger.clear()
        account.signOut()
        stateFlow.value = AccountSyncState()
    }

    companion object {
        @Volatile private var instance: AccountSync? = null
        fun get(context: Context): AccountSync = instance ?: synchronized(this) {
            instance ?: AccountSync(AppRepository.get(context), AccountService.get(context)).also { instance = it }
        }

        /** A random id for this install, kept until the app's data is cleared. */
        fun deviceId(context: Context): String {
            val prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE)
            prefs.getString("device", null)?.takeIf { it.length >= 8 }?.let { return it }
            val id = ByteArray(8).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
            prefs.edit().putString("device", id).apply()
            return id
        }

        /** "Google Pixel 9"; emulators and some builds report the manufacturer as unknown. */
        fun deviceName(manufacturer: String = Build.MANUFACTURER.orEmpty(), model: String = Build.MODEL.orEmpty()): String {
            val brand = manufacturer.takeUnless { it.isBlank() || it.equals("unknown", true) }?.replaceFirstChar { it.uppercase() }
            return when {
                model.isBlank() -> brand ?: "Android"
                brand == null || model.lowercase().startsWith(brand.lowercase()) -> model
                else -> "$brand $model"
            }
        }
    }
}
