package app.monogatari.android.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.util.Base64

/** Only the debug APK includes this ADB bridge for Unicode input in the Windows preview. */
class PreviewInputReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "app.monogatari.android.PREVIEW_PASTE") return
        val encoded = intent.getStringExtra("textBase64")?.takeIf { it.length <= 32768 } ?: return
        val text = runCatching { Base64.decode(encoded, Base64.NO_WRAP).toString(Charsets.UTF_8) }.getOrNull() ?: return
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Aidoku preview", text))
        resultCode = 1
    }
}
