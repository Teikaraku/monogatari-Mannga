package app.monogatari.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.monogatari.android.MonogatariViewModel
import app.monogatari.android.data.*
import app.monogatari.android.downloads.*
import app.monogatari.android.reader.ChapterListing

@Composable fun DownloadManagerScreen(vm: MonogatariViewModel, state: AppState) {
    val service = remember { DownloadService.get(vm.repo.context) }
    val queue by service.entries.collectAsState()
    var selected by remember { mutableStateOf<Pair<String, String>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var cellularResume by remember { mutableStateOf<String?>(null) }
    fun resume(id:String?=null) {
        val needsWifi=queue.any { (id==null || it.id==id) && !it.allowCellular && it.status in setOf("queued","paused","failed") }
        if(needsWifi && service.wifiOnly && !DownloadConnectivity.isWifi(vm.repo.context))cellularResume=id.orEmpty()
        else vm.run { service.resume(id) }
    }
    var wifi by remember { mutableStateOf(service.wifiOnly) }; var compress by remember { mutableStateOf(service.compress) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.comicbook+zip")) { uri ->
        selected?.let { (manga, chapter) -> if (uri != null) vm.run { service.export(manga, chapter, uri) } }
    }
    val completed = state.manga.filter { it.sourceId != "local" }.map { it.copy(chapters=it.chapters+ChapterListing.orphans(state,it)) }.filter { it.chapters.any(Chapter::downloaded) }
    Column(Modifier.fillMaxSize()) {
    PageTitle(tr("DOWNLOADS"), vm::back, actions = {
        IconButton({ vm.run { service.pause() } }) { Icon(Icons.Outlined.Pause, tr("PAUSE")) }
        IconButton({ resume() }) { Icon(Icons.Outlined.PlayArrow, tr("RESUME")) }
        IconButton({ confirmDelete = true }) { Icon(Icons.Outlined.DeleteSweep, tr("REMOVE_ALL_DOWNLOADS")) }
    })
    LazyColumn(Modifier.fillMaxSize()) {
        item { Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("ONLY_DOWNLOAD_ON_WIFI"), Modifier.weight(1f)); Switch(wifi, { wifi = it; service.wifiOnly = it })
        } }
        item { Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("COMPRESS_DOWNLOADS"), Modifier.weight(1f)); Switch(compress, { compress = it; service.compress = it })
        } }
        if (queue.any { it.status != "finished" }) item { Text(tr("DOWNLOAD_QUEUE"), Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium) }
        items(queue.filter { it.status != "finished" }, key = { it.id }) { item ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall)
                Text(when (item.status) { "running" -> tr("DOWNLOADING"); "paused" -> tr("PAUSED"); "failed" -> tr("DOWNLOAD_INCOMPLETE"); else -> tr("PROCESSING_QUEUE") }, style = MaterialTheme.typography.bodySmall)
                if (item.total > 0) { LinearProgressIndicator(progress = { item.progress.toFloat() / item.total }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)); Text("${item.progress} / ${item.total}") }
                if (item.error.isNotBlank()) Text(item.error, color = MaterialTheme.colorScheme.error)
                Row {
                    if (item.status in setOf("running", "queued")) TextButton({ vm.run { service.pause(item.id) } }) { Text(tr("PAUSE")) }
                    else TextButton({ resume(item.id) },enabled=item.status!="migrating") { Text(tr("RETRY")) }
                    TextButton({ vm.run { service.cancel(item.id) } }) { Text(tr("CANCEL")) }
                }
            }
            HorizontalDivider()
        }
        items(completed, key = Manga::id) { manga ->
            Column(Modifier.padding(16.dp)) {
                Text(manga.title, Modifier.clickable { vm.open(manga) }, style = MaterialTheme.typography.titleMedium)
                manga.chapters.filter(Chapter::downloaded).forEach { chapter -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(chapter.title, Modifier.weight(1f).clickable { vm.read(manga, chapter) })
                    IconButton({ selected = manga.id to chapter.key; export.launch("${manga.title.take(40)}-${chapter.number}.cbz") }) { Icon(Icons.Outlined.IosShare, tr("EXPORT")) }
                    IconButton({ vm.run { service.remove(manga.id, chapter.key) } }) { Icon(Icons.Outlined.Delete, tr("DELETE")) }
                } }
            }
        }
        if (completed.isEmpty() && queue.none { it.status != "finished" }) item { EmptyPage(Icons.Outlined.Download, tr("NO_DOWNLOADS"), tr("NO_DOWNLOADS_TEXT")) }
    } }
    if (confirmDelete) AlertDialog({ confirmDelete = false }, title = { Text(tr("REMOVE_ALL_DOWNLOADS")) },
        text = { Text(tr("REMOVE_ALL_DOWNLOADS_CONFIRM")) }, confirmButton = { TextButton({ confirmDelete = false; vm.run { service.deleteAll() } }) { Text(tr("DELETE")) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text(tr("CANCEL")) } })
    cellularResume?.let { id ->AlertDialog({cellularResume=null},title={Text(tr("NO_WIFI_ALERT_TITLE"))},text={Text(tr("DOWNLOAD_ANYWAY_MESSAGE"))},
        confirmButton={TextButton({cellularResume=null;vm.run{service.resume(id.takeIf(String::isNotEmpty),allowCellular=true)}}){Text(tr("DOWNLOAD"))}},
        dismissButton={TextButton({cellularResume=null}){Text(tr("CANCEL"))}}) }
}
