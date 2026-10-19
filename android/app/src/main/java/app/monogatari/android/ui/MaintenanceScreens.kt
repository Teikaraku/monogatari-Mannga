package app.monogatari.android.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.monogatari.android.MonogatariViewModel
import app.monogatari.android.data.AppState
import app.monogatari.android.library.Maintenance
import app.monogatari.android.sources.SourceLogStore
import org.json.JSONArray
import app.monogatari.android.data.objects

@Composable fun AdvancedActionScreen(vm: MonogatariViewModel, state: AppState) {
    val schema = remember { JSONArray(vm.repo.context.assets.open("ios/settings-schema.json").bufferedReader().use { it.readText() }).objects() }
    val title = schema.firstOrNull { it.optString("key") == vm.key }?.optString("titleKey").orEmpty()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) vm.run { val file = SourceLogStore.get(vm.repo.context).export(); vm.repo.context.contentResolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } } ?: error("Cannot open destination"); vm.back() }
    }
    if (vm.key == "Logs.display") { LaunchedEffect(Unit) { vm.screen = "logs" }; return }
    Column {
        PageTitle(tr(title), vm::back)
        if (vm.key != "Logs.export") SettingGroup { Text(tr(title + "_TEXT"), Modifier.padding(18.dp)) }
        if (vm.key == "Advanced.clearNetworkCache") {
            val bytes = remember { vm.repo.context.cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
            Text(tr("CACHE_SIZE_%@", android.text.format.Formatter.formatFileSize(vm.repo.context, bytes)), Modifier.padding(18.dp))
        }
        Button({ if (vm.key == "Logs.export") export.launch("monogatari-log.txt") else { val action = vm.key; vm.run { Maintenance.action(vm.repo, action); if (action == "Advanced.reset") vm.resetNavigation() else vm.back() } } }, Modifier.padding(16.dp), enabled = !vm.busy) { Text(tr(if (vm.key == "Logs.export") "EXPORT" else "CONTINUE")) }
    }
}
@Composable fun LogsScreen(vm: MonogatariViewModel) {
    val logs = remember { SourceLogStore.get(vm.repo.context) }; val entries by logs.entries.collectAsState()
    var query by remember { mutableStateOf("") }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> if (uri != null) vm.run { val file = logs.export(); vm.repo.context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } } }
    Column {
        PageTitle(tr("DISPLAY_LOGS"), vm::back, actions = { IconButton({ export.launch("monogatari-log.txt") }) { Icon(Icons.Outlined.Share, tr("EXPORT_LOGS"), tint = Pink) }; IconButton({ logs.clear() }) { Icon(Icons.Outlined.DeleteOutline, tr("CLEAR"), tint = Pink) } })
        SearchField(query, tr("SEARCH")) { query = it }
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp + centeredInset(DetailWidth), vertical = 16.dp)) { items(entries.filter { it.message.contains(query, true) }.asReversed()) { Text(it.formatted(), fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 3.dp)) } }
    }
}
@Composable fun LicensesScreen(vm: MonogatariViewModel) {
    val text = remember {
        val assets = vm.repo.context.assets
        buildString {
            append(assets.open("LICENSE").bufferedReader().use { it.readText() })
            append("\n\nHoshiDicts\n\n")
            append(assets.open("dictionary/HoshiDicts-LICENSE.txt").bufferedReader().use { it.readText() })
            assets.list("licenses").orEmpty().filter { it.endsWith(".txt") }.sorted().forEach { name ->
                append("\n\n"); append(name.removeSuffix(".txt")); append("\n\n")
                append(assets.open("licenses/$name").bufferedReader().use { it.readText() })
            }
        }
    }
    Column { PageTitle("GNU GPL v3", vm::back); Text(text, Modifier.verticalScroll(rememberScrollState()).padding(16.dp), fontSize = 12.sp) }
}
@Composable fun ExternalLinkScreen(vm: MonogatariViewModel) {
    LaunchedEffect(vm.key) { runCatching { vm.repo.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(vm.key)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { vm.message = it.message }; vm.back() }
}
