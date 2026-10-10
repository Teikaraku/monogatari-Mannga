package app.monogatari.android.dictionary

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.monogatari.android.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class GlossaryInstrumentedTest {
    @Test fun actualWebViewRendersDictionaryPngNestedStylesAndCssWithScriptsDisabled() {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        val id = "fixture-${UUID.randomUUID()}"; val root = File(context.filesDir, "dictionaries/$id").apply { mkdirs() }
        val image = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }; File(root, "image.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        File(root, "styles.css").writeText("ruby{color:rgb(255,0,0)}body{background:white}")
        val data = JSONArray().put(JSONObject().put("type", "structured-content").put("content", JSONObject().put("tag", "ruby").put("content", JSONArray().put("Dictionary").put(JSONObject().put("tag", "rt").put("content", "Rich glossary")))))
            .put(JSONObject().put("type", "image").put("path", "image.png").put("width", 64).put("height", 64))
        val trace = java.util.concurrent.CopyOnWriteArrayList<String>(); val diagnostic = File(context.filesDir, "glossary-diagnostics.json")
        val document = DictionaryGlossary.document(data, id, DictionaryGlossary.css(context, id))
        File(context.filesDir, "glossary-fixture.html").writeText(document)
        val direct = DictionaryGlossary.response(context, id, android.net.Uri.parse(DictionaryGlossary.origin(id) + "image.png"))
        val evidence = JSONObject().put("id", id).put("root", root.path).put("rootCanonical", root.canonicalPath).put("directStatus", direct.statusCode).put("directMime", direct.mimeType)
        direct.data.use { stream -> android.graphics.BitmapFactory.decodeStream(stream)?.let { decoded -> evidence.put("directPixel", decoded.getPixel(0, 0)); decoded.recycle() } }
        diagnostic.writeText(evidence.toString(2))
        val latch = CountDownLatch(1); var view: WebView? = null; var screenshot: Bitmap? = null; var failure: Throwable? = null
        var captureStarted = false
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val web = DictionaryGlossary.createView(activity, id, heightChanged = {
                        if (!captureStarted) {
                            captureStarted = true
                            view?.postVisualStateCallback(0, object : WebView.VisualStateCallback() { override fun onComplete(requestId: Long) {
                                view!!.postOnAnimation { view!!.postOnAnimation {
                                    try {
                                        val current = view!!; val location = IntArray(2); current.getLocationInWindow(location)
                                        val software = Bitmap.createBitmap(current.width, current.height, Bitmap.Config.ARGB_8888)
                                        current.draw(Canvas(software)); File(context.filesDir, "glossary-canvas-page.png").outputStream().use { software.compress(Bitmap.CompressFormat.PNG, 100, it) }; software.recycle()
                                        val captured = Bitmap.createBitmap(current.width, current.height, Bitmap.Config.ARGB_8888)
                                        val rectangle = Rect(location[0], location[1], location[0] + current.width, location[1] + current.height)
                                        PixelCopy.request(activity.window, rectangle, captured, { result ->
                                            if (result == PixelCopy.SUCCESS) screenshot = captured else { captured.recycle(); failure = AssertionError("Window PixelCopy failed: $result") }
                                            latch.countDown()
                                        }, Handler(Looper.getMainLooper()))
                                    } catch (error: Throwable) { failure = error; latch.countDown() }
                                } }
                            } })
                        }
                    }, diagnostics = { trace.add(it) }); view = web; activity.setContentView(web); web.layout(0, 0, 700, 600)
                    assertFalse(web.settings.javaScriptEnabled); assertFalse(web.settings.allowFileAccess); assertFalse(web.settings.allowContentAccess)
                    DictionaryGlossary.loadDocument(web, id, document)
                }
                assertTrue("Dictionary renderer did not finish", latch.await(30, TimeUnit.SECONDS)); failure?.let { throw it }
                val bitmap = screenshot!!; var cyan = 0; var red = 0
                for (y in 0 until bitmap.height step 2) for (x in 0 until bitmap.width step 2) { val pixel = bitmap.getPixel(x, y); if (Color.green(pixel) > 220 && Color.blue(pixel) > 220 && Color.red(pixel) < 40) cyan++; if (Color.red(pixel) > 180 && Color.green(pixel) < 100 && Color.blue(pixel) < 100) red++ }
                File(context.filesDir, "glossary-real-page.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                diagnostic.writeText(evidence.put("width", bitmap.width).put("height", bitmap.height).put("cyanPixels", cyan).put("redPixels", red).put("requests", JSONArray(trace)).toString(2))
                assertTrue("Actual local PNG did not render; see glossary-diagnostics.json", cyan > 50); assertTrue("Dictionary CSS did not render; see glossary-diagnostics.json", red > 5)
                bitmap.recycle()
                scenario.onActivity { view?.stopLoading(); view?.destroy() }
            }
        } finally { if (failure != null || screenshot == null) diagnostic.writeText(evidence.put("failure", failure?.stackTraceToString()).put("requests", JSONArray(trace)).toString(2)); root.deleteRecursively() }
    }
}
