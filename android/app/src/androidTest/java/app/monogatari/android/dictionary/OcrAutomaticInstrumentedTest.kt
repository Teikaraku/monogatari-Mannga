package app.monogatari.android.dictionary

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real offline model output, with the actual input pixels and all model diagnostics preserved. */
@RunWith(AndroidJUnit4::class)
class OcrAutomaticInstrumentedTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun render(text: String): Bitmap {
        val bitmap = Bitmap.createBitmap(1400, 420, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 105f }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, 1250).setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(true).build()
        canvas.save(); canvas.translate(65f, 100f); layout.draw(canvas); canvas.restore()
        return bitmap
    }
    private fun verify(language: String, text: String, expected: String) = runBlocking {
        val image = render(text)
        val evidence = File(context.filesDir, "ocr-script-$language-diagnostics.txt").apply { writeText("sourceLanguage=$language request=${OcrLanguages.request(true, language)} original=$text\n") }
        try {
            File(context.filesDir, "ocr-script-$language-page.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val result = OcrEngine.recognize(context, image, OcrLanguages.request(true, language)) { evidence.appendText(it) }
            evidence.appendText("\nfinal=$result\nfontGlyphs=${text.codePoints().toArray().filter { !Character.isWhitespace(it) }.associate { String(Character.toChars(it)).let { glyph -> glyph to Paint().hasGlyph(glyph) } }}\n")
            assertTrue("Expected actual '$expected' for $language; result=${result.text}; see ${evidence.name}", result.text.replace(Regex("\\s"), "").contains(expected.replace(Regex("\\s"), "")))
            val line = result.observations.first { it.region.text.replace(Regex("\\s"), "").contains(expected.replace(Regex("\\s"), "")) }
            assertTrue("Real symbol boxes missing for $language", line.characters.isNotEmpty())
            assertTrue(line.confidence.isFinite() && line.confidence in 0f..1f)
            assertTrue(line.characters.all { it.offset in line.region.text.indices && line.region.text.startsWith(it.text, it.offset) && it.bounds.left >= 0f && it.bounds.top >= 0f && it.bounds.right <= 1f && it.bounds.bottom <= 1f && it.bounds.right > it.bounds.left && it.bounds.bottom > it.bounds.top })
            val glyph = line.characters.first()
            val hit = OcrLayout.find(result.observations, (glyph.bounds.left + glyph.bounds.right) / 2, (glyph.bounds.top + glyph.bounds.bottom) / 2)
            assertNotNull("Actual $language symbol center must be clickable", hit)
            assertTrue(hit!!.fullText.contains(glyph.text))
        } finally { image.recycle() }
    }
    @Test fun restrictedCyrillicSourceRunsTheRealCyrillicModel() = verify("ru", "Привет мир", "Привет")
    @Test fun restrictedGreekSourceRunsTheRealGreekModel() = verify("el", "Καλημέρα κόσμε", "κόσμε")
    @Test fun restrictedHebrewSourceRunsTheRealHebrewModelWithLogicalOffsets() = verify("he", "שלום עולם", "שלום")
    @Test fun restrictedArabicSourceRunsTheRealArabicModelWithLogicalOffsets() = verify("ar", "مرحبا بالعالم", "مرحبا")
    @Test fun restrictedThaiSourceRunsTheRealThaiModel() = verify("th", "ภาษาไทย", "ภาษาไทย")
    @Test fun restrictedDevanagariSourceRunsTheRealBundledDevanagariModel() = verify("hi", "हिन्दी भाषा", "भाषा")

    @Test fun originalAutomaticModeActuallyRecognizesMixedJapaneseAndLatinAndKeepsTheTappedGlyph() = runBlocking {
        val bitmap = Bitmap.createBitmap(1400, 650, Bitmap.Config.ARGB_8888)
        val diagnostic = File(context.filesDir, "ocr-automatic-diagnostics.txt").apply { writeText("") }
        try {
            Canvas(bitmap).apply { drawColor(Color.WHITE); val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 100f }; drawText("AIDOKU READER", 65f, 180f, paint); drawText("日本語", 200f, 430f, paint) }
            File(context.filesDir, "ocr-automatic-page.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val result = OcrEngine.recognize(context, bitmap, preferredLanguage = "en") { diagnostic.appendText(it) }
            diagnostic.appendText("\nfinal=$result\n")
            assertTrue("Automatic Latin text missing: ${result.text}", result.text.contains("AIDOKU") && result.text.contains("READER"))
            assertTrue("Automatic Japanese text missing: ${result.text}", result.text.replace(Regex("\\s"), "").contains("日本語"))
            for (model in OcrModel.entries) assertTrue("Automatic model ${model.name} was not run", diagnostic.readText().contains("completed model=${model.name}"))
            val glyph = result.observations.flatMap { it.characters }.first { it.text == "本" }
            val hit = OcrLayout.find(result.observations, (glyph.bounds.left + glyph.bounds.right) / 2, (glyph.bounds.top + glyph.bounds.bottom) / 2)!!
            assertTrue("Automatic tap must retain Japanese symbol offset: $hit", hit.text.replace(Regex("\\s"), "").startsWith("本語"))
            assertTrue(hit.contextOffset > 0)
        } finally { bitmap.recycle() }
    }
}
