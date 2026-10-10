package app.monogatari.android.reader

import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

data class TextPage(val start: Int, val end: Int)

/** Use Android's actual shaping and line metrics, never a character-count approximation. */
object TextPaginator {
    fun paginate(text: CharSequence, width: Int, height: Int, fontPixels: Float, lineSpacing: Float, font: String): List<TextPage> {
        if (text.isEmpty()) return emptyList()
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            textSize = fontPixels
            typeface = ReaderFonts.typeface(font)
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(32))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(0f, lineSpacing)
            // This compile-time integer is inlined and does not instantiate the API29 LineBreaker on API26.
            .setBreakStrategy(LineBreaker.BREAK_STRATEGY_HIGH_QUALITY).build()
        val result = mutableListOf<TextPage>()
        var startLine = 0
        while (startLine < layout.lineCount) {
            val top = layout.getLineTop(startLine)
            var endLine = startLine + 1
            while (endLine < layout.lineCount && layout.getLineBottom(endLine) - top <= height.coerceAtLeast(32)) endLine++
            result += TextPage(layout.getLineStart(startLine), layout.getLineEnd(endLine - 1))
            startLine = endLine
        }
        return result
    }
}
