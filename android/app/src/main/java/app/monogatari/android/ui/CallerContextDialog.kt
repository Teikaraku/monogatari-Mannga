package app.monogatari.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** A new platform window must retain the caller's localized and storage-scoped context. */
@Composable internal fun CallerContextDialog(onDismissRequest: () -> Unit, properties: DialogProperties = DialogProperties(), content: @Composable () -> Unit) {
    val caller = LocalContext.current
    val callerConfiguration = LocalConfiguration.current
    Dialog(onDismissRequest, properties) {
        CompositionLocalProvider(LocalContext provides caller, LocalConfiguration provides callerConfiguration, content = content)
    }
}
