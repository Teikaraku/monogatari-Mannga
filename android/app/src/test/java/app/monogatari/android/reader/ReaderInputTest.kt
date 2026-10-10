package app.monogatari.android.reader

import android.view.KeyEvent
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ReaderInputTest {
    @Before fun start()=ReaderInput.clear()
    @After fun stop()=ReaderInput.clear()
    @Test fun arrowsRespectImageDirectionWhilePageKeysDoNotRequireVolumeNavigation() {
        val pages=mutableListOf<Int>();ReaderInput.onPage={pages+=it}
        assertFalse(ReaderInput.enabled)
        ReaderInput.mode="rtl";assertTrue(ReaderInput.dispatch(KeyEvent.KEYCODE_DPAD_LEFT));assertTrue(ReaderInput.dispatch(KeyEvent.KEYCODE_DPAD_RIGHT))
        ReaderInput.mode="ltr";ReaderInput.dispatch(KeyEvent.KEYCODE_DPAD_LEFT);ReaderInput.dispatch(KeyEvent.KEYCODE_DPAD_RIGHT)
        ReaderInput.mode="vertical";ReaderInput.dispatch(KeyEvent.KEYCODE_PAGE_UP);ReaderInput.dispatch(KeyEvent.KEYCODE_PAGE_DOWN)
        assertEquals(listOf(1,-1,-1,1,-1,1),pages)
        assertFalse(ReaderInput.dispatch(KeyEvent.KEYCODE_VOLUME_DOWN));ReaderInput.enabled=true;assertTrue(ReaderInput.dispatch(KeyEvent.KEYCODE_VOLUME_DOWN));assertEquals(1,pages.last())
    }
    @Test fun originalActionsDispatchOnceAndUseOriginalChapterShortcutOrder() {
        val actions=mutableListOf<String>();ReaderInput.onPage={};ReaderInput.onOffset={actions+="offset"};ReaderInput.onChapter={actions+="chapter:$it"}
        ReaderInput.onChapterList={actions+="chapters"};ReaderInput.onBars={actions+="bars"};ReaderInput.onClose={actions+="close"}
        listOf(KeyEvent.KEYCODE_O,KeyEvent.KEYCODE_COMMA,KeyEvent.KEYCODE_PERIOD,KeyEvent.KEYCODE_TAB,KeyEvent.KEYCODE_SPACE,KeyEvent.KEYCODE_ESCAPE).forEach { assertTrue(ReaderInput.dispatch(it)) }
        assertEquals(listOf("offset","chapter:1","chapter:-1","chapters","bars","close"),actions)
        assertTrue(ReaderInput.dispatch(KeyEvent.KEYCODE_SPACE,KeyEvent(0,0,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_SPACE,1)));assertEquals(6,actions.size)
    }
    @Test fun modalInputModifierAndKeyUpEventsAreNeverIntercepted() {
        var called=0;ReaderInput.onPage={called++};ReaderInput.onBars={called++};ReaderInput.canHandle={false}
        assertFalse(ReaderInput.dispatch(KeyEvent.KEYCODE_DPAD_LEFT));assertFalse(ReaderInput.dispatch(KeyEvent.KEYCODE_SPACE));assertEquals(0,called)
        ReaderInput.canHandle={true}
        assertFalse(ReaderInput.dispatch(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent(0,0,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_LEFT,0)))
        assertFalse(ReaderInput.dispatch(KeyEvent.KEYCODE_SPACE,KeyEvent(0,0,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_SPACE,0,KeyEvent.META_CTRL_ON)))
        assertFalse(ReaderInput.dispatch(KeyEvent.KEYCODE_A));ReaderInput.clear();assertFalse(ReaderInput.dispatch(KeyEvent.KEYCODE_PAGE_DOWN));assertEquals(0,called)
    }
}
