@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.monogatari.android.ui

import app.monogatari.android.AppBrand

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.monogatari.android.MonogatariViewModel
import app.monogatari.android.data.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable fun MonogatariApp(vm: MonogatariViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val isDark = state.preferences.theme == "dark" || (state.preferences.theme == "system" && isSystemInDarkTheme())
    val colors = monogatariColorScheme(isDark)
    MaterialTheme(colorScheme = colors) {
        val navigationProgress = remember { androidx.compose.animation.core.Animatable(1f) }
        var firstFrame by remember { mutableStateOf(true) }
        LaunchedEffect(vm.screen, vm.key) {
            if (firstFrame) firstFrame = false else {
                navigationProgress.snapTo(0f)
                navigationProgress.animateTo(1f, androidx.compose.animation.core.tween(250, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            }
        }
        val view = LocalView.current
        val focusManager = LocalFocusManager.current
        val savedPages = key(vm.uiEpoch) { androidx.compose.runtime.saveable.rememberSaveableStateHolder() }
        SideEffect { (view.context as? android.app.Activity)?.window?.let { window ->
            if (vm.screen != "reader") {
                window.statusBarColor = colors.background.toArgb(); window.navigationBarColor = colors.background.toArgb()
                androidx.core.view.WindowCompat.getInsetsController(window, view).apply { isAppearanceLightStatusBars = !isDark; isAppearanceLightNavigationBars = !isDark }
            }
        } }
        Surface(Modifier.fillMaxSize(), color = colors.background) {
                BackHandler(vm.screen != "main") { vm.back() }
            val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.run {
                vm.repo.import(uri); vm.showTabRoot(0)
            }}
            fun startImport() = importFile.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/vnd.comicbook+zip", "application/x-cbz", "application/epub+zip", "application/octet-stream"))
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).onPreviewKeyEvent { event ->
                if (vm.screen == "main" && event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN && event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ESCAPE) {
                    focusManager.clearFocus(force = true); true
                } else false
            }) {
                if (state.preferences.incognito && vm.screen != "reader") Text(tr("INCOGNITO_MODE"), Modifier.fillMaxWidth().background(Pink).padding(6.dp),
                    color = Color.White, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Box(Modifier.weight(1f).graphicsLayer { translationX = (1f - navigationProgress.value) * size.width * vm.navigationDirection }) {
                    // The backup file key is an input consumed by the page, not a new page identity.
                    savedPages.SaveableStateProvider("${vm.tab}:${vm.screen}:${if (vm.screen == "backups") "" else vm.key}") {
                    when (vm.screen) {
                        "main" -> when (vm.tab) {
                            0 -> LibraryScreen(vm, state, ::startImport)
                            1 -> NativeBookstoreScreen(vm, state)
                            2 -> NativeHistoryScreen(vm, state)
                            3 -> GlobalSearchScreen(vm, state)
                            4 -> SettingsScreen(vm, state)
                        }
                        "details" -> DetailScreen(vm, state)
                        "reader" -> key(vm.chapterKey) { ReaderScreen(vm, state) }
                        "source" -> RuntimeSourceScreen(vm, vm.key)
                        "sourceManager" -> NativeBrowseScreen(vm, state, ::startImport)
                        "copyCatalog" -> NativeCopyCatalogScreen(vm, state)
                        "sourceSettings" -> SourceSettingsScreen(vm, vm.key)
                        "addSources" -> NativeAddSourcesScreen(vm, state, ::startImport)
                        "downloads" -> DownloadManagerScreen(vm, state)
                        "backups" -> CompleteBackupsScreen(vm)
                        "categories" -> CategoriesScreen(vm, state)
                        "libraryFilters" -> LibraryFiltersScreen(vm, state)
                        "filterGroups" -> FilterGroupsScreen(vm, state)
                        "updates" -> UpdatesScreen(vm, state)
                        "migration" -> MigrationScreen(vm, state)
                        "tracking" -> TrackingSettingsScreen(vm)
                        "mangaTracking" -> state.manga.firstOrNull { it.id == vm.key }?.let { MangaTrackingScreen(vm, it) }
                        "dictionary" -> DictionarySettingsScreen(vm)
                        "vocabulary" -> VocabularyScreen(vm)
                        "insights" -> InsightsScreen(vm, state)
                        "setting" -> SettingsDetailScreen(vm, state)
                        "originalSettings" -> OriginalSettingsScreen(vm, state, vm.key.substringBefore('|'), vm.key.substringAfter('|', ""))
                        "advancedAction" -> AdvancedActionScreen(vm, state)
                        "logs" -> LogsScreen(vm)
                        "licenses" -> LicensesScreen(vm)
                        "external" -> ExternalLinkScreen(vm)
                        else -> EmptyPage(Icons.Outlined.Info, "页面未找到", "请返回首页")
                    }
                    }
                    if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter), color = Pink)
                }
                if (vm.screen != "reader") TabBar(vm)
            }
            vm.message?.let { message -> AlertDialog(onDismissRequest = { vm.message = null }, title = { Text(AppBrand.NAME) }, text = { Text(AppBrand.displayText(message)) },
                confirmButton = { TextButton({ vm.message = null }) { Text(tr("OK")) } }) }
        }
    }
}
private val tabTitles = listOf("LIBRARY", "BROWSE", "HISTORY", "SEARCH", "SETTINGS")
private val tabIcons = listOf(Icons.Outlined.LibraryBooks, Icons.Outlined.AutoStories, Icons.Outlined.History, Icons.Outlined.Search, Icons.Outlined.Settings)
@Composable private fun TabBar(vm: MonogatariViewModel) {
    Column {
        HorizontalDivider(thickness = 0.5.dp)
        Row(Modifier.fillMaxWidth().height(61.dp).background(MaterialTheme.colorScheme.background)) {
            tabTitles.forEachIndexed { index, key ->
                val title = if (index == 1) pt("Bookstore", "书库") else tr(key)
                Column(Modifier.weight(1f).fillMaxHeight().clickable {
                    if (vm.screen == "main" && vm.tab == index && index == 2 && vm.repo.settings.bool("Library.continueReadingOnReselect")) {
                        vm.historyReselected?.invoke()
                    }
                    vm.selectTab(index)
                }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    val color = if (vm.tab == index) Pink else MaterialTheme.colorScheme.onSurfaceVariant
                    Icon(tabIcons[index], title, Modifier.size(25.dp), tint = color)
                    Spacer(Modifier.height(3.dp)); Text(title, color = color, fontSize = 10.sp)
                }
            }
        }
    }
}
private val LibrarySelectionSetSaver = androidx.compose.runtime.saveable.listSaver<Set<String>, String>(
    save = { it.toList() }, restore = { it.toSet() })
@Composable private fun LibraryScreen(vm: MonogatariViewModel, state: AppState, import: () -> Unit) {
    val settings = vm.repo.settings
    val selectionView = LocalView.current
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf(settings.libraryCategory()) }
    val savedCategory = settings.libraryCategory()
    val showUncategorized = settings.bool("Library.showUncategorizedCategory")
    LaunchedEffect(savedCategory, state.categories, showUncategorized) {
        if (savedCategory != null && (savedCategory.isEmpty() && !showUncategorized || savedCategory.isNotEmpty() && savedCategory !in state.categories)) {
            category = null; settings.put("Library.currentCategory", null)
        } else category = savedCategory
    }
    var more by remember { mutableStateOf(false) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable(stateSaver = LibrarySelectionSetSaver) { mutableStateOf<Set<String>>(emptySet()) }
    var action by remember { mutableStateOf<String?>(null) }
    var contextManga by remember { mutableStateOf<String?>(null) }
    var chosenCategories by remember { mutableStateOf<Set<String>>(emptySet()) }
    var refreshWifi by remember { mutableStateOf(false) }
    val accessKey = org.json.JSONArray(listOf(category)).toString()
    var selectionScope by rememberSaveable { mutableStateOf(accessKey) }
    val locked = settings.bool("Library.lockLibrary") && (category.isNullOrEmpty() || category in settings.strings("Library.lockedCategories"))
    var unlockedCategoryKey by remember { mutableStateOf<String?>(null) }
    val hasAccess = !locked || unlockedCategoryKey == accessKey
    LaunchedEffect(hasAccess) { if (!hasAccess) { selecting = false; selected = emptySet(); action = null; contextManga = null } }
    LaunchedEffect(action, selected) { if (action == "category") chosenCategories = state.manga.singleOrNull { it.id in selected }?.categories?.toSet().orEmpty() }
    val tracking by app.monogatari.android.tracking.TrackingRepository.get(vm.repo.context).data.collectAsState()
    val manga = app.monogatari.android.library.LibraryRules.select(state, settings, category, query, tracking.bindings.map { it.mangaId }.toSet())
    fun canEditLibrary(): Boolean {
        val currentKey = org.json.JSONArray(listOf(category)).toString()
        val lockedNow = settings.bool("Library.lockLibrary") && (category.isNullOrEmpty() || category in settings.strings("Library.lockedCategories"))
        return !lockedNow || unlockedCategoryKey == currentKey
    }
    fun setSelected(book: Manga, value: Boolean) {
        if (!canEditLibrary() || manga.none { it.id == book.id } || (book.id in selected) == value) return
        selected = if (value) selected + book.id else selected - book.id
        if (value) selectionView.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
    }
    fun beginSelection() {
        if (!canEditLibrary() || contextManga != null || action != null || more) return
        if (!selecting) selected = emptySet()
        selecting = true
    }
    val visibleIds = manga.map { it.id }.toSet()
    // Capture the current selection in this composition scope. A button slot
    // must receive the resulting label rather than a closure over the delegate.
    val allItemsSelected = visibleIds.isNotEmpty() && selected.containsAll(visibleIds)
    val selectionAllLabel = tr(if (allItemsSelected) "DESELECT_ALL" else "SELECT_ALL")
    LaunchedEffect(visibleIds) { selected = selected.intersect(visibleIds) }
    LaunchedEffect(accessKey) {
        if (selectionScope != accessKey) { selected = emptySet(); action = null; contextManga = null }
        selectionScope = accessKey
    }
    BackHandler(selecting && hasAccess) { selecting = false; selected = emptySet() }
    fun refreshLibrary(allowCellular: Boolean = false) = vm.run {
        val selectedCategory = if (category == "") "" else category?.takeIf { it in state.categories && org.json.JSONObject(state.categoryMetadata).optJSONObject(it)?.optBoolean("group") != true }
        val errors = app.monogatari.android.library.LibraryRefresh.manualRefresh(vm.repo, selectedCategory, allowCellular)
        if (errors.isNotEmpty()) vm.message = errors.joinToString("\n")
    }
    Column {
        PageTitle(tr("LIBRARY"), actions = {
            IconButton({ vm.navigate("downloads") }) { Icon(Icons.Outlined.Download, tr("DOWNLOAD_QUEUE"), tint = Pink) }
            IconButton({ vm.navigate("updates") }) { Icon(Icons.Outlined.NotificationsNone, tr("MANGA_UPDATES"), tint = Pink) }
            Box {
                IconButton({ more = true }) { Icon(Icons.Outlined.MoreHoriz, tr("MORE_BARBUTTON"), tint = Pink) }
                // Select, layout, then sort and filter, as in the iOS library menu; Android adds categories and manual updates.
                val sortLabels = listOf("SORT_TITLE", "SORT_LAST_READ", "SORT_LAST_OPENED", "SORT_LAST_UPDATED", "SORT_DATE_ADDED", "SORT_LATEST_CHAPTER", "SORT_UNREAD_CHAPTERS", "SORT_TOTAL_CHAPTERS")
                val sortIndex = settings.int("Library.sortOption"); val ascending = settings.bool("Library.sortAscending")
                NativeMenu(more, { more = false }, listOf(
                    listOf(MenuAction(tr("SELECT"), Icons.Outlined.CheckCircleOutline, enabled = hasAccess) { beginSelection() }),
                    listOf(
                        MenuAction(tr("LAYOUT_GRID"), Icons.Outlined.GridView, checked = !state.preferences.listView) { vm.run { settings.put("Library.listView", false) } },
                        MenuAction(tr("LAYOUT_LIST"), Icons.AutoMirrored.Outlined.ViewList, checked = state.preferences.listView) { vm.run { settings.put("Library.listView", true) } }),
                    listOf(
                        MenuSubmenu(tr("SORT_BY"), Icons.Outlined.SwapVert, tr(sortLabels.getOrElse(sortIndex) { sortLabels.first() }), listOf(
                            sortLabels.mapIndexed { index, label -> MenuAction(tr(label), checked = index == sortIndex) { vm.run { settings.put("Library.sortOption", index) } } },
                            listOf(false, true).map { up -> MenuAction(tr(if (up) "ASCENDING" else "DESCENDING"), checked = ascending == up) { vm.run { settings.put("Library.sortAscending", up) } } })),
                        MenuAction(tr("BUTTON_FILTER"), Icons.Outlined.FilterList) { vm.navigate("libraryFilters") }),
                    listOf(
                        MenuAction(tr("CATEGORIES"), Icons.Outlined.Folder) { vm.navigate("categories") },
                        MenuAction(tr("UPDATE_NOW"), Icons.Outlined.Sync, enabled = hasAccess) {
                            if (app.monogatari.android.library.LibraryRefresh.requiresWifi(vm.repo)) refreshWifi = true else refreshLibrary()
                        })))
            }
        })
        SearchField(query, tr("LIBRARY_SEARCH")) { query = it }
        if (state.categories.isNotEmpty() || settings.bool("Library.showUncategorizedCategory")) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val all = tr("ALL"); val uncategorized = tr("UNCATEGORIZED")
            (listOf(null to all) + state.categories.map { it to it } + if (showUncategorized) listOf("" to uncategorized) else emptyList()).forEach { (id, label) ->
                FilterChip(category == id, { category = id; selectionScope = org.json.JSONArray(listOf(id)).toString(); selected = emptySet(); action = null; contextManga = null; vm.run { settings.put("Library.currentCategory", id) } }, label = { Text(label) })
            }
        }
        Box(Modifier.weight(1f)) {
            LibraryLock("library$accessKey", locked, vm, onAccessChanged = { unlockedCategoryKey = if (it) accessKey else null }, category = category) {
                if (manga.isEmpty()) EmptyPage(Icons.Outlined.LibraryBooks, tr("LIBRARY_EMPTY"), pt("Add comics from the bookstore.", "从书库添加漫画。"), pt("Browse bookstore", "浏览书库")) { vm.showTabRoot(1) }
                else MangaCollection(manga, vm.repo, { m ->
                    if (!canEditLibrary()) return@MangaCollection
                    if (settings.bool("Library.opensReaderView")) {
                        val chapter = if (settings.bool("Library.resumeLastOpenedChapter")) m.chapters.maxByOrNull { it.readAt }.takeIf { (it?.readAt ?: 0) > 0 }
                            else app.monogatari.android.reader.ChapterListing.next(m)
                        if (chapter != null) vm.read(m, chapter) else vm.open(m)
                    } else vm.open(m)
                }, Modifier.fillMaxSize(), onLongClick = { if (canEditLibrary()) contextManga = it.id },
                    selection = MangaCollectionSelection(selecting, selected, hasAccess && contextManga == null && action == null && !more,
                        accessKey, ::beginSelection, ::setSelected))
            }
        }
        if (selecting && hasAccess) {
            Text(tr("SELECTED_%i_OF_%i", selected.size, manga.size), Modifier.padding(horizontal = 16.dp, vertical = 6.dp), fontSize = 13.sp)
            // As on iOS: remove and add-to-category on the toolbar, everything else in one menu instead of a scrolling row of buttons.
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val enabled = selected.isNotEmpty()
                TextButton({ val value = !allItemsSelected; manga.forEach { setSelected(it, value) } }, enabled = manga.isNotEmpty()) { Text(selectionAllLabel) }
                Spacer(Modifier.weight(1f))
                IconButton({ action = "remove" }, enabled = enabled) { Icon(Icons.Outlined.DeleteOutline, tr("REMOVE"), tint = if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)) }
                IconButton({ action = "category" }, enabled = enabled) { Icon(Icons.Outlined.CreateNewFolder, tr("CATEGORIES"), tint = if (enabled) Pink else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)) }
                Box {
                    var selectionMenu by remember { mutableStateOf(false) }
                    IconButton({ selectionMenu = true }, enabled = enabled) { Icon(Icons.Outlined.MoreHoriz, tr("MORE_BARBUTTON"), tint = if (enabled) Pink else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)) }
                    val removable = category in state.categories && org.json.JSONObject(state.categoryMetadata).optJSONObject(category)?.optBoolean("group") != true
                    NativeMenu(selectionMenu, { selectionMenu = false }, listOf(
                        listOf(
                            MenuSubmenu(tr("MARK_ALL"), sections = listOf(listOf(
                                MenuAction(tr("READ"), Icons.Outlined.CheckCircleOutline) { action = "read" },
                                MenuAction(tr("UNREAD"), Icons.Outlined.RemoveCircleOutline) { action = "unread" }))),
                            MenuSubmenu(tr("DOWNLOAD"), Icons.Outlined.Download, sections = listOf(listOf(
                                MenuAction(tr("ALL")) { action = "download" },
                                MenuAction(tr("UNREAD")) { action = "downloadUnread" })))),
                        listOf(MenuAction(tr("MIGRATE"), Icons.Outlined.SwapHoriz) { if (canEditLibrary()) { vm.navigate("migration", selected.joinToString("\n")); selecting = false; selected = emptySet() } }),
                        buildList {
                            add(MenuAction(tr("REMOVE_DOWNLOADS"), Icons.Outlined.FileDownloadOff, destructive = true) { action = "removeDownloads" })
                            if (removable) add(MenuAction(tr("REMOVE_FROM_CATEGORY"), Icons.Outlined.FolderOff, destructive = true) { action = "removeCategory" })
                        }))
                }
                TextButton({ selecting = false; selected = emptySet() }) { Text(tr("DONE"), fontWeight = FontWeight.SemiBold) }
            }
        }
    }
    if (refreshWifi && hasAccess) CallerContextAlertDialog({ refreshWifi = false }, title = { Text(tr("REFRESH_NO_WIFI")) }, text = { Text(tr("REFRESH_NO_WIFI_TEXT")) },
        confirmButton = { TextButton({ refreshWifi = false; refreshLibrary(true) }) { Text(tr("REFRESH_ANYWAYS")) } }, dismissButton = { TextButton({ refreshWifi = false }) { Text(tr("CANCEL")) } })
    contextManga?.takeIf { hasAccess }?.let { id -> state.manga.firstOrNull { it.id == id }?.let { item ->
        OptionSheet(item.title, { contextManga = null }, subtitle = item.author) {
            val close = LocalSheetClose.current
            fun choose(value: String) { if (canEditLibrary()) { selected = setOf(id); action = value; close() } }
            val assignable = state.categories.any { org.json.JSONObject(state.categoryMetadata).optJSONObject(it)?.optBoolean("group") != true }
            val downloadable = item.sourceId != "local" && state.sources.any { it.id == item.sourceId }
            SheetActions(buildList {
                add(SheetActionItem(tr("MANGA_INFO"), Icons.Outlined.Info) { close(); vm.open(item) })
                if (item.url.isNotBlank()) add(SheetActionItem(tr("SHARE"), Icons.Outlined.Share) {
                    vm.repo.context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, item.url), null).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); close()
                })
                if (assignable) add(SheetActionItem(tr("EDIT_CATEGORIES"), Icons.Outlined.Folder) { choose("category") })
                add(SheetActionItem(tr("MIGRATE"), Icons.Outlined.SwapHoriz) { close(); vm.navigate("migration", id) })
            })
            Spacer(Modifier.height(12.dp))
            SheetActions(buildList {
                add(SheetActionItem(tr("MARK_READ"), Icons.Outlined.CheckCircleOutline) { choose("read") })
                add(SheetActionItem(tr("MARK_UNREAD"), Icons.Outlined.RadioButtonUnchecked) { choose("unread") })
                if (downloadable) {
                    add(SheetActionItem(tr("DOWNLOAD") + " · " + tr("ALL"), Icons.Outlined.Download) { choose("download") })
                    add(SheetActionItem(tr("DOWNLOAD") + " · " + tr("UNREAD"), Icons.Outlined.DownloadForOffline) { choose("downloadUnread") })
                }
            })
            Spacer(Modifier.height(12.dp))
            SheetActions(buildList {
                if (item.chapters.any { it.downloaded }) add(SheetActionItem(tr("REMOVE_DOWNLOADS"), Icons.Outlined.FileDownloadOff, destructive = true) { choose("removeDownloads") })
                if (!category.isNullOrEmpty() && category in item.categories) add(SheetActionItem(tr("REMOVE_FROM_CATEGORY"), Icons.Outlined.FolderOff, destructive = true) { choose("removeCategory") })
                add(SheetActionItem(tr("REMOVE_FROM_LIBRARY"), Icons.Outlined.DeleteOutline, destructive = true) { choose("remove") })
            })
        }
    } }
    action?.takeIf { hasAccess }?.let { requested ->
        fun apply() {
            val selectedIds = selected.intersect(visibleIds); val selectedScope = org.json.JSONArray(listOf(category)).toString()
            val selectedCategory = category; val categoriesToApply = chosenCategories.toList()
            vm.run {
            if (!canEditLibrary() || org.json.JSONArray(listOf(category)).toString() != selectedScope || selectedIds.isEmpty()) return@run
            val chosen = vm.repo.state.value.manga.filter { it.id in selectedIds }
            when (requested) {
                "read", "unread" -> chosen.forEach { vm.setChaptersRead(it.id, it.chapters.map { c -> c.key }.toSet(), requested == "read") }
                "download", "downloadUnread" -> chosen.filter { it.sourceId != "local" }.forEach { m -> m.chapters.filter { !it.downloaded && !it.locked && (requested != "downloadUnread" || !it.completed) }.forEach { app.monogatari.android.downloads.DownloadService.get(vm.repo.context).enqueueNow(m.id, it.key, allowCellular = true) } }
                "removeDownloads" -> chosen.filter { it.sourceId != "local" }.forEach { m -> (m.chapters + app.monogatari.android.reader.ChapterListing.orphans(vm.repo.state.value, m)).filter { it.downloaded }.forEach { vm.repo.removeDownload(m.id, it.key) } }
                "category" -> vm.repo.change { s -> s.copy(manga = s.manga.map { if (it.id in selectedIds) it.copy(categories = if (selectedIds.size == 1) categoriesToApply else (it.categories + categoriesToApply).distinct()) else it }) }
                "removeCategory" -> vm.repo.change { s -> s.copy(manga = s.manga.map { if (it.id in selectedIds) it.copy(categories = it.categories.filterNot { name -> name == selectedCategory }) else it }) }
                "remove" -> {
                    vm.repo.change { s -> s.copy(manga = s.manga.map { if (it.id in selectedIds) it.copy(inLibrary = false, categories = emptyList(), pinned = false) else it }) }
                    val tracks = app.monogatari.android.tracking.TrackingRepository.get(vm.repo.context); tracks.data.value.bindings.filter { it.mangaId in selectedIds }.forEach { tracks.unbind(it) }
                }
            }
            action = null; selecting = false; selected = emptySet()
        } }
        val title = tr(when (requested) { "read" -> "MARK_READ"; "unread" -> "MARK_UNREAD"; "download", "downloadUnread" -> "DOWNLOAD"; "removeDownloads" -> "REMOVE_DOWNLOADS"; "category" -> "CATEGORIES"; "removeCategory" -> "REMOVE_FROM_CATEGORY"; else -> "REMOVE_FROM_LIBRARY" })
        if (requested == "category") {
            val names = state.categories.filter { org.json.JSONObject(state.categoryMetadata).optJSONObject(it)?.optBoolean("group") != true }
            OptionSheet(title, { action = null }, subtitle = if (selected.size > 1) pt("${selected.size} selected", "已选 ${selected.size} 部") else "", footer = {
                SheetSecondaryButton(tr("CANCEL"), LocalSheetClose.current)
                SheetPrimaryButton(tr("CONTINUE"), { apply() })
            }) {
                SheetGroup { names.forEachIndexed { index, name ->
                    SheetOption(name, name in chosenCategories, { chosenCategories = if (name in chosenCategories) chosenCategories - name else chosenCategories + name }, multiple = true, divider = index < names.lastIndex)
                } }
            }
        } else CallerContextAlertDialog({ action = null }, title = { Text(title) }, text = { Text(if (requested.startsWith("download") && settings.bool("Library.downloadOnlyOnWifi") && !app.monogatari.android.library.LibraryRefresh.isWifi(vm.repo.context)) tr("DOWNLOAD_ANYWAY_MESSAGE") else "${selected.size}") },
            confirmButton = { TextButton({ apply() }) { Text(tr("CONTINUE")) } }, dismissButton = { TextButton({ action = null }) { Text(tr("CANCEL")) } })
    }
}
