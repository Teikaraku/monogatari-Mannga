package app.monogatari.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.monogatari.android.MonogatariViewModel
import app.monogatari.android.dictionary.*
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException

@Composable fun VocabularyDetailsDialog(vm: MonogatariViewModel, repo: DictionaryRepository, original: VocabularyEntry, dismiss: () -> Unit) {
    var entry by remember(original.id) { mutableStateOf(original) }
    var editing by remember(original.id) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var sentence by remember(original.id) { mutableStateOf(TextFieldValue(original.sentence)) }
    var offset by remember(original.id) { mutableStateOf(original.clozeOffset) }
    var lookupWord by remember(original.id) { mutableStateOf(original.word) }
    var definitions by remember(original.id) { mutableStateOf<List<DictionaryEntry>>(emptyList()) }
    var loadingDefinition by remember { mutableStateOf(true) }
    var source by remember(original.id) { mutableStateOf(VocabularySource(null, null)) }
    var loadingSource by remember { mutableStateOf(true) }
    LaunchedEffect(original) { if (!editing) entry = original }
    LaunchedEffect(lookupWord, entry.reading) {
        loadingDefinition = true
        try {
            val found = repo.lookup(lookupWord)
            val matchingReading = found.filter { entry.reading.isBlank() || it.reading == entry.reading }
            definitions = (matchingReading.ifEmpty { found }).take(16)
        } finally { loadingDefinition = false }
    }
    LaunchedEffect(entry.mangaId, entry.chapterKey) {
        loadingSource = true
        source = VocabularySource(null, null)
        try { source = VocabularySource.resolve(vm.repo, entry) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { source = VocabularySource(null, null) }
        finally { loadingSource = false }
    }
    val foreground = MaterialTheme.colorScheme.onSurface
    val tokenColor = MaterialTheme.colorScheme.secondaryContainer
    CallerContextDialog(dismiss, DialogProperties(dismissOnBackPress = !editing, dismissOnClickOutside = !editing, usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.94f).fillMaxHeight(.9f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (editing) TextButton({ editing = false; sentence = TextFieldValue(entry.sentence); offset = entry.clozeOffset }) { Text(tr("CANCEL")) }
                    else IconButton(dismiss) { Icon(Icons.Outlined.Close, tr("DONE")) }
                    Text(entry.word, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    if (editing) TextButton({ vm.run { entry = repo.updateContext(entry.id, sentence.text, offset); editing = false } }) { Text(tr("DONE")) }
                    else Box {
                        IconButton({ menu = true }) { Icon(Icons.Outlined.MoreVert, tr("EDIT")) }
                        NativeMenu(menu, { menu = false }, listOf(
                            listOf(
                                MenuAction(tr("EDIT"), Icons.Outlined.Edit) { sentence = TextFieldValue(entry.sentence); offset = entry.clozeOffset; editing = true },
                                MenuAction("AnkiConnect", Icons.AutoMirrored.Outlined.Send) { vm.run { val id = sendToAnki(repo, entry); entry = repo.updateNote(entry.id, id); vm.message = "Anki #$id" } }),
                            listOf(MenuAction(tr("REMOVE_WORD"), Icons.Outlined.DeleteOutline, destructive = true) { vm.run { repo.removeVocabulary(entry.id); dismiss() } })))
                    }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!editing) {
                        Text(tr("DEFINITION"), style = MaterialTheme.typography.titleMedium)
                        if (loadingDefinition) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (definitions.isEmpty() && !loadingDefinition) Text(if (lookupWord == entry.word && entry.definition.isNotBlank()) entry.definition else tr("NO_RESULTS"))
                        definitions.forEach { definition ->
                            Text(definition.dictionary, style = MaterialTheme.typography.labelSmall)
                            if (definition.reading.isNotBlank()) Text(definition.reading, color = Pink)
                            if (definition.glossary.isNotBlank() && DictionaryGlossary.rich(definition.glossary)) {
                                DictionaryGlossaryView(definition.dictionaryId, definition.glossary, "#" + String.format("%06x", foreground.toArgb() and 0xffffff)) { lookupWord = it }
                            } else Text(definition.definition)
                            if (definition.frequency.isNotBlank()) Text(definition.frequency, style = MaterialTheme.typography.bodySmall)
                            if (definition.pitch.isNotBlank()) Text(tr("PITCH_DICTIONARIES") + ": " + definition.pitch, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (editing || entry.sentence.isNotBlank()) {
                        Text(tr("VOCAB_CONTEXT"), style = MaterialTheme.typography.titleMedium)
                        if (editing) OutlinedTextField(sentence, { next ->
                            VocabularyCloze.edit(sentence.text, entry.cloze, offset, next.text, sentence.selection.start, sentence.selection.end)?.let { edited ->
                                val selected = VocabularyCloze.selection(next.selection.start, next.selection.end, VocabularyCloze.range(edited.sentence, entry.cloze, edited.offset))
                                offset = edited.offset; sentence = next.copy(text = edited.sentence, selection = TextRange(selected.first, selected.second))
                            }
                        }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp), visualTransformation = object : VisualTransformation {
                            override fun filter(text: AnnotatedString): TransformedText = TransformedText(buildAnnotatedString {
                                append(text); VocabularyCloze.range(text.text, entry.cloze, offset)?.let { addStyle(SpanStyle(fontWeight = FontWeight.Bold, background = tokenColor), it.first, it.last + 1) }
                            }, OffsetMapping.Identity)
                        })
                        else Text(buildAnnotatedString {
                            append(entry.sentence); VocabularyCloze.range(entry.sentence, entry.cloze, entry.clozeOffset)?.let { addStyle(SpanStyle(fontWeight = FontWeight.Bold), it.first, it.last + 1) }
                        })
                    }
                    if (!editing && entry.imagePath.isNotBlank()) AsyncImage(entry.imagePath, tr("VOCAB_CONTEXT"), Modifier.fillMaxWidth().heightIn(max = 180.dp))
                    if (!editing && loadingSource) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (!editing && !loadingSource) source.manga?.takeIf { it.id == original.mangaId }?.let { manga ->
                        val originChapter = source.chapter?.takeIf { it.key == original.chapterKey }
                        Text(tr("VOCAB_SOURCE"), style = MaterialTheme.typography.titleMedium)
                        Row(Modifier.fillMaxWidth().clickable {
                            originChapter?.let { vm.read(manga, it, original.page) } ?: vm.open(manga); dismiss()
                        }, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AsyncImage(manga.cover, null, Modifier.width(56.dp).height(84.dp))
                            Column { Text(manga.title); originChapter?.let { Text(it.title.ifBlank { tr("CHAPTER") } + " · ${original.page + 1}", style = MaterialTheme.typography.bodySmall) } }
                        }
                        Row {
                            originChapter?.let { chapter -> TextButton({ vm.read(manga, chapter, original.page); dismiss() }) { Text(tr("READ")) } }
                            TextButton({ vm.open(manga); dismiss() }) { Text(tr("VIEW_SERIES")) }
                        }
                    }
                }
            }
        }
    }
}
