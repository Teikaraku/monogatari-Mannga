package app.monogatari.android.dictionary

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.monogatari.android.MainActivity
import app.monogatari.android.ui.DictionaryGlossaryView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real touch events exercise the same AndroidView inside LazyColumn used by dictionary results. */
@RunWith(AndroidJUnit4::class)
class GlossaryScrollInstrumentedTest {
    @Test fun longTableAndRubyGlossaryCanScrollToItsActualImageFooterInsideComposeList() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val id = "scroll-${UUID.randomUUID()}"
        val root = File(context.filesDir, "dictionaries/$id").apply { mkdirs() }
        val footer = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        File(root, "footer.png").outputStream().use { footer.compress(Bitmap.CompressFormat.PNG, 100, it) }; footer.recycle()
        File(root, "styles.css").writeText("body{background:white}td{padding:12px 4px}ruby{color:#333}rt{font-size:10px}")
        val rows = JSONArray()
        repeat(60) { index ->
            rows.put(JSONObject().put("tag", "tr").put("content", JSONArray()
                .put(JSONObject().put("tag", "td").put("content", JSONObject().put("tag", "ruby").put("content", JSONArray()
                    .put("Dictionary row $index").put(JSONObject().put("tag", "rt").put("content", "reading $index")))))
                .put(JSONObject().put("tag", "td").put("content", "A complete long definition, including its original nested table."))))
        }
        val glossary = JSONArray().put(JSONObject().put("type", "structured-content").put("content", JSONObject().put("tag", "table").put("content", rows)))
            .put(JSONObject().put("type", "image").put("path", "footer.png").put("width", 64).put("height", 64)).toString()
        val diagnostics = JSONObject().put("id", id).put("rows", 60)
            .put("sdk", android.os.Build.VERSION.SDK_INT).put("webViewPackage", WebView.getCurrentWebViewPackage()?.let { "${it.packageName}/${it.versionName}" })
        val steps = JSONArray(); val diagnosticFile = File(context.filesDir, "glossary-long-diagnostics.json")
        val injectedTouches = JSONArray(); val receivedTouches = JSONArray()
        File(context.filesDir, "glossary-long-fixture.html").writeText(DictionaryGlossary.document(JSONArray(glossary), id, DictionaryGlossary.css(context, id)))
        var screenshot: Bitmap? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    MaterialTheme { Surface(Modifier.fillMaxSize()) { Column {
                        Text("Long glossary touch fixture")
                        LazyColumn(Modifier.fillMaxWidth().height(380.dp)) {
                            item { DictionaryGlossaryView(id, glossary, "#000000") }
                            item { Spacer(Modifier.height(400.dp)) }
                        }
                    } } }
                } }
                var web: WebView? = null
                val longViewportMinimum = (300 * context.resources.displayMetrics.density).toInt()
                val loadedDeadline = SystemClock.uptimeMillis() + 30_000
                while (SystemClock.uptimeMillis() < loadedDeadline) {
                    var loaded = false
                    scenario.onActivity { activity ->
                        web = findWebView(activity.window.decorView)
                        web?.let { loaded = it.width > 0 && it.height >= longViewportMinimum && it.contentHeight * it.scale > it.height * 2 && it.progress == 100 && it.hasWindowFocus() && it.isShown }
                    }
                    if (loaded) break
                    SystemClock.sleep(50)
                }
                var initialScroll = -1; var ready = false
                scenario.onActivity { web?.let { current ->
                    initialScroll = current.scrollY; ready = current.height >= longViewportMinimum && current.contentHeight * current.scale > current.height * 2 && current.hasWindowFocus() && current.isShown
                    diagnostics.put("contentHeight", current.contentHeight).put("scale", current.scale.toDouble()).put("viewHeight", current.height)
                        .put("windowFocus", current.hasWindowFocus()).put("shown", current.isShown).put("enabled", current.isEnabled)
                    // Observe delivery without consuming native WebView gestures or replacing its dispatch policy.
                    @Suppress("ClickableViewAccessibility")
                    current.setOnTouchListener { _, event ->
                        receivedTouches.put(JSONObject().put("action", event.actionMasked).put("downTime", event.downTime).put("eventTime", event.eventTime)
                            .put("arrivedUptime", SystemClock.uptimeMillis()).put("x", event.x.toDouble()).put("y", event.y.toDouble())
                            .put("windowFocus", current.hasWindowFocus()).put("scrollY", current.scrollY).put("canScrollDown", current.canScrollVertically(1)))
                        false
                    }
                } }
                diagnostics.put("loaded", ready)
                var reachedBottom = false
                for (attempt in 0 until 45) {
                    val visible = Rect(); var canScroll = false
                    scenario.onActivity {
                        web?.let { current ->
                            current.getGlobalVisibleRect(visible); canScroll = current.canScrollVertically(1)
                            steps.put(JSONObject().put("attempt", attempt).put("scrollY", current.scrollY).put("visible", visible.toShortString()).put("canScrollDown", canScroll).put("windowFocus", current.hasWindowFocus()))
                        }
                    }
                    if (!canScroll && initialScroll >= 0) { reachedBottom = true; break }
                    if (visible.height() < 80 || visible.width() < 80) break
                    swipe(visible, injectedTouches)
                    instrumentation.waitForIdleSync(); SystemClock.sleep(80)
                }
                var finalScroll = -1; var canScrollFurther = true
                val captured = CountDownLatch(1); var copyFailure: String? = null
                scenario.onActivity { activity ->
                    val current = web
                    if (current == null) { copyFailure = "Production glossary WebView was not created"; captured.countDown(); return@onActivity }
                    finalScroll = current.scrollY; canScrollFurther = current.canScrollVertically(1)
                    current.postVisualStateCallback(1, object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) { current.postOnAnimation { current.postOnAnimation frame@{
                            val visible = Rect()
                            if (!current.getGlobalVisibleRect(visible) || visible.isEmpty) { copyFailure = "Glossary left the visible LazyColumn viewport"; captured.countDown(); return@frame }
                            val screen = IntArray(2); val window = IntArray(2)
                            current.getLocationOnScreen(screen); current.getLocationInWindow(window)
                            visible.offset(window[0] - screen[0], window[1] - screen[1])
                            val bitmap = Bitmap.createBitmap(visible.width(), visible.height(), Bitmap.Config.ARGB_8888)
                            PixelCopy.request(activity.window, visible, bitmap, { result ->
                                if (result == PixelCopy.SUCCESS) screenshot = bitmap else { bitmap.recycle(); copyFailure = "PixelCopy=$result" }
                                captured.countDown()
                            }, Handler(Looper.getMainLooper()))
                        } } }
                    })
                }
                val captureFinished = captured.await(15, TimeUnit.SECONDS)
                var green = 0
                screenshot?.let { bitmap ->
                    for (y in 0 until bitmap.height step 2) for (x in 0 until bitmap.width step 2) {
                        val pixel = bitmap.getPixel(x, y)
                        if (Color.green(pixel) > 220 && Color.red(pixel) < 40 && Color.blue(pixel) < 40) green++
                    }
                    File(context.filesDir, "glossary-long-page.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                // A complete definition must also release the next drag to the enclosing results.
                val beforeHandoff = Rect(); val afterHandoff = Rect()
                scenario.onActivity { web?.getGlobalVisibleRect(beforeHandoff) }
                if (!canScrollFurther && beforeHandoff.height() >= 80) {
                    swipe(beforeHandoff, injectedTouches); instrumentation.waitForIdleSync(); SystemClock.sleep(120)
                    scenario.onActivity { web?.getGlobalVisibleRect(afterHandoff) }
                }
                val handedOff = !beforeHandoff.isEmpty && (afterHandoff.isEmpty || beforeHandoff != afterHandoff)
                diagnosticFile.writeText(diagnostics.put("steps", steps).put("initialScroll", initialScroll).put("finalScroll", finalScroll)
                    .put("reachedBottom", reachedBottom).put("canScrollFurther", canScrollFurther).put("greenPixels", green)
                    .put("captureFinished", captureFinished).put("captureFailure", copyFailure).put("parentHandoff", handedOff)
                    .put("injectedTouches", injectedTouches).put("receivedTouches", receivedTouches)
                    .put("beforeHandoff", beforeHandoff.toShortString()).put("afterHandoff", afterHandoff.toShortString()).toString(2))
                assertTrue("Long dictionary document did not load; see glossary-long-diagnostics.json", ready)
                assertTrue("Real swipes failed to scroll the glossary inside LazyColumn; see glossary-long-diagnostics.json", finalScroll > initialScroll)
                assertFalse("Long dictionary definition is still clipped before its end; see glossary-long-diagnostics.json", canScrollFurther)
                assertTrue("Actual dictionary image footer is not visible; see glossary-long-diagnostics.json", captureFinished && copyFailure == null && green > 50)
                assertTrue("The outer result list did not resume scrolling at the glossary boundary; see glossary-long-diagnostics.json", handedOff)
            }
        } catch (error: Throwable) {
            if (!diagnosticFile.isFile) diagnosticFile.writeText(diagnostics.put("steps", steps).put("injectedTouches", injectedTouches).put("receivedTouches", receivedTouches).put("failure", error.stackTraceToString()).toString(2))
            throw error
        } finally { screenshot?.recycle(); root.deleteRecursively() }
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findWebView(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun swipe(rectangle: Rect, injectedTouches: JSONArray) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downTime = SystemClock.uptimeMillis(); val x = rectangle.exactCenterX()
        val startY = rectangle.top + rectangle.height() * .85f; val endY = rectangle.top + rectangle.height() * .15f
        fun send(action: Int, y: Float) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            injectedTouches.put(JSONObject().put("action", action).put("downTime", event.downTime).put("eventTime", event.eventTime).put("x", x.toDouble()).put("y", y.toDouble()))
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, startY)
        for (step in 1..12) { SystemClock.sleep(16); send(MotionEvent.ACTION_MOVE, startY + (endY - startY) * step / 12) }
        send(MotionEvent.ACTION_UP, endY)
    }
}
