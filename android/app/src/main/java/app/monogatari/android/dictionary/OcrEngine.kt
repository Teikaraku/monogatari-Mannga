package app.monogatari.android.dictionary

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OcrRegion(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float)
data class OcrPage(val text: String, val regions: List<OcrRegion>, val width: Int, val height: Int, val observations: List<OcrObservation> = emptyList())
/** Bundled models operate offline; OCR runs against the actual decoded page image. */
object OcrEngine {
    suspend fun recognize(context: Context, uri: Uri, language: String = "auto", preferredLanguage: String? = null, diagnostics: ((String) -> Unit)? = null): OcrPage = withContext(Dispatchers.IO) {
        val bitmap = decodeImage(context, uri)
        try { recognize(context, bitmap, language, preferredLanguage, diagnostics) } finally { bitmap.recycle() }
    }
    suspend fun recognize(context: Context, bitmap: Bitmap, language: String = "auto", preferredLanguage: String? = null, diagnostics: ((String) -> Unit)? = null): OcrPage = withContext(Dispatchers.IO) {
        val selected = OcrLanguages.model(language)
        suspend fun run(model: OcrModel): OcrPage {
            currentCoroutineContext().ensureActive()
            val started = android.os.SystemClock.elapsedRealtime()
            val page = if (model == OcrModel.DEVANAGARI) {
                val mlkit = recognizeImage(bitmap, model.code, diagnostics, true)
                val native = ScriptOcr.recognize(context, bitmap, model, diagnostics)
                // The bundled MLKit Devanagari model can omit visible vowel marks. Keep a real
                // independent script-model recognition and its original boxes as an alternative.
                OcrLanguages.merge(listOf(model to mlkit, model to native), model.code)
            } else if (model.trainedData != null) ScriptOcr.recognize(context, bitmap, model, diagnostics) else recognizeImage(bitmap, model.code, diagnostics, true)
            diagnostics?.invoke("completed model=${model.name} elapsedMs=${android.os.SystemClock.elapsedRealtime() - started} observations=${page.observations.size}\n")
            return page
        }
        if (selected != null) return@withContext run(selected)
        // Language detection is actual local recognition with every available script model.
        // Keep complete glyph boxes from the winning observations, rather than mixing character offsets.
        val pages = mutableListOf<Pair<OcrModel, OcrPage>>()
        for (model in OcrModel.entries) pages += model to run(model)
        OcrLanguages.merge(pages, preferredLanguage).also { diagnostics?.invoke("automatic preferred=$preferredLanguage text=${it.text} observations=${it.observations}\n") }
    }
    suspend fun decodeImage(context: Context, uri: Uri): Bitmap = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = context.contentResolver.openInputStream(uri) ?: error("无法打开页面图像")
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "页面图像无法解码" }
        var sample = 1
        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > 4096) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: error("页面图像无法解码")
        val orientation = runCatching { context.contentResolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } ?: ExifInterface.ORIENTATION_NORMAL }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val transform = android.graphics.Matrix().apply { when (orientation) { 2 -> setScale(-1f, 1f); 3 -> setRotate(180f); 4 -> setScale(1f, -1f); 5 -> { setRotate(90f); postScale(-1f, 1f) }; 6 -> setRotate(90f); 7 -> { setRotate(-90f); postScale(-1f, 1f) }; 8 -> setRotate(270f) } }
        if (transform.isIdentity) bitmap else try { Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true) } finally { bitmap.recycle() }
    }
    /** Explicit-model bitmap API; automatic/native-script recognition also needs the asset Context. */
    suspend fun recognize(bitmap: Bitmap, language: String = "ja", diagnostics: ((String) -> Unit)? = null): OcrPage {
        val model = OcrLanguages.model(language)
        require(model != null && (model.trainedData == null || model == OcrModel.DEVANAGARI)) { "Use recognize(context, bitmap, language) for automatic or native script OCR" }
        return recognizeImage(bitmap, model.code, diagnostics, true)
    }
    private suspend fun recognizeImage(bitmap: Bitmap, language: String, diagnostics: ((String) -> Unit)?, verticalFallback: Boolean): OcrPage {
        val recognizer = when (language.substringBefore('-')) {
            "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            "zh", "zh-Hans", "zh-Hant" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            "hi" -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
            else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
        try {
            val recognized = suspendCancellableCoroutine<com.google.mlkit.vision.text.Text> { c ->
                recognizer.process(InputImage.fromBitmap(bitmap, 0)).addOnSuccessListener { if (c.isActive) c.resume(it) }.addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
            }
            diagnostics?.invoke(buildString {
                append("image=${bitmap.width}x${bitmap.height} language=$language text=${recognized.text}\n")
                recognized.textBlocks.forEachIndexed { blockIndex, block ->
                    append("block[$blockIndex] text=${block.text} box=${block.boundingBox}\n")
                    block.lines.forEachIndexed { lineIndex, line ->
                        append("  line[$lineIndex] text=${line.text} confidence=${line.confidence} box=${line.boundingBox}\n")
                        line.elements.forEachIndexed { elementIndex, element ->
                            append("    element[$elementIndex] text=${element.text} box=${element.boundingBox}\n")
                            element.symbols.forEachIndexed { symbolIndex, symbol -> append("      symbol[$symbolIndex] text=${symbol.text} box=${symbol.boundingBox}\n") }
                        }
                    }
                }
            })
            fun region(text: String, box: android.graphics.Rect) = OcrRegion(text, (box.left.toFloat() / bitmap.width).coerceIn(0f, 1f), (box.top.toFloat() / bitmap.height).coerceIn(0f, 1f), (box.right.toFloat() / bitmap.width).coerceIn(0f, 1f), (box.bottom.toFloat() / bitmap.height).coerceIn(0f, 1f))
            val observations = recognized.textBlocks.flatMap { it.lines }.mapNotNull { line -> line.boundingBox?.let { box ->
                val bounds = region(line.text, box); if (bounds.right <= bounds.left || bounds.bottom <= bounds.top) return@let null
                val vertical = box.height() > box.width() * 1.1f; val glyphs = mutableListOf<OcrGlyph>(); var search = 0
                fun add(text: String, rectangle: android.graphics.Rect, start: Int) {
                    val iterator = java.text.BreakIterator.getCharacterInstance(java.util.Locale.JAPANESE).apply { setText(text) }; val pieces = mutableListOf<Pair<Int, String>>(); var before = iterator.first(); var after = iterator.next()
                    while (after != java.text.BreakIterator.DONE) { pieces += before to text.substring(before, after); before = after; after = iterator.next() }
                    val r = region(text, rectangle)
                    pieces.forEachIndexed { i, (offset, character) -> val first = i.toFloat() / pieces.size; val last = (i + 1).toFloat() / pieces.size
                        val glyph = if (vertical) r.copy(text = character, top = r.top + (r.bottom - r.top) * first, bottom = r.top + (r.bottom - r.top) * last) else r.copy(text = character, left = r.left + (r.right - r.left) * first, right = r.left + (r.right - r.left) * last)
                        if (start + offset in line.text.indices) glyphs += OcrGlyph(start + offset, character, glyph)
                    }
                }
                for (element in line.elements) {
                    val start = line.text.indexOf(element.text, search).takeIf { it >= 0 } ?: search; var symbolSearch = start
                    val symbols = element.symbols.filter { it.boundingBox != null }
                    if (symbols.isEmpty()) element.boundingBox?.let { add(element.text, it, start) }
                    else for (symbol in symbols) { val offset = line.text.indexOf(symbol.text, symbolSearch).takeIf { it >= 0 } ?: symbolSearch; add(symbol.text, symbol.boundingBox!!, offset); symbolSearch = offset + symbol.text.length }
                    search = start + element.text.length
                }
                if (glyphs.isEmpty()) add(line.text, box, 0)
                OcrObservation(bounds, glyphs, line.confidence, if (vertical) OcrDirection.VERTICAL else OcrDirection.UNKNOWN)
            } }
            // Some MLKit versions segment upright vertical Japanese into individual square character lines.
            val oriented = observations.map { observation ->
                val r = observation.region; val width = r.right - r.left; val height = r.bottom - r.top
                val japanese = r.text.codePoints().allMatch { Character.UnicodeScript.of(it) in setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA) }
                val columnNeighbor = observations.any { other -> other !== observation && kotlin.math.abs((other.region.left + other.region.right - r.left - r.right) / 2) <= width * .52f && kotlin.math.max(0f, kotlin.math.max(other.region.top - r.bottom, r.top - other.region.bottom)) < kotlin.math.max(height, other.region.bottom - other.region.top) * 1.8f && kotlin.math.abs((other.region.top + other.region.bottom - r.top - r.bottom) / 2) > height * .5f }
                if (observation.direction == OcrDirection.UNKNOWN && japanese && r.text.codePointCount(0, r.text.length) == 1 && columnNeighbor) observation.copy(direction = OcrDirection.VERTICAL) else observation
            }
            val vertical = if (verticalFallback && language.substringBefore('-') in setOf("ja", "zh", "ko")) recognizeVertical(bitmap, language, oriented, diagnostics) else emptyList()
            val combined = oriented.filterNot { original -> vertical.any { recovered ->
                val a = original.region; val b = recovered.region
                val overlap = kotlin.math.max(0f, kotlin.math.min(a.right, b.right) - kotlin.math.max(a.left, b.left)) * kotlin.math.max(0f, kotlin.math.min(a.bottom, b.bottom) - kotlin.math.max(a.top, b.top))
                overlap > (a.right - a.left) * (a.bottom - a.top) * .7f && recovered.region.text.contains(a.text.trim())
            } } + vertical
            val text = if (vertical.isEmpty()) recognized.text else OcrLayout.ordered(combined, combined.indices.toList()).joinToString("\n") { combined[it].region.text }
            return OcrPage(text, combined.map { it.region }, bitmap.width, bitmap.height, combined)
        } finally { recognizer.close() }
    }
    private suspend fun recognizeVertical(bitmap: Bitmap, language: String, existing: List<OcrObservation>, diagnostics: ((String) -> Unit)?): List<OcrObservation> {
        val factor = kotlin.math.min(1f, 1600f / kotlin.math.max(bitmap.width, bitmap.height))
        val sample = if (factor == 1f) bitmap else Bitmap.createScaledBitmap(bitmap, (bitmap.width * factor).toInt().coerceAtLeast(1), (bitmap.height * factor).toInt().coerceAtLeast(1), true)
        val columns = try { val pixels = IntArray(sample.width * sample.height); sample.getPixels(pixels, 0, sample.width, 0, 0, sample.width, sample.height); OcrVerticalSegments.columns(sample.width, sample.height, pixels) } finally { if (sample !== bitmap) sample.recycle() }
        val result = mutableListOf<OcrObservation>(); var budget = 64
        for (column in columns) {
            if (column.glyphs.size > budget) continue
            val boxes = column.glyphs.map { box -> android.graphics.Rect((box.left / factor).toInt(), (box.top / factor).toInt(), kotlin.math.ceil(box.right / factor).toInt().coerceAtMost(bitmap.width), kotlin.math.ceil(box.bottom / factor).toInt().coerceAtMost(bitmap.height)) }
            val left = boxes.minOf { it.left }.toFloat() / bitmap.width; val right = boxes.maxOf { it.right }.toFloat() / bitmap.width
            val top = boxes.first().top.toFloat() / bitmap.height; val bottom = boxes.last().bottom.toFloat() / bitmap.height
            if (existing.any { o -> val r = o.region; kotlin.math.max(0f, kotlin.math.min(right, r.right) - kotlin.math.max(left, r.left)) * kotlin.math.max(0f, kotlin.math.min(bottom, r.bottom) - kotlin.math.max(top, r.top)) > (right - left) * (bottom - top) * .55f }) continue
            budget -= column.glyphs.size
            val strip = Bitmap.createBitmap(boxes.size * 112 + 32, 144, Bitmap.Config.ARGB_8888)
            data class Cell(val source: android.graphics.Rect, val target: android.graphics.RectF)
            val cells = mutableListOf<Cell>()
            try {
                val canvas = android.graphics.Canvas(strip); canvas.drawColor(android.graphics.Color.WHITE)
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
                if (!column.lightBackground) paint.colorFilter = android.graphics.ColorMatrixColorFilter(floatArrayOf(-1f,0f,0f,0f,255f, 0f,-1f,0f,0f,255f, 0f,0f,-1f,0f,255f, 0f,0f,0f,1f,0f))
                boxes.forEachIndexed { index, source ->
                    val scale = kotlin.math.min(96f / source.width(), 96f / source.height())
                    val target = android.graphics.RectF(16 + index * 112 + (96 - source.width() * scale) / 2, (144 - source.height() * scale) / 2,
                        16 + index * 112 + (96 + source.width() * scale) / 2, (144 + source.height() * scale) / 2)
                    canvas.drawBitmap(bitmap, source, target, paint); cells += Cell(source, target)
                }
                diagnostics?.invoke("vertical fallback source=$boxes -> upright horizontal strip=${strip.width}x${strip.height}\n")
                val recovered = recognizeImage(strip, language, diagnostics, false)
                val glyphs = mutableListOf<OcrGlyph>(); val text = StringBuilder()
                for (glyph in recovered.observations.flatMap { it.characters }.sortedBy { it.bounds.left }) {
                    if (glyph.text.isBlank()) continue
                    val x = (glyph.bounds.left + glyph.bounds.right) / 2 * strip.width
                    val cell = cells.firstOrNull { x in it.target.left..it.target.right } ?: continue
                    val sx = cell.source.width() / cell.target.width(); val sy = cell.source.height() / cell.target.height()
                    fun mapX(value: Float) = ((cell.source.left + (value * strip.width - cell.target.left) * sx) / bitmap.width).coerceIn(left, right)
                    fun mapY(value: Float) = ((cell.source.top + (value * strip.height - cell.target.top) * sy) / bitmap.height).coerceIn(top, bottom)
                    glyphs += OcrGlyph(text.length, glyph.text, OcrRegion(glyph.text, mapX(glyph.bounds.left), mapY(glyph.bounds.top), mapX(glyph.bounds.right), mapY(glyph.bounds.bottom)))
                    text.append(glyph.text)
                }
                val cjk = text.codePoints().toArray().count { Character.UnicodeScript.of(it) in setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL) }
                val count = text.codePointCount(0, text.length); val confidence = recovered.observations.maxOfOrNull { it.confidence } ?: 0f
                if (glyphs.size >= 2 && count >= boxes.size * .7f && count <= boxes.size * 2 && cjk >= count * .5f && confidence >= .35f) {
                    result += OcrObservation(OcrRegion(text.toString(), left, top, right, bottom), glyphs, confidence, OcrDirection.VERTICAL)
                    diagnostics?.invoke("mapped vertical text=$text glyphs=$glyphs\n")
                }
            } finally { strip.recycle() }
        }
        return result
    }
}
