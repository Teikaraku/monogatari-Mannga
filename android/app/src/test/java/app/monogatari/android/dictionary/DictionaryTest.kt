package app.monogatari.android.dictionary

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DictionaryTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun zip(entries: Map<String, String>): ByteArray = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip -> entries.forEach { (name, value) -> zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry() } } }.toByteArray()
    @Test fun importsAllFourDictionaryKindsAndQueriesMetadataAndDeinflection() = runBlocking {
        val repo = DictionaryRepository.get(context)
        val dictionary = repo.importDictionary(zip(mapOf("index.json" to """{"title":"Fixture-${UUID.randomUUID()}","revision":"1"}""", "term_bank_1.json" to """[["食べる","たべる","v1","v1",0,["to eat"],1,""]]""",
            "kanji_bank_1.json" to """[["食","ショク","たべる","",["eat"],{}]]""", "term_meta_bank_1.json" to """[["食べる","freq",{"reading":"たべる","frequency":123}],["食べる","pitch",{"reading":"たべる","pitches":[{"position":2}]}]]""")).inputStream())
        assertEquals(setOf("term", "kanji", "frequency", "pitch"), dictionary.kinds.split(',').toSet())
        val entries = repo.lookup("食べました"); assertEquals("食べる", entries.first().expression); assertTrue(entries.first().definition.contains("eat")); assertTrue(entries.first().frequency.contains("123")); assertTrue(entries.first().pitch.contains("2"))
        assertEquals("食べる", repo.lookup("taberu").first().expression); assertEquals("食", repo.lookup("食").first().expression)
        repo.configure(dictionary, enabled = false); assertFalse(repo.lookup("食べる").any { it.dictionary == dictionary.title })
    }
    @Test fun malformedImportRollsBackAndRejectsZipTraversal() = runBlocking {
        val repo = DictionaryRepository.get(context); repo.load(); val before = repo.dictionaries.value.size
        try { repo.importDictionary(zip(mapOf("index.json" to """{"title":"Broken"}""", "term_bank_1.json" to """[["partial","","","",0,["definition"]],BROKEN]""")).inputStream()); fail("Expected invalid JSON") } catch (_: Exception) { }
        assertEquals(before, repo.dictionaries.value.size)
        try { repo.importDictionary(zip(mapOf("../../escaped" to "bad", "index.json" to """{"title":"Escape"}""")).inputStream()); fail("Expected path rejection") } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("路径")) }
        assertFalse(File(context.filesDir.parentFile, "escaped").exists())
    }
    @Test fun longestMatchAndVocabularyDuplicatesUseReadingIdentity() = runBlocking {
        val repo = DictionaryRepository.get(context); val word = "term${UUID.randomUUID().toString().take(8)}"
        repo.importDictionary(zip(mapOf("index.json" to """{"title":"Longest-${UUID.randomUUID()}"}""", "term_bank_1.json" to """[["$word","read","","",0,["meaning"],1,""]]""")).inputStream())
        assertEquals(word, repo.lookupAt("$word suffix", 0).first)
        assertTrue(repo.saveVocabulary(VocabularyEntry(word = word, reading = "read"))); assertFalse(repo.saveVocabulary(VocabularyEntry(word = word, reading = "read"))); assertTrue(repo.saveVocabulary(VocabularyEntry(word = word, reading = "other")))
    }
    @Test fun kanaRomajiAndConjugationsIncludeOriginalDictionaryForms() {
        assertEquals("たべる", JapaneseLookup.hiragana("タベル")); assertTrue("きって" in JapaneseLookup.romaji("kitte")); assertTrue("よんや" in JapaneseLookup.romaji("yonya")); assertTrue("よにゃ" in JapaneseLookup.romaji("yonya"))
        assertTrue("食べる" in JapaneseLookup.candidates("食べました")); assertTrue("書く" in JapaneseLookup.candidates("書いた")); assertTrue("良い" in JapaneseLookup.candidates("良くなかった"))
    }
    @Test fun vocabularyIdentifiersAndAnkiMediaCannotReadOrWriteOutsideApplicationStorage() = runBlocking {
        val repo = DictionaryRepository.get(context); val before = repo.vocabulary.value
        try { repo.importBackup(JSONObject().put("vocabulary", org.json.JSONArray().put(VocabularyEntry(id = "../../credentials", word = "escape").json()))); fail("Expected unsafe identifier rejection") } catch (_: IllegalArgumentException) { }
        assertEquals(before, repo.vocabulary.value)
        val outside = File(context.filesDir.parentFile, "outside-anki.png"); val bitmap = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888); outside.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        try {
            MockWebServer().use { server ->
                try { AnkiConnect(server.url("/").toString(), context = context).add(VocabularyEntry(word = "word", imagePath = outside.path), "Default", "Basic", mapOf("Front" to "expression")); fail("Expected outside media rejection") } catch (_: IllegalArgumentException) { }
                assertEquals(0, server.requestCount)
            }
        } finally { outside.delete() }
    }
    @Test fun ankiPackageContainsReadableCollectionCardsModelsAndMedia() = runBlocking {
        val image = File(context.cacheDir, "anki-image.png"); val bitmap = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888); image.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle(); val output = ByteArrayOutputStream()
        AnkiExporter.exportApkg(context, listOf(VocabularyEntry(word = "食べる", reading = "たべる", definition = "eat", sentence = "パンを食べる", imagePath = image.path)), output)
        val directory = File(context.cacheDir, "apkg-test").apply { mkdirs() }; val names = mutableListOf<String>()
        ZipInputStream(output.toByteArray().inputStream()).use { zip -> while (true) { val entry = zip.nextEntry ?: break; names += entry.name; File(directory, entry.name).outputStream().use { zip.copyTo(it) }; zip.closeEntry() } }
        assertTrue(names.containsAll(listOf("collection.anki2", "media", "0")))
        val media = JSONObject(File(directory, "media").readText()); assertTrue(media.getString("0").endsWith(".png"))
        SQLiteDatabase.openDatabase(File(directory, "collection.anki2").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT flds FROM notes", null).use { assertTrue(it.moveToFirst()); assertTrue(it.getString(0).contains("食べる")); assertTrue(it.getString(0).contains("<img")) }
            db.rawQuery("SELECT count(*) FROM cards", null).use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            db.rawQuery("SELECT models FROM col", null).use { it.moveToFirst(); assertEquals(1, JSONObject(it.getString(0)).length()) }
        }
    }
    @Test fun ankiConnectChecksDuplicatesBeforeAddingAndSendsV6Note() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"result":[true],"error":null}""")); server.enqueue(MockResponse().setBody("""{"result":123,"error":null}"""))
            val id = AnkiConnect(server.url("/").toString()).add(VocabularyEntry(word = "word", definition = "meaning"), "Default", "Basic", mapOf("Front" to "expression", "Back" to "glossary"))
            assertEquals(123, id)
            val check = JSONObject(server.takeRequest().body.readUtf8()); assertEquals("canAddNotes", check.getString("action")); assertEquals(6, check.getInt("version"))
            val add = JSONObject(server.takeRequest().body.readUtf8()); assertEquals("addNote", add.getString("action")); assertFalse(add.getJSONObject("params").getJSONObject("note").getJSONObject("options").getBoolean("allowDuplicate"))
        }
    }
    @Test fun csvEscapesMultilineAndQuotationMarks() {
        assertTrue(AnkiExporter.csv(listOf(VocabularyEntry(word = "a\"b", sentence = "line1\nline2"))).contains("\"a\"\"b\""))
    }
}
