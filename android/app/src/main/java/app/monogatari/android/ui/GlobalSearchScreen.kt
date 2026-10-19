package app.monogatari.android.ui

import android.util.Base64
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.monogatari.android.MonogatariViewModel
import app.monogatari.android.data.*
import app.monogatari.android.sources.sourceSelectedLanguages
import app.monogatari.android.sources.sourceContentRating
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject

private data class SearchGroup(val source: SourceConfig, val entries: List<Manga>, val more: Boolean, val error: String = "")
private class SearchMemory {
    var query = ""
    val submitted = mutableStateOf("")
    val loading = mutableStateOf(false)
    val groups = mutableStateOf<List<SearchGroup>>(emptyList())
    val task = mutableStateOf<Job?>(null)
    val generation = mutableLongStateOf(0L)
    var sourceSignature = ""
}
@Composable fun GlobalSearchScreen(vm: MonogatariViewModel, state: AppState) {
    val store = vm.repo.settings
    val memory = remember(vm) { vm.pageMemory("globalSearch", ::SearchMemory) }
    var query by rememberSaveable { mutableStateOf(memory.query) }; var submitted by memory.submitted
    var filters by remember { mutableStateOf(false) }; var loading by memory.loading
    var groups by memory.groups
    val scope = vm.viewModelScope; var task by memory.task
    var searchGeneration by memory.generation
    val rules = remember(state.settings) { try {
        when (val raw = store.value("Search.filters")) { is JSONArray -> raw.objects(); is String -> JSONArray(if (raw.trimStart().startsWith('[')) raw else Base64.decode(raw, Base64.DEFAULT).toString(Charsets.UTF_8)).objects(); else -> emptyList() }
    } catch (_: Exception) { emptyList() } }
    val installed = remember(state.sources) { vm.repo.client.runtimes.installed().associateBy { it.id } }
    fun languages(s: SourceConfig) = if (s.type == app.monogatari.android.sources.CopySource.TYPE) listOf("zh") else installed[s.id]?.let { sourceSelectedLanguages(vm.repo.context,it) } ?: listOf("multi")
    fun rating(s: SourceConfig) = if (s.type == app.monogatari.android.sources.CopySource.TYPE) 1 else installed[s.id]?.info?.let(::sourceContentRating) ?: 0
    val available = state.sources.filter { it.id !in store.strings("Browse.disabledSources") }
    val sources = available.filter { s -> rules.all { r ->
        val values = when (r.optString("id")) { "sources" -> listOf(s.id); "languages" -> languages(s); "contentRating" -> listOf(listOf("safe", "containsNsfw", "primarilyNsfw").getOrElse(rating(s)) { "safe" }); else -> emptyList() }
        val include = r.optJSONArray("included").strings(); val exclude = r.optJSONArray("excluded").strings()
        (include.isEmpty() || values.any { it in include }) && values.none { it in exclude }
    } }
    val sourceSignature = sources.map { it.json().toString() to languages(it) }.toString() + rules.toString()
    fun search(text: String, committed: Boolean, delayMillis: Long = 0) {
        val generation = ++searchGeneration
        task?.cancel(); query = text; memory.query = text; memory.sourceSignature = sourceSignature
        if (text.isBlank()) { groups = emptyList(); submitted = ""; loading = false; return }
        task = scope.launch {
            loading = true
            try {
                if (committed && !store.bool("General.incognitoMode")) store.put("Search.history", JSONArray((store.strings("Search.history").filterNot { it == text } + text).takeLast(20)))
                if (delayMillis > 0) delay(delayMillis)
                submitted = text; groups = emptyList()
                val limiter = Semaphore(3)
                coroutineScope { sources.map { source -> launch {
                    val result = limiter.withPermit { withContext(Dispatchers.IO) {
                        try {
                            if (source.type == "local") SearchGroup(source, state.manga.filter { it.sourceId == "local" && (it.title.contains(text, true) || it.author.contains(text, true)) }, false)
                            else vm.repo.client.session(source).search(text, 1).let { SearchGroup(source, it.first, it.second) }
                        } catch (e: CancellationException) { throw e } catch (e: Exception) { SearchGroup(source, emptyList(), false, e.message.orEmpty()) }
                    } }
                    ensureActive(); groups = groups + result
                } }.joinAll() }
            } finally { if (generation == searchGeneration) loading = false }
        }
    }
    LaunchedEffect(Unit) {
        val pending = vm.searchQuery.takeIf { it.isNotBlank() }
        if (pending != null) { search(pending, true); vm.searchQuery = "" }
        else if (query.isNotBlank() && (submitted != query && !loading || memory.sourceSignature != sourceSignature)) search(query, false)
    }
    Column {
        PageTitle(tr("SEARCH"), actions = { IconButton({ filters = true }) { Icon(Icons.Outlined.FilterList, tr("FILTERS"), tint = Pink) } })
        SearchField(query, tr("SEARCH"), onSearch = { search(query, true) }) { search(it, false, 3000) }
        TextButton({ search(query, true) }, Modifier.fillMaxWidth()) { Text(tr("SEARCH")) }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Pink)
        LazyColumn {
            if (query.isBlank()) {
                val history = store.strings("Search.history").asReversed()
                if (history.isEmpty()) item { EmptyPage(Icons.Outlined.Search, tr("NO_RECENT_SEARCHES"), tr("NO_RECENT_SEARCHES_TEXT")) }
                else {
                    item { Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { Text(tr("RECENTLY_SEARCHED"), Modifier.weight(1f)); TextButton({ vm.run { store.put("Search.history", JSONArray()) } }) { Text(tr("CLEAR")) } } }
                    items(history, key = { it }) { text -> SettingRow(text, Icons.Outlined.History, onClick = { search(text, true) }, trailing = { IconButton({ vm.run { store.put("Search.history", JSONArray(store.strings("Search.history") - text)) } }) { Icon(Icons.Outlined.Close, tr("DELETE"), tint = Pink) } }) }
                }
            } else groups.forEach { result ->
                if (result.entries.isNotEmpty() || result.error.isNotBlank()) {
                    item { Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(if (result.source.type == "local") tr("LOCAL_FILES") else result.source.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        TextButton({ vm.pendingSourceQuery = submitted; vm.navigate("source", result.source.id) }) { Text(tr("VIEW_MORE")) }
                    } }
                    if (result.error.isNotBlank()) item { Text(result.error, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
                    else item { LazyRow(contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(result.entries, key = { it.id }) { m -> Column(Modifier.width(112.dp).clickable { vm.open(m) }) {
                            Cover(m, vm.repo, Modifier.fillMaxWidth().height(168.dp)); Spacer(Modifier.height(6.dp)); Text(m.title, maxLines = 2, fontSize = 13.sp)
                        } }
                    } }
                }
            }
        }
    }
    if (filters) OptionSheet(tr("FILTERS"), { filters = false; if (query.isNotBlank()) search(query, false) }, footer = {
        SheetSecondaryButton(tr("CLEAR_FILTERS"), { vm.run { store.put("Search.filters", JSONArray()) } })
        SheetPrimaryButton(tr("DONE"), LocalSheetClose.current)
    }) {
        val options = listOf("contentRating" to listOf("safe" to tr("SAFE"), "containsNsfw" to tr("CONTAINS_NSFW"), "primarilyNsfw" to tr("PRIMARILY_NSFW")),
            "languages" to available.flatMap(::languages).distinct().sorted().map { it to if (it == "multi") tr("MULTI_LANGUAGE") else java.util.Locale.forLanguageTag(it).getDisplayLanguage(vm.repo.context.resources.configuration.locales[0]) },
            "sources" to available.map { it.id to if (it.type == "local") tr("LOCAL_FILES") else it.name })
        options.filter { it.second.isNotEmpty() }.forEach { (id, entries) ->
            val rule = rules.firstOrNull { it.optString("id") == id }
            val included = rule?.optJSONArray("included").strings(); val excluded = rule?.optJSONArray("excluded").strings()
            // Each tap cycles a value through included, excluded and unfiltered.
            SheetSection(tr(when (id) { "contentRating" -> "CONTENT_RATING"; "languages" -> "LANGUAGES"; else -> "SOURCES" })) { SheetChips {
                entries.forEach { (value, label) -> SheetChip(label, value in included, { vm.run {
                    val nextInclude = if (value in included) included - value else if (value in excluded) included else included + value
                    val nextExclude = if (value in included) excluded + value else excluded - value
                    val updated = rules.filterNot { it.optString("id") == id } + JSONObject().put("type", 4).put("id", id).put("included", JSONArray(nextInclude)).put("excluded", JSONArray(nextExclude))
                    store.put("Search.filters", JSONArray(updated))
                } }, excluded = value in excluded) }
            } }
        }
    }
}
