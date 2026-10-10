package app.monogatari.android.ui

import app.monogatari.android.AppBrand

import android.annotation.SuppressLint
import android.content.Intent
import android.app.KeyguardManager
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewModelScope
import app.monogatari.android.MonogatariViewModel
import app.monogatari.android.data.CatalogSource
import app.monogatari.android.data.Manga
import app.monogatari.android.data.SourceConfig
import app.monogatari.android.library.migrateSourceKeys
import app.monogatari.android.sources.*
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrl

@Composable fun SourceImportButton(vm: MonogatariViewModel) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.run { val source = withContext(Dispatchers.IO) { vm.repo.client.runtimes.install(uri, retainPrevious = true) }; saveInstalled(vm, source) }
    }
    TextButton({ launcher.launch(arrayOf("application/zip", "application/octet-stream", "application/x-aidoku-source", "*/*")) }) { Text(tr("IMPORT_SOURCE")) }
}

internal suspend fun saveInstalled(vm: MonogatariViewModel, source: InstalledSource) {
    try {
    val boundary = source.manifest.optJSONObject("config")?.optInt("breakingChangeVersion", 0) ?: 0
    if (source.previousVersion != null && source.previousVersion < boundary && source.version >= boundary) {
        val mappings = withContext(Dispatchers.IO) {
            val runtime = vm.repo.client.runtimes.runtime(source.id)
            if ("handle_key_migration" in runtime.exports) {
                val known=linkedMapOf<String,MutableSet<String>>()
                fun remember(manga:String,chapter:String="") { if(manga.isBlank())return; val chapters=known.getOrPut(manga){linkedSetOf()};if(chapter.isNotBlank())chapters+=chapter }
                vm.state.value.manga.filter { it.sourceId==source.id }.forEach { manga -> remember(manga.key);manga.chapters.forEach { remember(manga.key,it.key) } }
                listOf(JSONObject(vm.state.value.records),JSONObject(vm.state.value.backupExtras)).forEach { data ->
                    listOf("history","chapters","readingSessions","updates","vocabulary").forEach { name -> data.optJSONArray(name)?.values()?.filterIsInstance<JSONObject>()?.filter { it.optString("sourceId")==source.id }?.forEach { row ->
                        remember(row.optString("mangaId"),row.optString(if(name=="chapters")"id" else "chapterId",row.optString("chapterKey")))
                    } }
                }
                app.monogatari.android.downloads.DownloadService.get(vm.repo.context).entries.value.filter { it.sourceId==source.id }.forEach { remember(it.mangaId.removePrefix("${source.id}::"),it.chapterKey) }
                app.monogatari.android.tracking.TrackingRepository.get(vm.repo.context).data.value.bindings.filter { it.mangaId.startsWith("${source.id}::") }.forEach { binding ->
                    val key=binding.mangaId.removePrefix("${source.id}::");remember(key)
                    binding.queuedPages().forEach { remember(key,it.key) }
                }
                app.monogatari.android.dictionary.DictionaryRepository.get(vm.repo.context).also { it.load() }.exportBackup().optJSONArray("vocabulary")?.values()?.filterIsInstance<JSONObject>()?.filter { it.optString("mangaId").startsWith("${source.id}::") }?.forEach { remember(it.getString("mangaId").removePrefix("${source.id}::"),it.optString("chapterKey")) }
                known.mapValues { (manga,chapters) -> runtime.migrateKey(manga) to chapters.associateWith { runtime.migrateKey(manga,it) } }
            } else emptyMap()
        }
        if (mappings.isNotEmpty()) {
            vm.repo.migrateSourceKeys(source.id, mappings.mapValues { it.value.first }, mappings.mapValues { it.value.second },updatedSource=source.config)
        }
    }
    } catch (e: Exception) { withContext(Dispatchers.IO) { vm.repo.client.runtimes.rollbackInstall(source) }; throw e }
    if(vm.state.value.sources.none { it==source.config }) try {
        vm.repo.change { state -> state.copy(sources = if (state.sources.any { it.id == source.id }) state.sources.map { if (it.id == source.id) source.config else it } else state.sources + source.config) }
    } catch(error:Exception) { withContext(Dispatchers.IO) { vm.repo.client.runtimes.rollbackInstall(source) };throw error }
    withContext(Dispatchers.IO) { vm.repo.client.runtimes.commitInstall(source) }
}

@Composable fun SourcePackageRow(vm: MonogatariViewModel, entry: CatalogSource) {
    val state by vm.state.collectAsState()
    val installed = state.sources.firstOrNull { it.id == entry.id }
    val disabled = entry.id in vm.repo.settings.strings("Browse.disabledSources")
    val version = remember(state.sources, entry.id, vm.busy) { runCatching { vm.repo.client.runtimes.installed(entry.id).version }.getOrDefault(-1) }
    val canUpdate = installed != null && version < entry.version
    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(entry.icon, null, Modifier.size(40.dp)); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) { Text(entry.name, fontWeight = FontWeight.Medium); Text(entry.languages.joinToString(" · ") { if (it == "multi") Localization.string(vm.repo.context, "MULTI_LANGUAGE") else java.util.Locale.forLanguageTag(it).getDisplayName(java.util.Locale.getDefault()) } + " · v${entry.version}", style = MaterialTheme.typography.bodySmall); if (entry.contentRating != 0) Text(tr(if (entry.contentRating == 1) "CONTAINS_NSFW" else "PRIMARILY_NSFW"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
        TextButton(enabled = !vm.busy && (disabled || (installed == null || canUpdate) && entry.downloadUrl.isNotEmpty()), onClick = {
            if (disabled) vm.run { vm.repo.settings.put("Browse.disabledSources", JSONArray(vm.repo.settings.strings("Browse.disabledSources") - entry.id)) }
            else vm.run { val source = withContext(Dispatchers.IO) { vm.repo.client.runtimes.install(entry.downloadUrl, entry.id, entry.version, retainPrevious = true) }; saveInstalled(vm, source) }
        }) { Text(tr(if (disabled) "ENABLE" else if (canUpdate) "BUTTON_UPDATE" else if (installed != null) "INSTALLED" else "BUTTON_GET")) }
    }
    HorizontalDivider(Modifier.padding(start = 66.dp))
}

private class SourceBrowseMemory {
    val runtime = mutableStateOf<SourceSession?>(null)
    val home = mutableStateOf<JSONObject?>(null)
    val definitions = mutableStateOf<List<JSONObject>>(emptyList())
    val listings = mutableStateOf<List<JSONObject>>(emptyList())
    val values = mutableStateOf<List<JSONObject>>(emptyList())
    val query = mutableStateOf("")
    val results = mutableStateOf<List<Manga>>(emptyList())
    val more = mutableStateOf(false)
    val page = mutableIntStateOf(1)
    val busy = mutableStateOf(false)
    val searchMode = mutableStateOf(false)
    val selectedListing = mutableStateOf<JSONObject?>(null)
    val selectedHome = mutableStateOf<JSONObject?>(null)
    val error = mutableStateOf<String?>(null)
    val browserUrl = mutableStateOf<String?>(null)
    val work = SourceBrowseWork()
    var submittedRequest: SourceBrowseRequest? = null
    var lastRequested: SourceBrowseRequest? = null
    var configurationLoaded = false
    var initialized = false
}

private class SourceBrowseMemoryHolder {
    private var source: SourceConfig? = null
    private var memory = SourceBrowseMemory()
    fun get(configuration: SourceConfig): SourceBrowseMemory {
        if (source != configuration) { memory.work.cancel(); source = configuration; memory = SourceBrowseMemory() }
        return memory
    }
}

@Composable fun RuntimeSourceScreen(vm: MonogatariViewModel, sourceId: String) {
    val state by vm.state.collectAsState()
    val source = state.sources.firstOrNull { it.id == sourceId } ?: return
    if (source.type == CopySource.TYPE) { NativeCopyCatalogScreen(vm, state, sourceOverride = source); return }
    val memory = vm.pageMemory("sourceBrowse:$sourceId") { SourceBrowseMemoryHolder() }.get(source)
    var runtime by memory.runtime
    var home by memory.home
    var definitions by memory.definitions
    var listings by memory.listings
    var values by memory.values
    var query by memory.query
    var results by memory.results
    var more by memory.more
    var page by memory.page
    var busy by memory.busy
    var searchMode by memory.searchMode
    var selectedListing by memory.selectedListing
    var selectedHome by memory.selectedHome
    var showFilters by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var browserUrl by memory.browserUrl
    var error by memory.error
    fun execute(replace: Boolean = false, block: suspend (Long) -> Unit) {
        memory.work.launch(vm.viewModelScope, replace, { busy = it }, { failure ->
            error = failure?.message
            (failure as? SourceRuntimeException)?.challengeUrl?.let { browserUrl = it }
        }) { generation ->
            val configuration = CoroutineScope(currentCoroutineContext()).launch {
                vm.state.collect { updated ->
                    if (updated.sources.firstOrNull { it.id == sourceId } != source) { memory.work.cancel(); busy = false }
                }
            }
            try { block(generation) } finally { configuration.cancel() }
        }
    }
    suspend fun session(generation: Long): SourceSession? {
        if (!memory.configurationLoaded || runtime == null) {
            val loaded = withContext(Dispatchers.IO) { val r = vm.repo.client.session(source); Triple(r, r.filters(), r.listings()) }
            if (!memory.work.accepts(generation)) return null
            runtime = loaded.first; definitions = loaded.second; listings = loaded.third
            if (values.isEmpty()) values = defaultFilterValues(definitions, loaded.first.legacy)
            memory.configurationLoaded = true
        }
        return runtime
    }
    fun loadSearch(append: Boolean = false, listing: JSONObject? = selectedListing, requestOverride: SourceBrowseRequest? = null) {
        val captured = if (append) memory.submittedRequest ?: return else requestOverride ?: SourceBrowseRequest.capture(query, values, listing)
        val useDefaults = !memory.configurationLoaded && values.isEmpty() && captured.filters().isEmpty()
        val nextPage = if (append) page + 1 else 1
        execute(replace = !append) { generation ->
            memory.lastRequested = captured
            val r = session(generation) ?: return@execute
            val request = if (useDefaults) captured.withFilters(defaultFilterValues(definitions, r.legacy)) else captured
            memory.lastRequested = request
            val submittedListing = request.listing()
            if (!append && submittedListing != null) { val layout = withContext(Dispatchers.IO) { r.listingHome(submittedListing) }; if (!memory.work.accepts(generation)) return@execute; if (layout != null) { selectedListing = submittedListing; selectedHome = layout; searchMode = false; memory.submittedRequest = request; page = 1; more = false; memory.initialized = true; return@execute } }
            val result = withContext(Dispatchers.IO) { request.fetch(r, nextPage) }
            if (!memory.work.accepts(generation)) return@execute
            selectedListing = submittedListing; selectedHome = null; memory.submittedRequest = request
            results = ((if (append) results else emptyList()) + result.first).distinctBy { it.id }; more = result.second; page = nextPage; searchMode = true
            memory.initialized = true
        }
    }
    fun initialize(pendingQuery: String? = null, pendingListing: JSONObject? = null) {
        val restored = memory.initialized && runtime != null
        val pending = pendingListing != null || pendingQuery != null
        if (restored && !pending || !pending && memory.work.task?.isActive == true || !pending && error != null && memory.lastRequested != null) return
        val listingSnapshot = pendingListing?.let { JSONObject(it.toString()) }
        val initialQuery = query
        if (pending) { query = pendingQuery.orEmpty(); selectedListing = listingSnapshot; memory.lastRequested = SourceBrowseRequest.capture(pendingQuery.orEmpty(), values, listingSnapshot) }
        execute(replace = pending) { generation ->
            val r = session(generation) ?: return@execute
            if (pending) {
                val request = SourceBrowseRequest.capture(pendingQuery.orEmpty(), values, listingSnapshot); memory.lastRequested = request
                val layout = listingSnapshot?.let { withContext(Dispatchers.IO) { r.listingHome(it) } }
                if (!memory.work.accepts(generation)) return@execute
                if (layout == null) { val result = withContext(Dispatchers.IO) { request.fetch(r, 1) }; if (!memory.work.accepts(generation)) return@execute; results = result.first; more = result.second; searchMode = true } else { selectedHome = layout; searchMode = false; more = false }
                memory.submittedRequest = request; selectedListing = listingSnapshot; selectedHome = layout; page = 1
            } else if ("get_home" in r.exports) {
                val layout = withContext(Dispatchers.IO) { r.home() }; if (!memory.work.accepts(generation)) return@execute; home = layout
            } else {
                val request = SourceBrowseRequest.capture(initialQuery, values, null); memory.lastRequested = request
                val result = withContext(Dispatchers.IO) { request.fetch(r, 1) }; if (!memory.work.accepts(generation)) return@execute
                memory.submittedRequest = request; results = result.first; more = result.second; page = 1; searchMode = true
            }
            memory.initialized = true
        }
    }
    LaunchedEffect(memory, vm.pendingSourceQuery, vm.pendingSourceListing) {
        if (vm.screen != "source" || vm.key != sourceId) return@LaunchedEffect
        val pendingListing = vm.pendingSourceListing.also { vm.pendingSourceListing = null }
        val pendingQuery = vm.pendingSourceQuery.also { vm.pendingSourceQuery = null }
        if (pendingListing != null || pendingQuery != null) showSettings = false
        initialize(pendingQuery, pendingListing)
    }
    if (showSettings) { SourceSettingsScreen(vm, sourceId) {
        showSettings = false
        memory.configurationLoaded = false
        if (searchMode || memory.lastRequested?.let { it != memory.submittedRequest } == true) {
            val previous = memory.lastRequested ?: memory.submittedRequest
            loadSearch(requestOverride = SourceBrowseRequest.capture(previous?.query ?: query, values, previous?.listing() ?: selectedListing))
        } else execute(replace = true) { generation ->
            val r = session(generation) ?: return@execute
            val options = withContext(Dispatchers.IO) { r.filters() to r.listings() }; if (!memory.work.accepts(generation)) return@execute
            definitions = options.first; listings = options.second
            val listing = selectedListing?.let { JSONObject(it.toString()) }
            val layout = withContext(Dispatchers.IO) { listing?.let(r::listingHome) ?: if ("get_home" in r.exports) r.home() else null }; if (!memory.work.accepts(generation)) return@execute
            if (listing != null) selectedHome = layout else home = layout
        }
    }; return }
    Column(Modifier.fillMaxSize()) {
        PageTitle(source.name, vm::back) {
            IconButton({ showFilters = true }, enabled = definitions.isNotEmpty()) { Icon(Icons.Outlined.FilterList, tr("FILTERS")) }
            // Settings and the website share the source's "more" menu, as on iOS.
            Box {
                var sourceMenu by remember { mutableStateOf(false) }
                IconButton({ sourceMenu = true }) { Icon(Icons.Outlined.MoreHoriz, tr("MORE_BARBUTTON")) }
                NativeMenu(sourceMenu, { sourceMenu = false }, listOf(listOf(
                    MenuAction(tr("SOURCE_SETTINGS"), Icons.Outlined.Settings) { showSettings = true },
                    MenuAction(tr("OPEN_WEBSITE"), Icons.Outlined.Public) { execute { browserUrl = withContext(Dispatchers.IO) { runtime?.baseUrl() ?: source.baseUrl } } })))
            }
        }
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true,
            placeholder = { Text(tr("SEARCH")) }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { selectedListing = null; loadSearch(false, null) }),
            trailingIcon = { IconButton({ selectedListing = null; loadSearch(false, null) }) { Icon(Icons.Outlined.ArrowForward, tr("SEARCH")) } })
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (home != null) FilterChip(!searchMode && selectedHome == null, { memory.work.cancel(); busy = false; error = null; memory.lastRequested = null; searchMode = false; selectedListing = null; selectedHome = null }, label = { Text(tr("HOME")) })
            FilterChip(searchMode && selectedListing == null, { selectedListing = null; loadSearch(false, null) }, label = { Text(tr("SEARCH")) })
            listings.forEach { listing -> FilterChip(selectedListing?.optString("id") == listing.optString("id"), { selectedListing = listing; loadSearch(false, listing) }, label = { Text(listing.optString("name")) }) }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error); TextButton({
            if (source.type == "wasm") vm.repo.client.runtimes.invalidate(sourceId)
            runtime = null; memory.configurationLoaded = false
            val requested = memory.lastRequested ?: memory.submittedRequest?.takeIf { searchMode || selectedListing != null }
            if (requested != null) loadSearch(requestOverride = requested) else initialize()
        }) { Text(tr("RETRY")) } }
        if (searchMode) {
            if (results.isEmpty() && !busy) Box(Modifier.weight(1f)) { EmptyPage(Icons.Outlined.Search, tr("NO_RESULTS"), tr("NO_RESULTS_TEXT")) }
            else MangaCollection(results, vm.repo, vm::open, Modifier.weight(1f))
            if (more) TextButton({ loadSearch(true) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(tr("MORE_BARBUTTON")) }
        } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items((selectedHome ?: home)?.optJSONArray("components")?.values()?.filterIsInstance<JSONObject>().orEmpty()) { component ->
                HomeComponent(vm, runtime, component, onListing = { selectedListing = it; loadSearch(false, it) }, onFilters = { values = it; selectedListing = null; loadSearch(false, null) }, onUrl = { browserUrl = it })
            }
        }
    }
    if (showFilters) SourceFilterDialog(definitions, values, runtime?.legacy == true, { showFilters = false }) { values = it; showFilters = false; selectedListing = null; loadSearch(false, null) }
    browserUrl?.takeIf { it.isNotEmpty() }?.let { url -> SourceWebsiteDialog(url, onDismiss = { browserUrl = null }, onDone = { address, ua, _ -> if (runtime is WasmSourceRuntime) runtime?.importBrowserCookies(address, ua, ""); browserUrl = null }, sourceId = sourceId, cookieHandler = { cookies -> runCatching { runtime?.importCookieRecords(url, cookies) } }) }
}

@Composable private fun HomeComponent(vm: MonogatariViewModel, runtime: SourceSession?, component: JSONObject,
    onListing: (JSONObject) -> Unit, onFilters: (List<JSONObject>) -> Unit, onUrl: (String) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(component.optString("title"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            component.optJSONObject("listing")?.let { listing -> TextButton({ onListing(listing) }) { Text(tr("SEE_ALL")) } }
        }
        component.stringOrNull("subtitle")?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        val entries = component.optJSONArray("entries")?.values()?.filterIsInstance<JSONObject>().orEmpty()
        fun action(raw: JSONObject) { val value = raw.optJSONObject("value"); when (value?.optInt("type")) { 0 -> value.optString("data").takeIf { it.startsWith("http:") || it.startsWith("https:") }?.let(onUrl); 1 -> value.optJSONObject("data")?.let(onListing); 2 -> value.optJSONObject("data")?.let { runtime?.manga(it)?.let(vm::open) } } }
        @Composable fun cover(raw: JSONObject, modifier: Modifier) { val m = raw.optJSONObject("value")?.takeIf { it.optInt("type") == 2 }?.optJSONObject("data")?.let { runtime?.manga(it) }
            Cover((m ?: Manga("home:${raw.optString("imageUrl")}", runtime?.sourceId.orEmpty(), raw.optString("title"))).copy(cover = raw.stringOrNull("imageUrl") ?: m?.cover.orEmpty()), vm.repo, modifier)
        }
        @Composable fun listItem(raw: JSONObject, index: Int) { Row(Modifier.fillMaxWidth().clickable { action(raw) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            cover(raw, Modifier.width(67.dp).height(100.dp)); Spacer(Modifier.width(12.dp))
            if (component.optBoolean("ranking")) { Text("${index + 1}", fontWeight = FontWeight.Bold); Spacer(Modifier.width(8.dp)) }
            Column(Modifier.weight(1f)) { Text(raw.optString("title"), maxLines = 2, fontWeight = FontWeight.Medium); raw.stringOrNull("subtitle")?.let { Text(it, maxLines = 1, style = MaterialTheme.typography.bodySmall) }
                raw.optJSONObject("value")?.optJSONObject("data")?.optJSONArray("tags")?.values()?.take(3)?.takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(" · "), maxLines = 1, style = MaterialTheme.typography.labelSmall) }
            }
        } }
        @Composable fun chapterItem(raw: JSONObject) { runtime?.manga(raw.getJSONObject("manga"))?.let { m -> Row(Modifier.fillMaxWidth().clickable { vm.open(m) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(m, vm.repo, Modifier.width(67.dp).height(100.dp)); Column(Modifier.padding(start = 12.dp).weight(1f)) { Text(m.title, maxLines = 2, fontWeight = FontWeight.Medium)
                val c = raw.getJSONObject("chapter"); val title = c.stringOrNull("title")?.takeIf(String::isNotEmpty) ?: (c.value("chapterNumber") as? Number)?.let { Localization.string(vm.repo.context, "CHAPTER_X", it.toDouble()) } ?: (c.value("volumeNumber") as? Number)?.let { Localization.string(vm.repo.context,"VOLUME_X",it.toDouble()) }.orEmpty()
                Text(title, maxLines = 1, style = MaterialTheme.typography.bodySmall); c.stringOrNull("scanlator")?.let { Text(it, maxLines = 1, style = MaterialTheme.typography.labelSmall) }
            }
        } } }
        when (component.optInt("type")) {
            1 -> { val manga = component.getJSONArray("manga").values().filterIsInstance<JSONObject>().mapNotNull { runtime?.manga(it) }
                val pager = rememberPagerState(pageCount = { manga.size })
                val interval = component.value("autoScrollInterval") as? Number
                LaunchedEffect(manga.size, interval, pager.currentPage, pager.isScrollInProgress) { if (manga.size > 1 && interval != null && interval.toDouble() > 0 && !pager.isScrollInProgress) { delay((interval.toDouble() * 1000).toLong().coerceAtLeast(100)); pager.animateScrollToPage((pager.currentPage + 1) % manga.size) } }
                if (manga.isNotEmpty()) HorizontalPager(pager, Modifier.fillMaxWidth(), pageSpacing = 16.dp) { index -> val m = manga[index]
                    Column(Modifier.fillMaxWidth().clickable { vm.open(m) }) { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Cover(m, vm.repo, Modifier.width(110.dp).height(165.dp)); Column(Modifier.weight(1f)) { Text(m.title, maxLines = 2, style = MaterialTheme.typography.titleLarge); Text(m.author, maxLines = 1, style = MaterialTheme.typography.bodySmall); Text(m.description, maxLines = 4, style = MaterialTheme.typography.bodyMedium) } }; if (m.tags.isNotEmpty()) Text(m.tags.take(4).joinToString(" · "), maxLines = 1, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp)) }
                }
            }
            3, 4 -> { val pageSize = (component.value("pageSize") as? Number)?.toInt()?.coerceIn(1, 50)
                if (pageSize == null) entries.forEachIndexed { i, raw -> if (component.optInt("type") == 4) chapterItem(raw) else listItem(raw, i) }
                else { val width = (LocalConfiguration.current.screenWidthDp - 52).coerceAtLeast(200).dp; LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) { items(entries.chunked(pageSize).withIndex().toList()) { page -> Column(Modifier.width(width)) { page.value.forEachIndexed { i, raw -> if (component.optInt("type") == 4) chapterItem(raw) else listItem(raw, page.index * pageSize + i) } } } } }
            }
            5 -> Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { component.getJSONArray("filters").values().filterIsInstance<JSONObject>().forEach { raw -> SuggestionChip({ onFilters(raw.optJSONArray("values")?.values()?.filterIsInstance<JSONObject>().orEmpty()) }, label = { Text(raw.optString("title")) }) } }
            6 -> entries.forEach { raw -> SettingRow(raw.optString("title"), subtitle = raw.stringOrNull("subtitle").orEmpty(), onClick = { action(raw) }) }
            else -> { val scroll = rememberLazyListState(); val interval = component.value("autoScrollInterval") as? Number
                LaunchedEffect(entries.size, interval, scroll.firstVisibleItemIndex, scroll.isScrollInProgress) { if (entries.size > 1 && interval != null && interval.toDouble() > 0 && !scroll.isScrollInProgress) { delay((interval.toDouble() * 1000).toLong().coerceAtLeast(100)); scroll.animateScrollToItem((scroll.firstVisibleItemIndex + 1) % entries.size) } }
                LazyRow(state = scroll, horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(entries) { raw -> val banner = component.optInt("type") == 0
                    Column(Modifier.width(if (banner) component.optInt("width", 210).coerceIn(1, 4000).dp else 120.dp).clickable { action(raw) }) { cover(raw, Modifier.fillMaxWidth().height(if (banner) component.optInt("height", 140).coerceIn(1, 4000).dp else 175.dp)); if (!banner) { Text(raw.optString("title"), maxLines = 2); raw.stringOrNull("subtitle")?.let { Text(it, style = MaterialTheme.typography.bodySmall) } } }
                } }
            }
        }
    }
}
@Composable private fun HomeManga(vm: MonogatariViewModel, manga: Manga) { Column(Modifier.width(120.dp).clickable { vm.open(manga) }) { Cover(manga, vm.repo, Modifier.width(120.dp).height(175.dp)); Text(manga.title, maxLines = 2) } }

internal fun defaultFilterValues(definitions: List<JSONObject>, legacy: Boolean): List<JSONObject> = definitions.flatMapIndexed { index, definition ->
    val type = definition.optString("type"); val id = definition.optString("id", definition.optString("name", index.toString()))
    if (type == "group") defaultFilterValues(definition.optJSONArray("filters")?.values()?.filterIsInstance<JSONObject>().orEmpty(), legacy)
    else if (type in setOf("note", "title", "author")) emptyList() else {
        val value = JSONObject(definition.toString()).put("id", id)
        when (type) {
            "text" -> value.put("type", 0).put("value", definition.optString("default"))
            "sort" -> value.put("type", 1).put("index", definition.optJSONObject("default")?.optInt("index") ?: 0).put("ascending", definition.optJSONObject("default")?.optBoolean("ascending") ?: false)
            "check", "genre" -> value.put("type", 2).put("value", if (!definition.has("default")) 0 else if (definition.optBoolean("default")) 1 else 2)
            "select" -> value.put("type", 3).put("value", if (legacy) definition.optInt("default") else definition.optString("default"))
            "multi-select" -> value.put("type", 4).put("included", definition.optJSONArray("defaultIncluded") ?: JSONArray()).put("excluded", definition.optJSONArray("defaultExcluded") ?: JSONArray())
            "range" -> value.put("type", 5)
        }; if (legacy && type == "genre") value.put("type", "genre"); listOf(value)
    }
}

@Composable private fun SourceFilterDialog(definitions: List<JSONObject>, values: List<JSONObject>, legacy: Boolean, onDismiss: () -> Unit, onApply: (List<JSONObject>) -> Unit) {
    var draft by remember { mutableStateOf(values.map { JSONObject(it.toString()) }) }
    fun update(id: String, block: (JSONObject) -> Unit) { draft = draft.map { if (it.optString("id") == id) JSONObject(it.toString()).also(block) else it } }
    fun idOf(definition: JSONObject, index: Int) = definition.optString("id", definition.optString("name", index.toString()))
    fun valueOf(id: String) = draft.firstOrNull { it.optString("id") == id } ?: JSONObject()
    val toggles = setOf("check", "genre")
    // Consecutive check filters share one wrapping chip group instead of one row per option.
    @Composable fun Filters(defs: List<JSONObject>) {
        var index = 0
        while (index < defs.size) {
            val d = defs[index]; val type = d.optString("type")
            if (type in toggles) {
                val run = buildList { while (index < defs.size && defs[index].optString("type") in toggles) { add(index to defs[index]); index++ } }
                SheetChips(Modifier.padding(vertical = 6.dp)) { run.forEach { (position, definition) ->
                    val id = idOf(definition, position); val state = valueOf(id).optInt("value")
                    key(id) { SheetChip(definition.optString("title", definition.optString("name", id)), state == 1,
                        { update(id) { j -> j.put("value", (j.optInt("value") + 1) % if (definition.optBoolean("canExclude")) 3 else 2) } }, excluded = state == 2) }
                } }
                continue
            }
            val id = idOf(d, index); val title = d.optString("title", d.optString("name", id)); val value = valueOf(id)
            key(id) { when (type) {
                "group" -> SheetSection(title) { Filters(d.optJSONArray("filters")?.values()?.filterIsInstance<JSONObject>().orEmpty()) }
                "note" -> Text(d.optString("text"), Modifier.padding(horizontal = 20.dp, vertical = 8.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                "author" -> { var text by remember { mutableStateOf("") }
                    OutlinedTextField(text, { text = it; val existing = draft.filterNot { f -> f.optString("id") == id }; draft = existing + JSONObject().put("type", "author").put("id", id).put("name", "Author").put("value", it) },
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), label = { Text(title) }, singleLine = true) }
                "text" -> OutlinedTextField(value.optString("value"), { update(id) { j -> j.put("value", it) } }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                    label = { Text(title) }, singleLine = true)
                "sort", "select", "multi-select" -> SheetSection(title) { SheetChips {
                    val options = d.optJSONArray("options")?.values()?.map { it.toString() }.orEmpty(); val ids = d.optJSONArray("ids")?.values()?.map { it.toString() } ?: options
                    val included = value.optJSONArray("included")?.values()?.map { it.toString() }.orEmpty(); val excluded = value.optJSONArray("excluded")?.values()?.map { it.toString() }.orEmpty()
                    options.forEachIndexed { i, label -> val option = ids.getOrElse(i) { label }
                        val selected = when (type) { "sort" -> value.optInt("index") == i; "select" -> if (legacy) value.optInt("value") == i else value.optString("value") == option; else -> option in included }
                        // Like the chapter list, choosing the active sort again flips its direction when the source allows it.
                        val ascending = type == "sort" && d.optBoolean("canAscend")
                        SheetChip(label, selected, { update(id) { j -> when (type) {
                            "sort" -> if (ascending && j.optInt("index") == i) j.put("ascending", !j.optBoolean("ascending")) else j.put("index", i)
                            "select" -> j.put("value", if (legacy) i else option)
                            else -> { val inc = j.optJSONArray("included")?.values()?.map { it.toString() }.orEmpty(); val exc = j.optJSONArray("excluded")?.values()?.map { it.toString() }.orEmpty()
                                when { option in inc && d.optBoolean("canExclude") -> { j.put("included", JSONArray(inc - option)); j.put("excluded", JSONArray(exc + option)) }
                                    option in inc -> j.put("included", JSONArray(inc - option)); option in exc -> j.put("excluded", JSONArray(exc - option)); else -> j.put("included", JSONArray(inc + option)) } }
                        } } }, excluded = type == "multi-select" && option in excluded, checkmark = type == "multi-select",
                            trailingIcon = if (ascending && selected) { if (value.optBoolean("ascending")) Icons.Outlined.ArrowUpward else Icons.Outlined.ArrowDownward } else null)
                    }
                } }
                "range" -> SheetSection(title) { Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { for (bound in listOf("from", "to")) OutlinedTextField(value.value(bound)?.toString().orEmpty(),
                    { text -> update(id) { j -> j.put(bound, text.toFloatOrNull()) } }, Modifier.weight(1f), singleLine = true,
                    placeholder = { Text(if (bound == "from") d.value("min")?.toString().orEmpty() else d.value("max")?.toString().orEmpty()) }) } }
            } }
            index++
        }
    }
    OptionSheet(tr("FILTERS"), onDismiss, footer = {
        SheetSecondaryButton(tr("RESET"), { draft = defaultFilterValues(definitions, legacy) })
        SheetPrimaryButton(tr("DONE"), { onApply(draft) })
    }) { Filters(definitions) }
}

private data class SourceSettingRenderValue(val value: Any?, val enabled: Boolean)
private fun sourceSettingsSnapshot(preferences: SourcePreferences?, settings: List<JSONObject>): Map<JSONObject, SourceSettingRenderValue> = buildMap {
    fun collect(rows: List<JSONObject>, parentEnabled: Boolean) { rows.forEach { setting ->
        val requires = setting.stringOrNull("requires"); val requiresFalse = setting.stringOrNull("requiresFalse")
        val enabled = parentEnabled && (requires == null || settingRequires(preferences, requires)) && (requiresFalse == null || !settingRequires(preferences, requiresFalse))
        put(setting, SourceSettingRenderValue(preferences?.get(setting.optString("key")), enabled))
        if (setting.optString("type") == "group") collect(setting.optJSONArray("items")?.values()?.filterIsInstance<JSONObject>().orEmpty(), enabled)
    } }
    collect(settings, true)
}
@Composable fun SourceSettingsScreen(vm: MonogatariViewModel, sourceId: String, onBack: () -> Unit = vm::back) {
    val source = vm.state.value.sources.firstOrNull { it.id == sourceId } ?: return
    var runtime by remember(sourceId) { mutableStateOf<SourceSession?>(null) }
    var definitions by remember(sourceId) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var revision by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    var browser by remember { mutableStateOf<JSONObject?>(null) }
    var oauth by remember { mutableStateOf<SourceOAuthSession?>(null) }
    var protectedAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pages by remember(sourceId) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var confirmation by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val credentialLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result -> if (result.resultCode == android.app.Activity.RESULT_OK) protectedAction?.invoke(); protectedAction = null }
    val scope = rememberCoroutineScope()
    fun protected(block: () -> Unit) {
        val intent = context.getSystemService(KeyguardManager::class.java).createConfirmDeviceCredentialIntent(Localization.string(context, "AUTH_TO_OPEN"), AppBrand.NAME)
        if (intent == null) vm.message = "请先设置 Android 屏幕锁定" else { protectedAction = block; credentialLauncher.launch(intent) }
    }
    fun openSettingBrowser(setting: JSONObject) { try { oauth = if (setting.optString("method") == "oauth") runtime?.let { SourceOAuthSession(it, setting) } else null; browser = setting } catch (e: Exception) { vm.message = e.message } }
    fun reload() { scope.launch { try { definitions = withContext(Dispatchers.IO) { (runtime ?: vm.repo.client.session(source).also { runtime = it }).settings() }; revision++ } catch (e: Exception) { vm.message = e.message } } }
    fun save(setting: JSONObject, value: Any?) { runtime?.preferences?.set(setting.optString("key"), value); revision++; scope.launch { try { withContext(Dispatchers.IO) { setting.stringOrNull("notification")?.let { runtime?.notify(it) } }; revision++; if (setting.has("refreshes")) reload() } catch (e: Exception) { vm.message = e.message } } }
    LaunchedEffect(sourceId) { reload() }
    BackHandler { if (pages.isNotEmpty()) pages = pages.dropLast(1) else onBack() }
    val displayedSettings = pages.lastOrNull()?.optJSONArray("items")?.values()?.filterIsInstance<JSONObject>() ?: definitions
    // Preference storage is not Compose state. Read the revision in this scope
    // and capture row values before lazy content is memoized. This also updates
    // requires/requiresFalse rows without recreating the list or its scroll state.
    val settingsRevision = revision
    val renderedValues = remember(runtime, displayedSettings, settingsRevision) { sourceSettingsSnapshot(runtime?.preferences, displayedSettings) }
    Column(Modifier.fillMaxSize()) {
        PageTitle(pages.lastOrNull()?.optString("title")?.let { tr(it) } ?: tr("SOURCE_SETTINGS"), { if (pages.isNotEmpty()) pages = pages.dropLast(1) else onBack() })
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = centeredInset(ReadableWidth))) {
            item { SettingRow(source.name, value = if (source.type == "wasm") runCatching { "v${vm.repo.client.runtimes.installed(sourceId).version}" }.getOrDefault("") else source.type.replaceFirstChar(Char::uppercase)) }
            fun androidx.compose.foundation.lazy.LazyListScope.rows(settings: List<JSONObject>) { settings.forEach { s ->
                val rendered = renderedValues.getValue(s); val enabled = rendered.enabled
                val type = s.optString("type"); val title = Localization.string(context, s.optString("title")); val value = rendered.value
                if (type == "group") { if (title.isNotEmpty()) item { Text(title, Modifier.padding(16.dp), fontWeight = FontWeight.Bold) }; rows(s.optJSONArray("items")?.values()?.filterIsInstance<JSONObject>().orEmpty()); s.stringOrNull("footer")?.let { footer -> item { Text(tr(footer), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) } } }
                else item {
                    if (!enabled) SettingRow(title, value = value?.toString().orEmpty(), trailing = if (type in setOf("switch", "toggle")) ({ Switch(value == true, null, Modifier.semantics { contentDescription = title }, enabled = false) }) else null)
                    else
                    when (type) {
                        "page" -> SettingRow(title, subtitle = Localization.string(context, s.optString("info")), onClick = { if (s.optBoolean("authToOpen")) protected { pages = pages + s } else pages = pages + s })
                        "note" -> Text(tr(s.optString("text")), Modifier.padding(16.dp))
                        "custom" -> SettingRow(title, value = tr("UNAVAILABLE"))
                        "switch", "toggle" -> SettingRow(title, subtitle = Localization.string(context, s.optString("subtitle")), trailing = { Switch(value == true, { newValue -> if (!newValue && s.optBoolean("authToDisable")) protected { save(s, false) } else save(s, newValue) }, Modifier.semantics { contentDescription = title }) })
                        "stepper", "slider" -> Column(Modifier.padding(16.dp)) { val min = s.optDouble("minimumValue").toFloat(); val max = s.optDouble("maximumValue", 1.0).toFloat().coerceAtLeast(min + 0.001f); var number by remember(settingsRevision) { mutableFloatStateOf((value as? Number)?.toFloat() ?: min) }; Text("$title: $number"); Row(verticalAlignment = Alignment.CenterVertically) { TextButton({ save(s, (number - s.optDouble("stepValue", 1.0).toFloat()).coerceAtLeast(min).toDouble()) }) { Text("−") }; Slider(number, { number = it }, Modifier.weight(1f), valueRange = min..max, onValueChangeFinished = { val step = s.optDouble("stepValue", 0.0).toFloat(); save(s, if (step > 0) (kotlin.math.round((number - min) / step) * step + min).toDouble() else number.toDouble()) }); TextButton({ save(s, (number + s.optDouble("stepValue", 1.0).toFloat()).coerceAtMost(max).toDouble()) }) { Text("+") } } }
                        "info" -> SettingRow(title, value = value?.toString().orEmpty())
                        "link" -> SettingRow(title, onClick = { if (s.optBoolean("external")) context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.getString("url")))) else browser = s })
                        "button" -> SettingRow(title, color = if (s.optBoolean("destructive")) MaterialTheme.colorScheme.error else Pink, onClick = { selected = s })
                        else -> SettingRow(title, value = if (type == "login") "" else when (value) { is JSONArray -> value.values().joinToString(", "); else -> if (s.optBoolean("secure")) "••••" else value?.toString().orEmpty() }, onClick = { if (s.optBoolean("authToOpen")) protected { selected = s } else selected = s })
                    }
                }
            } }; rows(displayedSettings)
            if (pages.isEmpty()) item { Column { TextButton({ confirmation = "clear" }, Modifier.fillMaxWidth()) { Text(tr("CLEAR_SOURCE_CACHE")) }; TextButton({ confirmation = "reset" }, Modifier.fillMaxWidth()) { Text(tr("RESET_SETTINGS")) } } }
        }
    }
    confirmation?.let { action -> CallerContextAlertDialog(onDismissRequest = { confirmation = null }, title = { Text(tr(if (action == "clear") "CLEAR_SOURCE_CACHE" else "RESET_SETTINGS")) }, text = { Text(if (action == "clear") tr("CLEAR_SOURCE_CACHE_TEXT") else tr("RESET_SETTINGS_CONFIRM_%@", source.name)) }, confirmButton = { TextButton({ confirmation = null; scope.launch { try { withContext(Dispatchers.IO) { if (action == "clear") runtime?.clearCookies() else runtime?.resetSettings() }; reload() } catch (e: Exception) { vm.message = e.message } } }) { Text(tr(if (action == "clear") "CLEAR" else "RESET")) } }, dismissButton = { TextButton({ confirmation = null }) { Text(tr("CANCEL")) } }) }
    selected?.let { setting -> SourceSettingEditor(vm, runtime, setting, { selected = null; revision++; if (setting.has("refreshes")) reload() }, { value -> save(setting, value); selected = null }, { openSettingBrowser(setting); selected = null }) }
    browser?.let { setting -> val url = oauth?.authorizationUrl ?: setting.optString("url").ifEmpty { runtime?.preferences?.get(setting.optString("urlKey"))?.toString().orEmpty() }; if (url.isNotEmpty()) SourceWebsiteDialog(url, { browser = null; oauth = null }, onDone = { address, ua, raw ->
        if (runtime is WasmSourceRuntime) runtime?.importBrowserCookies(address, ua, "")
        if (setting.optString("type") == "login" && setting.optString("method") == "web" && runtime != null) scope.launch { try { val cookies = raw.split(';').mapNotNull { val pair = it.trim().split('=', limit = 2); if (pair.size == 2) pair[0] to pair[1] else null }.toMap(); val ok = withContext(Dispatchers.IO) { runtime!!.webLogin(setting.optString("key"), cookies) }; if (!ok) vm.message = Localization.string(vm.repo.context, "LOGIN_FAILED"); else setting.stringOrNull("notification")?.let { withContext(Dispatchers.IO) { runtime!!.notify(it) } }; revision++ } catch (e: Exception) { vm.message = e.message } }; browser = null; oauth = null
    }, sourceId = sourceId, cookieHandler = { cookies -> runCatching { runtime?.importCookieRecords(url, cookies) } }, onNavigation = { address ->
        val session = oauth
        if (session?.accepts(address) == true) { scope.launch { try { withContext(Dispatchers.IO) { session.complete(address) }; browser = null; oauth = null; revision++ } catch (e: Exception) { vm.message = e.message } }; true } else false
    }, localStorageKeys = setting.optJSONArray("localStorageKeys")?.values()?.map { it.toString() }.orEmpty(), localStorageHandler = { storage -> storage.forEach { (name, value) -> runtime?.preferences?.set("${setting.optString("key")}.ls.$name", value) } }) }
}
internal fun settingRequires(preferences: SourcePreferences?, expression: String): Boolean = expression.split("&&").map(String::trim).filter(String::isNotEmpty).all { clause ->
    val parts = clause.split("==", limit = 2).map(String::trim)
    if (parts[0] == "true") true else {
        val value = when (val raw = preferences?.get(parts[0])) { is Boolean -> if (raw) "1" else "0"; is Number, is String -> raw.toString(); else -> null }
        if (parts.size == 2) value == parts[1] else value != null && value != "0"
    }
}

@Composable private fun SourceSettingEditor(vm: MonogatariViewModel, runtime: SourceSession?, setting: JSONObject, dismiss: () -> Unit, save: (Any?) -> Unit, openBrowser: () -> Unit) {
    val type = setting.optString("type"); val current = runtime?.preferences?.get(setting.optString("key"))
    var text by remember { mutableStateOf(if (current is JSONArray) current.values().joinToString("\n") else current?.toString().orEmpty()) }; var username by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf((current as? JSONArray)?.values()?.map { it.toString() }.orEmpty()) }
    val scope = rememberCoroutineScope()
    val options = setting.optJSONArray(if (type == "segment") "options" else "values")?.values()?.map { it.toString() }.orEmpty()
    val titles = setting.optJSONArray("titles")?.values()?.map { it.toString() } ?: options
    CallerContextAlertDialog(onDismissRequest = dismiss, title = { Text(tr(setting.optString("confirmTitle", setting.optString("title")))) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (type) {
                "select", "multi-select", "multi-single-select", "segment", "menu", "picker" -> options.forEachIndexed { i, option -> Row(Modifier.fillMaxWidth().clickable { when(type) { "multi-select" -> selected = if (option in selected) selected - option else selected + option;"multi-single-select" -> save(JSONArray().put(option));else -> save(if (type == "segment") i else option) } }, verticalAlignment = Alignment.CenterVertically) { if (type == "multi-select") Checkbox(option in selected, null) else RadioButton(if (type == "segment") (current as? Number)?.toInt() == i else if(type=="multi-single-select")option in selected else current == option, null); Text(tr(titles.getOrElse(i) { option })) } }
                "button" -> Text(tr(setting.optString("confirmText")))
                "login" -> if (setting.optString("method") == "basic") { Text(tr("LOGIN_BASIC_TEXT")); OutlinedTextField(username, { username = it }, label = { Text(tr("USERNAME")) }); OutlinedTextField(password, { password = it }, label = { Text(tr("PASSWORD")) }, visualTransformation = PasswordVisualTransformation()) } else { Text(tr("LOGIN_WEBVIEW_WARNING_TEXT")); TextButton(openBrowser) { Text(tr("LOGIN")) } }
                else -> OutlinedTextField(text, { text = it }, label = { Text(tr(setting.optString("placeholder"))) }, visualTransformation = if (setting.optBoolean("secure")) PasswordVisualTransformation() else VisualTransformation.None, minLines = if (type == "list" || type == "editable-list") 3 else 1)
            }
            if (type == "login" && current != null) TextButton({ scope.launch { try { withContext(Dispatchers.IO) {
                val key = setting.optString("key"); for (suffix in listOf("", ".username", ".password", ".keys", ".values")) runtime?.preferences?.set(key + suffix, null)
                setting.optJSONArray("localStorageKeys")?.values()?.forEach { runtime?.preferences?.set("$key.ls.$it", null) }
                if (setting.optBoolean("clearCookiesOnLogOut")) runtime?.clearCookies()
                setting.stringOrNull("notification")?.let { runtime?.notify(it) }
            }; dismiss() } catch (e: Exception) { vm.message = e.message } } }) { Text(setting.optString("logoutTitle", tr("LOGOUT"))) }
        }
    }, confirmButton = { TextButton({ when {
        type == "button" -> { vm.run { withContext(Dispatchers.IO) { runtime?.notify(setting.optString("notification", setting.optString("key"))) } }; dismiss() }
        type == "login" && setting.optString("method") == "basic" -> scope.launch { try { require(username.isNotBlank() && password.isNotEmpty()); val ok = withContext(Dispatchers.IO) { runtime?.basicLogin(setting.optString("key"), username, password) == true }; if (ok) { setting.stringOrNull("notification")?.let { withContext(Dispatchers.IO) { runtime?.notify(it) } }; dismiss() } else vm.message = Localization.string(vm.repo.context, "LOGIN_FAILED") } catch (e: Exception) { vm.message = e.message } }
        type == "login" -> openBrowser()
        type == "multi-select" || type=="multi-single-select" -> save(JSONArray(if(type=="multi-single-select")selected.take(1) else selected))
        type == "list" || type == "editable-list" -> save(JSONArray(text.lines().filter(String::isNotBlank)))
        else -> save(text)
    } }) { Text(tr(if (type == "login") "LOGIN" else "DONE")) } }, dismissButton = { TextButton(dismiss) { Text(tr("CANCEL")) } })
}

/** The browser appears only after a user action or a challenge response. Cookies retain the browser's domain scope. */
@SuppressLint("SetJavaScriptEnabled") @Composable fun SourceWebsiteDialog(url: String, onDismiss: () -> Unit, onDone: (String, String, String) -> Unit, sourceId: String = "browser", cookieHandler: (List<okhttp3.Cookie>) -> Unit = {}, onNavigation: (String) -> Boolean = { false }, localStorageKeys: List<String> = emptyList(), localStorageHandler: (Map<String, String?>) -> Unit = {}, callbackCookies: ((String, List<okhttp3.Cookie>) -> Unit)? = null) {
    if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.MULTI_PROFILE)) { CallerContextAlertDialog(onDismissRequest = onDismiss, title = { Text(tr("UNAVAILABLE")) }, text = { Text("请更新 Android System WebView 以启用独立的漫画源浏览器。") }, confirmButton = { TextButton(onDismiss) { Text(tr("DONE")) } }); return }
    var view by remember { mutableStateOf<WebView?>(null) }
    var address by remember { mutableStateOf(url) }
    var loading by remember { mutableStateOf(true) }
    CallerContextDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) { Column {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onDismiss) { Icon(Icons.Outlined.Close, tr("CANCEL")) }; Text(address, Modifier.weight(1f), maxLines = 1)
                TextButton({ view?.let { web ->
                    val manager = SourceWebProfiles.manager(web); manager.flush(); val records = SourceWebProfiles.records(web, listOf(url, web.url ?: url))
                    fun finish() { cookieHandler(records); onDone(web.url ?: url, web.settings.userAgentString, manager.getCookie(web.url ?: url).orEmpty()) }
                    if (localStorageKeys.isEmpty()) finish() else web.evaluateJavascript("JSON.stringify(Object.fromEntries(${JSONArray(localStorageKeys)}.map(k=>[k,localStorage.getItem(k)])))") { encoded ->
                        runCatching { val raw = org.json.JSONTokener(encoded).nextValue() as String; val values = JSONObject(raw); localStorageHandler(values.keys().asSequence().associateWith { key -> values.stringOrNull(key) }) }.onFailure { vmLog(web.context, it) }; finish()
                    }
                } }) { Text(tr("DONE")) }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            AndroidView(modifier = Modifier.weight(1f), factory = { context -> WebView(context).apply {
                SourceWebProfiles.configure(this, sourceId)
                settings.javaScriptEnabled = true; settings.domStorageEnabled = true; settings.allowFileAccess = false; settings.allowContentAccess = false
                webViewClient = object : WebViewClient() { override fun shouldOverrideUrlLoading(v: WebView, request: android.webkit.WebResourceRequest): Boolean { val target = request.url.toString(); val handled = onNavigation(target); if (handled) callbackCookies?.invoke(target, SourceWebProfiles.records(v, listOf(url, v.url ?: url))); return handled }; override fun onPageStarted(v: WebView, u: String?, favicon: android.graphics.Bitmap?) { loading = true; address = u ?: address }; override fun onPageFinished(v: WebView, u: String?) { loading = false; address = u ?: address } }
                view = this; loadUrl(url)
            } })
        } }
    }
    DisposableEffect(Unit) { onDispose { view?.stopLoading(); view?.destroy(); view = null } }
}
private fun vmLog(context: android.content.Context, e: Throwable) { SourceLogStore.get(context).add(e.message.orEmpty(), "WARNING") }
