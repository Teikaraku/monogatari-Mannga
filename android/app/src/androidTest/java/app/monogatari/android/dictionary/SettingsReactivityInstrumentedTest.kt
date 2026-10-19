package app.monogatari.android.dictionary

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.monogatari.android.MonogatariViewModel
import app.monogatari.android.MainActivity
import app.monogatari.android.data.AppRepository
import app.monogatari.android.data.AppState
import app.monogatari.android.reader.ReaderFixtureContext
import app.monogatari.android.ui.CompleteBackupsScreen
import app.monogatari.android.ui.DictionarySettingsScreen
import app.monogatari.android.ui.Localization
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Native touch and A11y checks on the actual production composables, using isolated storage. */
@RunWith(AndroidJUnit4::class)
class SettingsReactivityInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?, depth: Int = 0) {
            if (node == null || depth > 30 || result.size > 600) return
            result += node; for (index in 0 until node.childCount) visit(node.getChild(index), depth + 1)
        }
        visit(instrumentation.uiAutomation.rootInActiveWindow)
        return result
    }
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun hasLabel(node: AccessibilityNodeInfo, text: String) = node.text?.toString()?.lineSequence()?.any { it == text } == true
    private fun label(text: String) = nodes().firstOrNull { it.isVisibleToUser && hasLabel(it, text) }
    private fun toggle(text: String): AccessibilityNodeInfo? {
        val all = nodes().filter { it.isVisibleToUser }; val labels = all.filter { hasLabel(it, text) }
        return all.filter { it.isCheckable && it.isEnabled && labels.any { label -> bounds(it).let { r -> bounds(label).centerY() in r.top..r.bottom } } }.maxByOrNull { bounds(it).centerX() }
    }
    private fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 12_000
        while (SystemClock.uptimeMillis() < deadline) { instrumentation.waitForIdleSync(); if (condition()) return; Thread.sleep(60) }
        assertTrue(message, condition())
    }
    private fun touch(node: AccessibilityNodeInfo) {
        val r = bounds(node); assertTrue("Touch target must be on screen", node.isVisibleToUser && r.width() > 0 && r.height() > 0)
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, r.centerX().toFloat(), r.centerY().toFloat(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue("Actual native touch was rejected", instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        instrumentation.waitForIdleSync()
    }
    private fun fixture(dictionary: Boolean, case: String, body: suspend (AppRepository, MonogatariViewModel, Context, JSONObject) -> Unit) = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>(); val context = ReaderFixtureContext(base); val repo = AppRepository.get(context)
        val events = JSONObject().put("dictionary", dictionary).put("case", case)
        val values = JSONObject().put("Dictionary.enable", false).put("Dictionary.restrictOCRLanguages", false)
            .put("Dictionary.updateInterval", "never").put("AutomaticBackups.enabled", false).put("AutomaticBackups.interval", "daily")
            .put("Library.backgroundRefresh", false).put("Library.updateInterval", "never")
        repo.change { AppState(settings = values.toString()) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm: MonogatariViewModel; lateinit var localized: Context
                scenario.onActivity { activity ->
                    vm = context.model(repo); localized = context.ui(activity, "en")
                    activity.setContent { CompositionLocalProvider(LocalContext provides localized, LocalActivityResultRegistryOwner provides activity) {
                        MaterialTheme { if (dictionary) DictionarySettingsScreen(vm) else CompleteBackupsScreen(vm, scheduleAutomatic = false) }
                    } }
                }
                try { body(repo, vm, localized, events) } catch (failure: Throwable) {
                    events.put("error", failure.toString()).put("modelMessage", vm.message)
                    instrumentation.uiAutomation.takeScreenshot()?.let { image -> File(base.filesDir, "settings-reactivity-$case-failure.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle() }
                    throw failure
                } finally {
                    events.put("nodesBeforeClosingActivity", JSONArray(nodes().map { node -> JSONObject().put("text", node.text?.toString()).put("class", node.className?.toString()).put("checkable", node.isCheckable).put("checked", node.isChecked).put("visible", node.isVisibleToUser).put("bounds", bounds(node).toShortString()) }))
                }
            }
        } finally {
            val actual = JSONArray(nodes().map { node -> JSONObject().put("text", node.text?.toString()).put("description", node.contentDescription?.toString()).put("class", node.className?.toString()).put("checkable", node.isCheckable).put("checked", node.isChecked).put("visible", node.isVisibleToUser).put("bounds", bounds(node).toShortString()) })
            events.put("finalSettings", JSONObject(repo.state.value.settings)).put("nodes", actual)
            File(base.filesDir, "settings-reactivity-$case-diagnostics.json").writeText(events.toString(2))
            Thread.sleep(400); context.clear()
        }
    }
    @Test fun dictionaryLookupSwitchRealTouchImmediatelyChangesCheckedThenSecondTouchRestoresIt() = fixture(true, "dictionary") { repo, vm, ui, events ->
        val title = Localization.string(ui, "DICTIONARY_LOOKUP")
        waitFor("Actual dictionary switch missing") { toggle(title) != null }
        assertFalse(toggle(title)!!.isChecked); assertFalse(repo.settings.bool("Dictionary.enable"))
        touch(toggle(title)!!)
        waitFor("Native touch did not commit Dictionary.enable; message=${vm.message}") { repo.settings.bool("Dictionary.enable") }
        waitFor("Committed Dictionary.enable did not immediately redraw the real switch") { toggle(title)?.isChecked == true }
        events.put("firstTouchPersisted", true).put("firstTouchChecked", true)
        touch(toggle(title)!!)
        waitFor("Second touch did not restore Dictionary.enable") { !repo.settings.bool("Dictionary.enable") }
        waitFor("Second touch did not immediately uncheck the real switch") { toggle(title)?.isChecked == false }
        events.put("secondTouchPersisted", false).put("secondTouchChecked", false)
    }
    @Test fun automaticBackupScreenObservesActualSettingsFlowWithoutRecreatingItsComposable() = fixture(false, "backups-outer") { repo, _, ui, events ->
        val title = Localization.string(ui, "AUTOMATIC_BACKUPS")
        waitFor("Actual automatic-backup switch missing") { toggle(title) != null }
        assertFalse(toggle(title)!!.isChecked)
        repo.settings.put("AutomaticBackups.enabled", true)
        waitFor("Backup switch failed to observe actual settings Flow") { toggle(title)?.isChecked == true }
        events.put("outerSwitchCheckedAfterFlow", true)
        repo.settings.put("AutomaticBackups.interval", "weekly")
        waitFor("Backup interval did not redraw from actual settings Flow") { label(Localization.string(ui, "WEEKLY")) != null }
        repo.settings.put("AutomaticBackups.enabled", false)
        waitFor("Backup switch failed to uncheck after actual settings Flow") { toggle(title)?.isChecked == false }
        events.put("outerSwitchCheckedAfterRestore", false)
    }
    @Test fun automaticBackupDialogObservesRealFlowAndShowsThenHidesTheIntervalControls() = fixture(false, "backups-dialog") { repo, _, ui, events ->
        val title = Localization.string(ui, "AUTOMATIC_BACKUPS"); val interval = Localization.string(ui, "BACKUP_INTERVAL")
        waitFor("Actual automatic-backup card missing") { label(title) != null }
        touch(label(title)!!)
        waitFor("Actual automatic-backup dialog missing") { nodes().any { it.isVisibleToUser && it.text?.toString() == Localization.string(ui, "DONE") } && toggle(title) != null }
        assertFalse(toggle(title)!!.isChecked); assertNull(label(interval))
        repo.settings.put("AutomaticBackups.enabled", true)
        waitFor("Dialog did not observe the real backup-settings Flow") { toggle(title)?.isChecked == true && label(interval) != null }
        events.put("dialogCheckedAfterFlow", true).put("intervalVisibleAfterFlow", true)
        repo.settings.put("AutomaticBackups.enabled", false)
        waitFor("Dialog failed to remove conditional interval controls immediately") { toggle(title)?.isChecked == false && label(interval) == null }
        events.put("dialogCheckedAfterRestore", false).put("intervalVisibleAfterRestore", false)
    }
}
