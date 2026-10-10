package app.monogatari.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.monogatari.android.MonogatariViewModel
import app.monogatari.android.data.*
import app.monogatari.android.library.MangaMigration

@Composable fun MigrationScreen(vm: MonogatariViewModel, state: AppState) {
    val oldIds = vm.key.split('\n').filter(String::isNotBlank)
    var copy by remember { mutableStateOf(false) }
    var selectedSource by remember { mutableStateOf<String?>(null) }
    var position by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf(state.manga.firstOrNull { it.id == oldIds.firstOrNull() }?.title.orEmpty()) }
    var targets by remember { mutableStateOf<Map<String, Manga>>(emptyMap()) }
    var confirm by remember { mutableStateOf(false) }
    Column {
        PageTitle(tr("MIGRATE"), vm::back)
        if (selectedSource == null) LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp + centeredInset(DetailWidth), vertical = 16.dp)) {
            item { SettingRow(tr("COPY"), trailing = { Switch(copy, { copy = it }) }) }
            items(state.sources.filter { it.type != "local" }) { source -> SettingRow(source.name, onClick = {
                selectedSource = source.id; vm.search(source.id, query)
            }) }
        } else {
            Text("${position + 1} / ${oldIds.size} · ${state.manga.firstOrNull { it.id == oldIds.getOrNull(position) }?.title.orEmpty()}", Modifier.padding(16.dp))
            SearchField(query, tr("SEARCH")) { query = it }
            TextButton({ vm.search(selectedSource, query) }) { Text(tr("SEARCH")) }
            MangaCollection(vm.results.filter { it.id !in oldIds }, vm.repo, { destination ->
                targets = targets + (oldIds[position] to destination)
                if (position < oldIds.lastIndex) {
                    position++; query = state.manga.firstOrNull { it.id == oldIds[position] }?.title.orEmpty(); vm.search(selectedSource, query)
                } else confirm = true
            }, Modifier.weight(1f))
        }
    }
    if (confirm) AlertDialog({ confirm = false }, title = { Text(tr(if (copy) "COPY" else "MIGRATE")) }, text = {
        Column { targets.forEach { (id, target) -> Text("${state.manga.firstOrNull { it.id == id }?.title} → ${target.title}") } }
    }, confirmButton = { TextButton({ vm.run {
        targets.forEach { (old, new) -> MangaMigration.migrate(vm.repo, old, new, copy) }
        confirm = false; vm.back(); vm.message = "迁移完成"
    } }, enabled = !vm.busy) { Text(tr("CONTINUE")) } }, dismissButton = { TextButton({ confirm = false }) { Text(tr("CANCEL")) } })
}
