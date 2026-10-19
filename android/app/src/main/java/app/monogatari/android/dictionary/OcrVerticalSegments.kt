package app.monogatari.android.dictionary

import kotlin.math.max
import kotlin.math.min

/** Foreground projection finds separated upright glyphs; it never rotates the glyph shapes. */
object OcrVerticalSegments {
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
    }
    data class Column(val glyphs: List<Box>, val lightBackground: Boolean)
    private fun brightness(color: Int) = (((color shr 16 and 255) * 77) + ((color shr 8 and 255) * 150) + (color and 255) * 29) shr 8
    private fun ranges(active: BooleanArray, closeGap: Int): List<IntRange> {
        val result = mutableListOf<IntRange>(); var start = -1; var last = -1
        active.forEachIndexed { index, value -> if (value) {
            if (start < 0) start = index
            else if (index - last - 1 > closeGap) { result += start..last; start = index }
            last = index
        } }
        if (start >= 0) result += start..last
        return result
    }
    fun columns(width: Int, height: Int, pixels: IntArray): List<Column> {
        require(width > 0 && height > 0 && width.toLong() * height <= 2_560_000 && pixels.size == width * height)
        val border = (0 until 64).flatMap { index ->
            val x = index * (width - 1) / 63; val y = index * (height - 1) / 63
            listOf(brightness(pixels[x]), brightness(pixels[(height - 1) * width + x]), brightness(pixels[y * width]), brightness(pixels[y * width + width - 1]))
        }.sorted()
        val background = border[border.size / 2]; val light = background >= 128
        val threshold = if (light) max(80, background - 50) else min(175, background + 50)
        val ink = BooleanArray(pixels.size) { index -> val color = pixels[index]; color ushr 24 >= 64 && if (light) brightness(color) < threshold else brightness(color) > threshold }
        val projection = IntArray(width); ink.forEachIndexed { index, value -> if (value) projection[index % width]++ }
        val spans = ranges(BooleanArray(width) { projection[it] >= max(2, height / 500) }, max(2, width / 250))
        val result = mutableListOf<Column>()
        for (span in spans) {
            val size = span.last - span.first + 1
            if (size !in 8..min(240, max(width, height) / 3)) continue
            val rows = IntArray(height)
            for (y in 0 until height) for (x in span) if (ink[y * width + x]) rows[y]++
            val glyphs = ranges(BooleanArray(height) { rows[it] >= max(1, size / 40) }, max(1, size * 13 / 100))
                .map { Box(span.first, it.first, span.last + 1, it.last + 1) }
            if (glyphs.size !in 2..32) continue
            if (glyphs.last().bottom - glyphs.first().top < size * 1.7f) continue
            if (glyphs.any { it.height < size * .38f || it.height > size * 1.65f }) continue
            if (glyphs.zipWithNext().any { (a, b) -> b.top - a.bottom > size * 1.8f }) continue
            if (glyphs.any { glyph ->
                val density = (glyph.top until glyph.bottom).sumOf { rows[it] }.toFloat() / (glyph.width * glyph.height)
                density !in .025f.. .70f
            }) continue
            result += Column(glyphs, light)
        }
        return result.sortedByDescending { it.glyphs.first().left }.take(8)
    }
}
