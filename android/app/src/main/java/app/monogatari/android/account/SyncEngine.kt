package app.monogatari.android.account

import app.monogatari.android.data.AppState
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** A synced item as it is on this device right now. */
data class LocalItem(
    val key: String, val data: JSONObject,
    /** When it last changed here, if the data records it; otherwise the sync time is used. */
    val updated: Long?,
)

/** How one kind of record is read from and written to the app's state. All functions are pure. */
interface SyncAdapter {
    val kind: String
    /** Kinds whose records must be applied before this one (a title's progress needs the title). */
    val dependsOn: List<String> get() = emptyList()
    /** Whether an item missing here that was synced before is pushed as deleted. */
    val pushesDeletions: Boolean get() = true
    fun scan(state: AppState): List<LocalItem>
    /** The state with [record] applied, or null when this device cannot hold it yet. */
    fun apply(state: AppState, record: SyncRecord): AppState?
    /** The state without the item behind [key]. */
    fun remove(state: AppState, key: String): AppState
}

/** The content last agreed with the server for one record. */
data class Agreed(val hash: String?, val updated: Long)

/** What this device remembers about its sync with the signed-in account. */
interface SyncLedger {
    var owner: String?
    var cursor: Long
    fun agreed(kind: String): Map<String, Agreed>
    fun parked(): List<SyncRecord>
    /** Runs [ops] as one transaction. */
    fun write(ops: List<LedgerOp>)
    fun clear()
}

sealed interface LedgerOp {
    data class Agree(val kind: String, val key: String, val hash: String?, val updated: Long) : LedgerOp
    data class Forget(val kind: String, val key: String) : LedgerOp
    data class Park(val record: SyncRecord) : LedgerOp
    data class Unpark(val kind: String, val key: String) : LedgerOp
}

/** What the engine needs from the sync service. */
interface SyncRemote {
    suspend fun pull(since: Long): SyncPage
    suspend fun push(records: List<SyncRecord>): Long?
}

data class SyncReport(val pulled: Int = 0, val pushed: Int = 0, val applied: Int = 0, val waiting: Int = 0)

/** A stable hash of [data]: keys sorted at every level, 1.0 and 1 alike (they are after a JSON round trip). */
fun contentHash(data: JSONObject?): String {
    if (data == null) return ""
    val digest = MessageDigest.getInstance("SHA-1").digest(canonical(data).toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

private fun canonical(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "null"
    is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.opt(it)) }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.opt(it)) }
    is Boolean -> value.toString()
    is Int, is Long, is Short, is Byte -> value.toString()
    is Number -> value.toDouble().let { if (it == Math.floor(it) && Math.abs(it) < 1e15) it.toLong().toString() else it.toString() }
    else -> JSONObject.quote(value.toString())
}

/** Record keys: the hex SHA-256 of the identifying parts, so any characters a source uses in ids fit. */
fun syncKey(vararg parts: String): String =
    MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\n").toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/**
 * Two-way, record-level sync of the library with the account.
 *
 * The ledger remembers, per record, the content hash and update time last agreed with the server.
 * A round pulls what changed since the cursor, applies it (a pulled record wins over the local item
 * unless the item changed here later), then pushes every local item whose hash differs from the
 * agreed one, and a deletion for each agreed item that is gone. The server keeps the newest write per
 * record, so devices converge whatever order they sync in.
 */
class SyncEngine(
    private val ledger: SyncLedger,
    private val remote: SyncRemote,
    /** The signed-in user; sync state belongs to them, so another account starts over. */
    private val account: String,
    private val adapters: List<SyncAdapter>,
    private val read: () -> AppState,
    /** Applies the transform atomically to the app's state and returns the new state. */
    private val commit: suspend ((AppState) -> AppState) -> AppState,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val byKind = adapters.associateBy { it.kind }

    suspend fun sync(): SyncReport {
        claim()
        var cursor = ledger.cursor
        val pulled = mutableListOf<SyncRecord>()
        while (true) {
            val page = remote.pull(cursor)
            if (page.reset) {
                // The account was erased: forget what was agreed and send everything again, after reading
                // whatever other devices already wrote to the new, empty account.
                ledger.clear(); ledger.owner = account
                pulled.clear(); cursor = 0
                continue
            }
            pulled += page.items
            cursor = page.cursor
            if (!page.more) break
        }
        val applied = apply(pulled)
        ledger.cursor = cursor

        val changes = localChanges(read())
        var pushed = 0
        for (batch in changes.chunked(PUSH_BATCH)) {
            val seq = remote.push(batch.map { it.first })
            ledger.write(batch.map { it.second })
            pushed += batch.size
            // Skip pulling our own writes back, but only when nothing else was written in between.
            if (seq != null && seq == cursor + batch.size) { cursor = seq; ledger.cursor = cursor }
        }
        return SyncReport(pulled.size, pushed, applied, ledger.parked().size)
    }

    private fun claim() {
        if (ledger.owner == account) return
        ledger.clear(); ledger.owner = account
    }

    private fun order(kinds: Collection<String>): List<String> {
        val ordered = mutableListOf<String>()
        fun visit(kind: String) {
            if (kind in ordered) return
            byKind[kind]?.dependsOn?.forEach(::visit)
            ordered += kind
        }
        kinds.forEach(::visit)
        return ordered
    }

    private enum class Outcome { APPLIED, KEPT, WAITING }

    /** Applies pulled records together with earlier ones that were waiting; returns how many changed something. */
    private suspend fun apply(pulled: List<SyncRecord>): Int {
        val latest = linkedMapOf<Pair<String, String>, SyncRecord>()
        (ledger.parked() + pulled).forEach { record ->
            val known = latest[record.kind to record.key]
            if (known == null || record.updated >= known.updated) latest[record.kind to record.key] = record
        }
        if (latest.isEmpty()) return 0
        val grouped = latest.values.groupBy { it.kind }
        val agreedByKind = grouped.keys.associateWith { ledger.agreed(it) }
        val ops = mutableListOf<LedgerOp>()
        var applied = 0
        commit { start ->
            ops.clear(); applied = 0
            var state = start
            for (kind in order(grouped.keys)) {
                val adapter = byKind[kind] ?: continue // A kind from a newer app version: left for it.
                val records = grouped[kind] ?: continue
                val agreed = agreedByKind[kind].orEmpty()
                for (record in records) {
                    val local = adapter.scan(state).firstOrNull { it.key == record.key }
                    val (outcome, next) = applyOne(adapter, state, record, local, agreed[record.key], ops)
                    state = next
                    when (outcome) {
                        Outcome.WAITING -> ops += LedgerOp.Park(record)
                        Outcome.APPLIED -> { ops += LedgerOp.Unpark(kind, record.key); applied++ }
                        Outcome.KEPT -> ops += LedgerOp.Unpark(kind, record.key)
                    }
                }
            }
            state
        }
        ledger.write(ops)
        return applied
    }

    private fun applyOne(adapter: SyncAdapter, state: AppState, record: SyncRecord, local: LocalItem?, agreed: Agreed?, ops: MutableList<LedgerOp>): Pair<Outcome, AppState> {
        if (agreed != null && agreed.hash != null && agreed.updated >= record.updated) return Outcome.KEPT to state
        // Changed here since the last agreement, and later than the server's version: keep ours, it is pushed next.
        // An item never agreed counts as changed only when it carries its own change time (reading progress).
        val editedHere = local != null && (if (agreed?.hash == null) local.updated != null else contentHash(local.data) != agreed.hash)
        if (editedHere && local!!.updated != null && local.updated!! > record.updated) return Outcome.KEPT to state
        if (record.deleted) {
            ops += LedgerOp.Forget(adapter.kind, record.key)
            return if (local == null) Outcome.KEPT to state else Outcome.APPLIED to adapter.remove(state, record.key)
        }
        val next = adapter.apply(state, record) ?: return Outcome.WAITING to state
        // Agree on the item as this device scans it, so applying does not look like a local edit.
        val after = adapter.scan(next).firstOrNull { it.key == record.key }
        ops += LedgerOp.Agree(adapter.kind, record.key, after?.let { contentHash(it.data) } ?: contentHash(record.data), record.updated)
        return Outcome.APPLIED to next
    }

    /** Everything that differs from what was last agreed, as records to push with the ledger update after each. */
    private fun localChanges(state: AppState): List<Pair<SyncRecord, LedgerOp>> {
        val changes = mutableListOf<Pair<SyncRecord, LedgerOp>>()
        val time = now()
        for (adapter in adapters) {
            val agreed = ledger.agreed(adapter.kind)
            val present = HashSet<String>()
            for (item in adapter.scan(state)) {
                if (!present.add(item.key)) continue
                val hash = contentHash(item.data)
                val known = agreed[item.key]
                if (known?.hash == hash) continue
                // Must beat whatever was agreed; local change times can be missing or older.
                val updated = maxOf(item.updated ?: time, (known?.updated ?: 0) + 1)
                changes += SyncRecord(adapter.kind, item.key, updated, item.data) to LedgerOp.Agree(adapter.kind, item.key, hash, updated)
            }
            if (!adapter.pushesDeletions) continue
            for ((key, known) in agreed) {
                if (key in present || known.hash == null) continue
                val updated = maxOf(time, known.updated + 1)
                changes += SyncRecord(adapter.kind, key, updated, deleted = true) to LedgerOp.Forget(adapter.kind, key)
            }
        }
        return changes
    }

    companion object { const val PUSH_BATCH = 500 }
}
