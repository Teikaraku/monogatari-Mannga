package app.monogatari.android.backup

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.monogatari.android.data.*
import app.monogatari.android.dictionary.DictionaryRepository
import app.monogatari.android.dictionary.VocabularyEntry
import app.monogatari.android.tracking.TrackingRepository
import app.monogatari.android.sources.SourceRuntimeManager
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class BackupInstrumentedTest {
    /** Separate files, databases and preference namespaces keep the preview library untouched. */
    private class FixtureContext(val original: Context) : ContextWrapper(original) {
        val root = File(original.cacheDir, "backup-device-${UUID.randomUUID()}").apply { mkdirs() }
        private val namespace = root.name; private val names = mutableSetOf<String>()
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        override fun getDatabasePath(name: String): File = File(root, "databases/$name").apply { parentFile?.mkdirs() }
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences { val scoped = "$namespace-$name"; names += scoped; return original.getSharedPreferences(scoped, mode) }
        fun clear() { names.forEach(original::deleteSharedPreferences); root.deleteRecursively() }
    }
    @Test fun deviceSqliteBackupRestoresRealPngEpubCbzAndDictionaryDatabase() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>(); val context = FixtureContext(base)
        try {
            val repo = AppRepository(context); val dictionary = DictionaryRepository.get(context)
            val local = File(context.filesDir, "local/fixture").apply { mkdirs() }; val png = File(local, "image.png")
            val bitmap = Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }; png.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
            val css = File(local, "style.css").apply { writeText("body{background:url('${Uri.fromFile(png)}')}") }
            val html = File(local, "page.xhtml").apply { writeText("<html><link href='${Uri.fromFile(css)}'/><img src='${Uri.fromFile(png)}'/></html>") }
            val hash = AppRepository.hash("fixture"); val cbz = File(context.filesDir, "downloads/$hash.cbz").apply { parentFile.mkdirs() }
            ZipOutputStream(cbz.outputStream()).use { zip -> zip.putNextEntry(ZipEntry("page.png")); png.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
            val words = File(context.filesDir, "vocabulary-media/context.png").apply { parentFile.mkdirs(); png.copyTo(this) }
            dictionary.importBackup(JSONObject().put("vocabulary", JSONArray().put(VocabularyEntry(word = "fixture", imagePath = words.path).json())))
            repo.change { AppState(manga = listOf(Manga("book", "local", "Fixture", inLibrary = true, chapters = listOf(Chapter("html", "EPUB", html = Uri.fromFile(html).toString()), Chapter("comic", "Comic", pages = listOf("aidoku-archive://download/$hash?entry=page.png"), downloaded = true))))) }
            val backup = File(context.cacheDir, "full.zip"); backup.outputStream().use { BackupCodec.write(repo, it, BackupFormat.ANDROID_ZIP, BackupOptions(BackupSection.entries.toSet()), "Device fixture") }
            // This runs the real Android SQLite driver, including both PRAGMA cursors and VACUUM.
            val snapshot = File(context.cacheDir, "dictionary.db"); dictionary.exportDatabase(snapshot)
            SQLiteDatabase.openDatabase(snapshot.path, null, SQLiteDatabase.OPEN_READONLY).use { db -> db.rawQuery("SELECT count(*) FROM vocabulary", null).use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) } }
            local.deleteRecursively(); cbz.delete(); words.delete(); repo.change { AppState() }; dictionary.importBackup(JSONObject())
            BackupCodec.restore(repo, Uri.fromFile(backup))
            val restored = repo.state.value.manga.single(); val page = File(Uri.parse(restored.chapters.first().html).path!!)
            val links = if (page.exists()) Regex("file://[^']+").findAll(page.readText()).map { it.value }.toList() else emptyList()
            val diagnostic = JSONObject().put("filesDir", context.filesDir.path).put("canonicalFilesDir", context.filesDir.canonicalPath).put("restoredHtml", page.path).put("htmlExists", page.exists())
                .put("html", if (page.exists()) page.readText() else "").put("links", JSONArray(links.map { value -> val file = File(Uri.parse(value).path!!); JSONObject().put("uri", value).put("path", file.path).put("exists", file.exists()).put("canonicalPath", file.canonicalPath) }))
            ZipFile(backup).use { zip -> diagnostic.put("manifest", JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().use { it.readText() })) }
            File(base.filesDir, "backup-device-diagnostics.json").writeText(diagnostic.toString(2))
            assertTrue("Restored EPUB is missing: ${page.path}", page.exists()); assertEquals("EPUB must retain CSS and image references", 2, links.size)
            assertTrue("Restored EPUB resources are missing; see backup-device-diagnostics.json", links.all { File(Uri.parse(it).path!!).exists() })
            assertEquals(Color.CYAN, BitmapFactory.decodeFile(dictionary.vocabulary.value.single().imagePath).getPixel(0, 0))
            ZipFile(File(context.filesDir, "downloads/$hash.cbz")).use { zip -> assertNotNull(zip.getEntry("page.png")) }
            File(base.filesDir, "backup-device-verification.txt").writeText("PASS: real Android SQLite snapshot + PNG + EPUB HTML/CSS/resources + fixed-hash CBZ + vocabulary media\n")
        } finally { DictionaryRepository.get(base); TrackingRepository.get(base); SourceRuntimeManager.get(base); context.clear() }
    }
    @Test fun sensitiveAibRestoresOAuthAndServerCredentialsOnlyIntoEncryptedStorage() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>(); val context = FixtureContext(base)
        try {
            val repo = AppRepository(context); val original = JSONObject().put("name", "Sensitive fixture").put("date", 1720000000).put("version", "0.7.0")
                .put("sources", JSONArray().put(JSONObject().put("id", "server").put("config", android.util.Base64.encodeToString(CustomSourceCodec.encode(SourceConfig("server", "Komga", "komga", "https://fixture.invalid")), android.util.Base64.NO_WRAP))))
                .put("settings", JSONObject().put("Tracker.anilist.token", "fixture-oauth-secret").put("server.username", "fixture-user").put("server.password", "fixture-server-secret").put("Future.authBlob", JSONObject().put("unknown", "fixture-opaque-secret")).put("Reader.readingMode", "ltr"))
            val file = File(context.cacheDir, "private.aib"); file.outputStream().use { IOSBackupCodec.writeBinary(original, it) }
            BackupCodec.restore(repo, Uri.fromFile(file), RestoreOptions(selected = setOf(BackupSection.SOURCES, BackupSection.SETTINGS, BackupSection.TRACKING), restoreSensitive = true))
            assertEquals(okhttp3.Credentials.basic("fixture-user", "fixture-server-secret"), repo.secrets.get("server")); assertTrue(TrackingRepository.get(context).data.value.accounts.first { it.provider.id == "anilist" }.connected)
            val state = repo.state.value.json().toString(); assertFalse(state.contains("fixture-oauth-secret")); assertFalse(state.contains("fixture-server-secret")); assertFalse(state.contains("fixture-opaque-secret"))
            assertFalse(context.getSharedPreferences("credentials", 0).all.values.joinToString().contains("fixture-oauth-secret"))
            val safe = java.io.ByteArrayOutputStream(); BackupCodec.write(repo, safe, BackupFormat.IOS_JSON, BackupOptions(selected = setOf(BackupSection.SETTINGS)))
            assertFalse(safe.toString().contains("fixture-oauth-secret")); assertFalse(safe.toString().contains("fixture-server-secret"))
            val privateExport = java.io.ByteArrayOutputStream(); BackupCodec.write(repo, privateExport, BackupFormat.IOS_JSON, BackupOptions(selected = setOf(BackupSection.SETTINGS), sensitiveSettings = true))
            val exported = JSONObject(privateExport.toString()).getJSONObject("settings"); assertEquals("fixture-oauth-secret", exported.getString("Tracker.anilist.token")); assertEquals("fixture-opaque-secret", exported.getJSONObject("Future.authBlob").getString("unknown"))
        } finally { DictionaryRepository.get(base); TrackingRepository.get(base); SourceRuntimeManager.get(base); context.clear() }
    }
}
