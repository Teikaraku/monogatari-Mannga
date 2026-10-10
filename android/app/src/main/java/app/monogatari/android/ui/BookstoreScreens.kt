@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package app.monogatari.android.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import app.monogatari.android.MonogatariViewModel
import app.monogatari.android.data.*
import app.monogatari.android.sources.*
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.compose.AsyncImagePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.Locale

private class BookstoreMemory {
    var home by mutableStateOf<CopyHome?>(null)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    val work = SourceBrowseWork()
}
private class CopyCatalogMemory {
    var entries by mutableStateOf<List<Manga>>(emptyList())
    var topics by mutableStateOf<List<CopyTopic>>(emptyList())
    var topic by mutableStateOf<CopyTopic?>(null)
    var draft by mutableStateOf("")
    var submitted by mutableStateOf("")
    var theme by mutableStateOf("")
    var order by mutableStateOf("-datetime_updated")
    var status by mutableStateOf("")
    var period by mutableStateOf("day")
    var audience by mutableStateOf("male")
    var page by mutableIntStateOf(0)
    var more by mutableStateOf(false)
    var initialized = false
    var lastAppend = false
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    val work = SourceBrowseWork()
}
private fun copySource(state: AppState) = state.sources.firstOrNull { it.type == CopySource.TYPE }
private fun openCopy(vm: MonogatariViewModel, mode: String) { vm.navigate("copyCatalog", mode) }
private val rankPeriods = setOf("day", "week", "month", "total")

@Composable fun NativeBookstoreScreen(vm: MonogatariViewModel, state: AppState) {
    val source = copySource(state)
    if (source == null) { MissingCopySource(vm); return }
    val disabled = source.id in vm.repo.settings.strings("Browse.disabledSources")
    val memory = vm.pageMemory("bookstore:${source.json()}", ::BookstoreMemory)
    fun refresh() {
        memory.work.launch(vm.viewModelScope, replace = true, onBusy = { memory.busy = it }, onError = { memory.error = it?.message }) { token ->
            val result = withContext(Dispatchers.IO) { vm.repo.client.copy(source).home() }
            if (memory.work.accepts(token)) memory.home = result
        }
    }
    LaunchedEffect(memory, disabled) {
        if (disabled) { memory.work.cancel(); memory.busy = false }
        else if (memory.home == null) refresh()
    }
    DisposableEffect(memory) { onDispose { memory.work.cancel() } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(source.name, Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Medium)
            IconButton({ openCopy(vm, "search") }) { Icon(Icons.Outlined.Search, tr("SEARCH"), tint = Pink) }
            IconButton(::refresh, enabled = !disabled && !memory.busy) { Icon(Icons.Outlined.Refresh, tr("UPDATES"), tint = Pink) }
            IconButton({ vm.navigate("sourceManager") }) { Icon(Icons.Outlined.Tune, pt("Manage sources", "图源管理"), tint = Pink) }
        }
        if (disabled) { DisabledCopySource(vm, source); return@Column }
        val home = memory.home
        PullToRefreshBox(memory.busy && home != null, ::refresh, Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
                // The placeholder occupies the banner slot from the first frame, so the list stays anchored at the top when data arrives.
                if (home?.features?.isNotEmpty() == true) item(key = "features") { CopyFeaturePager(home.features, vm::open) }
                else if (home == null && memory.error == null) item(key = "features") { CopyFeaturePlaceholder() }
                item(key = "shortcuts") { Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp)) {
                    CopyShortcut(pt("Discover", "发现"), Icons.Outlined.Explore) { openCopy(vm, "discover") }
                    CopyShortcut(pt("Categories", "分类"), Icons.Outlined.Widgets) { openCopy(vm, "categories") }
                    CopyShortcut(pt("Rankings", "排行"), Icons.Outlined.BarChart) { openCopy(vm, "ranking") }
                    CopyShortcut(pt("Completed", "完结"), Icons.Outlined.TaskAlt) { openCopy(vm, "finished") }
                    CopyShortcut(pt("Topics", "专题"), Icons.Outlined.CollectionsBookmark) { openCopy(vm, "topics") }
                } }
                memory.error?.let { error -> item(key = "error") { CopyError(error, ::refresh) } }
                if (home == null) {
                    if (memory.error == null) item(key = "shelves-placeholder") { CopyShelfPlaceholder() }
                    return@LazyColumn
                }
                home.notice?.let { notice -> item(key = "home-notice") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Text(notice, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        home.diagnostics?.let { details ->
                            TextButton({ vm.message = details }) { Text(pt("Loading details", "查看加载详情")) }
                        }
                    }
                } }
                copyHomeShelves(home, vm)
            }
        }
    }
}

/**
 * Each feed gets the layout that suits its size and purpose: three picks fill one grid row, the rankings read as a
 * numbered chart, the long update feed scrolls in two rows, and the short completed list stays a fixed grid.
 */
private fun LazyListScope.copyHomeShelves(home: CopyHome, vm: MonogatariViewModel) {
    val sections = home.sections.associateBy { it.id }
    val placed = mutableSetOf<String>()
    fun shelf(id: String, more: String?, body: @Composable (CopySection) -> Unit) {
        val section = sections[id]?.takeIf { id !in placed } ?: return
        placed += id
        item(key = "title:$id") { CopyShelfHeader(section.title, more?.let { target -> { openCopy(vm, target) } }) }
        item(key = "books:$id") { body(section) }
    }
    shelf("recComics", null) { CopyBookGrid(it.entries.take(6), vm) }
    if (home.rankings.isNotEmpty()) {
        placed += "rankDayComics"
        item(key = "rankings") { CopyRankingShelf(home.rankings, vm) }
    }
    shelf("hotComics", "updates") { CopyTwoRowShelf(it.entries, vm) }
    shelf("newComics", "newest") { CopyBookRow(it.entries, vm) }
    shelf("rankDayComics", "ranking") { CopyBookRow(it.entries, vm) }
    shelf("finishComics", "finished") { CopyBookGrid(it.entries.take(6), vm) }
    home.sections.filter { it.id !in placed }.forEach { section -> shelf(section.id, null) { CopyBookRow(it.entries, vm) } }
    if (home.topics.isNotEmpty()) {
        item(key = "title:topics") { CopyShelfHeader(pt("Special collections", "精选专题")) { openCopy(vm, "topics") } }
        item(key = "topics") { LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(home.topics, key = { it.key }) { topic -> CopyTopicBanner(topic, Modifier.width(264.dp)) { openCopy(vm, "topic:${topic.key}") } }
        } }
    }
}

@Composable private fun CopyShelfHeader(title: String, onMore: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(19.dp).clip(RoundedCornerShape(2.dp)).background(Pink))
        Text(title, Modifier.weight(1f).padding(start = 10.dp), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        if (onMore != null) TextButton(onMore, contentPadding = PaddingValues(start = 12.dp, end = 4.dp)) {
            Text(pt("More", "更多"), fontSize = 14.sp); Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp))
        }
    }
}
@Composable private fun RowScope.CopyShortcut(title: String, icon: ImageVector, onOpen: () -> Unit) {
    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpen).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Pink.copy(alpha = .08f)), contentAlignment = Alignment.Center) {
            Icon(icon, title, Modifier.size(23.dp), tint = Pink)
        }
        Text(title, Modifier.padding(top = 7.dp), fontSize = 12.sp)
    }
}
@Composable private fun CopyFeaturePager(features: List<CopyFeature>, onOpen: (Manga) -> Unit) {
    val pager = rememberPagerState { features.size }
    // The timer restarts whenever a page settles, so a manual swipe always gets a full interval.
    LaunchedEffect(pager, features.size, pager.settledPage) {
        if (features.size < 2) return@LaunchedEffect
        delay(5000)
        if (!pager.isScrollInProgress) pager.animateScrollToPage((pager.settledPage + 1) % features.size)
    }
    Column {
        HorizontalPager(pager, contentPadding = PaddingValues(horizontal = 20.dp), pageSpacing = 12.dp) { index ->
            val feature = features[index]
            Box(Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(18.dp)).clickable { onOpen(feature.manga) }) {
                AsyncImage(feature.image, feature.manga.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .68f)))))
                Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                    Text(feature.manga.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(feature.title, color = Color.White.copy(alpha = .9f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            features.indices.forEach { index -> Box(Modifier.padding(horizontal = 3.dp).height(6.dp).width(if (index == pager.currentPage) 16.dp else 6.dp)
                .clip(RoundedCornerShape(3.dp)).background(if (index == pager.currentPage) Pink else MaterialTheme.colorScheme.outlineVariant)) }
        }
    }
}

private fun Manga.copyMeta(): JSONObject = runCatching { JSONObject(metadata) }.getOrDefault(JSONObject())
private fun Manga.latestChapter(): String = copyMeta().let { it.optString("copyLatestChapter").ifBlank { it.optString("last_chapter_name") } }

/** Cover, title and a secondary line; update feeds overlay the newest chapter on the cover. */
@Composable private fun CopyBookCard(manga: Manga, vm: MonogatariViewModel, modifier: Modifier = Modifier, titleLines: Int = 2, showLatest: Boolean = false) {
    val latest = if (showLatest) remember(manga.metadata) { manga.latestChapter() } else ""
    Column(modifier.clip(RoundedCornerShape(10.dp)).clickable { vm.open(manga) }) {
        Box {
            Cover(manga, vm.repo, Modifier.fillMaxWidth().aspectRatio(.72f))
            if (latest.isNotBlank()) Text(latest, Modifier.align(Alignment.BottomStart).fillMaxWidth().clip(RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .72f)))).padding(start = 7.dp, end = 7.dp, top = 14.dp, bottom = 5.dp),
                color = Color.White, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(manga.title, Modifier.padding(top = 7.dp), fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium, maxLines = titleLines, overflow = TextOverflow.Ellipsis)
        if (manga.author.isNotBlank()) Text(manga.author, fontSize = 11.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
@Composable private fun CopyBookGrid(entries: List<Manga>, vm: MonogatariViewModel, columns: Int = 3) {
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        entries.chunked(columns).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            row.forEach { CopyBookCard(it, vm, Modifier.weight(1f)) }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        } }
    }
}
@Composable private fun CopyBookRow(entries: List<Manga>, vm: MonogatariViewModel) {
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(entries, key = { it.id }) { manga -> CopyBookCard(manga, vm, Modifier.width(108.dp), showLatest = true) }
    }
}
/** A horizontal grid needs a fixed height; derive it from the card's cover ratio and the scaled text line heights. */
@Composable private fun CopyTwoRowShelf(entries: List<Manga>, vm: MonogatariViewModel) {
    val width = 104.dp
    val text = with(LocalDensity.current) { 19.sp.toDp() + 15.sp.toDp() }
    val card = width / .72f + 7.dp + text + 2.dp
    val rows = if (entries.size > 3) 2 else 1
    LazyHorizontalGrid(GridCells.Fixed(rows), Modifier.fillMaxWidth().height(card * rows + 16.dp * (rows - 1)), contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items(entries, key = { it.id }) { manga -> CopyBookCard(manga, vm, Modifier.width(width), titleLines = 1, showLatest = true) }
    }
}

private val rankRowHeight = 78.dp
@Composable private fun CopyRankingShelf(rankings: List<CopyRanking>, vm: MonogatariViewModel) {
    var period by rememberSaveable { mutableStateOf(rankings.first().period) }
    val current = rankings.firstOrNull { it.period == period } ?: rankings.first()
    val rows = current.entries.size.coerceIn(1, 3)
    Column {
        CopyShelfHeader(pt("Rankings", "排行榜")) { openCopy(vm, "ranking:${current.period}") }
        Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            rankings.forEach { ranking -> SheetChip(ranking.title, ranking.period == current.period, { period = ranking.period }, checkmark = false) }
        }
        val width = (LocalConfiguration.current.screenWidthDp * .8f).dp
        LazyHorizontalGrid(GridCells.Fixed(rows), Modifier.fillMaxWidth().height(rankRowHeight * rows + 8.dp * (rows - 1)), contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(current.entries, key = { _, manga -> "${current.period}:${manga.id}" }) { index, manga -> CopyRankRow(manga, index + 1, vm, Modifier.width(width)) }
        }
    }
}
@Composable private fun CopyRankRow(manga: Manga, position: Int, vm: MonogatariViewModel, modifier: Modifier = Modifier) {
    val meta = remember(manga.metadata) { manga.copyMeta() }
    val rank = meta.optInt("copyRank", position).takeIf { it > 0 } ?: position
    val previous = meta.optInt("copyRankLast", 0)
    val popular = meta.optLong("copyRankHeat", meta.optLong("popular", 0L))
    Row(modifier.height(rankRowHeight).clip(RoundedCornerShape(10.dp)).clickable { vm.open(manga) }, verticalAlignment = Alignment.CenterVertically) {
        Text("$rank", Modifier.width(34.dp), fontSize = if (rank <= 3) 24.sp else 18.sp, fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic,
            textAlign = TextAlign.Center, color = when (rank) { 1 -> Pink; 2 -> Color(0xFFFF6B3D); 3 -> Color(0xFFFFA024); else -> MaterialTheme.colorScheme.onSurfaceVariant })
        Cover(manga, vm.repo, Modifier.width(54.dp).height(72.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(manga.title, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (manga.author.isNotBlank()) Text(manga.author, fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (popular > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.LocalFireDepartment, null, Modifier.size(13.dp), tint = Pink.copy(alpha = .8f))
                Text(heat(popular), Modifier.padding(start = 2.dp), fontSize = 11.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (previous > 0 && previous != rank) Icon(if (rank < previous) Icons.Outlined.ArrowDropUp else Icons.Outlined.ArrowDropDown, null, Modifier.size(22.dp),
            tint = if (rank < previous) Pink else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun heat(value: Long): String = pt(
    when { value >= 1_000_000 -> String.format(Locale.ROOT, "%.1fM", value / 1e6); value >= 1_000 -> String.format(Locale.ROOT, "%.1fK", value / 1e3); else -> value.toString() },
    when { value >= 100_000_000 -> String.format(Locale.ROOT, "%.1f亿", value / 1e8); value >= 10_000 -> String.format(Locale.ROOT, "%.1f万", value / 1e4); else -> value.toString() })

/** Listing covers are resized variants (`name.jpg.200x80.jpg`); wide banners use the original and fall back to the variant. */
private fun fullSizeImage(url: String): String = url.replace(Regex("""(\.(?:jpe?g|png|webp))\.\d+x\d+\.(?:jpe?g|png|webp)$""", RegexOption.IGNORE_CASE), "$1")
@Composable private fun CopyTopicBanner(topic: CopyTopic, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpen)) {
        SubcomposeAsyncImage(fullSizeImage(topic.image), topic.title, Modifier.fillMaxWidth().aspectRatio(2.5f).clip(RoundedCornerShape(12.dp)).background(sheetGroupColor()),
            contentScale = ContentScale.Crop) {
            if (painter.state is AsyncImagePainter.State.Error) AsyncImage(topic.image, topic.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else SubcomposeAsyncImageContent()
        }
        Text(topic.title, Modifier.padding(top = 8.dp), fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (topic.subtitle.isNotBlank()) Text(topic.subtitle, fontSize = 11.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}
@Composable private fun CopyTopicCard(topic: CopyTopic, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
        SubcomposeAsyncImage(fullSizeImage(topic.image), null, Modifier.width(150.dp).aspectRatio(2.5f).clip(RoundedCornerShape(10.dp)).background(sheetGroupColor()),
            contentScale = ContentScale.Crop) {
            if (painter.state is AsyncImagePainter.State.Error) AsyncImage(topic.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else SubcomposeAsyncImageContent()
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(topic.title, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (topic.subtitle.isNotBlank()) Text(topic.subtitle, Modifier.padding(top = 4.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun CopyFeaturePlaceholder() {
    Box(Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(190.dp).clip(RoundedCornerShape(18.dp)).background(sheetGroupColor()))
}
@Composable private fun CopyShelfPlaceholder() {
    val tone = sheetGroupColor()
    Column(Modifier.padding(horizontal = 20.dp)) { repeat(2) {
        Box(Modifier.padding(top = 18.dp, bottom = 14.dp).size(width = 96.dp, height = 20.dp).clip(RoundedCornerShape(6.dp)).background(tone))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { repeat(3) { Column(Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth().aspectRatio(.72f).clip(RoundedCornerShape(8.dp)).background(tone))
            Box(Modifier.padding(top = 8.dp).fillMaxWidth(.85f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(tone))
            Box(Modifier.padding(top = 6.dp).fillMaxWidth(.5f).height(10.dp).clip(RoundedCornerShape(4.dp)).background(tone))
        } } }
    } }
}

@Composable fun NativeCopyCatalogScreen(vm: MonogatariViewModel, state: AppState, sourceOverride: SourceConfig? = null) {
    val source = sourceOverride ?: copySource(state)
    if (source == null) { MissingCopySource(vm); return }
    val requestedMode = vm.key.substringBefore(':')
    val mode = requestedMode.takeIf { it in setOf("discover", "categories", "ranking", "finished", "topics", "topic", "newest", "updates", "search") } ?: "discover"
    val topicKey = vm.key.substringAfter(':', "")
    // The home chart links to the period it is showing.
    val memory = vm.pageMemory("copyCatalog:${source.json()}:$mode:$topicKey") {
        CopyCatalogMemory().also { if (mode == "ranking" && topicKey in rankPeriods) it.period = topicKey }
    }
    val disabled = source.id in vm.repo.settings.strings("Browse.disabledSources")
    val context = LocalContext.current
    val genres = remember { JSONArray(context.assets.open("copy/genres.json").bufferedReader().use { it.readText() }).objects() }
    var showCategories by remember { mutableStateOf(mode == "categories" && !memory.initialized) }
    val title = when (mode) {
        "categories" -> pt("Categories", "分类"); "ranking" -> pt("Rankings", "排行榜"); "finished" -> pt("Completed", "完结漫画")
        "topics" -> pt("Topics", "专题"); "topic" -> memory.topic?.title ?: pt("Topic", "专题详情")
        "newest" -> pt("New arrivals", "新书入库"); "updates" -> pt("Latest updates", "热门更新")
        "search" -> tr("SEARCH"); else -> pt("Discover", "发现")
    }
    fun query() = CopyQuery(kind = when { mode == "ranking" -> "ranking"; mode == "finished" -> "finished"; mode == "search" || memory.submitted.isNotBlank() -> "search"; else -> "catalog" },
        keyword = memory.submitted, theme = memory.theme, order = if (mode == "newest") "-datetime_created" else memory.order,
        status = memory.status, rankPeriod = memory.period, audience = memory.audience)
    fun load(append: Boolean = false) {
        val captured = query()
        val page = if (append) memory.page + 1 else 1
        memory.lastAppend = append
        if (!append) { memory.entries = emptyList(); memory.topics = emptyList(); memory.topic = null; memory.page = 0; memory.more = false; memory.initialized = false }
        memory.work.launch(vm.viewModelScope, replace = !append, onBusy = { memory.busy = it }, onError = { memory.error = it?.message }) { token ->
            val client = vm.repo.client.copy(source)
            when (mode) {
                "topics" -> {
                    val result = withContext(Dispatchers.IO) { client.topics(page) }
                    if (memory.work.accepts(token)) { memory.topics = ((if (append) memory.topics else emptyList()) + result.entries).distinctBy { it.key }; memory.more = result.more }
                }
                "topic" -> {
                    val currentTopic = memory.topic
                    val result = withContext(Dispatchers.IO) { (currentTopic ?: client.topicDetail(topicKey)) to client.catalog(CopyQuery(kind = "topic", topic = topicKey), page) }
                    if (memory.work.accepts(token)) {
                        memory.topic = result.first
                        memory.entries = ((if (append) memory.entries else emptyList()) + result.second.entries).distinctBy { it.id }
                        memory.more = result.second.more
                    }
                }
                else -> {
                    val result = withContext(Dispatchers.IO) { client.catalog(captured, page) }
                    if (memory.work.accepts(token)) { memory.entries = ((if (append) memory.entries else emptyList()) + result.entries).distinctBy { it.id }; memory.more = result.more }
                }
            }
            if (memory.work.accepts(token)) { memory.page = page; memory.initialized = true }
        }
    }
    LaunchedEffect(memory, disabled) {
        if (disabled) { memory.work.cancel(); memory.busy = false }
        else if (!memory.initialized && mode != "search") load()
    }
    DisposableEffect(memory) { onDispose { memory.work.cancel() } }
    Column(Modifier.fillMaxSize()) {
        PageTitle(title, vm::back, actions = {
            if (mode !in setOf("topic", "topics", "ranking", "search")) IconButton({ showCategories = true }) { Icon(Icons.Outlined.FilterList, tr("FILTERS"), tint = Pink) }
            IconButton({ load() }, enabled = !memory.busy && (mode != "search" || memory.submitted.isNotBlank())) { Icon(Icons.Outlined.Refresh, tr("UPDATES"), tint = Pink) }
        })
        if (disabled) { DisabledCopySource(vm, source); return@Column }
        if (mode !in setOf("topic", "topics", "ranking", "finished")) SearchField(memory.draft, tr("SEARCH"), onSearch = { memory.submitted = memory.draft.trim(); load() }) { memory.draft = it }
        if (mode == "ranking") {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("day" to "日榜", "week" to "周榜", "month" to "月榜", "total" to "总榜").forEach { (value, label) ->
                    SheetChip(label, memory.period == value, { memory.period = value; load() }, checkmark = false)
                }
            }
            Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("male" to "少年向", "female" to "少女向").forEach { (value, label) ->
                    SheetChip(label, memory.audience == value, { memory.audience = value; load() }, checkmark = false)
                }
            }
        } else if (mode !in setOf("topic", "topics", "search")) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SheetChip(genres.firstOrNull { it.optString("id") == memory.theme }?.optString("name") ?: pt("All categories", "全部分类"), memory.theme.isNotEmpty(),
                    { showCategories = true }, checkmark = false, trailingIcon = Icons.Outlined.ExpandMore)
                if (mode != "newest") listOf("-datetime_updated" to "最近更新", "-popular" to "人气优先").forEach { (value, label) ->
                    SheetChip(label, memory.order == value, { memory.order = value; load() }, checkmark = false)
                }
            }
        }
        if (memory.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp))
        memory.error?.let { CopyError(it, { load(append = memory.lastAppend) }) }
        when (mode) {
            "topics" -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(memory.topics, key = { it.key }) { topic -> CopyTopicCard(topic) { openCopy(vm, "topic:${topic.key}") } }
                item { CopyLoadMore(memory.more, memory.busy) { load(true) } }
            }
            "topic" -> LazyVerticalGrid(GridCells.Adaptive(104.dp), Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                memory.topic?.let { topic ->
                    item(span = { GridItemSpan(maxLineSpan) }) { Column {
                        SubcomposeAsyncImage(fullSizeImage(topic.image), topic.title, Modifier.fillMaxWidth().aspectRatio(2.5f).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop) {
                            if (painter.state is AsyncImagePainter.State.Error) AsyncImage(topic.image, topic.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            else SubcomposeAsyncImageContent()
                        }
                        Text(topic.subtitle, Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        androidx.compose.foundation.text.selection.SelectionContainer { Text(Jsoup.parseBodyFragment(topic.intro).wholeText(), lineHeight = 24.sp) }
                    } }
                }
                items(memory.entries, key = { it.id }) { manga -> CopyBookCard(manga, vm) }
                item(span = { GridItemSpan(maxLineSpan) }) { CopyLoadMore(memory.more, memory.busy) { load(true) } }
            }
            else -> {
                if (!memory.busy && memory.error == null && memory.entries.isEmpty()) {
                    if (mode == "search" && memory.submitted.isBlank()) Text(pt("Search comics by title or author.", "输入漫画名或作者开始搜索"), Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else Text(pt("No comics found.", "没有找到符合条件的漫画"), Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (mode == "ranking") LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(memory.entries, key = { _, manga -> manga.id }) { index, manga -> CopyRankRow(manga, index + 1, vm, Modifier.fillMaxWidth()) }
                    item { CopyLoadMore(memory.more, memory.busy) { load(true) } }
                } else LazyVerticalGrid(GridCells.Adaptive(104.dp), Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    items(memory.entries, key = { it.id }) { manga -> CopyBookCard(manga, vm, showLatest = mode == "updates" || mode == "newest") }
                    item(span = { GridItemSpan(maxLineSpan) }) { CopyLoadMore(memory.more, memory.busy) { load(true) } }
                }
            }
        }
    }
    if (showCategories) CopyGenreSheet(genres, memory.theme, { showCategories = false }) { theme ->
        memory.theme = theme; memory.submitted = ""; memory.draft = ""; load()
    }
}

/** Every genre is visible at once; Comic Market event tags are grouped apart from subject genres. */
@Composable private fun CopyGenreSheet(genres: List<JSONObject>, selected: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    val event = remember { Regex("^C\\d+$") }
    val (events, subjects) = remember(genres) { genres.partition { event.matches(it.optString("name")) } }
    OptionSheet(pt("Categories", "漫画分类"), onDismiss) {
        val close = LocalSheetClose.current
        fun choose(id: String) { onSelect(id); close() }
        SheetSection(pt("Genres", "题材")) { SheetChips {
            SheetChip(pt("All", "全部"), selected.isEmpty(), { choose("") })
            subjects.forEach { genre -> SheetChip(genre.optString("name"), selected == genre.optString("id"), { choose(genre.optString("id")) }) }
        } }
        if (events.isNotEmpty()) SheetSection(pt("Comic Market", "同人展")) { SheetChips {
            events.sortedByDescending { it.optString("name").drop(1).toIntOrNull() ?: 0 }.forEach { genre ->
                SheetChip(genre.optString("name"), selected == genre.optString("id"), { choose(genre.optString("id")) })
            }
        } }
    }
}
@Composable private fun CopyLoadMore(more: Boolean, busy: Boolean, next: () -> Unit) {
    if (more) TextButton(next, Modifier.fillMaxWidth().padding(vertical = 12.dp), enabled = !busy) { Text(pt("Load more", "加载更多")) }
}
@Composable private fun CopyError(message: String, retry: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.errorContainer.copy(alpha = .45f), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(14.dp)) { Text(message, color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 13.sp); TextButton(retry) { Text(pt("Retry", "重试")) } }
    }
}
@Composable private fun MissingCopySource(vm: MonogatariViewModel) {
    Column {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            IconButton({ vm.navigate("sourceManager") }) { Icon(Icons.Outlined.Tune, pt("Manage sources", "图源管理"), tint = Pink) }
        }
        EmptyPage(Icons.Outlined.MenuBook, "拷贝漫画", pt("Add the source to load comics.", "添加拷贝漫画图源后即可浏览漫画"), pt("Add source", "添加图源")) {
            vm.run { vm.repo.change { state -> state.copy(sources = (state.sources + CopySource.configuration).distinctBy { it.id }) } }
        }
    }
}
@Composable private fun DisabledCopySource(vm: MonogatariViewModel, source: SourceConfig) {
    EmptyPage(Icons.Outlined.MenuBook, source.name, pt("This source is disabled.", "此图源已停用"), pt("Enable", "启用")) {
        vm.run { vm.repo.settings.put("Browse.disabledSources", JSONArray(vm.repo.settings.strings("Browse.disabledSources") - source.id)) }
    }
}
