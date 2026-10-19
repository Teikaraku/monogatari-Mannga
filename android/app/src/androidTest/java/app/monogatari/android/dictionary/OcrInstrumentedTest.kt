package app.monogatari.android.dictionary

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OcrInstrumentedTest {
    @Test fun accountTokensAreEncryptedByAndroidKeystoreAndRemainReadable() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = app.monogatari.android.data.SecretStore(context); val secret = "test-token-${java.util.UUID.randomUUID()}"
        store.set("instrumented.token", secret)
        assertEquals(secret, store.get("instrumented.token"))
        val raw = context.getSharedPreferences("credentials", android.content.Context.MODE_PRIVATE).getString("instrumented.token", "")
        assertFalse(raw!!.contains(secret)); store.remove("instrumented.token")
    }
    @Test fun actualPageBitmapIsRecognizedWithBundledModel() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val bitmap = Bitmap.createBitmap(1000, 400, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply { drawColor(Color.WHITE); drawText("AIDOKU READER", 70f, 190f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 100f }) }
        val page = File(context.cacheDir, "ocr-test-page.png"); page.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val result = OcrEngine.recognize(context, Uri.fromFile(page), "en")
        assertTrue(result.text.contains("AIDOKU")); assertTrue(result.text.contains("READER")); assertTrue(result.regions.isNotEmpty())
        assertTrue(result.regions.all { it.left >= 0 && it.top >= 0 && it.right <= 1 && it.bottom <= 1 })
        File(context.filesDir, "ocr-verification.txt").writeText("${result.text}\nregions=${result.regions.size}\nimage=${page.path}")
    }
    @Test fun actualRecognizedCharacterBoxesStartLookupAtTheTappedSecondWord() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>(); val bitmap = Bitmap.createBitmap(1000, 400, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).apply { drawColor(Color.WHITE); drawText("AIDOKU READER", 70f, 190f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 100f }) }
            val page = OcrEngine.recognize(bitmap, "en"); val glyph = page.observations.filter { it.region.text.contains("READER") }.flatMap { it.characters }.first { it.text == "R" }
            val x = (glyph.bounds.left + glyph.bounds.right) / 2; val y = (glyph.bounds.top + glyph.bounds.bottom) / 2; val hit = OcrLayout.find(page.observations, x, y)!!
            assertTrue(hit.text.startsWith("READER")); assertTrue(hit.contextOffset > 0); assertEquals("READER", hit.fullText.substring(hit.contextOffset).trim())
            File(context.filesDir, "ocr-click-verification.txt").writeText("PASS real MLKit character boxes: x=$x y=$y lookup=${hit.text} contextOffset=${hit.contextOffset}\n")
        } finally { bitmap.recycle() }
    }
    @Test fun actualJapaneseVerticalPageUsesTopToBottomCharacterOffsets() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>(); val bitmap = Bitmap.createBitmap(600, 650, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).apply { drawColor(Color.WHITE); val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 100f }; listOf("日", "本", "語").forEachIndexed { index, text -> drawText(text, 230f, 150f + index * 140f, paint) } }
            File(context.filesDir, "ocr-vertical-page.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val diagnostic = File(context.filesDir, "ocr-vertical-diagnostics.txt").apply { writeText("") }
            val page = OcrEngine.recognize(bitmap, "ja") { diagnostic.appendText(it) }
            diagnostic.appendText("\nobservations=${page.observations.joinToString("\n")}\nfontHasGlyph=${listOf("日", "本", "語").associateWith { Paint().hasGlyph(it) }}\n")
            val glyph = page.observations.flatMap { it.characters }.firstOrNull { it.text == "本" }
            assertNotNull("Recognized '本' glyph missing. Actual text=${page.text}; diagnostics=${diagnostic.path}", glyph)
            val tapped = glyph!!
            val hit = OcrLayout.find(page.observations, (tapped.bounds.left + tapped.bounds.right) / 2, (tapped.bounds.top + tapped.bounds.bottom) / 2)!!
            assertTrue("Vertical context: ${hit.fullText}", hit.fullText.replace(Regex("\\s"), "").contains("日本語")); assertTrue("Clicked vertical text: ${hit.text}", hit.text.replace(Regex("\\s"), "").startsWith("本語"))
            File(context.filesDir, "ocr-vertical-page.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; File(context.filesDir, "ocr-vertical-verification.txt").writeText("PASS real Japanese vertical OCR: context=${hit.fullText} clicked=${hit.text} offset=${hit.contextOffset}\n")
        } finally { bitmap.recycle() }
    }
    @Test fun jpegExifOrientationMatchesOcrAndVocabularyScreenshotCoordinates() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>(); val file = File(context.cacheDir, "ocr-oriented.jpg")
        val bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val compressed = try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) } } finally { bitmap.recycle() }
        val bytes = file.readBytes()
        val evidence = org.json.JSONObject().put("sdk", android.os.Build.VERSION.SDK_INT).put("compressed", compressed).put("length", bytes.size)
            .put("header", bytes.take(12).joinToString("") { "%02x".format(it.toInt() and 255) }).put("tail", bytes.takeLast(2).joinToString("") { "%02x".format(it.toInt() and 255) })
        val diagnostic = File(context.filesDir, "ocr-exif-diagnostics.json").apply { writeText(evidence.toString(2)) }
        assertTrue("JPEG encoder failed; see ocr-exif-diagnostics.json", compressed)
        assertTrue("EXIF fixture is not a legal JPEG; see ocr-exif-diagnostics.json", bytes.size > 4 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[bytes.lastIndex - 1] == 0xff.toByte() && bytes.last() == 0xd9.toByte())
        ExifInterface(file.path).apply { setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes() }
        val orientation = ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
        diagnostic.writeText(evidence.put("orientation", orientation).toString(2)); assertEquals(ExifInterface.ORIENTATION_ROTATE_90, orientation)
        val decoded = OcrEngine.decodeImage(context, Uri.fromFile(file)); try { assertEquals(20, decoded.width); assertEquals(40, decoded.height) } finally { decoded.recycle(); file.delete() }
    }
}
