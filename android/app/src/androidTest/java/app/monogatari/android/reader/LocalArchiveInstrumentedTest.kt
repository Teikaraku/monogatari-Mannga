package app.monogatari.android.reader

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.monogatari.android.data.ArchiveImporter
import app.monogatari.android.local.ArchiveXml
import app.monogatari.android.local.LocalFileNameParser
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalArchiveInstrumentedTest {
    @Test fun nativeRegexEnginePassesAll335OriginalFilenameCases() {
        val assets=InstrumentationRegistry.getInstrumentation().context.assets
        val data=JSONObject(assets.open("local-filename-cases.json").bufferedReader().use{it.readText()})
        var count=0
        for(kind in listOf("series","volume","chapter")) {
            val cases=data.getJSONArray(kind)
            for(i in 0 until cases.length()) {
                val entry=cases.getJSONArray(i);val value=entry.getString(0)
                val actual=when(kind){"series"->LocalFileNameParser.parseMangaSeries(value);"volume"->LocalFileNameParser.parseMangaVolume(value);else->LocalFileNameParser.parseMangaChapter(value)}
                assertEquals("Native $kind: $value",entry.getString(1),actual);count++
            }
        }
        assertEquals(335,count)
    }

    @Test fun actualCbzAndEpubPreserveNativeXmlMetadataResourcesAndRejectEntities() {
        val original=ApplicationProvider.getApplicationContext<Context>();val context=ReaderFixtureContext(original)
        try {
            val assets=InstrumentationRegistry.getInstrumentation().context.assets
            fun import(name:String)=File(context.cacheDir,name).also { target ->assets.open("local-archives/$name").use { input ->target.outputStream().use{input.copyTo(it)} } }
                .let { ArchiveImporter.import(context,Uri.fromFile(it)) }
            val cbz=import("Aidoku-QA-CBZ-20261009.cbz")
            assertEquals("Aidoku QA CBZ 20261009",cbz.title);assertEquals("Aidoku QA Author",cbz.author)
            assertEquals("Aidoku QA Artist",cbz.artist);assertEquals("en",cbz.language);assertEquals("rtl",cbz.viewer)
            val chapter=cbz.chapters.single();assertEquals("Original Chapter 3.5",chapter.title)
            assertEquals(3.5,chapter.number,0.0);assertEquals(2.0,chapter.volume);assertEquals(4,chapter.pages.size)
            assertEquals(4,JSONObject(chapter.metadata).getJSONObject("pageDescriptions").length())
            val epub=import("Aidoku-QA-EPUB-20261009.epub")
            assertEquals("Aidoku QA EPUB 20261009",epub.title);assertEquals("Aidoku QA Author",epub.author)
            assertEquals("en",epub.language);assertEquals(2,epub.chapters.size)
            val html=File(Uri.parse(epub.chapters.first().html).path!!).readText()
            assertTrue(html.contains("EPUB ORIGINAL STYLE VISIBLE"));assertTrue(html.contains("<table"));assertTrue(html.contains("file://"))
            assertTrue(html.contains("epub-1.html#arrival"));assertTrue(File(Uri.parse(epub.cover).path!!).isFile)
            val declarations=listOf("<!DOCTYPE x [<!ENTITY value SYSTEM 'file:///etc/passwd'>]><x>&value;</x>",
                "<!DOCTYPE x [<!ENTITY a 'first'><!ENTITY b '&a;&a;'>]><x>&b;</x>")
            for(declaration in declarations)for(encoding in listOf(Charsets.UTF_8,Charsets.UTF_16)) {
                try { ArchiveXml.parse(declaration.toByteArray(encoding));fail("Native XML accepted DTD/entity in $encoding") }catch(_:IllegalArgumentException) { }
            }
        } finally { context.clear() }
    }
}
