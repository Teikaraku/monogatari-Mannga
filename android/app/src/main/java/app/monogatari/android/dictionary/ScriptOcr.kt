package app.monogatari.android.dictionary

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.AtomicFile
import com.googlecode.tesseract.android.TessBaseAPI
import com.googlecode.tesseract.android.TessBaseAPI.PageIteratorLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Real offline LSTM engines for scripts not exposed by MLKit's Android recognizer. */
internal object ScriptOcr {
    private val mutex = Mutex()
    private val verified = mutableSetOf<String>()

    private fun dataDirectory(context: Context, model: OcrModel): File {
        val name = requireNotNull(model.trainedData)
        val root = File(context.filesDir.canonicalFile, "ocr-models").apply { mkdirs() }
        require(root.canonicalFile == root.absoluteFile) { "Invalid OCR model directory" }
        val directory = File(root, "tessdata").apply { mkdirs() }
        require(directory.canonicalFile == directory.absoluteFile) { "Invalid OCR model directory" }
        val target = File(directory, "$name.traineddata")
        require(target.canonicalFile == target.absoluteFile) { "Invalid OCR model file" }
        val identity = target.absolutePath
        if (identity in verified && target.isFile) return root
        val manifest = context.assets.open("dictionary/tessdata/provenance.json").bufferedReader().use { JSONObject(it.readText().removePrefix("\uFEFF")) }
        val models = manifest.getJSONArray("models")
        val item = (0 until models.length()).map(models::getJSONObject).single { it.getString("file") == target.name }
        val expectedBytes = item.getLong("bytes")
        val expectedHash = item.getString("sha256")
        fun hash(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
        if (!target.isFile || target.length() != expectedBytes || hash(target) != expectedHash) {
            val atomic = AtomicFile(target); val output = atomic.startWrite()
            try {
                val digest = MessageDigest.getInstance("SHA-256"); var bytes = 0L
                context.assets.open("dictionary/tessdata/${target.name}").use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) { val count = input.read(buffer); if (count < 0) break; bytes += count; require(bytes <= expectedBytes); digest.update(buffer, 0, count); output.write(buffer, 0, count) }
                }
                require(bytes == expectedBytes && digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) } == expectedHash) { "OCR model checksum mismatch" }
                atomic.finishWrite(output)
            } catch (failure: Throwable) { atomic.failWrite(output); throw failure }
        }
        verified += identity
        return root
    }

    suspend fun recognize(context: Context, bitmap: Bitmap, model: OcrModel, diagnostics: ((String) -> Unit)?): OcrPage = withContext(Dispatchers.IO) {
        // Keep large native LSTM workspaces sequential, including concurrent reader popups.
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            val data = dataDirectory(context, model)
            val scale = minOf(1f, 2000f / maxOf(bitmap.width, bitmap.height))
            val image = if (scale == 1f) bitmap else Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
            val engine = TessBaseAPI()
            try {
                require(engine.init(data.absolutePath, model.trainedData!!, TessBaseAPI.OEM_LSTM_ONLY)) { "Cannot initialize ${model.trainedData} OCR model" }
                engine.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
                engine.setImage(image)
                val text = engine.getUTF8Text().orEmpty()
                currentCoroutineContext().ensureActive()
                val observations = mutableListOf<OcrObservation>()
                val iterator = engine.getResultIterator()
                if (iterator != null) try {
                    fun region(value: String, box: Rect) = OcrRegion(value,
                        (box.left.toFloat() / image.width).coerceIn(0f, 1f), (box.top.toFloat() / image.height).coerceIn(0f, 1f),
                        (box.right.toFloat() / image.width).coerceIn(0f, 1f), (box.bottom.toFloat() / image.height).coerceIn(0f, 1f))
                    var line = ""; var bounds: OcrRegion? = null; var confidence = 0f; var search = 0
                    val glyphs = mutableListOf<OcrGlyph>()
                    fun flush() { bounds?.let { r -> if (line.isNotBlank() && r.right > r.left && r.bottom > r.top) observations += OcrObservation(r, glyphs.toList(), confidence, if (java.text.Bidi(line, java.text.Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).baseIsLeftToRight()) OcrDirection.LEFT_TO_RIGHT else OcrDirection.RIGHT_TO_LEFT) }; glyphs.clear() }
                    iterator.begin()
                    do {
                        currentCoroutineContext().ensureActive()
                        if (bounds == null || iterator.isAtBeginningOf(PageIteratorLevel.RIL_TEXTLINE)) {
                            flush(); line = iterator.getUTF8Text(PageIteratorLevel.RIL_TEXTLINE).orEmpty().trimEnd('\r', '\n')
                            bounds = region(line, iterator.getBoundingRect(PageIteratorLevel.RIL_TEXTLINE))
                            confidence = (iterator.confidence(PageIteratorLevel.RIL_TEXTLINE) / 100f).takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f; search = 0
                        }
                        val symbol = iterator.getUTF8Text(PageIteratorLevel.RIL_SYMBOL).orEmpty()
                        val offset = line.indexOf(symbol, search)
                        if (symbol.isNotEmpty() && offset >= 0) {
                            val r = region(symbol, iterator.getBoundingRect(PageIteratorLevel.RIL_SYMBOL))
                            if (r.right > r.left && r.bottom > r.top) glyphs += OcrGlyph(offset, symbol, r)
                            search = offset + symbol.length
                        }
                    } while (iterator.next(PageIteratorLevel.RIL_SYMBOL))
                    flush()
                } finally { iterator.delete() }
                diagnostics?.invoke("native model=${model.trainedData} image=${image.width}x${image.height} text=$text observations=$observations\n")
                OcrPage(observations.joinToString("\n") { it.region.text }, observations.map { it.region }, bitmap.width, bitmap.height, observations)
            } finally { engine.recycle(); if (image !== bitmap) image.recycle() }
        }
    }
}
