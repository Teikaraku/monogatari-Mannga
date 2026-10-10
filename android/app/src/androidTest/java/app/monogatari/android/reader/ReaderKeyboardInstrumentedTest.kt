package app.monogatari.android.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.monogatari.android.MainActivity
import app.monogatari.android.MonogatariViewModel
import app.monogatari.android.data.*
import app.monogatari.android.ui.ReaderScreen
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReaderKeyboardInstrumentedTest {
    @Test fun nativeHardwareEventsTurnRealReaderPagesAndRespectChapterModal() = runBlocking {
        val base=ApplicationProvider.getApplicationContext<Context>();val context=ReaderFixtureContext(base);val repo=AppRepository.get(context)
        val folder=File(context.filesDir,"reader-keyboard/${UUID.randomUUID()}").apply { mkdirs() }
        val pages=(0..2).map { index ->val image=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888).apply { eraseColor(listOf(Color.RED,Color.BLUE,Color.GREEN)[index]) }
            val file=File(folder,"$index.png");file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) };image.recycle();Uri.fromFile(file).toString() }
        val chapter=Chapter("keyboard","Real keyboard pages",1.0,pages=pages,total=3,downloaded=true)
        val manga=Manga("keyboard-${UUID.randomUUID()}","local","Keyboard verification",chapters=listOf(chapter),inLibrary=true)
        val instrumentation=InstrumentationRegistry.getInstrumentation();lateinit var vm:MonogatariViewModel
        val unrelatedOperation=kotlinx.coroutines.CompletableDeferred<Unit>()
        var active:MainActivity?=null;var currentUi:Context=context
        val events=java.util.concurrent.CopyOnWriteArrayList<String>()
        fun trace(value:String) { events+="${android.os.SystemClock.uptimeMillis()} $value" }
        fun persistEvents() { File(base.getExternalFilesDir(null),"verification").apply{mkdirs()}.resolve("reader-keyboard-events.txt").writeText(events.joinToString("\n")) }
        fun press(key:Int) {
            var focus=false
            instrumentation.runOnMainSync { focus=active?.hasWindowFocus()==true;trace("press key=$key focus=$focus touchMode=${active?.window?.decorView?.isInTouchMode} viewFocus=${active?.currentFocus?.javaClass?.name} registered=${ReaderInput.onPage!=null} canHandle=${ReaderInput.canHandle?.invoke()} page=${vm.readerVisiblePage}") }
            val focusedPackage=instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()
            trace("focused package=$focusedPackage")
            // Chapter dialogs own a separate focused window; those modal keys must still be injected into this app.
            assertTrue("Reader or its modal must own the native focused window, got $focusedPackage",focus || focusedPackage==base.packageName)
            val now=android.os.SystemClock.uptimeMillis()
            fun event(action:Int,time:Long)=KeyEvent(now,time,action,key,0,0,android.view.KeyCharacterMap.VIRTUAL_KEYBOARD,0,
                KeyEvent.FLAG_FROM_SYSTEM,android.view.InputDevice.SOURCE_KEYBOARD)
            assertTrue("Native key down injection rejected for $key",instrumentation.uiAutomation.injectInputEvent(event(KeyEvent.ACTION_DOWN,now),true))
            assertTrue("Native key up injection rejected for $key",instrumentation.uiAutomation.injectInputEvent(event(KeyEvent.ACTION_UP,android.os.SystemClock.uptimeMillis()),true))
            instrumentation.waitForIdleSync()
        }
        suspend fun page(expected:Int) { val until=System.currentTimeMillis()+5000;while(vm.readerVisiblePage!=expected && System.currentTimeMillis()<until){Thread.sleep(60);instrumentation.waitForIdleSync()};if(vm.readerVisiblePage!=expected){persistEvents();context.diagnose("reader-keyboard-failure-page-$expected",vm,active,currentUi)};assertEquals(expected,vm.readerVisiblePage) }
        try {
            repo.change { AppState(manga=listOf(manga),settings=ReaderOptions(mode="ltr",pageLayout="single",animateTransitions=false,volumeKeys=false,fullscreen=false).toLegacy(JSONObject()).put("Dictionary.enable",false).toString()) }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->vm=context.model(repo).apply { key=manga.id;chapterKey=chapter.key;readerChapter=chapter;screen="reader" }
                    val scoped=context.ui(activity)
                    active=activity;currentUi=scoped
                    activity.setContent { val state by repo.state.collectAsState();MaterialTheme { CompositionLocalProvider(LocalContext provides scoped,LocalActivityResultRegistryOwner provides activity) { ReaderScreen(vm,state) } } }
                    val callback=activity.window.callback
                    activity.window.callback=object:android.view.Window.Callback by callback {
                        override fun dispatchKeyEvent(event:KeyEvent):Boolean {
                            trace("window key=${event.keyCode} action=${event.action} focus=${activity.hasWindowFocus()}")
                            return callback.dispatchKeyEvent(event).also { trace("window returned=$it") }
                        }
                    }
                }
                val readyUntil=System.currentTimeMillis()+10000
                var focused=false
                while(!focused && System.currentTimeMillis()<readyUntil) {
                    instrumentation.runOnMainSync { focused=active?.hasWindowFocus()==true && vm.readerVisibleTotal==3 && ReaderInput.onPage!=null }
                    if(!focused)Thread.sleep(60)
                }
                assertTrue("Real reader window and input handler must be ready",focused)
                instrumentation.runOnMainSync { ReaderInput.traceObserver=::trace }
                instrumentation.waitForIdleSync();assertFalse(ReaderInput.enabled)
                var touchX=0f;var touchY=0f
                instrumentation.runOnMainSync { touchX=active!!.window.decorView.width/2f;touchY=active!!.window.decorView.height/2f }
                val touchTime=android.os.SystemClock.uptimeMillis()
                for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)) {
                    val touch=android.view.MotionEvent.obtain(touchTime,android.os.SystemClock.uptimeMillis(),action,touchX,touchY,0)
                    touch.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
                    try { assertTrue("Native reader touch injection rejected",instrumentation.uiAutomation.injectInputEvent(touch,true)) } finally { touch.recycle() }
                }
                instrumentation.waitForIdleSync()
                var inTouchMode=false
                instrumentation.runOnMainSync { inTouchMode=active!!.window.decorView.isInTouchMode;trace("before first navigation touchMode=$inTouchMode viewFocus=${active!!.currentFocus?.javaClass?.name}") }
                assertTrue("Real user touch must put the reader in touch mode before its first hardware key",inTouchMode)
                instrumentation.runOnMainSync { vm.run { unrelatedOperation.await() } }
                val busyUntil=System.currentTimeMillis()+5000
                while(!vm.busy && System.currentTimeMillis()<busyUntil) { Thread.sleep(50);instrumentation.waitForIdleSync() }
                assertTrue("An unrelated background operation must actually remain pending",vm.busy)
                press(KeyEvent.KEYCODE_PAGE_DOWN);page(1)
                unrelatedOperation.complete(Unit)
                press(KeyEvent.KEYCODE_DPAD_RIGHT);page(2)
                press(KeyEvent.KEYCODE_TAB);Thread.sleep(400);assertEquals(false,ReaderInput.canHandle?.invoke())
                press(KeyEvent.KEYCODE_DPAD_LEFT);Thread.sleep(180);assertEquals(2,vm.readerVisiblePage)
                press(KeyEvent.KEYCODE_BACK);Thread.sleep(400)
                press(KeyEvent.KEYCODE_PAGE_UP);page(1)
                press(KeyEvent.KEYCODE_ESCAPE);Thread.sleep(250);assertNotEquals("reader",vm.screen)
                File(context.getExternalFilesDir(null),"verification").apply { mkdirs() }.resolve("reader-keyboard.txt").writeText("Native Activity KeyEvents: PageDown works with volume disabled; Right advances actual LTR pager; Tab opens chapter modal and Left is blocked; Back dismisses modal; PageUp moves backward; Esc closes reader.")
            }
        } finally { unrelatedOperation.complete(Unit);persistEvents();ReaderInput.traceObserver=null;ReaderInput.clear();Thread.sleep(300);context.clear() }
    }
}
