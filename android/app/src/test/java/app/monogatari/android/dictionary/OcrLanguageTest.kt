package app.monogatari.android.dictionary

import org.junit.Assert.*
import org.junit.Test

class OcrLanguageTest {
    @Test fun originalAutoAndRestrictedSourceLanguageDoNotChooseTheFirstWhitelistItem() {
        assertEquals("auto", OcrLanguages.request(false, "ja"))
        assertEquals("fr", OcrLanguages.request(true, "fr"))
        assertEquals("en", OcrLanguages.request(true, null))
        assertEquals("ko", OcrLanguages.request(false, "ja", "ko"))
        assertEquals("ja", OcrLanguages.request(true, "ja", "auto"))
        assertEquals(OcrModel.LATIN, OcrLanguages.model("fr-CA"))
        assertEquals(OcrModel.CHINESE, OcrLanguages.model("zh_Hant"))
        assertEquals(OcrModel.JAPANESE, OcrLanguages.model("jpn"))
        assertEquals(OcrModel.DEVANAGARI, OcrLanguages.model("hi-IN"))
        assertEquals(OcrModel.CYRILLIC, OcrLanguages.model("uk"))
        assertEquals(OcrModel.CYRILLIC, OcrLanguages.model("sr"))
        assertEquals(OcrModel.LATIN, OcrLanguages.model("sr-Latn"))
        assertEquals(OcrModel.ARABIC, OcrLanguages.model("fa"))
        assertEquals(OcrModel.HEBREW, OcrLanguages.model("iw"))
        assertEquals(OcrModel.THAI, OcrLanguages.model("th"))
        assertEquals(OcrModel.GREEK, OcrLanguages.model("el"))
        val failure = runCatching { OcrLanguages.model("bn-Beng") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message!!.contains("bn-Beng"))
        assertTrue(failure.message!!.contains("Supported scripts"))
        assertTrue(runCatching { OcrLanguages.model(OcrLanguages.request(true, "multi")) }.exceptionOrNull() is IllegalArgumentException)
    }

    private fun observation(text: String, top: Float, confidence: Float, direction: OcrDirection = OcrDirection.UNKNOWN): OcrObservation {
        val box = OcrRegion(text, .1f, top, .8f, top + .1f)
        var offset = 0
        val glyphs = text.codePoints().toArray().mapIndexed { index, code ->
            val character = String(Character.toChars(code)); val glyph = OcrGlyph(offset, character, box.copy(text = character, left = .1f + index * .1f, right = .2f + index * .1f)); offset += character.length; glyph
        }
        return OcrObservation(box, glyphs, confidence, direction)
    }
    private fun page(vararg observations: OcrObservation) = OcrPage("", observations.map { it.region }, 1000, 800, observations.toList())

    @Test fun automaticMergeKeepsActualWinningGlyphOffsetsAndSeparateMixedScriptLines() {
        val japanese = observation("日本語かな", .1f, .95f, OcrDirection.VERTICAL)
        val noise = observation("ILII", .1f, .3f)
        val latin = observation("READER", .5f, .99f)
        val korean = observation("한국어", .75f, .91f)
        val result = OcrLanguages.merge(listOf(OcrModel.LATIN to page(noise, latin), OcrModel.JAPANESE to page(japanese), OcrModel.KOREAN to page(korean)), "ja")
        assertEquals(3, result.observations.size)
        assertTrue(result.text.contains("日本語かな")); assertTrue(result.text.contains("READER")); assertTrue(result.text.contains("한국어")); assertFalse(result.text.contains("ILII"))
        assertSame(japanese, result.observations.first { it.region.text == japanese.region.text })
        assertEquals(japanese.characters, result.observations.first { it.region.text == japanese.region.text }.characters)
        assertTrue(result.observations.all { it.confidence in 0f..1f })
    }

    @Test fun sourceLanguageBreaksEqualHanScoresWithoutDroppingAnUnrelatedObservation() {
        val ja = observation("漫画", .1f, .8f)
        val zh = observation("漫畫", .1f, .8f)
        val unrelated = observation("AIDOKU", .6f, .9f)
        val pages = listOf(OcrModel.JAPANESE to page(ja), OcrModel.CHINESE to page(zh), OcrModel.LATIN to page(unrelated))
        val chinese = OcrLanguages.merge(pages, "zh-TW")
        assertEquals(setOf("漫畫", "AIDOKU"), chinese.observations.map { it.region.text }.toSet())
        val japanese = OcrLanguages.merge(pages, "ja")
        assertEquals(setOf("漫画", "AIDOKU"), japanese.observations.map { it.region.text }.toSet())
    }
    @Test fun oneHighConfidenceGreekLetterAmongDigitsCannotSuppressCompleteActualJapaneseGlyphs() {
        val japanese = observation("日本語", .1f, .8072917f)
        val greekNoise = observation("Η 2-58", .1f, .81756896f)
        val latin = observation("AIDOKU READER", .6f, .79003906f)
        val result = OcrLanguages.merge(listOf(OcrModel.GREEK to page(greekNoise), OcrModel.JAPANESE to page(japanese), OcrModel.LATIN to page(latin)), "en")
        assertEquals(setOf("日本語", "AIDOKU READER"), result.observations.map { it.region.text }.toSet())
        val actual = result.observations.first { it.region.text == "日本語" }
        assertSame(japanese, actual); assertEquals(japanese.characters, actual.characters)
    }
}
