package app.monogatari.android.dictionary

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import app.monogatari.android.backup.IOSBackupCodec
import app.monogatari.android.data.AppRepository
import app.monogatari.android.data.AppState
import app.monogatari.android.data.Chapter
import app.monogatari.android.data.Manga
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VocabularyDetailsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun original(page: Int = 7, offset: Int? = 7) = JSONObject().put("sourceId", "s").put("mangaId", "a").put("chapterId", "shared").put("word", "灯舟").put("reading", "あかりぶね")
        .put("sentence", "😀 灯舟, 灯舟.").put("clozeText", "灯舟").put("clozeOffset", offset ?: JSONObject.NULL).put("page", page).put("createdDate", 1000.0)
        .put("future", JSONObject().put("nested", JSONArray(listOf("opaque", 1.5))))

    @Test fun repeatedWordOffsetsCountUtf16AndProtectedEditsMoveOnlyTheExactToken() {
        val sentence = "😀 灯舟, 灯舟."; val offset = sentence.lastIndexOf("灯舟")
        assertEquals(7, offset)
        assertEquals(7..8, VocabularyCloze.range(sentence, "灯舟", offset))
        assertEquals(offset, VocabularyCloze.capture(sentence, "灯舟", offset).offset)
        val changed = VocabularyCloze.edit(sentence, "灯舟", offset, "PREFIX $sentence")!!
        assertEquals(offset + 7, changed.offset); assertEquals("灯舟", changed.sentence.substring(changed.offset!!, changed.offset + 2))
        assertNull(VocabularyCloze.edit(sentence, "灯舟", offset, sentence.replaceRange(offset, offset + 1, "別")))
        assertNull(VocabularyCloze.edit(sentence, "灯舟", offset, sentence.substring(0, offset + 1) + "X" + sentence.substring(offset + 1)))
        assertEquals(offset to offset, VocabularyCloze.selection(offset + 1, offset + 2, VocabularyCloze.range(sentence, "灯舟", offset)))
        assertNull(VocabularyCloze.range("😀", "\uDE00", 1)); assertNull(VocabularyCloze.range(sentence, "灯舟", -1))
        val e = VocabularyEntry(word = "灯舟", sentence = sentence, cloze = "灯舟", clozeOffset = offset)
        assertEquals("😀 灯舟, <b>灯舟</b>.", AnkiContent.sentence(e))
        assertEquals("😀 灯舟, {{c1::灯舟}}.", AnkiContent.values(e).getValue("sentence-cloze"))
        assertEquals("😀 灯舟, ", AnkiContent.values(e).getValue("cloze-prefix")); assertEquals("7", AnkiContent.values(e).getValue("cloze-offset"))
        val beforeIdentical = VocabularyCloze.edit("word word", "word", 5, "word wordword", 5, 5)!!
        assertEquals(9, beforeIdentical.offset)
        assertNull(VocabularyCloze.edit("word word", "word", 5, "word wor", 9, 9))
    }

    @Test fun longPageContextKeepsActualTargetWithoutTruncatingInt16Offsets() {
        val sentence = "a".repeat(40000) + "😀 灯舟 suffix"
        val captured = VocabularyCloze.capture(sentence, "灯舟", sentence.indexOf("灯舟"))
        assertTrue(captured.offset!! in 0..32767); assertEquals("灯舟", captured.sentence.substring(captured.offset, captured.offset + 2))
        assertTrue(captured.sentence.endsWith("suffix")); assertNull(VocabularyCloze.capture("not a match", "灯舟", 0).offset)
    }

    @Test fun iosAndNativeRestorePreserveSecondOffsetUnknownFieldsAndSourceMigration() = runBlocking {
        val dictionary = DictionaryRepository.get(context)
        dictionary.importIosVocabulary(JSONArray().put(original()))
        val old = dictionary.vocabulary.value.single(); assertEquals(6, old.page); assertEquals(7, old.clozeOffset)
        val native = dictionary.exportBackup(); native.getJSONArray("vocabulary").getJSONObject(0).put("futureNative", JSONObject().put("value", "retain"))
        dictionary.importBackup(native)
        dictionary.migrateSourceKeys("s", mapOf("a" to "b"), mapOf("a" to mapOf("shared" to "new-shared")))
        val changed = dictionary.updateContext(old.id, "!" + old.sentence, old.clozeOffset!! + 1)
        assertEquals("s::b", changed.mangaId); assertEquals("new-shared", changed.chapterKey)
        assertEquals("retain", changed.json().getJSONObject("futureNative").getString("value"))
        val exported = dictionary.exportIosVocabulary().getJSONObject(0)
        assertEquals("b", exported.getString("mangaId")); assertEquals("new-shared", exported.getString("chapterId")); assertEquals(7, exported.getInt("page")); assertEquals(8, exported.getInt("clozeOffset"))
        assertEquals(1.5, exported.getJSONObject("future").getJSONArray("nested").getDouble(1), 0.0)
        val output = ByteArrayOutputStream(); IOSBackupCodec.writeBinary(JSONObject().put("vocabulary", JSONArray().put(exported)), output)
        val roundTrip = IOSBackupCodec.decode(output.toByteArray()).getJSONArray("vocabulary").getJSONObject(0)
        assertEquals(8, roundTrip.getInt("clozeOffset")); assertEquals(7, roundTrip.getInt("page")); assertEquals("opaque", roundTrip.getJSONObject("future").getJSONArray("nested").getString(0))
    }

    @Test fun originalNullAndUnspecifiedPageAndSignedOffsetsAreNotInvented() = runBlocking {
        val dictionary = DictionaryRepository.get(context)
        dictionary.importIosVocabulary(JSONArray().put(original(0, null)))
        assertNull(dictionary.vocabulary.value.single().clozeOffset)
        val unspecified = dictionary.exportIosVocabulary().getJSONObject(0)
        assertEquals(0, unspecified.getInt("page")); assertTrue(unspecified.isNull("clozeOffset"))
        dictionary.importIosVocabulary(JSONArray().put(original(1, -32768)))
        assertEquals(0, dictionary.vocabulary.value.single().page); assertEquals(-32768, dictionary.exportIosVocabulary().getJSONObject(0).getInt("clozeOffset"))
        assertNull(VocabularyCloze.range(dictionary.vocabulary.value.single().sentence, "灯舟", -32768))
    }

    @Test fun fractionalAndOverflowOffsetsRejectBeforeChangingExistingVocabulary() = runBlocking {
        val dictionary = DictionaryRepository.get(context); dictionary.saveVocabulary(VocabularyEntry(word = "Kept"))
        val old = dictionary.vocabulary.value
        for (value in listOf<Any>(1.5, 32768, -32769, "7")) {
            try { dictionary.importIosVocabulary(JSONArray().put(original().put("clozeOffset", value))); fail("Expected offset rejection: $value") } catch (_: IllegalArgumentException) { }
            assertEquals(old, dictionary.vocabulary.value)
            val row = VocabularyEntry(word = "invalid").json().put("clozeOffset", value)
            try { dictionary.importBackup(JSONObject().put("vocabulary", JSONArray().put(row))); fail("Expected native offset rejection: $value") } catch (_: IllegalArgumentException) { }
            assertEquals(old, dictionary.vocabulary.value)
        }
    }

    @Test fun legacyAndroidIosImportRecoversOriginalOffsetPageAndMetadataOnlyOnce() = runBlocking {
        val dictionary = DictionaryRepository.get(context); val raw = original()
        val old = VocabularyEntry(id = "legacy", word = "灯舟", reading = "あかりぶね", sentence = raw.getString("sentence"), mangaId = "s::a", chapterKey = "shared", page = 7, cloze = "灯舟", createdAt = 1000000L)
        dictionary.saveVocabulary(old)
        val legacy = old.json().apply { remove("clozeOffset"); remove("iosFields"); put("nativeOpaque", "retained") }
        SQLiteDatabase.openDatabase(context.getDatabasePath("dictionary.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { db -> db.update("vocabulary", ContentValues().apply { put("payload", legacy.toString()) }, "id=?", arrayOf(old.id)) }
        dictionary.recoverLegacyVocabulary(JSONArray().put(raw)); dictionary.recoverLegacyVocabulary(JSONArray().put(raw))
        val recovered = dictionary.vocabulary.value.single(); assertEquals(6, recovered.page); assertEquals(7, recovered.clozeOffset)
        assertEquals("retained", recovered.json().getString("nativeOpaque")); assertEquals("opaque", dictionary.exportIosVocabulary().getJSONObject(0).getJSONObject("future").getJSONArray("nested").getString(0))
    }

    @Test fun cachedAndSourceDeletedChaptersUseCompoundIdentityWithoutWritingProgress() = runBlocking {
        val repo = AppRepository(context)
        val a = Manga("a", "removed", "A", chapters = listOf(Chapter("shared", "A chapter", page = 7, total = 10, pages = listOf("file:///A.png"), downloaded = true)))
        val b = Manga("b", "removed", "B", chapters = listOf(Chapter("shared", "B chapter", page = 2, total = 10, pages = listOf("file:///B.png"), downloaded = true)))
        val orphan = Chapter("gone", "Downloaded removed chapter", page = 4, total = 10, pages = listOf("file:///orphan-A.png"), downloaded = true)
        val history = JSONArray().put(JSONObject().put("sourceId", "removed").put("mangaId", "b").put("chapterId", "gone").put("_chapter", Chapter("gone", "Wrong B", pages = listOf("file:///orphan-B.png")).json()))
            .put(JSONObject().put("sourceId", "removed").put("mangaId", "a").put("chapterId", "gone").put("_chapter", orphan.json()))
        repo.change { AppState(manga = listOf(a, b), records = JSONObject().put("history", history).toString()) }; val before = repo.state.value
        val cached = VocabularySource.resolve(repo, VocabularyEntry(word = "term", mangaId = a.id, chapterKey = "shared"))
        assertEquals(a, cached.manga); assertEquals("file:///A.png", cached.chapter!!.pages.single())
        val gone = VocabularySource.resolve(repo, VocabularyEntry(word = "term", mangaId = a.id, chapterKey = "gone"))
        assertEquals(orphan, gone.chapter)
        val absent = VocabularySource.resolve(repo, VocabularyEntry(word = "term", mangaId = a.id, chapterKey = "missing"))
        assertEquals(a, absent.manga); assertNull(absent.chapter); assertEquals(before, repo.state.value)
    }

    @Test fun apkgUsesExactSecondOccurrenceAndPreservesReadableSqliteCollection() = runBlocking {
        val word = VocabularyEntry(word = "灯舟", sentence = "😀 灯舟, 灯舟.", cloze = "灯舟", clozeOffset = 7)
        val bytes = ByteArrayOutputStream(); AnkiExporter.exportApkg(context, listOf(word), bytes)
        val file = File(context.cacheDir, "vocab-details-collection.anki2")
        ZipInputStream(bytes.toByteArray().inputStream()).use { zip -> while (true) { val entry = zip.nextEntry ?: break; if (entry.name == "collection.anki2") file.outputStream().use { zip.copyTo(it) }; zip.closeEntry() } }
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA integrity_check", null).use { assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0)) }
            db.rawQuery("SELECT flds FROM notes", null).use { assertTrue(it.moveToFirst()); assertEquals("😀 灯舟, <b>灯舟</b>.", it.getString(0).split('\u001f')[3]) }
        }
    }
    @Test fun actualAnkiConnectRequestUsesExactOffsetAndCurlyFieldAliases() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"error\":null,\"result\":[true]}")); server.enqueue(MockResponse().setBody("{\"error\":null,\"result\":42}"))
            val entry = VocabularyEntry(word = "灯舟", sentence = "😀 灯舟, 灯舟.", cloze = "灯舟", clozeOffset = 7)
            assertEquals(42L, AnkiConnect(server.url("/").toString()).add(entry, "Default", "Cloze", mapOf("Text" to "{sentence-cloze}", "Expression" to "expression")))
            val check = JSONObject(server.takeRequest().body.readUtf8()).getJSONObject("params").getJSONArray("notes").getJSONObject(0).getJSONObject("fields")
            assertEquals("😀 灯舟, {{c1::灯舟}}.", check.getString("Text")); assertEquals("灯舟", check.getString("Expression"))
            val add = JSONObject(server.takeRequest().body.readUtf8()).getJSONObject("params").getJSONObject("note").getJSONObject("fields")
            assertEquals(check.toString(), add.toString())
        }
    }

    @Test fun originalUiFixtureImportsActualRichMediaFrequencyPitchAndRomaji() = runBlocking {
        val dictionary = DictionaryRepository.get(context)
        val info = requireNotNull(javaClass.classLoader?.getResourceAsStream("ui-fixtures/yomitan-ui-fixture.zip")).use { dictionary.importDictionary(it) }
        assertEquals(5, info.terms); assertEquals(setOf("term", "kanji", "frequency", "pitch"), info.kinds.split(',').toSet())
        val entry = dictionary.lookup("AidokuBeacon").single(); assertTrue(DictionaryGlossary.rich(entry.glossary)); assertTrue(entry.frequency.contains("17")); assertTrue(entry.pitch.contains("2"))
        val document = DictionaryGlossary.document(JSONArray(entry.glossary), entry.dictionaryId, DictionaryGlossary.css(context, entry.dictionaryId))
        assertTrue(document.contains("BEACON_END_24")); assertTrue(document.contains("<ruby")); assertTrue(document.contains("color:#d00000"))
        assertEquals("灯す", dictionary.lookup("灯した").first().expression); assertEquals("灯す", dictionary.lookup("tomosu").first().expression)
        val image = DictionaryGlossary.response(context, entry.dictionaryId, android.net.Uri.parse(DictionaryGlossary.origin(entry.dictionaryId) + "media/beacon.png"))
        assertEquals(200, image.statusCode); image.data.use { assertArrayEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10), it.readNBytes(8)) }
    }
}
