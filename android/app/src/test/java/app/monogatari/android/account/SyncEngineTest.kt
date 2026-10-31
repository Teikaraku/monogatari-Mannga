package app.monogatari.android.account

import app.monogatari.android.data.AppState
import app.monogatari.android.data.Chapter
import app.monogatari.android.data.Manga
import app.monogatari.android.data.Preferences
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The sync service in memory, with its merge rule, sequence, paging and reset. */
class FakeServer(private val pageSize: Int = 3) {
    private data class Row(val seq: Long, val record: SyncRecord)
    private val rows = linkedMapOf<Pair<String, String>, Row>()
    var seq = 0L; private set
    var pushes = 0; private set

    private fun payload(r: SyncRecord) = r.data?.let { contentHash(it) }.orEmpty()
    private fun wins(new: SyncRecord, old: SyncRecord) = when {
        new.updated != old.updated -> new.updated > old.updated
        new.deleted != old.deleted -> new.deleted
        else -> payload(new) > payload(old)
    }

    fun remote() = object : SyncRemote {
        override suspend fun pull(since: Long): SyncPage {
            val after = rows.values.filter { it.seq > since }.sortedBy { it.seq }
            val page = after.take(pageSize)
            val reset = since > seq
            val cursor = page.lastOrNull()?.seq ?: if (reset) seq else since
            // Round-trip through JSON, as the network does.
            return SyncPage(page.map { SyncRecord.parse(JSONObject(it.record.json().toString())) }, cursor, page.size == pageSize && cursor < seq, reset, seq)
        }
        override suspend fun push(records: List<SyncRecord>): Long {
            pushes++
            for (r in records) {
                val old = rows[r.kind to r.key]?.record
                if (old == null || wins(r, old)) rows[r.kind to r.key] = Row(++seq, r)
            }
            return seq
        }
    }

    fun erase() { rows.clear(); seq = 0 }
    fun record(kind: String, key: String) = rows[kind to key]?.record
    fun kinds() = rows.keys.map { it.first }
}

class MemoryLedger : SyncLedger {
    override var owner: String? = null
    override var cursor = 0L
    private val agreed = mutableMapOf<String, MutableMap<String, Agreed>>()
    private val parked = linkedMapOf<Pair<String, String>, SyncRecord>()
    override fun agreed(kind: String) = agreed[kind].orEmpty().toMap()
    override fun parked() = parked.values.toList()
    override fun write(ops: List<LedgerOp>) = ops.forEach { op ->
        when (op) {
            is LedgerOp.Agree -> agreed.getOrPut(op.kind) { mutableMapOf() }[op.key] = Agreed(op.hash, op.updated)
            is LedgerOp.Forget -> agreed[op.kind]?.remove(op.key)
            is LedgerOp.Park -> parked[op.record.kind to op.record.key] = op.record
            is LedgerOp.Unpark -> parked.remove(op.kind to op.key)
        }
    }
    override fun clear() { agreed.clear(); parked.clear(); cursor = 0; owner = null }
}

/** One install: its state, ledger and clock. */
class Device(val server: FakeServer, var account: String = "alice", start: AppState = AppState()) {
    var state = start
    val ledger = MemoryLedger()
    var clock = 1_000_000L
    val adapters = listOf(CategoryAdapter, LibraryAdapter, ProgressAdapter, PrefAdapter { _, old -> old })
    fun engine() = SyncEngine(ledger, server.remote(), account, adapters, { state }, { block -> block(state).also { state = it } }, { clock })
    fun sync() = runBlocking { engine().sync() }
    fun manga(source: String, key: String) = state.manga.first { it.sourceId == source && it.key == key }
    fun edit(block: (AppState) -> AppState) { state = block(state) }
}

private fun title(key: String, vararg chapters: String, inLibrary: Boolean = true, categories: List<String> = emptyList()) =
    Manga(key, "copy2026", "Title $key", cover = "https://img/$key.jpg", inLibrary = inLibrary, categories = categories, addedAt = 500,
        chapters = chapters.mapIndexed { i, c -> Chapter(c, "Chapter $c", number = i + 1.0, total = 20) })

private fun AppState.read(manga: String, chapter: String, page: Int, at: Long, completed: Boolean = false) =
    copy(manga = this.manga.map { m -> if (m.key != manga) m else m.copy(chapters = m.chapters.map { c ->
        if (c.key == chapter) c.copy(page = page, readAt = at, completed = completed) else c }) })

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncEngineTest {
    @Test fun libraryCategoriesProgressAndSettingsReachTheOtherDevice() {
        val server = FakeServer()
        val phone = Device(server, start = AppState(manga = listOf(title("one-piece", "c1", "c2", categories = listOf("在读"))), categories = listOf("在读", "完结"),
            settings = JSONObject().put("Reader.readingMode", "rtl").put("Tracker.anilist.token", "secret").put("General.incognitoMode", true).toString()))
        phone.edit { it.read("one-piece", "c1", 7, 2_000_000) }
        phone.sync()

        val tablet = Device(server)
        tablet.sync()
        val manga = tablet.manga("copy2026", "one-piece")
        assertTrue(manga.inLibrary)
        assertEquals(listOf("在读"), manga.categories)
        assertEquals("Title one-piece", manga.title)
        assertEquals(listOf("在读", "完结"), tablet.state.categories)
        // Chapters are not loaded yet on the tablet: the progress waits as history until they are.
        val history = JSONObject(tablet.state.records).getJSONArray("history").getJSONObject(0)
        assertEquals("c1", history.getString("chapterId")); assertEquals(8, history.getInt("progress"))
        val settings = JSONObject(tablet.state.settings)
        assertEquals("rtl", settings.getString("Reader.readingMode"))
        // Credentials and device-local settings never leave the phone.
        assertFalse(settings.has("Tracker.anilist.token")); assertFalse(settings.has("General.incognitoMode"))
        assertEquals(setOf("library", "progress", "category", "pref"), server.kinds().toSet())
    }

    @Test fun progressIsAppliedToLoadedChaptersAndTheNewerReadWins() {
        val server = FakeServer()
        val phone = Device(server, start = AppState(manga = listOf(title("m", "c1"))))
        val tablet = Device(server, start = AppState(manga = listOf(title("m", "c1"))))
        phone.sync(); tablet.sync()

        phone.edit { it.read("m", "c1", 3, 2_000_000) }; phone.clock = 2_000_001
        tablet.edit { it.read("m", "c1", 12, 3_000_000) }; tablet.clock = 3_000_001
        phone.sync(); tablet.sync(); phone.sync()
        assertEquals(12, phone.manga("copy2026", "m").chapters[0].page)
        assertEquals(12, tablet.manga("copy2026", "m").chapters[0].page)
        // Converged: nothing left to push on either side.
        val pushes = server.pushes
        phone.sync(); tablet.sync()
        assertEquals(pushes, server.pushes)
    }

    @Test fun anOlderPulledVersionDoesNotOverwriteALaterLocalEdit() {
        val server = FakeServer()
        val phone = Device(server, start = AppState(manga = listOf(title("m", "c1"))))
        val tablet = Device(server, start = AppState(manga = listOf(title("m", "c1"))))
        phone.edit { it.read("m", "c1", 2, 2_000_000) }; phone.sync()
        // The tablet read further, later, without having synced yet.
        tablet.edit { it.read("m", "c1", 9, 4_000_000) }; tablet.sync()
        assertEquals(9, tablet.manga("copy2026", "m").chapters[0].page)
        assertEquals(9, server.record("progress", syncKey("copy2026", "m", "c1"))!!.data!!.getInt("page"))
    }

    @Test fun removingFromTheLibraryAndDeletingACategoryPropagate() {
        val server = FakeServer()
        val phone = Device(server, start = AppState(manga = listOf(title("a"), title("b")), categories = listOf("x", "y")))
        phone.sync()
        val tablet = Device(server); tablet.sync()
        assertEquals(2, tablet.state.manga.count { it.inLibrary })

        phone.clock += 10_000
        phone.edit { s -> app.monogatari.android.library.CategoryMaintenance.change(s.copy(manga = s.manga.map { if (it.key == "a") it.copy(inLibrary = false) else it }), "x", null) }
        phone.sync(); tablet.sync()
        assertFalse(tablet.manga("copy2026", "a").inLibrary)
        assertTrue(tablet.manga("copy2026", "b").inLibrary)
        assertEquals(listOf("y"), tablet.state.categories)
        assertTrue(server.record("library", syncKey("copy2026", "a"))!!.deleted)
    }

    @Test fun localFilesStayOnTheDevice() {
        val server = FakeServer()
        val phone = Device(server, start = AppState(manga = listOf(Manga("file.cbz", "local", "Mine", inLibrary = true, chapters = listOf(Chapter("1", "1", readAt = 5, page = 3))))))
        phone.sync()
        assertTrue(server.kinds().isEmpty())
    }

    @Test fun signingInAsSomeoneElseDoesNotPushThePreviousUsersDeletions() {
        val server = FakeServer()
        val phone = Device(server, start = AppState(manga = listOf(title("a"))))
        phone.sync()
        // Bob signs in on the same phone after the title was removed: nothing of Alice's is deleted in Bob's account,
        // and the ledger starts over for him.
        phone.account = "bob"
        phone.edit { s -> s.copy(manga = s.manga.map { it.copy(inLibrary = false) }) }
        val before = server.record("library", syncKey("copy2026", "a"))
        phone.sync()
        assertEquals(before, server.record("library", syncKey("copy2026", "a")))
        assertEquals("bob", phone.ledger.owner)
    }

    @Test fun anErasedAccountMakesTheDeviceStartOver() {
        val server = FakeServer()
        val phone = Device(server, start = AppState(manga = listOf(title("a"), title("b"))))
        phone.sync()
        assertTrue(phone.ledger.cursor > 0)
        server.erase()
        phone.sync()
        // Everything is sent again to the empty account.
        assertNotNull(server.record("library", syncKey("copy2026", "a")))
        assertNotNull(server.record("library", syncKey("copy2026", "b")))
    }

    @Test fun pagingAndOwnWritesAreNotPulledBack() {
        val server = FakeServer(pageSize = 2)
        val phone = Device(server, start = AppState(manga = (1..7).map { title("m$it") }))
        phone.sync()
        assertEquals(server.seq, phone.ledger.cursor)
        val tablet = Device(server); tablet.sync()
        assertEquals(7, tablet.state.manga.count { it.inLibrary })
        assertEquals(server.seq, tablet.ledger.cursor)
    }

    @Test fun progressForATitleNotHereWaitsUntilItArrives() {
        val server = FakeServer()
        val phone = Device(server, start = AppState(manga = listOf(title("hist", "c1", inLibrary = false))))
        phone.edit { it.read("hist", "c1", 4, 2_000_000) }
        phone.sync()
        // Read but never added to the library: the tablet has no such title, so the record is parked.
        val tablet = Device(server); tablet.sync()
        assertEquals(1, tablet.ledger.parked().size)
        tablet.edit { s -> s.copy(manga = s.manga + title("hist", "c1", inLibrary = false)) }
        tablet.sync()
        assertEquals(4, tablet.manga("copy2026", "hist").chapters[0].page)
        assertTrue(tablet.ledger.parked().isEmpty())
    }

    @Test fun aNewDeviceDoesNotOverwriteTheAccountWithItsUntouchedDefaults() {
        val server = FakeServer()
        val phone = Device(server, start = AppState(manga = listOf(title("m", categories = listOf("A"))), categories = listOf("A")))
        phone.sync()
        // The tablet already has the title (opened it once) but without categories; the account's version wins.
        val tablet = Device(server, start = AppState(manga = listOf(title("m"))))
        tablet.clock = 9_000_000
        tablet.sync()
        assertEquals(listOf("A"), tablet.manga("copy2026", "m").categories)
        assertEquals(JSONArray(listOf("A")).toString(), server.record("library", syncKey("copy2026", "m"))!!.data!!.getJSONArray("categories").toString())
    }

    @Test fun contentHashIgnoresKeyOrderAndIntegralDoubles() {
        assertEquals(contentHash(JSONObject().put("a", 1).put("b", JSONObject().put("y", 2.0).put("x", true))),
            contentHash(JSONObject("""{"b":{"x":true,"y":2},"a":1.0}""")))
        assertNotEquals(contentHash(JSONObject().put("a", 1)), contentHash(JSONObject().put("a", 1.5)))
        assertEquals(64, syncKey("copy2026", "id with spaces/和中文").length)
    }

    @Test fun preferencesAreKeptByTheEffectiveFunction() {
        val adapter = PrefAdapter { settings, old -> old.copy(mode = settings.optString("Reader.readingMode", old.mode)) }
        val state = adapter.apply(AppState(preferences = Preferences()), SyncRecord("pref", "Reader.readingMode", 5, JSONObject().put("value", "webtoon")))!!
        assertEquals("webtoon", state.preferences.mode)
        // Unknown or sensitive names are ignored.
        assertEquals("{}", adapter.apply(AppState(), SyncRecord("pref", "Tracker.anilist.token", 5, JSONObject().put("value", "x")))!!.settings)
    }
}
