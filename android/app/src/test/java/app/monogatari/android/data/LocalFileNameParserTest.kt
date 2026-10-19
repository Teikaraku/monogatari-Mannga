package app.monogatari.android.data

import app.monogatari.android.local.LocalFileNameParser
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class LocalFileNameParserTest {
    @Test fun allOriginalSwiftSeriesVolumeChapterAndDuplicateExamples() {
        val examples=JSONObject(javaClass.classLoader!!.getResourceAsStream("local-filename-cases.json")!!.bufferedReader().use{it.readText()})
        var count=0
        for(kind in listOf("series","volume","chapter")) { val cases=examples.getJSONArray(kind)
            for(index in 0 until cases.length()) { val entry=cases.getJSONArray(index);val input=entry.getString(0)
                val actual=when(kind){"series"->LocalFileNameParser.parseMangaSeries(input);"volume"->LocalFileNameParser.parseMangaVolume(input);else->LocalFileNameParser.parseMangaChapter(input)}
                assertEquals("$kind: $input",entry.getString(1),actual);count++
            }
        };assertEquals(335,count)
    }
    @Test fun unicodeDuplicateOffsetsAndOriginalFirstRangeNumberArePreserved() {
        assertEquals("7",LocalFileNameParser.parseMangaChapter("😀 漫画 - Ch 007 Content - Ch 009 Omake"))
        assertEquals("3",LocalFileNameParser.parseMangaVolume("ไทย😀 Vol 003 Content - Vol 004 Omake"))
        assertEquals("3",LocalFileNameParser.parseMangaVolume("👨‍👩‍👧‍👦 Vol3 - Vol4"))
        assertEquals("1",LocalFileNameParser.parseMangaChapter("👨‍👩‍👧‍👦 Ch1 - Ch2"))
        assertEquals(90.0,LocalFileNameParser.chapterNumber("Historys Strongest Disciple Kenichi c090-c098"))
        assertEquals(153.5,LocalFileNameParser.chapterNumber("Beelzebub_153b_RHS.zip"))
    }
}
