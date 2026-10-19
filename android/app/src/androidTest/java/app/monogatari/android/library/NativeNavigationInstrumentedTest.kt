package app.monogatari.android.library

import android.view.KeyEvent
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.monogatari.android.MonogatariViewModel
import app.monogatari.android.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class NativeNavigationInstrumentedTest {
    @Test fun consumingImportedBackupFileKeepsItsRealPreviewDialogOpen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "Backup preview ${UUID.randomUUID()}"
        val file = File(context.filesDir, "backups/preview-fixture-${UUID.randomUUID()}.aidoku.zip").apply { parentFile!!.mkdirs() }
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(JSONObject().put("format", "aidoku-complete").put("version", 2).put("name", name).put("date", System.currentTimeMillis() / 1000.0)
                .put("appVersion", "0.9").put("counts", JSONObject()).toString().toByteArray()); zip.closeEntry()
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var model: MonogatariViewModel? = null
                scenario.onActivity { activity -> model = ViewModelProvider(activity)[MonogatariViewModel::class.java]; model!!.navigate("backups", file.path) }
                fun contains(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
                    if (node == null) return false
                    if (node.text?.toString() == name) return true
                    return (0 until node.childCount).any { contains(node.getChild(it)) }
                }
                val deadline = System.currentTimeMillis() + 10_000
                while (System.currentTimeMillis() < deadline && !contains(instrumentation.uiAutomation.rootInActiveWindow)) {
                    Thread.sleep(80); instrumentation.waitForIdleSync()
                }
                assertTrue("The actual backup preview disappeared after consuming its file key", contains(instrumentation.uiAutomation.rootInActiveWindow))
                scenario.onActivity { assertEquals("", model!!.key) }
            }
        } finally { file.delete() }
    }
    @Test fun originalTabShortcutsSwitchRealActivityAndRestoreEachTabsNavigation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[MonogatariViewModel::class.java]
                model.screen = "main"
                activity.window.callback.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_5, 0, KeyEvent.META_CTRL_ON))
                assertEquals(4, model.tab)
                activity.window.callback.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_1, 0, KeyEvent.META_CTRL_ON))
                assertEquals(0, model.tab)
                model.selectTab(4); model.navigate("setting", "About")
                activity.window.callback.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_2, 0, KeyEvent.META_CTRL_ON))
                assertEquals("main", model.screen); assertEquals(1, model.tab)
                activity.window.callback.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_5, 0, KeyEvent.META_CTRL_ON))
                assertEquals("setting", model.screen); assertEquals("About", model.key)
                model.selectTab(4)
                assertEquals("main", model.screen)
            }
        }
    }
}
