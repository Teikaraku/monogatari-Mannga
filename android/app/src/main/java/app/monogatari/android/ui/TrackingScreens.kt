@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.monogatari.android.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.monogatari.android.MonogatariViewModel
import app.monogatari.android.data.Manga
import app.monogatari.android.data.SettingsStore
import app.monogatari.android.tracking.*
import coil.compose.AsyncImage
import java.util.UUID
import kotlinx.coroutines.launch

@Composable fun TrackingSettingsScreen(vm: MonogatariViewModel) {
    val repo = remember { TrackingRepository.get(vm.repo.context) }
    val tracking by repo.data.collectAsStateWithLifecycle()
    val app by vm.state.collectAsStateWithLifecycle()
    val settings = remember(vm.repo, app.settings) { SettingsStore(vm.repo) }
    var editing by remember { mutableStateOf<TrackerAccount?>(null) }
    var callback by remember { mutableStateOf("") }
    LaunchedEffect(app.sources) { repo.syncSourceAccounts(app.sources) }
    Column {
        PageTitle(tr("TRACKING"), vm::back, actions = { IconButton({ vm.run { repo.retryPending(); vm.message = Localization.string(vm.repo.context, "DONE") } }) { Icon(Icons.Outlined.Sync, tr("SYNC_WITH_TRACKER"), tint = Pink) } })
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp + centeredInset(DetailWidth), vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { Text(tr("TRACKERS"), style = MaterialTheme.typography.titleLarge); Text(tr("UPDATE_AFTER_READING_INFO"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(tr("UPDATE_AFTER_READING")); Switch(settings.bool("Tracking.updateAfterReading"), { vm.run { settings.put("Tracking.updateAfterReading", it) } }) } }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(tr("AUTO_SYNC_HISTORY")); Switch(settings.bool("Tracking.autoSyncFromTracker"), { vm.run { settings.put("Tracking.autoSyncFromTracker", it) } }) } }
            items(TrackerProvider.entries.filter { !it.server }) { p ->
                val a = tracking.accounts.firstOrNull { it.provider == p } ?: TrackerAccount(p.id, p)
                SettingRow(p.title, Icons.Outlined.AccountCircle, subtitle = if (a.connected) a.username else tr("LOGIN"), value = if (a.connected) tr("SETTINGS") else tr("LOGIN"), onClick = { editing = a })
            }
            item { Spacer(Modifier.height(14.dp)); Text(tr("ENHANCED_TRACKERS"), style = MaterialTheme.typography.titleLarge); Text(tr("ENHANCED_TRACKERS_INFO"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(tracking.accounts.filter { it.provider.server }, key = { it.id }) { a ->
                SettingRow(a.name, Icons.Outlined.Dns, subtitle = "${a.provider.title} · ${a.baseUrl}", onClick = { editing = a })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(tr("AUTO_SYNC_HISTORY")); Switch(a.autoSync, { vm.run { repo.saveAccount(a.copy(autoSync = it)) } }) }
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TrackerProvider.entries.filter { it.server }.forEach { p -> OutlinedButton({ editing = TrackerAccount("${p.id}:${UUID.randomUUID()}", p) }) { Text("+ ${p.title}") } }
                }
            }
            if (tracking.bindings.any { it.pending }) item {
                Text(pt("Pending sync", "待同步") + " (${tracking.bindings.count { it.pending }})", color = MaterialTheme.colorScheme.error)
                tracking.bindings.filter { it.pending }.forEach { Text("${it.title}：${it.error}", style = MaterialTheme.typography.bodySmall) }
            }
            item {
                Spacer(Modifier.height(14.dp)); Text(pt("OAuth callback", "授权回调"), style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(callback, { callback = it }, label = { Text(pt("Paste the full browser callback URL", "粘贴浏览器完整回调地址")) }, modifier = Modifier.fillMaxWidth())
                TextButton({ vm.run { vm.message = repo.handleOAuthCallback(Uri.parse(callback)); callback = "" } }, enabled = callback.isNotBlank()) { Text(tr("DONE")) }
            }
        }
    }
    editing?.let { TrackerAccountDialog(vm, repo, it) { editing = null } }
}

@Composable private fun TrackerAccountDialog(vm: MonogatariViewModel, repo: TrackingRepository, initial: TrackerAccount, dismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    var account by remember(initial) { mutableStateOf(initial) }
    var secret by remember { mutableStateOf("") }; var username by remember { mutableStateOf(initial.username) }; var password by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }; var advanced by remember { mutableStateOf(false) }
    AlertDialog(dismiss, title = { Text(account.name) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (account.connected) Text(tr("LOGIN") + ": ${account.username}", color = Pink)
            if (account.provider.server) {
                OutlinedTextField(account.name, { account = account.copy(name = it) }, label = { Text(tr("NAME")) }, singleLine = true, readOnly = account.sourceId.isNotEmpty())
                OutlinedTextField(account.baseUrl, { account = account.copy(baseUrl = it.trimEnd('/')) }, label = { Text(tr("SERVER_URL")) }, singleLine = true, readOnly = account.sourceId.isNotEmpty())
                if (account.sourceId.isEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(tr("VOLUMES")); Switch(account.useVolumes, { account = account.copy(useVolumes = it) }) }
                if (account.sourceId.isEmpty()) {
                    OutlinedTextField(username, { username = it }, label = { Text(tr("USERNAME")) }, singleLine = true)
                    OutlinedTextField(password, { password = it }, label = { Text(tr("PASSWORD")) }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    OutlinedTextField(token, { token = it }, label = { Text(pt("Bearer token (optional)", "Bearer 令牌（可选）")) }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    Button({ vm.run { repo.saveAccount(account); repo.loginServer(account, username, password, token); dismiss(); vm.message = Localization.string(vm.repo.context, "DONE") } }, enabled = account.baseUrl.isNotBlank()) { Text(tr("LOGIN")) }
                } else Text(pt("Credentials are shared with this source. Edit them in source settings.", "登录信息与漫画源共用，可在漫画源设置中修改。"))
            } else {
                Button({ vm.run {
                    repo.saveAccount(account, secret.takeIf { it.isNotEmpty() }); val url = repo.startOAuth(account); uriHandler.openUri(url); dismiss()
                } }, enabled = !vm.busy) { Text(pt("Browser OAuth login", "浏览器 OAuth 登录")) }
                OutlinedTextField(token, { token = it }, label = { Text(pt("Personal access token", "个人访问令牌")) }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                TextButton({ vm.run { repo.loginToken(account, token); dismiss(); vm.message = Localization.string(vm.repo.context, "DONE") } }, enabled = token.isNotBlank()) { Text(pt("Log in with token", "使用令牌登录")) }
                TextButton({ advanced = !advanced }) { Text(pt("OAuth application settings", "OAuth 应用设置")) }
                if (advanced || account.provider in listOf(TrackerProvider.BANGUMI, TrackerProvider.SHIKIMORI)) {
                    OutlinedTextField(account.clientId, { account = account.copy(clientId = it) }, label = { Text("Client ID") }, singleLine = true)
                    OutlinedTextField(secret, { secret = it }, label = { Text(pt("Client secret (leave blank to keep existing)", "Client Secret（保留现有值请留空）")) }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    OutlinedTextField(account.redirectUri, { account = account.copy(redirectUri = it) }, label = { Text(pt("Registered callback URL", "已注册的回调 URL")) }, singleLine = true)
                    Text(pt("Register this callback URL for your own OAuth application:", "使用自己的 OAuth 应用时注册此回调 URL：") + " aidoku-android://oauth/${account.provider.id}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(tr(if (account.sourceId.isNotEmpty()) "ENHANCED_TRACKERS" else "UPDATE_AFTER_READING")); Switch(account.autoSync, { account = account.copy(autoSync = it) }) }
            if (!account.provider.server) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(tr("SHOW_NSFW_RESULTS")); Switch(account.includeAdult, { account = account.copy(includeAdult = it) }) }
            if (account.connected && account.sourceId.isEmpty()) TextButton({ vm.run { repo.logout(account.id); dismiss() } }) { Text(tr("LOGOUT"), color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton({ vm.run { repo.saveAccount(account, secret.takeIf { it.isNotEmpty() }); dismiss() } }) { Text(tr("SAVE")) } }, dismissButton = { TextButton(dismiss) { Text(tr("DONE")) } })
}

@Composable fun MangaTrackingScreen(vm: MonogatariViewModel, manga: Manga) {
    val repo = remember { TrackingRepository.get(vm.repo.context) }
    val tracking by repo.data.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    var adding by remember { mutableStateOf(false) }; var editing by remember { mutableStateOf<TrackBinding?>(null) }; var removing by remember { mutableStateOf<TrackBinding?>(null) }
    LaunchedEffect(manga.sourceId) { repo.syncSourceAccounts(vm.repo.state.value.sources) }
    val bindings = tracking.bindings.filter { it.mangaId == manga.id }
    Column {
        PageTitle(tr("TRACKING"), vm::back, actions = { IconButton({ adding = true }) { Icon(Icons.Outlined.Add, tr("START_TRACKING"), tint = Pink) } })
        Text(manga.title, Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp + centeredInset(DetailWidth), vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (bindings.isEmpty()) item { EmptyPage(Icons.Outlined.Sync, tr("START_TRACKING"), pt("Log in, then search and select a matching item.", "连接账号后，搜索并选择对应条目。"), tr("START_TRACKING"), { adding = true }) }
            items(bindings, key = { it.id }) { b ->
                val a = tracking.accounts.firstOrNull { it.id == b.accountId }
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(a?.name ?: b.accountId, fontWeight = FontWeight.Bold); Text(b.title)
                    Text("${tr("TRACK_${b.state.status.name}")} · ${b.state.chapter} / ${b.state.totalChapters.takeIf { it > 0 } ?: "?"}" + if (a?.provider?.server == false) " · ${TrackingScores.display(a, b.state.score)}/${TrackingScores.scale(a)}" else "")
                    if (b.error.isNotEmpty()) Text(b.error + " · " + pt("Saved for retry", "已保存到待同步队列"), color = MaterialTheme.colorScheme.error)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton({ editing = b }) { Text(tr("EDIT")) }
                        Spacer(Modifier.weight(1f))
                        Box {
                            var cardMenu by remember { mutableStateOf(false) }
                            IconButton({ cardMenu = true }) { Icon(Icons.Outlined.MoreHoriz, tr("MORE"), tint = Pink) }
                            NativeMenu(cardMenu, { cardMenu = false }, listOf(
                                listOf(
                                    MenuAction(tr("RELOAD"), Icons.Outlined.Refresh) { vm.run { repo.refreshBinding(b) } },
                                    MenuAction(tr("SYNC_LOCAL_HISTORY"), Icons.Outlined.Sync) { vm.run { repo.syncBindingProgress(vm.repo, manga.id, b); vm.message = Localization.string(vm.repo.context, "DONE") } }),
                                if (a != null) listOf(MenuAction(tr("VIEW_ON_WEBSITE"), Icons.Outlined.Public) { uriHandler.openUri(repo.api.webUrl(a, b.remoteId)) }) else emptyList(),
                                listOf(MenuAction(tr("STOP_TRACKING"), Icons.Outlined.LinkOff, destructive = true) { removing = b })))
                        }
                    }
                } }
            }
            item { TextButton({ vm.navigate("tracking") }) { Text(tr("TRACKERS")) } }
        }
    }
    if (adding) TrackerSearchDialog(vm, repo, manga) { adding = false }
    editing?.let { binding -> tracking.accounts.firstOrNull { it.id == binding.accountId }?.let { TrackerEditDialog(vm, repo, binding, it) { editing = null } } }
    removing?.let { b -> AlertDialog({ removing = null }, title = { Text(tr("STOP_TRACKING")) }, text = { Text(pt("Remove tracking from this manga? Your remote collection is retained.", "从此漫画移除绑定？远端收藏会保留。")) },
        confirmButton = { TextButton({ vm.run { repo.unbind(b); removing = null } }) { Text(tr("STOP_TRACKING")) } }, dismissButton = { TextButton({ removing = null }) { Text(tr("CANCEL")) } }) }
}

@Composable private fun TrackerSearchDialog(vm: MonogatariViewModel, repo: TrackingRepository, manga: Manga, dismiss: () -> Unit) {
    val d by repo.data.collectAsStateWithLifecycle()
    val accounts = d.accounts.filter { it.connected && (!it.provider.server || it.sourceId.isEmpty() || it.sourceId == manga.sourceId) }
    var selected by remember { mutableStateOf(accounts.firstOrNull()?.id.orEmpty()) }; var query by remember { mutableStateOf(manga.title) }; var results by remember { mutableStateOf<List<TrackSearchResult>>(emptyList()) }
    var error by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    OptionSheet(tr("START_TRACKING"), dismiss, subtitle = manga.title, scrollable = false) {
        if (accounts.isEmpty()) SheetActions(listOf(SheetActionItem(tr("LOGIN"), Icons.Outlined.Login) { dismiss(); vm.navigate("tracking") }))
        else {
            SheetChips(Modifier.padding(bottom = 8.dp)) { accounts.forEach { a -> SheetChip(a.name, selected == a.id, { selected = a.id; results = emptyList() }, checkmark = false) } }
            val search = { scope.launchTracking({ busy = true; error = "" }, { busy = false }, { error = it }) { results = repo.search(selected, query) } }
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), label = { Text(tr("SERIES_TITLE")) }, singleLine = true,
                trailingIcon = { IconButton({ search() }, enabled = selected.isNotEmpty() && !busy && query.isNotBlank()) { Icon(Icons.Outlined.Search, tr("SEARCH"), tint = Pink) } },
                keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search), keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { if (selected.isNotEmpty() && !busy && query.isNotBlank()) search() }))
            val a = accounts.firstOrNull { it.id == selected }
            if (a?.sourceId == manga.sourceId) TextButton({ vm.run { bindTrackedManga(vm, repo, manga, selected, TrackSearchResult(manga.key, manga.title)); dismiss() } }, Modifier.padding(horizontal = 8.dp)) { Text(tr("TRACK")) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp))
            if (error.isNotBlank()) Text(error, Modifier.padding(horizontal = 20.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.error)
            LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 6.dp)) { items(results, key = { it.id }) { result ->
                Row(Modifier.fillMaxWidth().clickable { vm.run { bindTrackedManga(vm, repo, manga, selected, result); dismiss() } }.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (result.cover.isNotBlank()) AsyncImage(result.cover, result.title, Modifier.width(46.dp).height(66.dp).clip(RoundedCornerShape(6.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(result.title, fontWeight = FontWeight.Medium, maxLines = 2)
                        if (result.format.isNotBlank()) Text(result.format, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (result.tracked) Text(pt("Already tracked", "已收藏"), fontSize = 12.sp, color = Pink)
                    }
                }
            } }
        }
    }
}
private suspend fun bindTrackedManga(vm: MonogatariViewModel, repo: TrackingRepository, manga: Manga, accountId: String, result: TrackSearchResult) {
    repo.bind(manga, accountId, result)
    val binding = repo.data.value.bindings.first { it.mangaId == manga.id && it.accountId == accountId }
    if (SettingsStore(vm.repo).bool("Tracking.autoSyncFromTracker") || repo.data.value.accounts.first { it.id == accountId }.provider.server) repo.syncBindingProgress(vm.repo, manga.id, binding)
}
@Composable private fun TrackerEditDialog(vm: MonogatariViewModel, repo: TrackingRepository, binding: TrackBinding, account: TrackerAccount, dismiss: () -> Unit) {
    var state by remember { mutableStateOf(binding.state) }; var chapter by remember { mutableStateOf(state.chapter.toString()) }; var volume by remember { mutableStateOf(state.volume.toString()) }
    var score by remember { mutableStateOf(TrackingScores.display(account, state.score)) }; var offset by remember { mutableStateOf(binding.chapterOffset.toString()) }
    AlertDialog(dismiss, title = { Text(account.name) }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!account.provider.server) {
            MenuPicker(tr("STATUS"), TrackStatus.entries.map { it to tr("TRACK_${it.name}") }, state.status) { state = state.copy(status = it) }
            val options = TrackingScores.options(account)
            if (options.isEmpty()) OutlinedTextField(score, { score = it }, label = { Text("${tr("SCORE")} / ${TrackingScores.scale(account)}") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
            else MenuPicker(tr("SCORE"), options.map { (label, value) -> value to label }, state.score) { value -> state = state.copy(score = value); score = TrackingScores.display(account, value) }
        }
        OutlinedTextField(chapter, { chapter = it }, label = { Text(tr("CHAPTERS")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
        OutlinedTextField(volume, { volume = it }, label = { Text(tr("VOLUMES")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
        if (account.provider.supportsDates) {
            OutlinedTextField(state.startDate, { state = state.copy(startDate = it) }, label = { Text(pt("Started yyyy-MM-dd (blank to clear)", "开始日期 yyyy-MM-dd（留空清除）")) }, singleLine = true)
            OutlinedTextField(state.finishDate, { state = state.copy(finishDate = it) }, label = { Text(pt("Finished yyyy-MM-dd (blank to clear)", "结束日期 yyyy-MM-dd（留空清除）")) }, singleLine = true)
        }
        OutlinedTextField(offset, { offset = it }, label = { Text(pt("Chapter offset (source chapter + offset)", "章节偏移（本地章节号 + 偏移）")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
    } }, confirmButton = { TextButton({ vm.run {
        val next = state.copy(chapter = chapter.toDouble(), volume = volume.toInt(), score = if (TrackingScores.options(account).isEmpty()) TrackingScores.parse(account, score) else state.score).validated()
        val updated = binding.copy(chapterOffset = offset.toInt()); repo.setOffset(binding, updated.chapterOffset); repo.update(updated, next); dismiss()
    } }) { Text(tr("SAVE")) } }, dismissButton = { TextButton(dismiss) { Text(tr("CANCEL")) } })
}
internal fun kotlinx.coroutines.CoroutineScope.launchTracking(start: () -> Unit, finish: () -> Unit, error: (String) -> Unit, block: suspend () -> Unit) = launch {
    start(); try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { error(e.message ?: "Operation failed") } finally { finish() }
}
