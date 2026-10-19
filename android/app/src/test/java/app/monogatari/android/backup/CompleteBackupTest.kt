package app.monogatari.android.backup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.monogatari.android.data.*
import app.monogatari.android.dictionary.DictionaryRepository
import app.monogatari.android.dictionary.VocabularyEntry
import app.monogatari.android.reader.ReaderOptionStore
import app.monogatari.android.reader.ReaderOptions
import com.dd.plist.NSObject
import com.dd.plist.PropertyListParser
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Date
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [PosixAtomicFileShadow::class])
class CompleteBackupTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    @Before fun initializeWorkManager() { if (runCatching { androidx.work.WorkManager.getInstance(context) }.isFailure) androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build()) }
    private fun image(file: File): File { file.parentFile?.mkdirs(); val bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888); bitmap.eraseColor(Color.MAGENTA); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle(); return file }
    private fun zip(file: File, entries: Map<String, ByteArray>): File { file.parentFile?.mkdirs(); ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (name, value) -> zip.putNextEntry(ZipEntry(name)); zip.write(value); zip.closeEntry() } }; return file }
    private fun rewriteZip(file: File, transform: (String, ByteArray) -> ByteArray): File { val target = File(context.cacheDir, "changed-${UUID.randomUUID()}.zip"); ZipFile(file).use { zip -> zip(target, zip.entries().asSequence().associate { entry -> entry.name to transform(entry.name, zip.getInputStream(entry).use { it.readBytes() }) }) }; return target }
    private fun fixture(): JSONObject = JSONObject("""{"name":"Original","date":1720000000,"version":"0.7.0","future":{"nested":[1,"unchanged"]},
        "manga":[{"id":"m","sourceId":"en.fixture","title":"Original","status":1,"nsfw":0,"viewer":1,"chapterFlags":15,"editedKeys":4,"futureField":{"x":true}}],
        "library":[{"sourceId":"en.fixture","mangaId":"m","dateAdded":1700000000,"lastOpened":1710000000,"lastUpdated":1710000001,"categories":["Favorite"]}],
        "chapters":[{"id":"c","sourceId":"en.fixture","mangaId":"m","title":"C","chapter":2.5,"volume":2,"lang":"ja","sourceOrder":0,"futureField":13}],
        "history":[{"sourceId":"en.fixture","mangaId":"m","chapterId":"c","progress":7,"total":20,"completed":false,"dateRead":1720000000}],
        "categories":[{"title":"Favorite","sort":0,"group":true,"data":"AQID","futureField":"kept"}],
        "readingSessions":[{"sourceId":"en.fixture","mangaId":"m","chapterId":"c","startDate":1720000000,"endDate":1720000010,"pagesRead":6}],
        "updates":[{"sourceId":"en.fixture","mangaId":"m","chapterId":"c","date":1720000001,"viewed":false}],
        "settings":{"Reader.readingMode":"rtl","Future.config":{"a":[1,true]}},"sourceLists":["https://example.com/list/"],"sources":["en.fixture"]}""")
    @Test fun binaryPlistRetainsTypedUnknownDataDatesAndAllOriginalMetadata() {
        val original = fixture(); val unknown = mapOf("binary" to byteArrayOf(0, 127, -1), "timestamp" to Date(1720000000123))
        val types = JSONObject().put("futureBinary.binary", "data").put("futureBinary.timestamp", "date")
        original.put("futureBinary", JSONObject().put("binary", android.util.Base64.encodeToString(unknown["binary"] as ByteArray, 2)).put("timestamp", 1720000000.123)).put("_aidokuPlistTypes", types)
        val output = ByteArrayOutputStream(); IOSBackupCodec.writeBinary(original, output)
        assertTrue(output.toByteArray().toString(Charsets.US_ASCII).startsWith("bplist"))
        val native = IOSBackupCodec.fromIOS(IOSBackupCodec.decode(output.toByteArray())); assertEquals(6, native.manga.single().chapters.single().page)
        val exported = IOSBackupCodec.toIOS(native); assertEquals(app.monogatari.android.BuildConfig.VERSION_NAME, exported.getString("version")); assertEquals(7, exported.getJSONArray("history").getJSONObject(0).getInt("progress"))
        assertTrue(exported.getJSONArray("manga").getJSONObject(0).getJSONObject("futureField").getBoolean("x")); assertEquals(13, exported.getJSONArray("chapters").getJSONObject(0).getInt("futureField"))
        assertEquals("kept", exported.getJSONArray("categories").getJSONObject(0).getString("futureField")); assertEquals(6, exported.getJSONArray("readingSessions").getJSONObject(0).getInt("pagesRead"))
        assertEquals("unchanged", exported.getJSONObject("future").getJSONArray("nested").getString(1))
        val second = ByteArrayOutputStream(); IOSBackupCodec.writeBinary(exported, second)
        val java = PropertyListParser.parse(second.toByteArray()).toJavaObject() as Map<*, *>
        val extras = java["futureBinary"] as Map<*, *>; assertArrayEquals(byteArrayOf(0, 127, -1), extras["binary"] as ByteArray); assertEquals(Date(1720000000123), extras["timestamp"])
        val category = (java["categories"] as Array<*>).single() as Map<*, *>; assertArrayEquals(byteArrayOf(1, 2, 3), category["data"] as ByteArray)
    }
    @Test fun unreadMinusOneProgressRoundTripsAndOverflowIsRejectedBeforeRestore() {
        val original = fixture(); original.getJSONArray("history").getJSONObject(0).put("progress", -1)
        assertEquals(-1, IOSBackupCodec.toIOS(IOSBackupCodec.fromIOS(original)).getJSONArray("history").getJSONObject(0).getInt("progress"))
        original.getJSONArray("history").getJSONObject(0).put("progress", 40000)
        try { IOSBackupCodec.fromIOS(original); fail("Overflow must fail before state mutation") } catch (_: IllegalArgumentException) { }
    }
    @Test fun originalIntegerFieldsRejectFractionalValuesWhileChapterDatesAndUnknownNumbersRemainExact() {
        for ((section, field) in listOf("manga" to "status", "manga" to "editedKeys", "chapters" to "sourceOrder", "history" to "progress", "history" to "total", "readingSessions" to "pagesRead", "categories" to "sort")) {
            val original = fixture(); original.getJSONArray(section).getJSONObject(0).put(field, 1.5)
            try { IOSBackupCodec.fromIOS(original); fail("$section.$field must reject a fractional integer") } catch (_: IllegalArgumentException) { }
            val binary = ByteArrayOutputStream(); IOSBackupCodec.writeBinary(original, binary)
            try { IOSBackupCodec.fromIOS(IOSBackupCodec.decode(binary.toByteArray())); fail("Binary $section.$field must reject a fractional integer") } catch (_: IllegalArgumentException) { }
        }
        val original = fixture().put("date", 1720000000.125).put("futureNumber", 1.5)
        original.getJSONArray("history").getJSONObject(0).put("dateRead", 1720000000.125)
        val binary = ByteArrayOutputStream(); IOSBackupCodec.writeBinary(original, binary)
        val exported = IOSBackupCodec.toIOS(IOSBackupCodec.fromIOS(IOSBackupCodec.decode(binary.toByteArray())))
        assertEquals(2.5, exported.getJSONArray("chapters").getJSONObject(0).getDouble("chapter"), 0.0); assertEquals(1.5, exported.getDouble("futureNumber"), 0.0); assertEquals(1720000000.125, exported.getJSONArray("history").getJSONObject(0).getDouble("dateRead"), 0.0)
    }
    @Test fun opaqueMangaAndChapterKeysCannotCollideOrBorrowOtherHistoryMetadata() {
        fun manga(key: String) = JSONObject().put("id", key).put("sourceId", "fixture").put("title", key)
        fun chapter(manga: String, key: String, future: String) = JSONObject().put("sourceId", "fixture").put("mangaId", manga).put("id", key).put("lang", "en").put("future", future)
        fun history(manga: String, key: String, page: Int, future: String) = JSONObject().put("sourceId", "fixture").put("mangaId", manga).put("chapterId", key).put("progress", page).put("total", 10).put("dateRead", 1720000000 + page).put("future", future)
        val original = JSONObject().put("manga", JSONArray(listOf(manga("book::c"), manga("book"))))
            .put("chapters", JSONArray(listOf(chapter("book::c", "1", "first chapter"), chapter("book", "c::1", "second chapter"))))
            .put("history", JSONArray(listOf(history("book::c", "1", 2, "first history"), history("book", "c::1", 7, "second history"), history("book", "removed::chapter", 3, "orphan history"))))
        val binary = ByteArrayOutputStream(); IOSBackupCodec.writeBinary(original, binary)
        val native = IOSBackupCodec.fromIOS(IOSBackupCodec.decode(binary.toByteArray()))
        assertEquals(1, native.manga.first { it.key == "book::c" }.chapters.single().page); assertEquals(6, native.manga.first { it.key == "book" }.chapters.first { it.key == "c::1" }.page)
        val exported = IOSBackupCodec.toIOS(native.copy(manga = native.manga.map { m -> if (m.key == "book") m.copy(chapters = m.chapters.filterNot { it.key == "removed::chapter" }) else m }))
        val chapters = exported.getJSONArray("chapters").objects(); assertEquals("first chapter", chapters.first { it.optString("mangaId") == "book::c" }.getString("future")); assertEquals("second chapter", chapters.first { it.optString("mangaId") == "book" }.getString("future"))
        val histories = exported.getJSONArray("history").objects(); assertEquals(3, histories.size)
        assertEquals("first history", histories.first { it.optString("mangaId") == "book::c" }.getString("future")); assertEquals("second history", histories.first { it.optString("chapterId") == "c::1" }.getString("future")); assertEquals("orphan history", histories.first { it.optString("chapterId") == "removed::chapter" }.getString("future"))
    }
    @Test fun unknownTrackerRowsSurviveExportWhileUnboundKnownTrackersAreRemoved() = runBlocking {
        val repo = AppRepository(context); val future = JSONObject().put("sourceId", "local").put("mangaId", "book").put("trackerId", "future-tracker").put("id", "opaque-target").put("future", JSONObject().put("binary", "kept"))
        val removed = JSONObject().put("sourceId", "local").put("mangaId", "book").put("trackerId", "anilist").put("id", "123")
        repo.change { it.copy(backupExtras = JSONObject().put("trackItems", JSONArray(listOf(future, removed))).toString()) }
        app.monogatari.android.tracking.TrackingRepository.get(context).restoreSnapshot(app.monogatari.android.tracking.TrackingData().json())
        val output = ByteArrayOutputStream(); BackupCodec.write(repo, output, BackupFormat.IOS_JSON, BackupOptions(selected = setOf(BackupSection.TRACKING)))
        val exported = JSONObject(output.toString()).getJSONArray("trackItems"); assertEquals(1, exported.length()); assertEquals("future-tracker", exported.getJSONObject(0).getString("trackerId")); assertEquals("kept", exported.getJSONObject(0).getJSONObject("future").getString("binary"))
    }
    @Test fun dictionaryAndAnkiSettingsRestoreOnlyWithTheSettingsSectionEvenWhenVocabularyIsExcluded() = runBlocking {
        val repo = AppRepository(context); val dictionary = DictionaryRepository.get(context)
        val optionsKey = "Reader.androidOptions.local.complex.book::chapter"; val optionsValue = """{"fit":"width","contrast":1.2,"future":{"nested":["a::b.c"]}}"""
        repo.settings.put(optionsKey, optionsValue)
        dictionary.preferences.edit().putString("ocrLanguage", "ko").putString("anki.deck", "Original deck").commit()
        val file = File(context.cacheDir, "only-settings.aib"); file.outputStream().use { BackupCodec.write(repo, it, BackupFormat.IOS_BINARY, BackupOptions(selected = setOf(BackupSection.SETTINGS))) }
        repo.settings.put(optionsKey, "{}")
        dictionary.preferences.edit().putString("ocrLanguage", "en").putString("anki.deck", "Changed deck").commit()
        BackupCodec.restore(repo, Uri.fromFile(file), RestoreOptions(selected = setOf(BackupSection.SETTINGS)))
        assertEquals("ko", dictionary.preferences.getString("ocrLanguage", "")); assertEquals("Original deck", dictionary.preferences.getString("anki.deck", "")); assertEquals(optionsValue, repo.settings.string(optionsKey))
        val vocab = File(context.cacheDir, "vocab.zip"); vocab.outputStream().use { BackupCodec.write(repo, it, BackupFormat.ANDROID_ZIP, BackupOptions(selected = setOf(BackupSection.VOCABULARY, BackupSection.SETTINGS))) }
        dictionary.preferences.edit().putString("anki.deck", "Keep this deck").commit(); BackupCodec.restore(repo, Uri.fromFile(vocab), RestoreOptions(selected = setOf(BackupSection.VOCABULARY)))
        assertEquals("Keep this deck", dictionary.preferences.getString("anki.deck", ""))
    }
    @Test fun originalBuiltInSourceVarintsPreserveUnicodeAndStableIds() {
        for (type in listOf("komga", "kavita", "suwayomi")) {
            val source = SourceConfig("$type-original-id", "漫画".repeat(100), type, "https://example.com/server/")
            assertEquals(source, CustomSourceCodec.decode(CustomSourceCodec.encode(source)))
        }
        try { CustomSourceCodec.decode(byteArrayOf(1, -128)); fail("Truncated varint") } catch (_: IllegalArgumentException) { }
    }
    @Test fun nativeBackupRestoresActualImagesEpubCssResourcesCbzAndVocabularyContext() = runBlocking {
        val repo = AppRepository(context); val dictionary = DictionaryRepository.get(context)
        val book = File(context.filesDir, "local/epub-fixture"); val picture = image(File(book, "resources/picture.png")); val css = File(book, "styles/style.css").apply { parentFile.mkdirs(); writeText("body { background: url('${Uri.fromFile(picture)}'); }") }
        val html = File(book, "text/chapter.xhtml").apply { parentFile.mkdirs(); writeText("<html><link href='${Uri.fromFile(css)}'/><img src='${Uri.fromFile(picture)}'/></html>") }
        val archiveId = AppRepository.hash("fixture-download"); val archive = zip(File(context.filesDir, "downloads/$archiveId.cbz"), mapOf("pages/1.png" to picture.readBytes()))
        val archiveUri = "aidoku-archive://download/$archiveId?entry=pages%2F1.png"
        val media = image(File(context.filesDir, "vocabulary-media/fixture.png")); dictionary.importBackup(JSONObject().put("vocabulary", JSONArray().put(VocabularyEntry(word = "食べる", imagePath = media.path).json())))
        repo.change { AppState(manga = listOf(Manga("epub", "local", "EPUB", cover = Uri.fromFile(picture).toString(), inLibrary = true, chapters = listOf(Chapter("html", "Text", html = Uri.fromFile(html).toString()), Chapter("cbz", "Comic", pages = listOf(archiveUri), downloaded = true, total = 1)))), settings = "{\"Reader.readingMode\":\"ltr\"}") }
        ReaderOptionStore(context).save(ReaderOptions(fontSize = 31), "local::epub")
        val backup = File(context.cacheDir, "complete.zip"); backup.outputStream().use { BackupCodec.write(repo, it, BackupFormat.ANDROID_ZIP, BackupOptions(selected = BackupSection.entries.toSet()), "Portable") }
        ZipFile(backup).use { zip -> val manifest = JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().use { it.readText() }); assertEquals(app.monogatari.android.BuildConfig.VERSION_NAME, manifest.getString("appVersion")); assertEquals(2, manifest.getInt("version")) }
        book.deleteRecursively(); archive.delete(); media.delete(); repo.change { AppState() }; dictionary.importBackup(JSONObject())
        BackupCodec.restore(repo, Uri.fromFile(backup))
        val restored = repo.state.value.manga.single(); assertEquals("ltr", repo.state.value.preferences.mode)
        val restoredHtml = File(Uri.parse(restored.chapters[0].html).path!!); assertTrue(restoredHtml.isFile); assertFalse(restoredHtml.readText().contains(Uri.fromFile(book).toString()))
        val urls = Regex("file://[^']+").findAll(restoredHtml.readText()).map { File(Uri.parse(it.value).path!!) }.toList(); assertEquals(2, urls.size); assertTrue(urls.all(File::isFile)); assertTrue(urls.first { it.extension == "css" }.readText().contains("restored"))
        assertEquals(archiveUri, restored.chapters[1].pages.single()); ZipFile(File(context.filesDir, "downloads/$archiveId.cbz")).use { assertNotNull(it.getEntry("pages/1.png")) }
        assertTrue(File(dictionary.vocabulary.value.single().imagePath).isFile); assertEquals(31, ReaderOptionStore(context).get("local::epub").fontSize)
        assertFalse(File(context.filesDir, "restored").walkTopDown().any { it.name == "credentials.json" })
        // Back up a previously restored EPUB; sibling CSS and images must remain portable again.
        val second = File(context.cacheDir, "second.zip"); second.outputStream().use { BackupCodec.write(repo, it, BackupFormat.ANDROID_ZIP, BackupOptions(BackupSection.entries.toSet())) }
        File(context.filesDir, "restored").deleteRecursively(); BackupCodec.restore(repo, Uri.fromFile(second))
        val secondHtml = File(Uri.parse(repo.state.value.manga.single().chapters[0].html).path!!)
        assertTrue(Regex("file://[^']+").findAll(secondHtml.readText()).all { File(Uri.parse(it.value).path!!).isFile })
    }
    @Test fun nativeVocabularyOnlyRestoreKeepsUtf16ClozeOffsetUnknownFieldsAndOtherBookProgress() = runBlocking {
        val repo = AppRepository(context)
        val book = Manga("kept", "local", "Kept progress", chapters = listOf(Chapter("shared", "Kept chapter", page = 7, total = 10, metadata = "{\"scrollPosition\":0.75}")))
        repo.change { it.copy(manga = listOf(book)) }
        val dictionary = DictionaryRepository.get(context)
        val row = VocabularyEntry(id = "cloze-fixture", word = "灯舟", sentence = "😀 灯舟, 灯舟.", mangaId = "removed::source-book", chapterKey = "shared", page = 2, cloze = "灯舟", clozeOffset = 7,
            iosFields = "{\"future\":{\"typed\":[1.5,\"kept\"]}}", unknownFields = "{\"futureNative\":{\"kept\":true}}")
        dictionary.saveVocabulary(row)
        val backup = File(context.cacheDir, "cloze-only.zip")
        backup.outputStream().use { BackupCodec.write(repo, it, BackupFormat.ANDROID_ZIP, BackupOptions(setOf(BackupSection.VOCABULARY))) }
        dictionary.clearVocabulary()
        BackupCodec.restore(repo, Uri.fromFile(backup), RestoreOptions(setOf(BackupSection.VOCABULARY)))
        val restored = dictionary.vocabulary.value.single()
        assertEquals(7, restored.clozeOffset); assertEquals(2, restored.page); assertEquals("😀 灯舟, <b>灯舟</b>.", app.monogatari.android.dictionary.AnkiContent.sentence(restored))
        assertTrue(restored.json().getJSONObject("futureNative").getBoolean("kept")); assertEquals(1.5, JSONObject(restored.iosFields).getJSONObject("future").getJSONArray("typed").getDouble(0), 0.0)
        assertEquals(3, dictionary.exportIosVocabulary().getJSONObject(0).getInt("page")); assertEquals(book, repo.state.value.manga.single())
    }
    @Test fun partialRestorePreservesUnselectedHistorySettingsAndMergesLibrary() {
        val before = AppState(manga = listOf(Manga("m", "s", "Existing", inLibrary = true, chapters = listOf(Chapter("c", "C", page = 9, total = 20, readAt = 2000)))), settings = "{\"Reader.readingMode\":\"rtl\"}")
        val next = AppState(manga = listOf(Manga("m", "s", "Replacement", inLibrary = true, chapters = listOf(Chapter("c", "C", page = 1, total = 20, readAt = 1000))), Manga("new", "s", "New", inLibrary = true)), settings = "{\"Reader.readingMode\":\"ltr\"}")
        val selected = setOf(BackupSection.LIBRARY, BackupSection.CHAPTERS)
        val restored = BackupCodec.combine(before, next, RestoreOptions(selected, merge = true))
        assertEquals(2, restored.manga.size); assertEquals(9, restored.manga.first().chapters.single().page); assertEquals(before.settings, restored.settings)
    }
    @Test fun historyOnlyOriginalBackupRestoresProgressIntoExistingChapterWithoutReplacingLibrary() {
        val backup = fixture(); listOf("manga", "chapters", "library", "settings").forEach(backup::remove)
        val incoming = IOSBackupCodec.fromIOS(backup)
        val existing = AppState(manga = listOf(Manga("m", "en.fixture", "Kept title", inLibrary = true, chapters = listOf(Chapter("c", "Kept chapter", total = 20)))))
        val result = BackupCodec.combine(existing, incoming, RestoreOptions(setOf(BackupSection.HISTORY)))
        assertEquals("Kept title", result.manga.single().title); assertEquals("Kept chapter", result.manga.single().chapters.single().title)
        assertEquals(6, result.manga.single().chapters.single().page); assertTrue(result.manga.single().inLibrary)
    }
    @Test fun restoreFailureAfterFileCommitRollsBackArchiveVocabularyAndState() = runBlocking {
        val repo = AppRepository(context); val dictionary = DictionaryRepository.get(context); val id = AppRepository.hash("rollback")
        val archive = zip(File(context.filesDir, "downloads/$id.cbz"), mapOf("page.png" to byteArrayOf(7, 8)))
        val page = "aidoku-archive://download/$id?entry=page.png"; repo.change { AppState(manga = listOf(Manga("incoming", "local", "Incoming", inLibrary = true, chapters = listOf(Chapter("c", "C", pages = listOf(page), downloaded = true))))) }
        dictionary.importBackup(JSONObject().put("vocabulary", JSONArray().put(VocabularyEntry(word = "Incoming").json())))
        val backup = File(context.cacheDir, "rollback.zip"); backup.outputStream().use { BackupCodec.write(repo, it, BackupFormat.ANDROID_ZIP, BackupOptions(BackupSection.entries.toSet())) }
        val broken = rewriteZip(backup) { name, bytes -> if (name == "dictionary.db") byteArrayOf(0, 1, 2) else bytes }
        val before = AppState(manga = listOf(Manga("kept", "local", "Kept", inLibrary = true))); repo.change { before }
        dictionary.importBackup(JSONObject().put("vocabulary", JSONArray().put(VocabularyEntry(word = "Kept").json())))
        zip(archive, mapOf("old.png" to byteArrayOf(2))); val oldBytes = archive.readBytes()
        try { BackupCodec.restore(repo, Uri.fromFile(broken)); fail("Malformed dictionary must abort restore") } catch (_: Exception) { }
        assertEquals(before, repo.state.value); assertEquals("Kept", dictionary.vocabulary.value.single().word); assertArrayEquals(oldBytes, archive.readBytes())
        assertFalse(context.filesDir.listFiles().orEmpty().any { it.name.startsWith(".restore-stage-") })
    }
    @Test fun checksumMismatchAndTraversalFailWithoutChangingExistingFiles() {
        val state = AppState().json().toString().toByteArray()
        val corrupt = zip(File(context.cacheDir, "bad.zip"), mapOf("state.json" to state, "files/test.txt" to "actual".toByteArray(), "manifest.json" to """{"files":[{"name":"files/test.txt","size":6,"sha256":"incorrect"}]}""".toByteArray()))
        RestoreFiles(context).use { plan -> try { corrupt.inputStream().use(plan::extract); fail("Checksum failure") } catch (_: IllegalArgumentException) { } }
        val traversal = zip(File(context.cacheDir, "escape.zip"), mapOf("state.json" to state, "../escaped.txt" to byteArrayOf(1)))
        RestoreFiles(context).use { plan -> try { traversal.inputStream().use(plan::extract); fail("Traversal failure") } catch (_: IllegalArgumentException) { } }
        assertFalse(File(context.filesDir, "escaped.txt").exists())
    }
    @Test fun nativeProgressOverflowDoesNotWrapOrMutateTheExistingLibrary() = runBlocking {
        val repo = AppRepository(context); val before = AppState(manga = listOf(Manga("kept", "local", "Kept", inLibrary = true))); repo.change { before }
        val incoming = AppState(manga = listOf(Manga("bad", "local", "Bad", chapters = listOf(Chapter("c", "C"))))).json()
        incoming.getJSONArray("manga").getJSONObject(0).getJSONArray("chapters").getJSONObject(0).put("page", 4294967296L)
        val file = zip(File(context.cacheDir, "overflow.zip"), mapOf("state.json" to incoming.toString().toByteArray()))
        try { BackupCodec.restore(repo, Uri.fromFile(file)); fail("Must reject overflow before file or database commit") } catch (_: IllegalArgumentException) { }
        assertEquals(before, repo.state.value)
    }
    @Test fun automaticBackupsHonorOriginalOptionsDueIntervalAndKeepFour() = runBlocking {
        val repo = AppRepository(context); repo.change { AppState() }; val manager = CompleteBackupManager(repo)
        repo.settings.put("AutomaticBackups.enabled", true); repo.settings.put("AutomaticBackups.interval", "6hours")
        for (section in BackupSection.entries) repo.settings.put(CompleteBackupManager.autoKey(section) ?: "Android.Backups.${section.key}", section == BackupSection.LIBRARY)
        repeat(5) { assertTrue(manager.automaticIfDue(1800000000000L + it * 22000000L)) }
        manager.refresh(); assertEquals(4, manager.backups.value.count { it.automatic })
        assertFalse(manager.automaticIfDue(1800000000000L + 4 * 22000000L + 1)); val backup = manager.backups.value.first()
        manager.rename(backup.file, "Renamed"); assertEquals("Renamed", BackupCodec.preview(backup.file).name)
        ZipFile(backup.file).use { assertFalse(it.entries().asSequence().any { entry -> entry.name == "dictionary.json" || entry.name == "dictionary.db" || entry.name == "credentials.json" }) }
    }
}
