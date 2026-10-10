@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.monogatari.android.ui

import app.monogatari.android.AppBrand

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.monogatari.android.MonogatariViewModel
import app.monogatari.android.data.AppRepository
import app.monogatari.android.data.SettingsStore
import app.monogatari.android.data.SecretStore
import app.monogatari.android.dictionary.*
import app.monogatari.android.reader.ReaderImageGeometry
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@Composable fun DictionarySettingsScreen(vm: MonogatariViewModel) {
    val repo = remember { DictionaryRepository.get(vm.repo.context) }
    val dictionaries by repo.dictionaries.collectAsStateWithLifecycle(); val app by vm.state.collectAsStateWithLifecycle()
    val settings = remember(vm.repo, app.settings) { SettingsStore(vm.repo) }
    var lookup by remember { mutableStateOf(false) }; var removing by remember { mutableStateOf<DictionaryInfo?>(null) }; var progress by remember { mutableIntStateOf(0) }; var anki by remember { mutableStateOf(false) }
    var kind by remember { mutableStateOf("term") }
    var ocrLanguages by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { repo.load() }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.run { val dictionary = repo.importDictionary(uri) { progress = it }; progress = 0; vm.message = "${dictionary.title}：${dictionary.terms}" } }
    Column {
        PageTitle(tr("DICTIONARIES"), vm::back, actions = {
            IconButton({ lookup = true }) { Icon(Icons.Outlined.Search, tr("SEARCH"), tint = Pink) }
            IconButton({ import.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) }) { Icon(Icons.Outlined.Add, tr("IMPORT"), tint = Pink) }
        })
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp + centeredInset(DetailWidth), vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(tr("DICTIONARY_LOOKUP")); Switch(settings.bool("Dictionary.enable"), { vm.run { settings.put("Dictionary.enable", it) } }) }; Text(tr("DICTIONARY_SETTINGS_TEXT"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { Text(tr("ABOUT_DICTIONARIES_TEXT"), style = MaterialTheme.typography.bodySmall) }
            item { Row(Modifier.horizontalScroll(rememberScrollState())) { listOf("term" to "TERM_DICTIONARIES", "frequency" to "FREQUENCY_DICTIONARIES", "pitch" to "PITCH_DICTIONARIES", "kanji" to "KANJI_DICTIONARIES").forEach { (type, key) -> FilterChip(kind == type, { kind = type }, { Text(tr(key)) }) } } }
            if (progress > 0) item { Text("${tr("IMPORTING_DICTIONARY")} $progress"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
            items(dictionaries.filter { kind in it.kinds.split(',') }, key = { it.id }) { info -> Card { Column(Modifier.padding(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Column(Modifier.weight(1f)) { Text(info.title, fontWeight = FontWeight.Bold); Text("${info.revision} · ${info.terms} · ${info.author}", style = MaterialTheme.typography.bodySmall) }; Switch(info.enabled, { vm.run { repo.configure(info, enabled = it) } }) }
                if (info.description.isNotEmpty()) Text(info.description, style = MaterialTheme.typography.bodySmall)
                // Order, category and removal live in one menu, like the iOS dictionary list.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val categories = listOf("none" to tr("NONE"), "monolingual" to pt("Monolingual", "单语"), "bilingual" to pt("Bilingual", "双语"), "exclude" to pt("Excluded", "排除"))
                    Text(categories.firstOrNull { it.first == info.category }?.second.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box {
                        var dictionaryMenu by remember { mutableStateOf(false) }
                        IconButton({ dictionaryMenu = true }) { Icon(Icons.Outlined.MoreHoriz, tr("MORE"), tint = Pink) }
                        NativeMenu(dictionaryMenu, { dictionaryMenu = false }, listOf(
                            listOf(
                                MenuAction(pt("Move up", "上移"), Icons.Outlined.KeyboardArrowUp) { vm.run { repo.move(info, -1) } },
                                MenuAction(pt("Move down", "下移"), Icons.Outlined.KeyboardArrowDown) { vm.run { repo.move(info, 1) } }),
                            listOf(MenuSubmenu(tr("CATEGORY"), Icons.Outlined.Category, categories.firstOrNull { it.first == info.category }?.second.orEmpty(), listOf(
                                categories.map { (category, label) -> MenuAction(label, checked = info.category == category) { vm.run { repo.configure(info, category = category) } } }))),
                            listOf(MenuAction(tr("REMOVE"), Icons.Outlined.DeleteOutline, destructive = true) { removing = info })))
                    }
                }
            } } }
            item { OutlinedButton({ import.launch(arrayOf("application/zip", "application/octet-stream")) }, Modifier.fillMaxWidth()) { Text(tr("IMPORT")) } }
            item { TextButton({ vm.run { val updates = repo.checkUpdates(); updates.forEach { repo.updateDictionary(it) }; vm.message = "${updates.size} ${Localization.string(vm.repo.context, "DICTIONARY_UPDATES")}" } }) { Text(tr("UPDATE_NOW")) } }
            if (settings.bool("Dictionary.restrictOCRLanguages")) item { TextButton({ ocrLanguages = true }) { Text(tr("DICTIONARY_OCR_LANGUAGES")) }; Text(settings.strings("Dictionary.restrictedOCRLanguages").joinToString { java.util.Locale.forLanguageTag(it).displayLanguage.ifBlank { it } }) }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(tr("DICTIONARY_TEXT_OVERLAY_MODE")); Switch(settings.bool("Dictionary.textOverlayMode"), { vm.run { settings.put("Dictionary.textOverlayMode", it) } }) } }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(tr("DICTIONARY_RESTRICT_OCR_LANGUAGES")); Switch(settings.bool("Dictionary.restrictOCRLanguages"), { vm.run { settings.put("Dictionary.restrictOCRLanguages", it) } }) } }
            item { Text(tr("DICTIONARY_RESTRICT_OCR_LANGUAGES_INFO"), style = MaterialTheme.typography.bodySmall) }
            item { Text(tr("DICTIONARY_OVERLAY_PADDING")); Slider(settings.double("Dictionary.overlayPadding").toFloat(), { vm.run { settings.put("Dictionary.overlayPadding", it.toDouble()) } }, valueRange = 0f..20f) }
            item { Text(tr("DICTIONARY_OVERLAY_TEXT_SCALE")); Slider(settings.double("Dictionary.overlayTextScaleMultiplier").toFloat(), { vm.run { settings.put("Dictionary.overlayTextScaleMultiplier", it.toDouble()) } }, valueRange = 0.5f..3f) }
            item { Text(tr("DISPLAY_MODE")); Row { listOf("default" to "DEFAULT_DISPLAY_MODE", "fullWidth" to "FULL_WIDTH").forEach { (mode, label) -> FilterChip(settings.string("Dictionary.displayMode") == mode, { vm.run { settings.put("Dictionary.displayMode", mode) } }, { Text(tr(label)) }) } } }
            item { Text(tr("DICTIONARY_POPUP_WIDTH")); Slider(settings.double("Dictionary.popupWidth").toFloat(), { vm.run { settings.put("Dictionary.popupWidth", it.toDouble()) } }, valueRange = 240f..900f) }
            item { Text(tr("DICTIONARY_POPUP_HEIGHT")); Slider(settings.double("Dictionary.popupHeight").toFloat(), { vm.run { settings.put("Dictionary.popupHeight", it.toDouble()) } }, valueRange = 350f..1000f) }
            item { MenuPicker(tr("UPDATE_INTERVAL"), listOf("never" to "NEVER", "daily" to "DAILY", "weekly" to "WEEKLY", "monthly" to "MONTHLY").map { (value, label) -> value to tr(label) }, settings.string("Dictionary.updateInterval")) { value -> vm.run { settings.put("Dictionary.updateInterval", value); repo.load() } } }
            item { MenuPicker(tr("LOOKUP_GESTURE"), listOf("single-tap" to "SINGLE_TAP", "long-press" to "LONG_PRESS").map { (key, label) -> key to tr(label) }, settings.string("Dictionary.lookupGesture")) { key -> vm.run { settings.put("Dictionary.lookupGesture", key) } } }
            item { TextButton({ vm.navigate("vocabulary") }) { Text(tr("VOCABULARY")) }; TextButton({ anki = true }) { Text("AnkiConnect") } }
        }
    }
    if (lookup) ReaderDictionaryOverlay("", "", 0, "", "", onDismiss = { lookup = false })
    if (ocrLanguages) OcrLanguageWhitelistDialog(settings.strings("Dictionary.restrictedOCRLanguages"), { selected -> vm.run { settings.put("Dictionary.restrictedOCRLanguages", JSONArray(selected)); ocrLanguages = false } }, { ocrLanguages = false })
    if (anki) AnkiSettingsDialog(repo) { anki = false }
    removing?.let { info -> AlertDialog({ removing = null }, title = { Text(tr("REMOVE_DICTIONARY")) }, text = { Text(info.title) }, confirmButton = { TextButton({ vm.run { repo.removeDictionary(info.id); removing = null } }) { Text(tr("REMOVE")) } }, dismissButton = { TextButton({ removing = null }) { Text(tr("CANCEL")) } }) }
}

@Composable fun VocabularyScreen(vm: MonogatariViewModel) {
    val repo = remember { DictionaryRepository.get(vm.repo.context) }; val entries by repo.vocabulary.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }; var editing by remember { mutableStateOf<VocabularyEntry?>(null) }; var clear by remember { mutableStateOf(false) }; var format by remember { mutableStateOf("apkg") }
    LaunchedEffect(Unit) { repo.load() }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> if (uri != null) vm.run {
        val output = vm.repo.context.contentResolver.openOutputStream(uri) ?: error("Cannot create export file")
        output.use { if (format == "apkg") AnkiExporter.exportApkg(vm.repo.context, entries, it) else it.write(AnkiExporter.csv(entries).toByteArray()) }
        vm.message = Localization.string(vm.repo.context, "EXPORT")
    } }
    Column {
        PageTitle(tr("VOCABULARY"), vm::back, actions = { IconButton({ clear = true }) { Icon(Icons.Outlined.DeleteOutline, tr("CLEAR"), tint = Pink) } })
        SearchField(query, tr("SEARCH")) { query = it }
        Row(Modifier.padding(horizontal = 16.dp)) { TextButton({ format = "apkg"; export.launch("${AppBrand.NAME}-vocabulary.apkg") }, enabled = entries.isNotEmpty()) { Text("Anki APKG") }; TextButton({ format = "csv"; export.launch("${AppBrand.NAME}-vocabulary.csv") }, enabled = entries.isNotEmpty()) { Text("CSV") } }
        if (entries.isEmpty()) EmptyPage(Icons.Outlined.Translate, tr("NO_VOCABULARY"), tr("NO_VOCABULARY_TEXT"))
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp + centeredInset(DetailWidth), vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { items(entries.filter { it.word.contains(query, true) || it.reading.contains(query, true) }, key = { it.id }) { e ->
            Card(Modifier.fillMaxWidth().clickable { editing = e }) { Column(Modifier.padding(14.dp)) { Text(e.word, style = MaterialTheme.typography.titleLarge); if (e.reading.isNotBlank()) Text(e.reading, color = Pink); Text(e.sentence, maxLines = 2); Text(e.definition, maxLines = 3, style = MaterialTheme.typography.bodySmall) } }
        } }
    }
    editing?.let { selected -> entries.firstOrNull { it.id == selected.id }?.let { VocabularyEditDialog(vm, repo, it) { editing = null } } }
    if (clear) AlertDialog({ clear = false }, title = { Text(tr("CLEAR_VOCABULARY_LIST")) }, text = { Text(tr("CLEAR_VOCABULARY_LIST_TEXT")) }, confirmButton = { TextButton({ vm.run { repo.clearVocabulary(); clear = false } }) { Text(tr("CLEAR")) } }, dismissButton = { TextButton({ clear = false }) { Text(tr("CANCEL")) } })
}
@Composable private fun VocabularyEditDialog(vm: MonogatariViewModel, repo: DictionaryRepository, original: VocabularyEntry, dismiss: () -> Unit) {
    VocabularyDetailsDialog(vm, repo, original, dismiss)
}

/** Called by the native reader with its current, materialized page URI or actual visible text. */
@Composable fun ReaderDictionaryOverlay(mangaId: String, chapterKey: String, page: Int, pageUri: String, text: String, initialPoint: Pair<Float, Float>? = null, sourceLanguage: String? = null, onDismiss: () -> Unit) {
    val context = LocalContext.current; val repo = remember { DictionaryRepository.get(context) }; val appRepo = remember { AppRepository.get(context) }; val settings = remember { SettingsStore(appRepo) }
    val appState by appRepo.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var manualLanguage by remember(pageUri, mangaId, chapterKey) { mutableStateOf<String?>(null) }
    val manga = appState.manga.firstOrNull { it.id == mangaId }
    val effectiveSourceLanguage = sourceLanguage ?: ReaderImageGeometry.language(manga?.chapters?.firstOrNull { it.key == chapterKey }?.language.orEmpty(), appState.sources.firstOrNull { it.id == manga?.sourceId }?.configuration, manga?.language.orEmpty())
    val language = OcrLanguages.request(settings.bool("Dictionary.restrictOCRLanguages"), effectiveSourceLanguage, manualLanguage)
    var sourceText by remember { mutableStateOf(text) }; var ocr by remember { mutableStateOf<OcrPage?>(null) }; var query by remember { mutableStateOf("") }; var results by remember { mutableStateOf<List<DictionaryEntry>>(emptyList()) }
    var sentence by remember { mutableStateOf(text) }; var selectedOffset by remember { mutableStateOf<Int?>(null) }; var busy by remember { mutableStateOf(false) }; var message by remember { mutableStateOf("") }
    var region by remember { mutableStateOf<OcrRegion?>(null) }
    fun lookup(value: String) { query = value; selectedOffset = sentence.indexOf(value).takeIf { value.isNotEmpty() && it >= 0 }; scope.launchTracking({ busy = true; message = "" }, { busy = false }, { message = it }) { results = repo.lookup(value) } }
    fun lookupOffset(value: String, offset: Int) { sentence = value; selectedOffset = offset; scope.launchTracking({ busy = true; message = "" }, { busy = false }, { message = it }) { val (word, matches) = repo.lookupAt(value, offset); query = word; results = matches } }
    LaunchedEffect(pageUri, language, effectiveSourceLanguage, initialPoint) {
        repo.load()
        if (pageUri.isNotBlank()) { busy = true; try {
            ocr = null; region = null; query = ""; results = emptyList(); message = ""
            ocr = OcrEngine.recognize(context, Uri.parse(pageUri), language, effectiveSourceLanguage); sourceText = ocr!!.text; sentence = sourceText
            initialPoint?.let { point -> OcrLayout.find(ocr!!.observations, point.first, point.second)?.let { hit -> region = hit.contextBounds; sentence = hit.fullText; selectedOffset = hit.contextOffset; val (word, found) = repo.lookupAt(hit.fullText, hit.contextOffset); query = word; results = found } }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { message = e.message.orEmpty() } finally { busy = false } }
    }
    val popupModifier = if (settings.string("Dictionary.displayMode") == "fullWidth") Modifier.fillMaxWidth() else Modifier.widthIn(max = settings.double("Dictionary.popupWidth").coerceIn(240.0, 900.0).dp).fillMaxWidth(0.94f)
    val minimumHeight = if (sourceText.isNotBlank()) 580.0 else 350.0
    DictionaryPopupDialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) { Surface(popupModifier.height(settings.double("Dictionary.popupHeight").coerceIn(minimumHeight, 1000.0).dp), shape = MaterialTheme.shapes.large,
        color = dictionaryPopupColor(settings.bool("Dictionary.popupDisableTransparency"), MaterialTheme.colorScheme.surface), contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(tr("DICTIONARY_POPUP"), style = MaterialTheme.typography.titleLarge); IconButton(onDismiss) { Icon(Icons.Outlined.Close, tr("DONE")) } }
            if (pageUri.isNotBlank()) {
                Text(pt("Manual language (optional)", "手动语言（可选）"), style = MaterialTheme.typography.labelSmall)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    FilterChip(manualLanguage == null, { manualLanguage = null }, { Text(if (settings.bool("Dictionary.restrictOCRLanguages")) tr("DEFAULT") + " (${effectiveSourceLanguage.ifBlank { "en" }})" else tr("AUTOMATIC")) })
                    OcrModel.entries.forEach { model -> FilterChip(manualLanguage == model.code, { manualLanguage = model.code }, { Text(java.util.Locale.forLanguageTag(model.code).displayLanguage) }) }
                }
            }
            ocr?.let { recognized ->
                if (settings.bool("Dictionary.textOverlayMode")) Box(Modifier.fillMaxWidth().heightIn(max = 190.dp).aspectRatio(recognized.width.toFloat() / recognized.height)) {
                    AsyncImage(pageUri, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                    Canvas(Modifier.fillMaxSize().pointerInput(recognized) { detectTapGestures { position ->
                        val hit = OcrLayout.find(recognized.observations, position.x / size.width, position.y / size.height)
                        if (hit != null) { region = hit.contextBounds; lookupOffset(hit.fullText, hit.contextOffset) }
                    } }) { val padding = settings.double("Dictionary.overlayPadding").coerceIn(0.0, 30.0).dp.toPx(); recognized.regions.forEach { r -> drawRect(Color.Green, Offset(r.left * size.width - padding, r.top * size.height - padding), Size((r.right - r.left) * size.width + padding * 2, (r.bottom - r.top) * size.height + padding * 2), style = Stroke(2.dp.toPx())) } }
                }
            }
            if (sourceText.isNotBlank()) Box(Modifier.fillMaxWidth().heightIn(max = 145.dp).verticalScroll(rememberScrollState())) { LookupText(sourceText, settings.double("Dictionary.overlayTextScaleMultiplier").toFloat().coerceIn(0.5f, 3f), ::lookupOffset) }
            OutlinedTextField(query, { query = it }, label = { Text(tr("SEARCH")) }, singleLine = true, modifier = Modifier.fillMaxWidth(), trailingIcon = { IconButton({ lookup(query) }) { Icon(Icons.Outlined.Search, tr("SEARCH")) } })
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (message.isNotBlank()) Text(message, color = Pink)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { items(results) { entry -> Card { Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.expression, style = MaterialTheme.typography.titleLarge); if (entry.reading.isNotBlank()) Text(entry.reading, color = Pink)
                Text(entry.dictionary + if (entry.reason.isNotBlank()) " · ${entry.reason}" else "", style = MaterialTheme.typography.labelSmall)
                if (entry.glossary.isNotBlank() && app.monogatari.android.dictionary.DictionaryGlossary.rich(entry.glossary)) DictionaryGlossaryView(entry.dictionaryId, entry.glossary, "#" + String.format("%06x", MaterialTheme.colorScheme.onSurface.toArgb() and 0xffffff), ::lookup) else Text(entry.definition)
                if (entry.tags.isNotBlank()) Text(entry.tags, style = MaterialTheme.typography.bodySmall)
                if (entry.frequency.isNotBlank()) Text(entry.frequency, style = MaterialTheme.typography.bodySmall)
                if (entry.pitch.isNotBlank()) Text(tr("PITCH_DICTIONARIES") + ": ${entry.pitch}", style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton({ scope.launchTracking({ busy = true }, { busy = false }, { message = it }) {
                        val captured = VocabularyCloze.capture(sentence, query, selectedOffset)
                        var e = VocabularyEntry(word = entry.expression, reading = entry.reading, definition = entry.definition, sentence = captured.sentence, mangaId = mangaId, chapterKey = chapterKey, page = page, cloze = query, clozeOffset = captured.offset)
                        if (pageUri.isNotBlank()) e = repo.captureContext(e, Uri.parse(pageUri), region)
                        message = if (repo.saveVocabulary(e)) Localization.string(context, "SAVE") else "✓ ${e.word}"
                    } }) { Text(tr("SAVE")) }
                    TextButton({ scope.launchTracking({ busy = true }, { busy = false }, { message = it }) {
                        val captured = VocabularyCloze.capture(sentence, query, selectedOffset)
                        var e = VocabularyEntry(word = entry.expression, reading = entry.reading, definition = entry.definition, sentence = captured.sentence, mangaId = mangaId, chapterKey = chapterKey, page = page, cloze = query, clozeOffset = captured.offset)
                        if (pageUri.isNotBlank()) e = repo.captureContext(e, Uri.parse(pageUri), region)
                        val note = sendToAnki(repo, e); repo.saveVocabulary(e.copy(noteId = note)); message = "Anki #$note"
                    } }) { Text("Anki") }
                }
            } } } }
        }
    } }
}
@Composable private fun OcrLanguageWhitelistDialog(original: List<String>, save: (List<String>) -> Unit, dismiss: () -> Unit) {
    val context = LocalContext.current; val app = remember(context) { AppRepository.get(context) }
    val state by app.state.collectAsStateWithLifecycle()
    val disableTransparency = remember(state.settings) { app.settings.bool("Dictionary.popupDisableTransparency") }
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    var selected by remember { mutableStateOf(original.toSet()) }; var query by remember { mutableStateOf("") }
    val codes = remember(locale, original) { (java.util.Locale.getISOLanguages().toList() + original + locale.language).distinct().sortedWith(compareBy<String> { it != locale.language }.thenBy { java.util.Locale.forLanguageTag(it).getDisplayLanguage(locale) }) }
    OptionSheet(tr("DICTIONARY_OCR_LANGUAGES"), dismiss, scrollable = false, containerColor = dictionaryPopupColor(disableTransparency, MaterialTheme.colorScheme.surface), footer = {
        SheetSecondaryButton(tr("CANCEL"), LocalSheetClose.current)
        SheetPrimaryButton(tr("DONE"), { save(codes.filter { it in selected }) })
    }) {
        SearchField(query, tr("SEARCH")) { query = it }
        val shown = codes.filter { it.contains(query, true) || java.util.Locale.forLanguageTag(it).getDisplayLanguage(locale).contains(query, true) }
        LazyColumn(Modifier.fillMaxWidth()) { itemsIndexed(shown, key = { _, code -> code }) { index, code ->
            SheetOption(java.util.Locale.forLanguageTag(code).getDisplayLanguage(locale).ifBlank { code }, code in selected, { selected = if (code in selected) selected - code else selected + code },
                multiple = true, divider = index < shown.lastIndex)
        } }
    }
}
@Composable private fun LookupText(value: String, scale: Float, clicked: (String, Int) -> Unit) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    BasicText(value, Modifier.pointerInput(value) { detectTapGestures { position -> layout?.let { clicked(value, it.getOffsetForPosition(position)) } } }, style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = MaterialTheme.typography.bodyLarge.fontSize * scale), onTextLayout = { layout = it })
}
@Composable private fun AnkiSettingsDialog(repo: DictionaryRepository, dismiss: () -> Unit) {
    val context = LocalContext.current; val secrets = remember { SecretStore(context) }; val scope = rememberCoroutineScope()
    var endpoint by remember { mutableStateOf(repo.preferences.getString("anki.endpoint", "http://127.0.0.1:8765").orEmpty()) }; var key by remember { mutableStateOf("") }
    var deck by remember { mutableStateOf(repo.preferences.getString("anki.deck", "Default").orEmpty()) }; var model by remember { mutableStateOf(repo.preferences.getString("anki.model", "Basic").orEmpty()) }
    var mapping by remember { mutableStateOf(repo.preferences.getString("anki.mapping", "{\"Front\":\"expression\",\"Back\":\"glossary\"}").orEmpty()) }; var message by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    AlertDialog(dismiss, title = { Text("AnkiConnect") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(endpoint, { endpoint = it }, label = { Text("AnkiConnect URL") }, singleLine = true)
        OutlinedTextField(key, { key = it }, label = { Text("API Key") }, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
        OutlinedTextField(deck, { deck = it }, label = { Text(pt("Deck", "牌组")) }); OutlinedTextField(model, { model = it }, label = { Text(pt("Note type", "笔记类型")) })
        OutlinedTextField(mapping, { mapping = it }, label = { Text(pt("Field mapping JSON", "字段映射 JSON")) }); Text("expression · reading · glossary · sentence · sentence-plain · sentence-cloze · cloze · cloze-offset · cloze-prefix · cloze-suffix · image", style = MaterialTheme.typography.bodySmall)
        TextButton({ scope.launchTracking({ busy = true }, { busy = false }, { message = it }) { val client = AnkiConnect(endpoint, key.ifEmpty { secrets.get("anki.key").orEmpty() }); message = "Decks: ${client.decks().joinToString()}\nModels: ${client.models().joinToString()}\nFields: ${client.fields(model).joinToString()}" } }, enabled = !busy) { Text(tr("RELOAD")) }
        if (message.isNotBlank()) Text(message)
    } }, confirmButton = { TextButton({ scope.launchTracking({ busy = true }, { busy = false }, { message = it }) { JSONObject(mapping); if (key.isNotBlank()) secrets.set("anki.key", key); repo.preferences.edit().putString("anki.endpoint", endpoint).putString("anki.deck", deck).putString("anki.model", model).putString("anki.mapping", mapping).apply(); dismiss() } }) { Text(tr("SAVE")) } }, dismissButton = { TextButton(dismiss) { Text(tr("CANCEL")) } })
}
internal suspend fun sendToAnki(repo: DictionaryRepository, entry: VocabularyEntry): Long {
    val mapping = JSONObject(repo.preferences.getString("anki.mapping", "{\"Front\":\"expression\",\"Back\":\"glossary\"}").orEmpty())
    return AnkiConnect(repo.preferences.getString("anki.endpoint", "http://127.0.0.1:8765").orEmpty(), SecretStore(repo.context).get("anki.key").orEmpty(), repo.context).add(entry,
        repo.preferences.getString("anki.deck", "Default").orEmpty(), repo.preferences.getString("anki.model", "Basic").orEmpty(), mapping.keys().asSequence().associateWith { mapping.getString(it) })
}
