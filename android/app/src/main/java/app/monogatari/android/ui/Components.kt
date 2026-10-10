@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package app.monogatari.android.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.monogatari.android.R
import app.monogatari.android.data.*
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val Pink = Color(0xFFFF2D55)
val GroupGray = Color(0xFFF2F2F7)
@Composable fun PageTitle(title: String, back: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (back != null) IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, tr("CANCEL"), tint = Pink) }
            Spacer(Modifier.weight(1f)); actions()
        }
        Text(title, Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp), fontSize = 34.sp, fontWeight = FontWeight.Bold,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
@Composable fun SearchField(value: String, placeholder: String, onSearch: (() -> Unit)? = null, onChange: (String) -> Unit) {
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    TextField(value, onChange, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = if (onSearch != null) androidx.compose.ui.text.input.ImeAction.Search else androidx.compose.ui.text.input.ImeAction.Done),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSearch?.invoke(); keyboard?.hide() }, onDone = { keyboard?.hide() }),
        placeholder = { Text(placeholder) }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
        trailingIcon = { if (value.isNotEmpty()) IconButton({ onChange("") }) { Icon(Icons.Outlined.Close, tr("CLEAR")) } },
        shape = RoundedCornerShape(12.dp), colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
}
@Composable fun EmptyPage(icon: ImageVector, title: String, description: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, Modifier.size(62.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(18.dp)); Text(title, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp)); Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 24.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(16.dp)); TextButton(onAction) { Text(action) } }
    }
}
@Composable fun Cover(manga: Manga, repo: AppRepository, modifier: Modifier = Modifier) {
    val state by repo.state.collectAsState()
    val source = state.sources.firstOrNull { it.id == manga.sourceId }
    val processed by produceState<ByteArray?>(null, source?.id, source?.configuration, manga.cover) {
        value = if (source != null && source.type in setOf("wasm", app.monogatari.android.sources.CopySource.TYPE) && manga.cover.isNotBlank()) withContext(Dispatchers.IO) {
            runCatching { repo.client.coverBytes(source, manga.cover) }.getOrNull()
        } else null
    }
    val request = ImageRequest.Builder(LocalContext.current).data(processed ?: manga.cover.ifBlank { R.drawable.manga_placeholder })
        .crossfade(true).apply { if (source != null && source.baseUrl.isNotBlank() && manga.cover.startsWith(source.baseUrl.trimEnd('/') + "/")) {
            repo.client.headers(source).forEach { (k, v) -> addHeader(k, v) }
        }}.build()
    AsyncImage(request, manga.title, modifier.clip(RoundedCornerShape(8.dp)), placeholder = painterResource(R.drawable.manga_placeholder),
        error = painterResource(R.drawable.manga_placeholder), contentScale = ContentScale.Crop)
}
@Composable fun MangaCollection(manga: List<Manga>, repo: AppRepository, onOpen: (Manga) -> Unit, modifier: Modifier = Modifier,
    onLongClick: ((Manga) -> Unit)? = null, selection: MangaCollectionSelection? = null) {
    val state by repo.state.collectAsState()
    val currentSelection by rememberUpdatedState(selection)
    val currentOpen by rememberUpdatedState(onOpen)
    val currentLongClick by rememberUpdatedState(onLongClick)
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    val grid = rememberLazyGridState()
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val bounds = remember { mutableMapOf<String, LayoutCoordinates>() }
    val keys = manga.map { it.id }
    val collectionModifier = modifier.onGloballyPositioned { coordinates[0] = it }
        .semantics { testTagsAsResourceId = true }
        .testTag("app.monogatari.android:id/manga_${if (state.preferences.listView) "list" else "grid"}")
        .then(if (selection?.enabled == true) Modifier.pointerInput(selection.scopeKey, state.preferences.listView, keys) {
            twoFingerMangaSelection(manga, { currentSelection }, {
                val parent = coordinates[0]
                if (parent?.isAttached != true) emptyMap() else bounds.filterValues { it.isAttached }
                    .mapValues { parent.localBoundingBoxOf(it.value, clipBounds = false) }
            }, if (state.preferences.listView) list else grid)
        } else Modifier)
    fun itemModifier(book: Manga): Modifier = Modifier
        .testTag("app.monogatari.android:id/manga_${AppRepository.hash(book.id)}")
        .onGloballyPositioned { item ->
            if (selection != null && item.isAttached) bounds[book.id] = item
        }
        .combinedClickable(enabled = selection?.enabled != false,
            onClick = {
                val controller = currentSelection
                if (controller != null) {
                    if (controller.enabled) {
                        if (controller.active) controller.set(book, book.id !in controller.selected) else currentOpen(book)
                    }
                } else currentOpen(book)
            },
            onLongClick = if (selection?.active == true || onLongClick == null) null else { { currentLongClick?.invoke(book) } })
        .then(if (selection?.active == true) Modifier.semantics {
            selected = book.id in selection.selected
            role = Role.Checkbox
            toggleableState = if (book.id in selection.selected) ToggleableState.On else ToggleableState.Off
        } else Modifier)
    val offlineChapterKeys = remember(state.records) { org.json.JSONObject(state.records).optJSONArray("history").objects().filter { it.optJSONObject("_chapter")?.optBoolean("downloaded") == true }
        .groupBy { it.optString("sourceId") to it.optString("mangaId") }.mapValues { (_, rows) -> rows.map { it.optString("chapterId") }.toSet() } }
    if (state.preferences.listView) LazyColumn(collectionModifier, state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(manga, key = { it.id }) { m ->
            DisposableEffect(m.id) { onDispose { bounds.remove(m.id) } }
            val checked = m.id in selection?.selected.orEmpty()
            Row(itemModifier(m).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (selection?.active == true) { MangaSelectionIndicator(checked); Spacer(Modifier.width(16.dp)) }
                Cover(m, repo, Modifier.width(65.dp).height(96.dp).alpha(if (selection?.active == true && !checked) 0.5f else 1f))
                Column(Modifier.padding(start = 14.dp).weight(1f)) {
                    Text(m.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(m.author.ifBlank { if (m.sourceId == "local") tr("LOCAL_FILES") else state.sources.firstOrNull { it.id == m.sourceId }?.name ?: m.sourceId }, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (m.chapters.isNotEmpty()) Text("${m.unread} ${tr("UNREAD")}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (selection?.active != true) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else {
    val configuration = LocalConfiguration.current
    val columns = when (state.preferences.layout) {
        "custom" -> repo.settings.int(if (configuration.screenWidthDp > configuration.screenHeightDp) "Appearance.customLandscapeRows" else "Appearance.customPortraitRows").coerceIn(1, 15)
        "compact" -> maxOf(1, configuration.screenWidthDp / if (configuration.smallestScreenWidthDp >= 600) 150 else 120)
        else -> maxOf(1, configuration.screenWidthDp / 180)
    }
    LazyVerticalGrid(GridCells.Fixed(columns), collectionModifier, state = grid,
        contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        items(manga, key = { it.id }) { m ->
            DisposableEffect(m.id) { onDispose { bounds.remove(m.id) } }
            val checked = m.id in selection?.selected.orEmpty()
            Column(itemModifier(m)) {
                val downloaded = (m.chapters.filter { it.downloaded }.map { it.key }.toSet() + offlineChapterKeys[m.sourceId to m.key].orEmpty()).size
                Box { Cover(m, repo, Modifier.fillMaxWidth().aspectRatio(2f / 3f).alpha(if (selection?.active == true && !checked) 0.5f else 1f))
                    if (m.unread > 0 && repo.settings.bool("Library.unreadChapterBadges")) Text("${m.unread}", Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(5.dp))
                        .background(Pink).padding(horizontal = 6.dp, vertical = 2.dp), color = Color.White, fontSize = 11.sp)
                    if (m.pinned) Icon(Icons.Outlined.PushPin, tr("PIN"), Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp), tint = Color.White)
                    if (repo.settings.bool("Library.downloadedChapterBadges") && downloaded > 0) Text("$downloaded",
                        Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFF34C759)).padding(horizontal = 6.dp, vertical = 2.dp), color = Color.White, fontSize = 11.sp)
                    if (selection?.active == true) Box(Modifier.align(Alignment.BottomEnd).padding(10.dp)) { MangaSelectionIndicator(checked) }
                }
                Spacer(Modifier.height(6.dp)); Text(m.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    }
}
@Composable private fun MangaSelectionIndicator(selected: Boolean) {
    Icon(if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null,
        Modifier.size(24.dp).background(Color.White, androidx.compose.foundation.shape.CircleShape), tint = if (selected) Pink else Color.Gray)
}
@Composable fun SettingGroup(title: String = "", footer: String = "", content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        if (title.isNotEmpty()) Text(title, Modifier.padding(start = 16.dp, bottom = 7.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface), content = content)
        if (footer.isNotEmpty()) Text(footer, Modifier.padding(start = 16.dp, end = 16.dp, top = 7.dp),
            fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable fun SettingRow(title: String, icon: ImageVector? = null, color: Color = Pink, subtitle: String = "", value: String = "",
    onClick: (() -> Unit)? = null, enabled: Boolean = true, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
        .semantics { if (!enabled) disabled() }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Box(Modifier.size(29.dp).clip(RoundedCornerShape(7.dp)).background(color), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(20.dp), tint = Color.White) }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .5f))
            if (subtitle.isNotEmpty()) Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp)
        }
        if (value.isNotEmpty()) Text(value, Modifier.widthIn(max = 180.dp), fontSize = 14.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else .5f))
        if (trailing != null) trailing() else if (onClick != null) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(Modifier.padding(start = if (icon == null) 14.dp else 55.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
}
