package app.monogatari.android.ui

import android.app.KeyguardManager
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import app.monogatari.android.MonogatariViewModel
import app.monogatari.android.AppBrand
import app.monogatari.android.data.*
import app.monogatari.android.library.LibraryRefresh
import app.monogatari.android.reader.*
import org.json.JSONArray
import org.json.JSONObject

@Composable fun OriginalSettingsScreen(vm: MonogatariViewModel, state: AppState, group: String, page: String = "") {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val schema = remember { JSONArray(context.assets.open("ios/settings-schema.json").bufferedReader().use { it.readText() }).objects() }
    val grouped = schema.filter { it.optString("group") == group }.distinctBy { it.optString("key") to it.optString("groupId") }
    if (grouped.isEmpty() || page.isNotBlank() && grouped.none { page in it.optJSONArray("parentPages").strings() }) {
        LaunchedEffect(group, page) { vm.showTabRoot(4) }
        return
    }
    val titleKey = when (group) { "appearance" -> "APPEARANCE"; "library" -> "LIBRARY"; "reader" -> "READER"; "advanced" -> "ADVANCED"; else -> "SETTINGS" }
    val store = vm.repo.settings
    val scroll = rememberSaveable(group, page, saver = ScrollState.Saver) { ScrollState(0) }
    var authenticatedAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showModels by remember { mutableStateOf(false) }
    val authentication = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) authenticatedAction?.invoke()
        authenticatedAction = null
    }
    fun authenticate(action: () -> Unit) {
        if (app.monogatari.android.library.DeviceAuthentication.authenticate(context, Localization.string(context, "AUTH_TO_OPEN")) { if (it) action() }) return
        val intent = context.getSystemService(KeyguardManager::class.java).createConfirmDeviceCredentialIntent(Localization.string(context, "AUTH_TO_OPEN"), AppBrand.NAME)
        if (intent != null) { authenticatedAction = action; authentication.launch(intent) }
    }
    val notification = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> vm.run { store.put("Library.notifyNewChapters", granted) } }
    Column {
        PageTitle(tr(if (page.isBlank()) titleKey else page), vm::back, contentWidth = ReadableWidth)
        Column(Modifier.fillMaxSize().background(if (MaterialTheme.colorScheme.background == androidx.compose.ui.graphics.Color.White) GroupGray else androidx.compose.ui.graphics.Color.Black).verticalScroll(scroll)) {
            if (page.isBlank()) grouped.flatMap { it.optJSONArray("parentPages").strings() }.distinct().forEach { child ->
                SettingGroup { SettingRow(tr(child), onClick = { vm.navigate("originalSettings", "$group|$child") }) }
            }
            grouped.filter { val pages = it.optJSONArray("parentPages").strings(); if (page.isBlank()) pages.isEmpty() else page in pages }.groupBy { it.optString("groupId") }.values.forEach { section ->
                val metadata = section.first()
                if (!store.requires(metadata.optString("groupRequires")) || metadata.optJSONArray("groupRequiresFalse").strings().any(store::requires)) return@forEach
                val sectionTitle = metadata.optString("groupTitle").takeIf(String::isNotEmpty)?.let { tr(it) }.orEmpty()
                val sectionFooter = metadata.optString("groupFooter").takeIf(String::isNotEmpty)?.let { tr(it) }.orEmpty()
                SettingGroup(if (metadata.optBoolean("groupTitleExperimental")) tr("%@_EXPERIMENTAL", sectionTitle) else sectionTitle, footer = sectionFooter) {
                    section.forEach { item ->
                        val key = item.getString("key"); val type = item.optString("type")
                        val localizedTitle = tr(item.getString("titleKey"))
                        val baseTitle = if (key == "Reader.textTheme" && localizedTitle == "Theme") pt("Theme", "主题") else localizedTitle
                        val title = if (item.optBoolean("titleExperimental")) tr("%@_EXPERIMENTAL", baseTitle) else baseTitle
                        val enabled = store.requires(item.optString("requires")) && (item.optString("requiresFalse").isBlank() || !store.requires(item.optString("requiresFalse")))
                        fun put(value: Any?) = vm.run {
                            if (!enabled) return@run
                            if (key in setOf("Library.lockLibrary", "History.lockHistoryTab") && value == true && !context.getSystemService(KeyguardManager::class.java).isDeviceSecure) error(platformText(vm, "Set up a screen lock in Android settings first.", "请先在系统设置中启用屏幕锁定"))
                            store.put(key, value)
                            if (key.startsWith("Library.")) LibraryRefresh.schedule(vm.repo)
                        }
                        when (type) {
                            "toggle" -> {
                                fun toggle(checked: Boolean) {
                                    if (key == "Library.notifyNewChapters" && checked && android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notification.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                    else if (!checked && key in setOf("Library.lockLibrary", "History.lockHistoryTab")) authenticate { put(false) }
                                    else put(checked)
                                }
                                SettingRow(title, enabled = enabled, onClick = { toggle(!store.bool(key)) }, trailing = {
                                    Switch(store.bool(key), ::toggle, enabled = enabled)
                                })
                            }
                            "segment", "select", "picker", "multiselect" -> {
                                if (key == "Reader.textFontFamily") {
                                    ReaderFontPicker(store.string(key).let { if (it == "System") "system" else it }, enabled = enabled) { font -> put(if (font == "system") "System" else font) }
                                } else {
                                    val pairs = options(item)
                                    var expanded by remember(key) { mutableStateOf(false) }
                                    val selected = store.value(key)
                                    val value = if (type == "multiselect") store.strings(key).size.toString() else pairs.firstOrNull { it.first.toString() == selected.toString() }?.second?.let { tr(it) } ?: selected?.toString().orEmpty()
                                    SettingRow(title, value = value, enabled = enabled, onClick = { expanded = true })
                                    if (expanded && enabled) {
                                        val multiple = type == "multiselect"
                                        val footer: (@Composable RowScope.() -> Unit)? = if (multiple) { { SheetPrimaryButton(tr("DONE"), LocalSheetClose.current) } } else null
                                        OptionSheet(title, { expanded = false }, footer = footer) {
                                            val close = LocalSheetClose.current
                                            SheetGroup { pairs.forEachIndexed { index, (value, titleKey) ->
                                                SheetOption(tr(titleKey), if (multiple) value.toString() in store.strings(key) else value.toString() == selected.toString(), {
                                                    if (multiple) { val old = store.strings(key); put(JSONArray(if (value.toString() in old) old - value.toString() else old + value.toString())) }
                                                    else { put(value); close() }
                                                }, multiple = multiple, divider = index < pairs.lastIndex)
                                            } }
                                        }
                                    }
                                }
                            }
                            "stepper" -> {
                                val minimum = item.optDouble("minimum", 0.0); val maximum = item.optDouble("maximum", 100.0); val step = item.optDouble("step", 1.0)
                                val current = store.double(key)
                                fun change(delta: Double) { val next = (current + delta).coerceIn(minimum, maximum); put(if (next % 1.0 == 0.0) next.toLong() else next) }
                                val display = if (current % 1.0 == 0.0) current.toLong().toString() else current.toString()
                                SettingRow(title, value = display, enabled = enabled, trailing = {
                                    Row { IconButton({ change(-step) }, enabled = enabled && current > minimum) { Icon(Icons.Outlined.Remove, tr("REMOVE")) }
                                        IconButton({ change(step) }, enabled = enabled && current < maximum) { Icon(Icons.Outlined.Add, tr("ADD")) } }
                                })
                            }
                            "custom" -> when (key) {
                                "Appearance.layout" -> {
                                    SettingRow(title)
                                    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                        listOf("standard" to "STANDARD", "compact" to "COMPACT", "custom" to "CUSTOM").forEach { (value, label) ->
                                            val selected = store.string(key) == value
                                            val pad = if (configuration.smallestScreenWidthDp >= 600) "Pad" else ""
                                            val asset = "file:///android_asset/ios/images/Layout${value.replaceFirstChar { it.uppercase() }}$pad-$value@3x.png"
                                            Column(Modifier.weight(1f).clickable { put(value) }, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                                                coil.compose.AsyncImage(asset, null, Modifier.height(82.dp), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(if (selected) Pink else MaterialTheme.colorScheme.onSurfaceVariant))
                                                Text(tr(label), Modifier.padding(vertical = 8.dp))
                                                Icon(if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null, tint = if (selected) Pink else MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                    if (store.string(key) == "custom") listOf("Appearance.customPortraitRows" to "PORTRAIT", "Appearance.customLandscapeRows" to "LANDSCAPE").forEach { (key, label) ->
                                        SettingRow(tr(label), value = store.int(key).toString(), trailing = { Row {
                                            IconButton({ vm.run { store.put(key, (store.int(key) - 1).coerceAtLeast(1)) } }) { Icon(Icons.Outlined.Remove, null) }
                                            IconButton({ vm.run { store.put(key, (store.int(key) + 1).coerceAtMost(15)) } }) { Icon(Icons.Outlined.Add, null) }
                                        } })
                                    }
                                }
                                "Library.defaultCategory", "Library.lockedCategories", "Library.excludedUpdateCategories" -> {
                                    var open by remember { mutableStateOf(false) }
                                    SettingRow(title, value = if (key == "Library.defaultCategory") when (val category = store.string(key)) { "" -> tr("ALWAYS_ASK"); "none" -> tr("NONE"); else -> category } else store.strings(key).size.toString(), enabled = enabled, onClick = { if (key == "Library.lockedCategories") authenticate { open = true } else open = true })
                                    if (open && enabled) {
                                        val single = key == "Library.defaultCategory"
                                        val names = if (single) listOf("", "none") + state.categories.filter { JSONObject(state.categoryMetadata).optJSONObject(it)?.optBoolean("group") != true }
                                            else if (key == "Library.lockedCategories") state.categories else state.categories.filter { JSONObject(state.categoryMetadata).optJSONObject(it)?.optBoolean("group") != true }
                                        val footer: (@Composable RowScope.() -> Unit)? = if (!single) { { SheetPrimaryButton(tr("DONE"), LocalSheetClose.current) } } else null
                                        OptionSheet(title, { open = false }, footer = footer) {
                                            val close = LocalSheetClose.current
                                            if (names.isNotEmpty()) SheetGroup { names.forEachIndexed { index, name ->
                                                val label = if (name.isBlank()) tr("ALWAYS_ASK") else if (name == "none") tr("NONE") else name
                                                SheetOption(label, if (single) store.string(key) == name else name in store.strings(key), {
                                                    if (single) { put(name); close() }
                                                    else { val old = store.strings(key); put(JSONArray(if (name in old) old - name else old + name)) }
                                                }, multiple = !single, divider = index < names.lastIndex)
                                            } }
                                        }
                                    }
                                }
                            }
                            "page" -> SettingRow(title, enabled = enabled, onClick = {
                                when (key) {
                                    "Library.categories" -> vm.navigate("categories")
                                    "Library.filterGroups" -> vm.navigate("filterGroups")
                                    "Reader.upscalingModels" -> showModels = true
                                    "Tracking" -> vm.navigate("tracking")
                                    "About" -> vm.navigate("setting", "About")
                                    "Insights" -> vm.navigate("insights")
                                    "Backups" -> vm.navigate("backups")
                                    "Downloads" -> vm.navigate("downloads")
                                    else -> vm.navigate("originalSettings", "$group|${item.getString("titleKey")}")
                                }
                            })
                            "button" -> SettingRow(title, enabled = enabled, onClick = { vm.navigate("advancedAction", key) })
                            "text" -> {
                                var text by remember(key, state.settings) { mutableStateOf(store.string(key)) }
                                OutlinedTextField(text, { text = it }, enabled = enabled, label = { Text(title) }, modifier = Modifier.padding(16.dp).fillMaxWidth())
                                TextButton({ put(text) }, Modifier.padding(horizontal = 16.dp), enabled = enabled) { Text(tr("SAVE")) }
                            }
                        }
                    }
                }
            }
            if (group == "reader" && page.isBlank()) {
                val options = ReaderOptions.fromLegacy(store)
                SettingGroup(pt("Additional reader options", "其他阅读选项")) {
                    ReaderExtensionsSettingsContent(options) { next -> vm.run {
                        val extras = JSONObject(store.string("Android.Reader.options").ifBlank { "{}" })
                        val values = next.extrasJSON(); values.keys().forEach { extras.put(it, values.get(it)) }
                        store.put("Android.Reader.options", extras.toString())
                        ReaderOptionStore(context).save(next)
                    } }
                }
            }
        }
    }
    if (showModels) UpscaleModelsDialog { showModels = false }
}
private fun options(item: JSONObject): List<Pair<Any, String>> {
    val key = item.getString("key")
    if (key == "General.appearance") return listOf(0 to "APPEARANCE_LIGHT", 1 to "APPEARANCE_DARK")
    val values = item.optJSONArray("values") ?: JSONArray(); val titles = item.optJSONArray("optionTitles").strings()
    return (0 until values.length()).map { values.get(it) to titles.getOrElse(it) { values.get(it).toString() } }
}
