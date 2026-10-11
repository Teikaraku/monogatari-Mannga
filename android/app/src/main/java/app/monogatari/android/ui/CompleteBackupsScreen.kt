@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.monogatari.android.ui

import app.monogatari.android.AppBrand

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.monogatari.android.MonogatariViewModel
import app.monogatari.android.backup.*
import app.monogatari.android.data.BackupCodec
import app.monogatari.android.data.SettingsStore
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun CompleteBackupsScreen(vm: MonogatariViewModel, scheduleAutomatic: Boolean = true) {
    val manager = remember(vm.repo) { CompleteBackupManager(vm.repo) }; val backups by manager.backups.collectAsStateWithLifecycle(); val invalid by manager.invalid.collectAsStateWithLifecycle()
    val app by vm.state.collectAsStateWithLifecycle()
    val settings = remember(vm.repo, app.settings) { SettingsStore(vm.repo) }
    var create by remember { mutableStateOf(false) }; var automatic by remember { mutableStateOf(false) }; var preview by remember { mutableStateOf<BackupSummary?>(null) }
    var rename by remember { mutableStateOf<BackupSummary?>(null) }; var remove by remember { mutableStateOf<File?>(null) }; var exporting by remember { mutableStateOf<File?>(null) }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.run { preview = manager.import(uri); vm.message = Localization.string(vm.repo.context, "BACKUP_IMPORT_SUCCESS") } }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> val file = exporting; if (uri != null && file != null) vm.run { manager.export(file, uri) }; exporting = null }
    LaunchedEffect(vm.key) {
        manager.refresh(); if (scheduleAutomatic) CompleteBackupManager.schedule(vm.repo)
        if (vm.key.isNotBlank()) { val target = File(vm.key); if (target.isFile && target.canonicalFile.parentFile == manager.directory.canonicalFile) { preview = withContext(Dispatchers.IO) { BackupCodec.preview(target) }; vm.key = "" } }
    }
    Column(Modifier.fillMaxSize()) {
        PageTitle(tr("BACKUPS"), vm::back, actions = { IconButton({ create = true }) { Icon(Icons.Outlined.Add, tr("CREATE_BACKUP"), tint = Pink) } })
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp + centeredInset(DetailWidth), vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ create = true }, Modifier.weight(1f)) { Text(tr("CREATE_BACKUP")) }
                OutlinedButton({ import.launch(arrayOf("application/zip", "application/json", "application/octet-stream", "application/x-plist", "*/*")) }, Modifier.weight(1f)) { Text(tr("IMPORT_BACKUP")) }
            } }
            item { Card(Modifier.fillMaxWidth().clickable { automatic = true }) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(tr("AUTOMATIC_BACKUPS"), fontWeight = FontWeight.Medium); Text(intervalTitle(settings.string("AutomaticBackups.interval")), style = MaterialTheme.typography.bodySmall) }
                Switch(settings.bool("AutomaticBackups.enabled"), { vm.run { settings.put("AutomaticBackups.enabled", it); if (scheduleAutomatic) CompleteBackupManager.schedule(vm.repo) } })
            } } }
            if (backups.isEmpty() && invalid.isEmpty()) item { Text(tr("BACKUPS") + " (0)", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(backups, key = { it.file.name }) { backup -> Card { Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(Modifier.fillMaxWidth().clickable { preview = backup }) { Column(Modifier.weight(1f)) { Text(backup.name, fontWeight = FontWeight.Bold); Text("${DateFormat.getDateTimeInstance().format(Date(backup.date))} · ${size(backup.size)}", style = MaterialTheme.typography.bodySmall); Text(formatTitle(backup.format) + if (backup.automatic) " · ${tr("AUTOMATIC_BACKUPS")}" else "", style = MaterialTheme.typography.bodySmall) }; Icon(Icons.Outlined.ChevronRight, null) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ preview = backup }) { Text(tr("RESTORE")) }
                    Spacer(Modifier.weight(1f))
                    Box {
                        var backupMenu by remember { mutableStateOf(false) }
                        IconButton({ backupMenu = true }) { Icon(Icons.Outlined.MoreHoriz, tr("MORE"), tint = Pink) }
                        NativeMenu(backupMenu, { backupMenu = false }, listOf(
                            listOf(MenuAction(tr("RENAME"), Icons.Outlined.Edit) { rename = backup }),
                            listOf(
                                MenuAction(tr("EXPORT"), Icons.Outlined.SaveAlt) { exporting = backup.file; export.launch("${safeName(backup.name)}.${backup.format.extension}") },
                                MenuAction(tr("SHARE"), Icons.Outlined.IosShare) { vm.run { shareBackup(vm, backup) } }),
                            listOf(MenuAction(tr("DELETE"), Icons.Outlined.DeleteOutline, destructive = true) { remove = backup.file })))
                    }
                }
            } } }
            items(invalid.entries.toList(), key = { it.key.name }) { entry -> Card { Column(Modifier.padding(16.dp)) { Text(tr("CORRUPTED_BACKUP"), color = MaterialTheme.colorScheme.error); Text(entry.key.name); Text(entry.value); TextButton({ remove = entry.key }) { Text(tr("DELETE")) } } } }
        }
    }
    if (create) BackupCreateDialog(onDismiss = { create = false }) { name, options, format -> create = false; vm.run { preview = manager.create(name, options, format) } }
    if (automatic) AutomaticBackupDialog(vm, manager, scheduleAutomatic) { automatic = false }
    preview?.let { backup -> BackupRestoreDialog(backup, onDismiss = { preview = null }) { options -> preview = null; vm.run {
        val result = BackupCodec.restore(vm.repo, Uri.fromFile(backup.file), options); manager.refresh(); if (scheduleAutomatic) CompleteBackupManager.schedule(vm.repo)
        vm.message = Localization.string(vm.repo.context, "BACKUP_IMPORT_SUCCESS") + if (result.warnings.isEmpty()) "" else "\n" + result.warnings.joinToString("\n")
    } } }
    rename?.let { backup -> var name by remember(backup.file) { mutableStateOf(backup.name) }; AlertDialog(onDismissRequest = { rename = null }, title = { Text(tr("RENAME_BACKUP")) }, text = { OutlinedTextField(name, { name = it }, label = { Text(tr("BACKUP_NAME")) }) }, confirmButton = { TextButton({ rename = null; vm.run { manager.rename(backup.file, name) } }, enabled = name.isNotBlank()) { Text(tr("DONE")) } }, dismissButton = { TextButton({ rename = null }) { Text(tr("CANCEL")) } }) }
    remove?.let { file -> AlertDialog(onDismissRequest = { remove = null }, title = { Text(tr("DELETE")) }, text = { Text(file.name) }, confirmButton = { TextButton({ remove = null; vm.run { manager.delete(file) } }) { Text(tr("DELETE")) } }, dismissButton = { TextButton({ remove = null }) { Text(tr("CANCEL")) } }) }
}

@Composable private fun BackupCreateDialog(onDismiss: () -> Unit, create: (String, BackupOptions, BackupFormat) -> Unit) {
    var name by remember { mutableStateOf("") }; var selected by remember { mutableStateOf(BackupOptions().selected) }; var sensitive by remember { mutableStateOf(false) }; var format by remember { mutableStateOf(BackupFormat.ANDROID_ZIP) }
    val sections = BackupSection.entries.filter { format == BackupFormat.ANDROID_ZIP || it !in setOf(BackupSection.DICTIONARIES, BackupSection.MEDIA) }
    BackupDialog(tr("CREATE_BACKUP"), onDismiss, tr("CREATE_BACKUP"), selected.isNotEmpty(), { create(name, BackupOptions(selected, sensitive), format) }) {
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), label = { Text(tr("BACKUP_NAME")) }, singleLine = true)
        SheetSection(pt("Format", "格式")) { SheetChips { BackupFormat.entries.forEach { item -> SheetChip(formatTitle(item), format == item, { format = item }, checkmark = false) } } }
        SheetSection(pt("Contents", "内容")) { SheetGroup { sections.forEachIndexed { index, section ->
            SelectionRow(section, section in selected, divider = index < sections.lastIndex) { selected = if (it) selected + section else selected - section }
        } } }
        Spacer(Modifier.height(12.dp)); SheetGroup { ToggleRow(tr("SENSITIVE_SETTINGS"), sensitive, divider = false) { sensitive = it } }
    }
}
@Composable private fun BackupRestoreDialog(backup: BackupSummary, onDismiss: () -> Unit, restore: (RestoreOptions) -> Unit) {
    // Sources come with the app, so a backup never installs sources or adds source lists.
    val sections = remember(backup.file) { BackupSection.entries.filter { it !in setOf(BackupSection.SOURCES, BackupSection.SOURCE_LISTS) &&
        (backup.format == BackupFormat.ANDROID_ZIP || it !in setOf(BackupSection.MEDIA, BackupSection.DICTIONARIES)) } }
    var selected by remember(backup.file) { mutableStateOf(sections.toSet()) }; var merge by remember { mutableStateOf(false) }; var sensitive by remember { mutableStateOf(false) }; var confirmation by remember { mutableStateOf(false) }; var installSources by remember { mutableStateOf(false) }
    BackupDialog(tr("BACKUP_INFO_TITLE"), onDismiss, tr("RESTORE"), selected.isNotEmpty(), { confirmation = true },
        subtitle = "${backup.name} · ${DateFormat.getDateTimeInstance().format(Date(backup.date))}\n${backup.version} · ${size(backup.size)} · ${formatTitle(backup.format)}") {
        SheetSection(pt("Contents", "内容")) { SheetGroup { sections.forEachIndexed { index, section ->
            SelectionRow(section, section in selected, backup.counts[section], divider = index < sections.lastIndex) { selected = if (it) selected + section else selected - section }
        } } }
        Spacer(Modifier.height(12.dp))
        SheetGroup {
            ToggleRow(pt("Merge existing data", "合并已有数据"), merge) { merge = it }
            ToggleRow(tr("SENSITIVE_SETTINGS"), sensitive, divider = BackupSection.SOURCES in selected) { sensitive = it }
            if (BackupSection.SOURCES in selected) ToggleRow(tr("INSTALL_SOURCES"), installSources, divider = false) { installSources = it }
        }
    }
    if (confirmation) AlertDialog(onDismissRequest = { confirmation = false }, title = { Text(tr("RESTORE_BACKUP")) }, text = { Text(if (merge) pt("Merge the backup with current data. Backup values take precedence for matching entries.", "将备份与当前数据合并；同一条目的备份值优先。") else tr("RESTORE_BACKUP_TEXT") + "\n" + pt("Only selected data is replaced.", "仅替换所选数据项。")) }, confirmButton = { TextButton({ confirmation = false; restore(RestoreOptions(selected, merge, sensitive, installSources)) }) { Text(tr("RESTORE")) } }, dismissButton = { TextButton({ confirmation = false }) { Text(tr("CANCEL")) } })
}
@Composable private fun AutomaticBackupDialog(vm: MonogatariViewModel, manager: CompleteBackupManager, scheduleAutomatic: Boolean, dismiss: () -> Unit) {
    val app by vm.state.collectAsStateWithLifecycle(); val settings = remember(vm.repo, app.settings) { SettingsStore(vm.repo) }
    fun interval(key: String) = vm.run { settings.put("AutomaticBackups.interval", key); if (scheduleAutomatic) CompleteBackupManager.schedule(vm.repo) }
    BackupDialog(tr("AUTOMATIC_BACKUPS"), dismiss, tr("DONE"), onConfirm = dismiss, cancel = false) {
        SheetGroup { ToggleRow(tr("AUTOMATIC_BACKUPS"), settings.bool("AutomaticBackups.enabled"), divider = false) { vm.run { settings.put("AutomaticBackups.enabled", it); if (scheduleAutomatic) CompleteBackupManager.schedule(vm.repo) } } }
        if (settings.bool("AutomaticBackups.enabled")) {
            SheetSection(tr("BACKUP_INTERVAL")) { SheetChips { CompleteBackupManager.intervals.keys.forEach { key -> SheetChip(intervalTitle(key), settings.string("AutomaticBackups.interval") == key, { interval(key) }, checkmark = false) } } }
            SheetSection(pt("Contents", "内容")) { SheetGroup {
                BackupSection.entries.forEach { section -> val key = CompleteBackupManager.autoKey(section) ?: "Android.Backups.${section.key}"; SelectionRow(section, settings.value(key) != false) { vm.run { settings.put(key, it) } } }
                ToggleRow(tr("SENSITIVE_SETTINGS"), settings.bool("AutomaticBackups.sensitiveSettings"), divider = false) { vm.run { settings.put("AutomaticBackups.sensitiveSettings", it) } }
            } }
            if (settings.double("AutomaticBackups.lastBackup") > 0) Text(tr("LAST_BACKED_UP_%@", DateFormat.getDateTimeInstance().format(Date((settings.double("AutomaticBackups.lastBackup") * 1000).toLong()))),
                Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton({ vm.run { manager.automaticIfDue(force = true) } }, Modifier.padding(horizontal = 8.dp)) { Text(tr("CREATE_BACKUP")) }
        }
    }
}
@Composable private fun BackupDialog(title: String, dismiss: () -> Unit, confirmLabel: String, confirmEnabled: Boolean = true, onConfirm: () -> Unit, subtitle: String = "",
    cancel: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    OptionSheet(title, dismiss, subtitle = subtitle, footer = {
        if (cancel) SheetSecondaryButton(tr("CANCEL"), LocalSheetClose.current)
        SheetPrimaryButton(confirmLabel, onConfirm, confirmEnabled)
    }, content = content)
}
@Composable private fun SelectionRow(section: BackupSection, checked: Boolean, count: Int? = null, divider: Boolean = true, changed: (Boolean) -> Unit) =
    ToggleRow(tr(section.labelKey), checked, count?.toString().orEmpty(), divider, changed)
@Composable private fun ToggleRow(title: String, checked: Boolean, subtitle: String = "", divider: Boolean = true, changed: (Boolean) -> Unit) =
    SheetOption(title, checked, { changed(!checked) }, subtitle = subtitle, multiple = true, divider = divider)
@Composable private fun intervalTitle(value: String): String = tr(when (value) { "6hours" -> "EVERY_6_HOURS"; "12hours" -> "EVERY_12_HOURS"; "2days" -> "EVERY_2_DAYS"; "weekly" -> "WEEKLY"; else -> "DAILY" })
private fun formatTitle(format: BackupFormat) = when (format) { BackupFormat.ANDROID_ZIP -> "Android ZIP"; BackupFormat.IOS_BINARY -> "iOS .aib (binary plist)"; BackupFormat.IOS_JSON -> "iOS JSON" }
private fun safeName(name: String) = AppBrand.displayText(name).replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80).ifBlank { AppBrand.NAME }
private fun size(bytes: Long): String = if (bytes < 1024 * 1024) "${bytes / 1024} KB" else if (bytes < 1024L * 1024 * 1024) "${bytes / (1024 * 1024)} MB" else "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
private fun shareBackup(vm: MonogatariViewModel, backup: BackupSummary) {
    val context = vm.repo.context; val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", backup.file)
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), backup.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
