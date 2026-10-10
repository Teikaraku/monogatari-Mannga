package app.monogatari.android.dictionary

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DictionaryGlossaryTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    @Test fun structuredRubyTablesNestedStylesAndImagesRenderWithoutExecutableMarkup() {
        val ruby = JSONObject().put("tag", "ruby").put("content", JSONArray().put("漢").put(JSONObject().put("tag", "rt").put("content", "かん"))).put("style", JSONObject().put("fontWeight", "bold")).put("data", JSONObject().put("meaning", "primary"))
        val content = JSONArray().put("<script>steal()</script>").put(JSONObject().put("type", "structured-content").put("content", ruby))
            .put(JSONObject().put("type", "image").put("path", "media/example.png").put("description", "Image")).put(JSONObject().put("type", "image").put("path", "../../credentials").put("description", "Rejected image"))
        val document = DictionaryGlossary.document(content, "fixture", "ruby{color:red}</style><script>remote()</script>")
        assertTrue(document.contains("<ruby")); assertTrue(document.contains("<rt class=\"gloss-sc-rt\">かん</rt>")); assertTrue(document.contains("font-weight:bold")); assertTrue(document.contains("data-sc-meaning=\"primary\"")); assertTrue(document.contains("https://dictionary.monogatari.invalid/fixture/media/example.png")); assertFalse(document.contains("<script>")); assertFalse(document.contains("src=\"../../credentials")); assertTrue(document.contains("script-src 'none'"))
    }
    @Test fun mediaResponseServesOnlyTheCurrentDictionaryAndBlocksTraversalSecretsAndRemoteHosts() {
        val root = File(context.filesDir, "dictionaries/fixture/media").apply { mkdirs() }; File(root, "image.png").writeBytes(byteArrayOf(1, 2, 3)); File(context.filesDir, "tracking.json").writeText("private-token")
        val valid = DictionaryGlossary.response(context, "fixture", Uri.parse("https://dictionary.monogatari.invalid/fixture/media/image.png")); assertEquals(200, valid.statusCode); valid.data.close()
        for (uri in listOf("https://evil.invalid/fixture/media/image.png", "https://dictionary.monogatari.invalid/other/image.png", "https://dictionary.monogatari.invalid/fixture/%2e%2e/%2e%2e/tracking.json", "file://${context.filesDir}/tracking.json", "https://dictionary.monogatari.invalid/fixture/media/image.png?redirect=secret")) assertEquals(403, DictionaryGlossary.response(context, "fixture", Uri.parse(uri)).statusCode)
    }
    @Test fun generatedMainDocumentIsBoundToItsExactOriginAndNeverServedToFramesOrArbitraryDataUrls() {
        val session = DictionaryGlossary.DocumentSession("fixture"); val first = session.bind("<html>First</html>")
        val response = session.response(Uri.parse(first), true, "GET")!!
        assertEquals(200, response.statusCode); assertEquals("<html>First</html>", response.data.bufferedReader().use { it.readText() }); assertTrue(response.responseHeaders?.get("Content-Security-Policy").orEmpty().contains("script-src 'none'"))
        assertNull(session.response(Uri.parse(first), false, "GET")); assertNull(session.response(Uri.parse(first), true, "POST")); assertNull(session.response(Uri.parse(first.replace("fixture", "other")), true, "GET"))
        assertNull(session.response(Uri.parse("data:text/html;base64,PHNjcmlwdD4="), true, "GET"))
        val second = session.bind("<html>Updated</html>"); assertNull(session.response(Uri.parse(first), true, "GET")); assertNotEquals(first, second)
        assertEquals("https://dictionary.monogatari.invalid/fixture/image.png", java.net.URI(second).resolve("image.png").toString())
    }
    @Test fun importLookupAndDatabaseBackupPreserveTheRichGlossaryAndItsDictionaryMedia() = runBlocking {
        val glossary = """[{"type":"structured-content","content":{"tag":"div","style":{"fontWeight":"bold"},"content":["Meaning",{"type":"image","path":"image.png","description":"Figure"}]}}]"""
        val output = ByteArrayOutputStream(); ZipOutputStream(output).use { zip -> mapOf("index.json" to """{"title":"Rich fixture","revision":"1"}""", "term_bank_1.json" to """[["漢字","かんじ","","",0,$glossary,1,""]]""", "styles.css" to "div{color:red}", "image.png" to "fixture bytes").forEach { (name, data) -> zip.putNextEntry(ZipEntry(name)); zip.write(data.toByteArray()); zip.closeEntry() } }
        val repo = DictionaryRepository.get(context); val imported = repo.importDictionary(output.toByteArray().inputStream()); val entry = repo.lookup("漢字").first { it.dictionaryId == imported.id }
        assertTrue(DictionaryGlossary.rich(entry.glossary)); assertEquals("bold", JSONArray(entry.glossary).getJSONObject(0).getJSONObject("content").getJSONObject("style").getString("fontWeight")); assertEquals("div{color:red}", DictionaryGlossary.css(context, imported.id))
        val response = DictionaryGlossary.response(context, imported.id, Uri.parse(DictionaryGlossary.origin(imported.id) + "image.png")); assertEquals("fixture bytes", response.data.bufferedReader().use { it.readText() })
        val backup = File(context.cacheDir, "rich-dictionary.db"); repo.exportDatabase(backup); repo.importDictionariesDatabase(backup); assertEquals(entry.glossary, repo.lookup("漢字").first { it.dictionaryId == imported.id }.glossary)
    }
    @Test fun originalInternalLookupAndExternalLinksRequireAnExplicitMainFrameGesture() {
        var lookedUp = ""; var opened = ""
        val internal = Uri.parse(DictionaryGlossary.link(DictionaryGlossary.origin("fixture"), "?query=%E6%BC%A2%E5%AD%97"))
        assertTrue(DictionaryGlossary.dispatchLink("fixture", internal, true, true, { lookedUp = it }, { opened = it.toString() })); assertEquals("漢字", lookedUp)
        assertTrue(DictionaryGlossary.dispatchLink("fixture", Uri.parse("https://example.com/definition"), true, true, { lookedUp = it }, { opened = it.toString() })); assertEquals("https://example.com/definition", opened)
        assertFalse(DictionaryGlossary.dispatchLink("fixture", Uri.parse("https://example.com/auto"), false, true, {}, { fail("Automatic external navigation") })); assertFalse(DictionaryGlossary.dispatchLink("fixture", Uri.parse("https://example.com/iframe"), true, false, {}, { fail("Frame external navigation") }))
        assertNull(DictionaryGlossary.link(DictionaryGlossary.origin("fixture"), "javascript:steal()")); assertNull(DictionaryGlossary.link(DictionaryGlossary.origin("fixture"), "file:///credentials"))
        val image = DictionaryGlossary.document(JSONObject().put("tag", "img").put("path", "figure.png").put("collapsible", true).put("collapsed", true).put("imageRendering", "pixelated"), "fixture")
        assertTrue(image.contains("<details>")); assertTrue(image.contains("figure.png")); assertTrue(image.contains("image-rendering:pixelated"))
    }
}
