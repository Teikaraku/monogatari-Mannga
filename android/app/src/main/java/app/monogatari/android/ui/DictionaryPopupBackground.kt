package app.monogatari.android.ui

import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.Window
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import java.util.function.Consumer

/** A floating translucent native window is required by public background-blur APIs. */
@Composable internal fun DictionaryPopupDialog(onDismiss: () -> Unit, properties: DialogProperties = DialogProperties(), content: @Composable () -> Unit) {
    val caller = LocalContext.current; val configuration = LocalConfiguration.current
    val dialogContext = remember(caller) { ContextThemeWrapper(caller, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar) }
    CompositionLocalProvider(LocalContext provides dialogContext) {
        CallerContextDialog(onDismiss, properties) {
            CompositionLocalProvider(LocalContext provides caller, LocalConfiguration provides configuration, content = content)
        }
    }
}

/** API26-30 and system-disabled/GPU-unsupported devices always retain opaque readable backgrounds. */
@Composable internal fun dictionaryPopupColor(disableTransparency: Boolean, surface: Color): Color {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window
    val radius = with(LocalDensity.current) { 30.dp.roundToPx() }
    val corner = with(LocalDensity.current) { 24.dp.toPx() }
    var blurred by remember(window, disableTransparency) { mutableStateOf(false) }
    DisposableEffect(window, disableTransparency, radius, corner) {
        val close = if (window != null && !disableTransparency && Build.VERSION.SDK_INT >= 31)
            DictionaryWindowBlur.attach(window, radius, corner) { blurred = it } else null
        onDispose { close?.invoke() }
    }
    return if (blurred && !disableTransparency) surface.copy(alpha = 0.82f) else surface
}

/** Uses compositor-owned window blur; never captures another window or requests projection. */
@RequiresApi(31)
private object DictionaryWindowBlur {
    fun attach(window: Window, radius: Int, corner: Float, changed: (Boolean) -> Unit): (() -> Unit)? {
        if (!window.isFloating) { changed(false); return null }
        val manager = window.context.getSystemService(WindowManager::class.java) ?: return null
        val originalBackground = window.decorView.background
        val originalAttributes = WindowManager.LayoutParams().apply { copyFrom(window.attributes) }
        val background = GradientDrawable().apply { setColor(android.graphics.Color.TRANSPARENT); cornerRadius = corner }
        var active = true
        fun apply(enabled: Boolean) {
            if (!active) return
            val applied = enabled && runCatching {
                window.setBackgroundDrawable(background)
                window.setBackgroundBlurRadius(radius)
            }.isSuccess
            if (!applied) {
                runCatching { window.setBackgroundBlurRadius(0); window.setBackgroundDrawable(originalBackground) }
            }
            changed(applied)
        }
        // The system listener immediately publishes its current state and also
        // reports power saver, GPU limitations and multimedia disablement.
        val listener = Consumer<Boolean> { apply(it) }
        try {
            apply(manager.isCrossWindowBlurEnabled)
            manager.addCrossWindowBlurEnabledListener(listener)
        } catch (_: RuntimeException) {
            active = false
            runCatching { manager.removeCrossWindowBlurEnabledListener(listener) }
            runCatching { window.setBackgroundBlurRadius(0); window.setBackgroundDrawable(originalBackground); window.attributes = originalAttributes }
            changed(false); return null
        }
        return {
            active = false
            runCatching { manager.removeCrossWindowBlurEnabledListener(listener) }
            // These fresh dictionary windows start without any background blur.
            runCatching { window.setBackgroundBlurRadius(0); window.setBackgroundDrawable(originalBackground); window.attributes = originalAttributes }
        }
    }
}
