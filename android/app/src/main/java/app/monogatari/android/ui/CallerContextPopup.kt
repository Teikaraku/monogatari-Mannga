@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.monogatari.android.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/** Platform popup windows otherwise replace a localized, storage-scoped caller context. */
@Composable internal fun CallerContextDropdownMenu(expanded: Boolean, onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit) {
    val caller = LocalContext.current; val configuration = LocalConfiguration.current
    DropdownMenu(expanded, onDismissRequest) {
        val column = this
        CompositionLocalProvider(LocalContext provides caller, LocalConfiguration provides configuration) { content(column) }
    }
}
@Composable internal fun CallerContextBottomSheet(onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val caller = LocalContext.current; val configuration = LocalConfiguration.current
    ModalBottomSheet(onDismissRequest) {
        val column = this
        CompositionLocalProvider(LocalContext provides caller, LocalConfiguration provides configuration) { content(column) }
    }
}
@Composable internal fun CallerContextAlertDialog(onDismissRequest: () -> Unit, confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier, dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null, text: (@Composable () -> Unit)? = null) {
    val caller = LocalContext.current; val configuration = LocalConfiguration.current
    fun retain(slot: @Composable () -> Unit): @Composable () -> Unit = {
        CompositionLocalProvider(LocalContext provides caller, LocalConfiguration provides configuration, content = slot)
    }
    AlertDialog(onDismissRequest, retain(confirmButton), modifier = modifier, dismissButton = dismissButton?.let(::retain),
        title = title?.let(::retain), text = text?.let(::retain))
}
