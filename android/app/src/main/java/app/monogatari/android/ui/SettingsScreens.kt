package app.monogatari.android.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.monogatari.android.MonogatariViewModel
import app.monogatari.android.data.*
import org.json.JSONArray
import org.json.JSONObject

private data class Setting(val route: String, val key: String, val title: String, val icon: ImageVector, val color: Color)
private val primarySettings = listOf(
    Setting("setting", "Appearance", "APPEARANCE", Icons.Outlined.TextFields, Color(0xFF007AFF)),
    Setting("setting", "Library", "LIBRARY", Icons.Outlined.LibraryBooks, Pink),
    Setting("setting", "Reader", "READER", Icons.AutoMirrored.Outlined.MenuBook, Color(0xFF34C759)),
    Setting("dictionary", "", "DICTIONARIES", Icons.Outlined.Translate, Color(0xFF5856D6)),
    Setting("tracking", "", "TRACKING", Icons.Outlined.Sync, Color(0xFFFF9500)),
    Setting("setting", "Advanced", "ADVANCED", Icons.Outlined.Settings, Color(0xFF8E8E93)))
private val otherSettings = listOf(
    Setting("setting", "About", "ABOUT", Icons.Outlined.Info, Color(0xFF8E8E93)),
    Setting("insights", "", "INSIGHTS", Icons.Outlined.BarChart, Color(0xFF5856D6)),
    Setting("backups", "", "BACKUPS", Icons.Outlined.Storage, Pink),
    Setting("downloads", "", "DOWNLOADS", Icons.Outlined.Download, Color(0xFF007AFF)))
@Composable fun SettingsScreen(vm: MonogatariViewModel, state: AppState) {
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val schema = remember { JSONArray(vm.repo.context.assets.open("ios/settings-schema.json").bufferedReader().use { it.readText() }).objects() }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
        PageTitle(tr("SETTINGS"), contentWidth = ReadableWidth); SearchField(query, tr("SEARCH"), modifier = Modifier.readableWidth()) { query = it }
        LazyColumn {
            item { SettingGroup { SettingRow(tr("INCOGNITO_MODE"), subtitle = tr("INCOGNITO_MODE_TEXT"), trailing = {
                Switch(state.preferences.incognito, { checked -> vm.run { vm.repo.settings.put("General.incognitoMode", checked) } })
            }) } }
            listOf(primarySettings, otherSettings).forEach { group -> item { SettingGroup {
                group.filter { Localization.string(vm.repo.context, it.title).contains(query, true) }.forEach { s ->
                    SettingRow(tr(s.title), s.icon, s.color, onClick = { vm.navigate(s.route, s.key) })
                }
            } } }
            if (query.isNotBlank()) items(schema.filter { Localization.string(vm.repo.context, it.optString("titleKey")).contains(query, true) }.distinctBy { it.optString("key") }) { item ->
                SettingGroup { SettingRow(tr(item.optString("titleKey")), subtitle = tr(item.optString("group").uppercase()), onClick = {
                    vm.navigate("originalSettings", item.optString("group") + "|" + item.optJSONArray("parentPages").strings().lastOrNull().orEmpty())
                }) }
            }
        }
    }
}
@Composable fun SettingsDetailScreen(vm: MonogatariViewModel, state: AppState) {
    if (vm.key in setOf("Appearance", "Library", "Reader", "Advanced")) { OriginalSettingsScreen(vm, state, vm.key.lowercase()); return }
    if (vm.key != "About") {
        LaunchedEffect(vm.key) { vm.showTabRoot(4) }
        return
    }
    Column {
        PageTitle(tr("ABOUT"), vm::back, contentWidth = ReadableWidth)
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant).verticalScroll(rememberScrollState())) {
            SettingGroup { SettingRow(tr("VERSION"), value = app.monogatari.android.BuildConfig.VERSION_NAME) }
            SettingGroup {
                SettingRow(tr("GITHUB_REPO"), onClick = { vm.navigate("external", app.monogatari.android.AppBrand.REPOSITORY_URL) })
                SettingRow("GNU GPL v3", onClick = { vm.navigate("licenses") }) }
        }
    }
}
fun platformText(vm: MonogatariViewModel, english: String, chinese: String) = if (vm.repo.context.resources.configuration.locales[0].language == "zh") chinese else english

@Composable fun CategoriesScreen(vm: MonogatariViewModel, state: AppState) {
    var editing by remember { mutableStateOf<String?>(null) }; var name by remember { mutableStateOf("") }; var deleting by remember { mutableStateOf<String?>(null) }
    val categories = state.categories.filter { JSONObject(state.categoryMetadata).optJSONObject(it)?.optBoolean("group") != true }
    Column {
        PageTitle(tr("CATEGORIES"), vm::back, actions = { IconButton({ name = ""; editing = "" }) { Icon(Icons.Outlined.Add, tr("CATEGORY_ADD"), tint = Pink) } })
        if (categories.isEmpty()) EmptyPage(Icons.Outlined.Label, tr("CATEGORIES"), tr("CATEGORY_ADD_TEXT"), tr("CATEGORY_ADD")) { editing = ""; name = "" }
        else LazyColumn { items(categories, key = { it }) { category -> SettingRow(category, value = state.manga.count { category in it.categories && it.inLibrary }.toString(),
            onClick = { editing = category; name = category }, trailing = { Row {
                fun move(delta: Int) = vm.run { vm.repo.change { s -> val order = s.categories.toMutableList(); val index = order.indexOf(category); val next = index + delta; if (index >= 0 && next in order.indices) { order[index] = order[next]; order[next] = category }; s.copy(categories = order) } }
                Box {
                    var categoryMenu by remember { mutableStateOf(false) }
                    IconButton({ categoryMenu = true }) { Icon(Icons.Outlined.MoreHoriz, tr("MORE"), tint = Pink) }
                    NativeMenu(categoryMenu, { categoryMenu = false }, listOf(
                        listOf(MenuAction(tr("RENAME"), Icons.Outlined.Edit) { editing = category; name = category }),
                        listOf(MenuAction(pt("Move up", "上移"), Icons.Outlined.KeyboardArrowUp) { move(-1) }, MenuAction(pt("Move down", "下移"), Icons.Outlined.KeyboardArrowDown) { move(1) }),
                        listOf(MenuAction(tr("DELETE"), Icons.Outlined.DeleteOutline, destructive = true) { deleting = category })))
                }
            } }) } }
    }
    if (editing != null) AlertDialog({ editing = null }, title = { Text(tr(if (editing.isNullOrEmpty()) "CATEGORY_ADD" else "RENAME_CATEGORY")) },
        text = { OutlinedTextField(name, { name = it }, label = { Text(tr("CATEGORY_NAME")) }, singleLine = true) },
        confirmButton = { TextButton({ vm.run {
            val old = editing.orEmpty(); val title = name.trim()
            vm.repo.change { s -> app.monogatari.android.library.CategoryMaintenance.change(s, old, title) }; editing = null
        } }, enabled = name.isNotBlank() && (name.trim() == editing || name.trim() !in state.categories)) { Text(tr("SAVE")) } }, dismissButton = { TextButton({ editing = null }) { Text(tr("CANCEL")) } })
    deleting?.let { category -> AlertDialog({ deleting = null }, title = { Text(tr("DELETE")) }, text = { Text(category) }, confirmButton = { TextButton({ vm.run {
        vm.repo.change { s -> app.monogatari.android.library.CategoryMaintenance.change(s, category, null) }; deleting = null
    } }) { Text(tr("DELETE")) } }, dismissButton = { TextButton({ deleting = null }) { Text(tr("CANCEL")) } }) }
}
