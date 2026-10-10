package app.monogatari.android.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.monogatari.android.MonogatariViewModel
import app.monogatari.android.data.*
import app.monogatari.android.downloads.DownloadService
import app.monogatari.android.sources.*
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

private fun languages(source: SourceConfig): List<String> {
    if (source.type == CopySource.TYPE) return listOf("zh")
    if (source.type != "wasm") return listOf("multi")
    return sourceSupportedLanguages(JSONObject(source.configuration))
}
private fun rating(source: SourceConfig) = if (source.type == CopySource.TYPE) 1 else if (source.type == "wasm") sourceContentRating(JSONObject(source.configuration)) else 0
private fun languageName(context: android.content.Context, code: String) = if (code == "multi") Localization.string(context, "MULTI_LANGUAGE") else Locale.forLanguageTag(code).getDisplayName(Locale.getDefault()).ifBlank { code }
private fun enabledRatings(vm: MonogatariViewModel): Set<Int> = (vm.repo.settings.value("Browse.contentRatings") as? JSONArray)?.values()?.mapNotNull { when (it.toString()) { "0", "safe" -> 0; "1", "containsNsfw" -> 1; "2", "primarilyNsfw" -> 2; else -> null } }?.toSet() ?: setOf(0, 1)
private fun accepts(languages: List<String>, selected: List<String>) = languages.any { it.substringBefore('-').substringBefore('_').lowercase() in selected.map(String::lowercase) }
private fun catalog(state: AppState) = state.sourceLists.flatMap { it.entries }.filter(SourceCompatibility::supports).groupBy { it.id }.values.map { versions -> versions.maxBy { it.version } }

@Composable fun NativeBrowseScreen(vm: MonogatariViewModel, state: AppState, import: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var edit by rememberSaveable { mutableStateOf(false) }
    var filter by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf<SourceConfig?>(null) }
    val context = LocalContext.current
    val pinned = vm.repo.settings.strings("Browse.pinnedList")
    val disabled = vm.repo.settings.strings("Browse.disabledSources")
    val sourceLanguages = vm.repo.settings.strings("Browse.languages")
    val ratings = enabledRatings(vm)
    val manager = vm.repo.client.runtimes
    val pinnedTitle = tr("PINNED"); val installedTitle = tr("INSTALLED"); val disabledTitle = tr("DISABLED")
    val available = catalog(state)
    val updates = available.filter { item -> state.sources.any { it.id == item.id } && runCatching { manager.installed(item.id).version < item.version }.getOrDefault(false) }
    val visible = state.sources.filter { it.name.contains(query, true) && (edit || it.id !in disabled) }
    fun put(key: String, value: List<String>) { vm.run { vm.repo.settings.put(key, JSONArray(value)) } }
    fun updateAll() { vm.run { val failures = mutableListOf<String>(); for (entry in updates) try { val source = withContext(Dispatchers.IO) { manager.install(entry.downloadUrl, entry.id, entry.version, retainPrevious = true) }; saveInstalled(vm, source) } catch (e: Exception) { failures += "${entry.name}: ${e.message}" }; if (failures.isNotEmpty()) vm.message = failures.joinToString("\n") } }
    Column(Modifier.fillMaxSize()) {
        PageTitle(if (vm.screen == "sourceManager") pt("Sources", "图源管理") else tr("BROWSE"), back = if (vm.screen == "sourceManager") vm::back else null,
            actions = { IconButton({ edit = !edit }) { Icon(if (edit) Icons.Outlined.Done else Icons.Outlined.Edit, tr(if (edit) "DONE" else "EDIT_SOURCES")) }; IconButton({ vm.navigate("addSources") }) { Icon(Icons.Outlined.Add, tr("ADD_SOURCE"), tint = Pink) } })
        SearchField(query, tr("SEARCH")) { query = it }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp + centeredInset(DetailWidth), vertical = 16.dp)) {
            if (updates.isNotEmpty()) {
                item { Row(verticalAlignment = Alignment.CenterVertically) { Text(tr("UPDATES"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge); TextButton(::updateAll, enabled = !vm.busy) { Text(tr("BUTTON_UPDATE") + " · " + tr("ALL")) } } }
                items(updates.filter { it.name.contains(query, true) }, key = { "update:${it.id}" }) { SourcePackageRow(vm, it) }
            }
            fun androidx.compose.foundation.lazy.LazyListScope.section(title: String, sources: List<SourceConfig>) {
                if (sources.isEmpty()) return
                item { Text(title, Modifier.padding(vertical = 14.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                items(sources, key = { "installed:${it.id}" }) { source ->
                    var menu by remember(source.id) { mutableStateOf(false) }
                    val entry = available.firstOrNull { it.id == source.id }
                    val installed = if (source.type == "wasm") runCatching { manager.installed(source.id) }.getOrNull() else null
                    Row(Modifier.fillMaxWidth().clickable { if (edit) menu = true else vm.navigate("source", source.id) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (installed != null) AsyncImage(installed.icon, null, Modifier.size(42.dp)) else Icon(if (source.type == "local") Icons.Outlined.Folder else Icons.Outlined.Dns, null, Modifier.size(36.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(source.name, fontWeight = FontWeight.Medium); Text(languages(source).joinToString(" · ") { languageName(context, it) } + " · " + (installed?.let { "v${it.version}" } ?: tr("BUILT_IN_SOURCES")), style = MaterialTheme.typography.bodySmall)
                            if (rating(source) != 0) Text(tr(if (rating(source) == 1) "CONTAINS_NSFW" else "PRIMARILY_NSFW"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            if (source.id in disabled) Text(tr("DISABLED"), style = MaterialTheme.typography.labelSmall)
                            if (installed != null && entry == null) Text(tr("MISSING_SOURCE_LIST"), style = MaterialTheme.typography.labelSmall)
                        }
                        Box { IconButton({ menu = true }) { Icon(Icons.Outlined.MoreVert, tr("EDIT")) }
                            // Order, then pin / disable, then the destructive uninstall, as in the iOS source context menu.
                            fun move(direction: Int) { val next = pinned.toMutableList(); val old = next.indexOf(source.id); val index = old + direction
                                if (index in next.indices) { next.removeAt(old); next.add(index, source.id); put("Browse.pinnedList", next) } }
                            NativeMenu(menu, { menu = false }, listOf(
                                if (source.id in pinned) listOf(
                                    MenuAction(pt("Move up", "上移"), Icons.Outlined.KeyboardArrowUp, enabled = pinned.indexOf(source.id) > 0) { move(-1) },
                                    MenuAction(pt("Move down", "下移"), Icons.Outlined.KeyboardArrowDown, enabled = pinned.indexOf(source.id) < pinned.lastIndex) { move(1) }) else emptyList(),
                                listOf(
                                    MenuAction(tr(if (source.id in pinned) "UNPIN" else "PIN"), Icons.Outlined.PushPin) { put("Browse.pinnedList", if (source.id in pinned) pinned - source.id else pinned + source.id) },
                                    MenuAction(tr(if (source.id in disabled) "ENABLE" else "DISABLE"), if (source.id in disabled) Icons.Outlined.PlayArrow else Icons.Outlined.Pause) {
                                        put("Browse.disabledSources", if (source.id in disabled) disabled - source.id else disabled + source.id) },
                                    MenuAction(tr("SOURCE_SETTINGS"), Icons.Outlined.Settings) { vm.navigate("sourceSettings", source.id) }),
                                if (source.type != "local") listOf(MenuAction(tr("UNINSTALL"), Icons.Outlined.DeleteOutline, destructive = true) { remove = source }) else emptyList()))
                        }
                    }
                    HorizontalDivider()
                }
            }
            section(pinnedTitle, pinned.mapNotNull { id -> visible.firstOrNull { it.id == id } })
            section(installedTitle, visible.filter { it.id !in pinned && it.id !in disabled }.sortedWith(compareBy<SourceConfig> { if (languages(it).size > 1 || languages(it).firstOrNull() == "multi") "" else languageName(context, languages(it).first()) }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }))
            if (edit) section(disabledTitle, visible.filter { it.id in disabled && it.id !in pinned })
            if (visible.isEmpty()) item { EmptyPage(Icons.Outlined.Extension, tr("BROWSE_NO_SOURCES"), tr("BROWSE_NO_SOURCES_TEXT_NEW")) }
            item { SettingRow(tr("ADD_SOURCE"), Icons.Outlined.AddCircleOutline, onClick = { vm.navigate("addSources") }); SettingRow(tr("IMPORT"), Icons.Outlined.FileOpen, onClick = import) }
        }
    }
    if (filter) SourceBrowseFilter(vm, (state.sources.flatMap(::languages) + available.flatMap { it.languages }).distinct(), { filter = false })
    remove?.let { source -> CallerContextAlertDialog({ remove = null }, title = { Text(tr("UNINSTALL")) }, text = { Text(source.name) }, confirmButton = { TextButton({ remove = null; vm.run {
        val downloads = DownloadService.get(context); downloads.entries.value.filter { it.sourceId == source.id }.forEach { downloads.cancel(it.id) }
        vm.repo.change { current -> val settings = JSONObject(current.settings).put("Browse.pinnedList", JSONArray(pinned - source.id)).put("Browse.disabledSources", JSONArray(disabled - source.id)); current.copy(sources = current.sources.filterNot { it.id == source.id }, settings = settings.toString()) }
        withContext(Dispatchers.IO) { if (source.type == "wasm") manager.remove(source.id); vm.repo.client.clearAuthentication(source.id); SourcePreferences(context, source.id).keys().forEach { SourcePreferences(context, source.id).set(it, null) }; SourceWebProfiles.clear(context, source.id) }
    } }) { Text(tr("UNINSTALL")) } }, dismissButton = { TextButton({ remove = null }) { Text(tr("CANCEL")) } }) }
}

@Composable private fun SourceBrowseFilter(vm: MonogatariViewModel, languages: List<String>, dismiss: () -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(vm.repo.settings.strings("Browse.languages")) }
    var ratings by remember { mutableStateOf(enabledRatings(vm)) }
    OptionSheet(tr("FILTERS"), dismiss, footer = {
        SheetSecondaryButton(tr("CANCEL"), LocalSheetClose.current)
        SheetPrimaryButton(tr("DONE"), { vm.run { vm.repo.settings.put("Browse.languages", JSONArray(selected)); vm.repo.settings.put("Browse.contentRatings", JSONArray(ratings.sorted())); dismiss() } })
    }) {
        SheetSection(tr("LANGUAGE")) { SheetChips {
            (listOf("multi") + languages).distinct().sortedBy { if (it == "multi") "" else languageName(context, it) }.forEach { code ->
                SheetChip(languageName(context, code), code in selected, { selected = if (code in selected) selected - code else selected + code })
            }
        } }
        SheetSection(tr("CONTENT_RATING")) { SheetChips {
            listOf("SAFE", "CONTAINS_NSFW", "PRIMARILY_NSFW").forEachIndexed { index, title -> SheetChip(tr(title), index in ratings, { ratings = if (index in ratings) ratings - index else ratings + index }) }
        } }
    }
}

@Composable fun NativeAddSourcesScreen(vm: MonogatariViewModel, state: AppState, import: () -> Unit) {
    var setup by remember { mutableStateOf<String?>(null) }; var query by rememberSaveable { mutableStateOf("") }; var filter by remember { mutableStateOf(false) }
    val selected = vm.repo.settings.strings("Browse.languages"); val ratings = enabledRatings(vm)
    Column(Modifier.fillMaxSize()) {
        PageTitle(tr("ADD_SOURCE"), vm::back, actions = { IconButton({ filter = true }) { Icon(Icons.Outlined.FilterList, tr("FILTERS")) }; IconButton({ vm.run { val fresh = withContext(Dispatchers.IO) { state.sourceLists.map { vm.repo.client.sourceList(it.url) } }; vm.repo.change { it.copy(sourceLists = fresh) } } }, enabled = !vm.busy) { Icon(Icons.Outlined.Refresh, tr("UPDATES")) } })
        SearchField(query, tr("SEARCH")) { query = it }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp + centeredInset(DetailWidth), vertical = 16.dp)) {
            item { Text(tr("BUILT_IN_SOURCES"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            if (query.isEmpty() || Localization.string(vm.repo.context, "LOCAL_FILES").contains(query, true)) item { SettingRow(tr("LOCAL_FILES"), Icons.Outlined.Folder, onClick = import) }
            if ("拷贝漫画".contains(query, true) || "copy".contains(query, true)) item { SettingRow("拷贝漫画", Icons.Outlined.MenuBook,
                subtitle = CopySource.WEBSITE, value = if (state.sources.any { it.type == CopySource.TYPE }) pt("Installed", "已添加") else pt("Add", "添加"), onClick = { vm.run {
                    vm.repo.change { current -> current.copy(sources = (current.sources + CopySource.configuration).distinctBy { it.id }) }
                    vm.repo.settings.put("Browse.disabledSources", JSONArray(vm.repo.settings.strings("Browse.disabledSources") - CopySource.ID))
                    vm.navigate("copyCatalog", "discover")
                } }) }
            for (type in listOf("komga", "kavita", "suwayomi")) if (type.contains(query, true)) item { SettingRow(tr(type.uppercase()), Icons.Outlined.Dns, subtitle = tr("${type.uppercase()}_TAGLINE"), onClick = { setup = type }) }
            val disabled = vm.repo.settings.strings("Browse.disabledSources")
            val entries = catalog(state).filter { (it.name.contains(query, true) || it.altNames.any { name -> name.contains(query, true) }) && state.sources.none { source -> source.id == it.id && source.id !in disabled } && accepts(it.languages, selected) && it.contentRating in ratings }.sortedWith(compareBy<CatalogSource> { if (it.languages.size > 1) "" else it.languages.firstOrNull().orEmpty() }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            item { Text(tr("EXTERNAL"), Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            items(entries, key = { it.id }) { SourcePackageRow(vm, it) }
            item { SourceImportButton(vm) }
        }
    }
    if (filter) SourceBrowseFilter(vm, catalog(state).flatMap { it.languages }.distinct(), { filter = false })
    setup?.let { NativeServerSetup(vm, it, { setup = null }) }
}

@Composable private fun NativeServerSetup(vm: MonogatariViewModel, type: String, dismiss: () -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(Localization.string(context, type.uppercase())) }; var address by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }; var apiKey by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }; var failure by remember { mutableStateOf<String?>(null) }; var checked by remember { mutableStateOf(false) }
    var basic by remember { mutableStateOf(false) }; var keyLogin by remember { mutableStateOf(false) }; var oidc by remember { mutableStateOf(false) }; var anonymous by remember { mutableStateOf(false) }
    var method by remember { mutableStateOf("basic") }; var showBrowser by remember { mutableStateOf(false) }
    val id = remember { "$type-${UUID.randomUUID()}" }
    fun source() = SourceConfig(id, name.ifBlank { type.replaceFirstChar(Char::uppercase) }, type, address.trim().trimEnd('/'))
    fun run(block: suspend () -> Unit) { if (busy) return; scope.launch { busy = true; failure = null; try { block() } catch (e: Exception) { failure = e.message } finally { busy = false } } }
    fun checkServer() { run { val s = source(); require(s.baseUrl.toHttpUrl().username.isEmpty() && s.baseUrl.toHttpUrl().password.isEmpty()) { Localization.string(context, "INVALID_SERVER_URL") }; withContext(Dispatchers.IO) {
        when (type) {
            "komga" -> vm.repo.client.http.newCall(Request.Builder().url(vm.repo.client.url(s, "api/v2/users/me")).build()).execute().use { r -> val body = JSONObject(r.body?.string() ?: "{}"); require(r.code == 401 && body.optString("error") == "Unauthorized"); basic = true }
            "kavita" -> { require(vm.repo.client.request(s, "api/admin/exists").trim() == "true"); val config = runCatching { JSONObject(vm.repo.client.request(s, "api/settings/oidc")) }.getOrNull(); basic = config?.optBoolean("disablePasswordAuthentication") != true; oidc = config?.optBoolean("enabled") == true; keyLogin = s.baseUrl != "https://demo.kavitareader.com" }
            "suwayomi" -> { val login = vm.repo.client.suwayomi(s).loginType(s) ?: error(Localization.string(context, "INVALID_SERVER_URL")); anonymous = login == SuwayomiClient.LoginType.NONE; basic = !anonymous }
        }
        }; checked = true; method = if (basic) "basic" else if (anonymous) "anonymous" else if (oidc) "oidc" else "key"
    } }
    suspend fun saveSource() { val s = source(); require(vm.state.value.sources.none { it.name == s.name }) { Localization.string(context, "SOURCE_NAME_UNIQUE_ERROR") }; require(vm.state.value.sources.none { it.type == type && it.baseUrl.trimEnd('/') == s.baseUrl }) { Localization.string(context, "SERVER_URL_UNIQUE_ERROR") }
        val prefs = SourcePreferences(context, id); prefs.set("server", s.baseUrl); prefs.set("name", s.name)
        vm.repo.change { it.copy(sources = it.sources + s) }; dismiss()
    }
    fun login(cookies: Map<String, String>? = null) { run { try { withContext(Dispatchers.IO) {
        if (cookies != null) { check(vm.repo.client.kavita().webAuthenticate(source(), cookies)) { Localization.string(context, "LOGIN_FAILED") }; SourcePreferences(context, id).set("login_oidc", "logged_in") }
        else vm.repo.client.authenticate(source(), if (method == "key" || method == "anonymous") "" else username, if (method == "key") apiKey else if (method == "anonymous") "" else password)
        }; saveSource()
    } catch (e: Exception) { vm.repo.client.clearAuthentication(id); SourcePreferences(context, id).keys().forEach { SourcePreferences(context, id).set(it, null) }; throw e } } }
    CallerContextAlertDialog(dismiss, title = { Text(tr("${type.uppercase()}_SETUP")) }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(tr("${type.uppercase()}_INFO"), style = MaterialTheme.typography.bodySmall)
        TextButton({ val url = when(type) { "komga" -> "https://komga.org/docs/introduction"; "kavita" -> "https://wiki.kavitareader.com/getting-started/"; else -> "https://github.com/Suwayomi/Suwayomi-Server" }; context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }) { Text(tr("LEARN_MORE")) }
        OutlinedTextField(name, { name = it }, label = { Text(tr("SOURCE_NAME")) }, singleLine = true)
        OutlinedTextField(address, { address = it; checked = false; basic = false; oidc = false; anonymous = false; keyLogin = false }, label = { Text(tr("SERVER_URL")) }, placeholder = { Text(if (type == "suwayomi") "http://127.0.0.1:4567" else "https://…") }, singleLine = true)
        if (type in setOf("komga", "kavita")) { TextButton({ address = if (type == "komga") "https://demo.komga.org" else "https://demo.kavitareader.com"; username = if (type == "komga") "demo@komga.org" else "demouser"; password = if (type == "komga") "komga-demo" else "Demouser64"; checked = false; checkServer() }) { Text(tr("DEMO_${type.uppercase()}_SERVER")) }; if (address == if (type == "komga") "https://demo.komga.org" else "https://demo.kavitareader.com") Text(tr("DEMO_${type.uppercase()}_SERVER_INFO"), style = MaterialTheme.typography.bodySmall) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (checked) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { if (basic) FilterChip(method == "basic", { method = "basic" }, label = { Text(tr("BASIC_LOGIN")) }); if (keyLogin) FilterChip(method == "key", { method = "key" }, label = { Text(tr("API_KEY")) }); if (oidc) FilterChip(method == "oidc", { method = "oidc" }, label = { Text(tr("OIDC")) }) }
            when(method) { "basic" -> { OutlinedTextField(username, { username = it }, label = { Text(tr(if (type == "komga") "EMAIL" else "USERNAME")) }, singleLine = true); OutlinedTextField(password, { password = it }, label = { Text(tr("PASSWORD")) }, singleLine = true, visualTransformation = PasswordVisualTransformation()) }; "key" -> OutlinedTextField(apiKey, { apiKey = it }, label = { Text(tr("API_KEY")) }, visualTransformation = PasswordVisualTransformation()); "oidc" -> Text(tr("LOGIN_WEBVIEW_WARNING_TEXT"), style = MaterialTheme.typography.bodySmall) }
        }
        failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    } }, confirmButton = { TextButton({ if (!checked) checkServer() else if (method == "oidc") showBrowser = true else login() }, enabled = !busy && address.isNotBlank() && (!checked || method == "anonymous" || method == "oidc" || method == "key" && apiKey.isNotBlank() || method == "basic" && username.isNotBlank() && password.isNotEmpty())) { Text(tr(if (checked && method != "anonymous") "LOGIN" else "CONTINUE")) } }, dismissButton = { TextButton(dismiss) { Text(tr("CANCEL")) } })
    if (showBrowser) SourceWebsiteDialog(source().baseUrl + "/oidc/login?returnURL=aidoku://oidc-auth", { showBrowser = false }, { _, _, raw -> showBrowser = false; login(raw.split(';').mapNotNull { part -> part.trim().split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()) }, sourceId = id,
        onNavigation = { Uri.parse(it).let { uri -> uri.scheme == "aidoku" && uri.host == "oidc-auth" } }, callbackCookies = { _, cookies -> showBrowser = false; login(cookies.associate { it.name to it.value }) })
}
