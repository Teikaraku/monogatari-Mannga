package app.monogatari.android.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.monogatari.android.data.*
import app.monogatari.android.dictionary.*
import app.monogatari.android.downloads.DownloadService
import app.monogatari.android.tracking.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [app.monogatari.android.backup.PosixAtomicFileShadow::class])
class LibraryMigrationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun logical(value: Any?): Any? = when (value) { is JSONObject -> value.keys().asSequence().associateWith { logical(value.opt(it)) }; is JSONArray -> (0 until value.length()).map { logical(value.opt(it)) }; else -> value }
    @Before fun initializeWorkManager() { if (runCatching { androidx.work.WorkManager.getInstance(context) }.isFailure) androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build()) }
    private fun archive(manga: String, chapter: String, content: String): String {
        val hash = AppRepository.chapterDownloadId(manga, chapter); val file = File(context.filesDir, "downloads/$hash.cbz"); file.parentFile!!.mkdirs()
        ZipOutputStream(file.outputStream()).use { it.putNextEntry(ZipEntry("page.txt")); it.write(content.toByteArray()); it.closeEntry() }; return "aidoku-archive://download/$hash?entry=page.txt"
    }
    private fun content(manga: String, chapter: String) = java.util.zip.ZipFile(File(context.filesDir, "downloads/${AppRepository.chapterDownloadId(manga, chapter)}.cbz")).use { zip -> zip.getInputStream(zip.getEntry("page.txt")).bufferedReader().use { it.readText() } }
    private suspend fun fixture(): AppRepository {
        val repo = AppRepository(context)
        val a = Manga("a", "fixture", "A", chapters = listOf(Chapter("1", "A1", 1.0, pages = listOf(archive("fixture::a", "1", "page A")), completed = true, downloaded = true, readAt = 1000, metadata = """{"key":"1","_manga":{"key":"a","sourceId":"fixture"}}""")), inLibrary = true)
        val b = Manga("b", "fixture", "B", chapters = listOf(Chapter("2", "B2", 2.0, pages = listOf(archive("fixture::b", "2", "page B")), downloaded = true)), inLibrary = true)
        fun row(manga: String, chapter: String) = JSONObject().put("sourceId", "fixture").put("mangaId", manga).put("chapterId", chapter).put("future", manga)
        val orphan = row("a", "gone").put("_chapter", Chapter("gone", "Old", pages = listOf(archive("fixture::a", "gone", "orphan")), downloaded = true).json())
        repo.change { AppState(manga = listOf(a, b), sources = listOf(SourceConfig.local, SourceConfig("fixture", "Fixture", "local", configuration = "{\"info\":{\"version\":1}}"), SourceConfig("other", "Other", "local")),
            settings = """{"Reader.readingMode.fixture.a":"rtl","Reader.readingMode.fixture::b":"ltr","Manga.chapterDisplayMode.fixture.a":2,"Manga.chapterDisplayMode.fixture::b":1}""",
            records = JSONObject().put("history", JSONArray(listOf(orphan))).put("readingSessions", JSONArray(listOf(row("a", "1").put("startDate", 1)))).toString(),
            backupExtras = JSONObject().put("manga", JSONArray(listOf(JSONObject().put("sourceId", "fixture").put("id", "a").put("future", "A")))).put("trackItems", JSONArray(listOf(row("a", "1").put("trackerId", "komga").put("id", "fixture|a")))).toString()) }
        repo.change { it.copy(settings = JSONObject(it.settings).put("Reader.androidOptions.fixture.a", """{"fit":"width","contrast":1.2,"future":{"nested":["x::y"]}}""").put("Reader.androidOptions.fixture.b", """{"fit":"height","contrast":1.8}""").toString()) }
        val reader = context.getSharedPreferences("reader-options", Context.MODE_PRIVATE); reader.edit().putString("series:fixture::a", "{\"zoom\":3}").putString("series:fixture::b", "{\"zoom\":9}").commit()
        val accounts = listOf(TrackerAccount("public", TrackerProvider.ANILIST), TrackerAccount("server", TrackerProvider.KOMGA, sourceId = "fixture", baseUrl = "https://fixture"))
        TrackingRepository.get(context).restoreSnapshot(TrackingData(accounts, listOf(TrackBinding(a.id, "public", "remote-public", "A", chapterOffset = 3), TrackBinding(a.id, "server", "a", "A", pending = true, pendingPages = listOf(ChapterRemoteProgress("1", 4, 10, false, updatedAt = 20), ChapterRemoteProgress("gone", 2, 5, false, updatedAt = 21))), TrackBinding(b.id, "public", "other-public", "B"))).json())
        val dictionary = DictionaryRepository.get(context); dictionary.load(); dictionary.saveVocabulary(VocabularyEntry(id = "a-word", word = "字", mangaId = a.id, chapterKey = "gone"))
        return repo
    }
    @Test fun sourceKeySwapMigratesFilesOpaqueHistoryReaderVocabularyAndEveryQueuedChapterAtomically() = runBlocking {
        val repo = fixture(); val downloads = DownloadService(context, repo, scheduleWork = false)
        repo.migrateSourceKeys("fixture", mapOf("a" to "b", "b" to "a"), mapOf("a" to mapOf("1" to "2", "gone" to "renamed"), "b" to mapOf("2" to "1")), downloads, updatedSource = SourceConfig("fixture", "Version2", "local", configuration = "{\"info\":{\"version\":2}}"))
        assertEquals("A", repo.state.value.manga.first { it.key == "b" }.title); assertEquals("page A", content("fixture::b", "2")); assertEquals("page B", content("fixture::a", "1")); assertEquals("orphan", content("fixture::b", "renamed"))
        assertEquals(listOf("local", "fixture", "other"), repo.state.value.sources.map { it.id }); assertEquals("Version2", repo.state.value.sources[1].name)
        val history = JSONObject(repo.state.value.records).getJSONArray("history").getJSONObject(0); assertEquals("b", history.getString("mangaId")); assertEquals("renamed", history.getJSONObject("_chapter").getString("key")); assertTrue(history.getJSONObject("_chapter").getJSONArray("pages").getString(0).contains(AppRepository.chapterDownloadId("fixture::b", "renamed")))
        assertEquals("fixture|b", JSONObject(repo.state.value.backupExtras).getJSONArray("trackItems").getJSONObject(0).getString("id"))
        val settings = JSONObject(repo.state.value.settings); assertEquals("rtl", settings.getString("Reader.readingMode.fixture.b")); assertEquals("ltr", settings.getString("Reader.readingMode.fixture.a"))
        assertEquals(2, settings.getInt("Manga.chapterDisplayMode.fixture.b")); assertEquals(1, settings.getInt("Manga.chapterDisplayMode.fixture.a"))
        assertEquals("width", JSONObject(settings.getString("Reader.androidOptions.fixture.b")).getString("fit")); assertEquals("height", JSONObject(settings.getString("Reader.androidOptions.fixture.a")).getString("fit")); assertEquals("x::y", JSONObject(settings.getString("Reader.androidOptions.fixture.b")).getJSONObject("future").getJSONArray("nested").getString(0))
        assertEquals(3, JSONObject(context.getSharedPreferences("reader-options", 0).getString("series:fixture::b", "{}")!!).getInt("zoom"))
        val bindings = TrackingRepository.get(context).data.value.bindings; assertEquals("remote-public", bindings.first { it.mangaId == "fixture::b" && it.accountId == "public" }.remoteId); assertEquals(setOf("2", "renamed"), bindings.first { it.accountId == "server" }.queuedPages().map { it.key }.toSet())
        assertEquals("fixture::b", DictionaryRepository.get(context).vocabulary.value.single().mangaId); assertEquals("renamed", DictionaryRepository.get(context).vocabulary.value.single().chapterKey)
        assertFalse(SourceMigrationJournal.file(context).baseFile.exists())
    }
    @Test fun failureAfterSQLiteCommitRollsBackAllParticipantsAndPreservesOtherSourceProgress() = runBlocking {
        val repo = fixture(); val original = repo.state.value; val tracker = TrackingRepository.get(context).data.value
        try { repo.migrateSourceKeys("fixture", mapOf("a" to "next"), mapOf("a" to mapOf("1" to "changed", "gone" to "gone")), DownloadService(context, repo, false), afterStateCommit = {
            repo.change { it.copy(manga = it.manga + Manga("new", "other", "Concurrent", chapters = listOf(Chapter("x", "X", completed = true, readAt = 9000)))) }; throw IOException("Injected commit failure")
        }); fail("Expected failure") } catch (error: IOException) { assertEquals("Injected commit failure", error.message); assertEquals(0, error.suppressed.size) }
        assertEquals(original.manga, repo.state.value.manga.filter { it.sourceId == "fixture" }); assertEquals(9000L, repo.state.value.manga.first { it.sourceId == "other" }.chapters.single().readAt)
        assertEquals(tracker, TrackingRepository.get(context).data.value); assertEquals("page A", content("fixture::a", "1")); assertEquals("fixture::a", DictionaryRepository.get(context).vocabulary.value.single().mangaId)
        assertEquals(JSONObject(original.settings).getString("Reader.androidOptions.fixture.a"), JSONObject(repo.state.value.settings).getString("Reader.androidOptions.fixture.a")); assertFalse(JSONObject(repo.state.value.settings).has("Reader.androidOptions.fixture.next"))
        assertFalse(SourceMigrationJournal.file(context).baseFile.exists()); assertFalse(File(context.filesDir, "download-key-migration.json").exists())
    }
    @Test fun incompletePersistentJournalRestoresOriginalKeysBeforeRepositoryConstruction() = runBlocking {
        val repo = fixture(); val original = repo.state.value; val tracking = TrackingRepository.get(context); val vocabulary = DictionaryRepository.get(context)
        val mappings = mapOf("a" to ("new" to mapOf("1" to "newChapter")))
        SourceMigrationJournal.create(context, original, "fixture", mappings, tracking.data.value.json(), app.monogatari.android.reader.ReaderOptionStore(context).export(), vocabulary.exportBackup(), JSONArray())
        tracking.migrateSourceBindings("fixture", mapOf("a" to "new")); vocabulary.migrateSourceKeys("fixture", mapOf("a" to "new"), emptyMap())
        val partial = migrateStateKeys(original, "fixture", mappings); val db = AppDatabase(context); db.save(original, partial)
        val recovered = recoverSourceKeyMigrations(context, db, partial)
        assertEquals(logical(original.json()), logical(recovered.json())); assertEquals(original.manga, db.load().manga)
        assertEquals("fixture::a", JSONObject(File(context.filesDir, "tracking.json").readText()).getJSONArray("bindings").getJSONObject(0).getString("mangaId")); assertFalse(SourceMigrationJournal.file(context).baseFile.exists()); db.close()
    }
    @Test fun committedPersistentJournalKeepsNewKeysAndUpdatedSourceVersion() = runBlocking {
        val repo = fixture(); val old = repo.state.value; val mappings = mapOf("a" to ("new" to mapOf("1" to "next")))
        val journal = SourceMigrationJournal.create(context, old, "fixture", mappings, TrackingRepository.get(context).data.value.json(), JSONObject(), DictionaryRepository.get(context).exportBackup(), JSONArray())
        val committed = migrateStateKeys(old, "fixture", mappings).copy(settings = JSONObject(old.settings).put(SourceMigrationJournal.marker, journal.getString("id")).toString())
        val db = AppDatabase(context); db.save(old, committed); assertEquals(committed, recoverSourceKeyMigrations(context, db, committed)); assertFalse(SourceMigrationJournal.file(context).baseFile.exists()); db.close()
    }
    @Test fun mangaCopyOnlyMigratesCompletedProgressAndNeverAliasesMangaScopedDownloadIdentifiers() {
        val old = Manga("old", "same", "Old", inLibrary = true, categories = listOf("A"), pinned = true, chapters = listOf(Chapter("1", "Read", 2.0, completed = true, readAt = 1000, downloaded = true, pages = listOf("file:///private/old")), Chapter("99", "Partial", 99.0, page = 20, readAt = 2000)))
        val target = Manga("new", "same", "New", categories = listOf("B"), chapters = listOf(Chapter("1", "1", 1.0), Chapter("2", "2", 2.0), Chapter("3", "3", 3.0)))
        val migrated = MangaMigration.migratedManga(old, target)
        assertEquals(listOf("A", "B"), migrated.categories); assertTrue(migrated.pinned); assertEquals(listOf(true, true, false), migrated.chapters.map { it.completed }); assertTrue(migrated.chapters.none { it.downloaded }); assertTrue(migrated.chapters.all { it.pages.isEmpty() })
        val proven = MangaMigration.migratedManga(old.copy(chapters = listOf(old.chapters[0].copy(url = "https://same/chapter"))), target.copy(chapters = listOf(target.chapters[0].copy(url = "https://same/chapter"))))
        assertEquals(old.chapters[0].pages, proven.chapters.single().pages)
    }
    @Test fun migrationDistinguishesRealChapterZeroAndFallsBackToCompletedVolumeOnly() {
        val zero = Chapter("zero", "Zero", metadata = "{\"chapterNumber\":0}")
        val old = Manga("old", "local", "Old", chapters = listOf(zero.copy(completed = true), Chapter("partial", "Partial", 90.0)))
        val target = Manga("new", "local", "New", chapters = listOf(zero, Chapter("one", "One", 1.0)))
        assertEquals(listOf(true, false), MangaMigration.migratedManga(old, target).chapters.map { it.completed })
        val volumes = MangaMigration.migratedManga(old.copy(chapters = listOf(Chapter("read", "Read", volume = 2.0, completed = true), Chapter("partial", "Partial", volume = 9.0))), target.copy(chapters = listOf(Chapter("v1", "V1", volume = 1.0), Chapter("v2", "V2", volume = 2.0), Chapter("v3", "V3", volume = 3.0))))
        assertEquals(listOf(true, true, false), volumes.chapters.map { it.completed })
    }
    @Test fun mangaMovePreservesOldHistoryDownloadsAndSessionsAndCopiesOriginalDotReaderSettings() = runBlocking {
        val repo = AppRepository(context); val old = Manga("old", "local", "Old", inLibrary = true, categories = listOf("A"), chapters = listOf(Chapter("one", "One", 1.0, completed = true, downloaded = true, pages = listOf("file:///old"), readAt = 1000)))
        val target = Manga("new", "local", "New", chapters = listOf(Chapter("different", "One", 1.0)))
        repo.change { it.copy(manga = listOf(old), records = "{\"readingSessions\":[{\"mangaId\":\"old\",\"sourceId\":\"local\"}]}", settings = "{\"Reader.readingMode.local.old\":\"ltr\",\"Manga.chapterDisplayMode.local.old\":2}") }
        MangaMigration.migrate(repo, old.id, target, copy = false)
        val retained = repo.state.value.manga.first { it.id == old.id }; assertFalse(retained.inLibrary); assertTrue(retained.chapters.single().downloaded); assertEquals(1000L, retained.lastRead); assertTrue(repo.state.value.records.contains("old"))
        assertEquals("ltr", JSONObject(repo.state.value.settings).getString("Reader.readingMode.local.new")); assertFalse(JSONObject(repo.state.value.settings).has("Reader.readingMode.local.old")); assertTrue(repo.state.value.manga.first { it.id == target.id }.chapters.single().completed)
        assertEquals(2, JSONObject(repo.state.value.settings).getInt("Manga.chapterDisplayMode.local.new")); assertFalse(JSONObject(repo.state.value.settings).has("Manga.chapterDisplayMode.local.old"))
    }
    @Test fun mangaMigrationFailureAfterCommitCanRetryWithoutLosingHistory() = runBlocking {
        val repo = AppRepository(context); val old = Manga("old", "local", "Old", inLibrary = true, chapters = listOf(Chapter("one", "One", 1.0, completed = true, readAt = 1000))); val target = Manga("new", "local", "New", chapters = listOf(Chapter("other", "One", 1.0)))
        repo.change { it.copy(manga = listOf(old)) }; val before = repo.state.value
        try { MangaMigration.migrate(repo, old.id, target, false) { throw IOException("Injected") }; fail("Expected failure") } catch (_: IOException) { }
        assertEquals(before, repo.state.value); assertFalse(app.monogatari.android.backup.DataRestoreJournal.file(context).baseFile.exists())
        assertTrue(MangaMigration.migrate(repo, old.id, target, false).chapters.single().completed)
    }
    @Test fun mangaCopyKeepsOriginalLibraryEntryAndBothReaderAndTrackerDisplayPreferences() = runBlocking {
        val repo = AppRepository(context); val old = Manga("old", "local", "Old", inLibrary = true); val target = Manga("new", "local", "New")
        repo.change { it.copy(manga = listOf(old), settings = "{\"Reader.readingMode.local.old\":\"ltr\",\"Manga.chapterDisplayMode.local.old\":2}") }
        repo.settings.put("Reader.androidOptions.local.old", """{"fit":"width","colorTint":4294967295,"future":{"nested":["a.b::c"]}}""")
        MangaMigration.migrate(repo, old.id, target, true)
        assertTrue(repo.state.value.manga.first { it.id == old.id }.inLibrary); assertTrue(repo.state.value.manga.first { it.id == target.id }.inLibrary)
        val settings = JSONObject(repo.state.value.settings); assertEquals("ltr", settings.getString("Reader.readingMode.local.old")); assertEquals("ltr", settings.getString("Reader.readingMode.local.new")); assertEquals(2, settings.getInt("Manga.chapterDisplayMode.local.old")); assertEquals(2, settings.getInt("Manga.chapterDisplayMode.local.new"))
        assertEquals(settings.getString("Reader.androidOptions.local.old"), settings.getString("Reader.androidOptions.local.new")); assertEquals("a.b::c", JSONObject(settings.getString("Reader.androidOptions.local.new")).getJSONObject("future").getJSONArray("nested").getString(0))
    }
    @Test fun overlappingOpaqueMangaIdsKeepTheirOwnUnknownPreferencesDuringSourceRenameAndRollback() = runBlocking {
        val repo = AppRepository(context); val source = SourceConfig("s", "Local fixture", "local")
        val book = Manga("b", "s", "B"); val other = Manga("a.s.b", "s", "Opaque other")
        val original = JSONObject().put("Reader.androidOptions.s.b", "{\"fit\":\"width\"}")
            .put("Reader.androidOptions.s.a.s.b", "{\"fit\":\"height\",\"future\":{\"value\":7}}")
            .put("Manga.future.preference.s::b", JSONObject().put("unknown", JSONArray(listOf(1, "preserved"))))
            .put("Manga.future.preference.s::a.s.b", JSONObject().put("unknown", JSONArray(listOf(2, "other preserved"))))
        repo.change { AppState(manga = listOf(book, other), sources = listOf(SourceConfig.local, source), settings = original.toString()) }
        val downloads = DownloadService(context, repo, scheduleWork = false)
        val newer = "{\"fit\":\"height\",\"future\":{\"value\":99}}"
        try { repo.migrateSourceKeys("s", mapOf("b" to "c"), emptyMap(), downloads, afterStateCommit = {
            repo.settings.put("Reader.androidOptions.s.a.s.b", newer); throw IOException("Rollback opaque ownership")
        }); fail("Expected failure") } catch (_: IOException) { }
        val rolledBack = JSONObject(repo.state.value.settings)
        assertEquals(newer, rolledBack.getString("Reader.androidOptions.s.a.s.b")); assertEquals(original.getString("Reader.androidOptions.s.b"), rolledBack.getString("Reader.androidOptions.s.b"))
        assertEquals(logical(original.getJSONObject("Manga.future.preference.s::a.s.b")), logical(rolledBack.getJSONObject("Manga.future.preference.s::a.s.b"))); assertFalse(rolledBack.has("Reader.androidOptions.s.a.s.c"))
        repo.migrateSourceKeys("s", mapOf("b" to "c"), emptyMap(), downloads)
        val renamed = JSONObject(repo.state.value.settings)
        assertEquals(original.getString("Reader.androidOptions.s.b"), renamed.getString("Reader.androidOptions.s.c")); assertEquals(newer, renamed.getString("Reader.androidOptions.s.a.s.b"))
        assertEquals(logical(original.getJSONObject("Manga.future.preference.s::b")), logical(renamed.getJSONObject("Manga.future.preference.s.c"))); assertTrue(renamed.has("Manga.future.preference.s::a.s.b")); assertFalse(renamed.has("Manga.future.preference.s.a.s.c"))
    }
    @Test fun overlappingOpaquePreferencesDoNotMoveWithAnotherBookWhenCopyingOrSwappingKeys() = runBlocking {
        val source = SourceConfig("s", "Local fixture", "local"); val book = Manga("b", "s", "B"); val other = Manga("a.s.b", "s", "Opaque other"); val target = Manga("c", "s", "C")
        val settings = JSONObject().put("Reader.future.preference.s.b", JSONObject().put("nested", JSONArray(listOf("B"))))
            .put("Reader.future.preference.s.a.s.b", JSONObject().put("nested", JSONArray(listOf("OTHER"))))
            .put("Manga.chapterDisplayMode.s.c", 2)
        val state = AppState(manga = listOf(book, other, target), sources = listOf(SourceConfig.local, source), settings = settings.toString())
        val swapped = JSONObject(migrateStateKeys(state, "s", mapOf("b" to ("c" to emptyMap()), "c" to ("b" to emptyMap()))).settings)
        assertEquals("B", swapped.getJSONObject("Reader.future.preference.s.c").getJSONArray("nested").getString(0)); assertEquals(2, swapped.getInt("Manga.chapterDisplayMode.s.b"))
        assertEquals("OTHER", swapped.getJSONObject("Reader.future.preference.s.a.s.b").getJSONArray("nested").getString(0)); assertFalse(swapped.has("Reader.future.preference.s.a.s.c"))
        val repo = AppRepository(context); repo.change { state }; MangaMigration.migrate(repo, book.id, target, copy = true)
        val copied = JSONObject(repo.state.value.settings)
        assertEquals(logical(settings.getJSONObject("Reader.future.preference.s.b")), logical(copied.getJSONObject("Reader.future.preference.s.c"))); assertTrue(copied.has("Reader.future.preference.s.b"))
        assertEquals("OTHER", copied.getJSONObject("Reader.future.preference.s.a.s.b").getJSONArray("nested").getString(0)); assertFalse(copied.has("Reader.future.preference.s.a.s.c"))
    }
}
