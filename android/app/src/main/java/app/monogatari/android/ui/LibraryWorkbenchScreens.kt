@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.monogatari.android.ui

import app.monogatari.android.AppBrand

import android.app.KeyguardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.monogatari.android.MonogatariViewModel
import app.monogatari.android.data.*
import app.monogatari.android.library.*
import org.json.JSONArray
import org.json.JSONObject

private val ruleTitles = listOf("DOWNLOADED", "IS_TRACKING", "FILTER_HAS_UNREAD", "FILTER_STARTED", "STATUS_COMPLETED", "SOURCES", "CONTENT_RATING", "CATEGORY")
/** Each chip cycles a rule through include (check), exclude (cross) and off. */
@Composable fun LibraryFilterEditor(vm: MonogatariViewModel, state: AppState, initial: List<LibraryRule>, onChange: (List<LibraryRule>) -> Unit) {
    @Composable fun RuleChip(title: String, rule: LibraryRule?, onClick: () -> Unit) = SheetChip(title, rule != null && !rule.exclude, onClick, excluded = rule?.exclude == true)
    val categories = state.categories.filter { !JSONObject(state.categoryMetadata).optJSONObject(it).let { meta -> meta?.optBoolean("group") ?: false } }
    Column {
        SheetSection(tr("STATUS")) { SheetChips { for (type in 0..4) RuleChip(tr(ruleTitles[type]), initial.firstOrNull { it.type == type }) { onChange(LibraryRules.toggle(initial, type)) } } }
        if (state.sources.isNotEmpty()) SheetSection(tr("SOURCES")) { SheetChips { state.sources.forEach { source ->
            RuleChip(if (source.type == "local") tr("LOCAL_FILES") else source.name, initial.firstOrNull { it.type == 5 && it.value == source.id }) { onChange(LibraryRules.toggle(initial, 5, source.id)) }
        } } }
        SheetSection(tr("CONTENT_RATING")) { SheetChips { listOf("unknown" to "UNKNOWN", "safe" to "SAFE", "suggestive" to "SUGGESTIVE", "nsfw" to "NSFW").forEach { (value, title) ->
            RuleChip(tr(title), initial.firstOrNull { it.type == 6 && it.value == value }) { onChange(LibraryRules.toggle(initial, 6, value)) }
        } } }
        if (categories.isNotEmpty()) SheetSection(tr("CATEGORIES")) { SheetChips { categories.forEach { name ->
            RuleChip(name, initial.firstOrNull { it.type == 7 && it.value == name }) { onChange(LibraryRules.toggle(initial, 7, name)) }
        } } }
    }
}
@Composable fun LibraryFiltersScreen(vm: MonogatariViewModel, state: AppState) {
    val settings = vm.repo.settings
    val rules = LibraryRules.parse(settings.value("Library.filters"))
    Column { PageTitle(tr("FILTER_BY"), vm::back)
        Column(Modifier.verticalScroll(rememberScrollState())) {
            LibraryFilterEditor(vm, state, rules) { next -> vm.run { settings.put("Library.filters", JSONArray(next.map { it.json() })) } }
            TextButton({ vm.run { settings.put("Library.filters", JSONArray()) } }, Modifier.fillMaxWidth()) { Text(tr("RESET")) }
        }
    }
}
@Composable fun FilterGroupsScreen(vm: MonogatariViewModel, state: AppState) {
    var editing by remember { mutableStateOf<String?>(null) }; var adding by remember { mutableStateOf(false) }
    val groups = JSONObject(state.categoryMetadata)
    Column {
        PageTitle(tr("FILTER_GROUPS"), vm::back, actions = { IconButton({ adding = true; editing = "" }) { Icon(Icons.Outlined.Add, tr("ADD"), tint = Pink) } })
        LazyColumn { items(state.categories.filter { groups.optJSONObject(it)?.optBoolean("group") == true }, key = { it }) { name ->
            SettingRow(name, onClick = { adding = false; editing = name }, trailing = { IconButton({ vm.run {
                vm.repo.change { s -> CategoryMaintenance.change(s, name, null) }
            } }) { Icon(Icons.Outlined.DeleteOutline, tr("DELETE"), tint = Pink) } })
        } }
    }
    if (editing != null) {
        var title by remember(editing) { mutableStateOf(editing.orEmpty()) }
        var rules by remember(editing) { mutableStateOf(LibraryRules.parse(groups.optJSONObject(editing.orEmpty())?.opt("filters"))) }
        OptionSheet(tr("FILTER_GROUPS"), { editing = null }, footer = {
            SheetSecondaryButton(tr("CANCEL"), LocalSheetClose.current)
            SheetPrimaryButton(tr("DONE"), { vm.run {
                val old = editing.orEmpty(); val name = title.trim()
                vm.repo.change { s ->
                    val changed = CategoryMaintenance.change(s, old, name)
                    val data = JSONObject(changed.categoryMetadata)
                    data.put(name, (data.optJSONObject(name) ?: JSONObject()).put("group", true).put("filters", JSONArray(rules.map { it.json() })))
                    changed.copy(categoryMetadata = data.toString())
                }; editing = null
            } }, enabled = title.isNotBlank() && (title.trim() == editing || title.trim() !in state.categories))
        }) {
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), label = { Text(tr("NAME")) }, singleLine = true)
            LibraryFilterEditor(vm, state, rules) { rules = it }
        }
    }
}
@Composable fun UpdatesScreen(vm: MonogatariViewModel, state: AppState) {
    val groups = UpdateRecords.groups(state)
    Column {
        PageTitle(tr("MANGA_UPDATES"), vm::back)
        if (groups.isEmpty()) EmptyPage(Icons.Outlined.NotificationsNone, tr("NO_UPDATES"), "")
        else LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp + centeredInset(DetailWidth), vertical = 8.dp)) { groups.groupBy { it.day }.forEach { (day, entries) ->
            item(key = "date-$day") { Text(relativeDay(vm.repo.context, day), Modifier.padding(vertical = 14.dp), fontSize = 20.sp, fontWeight = FontWeight.Medium) }
            items(entries, key = { org.json.JSONArray(listOf(it.day.toString(), it.manga.sourceId, it.manga.key)).toString() }) { group ->
                val manga = group.manga
                fun remove() = vm.run { vm.repo.change { UpdateRecords.remove(it, group) } }
                val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
                    if (value == SwipeToDismissBoxValue.EndToStart) { remove(); true } else false
                })
                SwipeToDismissBox(dismiss, enableDismissFromStartToEnd = false, backgroundContent = {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.error).padding(20.dp), contentAlignment = Alignment.CenterEnd) { Icon(Icons.Outlined.DeleteOutline, tr("DELETE"), tint = MaterialTheme.colorScheme.onError) }
                }) {
                    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).clickable { vm.open(manga) }.padding(vertical = 12.dp), verticalAlignment = if (group.updates.size == 1) Alignment.CenterVertically else Alignment.Top) {
                        Cover(manga, vm.repo, Modifier.size(56.dp))
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(manga.title, maxLines = 2, color = if (group.updates.first().optBoolean("viewed")) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                            group.updates.take(5).forEach { update -> manga.chapters.firstOrNull { it.key == update.optString("chapterId") }?.let { chapter ->
                                Text(chapter.title, maxLines = 1, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } }
                            if (group.updates.size > 5) Text(tr("%lld_PLUS_MORE", group.updates.size - 5), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(::remove) { Icon(Icons.Outlined.DeleteOutline, tr("DELETE"), tint = Pink) }
                    }
                }
            }
        } }
    }
}
@Composable fun LibraryLock(contentKey: String, locked: Boolean, vm: MonogatariViewModel, onAccessChanged: (Boolean) -> Unit = {}, category: String? = null, content: @Composable () -> Unit) {
    var unlocked by remember(contentKey, locked) { mutableStateOf(!locked) }
    val accessCallback by rememberUpdatedState(onAccessChanged)
    SideEffect { accessCallback(unlocked || !locked) }
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, contentKey, locked) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP && locked) { unlocked = false; accessCallback(false) } }
        owner.lifecycle.addObserver(observer); onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result -> unlocked = result.resultCode == android.app.Activity.RESULT_OK }
    val title = tr(if (contentKey == "history") "VIEW_HISTORY" else "VIEW_LIBRARY")
    if (unlocked || !locked) content() else EmptyPage(Icons.Outlined.Lock, tr(when { contentKey == "history" -> "HISTORY_LOCKED"; category != null -> "CATEGORY_LOCKED"; else -> "LIBRARY_LOCKED" }), tr("AUTH_TO_OPEN"), title) {
        if (DeviceAuthentication.authenticate(context, title) { unlocked = it }) return@EmptyPage
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val intent = keyguard.createConfirmDeviceCredentialIntent(title, AppBrand.NAME)
        if (intent == null) vm.message = platformText(vm, "Set a screen lock in Android settings before using the library lock.", "请先在 Android 系统中设置屏幕锁定，再使用书架锁。") else launcher.launch(intent)
    }
}
