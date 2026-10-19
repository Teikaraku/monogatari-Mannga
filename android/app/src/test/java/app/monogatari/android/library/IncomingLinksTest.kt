package app.monogatari.android.library

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.monogatari.android.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IncomingLinksTest {
    @Test fun chapterDownloadIdentityCannotCollideWhenKeysContainSeparators() {
        assertNotEquals(AppRepository.chapterDownloadId("s::m", "c::d"), AppRepository.chapterDownloadId("s::m::c", "d"))
    }
    @Test fun encodedKeysKeepSlashesUnicodePercentAndPlusWithoutDoubleDecoding() {
        val link = IncomingLinks.appLink(Uri.parse("aidoku://en.example/" + Uri.encode("书/50%2F+a") + "/" + Uri.encode("chapter/1+2") + "?action=read"))!!
        assertEquals("书/50%2F+a", link.mangaKey); assertEquals("chapter/1+2", link.chapterKey); assertEquals("read", link.action)
        assertNull(IncomingLinks.appLink(Uri.parse("aidoku://addSourceList?url=https%3A%2F%2Fexample.com")))
    }
    @Test fun unnamedSourceAndBackupZipsAreNotImportedAsComics() {
        val context = ApplicationProvider.getApplicationContext<Context>(); val repo = AppRepository(context)
        fun archive(entry: String) = File(context.cacheDir, "${UUID.randomUUID()}.zip").also { file -> ZipOutputStream(file.outputStream()).use { it.putNextEntry(ZipEntry(entry)); it.write(byteArrayOf(1, 2)); it.closeEntry() } }
        assertEquals("source", IncomingLinks.fileKind(repo, Uri.fromFile(archive("main.wasm"))))
        assertEquals("backup", IncomingLinks.fileKind(repo, Uri.fromFile(archive("state.json"))))
        assertEquals("comic", IncomingLinks.fileKind(repo, Uri.fromFile(archive("001.png"))))
    }
    @Test fun originalSettingsAliasesUseOriginalModernKeys() {
        val context = ApplicationProvider.getApplicationContext<Context>(); val store = SettingsStore(AppRepository(context))
        val result = store.normalizeLegacy(JSONObject().put("Browse.showNsfwSources", true).put("Library.pinManga", true).put("Library.pinMangaType", 1).put("enabledModelFile", "waifu2x_photo_noise0_scale2x.mlmodelc").put("General.portraitRows", 3))
        assertEquals(3, result.getJSONArray("Browse.contentRatings").length()); assertEquals("updatedChapters", result.getString("Library.pinTitles"))
        assertEquals("custom", result.getString("Appearance.layout")); assertEquals(3, result.getInt("Appearance.customPortraitRows"))
        assertTrue(result.has("Data.enabledModelFile")); assertFalse(result.has("enabledModelFile"))
    }
}
