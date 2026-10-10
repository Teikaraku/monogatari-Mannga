package app.monogatari.android.sources

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.monogatari.android.MainActivity
import app.monogatari.android.data.AppRepository
import app.monogatari.android.data.AppState
import app.monogatari.android.data.SourceConfig
import app.monogatari.android.reader.ReaderFixtureContext
import app.monogatari.android.ui.MonogatariApp
import app.monogatari.android.ui.Localization
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SourceSettingsInstrumentedTest {
    private var toggleTitle = "Use Chapters"
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        fun visit(node: AccessibilityNodeInfo?, depth: Int = 0) {
            if (node == null || depth > 30 || size > 600) return
            add(node); for (index in 0 until node.childCount) visit(node.getChild(index), depth + 1)
        }
        visit(automation.rootInActiveWindow)
    }
    private fun hasToggleLabel(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (node.contentDescription?.toString() == toggleTitle) return true
        if (depth >= 8) return false
        return (0 until node.childCount).any { index -> node.getChild(index)?.let { hasToggleLabel(it, depth + 1) } == true }
    }
    // Compose puts the label in a fake descendant while native checked state
    // and the real touch bounds belong to this checkable parent Switch.
    private fun toggle() = nodes().firstOrNull { it.isVisibleToUser && it.isCheckable && hasToggleLabel(it) }
    private fun bounds(node: AccessibilityNodeInfo): Rect = Rect().also(node::getBoundsInScreen)
    private fun stableToggleBounds(): Rect {
        var previous: Rect? = null; var unchangedSince = 0L
        waitFor("The actual checkable Switch must settle before native touch") {
            val node = toggle()
            node?.refresh()
            val current = node?.takeIf { it.isEnabled }?.let(::bounds)
            if (current == null || current.isEmpty) { previous = null; unchangedSince = 0L; false }
            else {
                if (current != previous) { previous = current; unchangedSince = SystemClock.uptimeMillis() }
                SystemClock.uptimeMillis() - unchangedSince >= 200
            }
        }
        return checkNotNull(previous)
    }
    private fun waitFor(message: String, timeout: Long = 12_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            Thread.sleep(40)
        }
        assertTrue(message, condition())
    }
    private fun tap(rect: Rect) {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, rect.exactCenterX(), rect.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue("Actual touchscreen event was rejected", automation.injectInputEvent(event, true)) } finally { event.recycle() }
            Thread.sleep(30)
        }
    }
    private fun record(context: Context, label: String, data: JSONObject) {
        val directory = File(context.getExternalFilesDir(null), "verification").apply { mkdirs() }
        automation.takeScreenshot()?.let { image ->
            File(directory, "$label.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        }
        val controls = JSONArray(nodes().filter { it.isVisibleToUser }.map { node -> JSONObject()
            .put("text", node.text?.toString()).put("description", node.contentDescription?.toString())
            .put("checkable", node.isCheckable).put("checked", node.isChecked).put("enabled", node.isEnabled)
            .put("bounds", bounds(node).flattenToString()).put("childCount", node.childCount)
            .put("children", JSONArray((0 until node.childCount).mapNotNull { index -> node.getChild(index)?.let { child ->
                JSONObject().put("description", child.contentDescription?.toString()).put("text", child.text?.toString())
                    .put("checkable", child.isCheckable).put("bounds", bounds(child).flattenToString()) } }))
            .put("parentBounds", node.parent?.let { bounds(it).flattenToString() }) })
        File(directory, "$label.json").writeText(data.put("targetLabel", toggleTitle).put("controls", controls).toString(2))
    }

    @Test fun nativeToggleImmediatelyRepaintsAndPersistsWithoutAnAppStateChange() = runBlocking {
        val context = ReaderFixtureContext(ApplicationProvider.getApplicationContext<Context>())
        val repo = AppRepository.get(context)
        // Real built-in settings contain a toggle and no slider/stepper. An empty
        // Komga URL never makes a network request while loading these settings.
        val source = SourceConfig("settings-toggle-fixture", "Toggle fixture", "komga", "")
        val settings = JSONObject().put("General.useSystemAppearance", false).put("General.appearance", 0)
            .put("Library.backgroundRefresh", false).put("Library.updateInterval", "never")
        repo.change { AppState(sources = listOf(source), settings = settings.toString(), preferences = repo.settings.effectivePreferences(settings)) }
        val preferences = SourcePreferences(context, source.id)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val vm = context.model(repo); vm.navigate("sourceSettings", source.id)
                    val localized = context.ui(activity, "en")
                    toggleTitle = Localization.string(localized, "USE_CHAPTERS")
                    activity.setContent { CompositionLocalProvider(LocalContext provides localized,
                        LocalLayoutDirection provides LayoutDirection.Ltr, LocalActivityResultRegistryOwner provides activity) { MonogatariApp(vm) } }
                }
                try {
                    waitFor("Real source settings did not load") { preferences.get("useChapters") == false && (toggle() != null || nodes().any { it.isVisibleToUser && it.isScrollable }) }
                    repeat(12) {
                        if (toggle() == null) {
                            val list = nodes().firstOrNull { it.isVisibleToUser && it.isScrollable }
                            assertNotNull("The actual settings list must remain scrollable", list)
                            assertTrue(list!!.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)); Thread.sleep(120)
                        }
                    }
                    waitFor("Actual Use Chapters switch was not visible") { toggle()?.isEnabled == true }
                    assertFalse(toggle()!!.isChecked)
                    val before = repo.state.value; val originalBounds = stableToggleBounds()
                    record(context, "source-settings-toggle-before", JSONObject().put("stored", preferences.get("useChapters"))
                        .put("touchTarget", originalBounds.flattenToString()).put("checked", toggle()!!.isChecked))
                    tap(originalBounds)
                    waitFor("Persisted true must immediately repaint the actual native switch", 2_000) { preferences.get("useChapters") == true && toggle()?.isChecked == true }
                    assertEquals("Source setting notification must not provide incidental app-state invalidation", before, repo.state.value)
                    assertEquals("Updating a toggle must preserve list scroll position", originalBounds, bounds(toggle()!!))
                    assertEquals(true, SourcePreferences(context, source.id).get("useChapters"))
                    record(context, "source-settings-toggle-on", JSONObject().put("stored", true).put("checked", toggle()!!.isChecked).put("appStateUnchanged", before == repo.state.value))
                    val restoredTarget = stableToggleBounds()
                    assertEquals(originalBounds, restoredTarget)
                    tap(restoredTarget)
                    waitFor("A second actual touch must persist and immediately restore the native switch", 2_000) { preferences.get("useChapters") == false && toggle()?.isChecked == false }
                    assertEquals(before, repo.state.value); assertEquals(originalBounds, bounds(toggle()!!))
                    assertEquals(false, SourcePreferences(context, source.id).get("useChapters"))
                    record(context, "source-settings-toggle-off", JSONObject().put("stored", false).put("checked", toggle()!!.isChecked).put("appStateUnchanged", before == repo.state.value))
                } catch (error: Throwable) {
                    record(context, "source-settings-toggle-failure", JSONObject().put("error", error.toString()).put("stored", preferences.get("useChapters")))
                    throw error
                }
            }
        } finally { Thread.sleep(400); context.clear() }
    }
}
