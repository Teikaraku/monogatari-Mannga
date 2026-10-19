package app.monogatari.android.dictionary

import org.junit.Assert.*
import org.junit.Test

class OcrLayoutTest {
    private fun observation(text: String, region: OcrRegion, vertical: Boolean = false): OcrObservation {
        val glyphs = mutableListOf<OcrGlyph>(); var start = 0; val points = text.codePoints().toArray()
        points.forEachIndexed { index, point -> val before = index.toFloat() / points.size; val after = (index + 1).toFloat() / points.size; val glyph = String(Character.toChars(point)); glyphs += OcrGlyph(start, glyph, if (vertical) region.copy(text = glyph, top = region.top + (region.bottom - region.top) * before, bottom = region.top + (region.bottom - region.top) * after) else region.copy(text = glyph, left = region.left + (region.right - region.left) * before, right = region.left + (region.right - region.left) * after)); start += glyph.length }
        return OcrObservation(region, glyphs, direction = if (vertical) OcrDirection.VERTICAL else OcrDirection.LEFT_TO_RIGHT)
    }
    @Test fun horizontalTapStartsAtTheClickedCharacterAndIncludesAdjacentContextLines() {
        val first = observation("甲乙", OcrRegion("甲乙", .1f, .1f, .3f, .2f)); val second = observation("丙丁", OcrRegion("丙丁", .1f, .22f, .3f, .32f))
        val hit = OcrLayout.find(listOf(second, first), .25f, .15f)!!; assertEquals("乙丙丁", hit.text); assertEquals("甲乙丙丁", hit.fullText); assertEquals(first, hit.primary)
    }
    @Test fun verticalColumnsUseOriginalRightToLeftAndTopToBottomOrdering() {
        val right = observation("甲乙", OcrRegion("甲乙", .6f, .1f, .66f, .35f), true); val left = observation("丙丁", OcrRegion("丙丁", .54f, .1f, .60f, .35f), true)
        val hit = OcrLayout.find(listOf(left, right), .63f, .30f)!!; assertEquals("乙丙丁", hit.text); assertEquals("甲乙丙丁", hit.fullText)
        assertEquals(listOf(1, 0), OcrLayout.clusters(listOf(left, right)).single())
    }
    @Test fun supplementaryCharacterOffsetsAndDisconnectedBubblesRemainCorrect() {
        val first = observation("甲😀乙", OcrRegion("甲😀乙", .1f, .1f, .4f, .2f)); val far = observation("他", OcrRegion("他", .8f, .8f, .85f, .85f))
        val hit = OcrLayout.find(listOf(first, far), .25f, .15f)!!; assertEquals("😀乙", hit.text); assertEquals(0, hit.characters.first().offset); assertEquals(2, hit.characters.last().offset); assertEquals("甲😀乙", hit.fullText); assertNull(OcrLayout.find(listOf(first, far), .6f, .6f))
    }
    @Test fun verticalForegroundProjectionFindsUprightColumnsInBothContrastDirectionsWithoutTreatingHorizontalRowsAsVertical() {
        val width = 160; val height = 140
        fun image(inverted: Boolean, horizontal: Boolean = false, solid: Boolean = false): IntArray {
            val background = if (inverted) 0xff000000.toInt() else 0xffffffff.toInt()
            val foreground = if (inverted) 0xffffffff.toInt() else 0xff000000.toInt()
            val pixels = IntArray(width * height) { background }
            val origins = if (horizontal) listOf(20 to 20, 50 to 20, 80 to 20) else listOf(20 to 20, 20 to 50, 20 to 80, 120 to 20, 120 to 50, 120 to 80)
            origins.forEach { (x, y) -> for (dy in 0 until 18) for (dx in 0 until 18) if (solid || dx < 3 || dx >= 15 || dy < 3 || dy >= 15) pixels[(y + dy) * width + x + dx] = foreground }
            return pixels
        }
        val normal = OcrVerticalSegments.columns(width, height, image(false)); val inverted = OcrVerticalSegments.columns(width, height, image(true))
        assertEquals(2, normal.size); assertEquals(listOf(120, 20), normal.map { it.glyphs.first().left }); assertEquals(listOf(20, 50, 80), normal.first().glyphs.map { it.top })
        assertEquals(normal.map { it.glyphs }, inverted.map { it.glyphs }); assertTrue(normal.all { it.lightBackground }); assertTrue(inverted.none { it.lightBackground })
        assertTrue(OcrVerticalSegments.columns(width, height, image(false, horizontal = true)).isEmpty())
        assertTrue(OcrVerticalSegments.columns(width, height, image(false, solid = true)).isEmpty())
    }
}
