package app.monogatari.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import app.monogatari.android.update.AppRelease
import app.monogatari.android.update.AppUpdateController
import java.util.Locale

/** A required update can't be dismissed; an optional one can wait until the next launch or skip this version. */
@Composable fun AppUpdateDialog(updates: AppUpdateController, release: AppRelease) {
    val context = LocalContext.current
    val forced = updates.forced
    val progress = updates.progress
    AlertDialog(
        onDismissRequest = { updates.later() },
        properties = DialogProperties(dismissOnBackPress = !forced, dismissOnClickOutside = false),
        title = { Text(if (forced) pt("Update required", "需要更新") else pt("New version", "发现新版本")) },
        text = {
            Column {
                Text(listOf(release.version, release.size.takeIf { it > 0 }?.let { String.format(Locale.ROOT, "%.1f MB", it / 1_048_576.0) })
                    .filterNotNull().filter(String::isNotBlank).joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                if (forced) Text(pt("This version needs to be updated before it can be used.", "当前版本需要更新后才能继续使用。"), Modifier.padding(top = 10.dp))
                if (release.notes.isNotBlank()) Text(release.notes, Modifier.padding(top = 12.dp).heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                    fontSize = 14.sp, lineHeight = 21.sp)
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp), color = Pink)
                    Text("${(progress * 100).toInt()}%", Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
                updates.failure?.let { Text(it, Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton({ updates.update(context) }, enabled = progress == null) {
                Text(when {
                    progress != null -> pt("Downloading", "正在下载")
                    updates.ready != null -> pt("Install", "安装")
                    else -> pt("Update", "立即更新")
                })
            }
        },
        dismissButton = if (forced) null else ({
            Row {
                TextButton({ updates.skip() }, enabled = progress == null) { Text(pt("Skip this version", "跳过此版本")) }
                TextButton({ updates.later() }, enabled = progress == null) { Text(pt("Later", "稍后")) }
            }
        })
    )
}
