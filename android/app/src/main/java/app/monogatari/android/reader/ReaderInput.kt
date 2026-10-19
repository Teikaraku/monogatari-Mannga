package app.monogatari.android.reader

import android.view.KeyEvent

/** Hardware reader shortcuts are active independently of optional volume navigation. */
object ReaderInput {
    /** Optional native-input diagnostics; normal reading installs no observer. */
    var traceObserver:((String)->Unit)?=null
    fun trace(message:String) { traceObserver?.invoke(message) }
    var enabled = false
    var mode="ltr"
    var canHandle:(()->Boolean)?=null
    var onPage: ((Int) -> Unit)? = null
    var onOffset:(()->Unit)?=null
    var onChapter:((Int)->Unit)?=null
    var onChapterList:(()->Unit)?=null
    var onBars:(()->Unit)?=null
    var onClose:(()->Unit)?=null
    fun clear() { enabled=false;mode="ltr";canHandle=null;onPage=null;onOffset=null;onChapter=null;onChapterList=null;onBars=null;onClose=null }
    fun dispatch(keyCode: Int,event:KeyEvent?=null): Boolean {
        val accepted=onPage!=null && canHandle?.invoke()!=false
        trace("dispatch key=$keyCode action=${event?.action} registered=${onPage!=null} accepted=$accepted mode=$mode")
        if (onPage==null || !accepted) return false
        if(event!=null && (event.action!=KeyEvent.ACTION_DOWN || event.isCtrlPressed || event.isMetaPressed || event.isAltPressed || event.isShiftPressed)) { trace("dispatch rejected action/modifiers");return false }
        val delta=when(keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN->if(enabled)1 else return false
            KeyEvent.KEYCODE_VOLUME_UP->if(enabled)-1 else return false
            KeyEvent.KEYCODE_PAGE_DOWN->1;KeyEvent.KEYCODE_PAGE_UP->-1
            KeyEvent.KEYCODE_DPAD_LEFT->if(mode=="rtl")1 else -1
            KeyEvent.KEYCODE_DPAD_RIGHT->if(mode=="rtl")-1 else 1
            else->null
        }
        if(delta!=null) { trace("dispatch page delta=$delta");onPage?.invoke(delta);return true }
        val callback:(()->Unit)?=when(keyCode) {
            KeyEvent.KEYCODE_O->onOffset
            KeyEvent.KEYCODE_COMMA->onChapter?.let { { it(1) } }
            KeyEvent.KEYCODE_PERIOD->onChapter?.let { { it(-1) } }
            KeyEvent.KEYCODE_TAB->onChapterList
            KeyEvent.KEYCODE_SPACE->onBars
            KeyEvent.KEYCODE_ESCAPE->onClose
            else->null
        }
        if(callback==null)return false
        if(event==null || event.repeatCount==0)callback()
        return true
    }
}
