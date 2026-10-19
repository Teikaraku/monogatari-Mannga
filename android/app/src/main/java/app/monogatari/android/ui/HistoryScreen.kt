package app.monogatari.android.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.monogatari.android.MonogatariViewModel
import app.monogatari.android.data.*
import app.monogatari.android.library.Maintenance
import java.time.*
import java.time.format.*
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable fun NativeHistoryScreen(vm: MonogatariViewModel, state: AppState) {
    var query by rememberSaveable { mutableStateOf("") }; var clear by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Pair<Manga, Chapter>?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val zone = ZoneId.systemDefault()
    val sourcesKey = state.sources.map { it.id to it.configuration }
    LaunchedEffect(sourcesKey) {
        state.manga.filter { JSONObject(it.metadata).optBoolean("_androidMissingDetails") && it.sourceId != "local" }.forEach { manga ->
            if (state.sources.any { it.id == manga.sourceId }) try { vm.repo.refresh(manga) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { app.monogatari.android.sources.SourceLogStore.get(vm.repo.context).add("History details: ${e.message}", "ERROR") }
        }
    }
    val native = state.manga.flatMap { m -> m.chapters.filter { it.readAt > 0 }.map { m to it } }
    val orphans = JSONObject(state.records).optJSONArray("history").objects().mapNotNull { h ->
        val manga = state.manga.firstOrNull { it.sourceId == h.optString("sourceId") && it.key == h.optString("mangaId") } ?: return@mapNotNull null
        if (manga.chapters.any { it.key == h.optString("chapterId") } || h.optDouble("dateRead") <= 0) return@mapNotNull null
        val chapter = h.optJSONObject("_chapter")?.let(Chapter::parse) ?: Chapter(h.optString("chapterId"), h.optString("chapterId"), page = (h.optInt("progress") - 1).coerceAtLeast(0), total = h.optInt("total"), completed = h.optBoolean("completed"), readAt = (h.optDouble("dateRead") * 1000).toLong())
        manga to chapter
    }
    val history = (native + orphans).filter { (m, _) -> m.title.contains(query, true) || m.author.contains(query, true) }.sortedByDescending { it.second.readAt }
    LibraryLock("history", vm.repo.settings.bool("History.lockHistoryTab"), vm, onAccessChanged = { vm.historyAccess = it }) {
    val reselect: () -> Unit = { if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0) scope.launch { listState.animateScrollToItem(0) } else vm.continueRecentHistory() }
    SideEffect { vm.historyReselected = reselect }
    DisposableEffect(vm) { onDispose { vm.historyReselected = null; vm.historyAccess = false } }
    Column {
        PageTitle(tr("HISTORY"), actions = { IconButton({ clear = true }) { Icon(Icons.Outlined.DeleteOutline, tr("CLEAR_READ_HISTORY"), tint = Pink) } })
        SearchField(query, tr("SEARCH")) { query = it }
        Box(Modifier.weight(1f)) {
            if (history.isEmpty()) EmptyPage(Icons.Outlined.History, tr("NO_HISTORY"), tr("NO_HISTORY_TEXT"))
            else LazyColumn(state = listState, contentPadding = PaddingValues(horizontal = 16.dp + centeredInset(DetailWidth), vertical = 16.dp)) {
                history.groupBy { Instant.ofEpochMilli(it.second.readAt).atZone(zone).toLocalDate() }.forEach { (date, entries) ->
                    item { Text(relativeDay(vm.repo.context, date), Modifier.padding(vertical = 14.dp), fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                    items(entries, key = { JSONArray(listOf(it.first.sourceId, it.first.key, it.second.key)).toString() }) { (m, c) ->
                        Row(Modifier.fillMaxWidth().combinedClickable(onClick = { vm.read(m, c) }, onLongClick = { deleting = m to c }).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Cover(m, vm.repo, Modifier.width(53.dp).height(78.dp))
                            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                Text(m.title, fontWeight = FontWeight.SemiBold); Text(c.title, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(if (c.completed) tr("READ") else tr("PAGE_X_OF_X", c.page + 1, c.total), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton({ vm.open(m) }) { Icon(Icons.Outlined.MoreHoriz, tr("MANGA_INFO"), tint = Pink) }
                            IconButton({ deleting = m to c }) { Icon(Icons.Outlined.DeleteOutline, tr("DELETE"), tint = Pink) }
                        }
                    }
                }
            }
        }
    }
    if (clear) AlertDialog({ clear = false }, title = { Text(tr("CLEAR_READ_HISTORY")) }, text = { Text(tr("CLEAR_READ_HISTORY_TEXT")) }, confirmButton = {
        TextButton({ vm.run { Maintenance.clearHistory(vm.repo); clear = false } }) { Text(tr("CLEAR")) }
    }, dismissButton = { TextButton({ clear = false }) { Text(tr("CANCEL")) } })
    deleting?.let { (m, c) ->
        fun remove(all: Boolean) = vm.run { vm.repo.change { s ->
            fun matches(j: JSONObject) =
                j.optString("sourceId") == m.sourceId && j.optString("mangaId") == m.key && (all || j.optString("chapterId") == c.key)
            val records = JSONObject(app.monogatari.android.library.HistoryRecords.clear(s.records, ::matches)); val extras = JSONObject(app.monogatari.android.library.HistoryRecords.clear(s.backupExtras, ::matches))
            s.copy(manga = s.manga.map { manga -> if (manga.id != m.id) manga else { val chapters = manga.chapters.map { chapter ->
                if (all || chapter.key == c.key) { val metadata = JSONObject(chapter.metadata); metadata.remove("scrollPosition"); chapter.copy(page = 0, completed = false, readAt = 0, metadata = metadata.toString()) } else chapter
            }; val orphanLast = records.optJSONArray("history").objects().filter { it.optString("sourceId") == manga.sourceId && it.optString("mangaId") == manga.key }.maxOfOrNull { (it.optDouble("dateRead") * 1000).toLong() } ?: 0
                manga.copy(chapters = chapters, metadata = JSONObject(manga.metadata).put("lastRead", maxOf(chapters.maxOfOrNull { it.readAt } ?: 0, orphanLast)).toString())
            } }, records = records.toString(), backupExtras = extras.toString())
        }; deleting = null }
        AlertDialog({ deleting = null }, title = { Text(tr("CLEAR_READ_HISTORY")) }, text = { Column { Text(c.title); TextButton({ remove(true) }) { Text(tr("REMOVE_ALL_MANGA_HISTORY")) } } },
            confirmButton = { TextButton({ remove(false) }) { Text(tr("REMOVE")) } }, dismissButton = { TextButton({ deleting = null }) { Text(tr("CANCEL")) } })
    }
    }
}

fun relativeDay(context: android.content.Context, date: LocalDate): String {
    val today = LocalDate.now()
    val locale = context.resources.configuration.locales[0]
    return if (date == today || date == today.minusDays(1)) android.icu.text.RelativeDateTimeFormatter.getInstance(android.icu.util.ULocale.forLocale(locale)).format(
        if (date == today) android.icu.text.RelativeDateTimeFormatter.Direction.THIS else android.icu.text.RelativeDateTimeFormatter.Direction.LAST, android.icu.text.RelativeDateTimeFormatter.AbsoluteUnit.DAY)
    else date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
}
