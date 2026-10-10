package app.monogatari.android.library

import app.monogatari.android.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecordConsistencyTest {
    @Test fun interruptedResetFinishesClearingAppDataAndPreservesDictionaryVocabularyAndBackups() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = AppDatabase(context)
        val original = AppState(manga = listOf(Manga("book", "local", "Book", inLibrary = true)), categories = listOf("Saved"))
        database.save(if (database.hasState()) database.load() else null, original)
        listOf("downloads", "local", "covers", "sources", "dictionaries", "vocabulary-media", "backups").forEach { name -> File(context.filesDir, "$name/fixture").apply { parentFile!!.mkdirs(); writeText("retained data") } }
        context.getSharedPreferences("reader-options", Context.MODE_PRIVATE).edit().putString("series:local::book", "{}").commit()
        ResetRequest.begin(context)
        assertTrue(ResetRequest.pending(context))
        val restored = ResetRequest.recover(context, database, original)
        assertTrue(restored.manga.isEmpty()); assertTrue(database.load().categories.isEmpty())
        for (name in listOf("downloads", "local", "covers", "sources")) assertFalse(File(context.filesDir, name).exists())
        for (name in listOf("dictionaries", "vocabulary-media", "backups")) assertEquals("retained data", File(context.filesDir, "$name/fixture").readText())
        assertTrue(context.getSharedPreferences("reader-options", Context.MODE_PRIVATE).all.isEmpty())
        assertFalse(ResetRequest.pending(context))
        database.close()
    }
    @Test fun libraryCountsExcludeLockedAndRespectLanguageAndEachScanlator() {
        val manga = Manga("book", "source", "Book", metadata = JSONObject().put("langFilter", "ja").put("scanlatorFilter", JSONArray(listOf("Group B"))).put("chapterFlags", 1 shl 4).toString(), chapters = listOf(
            Chapter("unread", "Unread", language = "ja", scanlator = "Group A, Group B"),
            Chapter("locked", "Locked", language = "ja", scanlator = "Group B", locked = true),
            Chapter("otherLang", "Other", language = "en", scanlator = "Group B"),
            Chapter("done", "Done", language = "ja", scanlator = "Group B", completed = true, locked = true)))
        assertEquals(1, manga.unread)
        assertEquals(1, ChapterMetrics.readCount(manga))
        assertTrue(LibraryRules.matches(manga, listOf(LibraryRule(2))))
    }
    @Test fun clearHistoryKeepsRemovedOfflineChapterAndItsArchiveReference() {
        val offline = Chapter("removed", "Offline", pages = listOf("aidoku-archive://download/abc#page.png"), page = 4, total = 5, downloaded = true, completed = true, readAt = 1700000000000)
        val row = JSONObject().put("sourceId", "source").put("mangaId", "book").put("chapterId", "removed").put("dateRead", 1700000000).put("progress", 5).put("completed", true).put("_chapter", offline.json())
        val other = JSONObject().put("sourceId", "source").put("mangaId", "book").put("chapterId", "online").put("dateRead", 1700000000)
        val data = JSONObject(HistoryRecords.clear(JSONObject().put("history", JSONArray(listOf(row, other))).put("readingSessions", JSONArray(listOf(other))).toString()) { true })
        val retained = data.getJSONArray("history").objects().single()
        val chapter = Chapter.parse(retained.getJSONObject("_chapter"))
        assertEquals(offline.pages, chapter.pages)
        assertEquals(0L, chapter.readAt)
        assertEquals(5, chapter.total)
        assertFalse(chapter.completed)
        assertEquals(0, retained.getInt("progress"))
        assertEquals(0, data.getJSONArray("readingSessions").length())
    }
    @Test fun categoryRenameAndDeletePreserveUnrelatedRulesAndUnknownMetadata() {
        val rules = JSONArray(listOf(LibraryRule(7, "Old").json(), LibraryRule(5, "source").json()))
        val state = AppState(categories = listOf("Old", "Group"), manga = listOf(Manga("book", "source", "Book", categories = listOf("Old"))),
            settings = JSONObject().put("Library.filters", rules).put("Library.defaultCategory", "Old").put("Library.lockedCategories", JSONArray(listOf("Old", "Other"))).toString(),
            categoryMetadata = JSONObject().put("Old", JSONObject().put("opaque", 17)).put("Group", JSONObject().put("group", true).put("filters", rules)).toString())
        val renamed = CategoryMaintenance.change(state, "Old", "New")
        assertEquals(listOf("New", "Group"), renamed.categories)
        assertEquals(listOf("New"), renamed.manga.single().categories)
        assertEquals(17, JSONObject(renamed.categoryMetadata).getJSONObject("New").getInt("opaque"))
        assertEquals("New", LibraryRules.parse(JSONObject(renamed.settings).get("Library.filters")).first().value)
        val removed = CategoryMaintenance.change(renamed, "New", null)
        assertFalse(JSONObject(removed.settings).has("Library.defaultCategory"))
        assertEquals(listOf("Other"), JSONObject(removed.settings).getJSONArray("Library.lockedCategories").strings())
        assertEquals(listOf(LibraryRule(5, "source")), LibraryRules.parse(JSONObject(removed.categoryMetadata).getJSONObject("Group").get("filters")))
        assertTrue(removed.manga.single().categories.isEmpty())
    }
    @Test fun updatesGroupByLocalDateAndCompositeIdentityAndDeleteOnlyThatGroup() {
        fun row(source: String, book: String, chapter: String, date: Double) = JSONObject().put("sourceId", source).put("mangaId", book).put("chapterId", chapter).put("date", date)
        val rows = JSONArray(listOf(row("first", "same", "one", 1710052200.0), row("first", "same", "two", 1710055800.0), row("second", "same", "one", 1710055800.0), row("first", "same", "yesterday", 1709962200.0)))
        val state = AppState(manga = listOf(Manga("same", "first", "A"), Manga("same", "second", "B")), records = JSONObject().put("updates", rows).toString(), backupExtras = JSONObject().put("updates", rows).toString())
        val groups = UpdateRecords.groups(state, ZoneId.of("America/Los_Angeles"))
        assertEquals(3, groups.size)
        val first = groups.single { it.manga.sourceId == "first" && it.updates.size == 2 }
        val removed = UpdateRecords.remove(state, first)
        for (raw in listOf(removed.records, removed.backupExtras)) {
            val remaining = JSONObject(raw).getJSONArray("updates").objects()
            assertEquals(2, remaining.size)
            assertTrue(remaining.any { it.optString("sourceId") == "second" })
            assertTrue(remaining.any { it.optString("chapterId") == "yesterday" })
        }
    }
}
