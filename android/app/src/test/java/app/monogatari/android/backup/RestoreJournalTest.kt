package app.monogatari.android.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.monogatari.android.data.*
import app.monogatari.android.tracking.TrackingData
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [PosixAtomicFileShadow::class])
class RestoreJournalTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun plan(journal: JSONObject, content: String): Pair<File, File> {
        val staging = File(context.filesDir, ".restore-stage-${UUID.randomUUID()}").apply { mkdirs() }; val destination = File(context.filesDir, "restored/${UUID.randomUUID()}").apply { parentFile!!.mkdirs() }
        val name = "files/downloads/${"a".repeat(64)}.cbz"; File(staging, name).apply { parentFile!!.mkdirs(); writeText(content) }
        DataRestoreJournal.planFiles(context, journal, staging, destination, listOf(name)); check(staging.renameTo(destination)); return File(destination, name) to File(context.filesDir, name.removePrefix("files/"))
    }
    @Test fun processDeathAfterOldFileRenameRestoresTheOldArchiveAndDatabase() {
        val original = AppState(manga = listOf(Manga("old", "local", "Old", inLibrary = true))); val db = AppDatabase(context); db.save(null, original)
        val live = File(context.filesDir, "downloads/${"a".repeat(64)}.cbz").apply { parentFile!!.mkdirs(); writeText("original bytes") }
        val journal = DataRestoreJournal.create(context, original, TrackingData().json(), JSONObject().put("global", "{\"direction\":\"rtl\"}"))
        val (incoming, target) = plan(journal, "new bytes"); val previous = File(incoming.parentFile!!.parentFile!!.parentFile, ".previous/downloads/${live.name}").apply { parentFile!!.mkdirs() }
        assertTrue(live.renameTo(previous)); assertTrue(incoming.renameTo(target))
        val partial = AppState(manga = listOf(Manga("new", "local", "New", inLibrary = true))); db.save(original, partial)
        context.getSharedPreferences("reader-options", 0).edit().putString("global", "{\"direction\":\"ltr\"}").commit()
        val restored = recoverDataRestores(context, db, partial)
        assertEquals("original bytes", live.readText()); assertEquals(original, restored); assertEquals("{\"direction\":\"rtl\"}", context.getSharedPreferences("reader-options", 0).getString("global", "")); assertFalse(DataRestoreJournal.file(context).baseFile.exists()); db.close()
    }
    @Test fun processDeathBeforeOldFileRenameLeavesTheUnchangedArchiveIntact() {
        val original = AppState(); val db = AppDatabase(context); db.save(null, original)
        val live = File(context.filesDir, "downloads/${"a".repeat(64)}.cbz").apply { parentFile!!.mkdirs(); writeText("untouched") }
        val journal = DataRestoreJournal.create(context, original, TrackingData().json(), JSONObject()); plan(journal, "new bytes")
        recoverDataRestores(context, db, original); assertEquals("untouched", live.readText()); db.close()
    }
    @Test fun committedRestoreFinishesCleanupAndKeepsTheNewArchive() {
        val original = AppState(); val db = AppDatabase(context); db.save(null, original)
        val live = File(context.filesDir, "downloads/${"a".repeat(64)}.cbz").apply { parentFile!!.mkdirs(); writeText("old bytes") }
        val journal = DataRestoreJournal.create(context, original, TrackingData().json(), JSONObject()); val (incoming, target) = plan(journal, "new bytes")
        val previous = File(incoming.parentFile!!.parentFile!!.parentFile, ".previous/downloads/${live.name}").apply { parentFile!!.mkdirs() }; assertTrue(live.renameTo(previous)); assertTrue(incoming.renameTo(target))
        val committed = original.copy(settings = JSONObject().put(DataRestoreJournal.marker, journal.getString("id")).toString()); db.save(original, committed)
        assertEquals(committed, recoverDataRestores(context, db, committed)); assertEquals("new bytes", live.readText()); assertFalse(previous.exists()); assertFalse(DataRestoreJournal.file(context).baseFile.exists()); db.close()
    }
    @Test fun sameParentOrphanHistoryAndZeroChapterNumberRoundTripWithoutLosingMediaOnPartialRestore() {
        val active = Chapter("active", "Zero", metadata = "{\"chapterNumber\":0}")
        val row = JSONObject().put("sourceId", "local").put("mangaId", "book").put("chapterId", "gone").put("progress", 4).put("dateRead", 5).put("future", "kept").put("_chapter", Chapter("gone", "Deleted", downloaded = true, pages = listOf("file:///retained.cbz")).json())
        val original = AppState(manga = listOf(Manga("book", "local", "Book", chapters = listOf(active))), records = JSONObject().put("history", JSONArray(listOf(row))).toString())
        val ios = IOSBackupCodec.toIOS(original); assertEquals("gone", ios.getJSONArray("history").getJSONObject(0).getString("chapterId")); assertEquals("kept", ios.getJSONArray("history").getJSONObject(0).getString("future")); assertEquals(0.0, ios.getJSONArray("chapters").getJSONObject(0).getDouble("chapter"), 0.0)
        val incoming = original.copy(records = JSONObject().put("history", JSONArray(listOf(JSONObject(row.toString()).put("_chapter", Chapter("gone", "Deleted", page = 4).json())))).toString())
        val restored = BackupCodec.combine(original, incoming, RestoreOptions(selected = setOf(BackupSection.HISTORY)))
        assertEquals("file:///retained.cbz", JSONObject(restored.records).getJSONArray("history").getJSONObject(0).getJSONObject("_chapter").getJSONArray("pages").getString(0))
    }
}
