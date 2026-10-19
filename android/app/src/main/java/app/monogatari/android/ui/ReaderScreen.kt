package app.monogatari.android.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import android.net.Uri
import android.view.View
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.monogatari.android.MonogatariViewModel
import app.monogatari.android.data.*
import app.monogatari.android.reader.*
import app.monogatari.android.downloads.ArchivePageStore
import app.monogatari.android.downloads.DownloadService
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Size
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onEach
import java.io.File
import org.json.JSONObject

private data class VisualPage(val url: String, val originalIndex: Int, val half: Int? = null)

@Composable fun ReaderScreen(vm: MonogatariViewModel, state: AppState) {
    val manga = state.manga.firstOrNull { it.id == vm.key } ?: return
    val chapter = vm.readerChapter ?: return
    var textLoadError by remember(chapter.key) { mutableStateOf<String?>(null) }
    var textRetry by remember(chapter.key) { mutableIntStateOf(0) }
    var sourceTexts by remember(chapter.key) { mutableStateOf<List<String>?>(null) }
    val textOnly=chapter.pages.isNotEmpty() && chapter.pages.all { url ->
        (if(ArchivePageStore.isArchive(url)) Uri.parse(url).getQueryParameter("entry") else Uri.parse(url).path)?.substringAfterLast('.')?.lowercase() in setOf("txt","md") }
    val text by produceState(chapter.text,chapter.key,chapter.pages,textRetry) { textLoadError=null;if(textOnly) try {
        val loaded=withContext(Dispatchers.IO) {
        var size=0
        chapter.pages.map { url ->
            val bytes=if(ArchivePageStore.isArchive(url)) ArchivePageStore.bytes(vm.repo.context,url) else vm.repo.context.contentResolver.openInputStream(Uri.parse(url))?.use { input ->
            java.io.ByteArrayOutputStream().also { ArchiveImporter.copyBounded(input,it,5L*1024*1024) }.toByteArray() } ?: error("文字页面文件缺失")
            size+=bytes.size;require(size<=20*1024*1024) { "文字章节过大" };bytes.toString(Charsets.UTF_8)
        }
        };sourceTexts=loaded;value=loaded.joinToString("\n\n")
    } catch(e:CancellationException){throw e} catch(e:Exception){if(chapter.text.isNotBlank()){sourceTexts=listOf(chapter.text);value=chapter.text}else textLoadError=e.message ?: "文字加载失败"} }
    if(textLoadError!=null) EmptyPage(Icons.Outlined.ErrorOutline,tr("FAILED_CHAPTER_LOAD"),textLoadError.orEmpty(),tr("RETRY")){textRetry++}
    else if(textOnly && sourceTexts==null) Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator()}
    else key(manga.id, chapter.key) { ChapterReader(vm, state, manga, chapter.copy(text=text),sourceTexts ?: listOf(text)) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun ChapterReader(vm: MonogatariViewModel, state: AppState, manga: Manga, chapter: Chapter,sourceTexts:List<String>) {
    val context = LocalContext.current; val density = LocalDensity.current
    val hapticView = LocalView.current
    var lastSliderPage by remember { mutableIntStateOf(-1) }
    val store = remember { ReaderOptionStore(context) }
    val legacySettings = remember(vm.repo) { SettingsStore(vm.repo) }
    var options by remember(manga.id) { mutableStateOf(ReaderOptions.fromLegacy(legacySettings, manga.id)) }
    LaunchedEffect(state.settings) { options = ReaderOptions.fromLegacy(legacySettings, manga.id) }
    var chrome by remember { mutableStateOf(true) }; var settings by remember { mutableStateOf(false) }
    var showChapters by remember { mutableStateOf(false) }; var quickActions by remember { mutableStateOf(false) }
    var showDescription by remember { mutableStateOf(false) }
    var descriptionRetry by remember { mutableIntStateOf(0) }
    var richSourceIndices by remember { mutableStateOf(setOf(0)) }
    var dictionary by remember { mutableStateOf(false) }; var dictionaryUri by remember { mutableStateOf("") }
    var actionPage by remember { mutableStateOf<VisualPage?>(null) }
    var pendingGalleryPage by remember { mutableStateOf<VisualPage?>(null) }
    var pendingSavePage by remember { mutableStateOf<VisualPage?>(null) }
    val reloadVersions=remember { mutableStateMapOf<String,Int>() }
    var dictionaryPage by remember { mutableIntStateOf(0) }
    var dictionaryText by remember { mutableStateOf("") }
    var dictionaryPoint by remember { mutableStateOf<Pair<Float,Float>?>(null) }
    val readerFocus=remember { FocusRequester() }
    val readerModal=settings || showChapters || quickActions || showDescription || dictionary
    LaunchedEffect(readerModal) {
        // Give the real Compose owner focus in touch mode, before ViewRoot treats the first navigation key as focus acquisition.
        if(!readerModal) { withFrameNanos { };readerFocus.requestFocus() }
    }
    var autoScroll by remember { mutableStateOf(false) }
    var richUserScrolled by remember { mutableStateOf(false) }
    var transition by remember { mutableStateOf<Int?>(null) };var pageZoomed by remember { mutableStateOf(false) }
    val source = state.sources.firstOrNull { it.id == manga.sourceId }
    val scope = rememberCoroutineScope()
    val defaultMode = legacySettings.string("Reader.readingMode").ifBlank { "auto" }
    val selectedMode = if (options.mode == "default") defaultMode else options.mode
    val mode = if (selectedMode == "auto") manga.viewer.takeIf { it in setOf("rtl", "ltr", "vertical", "webtoon", "continuous") } ?: "rtl" else selectedMode
    val continuous = mode in setOf("webtoon", "continuous")
    val textMode = chapter.text.isNotBlank() || chapter.html.isNotBlank()
    val richMode=textMode && (chapter.html.isNotBlank() && (options.textStyle=="rich" || chapter.text.isBlank()) || chapter.html.isBlank() && options.textStyle!="paged")
    val textScroll = textMode && (options.textStyle != "paged" || richMode)
    val dimensions = remember { mutableStateMapOf<String, Float>() }
    val visual = chapter.pages.flatMapIndexed { i, url ->
        if (!continuous && options.splitWideImages && (dimensions[url] ?: 0f) > 1f) {
            val order = if ((mode == "rtl") xor options.reverseSplitOrder) listOf(1, 0) else listOf(0, 1)
            order.map { VisualPage(url, i, it) }
        } else listOf(VisualPage(url, i))
    }
    var viewportWidth by remember { mutableIntStateOf(0) }; var viewportHeight by remember { mutableIntStateOf(0) }
    val double = !continuous && (options.pageLayout == "double" || options.pageLayout == "auto" && viewportWidth > viewportHeight)
    val ratios=dimensions.toMap()
    var isolatedHeads by remember { mutableStateOf<Set<String>>(emptySet()) }
    fun pageKey(page:VisualPage)="${page.originalIndex}:${page.half}"
    val pairable=visual.map { it.half != null || (ratios[it.url] ?: 0f) <= 1f }
    val groups = remember(visual, double, options.pageOffset,ratios,isolatedHeads) {
        ReaderLayout.spreads(pairable,double,options.pageOffset,visual.indices.filter { pageKey(visual[it]) in isolatedHeads }.toSet())
            .map { spread -> spread.map { visual[it] } }
    }
    val markdownDocument by produceState<String?>(null,chapter.text,chapter.html,source?.id,sourceTexts) {
        if(chapter.html.isBlank() && textMode)value=withContext(Dispatchers.IO) {
            sourceTexts.mapIndexed { index,part ->ReaderTextSources.wrap(index,ReaderMarkdown.materialize(context,part) { url ->
                loadPageBitmap(context,vm,source,VisualPage(url,index),options.copy(upscale=false,cropBorders=false))
            }) }.joinToString("\n")
        }
    }
    val contentText:CharSequence=remember(chapter.text,chapter.html,markdownDocument,viewportWidth,viewportHeight,double,options.textMargins,options.paragraphSpacing) {
        if(chapter.html.isNotBlank())chapter.text else markdownDocument?.let { ReaderMarkdown.spans(context,it,
            (viewportWidth/(if(double)2 else 1)-with(density){(options.textMargins*2).dp.toPx()}).toInt(),
            (viewportHeight-with(density){112.dp.toPx()}).toInt(),with(density){options.paragraphSpacing.dp.roundToPx()}) } ?: ""
    }
    val textPages by produceState<List<TextPage>>(emptyList(), contentText, viewportWidth, viewportHeight, double,options.fontSize, options.lineSpacing, options.textMargins, options.textFont) {
        if (textMode && viewportWidth > 0 && viewportHeight > 0) value = withContext(Dispatchers.Default) {
            TextPaginator.paginate(contentText, (viewportWidth / (if(double)2 else 1) - with(density) { (options.textMargins * 2).dp.toPx() }).toInt(),
                (viewportHeight - with(density) { 112.dp.toPx() }).toInt(), with(density) { options.fontSize.sp.toPx() }, options.lineSpacing, options.textFont)
        }
    }
    val textGroups=remember(textPages.size,double) { ReaderLayout.spreads(List(textPages.size){true},double,false) }
    val count = if (textMode) textGroups.size.coerceAtLeast(1) else groups.size.coerceAtLeast(1)
    val savedSplit=JSONObject(chapter.metadata).takeIf { it.optInt("splitSourceIndex",chapter.page)==chapter.page }?.optInt("splitIndex",-1)?.takeIf { it in 0..1 }
    val initial = if (textMode) (chapter.page/(if(double)2 else 1)) else groups.indexOfFirst { group -> group.any { it.originalIndex==chapter.page && (savedSplit==null || it.half==null || it.half==savedSplit) } }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = initial.coerceIn(0, count - 1)) { count }
    var previousGroups by remember { mutableStateOf(groups) }
    val groupLayoutReady=groups==previousGroups
    LaunchedEffect(groups) {
        if(!textMode && !continuous && previousGroups!=groups) {
            val anchor=previousGroups.getOrNull(pager.currentPage)?.firstOrNull()
            val half=anchor?.half ?: savedSplit?.takeIf { anchor?.originalIndex==chapter.page }
            val target=groups.indexOfFirst { group -> group.any { it.originalIndex==anchor?.originalIndex && (half==null || it.half==half) } }
                .let { exact ->if(exact>=0)exact else groups.indexOfFirst { group ->group.any { it.originalIndex==anchor?.originalIndex } } }
            if(target>=0)pager.scrollToPage(target)
        };previousGroups=groups
    }
    val list = rememberLazyListState(chapter.page.coerceIn(0, maxOf(0, visual.lastIndex)))
    val scroll = rememberScrollState()
    val savedFraction=runCatching { JSONObject(chapter.metadata).optDouble("scrollPosition",if(chapter.total>1)chapter.page.toDouble()/(chapter.total-1) else 0.0) }.getOrDefault(0.0).takeIf(Double::isFinite)?.coerceIn(0.0,1.0) ?: 0.0
    var richFraction by remember { mutableDoubleStateOf(savedFraction) }
    var richEstimatedPages by remember { mutableIntStateOf(chapter.total.coerceAtLeast(1)) }
    var richScrollExtent by remember { mutableFloatStateOf(0f) }
    var richTarget by remember { mutableDoubleStateOf(savedFraction) }
    var scrollInitialized by remember(textScroll,richMode) { mutableStateOf(!textScroll) }
    LaunchedEffect(scroll.maxValue,viewportHeight,textScroll,richMode) {
        if(textScroll && !richMode && !scrollInitialized && viewportHeight>0) { scroll.scrollTo((savedFraction*scroll.maxValue).toInt());scrollInitialized=true }
    }
    val textScrollFraction=if(richMode)richFraction else if(scroll.maxValue>0)scroll.value.toDouble()/scroll.maxValue else if(scrollInitialized)1.0 else 0.0
    val scrollPageCount=if(richMode)richEstimatedPages else ReaderTextSources.scrollPageCount((scroll.maxValue+viewportHeight).toFloat(),viewportHeight.toFloat())
    val scrollPercent=(textScrollFraction*100).toInt().coerceIn(0,100)
    val logicalPage = when { textScroll ->ReaderTextSources.scrollPage(textScrollFraction,scrollPageCount)
        textMode -> textGroups.getOrNull(pager.currentPage)?.firstOrNull() ?: 0; continuous -> visual.getOrNull(list.firstVisibleItemIndex)?.originalIndex ?: 0
        else -> groups.getOrNull(pager.currentPage)?.firstOrNull()?.originalIndex ?: 0 }
    val total = if (textScroll) scrollPageCount else if (textMode) textPages.size.coerceAtLeast(1) else chapter.pages.size
    val lastVisiblePage=if(continuous)list.layoutInfo.visibleItemsInfo.mapNotNull { visual.getOrNull(it.index)?.originalIndex }.maxOrNull() ?: logicalPage else logicalPage
    val completionVisible=when {
        textScroll ->scrollInitialized && logicalPage>=total-1
        textMode ->textGroups.getOrNull(pager.currentPage).orEmpty().lastOrNull()==textPages.lastIndex && textPages.isNotEmpty()
        continuous ->list.layoutInfo.visibleItemsInfo.any { it.index==visual.lastIndex } && visual.isNotEmpty()
        else ->groups.getOrNull(pager.currentPage).orEmpty().contains(visual.lastOrNull()) && visual.isNotEmpty() && groupLayoutReady
    }
    val frameMode=rememberUpdatedState(mode);val frameText=rememberUpdatedState(textMode);val frameTextScroll=rememberUpdatedState(textScroll)
    val frameGroups=rememberUpdatedState(groups);val frameVisual=rememberUpdatedState(visual);val framePage=rememberUpdatedState(logicalPage)
    val frameTextPages=rememberUpdatedState(textPages.size);val frameGroupReady=rememberUpdatedState(groupLayoutReady)
    val frameTextGroups=rememberUpdatedState(textGroups)
    val frameScrollReady=rememberUpdatedState(scrollInitialized)
    val frameTotal=rememberUpdatedState(total)
    val framePosition=rememberUpdatedState(if(textScroll)textScrollFraction else if(textMode)logicalPage.toDouble()/(total-1).coerceAtLeast(1) else null)
    val frameCompleted=rememberUpdatedState(completionVisible)
    SideEffect { if(!textScroll || scrollInitialized) { vm.readerVisiblePage=logicalPage;vm.readerVisibleTotal=total
        vm.readerVisibleCompleted=completionVisible
        if(textMode)vm.readerVisiblePosition=framePosition.value } }
    DisposableEffect(manga.id,chapter.key) { vm.beginReadingSession(manga.id,chapter.key,chapter.page)
        onDispose { if(!frameTextScroll.value || frameScrollReady.value)vm.progress(manga.id,chapter.key,framePage.value,frameTotal.value,framePosition.value,completed=frameCompleted.value);vm.endReadingSession() } }
    LaunchedEffect(manga.id,chapter.key) {
        var firstVisible=emptySet<Int>()
        snapshotFlow {
            when {
                frameText.value -> if(frameTextScroll.value) { if(frameScrollReady.value)setOf(framePage.value) else emptySet() } else frameTextGroups.value.getOrNull(pager.currentPage).orEmpty().toSet()
                frameMode.value in setOf("webtoon","continuous") -> list.layoutInfo.visibleItemsInfo.mapNotNull { frameVisual.value.getOrNull(it.index)?.originalIndex }.toSet()
                else -> if(frameGroupReady.value)frameGroups.value.getOrNull(pager.currentPage).orEmpty().map { it.originalIndex }.toSet() else emptySet()
            }
        }.filter { it.isNotEmpty() }.distinctUntilChanged().onEach { if(firstVisible.isEmpty()){firstVisible=it;vm.initialReadingPages(manga.id,chapter.key,it)} }.drop(1)
            .collect { pages -> vm.readingInteraction(manga.id,chapter.key,pages+firstVisible) }
    }
    val currentUrl = chapter.pages.getOrNull(logicalPage).orEmpty()
    val currentVisual=groups.getOrNull(pager.currentPage)?.firstOrNull()?.takeUnless { continuous } ?: visual.firstOrNull { it.originalIndex==logicalPage } ?: VisualPage(currentUrl,logicalPage)
    fun quickAction(page:VisualPage?=null) { actionPage=page ?: currentVisual;quickActions=true }
    val actionTarget=actionPage ?: currentVisual
    val actionIsImage=!textMode && (if(ArchivePageStore.isArchive(actionTarget.url))Uri.parse(actionTarget.url).getQueryParameter("entry") else Uri.parse(actionTarget.url).path)
        ?.substringAfterLast('.')?.lowercase() !in setOf("txt","md")
    val imagePage=!textMode && (Uri.parse(currentUrl).path?.substringAfterLast('.')?.lowercase() !in setOf("txt","md"))
    val visibleDescriptionSources=when {
        richMode ->richSourceIndices
        textMode && !textScroll ->ReaderTextSources.indices(contentText,textGroups.getOrNull(pager.currentPage).orEmpty().mapNotNull{textPages.getOrNull(it)}).ifEmpty { setOf(0) }
        textMode ->setOf(0)
        continuous ->list.layoutInfo.visibleItemsInfo.mapNotNull { visual.getOrNull(it.index)?.originalIndex }.toSet()
        else ->groups.getOrNull(pager.currentPage).orEmpty().map { it.originalIndex }.toSet()
    }
    val descriptionIndices by produceState<Set<Int>>(emptySet(),visibleDescriptionSources,chapter.metadata,chapter.pages,source?.id,textMode,descriptionRetry) {
        value=withContext(Dispatchers.IO) {
            val inline=JSONObject(chapter.metadata).optJSONObject("pageDescriptions") ?: JSONObject()
            val available=(chapter.pages.indices+inline.keys().asSequence().mapNotNull(String::toIntOrNull).toList()).toSet().filter { index ->
                inline.optString(index.toString()).isNotBlank() || (source?.type=="wasm" && chapter.pages.getOrNull(index)?.let { url ->
                    !ArchivePageStore.isArchive(url) && runCatching { vm.repo.client.runtimes.runtime(source.id).hasPageDescription(url) }.getOrDefault(false)
                }==true)
            }.toSet()
            ReaderTextSources.descriptionIndices(visibleDescriptionSources,available,textMode)
        }
    }
    var descriptionLoading by remember { mutableStateOf(false) };var descriptionError by remember { mutableStateOf("") }
    val pageDescription by produceState("",showDescription,descriptionIndices,descriptionRetry,source?.id) {
        if(showDescription) { descriptionLoading=true;descriptionError="";try { value=withContext(Dispatchers.IO) {
            val inline=JSONObject(chapter.metadata).optJSONObject("pageDescriptions") ?: JSONObject()
            descriptionIndices.sorted().mapNotNull { index ->inline.optString(index.toString()).takeIf(String::isNotBlank) ?: chapter.pages.getOrNull(index)?.let { url ->
                if(source?.type=="wasm" && !ArchivePageStore.isArchive(url))vm.repo.client.runtimes.runtime(source.id).getPageDescription(url) else null
            } }.distinct().joinToString("\n\n")
        } }catch(error:CancellationException){throw error}catch(error:Exception){descriptionError=error.message.orEmpty()}finally{descriptionLoading=false} }
    }
    val chapterOrder=remember(manga.chapters,manga.metadata) { ChapterListing.chronological(manga) }
    fun targetChapter(delta:Int)=ChapterNavigator.target(chapterOrder,chapter,delta,options.skipDuplicates,options.markDuplicates) { ChapterListing.includes(manga,it) }
    fun nextChapter(delta:Int) { targetChapter(delta)?.let{vm.read(manga,it)} }
    fun reloadPage(page:VisualPage) { scope.launch {
        try { withContext(Dispatchers.IO) { ReaderPageCache.invalidate(context,page.url,source?.id) }
            reloadVersions[page.url]=(reloadVersions[page.url] ?: 0)+1
        } catch(error:CancellationException){throw error}catch(error:Exception){vm.message=error.message}
    } }
    fun saveOptions(next:ReaderOptions,global:Boolean=false) { options=next;store.save(next,if(global)null else manga.id)
        scope.launch { vm.repo.change { it.copy(settings=next.toLegacy(JSONObject(it.settings),if(global)null else manga.id).toString()) } }
    }
    fun toggleOffset() {
        if(!double || textMode || continuous)return
        val current=visual.indexOfFirst{it.originalIndex==logicalPage}.coerceAtLeast(0)
        val head=ReaderLayout.segmentHead(pairable,current) ?: return
        val first=pairable.indexOfFirst{it}
        if(head==first)saveOptions(options.copy(pageOffset=!options.pageOffset))
        else { val key=pageKey(visual[head]);isolatedHeads=if(key in isolatedHeads)isolatedHeads-key else isolatedHeads+key }
    }
    fun toggleManualIsolation(page:VisualPage) {
        val key=pageKey(page);isolatedHeads=if(key in isolatedHeads)isolatedHeads-key else isolatedHeads+key
    }
    fun step(delta: Int) { scope.launch {
        ReaderInput.trace("step delta=$delta pager=${pager.currentPage} count=$count transition=$transition")
        transition?.let { current -> if(current==delta)nextChapter(delta) else transition=null;return@launch }
        when { richMode -> richTarget=(richFraction+delta*.1).coerceIn(0.0,1.0)
            textScroll -> scroll.animateScrollBy(delta * viewportHeight * .8f)
            continuous -> { val target = list.firstVisibleItemIndex + delta; if (target in visual.indices) list.animateScrollToItem(target) else if (delta > 0) nextChapter(1) }
            else -> { val target = pager.currentPage + delta; if (target in 0 until count) {
                if (options.animateTransitions) pager.animateScrollToPage(target) else pager.scrollToPage(target)
                ReaderInput.trace("step complete target=$target pager=${pager.currentPage}")
            } else transition=delta }
        }
    } }
    fun keyboardStep(delta:Int) {
        ReaderInput.trace("keyboard delta=$delta continuous=$continuous rich=$richMode textScroll=$textScroll pager=${pager.currentPage} count=$count")
        if(continuous)scope.launch { list.animateScrollBy(delta*viewportHeight*2f/3) }
        else if(richMode)richTarget=(richFraction+delta*viewportHeight*2.0/3/richScrollExtent.coerceAtLeast(1f)).coerceIn(0.0,1.0)
        else if(textScroll)scope.launch { scroll.animateScrollBy(delta*viewportHeight*2f/3) }
        else step(delta)
    }
    fun tap(position: Offset, width: Int, height: Int) {
        val x = position.x / width.coerceAtLeast(1); val y = position.y / height.coerceAtLeast(1)
        val delta = TapZones.action(options.tapZones, x, y, continuous)
        if (delta == 0) chrome = !chrome else step(if (options.invertTapZones xor (mode == "rtl" && options.tapZones != "vertical")) -delta else delta)
    }
    fun lookup(page:VisualPage?=null,point:Pair<Float,Float>?=null) { val target=page ?: currentVisual;scope.launch {
        try {
            dictionaryPoint=point
            dictionaryPage=if(textMode)logicalPage else target.originalIndex
            val targetText=(if(ArchivePageStore.isArchive(target.url))Uri.parse(target.url).getQueryParameter("entry") else Uri.parse(target.url).path)?.substringAfterLast('.')?.lowercase() in setOf("txt","md")
            dictionaryText=if(textMode)textPages.getOrNull(logicalPage)?.let { contentText.subSequence(it.start,it.end).toString() } ?: contentText.toString()
                else if(targetText)withContext(Dispatchers.IO) { val bytes=if(ArchivePageStore.isArchive(target.url))ArchivePageStore.bytes(context,target.url) else
                    context.contentResolver.openInputStream(Uri.parse(target.url))?.use { stream ->java.io.ByteArrayOutputStream().also { ArchiveImporter.copyBounded(stream,it,5L*1024*1024) }.toByteArray() } ?: byteArrayOf();bytes.toString(Charsets.UTF_8) } else ""
            dictionaryUri = if (textMode || targetText || target.url.isBlank()) "" else withContext(Dispatchers.IO) {
                val bitmap=loadPageBitmap(context,vm,source,target,options)
                val file = File(context.cacheDir, "reader-ocr/${AppRepository.hash("${target.url}:${target.half}")}.png"); file.parentFile?.mkdirs()
                file.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)) }
                ReaderPageCache.touch(context,file);Uri.fromFile(file).toString()
            }
            dictionary = true
        } catch (e: Exception) { vm.message = e.message }
    } }
    val saveImage = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val target=pendingSavePage;pendingSavePage=null
        if (uri != null && target!=null) vm.run { withContext(Dispatchers.IO) {
            val bitmap=loadPageBitmap(context,vm,source,target,options)
            context.contentResolver.openOutputStream(uri,"wt")?.use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)) } ?: error("无法保存页面")
        } }
    }
    DisposableEffect(options.keepScreenOn, options.brightness, options.orientation, options.fullscreen) {
        val activity = context as? Activity; val window = activity?.window
        val oldBrightness = window?.attributes?.screenBrightness; val oldOrientation = activity?.requestedOrientation
        val oldFlags = window?.decorView?.systemUiVisibility ?: 0
        if (options.keepScreenOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window?.attributes = window?.attributes?.apply { screenBrightness = options.brightness }
        if (options.fullscreen) window?.decorView?.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        activity?.requestedOrientation = when (options.orientation) { "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT; "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE; "locked" -> ActivityInfo.SCREEN_ORIENTATION_LOCKED; else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); window?.decorView?.systemUiVisibility = oldFlags
            if (oldBrightness != null) window?.attributes = window?.attributes?.apply { screenBrightness = oldBrightness }
            if (oldOrientation != null) activity?.requestedOrientation = oldOrientation }
    }
    SideEffect {
        ReaderInput.enabled=options.volumeKeys;ReaderInput.mode=if(textMode)"ltr" else mode;ReaderInput.onPage=::keyboardStep
        ReaderInput.onOffset=::toggleOffset;ReaderInput.onChapter=::nextChapter;ReaderInput.onChapterList={showChapters=true}
        ReaderInput.onBars={chrome=!chrome};ReaderInput.onClose=vm::back
        ReaderInput.canHandle={ !settings && !showChapters && !quickActions && !showDescription && !dictionary && vm.message==null
            && (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)?.isAcceptingText!=true
            && (context as? Activity)?.currentFocus?.onCheckIsTextEditor()!=true }
    }
    DisposableEffect(Unit) { onDispose { ReaderInput.clear() } }
    DisposableEffect(chapter.key) { onDispose {
        val current = vm.repo.state.value.manga.firstOrNull { it.id == manga.id }?.chapters?.firstOrNull { it.key == chapter.key }
        if (current?.completed == true && current.downloaded && manga.sourceId != "local" && legacySettings.bool("Library.deleteDownloadAfterReading")) {
            CoroutineScope(Dispatchers.IO).launch { DownloadService.get(context).remove(manga.id, chapter.key) }
        }
    } }
    fun sharePage(copy: Boolean,target:VisualPage=actionTarget) { scope.launch {
        try {
            val uri = withContext(Dispatchers.IO) {
                val bitmap=loadPageBitmap(context,vm,source,target,options)
                val file=File(context.cacheDir,"reader-shared/${AppRepository.hash("${target.url}:${target.half}")}.png");file.parentFile?.mkdirs()
                file.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)) }
                FileProvider.getUriForFile(context,"${context.packageName}.files",file)
            }
            if (copy) (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newUri(context.contentResolver,manga.title,uri))
            else context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/*").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),null))
        } catch (e: Exception) { vm.message=e.message }
    } }
    val visibleSplit=groups.getOrNull(pager.currentPage)?.firstOrNull()?.half.takeUnless { textMode || continuous }
    LaunchedEffect(logicalPage,lastVisiblePage,total,scrollInitialized,textPages.size,groupLayoutReady,completionVisible,visibleSplit,if(textScroll)textScrollFraction else null) {
        if (total <= 0 || !textMode && !continuous && !groupLayoutReady || textScroll && !scrollInitialized || textMode && !textScroll && textPages.isEmpty()) return@LaunchedEffect
        if(textScroll)delay(150)
        vm.progress(manga.id, chapter.key, logicalPage, total,position=framePosition.value,completed=completionVisible)
        if(visibleSplit!=null)vm.repo.updateChapter(manga.id,chapter.key) { current ->current.copy(metadata=JSONObject(current.metadata).put("splitIndex",visibleSplit).put("splitSourceIndex",logicalPage).toString()) }
        if(completionVisible && chapter.downloaded && manga.sourceId!="local" && legacySettings.bool("Library.deleteDownloadAfterReading") && !state.preferences.incognito) {
            withContext(Dispatchers.IO) { DownloadService.get(context).markForDeletion(manga.id,chapter.key) }
        }
        if (options.markDuplicates && completionVisible && total > 0 && !state.preferences.incognito) {
            manga.chapters.filter { it.key!=chapter.key && ChapterNavigator.areDuplicates(it,chapter) }.forEach { vm.progress(manga.id, it.key, it.total.coerceAtLeast(1) - 1, it.total.coerceAtLeast(1)) }
        }
    }
    LaunchedEffect(chapter.key, options.preloadPages, logicalPage) {
        if (options.preloadPages > 0 && !textMode) withContext(Dispatchers.IO) {
            chapter.pages.drop(logicalPage + 1).take(options.preloadPages).forEach { url ->
                val data = if (ArchivePageStore.isArchive(url)) ArchivePageStore.materialize(context, url) else if (source?.type == "wasm" && url.startsWith("http")) readerMaterialize(context, vm, source, url) else url
                context.imageLoader.enqueue(pageRequest(context, vm, source, data, options, null))
            }
        }
    }
    var previousTextPages by remember { mutableStateOf<List<TextPage>>(emptyList()) };var previousTextDouble by remember { mutableStateOf(false) }
    LaunchedEffect(textPages,textScroll,double) {
        if(textMode && !textScroll && textPages.isNotEmpty()) {
            val offset=previousTextPages.getOrNull(pager.currentPage*(if(previousTextDouble)2 else 1))?.start
            val target=if(offset!=null)textPages.indexOfLast{it.start<=offset}.coerceAtLeast(0) else (savedFraction*textPages.lastIndex).toInt()
            pager.scrollToPage(textGroups.indexOfFirst { target in it }.coerceIn(0,count-1))
            previousTextPages=textPages;previousTextDouble=double
        }
    }
    fun saveToGallery(target:VisualPage) { vm.run { withContext(Dispatchers.IO) {
        val image=loadPageBitmap(context,vm,source,target,options)
        ReaderImages.saveGallery(context,image,"${manga.title}-${target.originalIndex+1}")
    } } }
    val galleryPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted)pendingGalleryPage?.let(::saveToGallery)
        pendingGalleryPage=null
    }
    fun gallery() {
        if(android.os.Build.VERSION.SDK_INT<=28 && context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingGalleryPage=actionTarget;galleryPermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else saveToGallery(actionTarget)
    }
    LaunchedEffect(pager.isScrollInProgress, list.isScrollInProgress, scroll.isScrollInProgress) {
        if (options.hideBarsOnSwipe && (pager.isScrollInProgress || list.isScrollInProgress || scroll.isScrollInProgress)) chrome = false
    }
    LaunchedEffect(chapter.key,continuous,textScroll,options.infiniteScroll) {
        if(!options.infiniteScroll || !continuous && !textScroll || richMode)return@LaunchedEffect
        var scrolled=false
        snapshotFlow { if(continuous)list.isScrollInProgress to list.canScrollForward else scroll.isScrollInProgress to scroll.canScrollForward }
            .distinctUntilChanged().collect { (moving,canForward) ->
                if(moving)scrolled=true
                if(scrolled && !moving && !canForward) { scrolled=false;nextChapter(1) }
            }
    }
    LaunchedEffect(autoScroll, options.autoScrollSpeed) {
        if(richMode)return@LaunchedEffect
        while (autoScroll) { delay(16)
            if (list.isScrollInProgress || scroll.isScrollInProgress) continue
            val pixels = options.autoScrollSpeed.coerceIn(1, 10) * 100 * density.density / 60f
            if (continuous) { val moved = list.scrollBy(pixels); if (moved == 0f) { autoScroll = false; if (options.infiniteScroll) nextChapter(1) } }
            else if (textScroll) { val moved = scroll.scrollBy(pixels); if (moved == 0f) { autoScroll = false; if (options.infiniteScroll) nextChapter(1) } }
            else autoScroll = false
        }
    }
    LaunchedEffect(richFraction,options.infiniteScroll) {
        if(richMode && richUserScrolled && richFraction>=.999 && options.infiniteScroll) { richUserScrolled=false;nextChapter(1) }
    }
    val background = when (options.background) { "white" -> Color.White; "gray" -> Color.DarkGray; "system" -> MaterialTheme.colorScheme.background; else -> Color.Black }
    val darkText = options.textAppearance == "dark" || options.textAppearance == "system" && isSystemInDarkTheme()
    val textBackground = when (options.textTheme) { "dark" -> Color(0xFF171717); "sepia" -> Color(if (darkText) 0xFF3B3226 else 0xFFFAF1E3)
        "paper" -> Color(if (darkText) 0xFF2E2E2B else 0xFFF2F1EC); "gray" -> Color(if (darkText) 0xFF414141 else 0xFFD1D1D6)
        "green" -> Color(if (darkText) 0xFF23382B else 0xFFD6E8D4); "light" -> Color.White; else -> if (darkText) Color.Black else Color.White }
    val textColor = if (darkText || options.textTheme == "dark") Color.White.copy(alpha=.9f) else Color.Black.copy(alpha=.9f)
    BoxWithConstraints(Modifier.fillMaxSize().focusRequester(readerFocus).focusable(!readerModal)
        .background(if (textMode) textBackground else background)
        .pointerInput(mode,chapter.key,pager.currentPage,count,pageZoomed,double) {
            var doubleTapAt=0L
            awaitEachGesture {
                val down=awaitFirstDown(requireUnconsumed=false);val start=down.position;var end=start;var pointers=1
                var pressed=true
                while(pressed) { val event=awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Final)
                    pointers=maxOf(pointers,event.changes.count{it.pressed});event.changes.firstOrNull{it.id==down.id}?.let{end=it.position};pressed=event.changes.any{it.pressed}
                }
                val distance=end-start;val now=System.currentTimeMillis()
                if(pointers==2 && distance.getDistance()<24*density.density) {
                    if(now-doubleTapAt<350){toggleOffset();doubleTapAt=0}else doubleTapAt=now
                } else if(!continuous && !textScroll && !pageZoomed && pointers==1) {
                    val movement=if(mode=="vertical")distance.y else distance.x
                    if(kotlin.math.abs(movement)>96*density.density) {
                        val direction=if(movement*(if(mode=="rtl")1 else -1)>0)1 else -1
                        if(direction>0 && pager.currentPage==count-1 || direction<0 && pager.currentPage==0) {
                            if(transition==direction)nextChapter(direction) else transition=direction
                        }
                    }
                }
            }
        }) {
        SideEffect { viewportWidth = with(density) { maxWidth.roundToPx() }; viewportHeight = with(density) { maxHeight.roundToPx() } }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        when {
            textMode && chapter.html.isBlank() && markdownDocument==null -> Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator()}
            richMode -> RichEpub(chapter.html.ifBlank { markdownDocument.orEmpty() }, options, context.filesDir, richTarget,{ richFraction=it;scrollInitialized=true },autoScroll,
                {autoScroll=false;if(options.infiniteScroll)nextChapter(1)},{richUserScrolled=true},pageCount={richEstimatedPages=it},scrollExtent={richScrollExtent=it},sourceMapping=chapter.html.isBlank(),sourceIndices={indices->richSourceIndices=indices.filter { it in sourceTexts.indices }.toSet().ifEmpty { setOf(0) }},link={ url ->
                    val target=manga.chapters.firstOrNull { candidate ->candidate.html.isNotBlank() && Uri.parse(candidate.html).path==Uri.parse(url).path }
                    if(target!=null && target.key!=chapter.key)vm.read(manga,target)
                    else if(Uri.parse(url).scheme in setOf("http","https","mailto"))runCatching { context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url))) }
                }) { chrome = !chrome }
            textScroll -> SelectionContainer { Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = options.textMargins.dp, vertical = 56.dp)
                .pointerInput(options) { detectTapGestures(onTap = { tap(it, size.width, size.height) }) }) {
                chapter.text.split("\n\n").forEach { paragraph -> Text(paragraph, color = textColor, fontSize = options.fontSize.sp,
                    lineHeight = (options.fontSize * options.lineSpacing).sp, fontFamily = readerFont(options), modifier = Modifier.padding(bottom = options.paragraphSpacing.dp)) }
            } }
            textMode -> {
                val spreadContent:@Composable (Int)->Unit={spreadIndex ->
                Row(Modifier.fillMaxSize()) { (textGroups.getOrNull(spreadIndex).orEmpty().let { if(mode=="rtl")it.reversed() else it }).forEach { index ->
                val range = textPages.getOrNull(index)
                Box(Modifier.weight(1f).fillMaxSize().padding(horizontal = options.textMargins.dp, vertical = 56.dp)
                    .pointerInput(options) { detectTapGestures(onTap = { tap(it, size.width, size.height) }) }) {
                    ReaderStyledText(range?.let { contentText.subSequence(it.start,it.end) } ?: contentText,options,textColor) }
                }
                }
                }
                if(mode=="vertical")VerticalPager(pager,Modifier.fillMaxSize()){spreadContent(it)}
                else HorizontalPager(pager,Modifier.fillMaxSize(),reverseLayout=mode=="rtl"){spreadContent(it)}
            }
            continuous -> LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(horizontal =
                if (options.pillarbox && (options.pillarboxOrientation == "both" || (options.pillarboxOrientation == "landscape") == (viewportWidth > viewportHeight))) maxWidth * (options.pillarboxAmount.coerceIn(0, 95) / 200f) else 0.dp),
                verticalArrangement = Arrangement.spacedBy(if (mode == "continuous") 12.dp else 0.dp)) {
                items(visual, key = { "${it.originalIndex}:${it.half}" }) { p -> ReaderImage(p, source, vm, options, Modifier.fillMaxWidth().heightIn(min = 100.dp), true,
                    { dimensions[p.url] = it }, { position, width, height -> tap(position, width, height) }, {page,point->lookup(page,point)}, {quickAction(it)},reloadVersion=reloadVersions[p.url] ?: 0) }
                item { TextButton({ nextChapter(1) }, Modifier.fillMaxWidth().padding(24.dp)) { Text(tr("NEXT_COLON")) } }
            }
            mode == "vertical" -> VerticalPager(pager, Modifier.fillMaxSize()) { index -> ReaderSpread(groups.getOrNull(index).orEmpty(), source, vm, options, mode, { url, ratio -> dimensions[url] = ratio }, ::tap, {page,point->lookup(page,point)}, {quickAction(it)},index==pager.currentPage,{pageZoomed=it},{reloadVersions[it.url] ?: 0}) }
            else -> HorizontalPager(pager, Modifier.fillMaxSize(), reverseLayout = mode == "rtl") { index -> ReaderSpread(groups.getOrNull(index).orEmpty(), source, vm, options, mode, { url, ratio -> dimensions[url] = ratio }, ::tap, {page,point->lookup(page,point)}, {quickAction(it)},index==pager.currentPage,{pageZoomed=it},{reloadVersions[it.url] ?: 0}) }
        }
        }
        transition?.let { direction -> ReaderChapterTransition(chapter,targetChapter(direction),direction,vm.busy,{nextChapter(direction)},{transition=null}) }
        if (options.colorTint != 0L && options.tintOpacity > 0f) Box(Modifier.fillMaxSize().background(Color(options.colorTint).copy(alpha = options.tintOpacity)))
        if (chrome) {
            Row(Modifier.fillMaxWidth().align(Alignment.TopCenter).background(Color.Black.copy(alpha = .83f)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(vm::back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, tr("CLOSE_READER"), tint = Color.White) }
                Column(Modifier.weight(1f).clickable { showChapters = true }) { Text(manga.title, color = Color.White, maxLines = 1); Text(chapter.title, color = Color.LightGray, fontSize = 12.sp, maxLines = 1) }
                IconButton({lookup()}) { Icon(Icons.Outlined.Translate, tr("DICTIONARY_LOOKUP"), tint = Color.White) }
                if (descriptionIndices.isNotEmpty()) IconButton({ showDescription=true }) { Icon(Icons.Outlined.Info, tr("PAGE_SINGULAR"), tint=Color.White) }
                IconButton({ quickAction() }) { Icon(Icons.Outlined.MoreVert, tr("MORE"), tint = Color.White) }
                IconButton({ settings = true }) { Icon(Icons.Outlined.Settings, tr("READER_SETTINGS"), tint = Color.White) }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = .83f)).padding(12.dp)) {
                Text(if (textScroll) "$scrollPercent%" else "${logicalPage + 1} / $total", color = Color.White, modifier = Modifier.align(Alignment.CenterHorizontally))
                if (textScroll || total > 1) Slider(if(textScroll)scrollPercent.toFloat() else logicalPage.toFloat().coerceIn(0f, (total - 1).toFloat()), { value ->
                    val previewPage = (if (textScroll) (value / 100 * total).toInt() else value.toInt()).coerceIn(0, (total - 1).coerceAtLeast(0))
                    if (previewPage != lastSliderPage) {
                        lastSliderPage = previewPage
                        hapticView.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    }
                    scope.launch {
                    when { richMode -> richTarget=value/100.0;textScroll -> scroll.scrollTo((value / 100 * scroll.maxValue).toInt()); textMode -> pager.scrollToPage(textGroups.indexOfFirst { value.toInt() in it }.coerceIn(0,count-1))
                        continuous -> list.scrollToItem(visual.indexOfFirst { it.originalIndex >= value.toInt() }.coerceAtLeast(0))
                        else -> pager.scrollToPage(groups.indexOfFirst { g -> g.any { it.originalIndex >= value.toInt() } }.coerceAtLeast(0)) }
                } }, valueRange = 0f..(if(textScroll)100f else (total - 1).toFloat()))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton({ nextChapter(-1) }, enabled = targetChapter(-1)!=null) { Text(tr("PREVIOUS_COLON")) }
                    TextButton({ showChapters = true }) { Text(tr("CHAPTERS")) }
                    TextButton({ nextChapter(1) }, enabled = targetChapter(1)!=null) { Text(tr("NEXT_COLON")) }
                }
            }
        } else if (options.showPageNumbers) Text(if (textScroll) "$scrollPercent%" else "${logicalPage + 1} / $total", color = Color.White,
            modifier = Modifier.align(Alignment.BottomCenter).background(Color.Black.copy(alpha = .4f)).padding(8.dp), fontSize = 12.sp)
        if (options.autoScrollButton && (continuous || textScroll)) FloatingActionButton({ autoScroll = !autoScroll }, Modifier.align(if (options.autoScrollPosition == "left") Alignment.CenterStart else Alignment.CenterEnd).padding(8.dp)) {
            Icon(if (autoScroll) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, tr("AUTO_SCROLL"))
        }
        if (options.liveText && !textMode) FilledTonalIconButton({lookup()}, Modifier.align(Alignment.BottomEnd).padding(end=16.dp, bottom=if (chrome) 160.dp else 48.dp)) {
            Icon(Icons.Outlined.TextFields, tr("LIVE_TEXT"))
        }
    }
    if (settings) ReaderSettingsDialog(options,::saveOptions, { settings = false })
    if (showChapters) {
        val listed = chapterOrder.filter { ChapterListing.includes(manga, it) }
        OptionSheet(tr("CHAPTERS"), { showChapters = false }, subtitle = manga.title, scrollable = false) {
            val close = LocalSheetClose.current
            val list = rememberLazyListState((listed.indexOfFirst { it.key == chapter.key } - 3).coerceAtLeast(0))
            LazyColumn(Modifier.fillMaxWidth(), state = list, contentPadding = PaddingValues(horizontal = 12.dp)) {
                items(listed, key = { it.key }) { c ->
                    val current = c.key == chapter.key
                    Row(Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).background(if (current) Pink.copy(alpha = .1f) else Color.Transparent)
                        .clickable { close(); if (!current) vm.read(manga, c) }.padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(c.title.ifBlank { ChapterListing.number(c)?.let { tr("CHAPTER_X", it) } ?: tr("CHAPTER") }, Modifier.weight(1f), fontSize = 15.sp, maxLines = 2,
                            fontWeight = if (current) androidx.compose.ui.text.font.FontWeight.Medium else null,
                            color = when { current -> Pink; c.completed -> MaterialTheme.colorScheme.onSurfaceVariant; else -> MaterialTheme.colorScheme.onSurface })
                        if (c.downloaded) Icon(Icons.Outlined.DownloadDone, tr("DOWNLOADED"), Modifier.padding(start = 8.dp).size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (current) Icon(Icons.Outlined.PlayArrow, null, Modifier.padding(start = 8.dp).size(18.dp), tint = Pink)
                    }
                }
            }
        }
    }
    if (quickActions) OptionSheet(tr("PAGE_X", actionTarget.originalIndex + 1), { quickActions = false }) {
        val close = LocalSheetClose.current
        val image = actionIsImage && actionTarget.url.isNotBlank()
        SheetActions(buildList {
            add(SheetActionItem(tr("DICTIONARY_LOOKUP"), Icons.Outlined.Translate) { close(); lookup(actionTarget) })
            if (double && !textMode && !continuous) {
                add(SheetActionItem(tr("PAGE_OFFSET"), Icons.Outlined.SwapHoriz) { close(); toggleOffset() })
                add(SheetActionItem(tr(if (pageKey(actionTarget) in isolatedHeads) "UNSET_AS_SINGLE_PAGE" else "SET_AS_SINGLE_PAGE"), Icons.Outlined.CropPortrait) { close(); toggleManualIsolation(actionTarget) })
            }
        })
        if (image) { Spacer(Modifier.height(12.dp)); SheetActions(listOf(
            SheetActionItem(tr("SAVE"), Icons.Outlined.SaveAlt) { close(); pendingSavePage = actionTarget; saveImage.launch("${manga.title.take(40)}-${actionTarget.originalIndex + 1}.png") },
            SheetActionItem(tr("SAVE_TO_PHOTOS"), Icons.Outlined.PhotoLibrary) { close(); gallery() },
            SheetActionItem(tr("COPY"), Icons.Outlined.ContentCopy) { close(); sharePage(true) },
            SheetActionItem(tr("SHARE"), Icons.Outlined.Share) { close(); sharePage(false) },
            SheetActionItem(tr("RELOAD"), Icons.Outlined.Refresh) { close(); reloadPage(actionTarget) })) }
        Spacer(Modifier.height(12.dp))
        SheetActions(listOf(SheetActionItem(tr("MARK_READ"), Icons.Outlined.DoneAll) { close(); vm.progress(manga.id, chapter.key, total.coerceAtLeast(1) - 1, total.coerceAtLeast(1)) }))
    }
    if (showDescription) AlertDialog({ showDescription=false },title={Text(tr("PAGE_X",logicalPage+1))},text={Column {
        if(descriptionLoading)CircularProgressIndicator()
        else if(descriptionError.isNotBlank()){Text(descriptionError,color=MaterialTheme.colorScheme.error);TextButton({descriptionRetry++}){Text(tr("RETRY"))}}
        else SelectionContainer { Text(pageDescription.ifBlank { tr("UNAVAILABLE") }) }
    }},confirmButton={TextButton({showDescription=false}){Text(tr("DONE"))}})
    // The dictionary agent supplies OCR, word selection, lookup and vocabulary persistence.
    if (dictionary) ReaderDictionaryOverlay(manga.id, chapter.key, dictionaryPage, dictionaryUri,dictionaryText,initialPoint=dictionaryPoint,
        sourceLanguage=ReaderImageGeometry.language(chapter.language,source?.configuration,manga.language)) { dictionary = false }
}

private fun readerFont(options: ReaderOptions) = ReaderFonts.family(options.textFont)
private suspend fun loadPageBitmap(context:Context,vm:MonogatariViewModel,source:SourceConfig?,page:VisualPage,options:ReaderOptions):android.graphics.Bitmap {
    val data=if(ArchivePageStore.isArchive(page.url))ArchivePageStore.materialize(context,page.url) else if(source?.type=="wasm" && page.url.startsWith("http"))readerMaterialize(context,vm,source,page.url) else page.url
    val result=context.imageLoader.execute(pageRequest(context,vm,source,data,options,page.half).newBuilder().allowHardware(false).build())
    if(result is coil.request.ErrorResult)throw result.throwable
    return (result as coil.request.SuccessResult).drawable.toBitmap(config=android.graphics.Bitmap.Config.ARGB_8888)
}

@Composable private fun ReaderStyledText(text:CharSequence,options:ReaderOptions,color:Color) {
    AndroidView(factory={context->android.widget.TextView(context).apply {
        includeFontPadding=false;setTextIsSelectable(true);movementMethod=android.text.method.LinkMovementMethod.getInstance()
        textDirection=View.TEXT_DIRECTION_FIRST_STRONG
        breakStrategy=android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY
    }},modifier=Modifier.fillMaxSize(),update={view ->view.text=text;view.textSize=options.fontSize.toFloat()
        view.typeface=ReaderFonts.typeface(options.textFont);view.setLineSpacing(0f,options.lineSpacing);view.setTextColor(color.toArgb()) })
}

private fun pageRequest(context: android.content.Context, vm: MonogatariViewModel, source: SourceConfig?, url: String, options: ReaderOptions, half: Int?): ImageRequest {
    return ImageRequest.Builder(context).data(url).crossfade(false).apply {
        if (source != null && url.startsWith("http")) vm.repo.client.imageRequest(source, url).headers.forEach { (k, v) -> addHeader(k, v) }
        // Decode the original pixels so cropping precedes downsampling, AI processing and spread splitting.
        size(Size.ORIGINAL)
        val processor=ReaderPageProcessing.transformation(context,options,half,
            SettingsStore(vm.repo).string("Data.enabledModelFile"),SettingsStore(vm.repo).string("Reader.upscaleQuality")=="best")
        processor?.let { transformations(it) }
        allowHardware(processor==null && options.background!="auto")
    }.build()
}

@Composable private fun ReaderSpread(pages: List<VisualPage>, source: SourceConfig?, vm: MonogatariViewModel, options: ReaderOptions, mode: String,
    dimension: (String, Float) -> Unit, tap: (Offset, Int, Int) -> Unit, lookup: (VisualPage,Pair<Float,Float>?) -> Unit, quickAction: (VisualPage) -> Unit,active:Boolean=true,onZoom:(Boolean)->Unit={},reloadVersion:(VisualPage)->Int={0}) {
    var scale by remember(pages) { mutableFloatStateOf(1f) };var offset by remember(pages) { mutableStateOf(Offset.Zero) }
    val transform=rememberTransformableState { zoom,pan,_ ->scale=(scale*zoom).coerceIn(1f,8f);offset=if(scale==1f)Offset.Zero else offset+pan }
    val collective=pages.size>1
    LaunchedEffect(scale,active){if(active && collective)onZoom(scale>1f)}
    Box(Modifier.fillMaxSize().clipToBounds()) {
        Row(Modifier.fillMaxSize().then(if(collective)Modifier.graphicsLayer {
            scaleX=scale;scaleY=scale;translationX=offset.x.coerceIn(-size.width*(scale-1)/2,size.width*(scale-1)/2);translationY=offset.y.coerceIn(-size.height*(scale-1)/2,size.height*(scale-1)/2)
        }.transformable(transform,canPan={scale>1f}) else Modifier)) { (if (mode == "rtl") pages.reversed() else pages).forEachIndexed { index,page ->
            ReaderImage(page, source, vm, options, Modifier.weight(1f).fillMaxHeight(), false, { dimension(page.url, it) },
                {position,width,height ->tap(Offset(position.x+index*width,position.y),width*pages.size,height)},lookup,quickAction,
                zoomEnabled=!collective,onSpreadDoubleTap=if(collective)({scale=if(scale>1f)1f else 2.5f;offset=Offset.Zero}) else null,active=active && !collective,onZoom=onZoom,reloadVersion=reloadVersion(page))
        } }
    }
}

@Composable private fun ReaderImage(page: VisualPage, source: SourceConfig?, vm: MonogatariViewModel, options: ReaderOptions, modifier: Modifier, continuous: Boolean,
    dimension: (Float) -> Unit, tap: (Offset, Int, Int) -> Unit, lookup: (VisualPage,Pair<Float,Float>?) -> Unit, quickAction: (VisualPage) -> Unit,
    zoomEnabled:Boolean=true,onSpreadDoubleTap:(()->Unit)?=null,active:Boolean=true,onZoom:(Boolean)->Unit={},reloadVersion:Int=0) {
    val context = LocalContext.current
    val scope=rememberCoroutineScope()
    val configuration=LocalConfiguration.current
    var scale by remember(page) { mutableFloatStateOf(1f) }; var offset by remember(page) { mutableStateOf(Offset.Zero) }
    var imageWidth by remember(page) { mutableIntStateOf(0) };var imageHeight by remember(page) { mutableIntStateOf(0) }
    var layerWidth by remember(page) { mutableIntStateOf(0) };var layerHeight by remember(page) { mutableIntStateOf(0) }
    LaunchedEffect(scale,active){if(active)onZoom(scale>1f)}
    var error by remember(page) { mutableStateOf(false) }; var retry by remember(page) { mutableIntStateOf(0) }
    var autoBackground by remember(page) { mutableStateOf(listOf(android.graphics.Color.BLACK)) }
    val resolved by produceState(page.url.takeUnless { source?.type == "wasm" && it.startsWith("http") }.orEmpty(), page.url, retry, source?.id,reloadVersion) {
        try { if (ArchivePageStore.isArchive(page.url)) value = withContext(Dispatchers.IO) { ArchivePageStore.materialize(context, page.url) }
        else if (source?.type == "wasm" && page.url.startsWith("http")) {
            value = ""
            value = readerMaterialize(context, vm, source, page.url)
        } else value = page.url }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = true; vm.message = e.message }
    }
    val isText = Uri.parse(resolved).path?.substringAfterLast('.')?.lowercase() in setOf("txt", "md")
    val text by produceState("", resolved,retry,reloadVersion) { if (isText)try { value = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(Uri.parse(resolved))?.use { input -> java.io.ByteArrayOutputStream().also { ArchiveImporter.copyBounded(input, it, 5L * 1024 * 1024) }.toString(Charsets.UTF_8.name()) }.orEmpty()
    } } catch(e:CancellationException){throw e}catch(e:Exception){error=true;vm.message=e.message} }
    val markdown by produceState<String?>(null,text,isText,retry) { if(isText) value=withContext(Dispatchers.IO) {
        ReaderMarkdown.materialize(context,text) { url ->loadPageBitmap(context,vm,source,VisualPage(url,page.originalIndex),options.copy(upscale=false,cropBorders=false)) }
    } }
    val styled=remember(markdown,options.textMargins,options.paragraphSpacing) { markdown?.let { ReaderMarkdown.spans(context,it,
        (context.resources.displayMetrics.widthPixels-(options.textMargins*2*context.resources.displayMetrics.density)).toInt(),context.resources.displayMetrics.heightPixels,
        (options.paragraphSpacing*context.resources.displayMetrics.density).toInt()) } }
    val enabledModel = SettingsStore(vm.repo).string("Data.enabledModelFile")
    val upscaleQuality = SettingsStore(vm.repo).string("Reader.upscaleQuality")
    val request = remember(resolved, page.half, retry,reloadVersion, configuration.screenWidthDp, configuration.densityDpi, options.cropBorders, options.downsample, options.upscale, options.upscaleMaxHeight, options.background, enabledModel, upscaleQuality) {
        pageRequest(context, vm, source, resolved, options, page.half).newBuilder().apply {
            if(reloadVersion>0 || retry>0)setParameter("monogatari-reader-reload","$reloadVersion:$retry")
        }.build()
    }
    val transform = rememberTransformableState { zoom, pan, _ -> scale = (scale * zoom).coerceIn(1f, 8f); offset = if (scale == 1f) Offset.Zero else offset + pan }
    val matrix = remember(options.grayscale, options.invertColors, options.contrast) {
        ColorMatrix().apply { if (options.grayscale) setToSaturation(0f)
            val contrast = ColorMatrix(floatArrayOf(options.contrast,0f,0f,0f,128f*(1-options.contrast),0f,options.contrast,0f,0f,128f*(1-options.contrast),0f,0f,options.contrast,0f,128f*(1-options.contrast),0f,0f,0f,1f,0f)); timesAssign(contrast)
            if (options.invertColors) timesAssign(ColorMatrix(floatArrayOf(-1f,0f,0f,0f,255f,0f,-1f,0f,0f,255f,0f,0f,-1f,0f,255f,0f,0f,0f,1f,0f))) }
    }
    val dictionaryState by vm.state.collectAsStateWithLifecycle()
    val dictionarySettings = remember(vm.repo, dictionaryState.settings) { SettingsStore(vm.repo) }
    val lookupGesture = dictionarySettings.string("Dictionary.lookupGesture")
    val language=ReaderImageGeometry.language(vm.readerChapter?.language.orEmpty(),source?.configuration,
        dictionaryState.manga.firstOrNull { it.id==vm.key }?.language.orEmpty())
    val lookupEnabled=ReaderImageGeometry.lookupEnabled(dictionarySettings.bool("Dictionary.enable"),dictionarySettings.bool("Dictionary.restrictOCRLanguages"),language,
        (dictionarySettings.value("Dictionary.restrictedOCRLanguages") as? org.json.JSONArray).strings().toSet(),isText)
    Box(modifier.then(if(options.background=="auto") Modifier.background(Brush.verticalGradient(autoBackground.map { Color(it) }.let { if(it.size==1)it+it else it })) else Modifier)
        .clipToBounds().pointerInput(page, options, lookupEnabled, lookupGesture,zoomEnabled,continuous) {
        fun point(position:Offset)=ReaderImageGeometry.normalizedTap(position.x,position.y,size.width.toFloat(),size.height.toFloat(),imageWidth.toFloat(),imageHeight.toFloat(),
            if(continuous)"width" else options.fit,scale,offset.x,offset.y,layerWidth.toFloat(),layerHeight.toFloat())
        detectTapGestures(onTap = { position ->if (lookupEnabled && lookupGesture == "single-tap") {
            if(isText)lookup(page,null) else point(position)?.let { lookup(page,it) } ?: tap(position,size.width,size.height)
        } else tap(position, size.width, size.height) },
            onLongPress = { position ->if (lookupEnabled && lookupGesture == "long-press") { if(isText)lookup(page,null) else point(position)?.let { lookup(page,it) } }
                else if (!options.disableQuickActions) quickAction(page) },
            onDoubleTap = if (options.disableDoubleTap || lookupEnabled && lookupGesture == "single-tap") null else { { _: Offset ->
                if(onSpreadDoubleTap!=null)onSpreadDoubleTap() else { scale = if (scale > 1f) 1f else 2.5f; offset = Offset.Zero } } })
    }.then(if(zoomEnabled)Modifier.transformable(transform, canPan = { scale > 1f }) else Modifier), contentAlignment = Alignment.Center) {
        if (isText) Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(options.textMargins.dp)) {
            if(styled==null)CircularProgressIndicator() else ReaderStyledText(styled,options,if(options.background=="white")Color.Black else Color.White)
        }
        else if (resolved.isBlank() && !error) CircularProgressIndicator()
        else AsyncImage(request, tr("PAGE_SINGULAR"), Modifier.fillMaxWidth().then(if (continuous) Modifier else Modifier.fillMaxHeight())
            .onSizeChanged { layerWidth=it.width;layerHeight=it.height }
            .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x.coerceIn(-size.width * (scale-1)/2, size.width * (scale-1)/2); translationY = offset.y.coerceIn(-size.height * (scale-1)/2, size.height * (scale-1)/2) },
            contentScale = if (continuous) ContentScale.FillWidth else when (options.fit) { "width" -> ContentScale.FillWidth; "height" -> ContentScale.FillHeight; "fill" -> ContentScale.Crop; else -> ContentScale.Fit },
            colorFilter = ColorFilter.colorMatrix(matrix), onError = {
                error = true
                app.monogatari.android.sources.SourceLogStore.get(context).add("Reader image load failed: $resolved; hardware=${request.allowHardware}; crop=${options.cropBorders}; downsample=${options.downsample}; upscale=${options.upscale}; half=${page.half}\n${it.result.throwable.stackTraceToString()}","ERROR")
            }, onSuccess = {
                imageWidth=it.result.drawable.intrinsicWidth;imageHeight=it.result.drawable.intrinsicHeight
                error = false; if (page.half == null && it.result.drawable.intrinsicHeight > 0) dimension(it.result.drawable.intrinsicWidth.toFloat() / it.result.drawable.intrinsicHeight)
                if(options.background=="auto") autoBackground=PageBackground.choose(it.result.drawable.toBitmap(),configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE)
            })
        if (error) TextButton({ scope.launch {
            try { withContext(Dispatchers.IO) { ReaderPageCache.invalidate(context,page.url,source?.id,resolved) };error=false;retry++ }
            catch(failure:CancellationException){throw failure}catch(failure:Exception){vm.message=failure.message}
        } }) { Text(tr("RETRY")) }
    }
}

private suspend fun readerMaterialize(context: android.content.Context, vm: MonogatariViewModel, source: SourceConfig, url: String): String = withContext(Dispatchers.IO) {
    ReaderPageCache.materialize(context,source.id,url) { vm.repo.client.imageBytes(source,url) }
}

@Composable private fun RichEpub(html: String, options: ReaderOptions, root: File, jump: Double, progress: (Double) -> Unit,
    autoScroll:Boolean,autoEnd:()->Unit,interaction:()->Unit,link:(String)->Unit={},sourceMapping:Boolean=false,sourceIndices:(Set<Int>)->Unit={},pageCount:(Int)->Unit={},scrollExtent:(Float)->Unit={},tap: () -> Unit) {
    val context = LocalContext.current
    val dark=options.textAppearance=="dark" || options.textAppearance=="system" && isSystemInDarkTheme()
    val bg=when(options.textTheme){"sepia"->if(dark)"#3B3226" else "#FAF1E3";"paper"->if(dark)"#2E2E2B" else "#F2F1EC";"gray"->if(dark)"#414141" else "#D1D1D6";else->if(dark)"#171717" else "#FFFFFF"}
    val fg=if(dark)"#EEEEEE" else "#242424"
    var lastJump by remember(html,options.fontSize,options.textTheme) { mutableDoubleStateOf(-1.0) }
    var activeView by remember(html) { mutableStateOf<WebView?>(null) }
    val currentEnd=rememberUpdatedState(autoEnd)
    val currentLink=rememberUpdatedState(link)
    val currentSources=rememberUpdatedState(sourceIndices)
    val currentPageCount=rememberUpdatedState(pageCount)
    val currentExtent=rememberUpdatedState(scrollExtent)
    LaunchedEffect(autoScroll,activeView,options.autoScrollSpeed) {
        if(autoScroll) while(isActive) { delay(16);val view=activeView ?: continue
            val max=(view.contentHeight*view.scale-view.height).toInt().coerceAtLeast(0)
            if(view.scrollY>=max) { currentEnd.value();break }
            view.scrollBy(0,(options.autoScrollSpeed.coerceIn(1,10)*100*context.resources.displayMetrics.density/60).toInt().coerceAtLeast(1))
        }
    }
    val document by produceState("",html) { value=withContext(Dispatchers.IO) {
        if(html.startsWith("file:")) File(Uri.parse(html).path!!).takeIf { it.canonicalPath.startsWith(root.canonicalPath+File.separator) }?.readText().orEmpty() else html
    } }
    val allowedRoot=if(html.startsWith("file:")) File(Uri.parse(html).path!!).parentFile ?: root else root
    val fontFile=ReaderFonts.file(options.textFont)
    val base=if(html.startsWith("file:"))html else Uri.fromFile(allowedRoot).toString()+"/"
    key(html, options.fontSize, options.textTheme, options.textFont,options.textAppearance,options.lineSpacing,options.textMargins,document) {
        AndroidView(factory = { WebView(context).apply {
            activeView=this
            // Only our escaped CommonMark document receives this read-only DOM bridge. Imported EPUB JavaScript stays disabled.
            settings.javaScriptEnabled = sourceMapping; settings.allowFileAccess = true; settings.allowContentAccess = false
            settings.blockNetworkLoads = true; settings.defaultFontSize = options.fontSize; settings.defaultTextEncodingName = "UTF-8"
            fun reportSources() { if(sourceMapping)evaluateJavascript("(function(){var p=Array.from(document.querySelectorAll('[data-monogatari-source]')).filter(function(e){var r=e.getBoundingClientRect();return r.bottom>0&&r.top<innerHeight;}).map(function(e){return Number(e.getAttribute('data-monogatari-source'));});monogatariTextSources.visible(JSON.stringify(p));})()",null) }
            if(sourceMapping)addJavascriptInterface(object {
                @android.webkit.JavascriptInterface fun visible(json:String) {
                    val indices=runCatching { val values=org.json.JSONArray(json);require(values.length()<=2000);(0 until values.length()).map { values.getInt(it) }.filter { it>=0 }.toSet() }.getOrDefault(emptySet())
                    android.os.Handler(android.os.Looper.getMainLooper()).post { currentSources.value(indices) }
                }
            },"monogatariTextSources")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView,url: String) { view.postDelayed({
                    if(document.isBlank())return@postDelayed
                    val max=(view.contentHeight*view.scale-view.height).toInt().coerceAtLeast(0)
                    currentExtent.value(max.toFloat())
                    currentPageCount.value(ReaderTextSources.scrollPageCount(view.contentHeight*view.scale,view.height.toFloat()))
                    view.scrollTo(0,(max*jump).toInt());lastJump=jump;progress(if(max==0)1.0 else jump)
                    reportSources()
                },100) }
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?):Boolean {
                    val uri=request?.url ?: return true
                    if(uri.fragment!=null && uri.buildUpon().fragment(null).build().toString()==Uri.parse(base).buildUpon().fragment(null).build().toString())return false
                    currentLink.value(uri.toString());return true
                }
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val uri = request?.url ?: return null
                    val file = if (uri.scheme == "file") uri.path?.let(::File) else null
                    if(uri.scheme=="data")return null
                    if(uri.toString()=="https://monogatari-reader.invalid/font" && fontFile!=null)
                        return WebResourceResponse("font/${if(fontFile.extension=="otf")"otf" else "ttf"}",null,fontFile.inputStream())
                    val cacheRoot=File(context.cacheDir,"reader-pages").canonicalPath+File.separator
                    if (file == null || !file.canonicalPath.startsWith(allowedRoot.canonicalPath + File.separator) && !file.canonicalPath.startsWith(cacheRoot)) return WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(byteArrayOf()))
                    return null
                }
            }
            setOnScrollChangeListener { _, _, y, _, _ -> val max = (contentHeight * scale - height).toInt();currentPageCount.value(ReaderTextSources.scrollPageCount(contentHeight*scale,height.toFloat()))
                currentExtent.value(max.coerceAtLeast(0).toFloat())
                if (max > 0) progress((y.toDouble()/max).coerceIn(0.0,1.0));reportSources() }
            var downX=0f;var downY=0f;var downAt=0L
            setOnTouchListener { _,event -> when(event.actionMasked){android.view.MotionEvent.ACTION_DOWN->{downX=event.x;downY=event.y;downAt=event.eventTime}
                android.view.MotionEvent.ACTION_MOVE->{if(kotlin.math.abs(event.x-downX)+kotlin.math.abs(event.y-downY)>=16)interaction()}
                android.view.MotionEvent.ACTION_UP->{if(event.eventTime-downAt<250 && kotlin.math.abs(event.x-downX)+kotlin.math.abs(event.y-downY)<16)tap()}}
                false }
            val family=if(fontFile!=null)"MonogatariReaderFont" else when(options.textFont){"serif"->"serif";"mono"->"monospace";"cursive"->"cursive";else->"sans-serif"}
            val fontStyle=if(fontFile==null)"" else "@font-face{font-family:MonogatariReaderFont;src:url('https://monogatari-reader.invalid/font');}"
            val style="<style>$fontStyle body{background:$bg!important;color:$fg!important;font-size:${options.fontSize}px!important;line-height:${options.lineSpacing}!important;margin:${options.textMargins}px!important;font-family:$family!important;}img,svg{max-width:100%;height:auto;}p{margin-bottom:${options.paragraphSpacing}px;}table{border-collapse:collapse;width:100%;}th,td{border:1px solid #888;padding:6px;}pre{white-space:pre-wrap;word-break:break-word;}a{color:#E25478;}</style>"
            val directional=org.jsoup.Jsoup.parse(document).apply {
                if(!body().hasAttr("dir") && !selectFirst("html")!!.hasAttr("dir"))body().attr("dir","auto")
                if(sourceMapping)select("p,h1,h2,h3,h4,h5,h6,li,blockquote,td,th").filterNot{it.hasAttr("dir")}.forEach{it.attr("dir","auto")}
            }.outerHtml()
            loadDataWithBaseURL(base,style+directional,"text/html","UTF-8",null)
        } }, modifier = Modifier.fillMaxSize(),onReset=null,onRelease={it.removeJavascriptInterface("monogatariTextSources");it.destroy();if(activeView===it)activeView=null},update={ view ->if(lastJump>=0 && jump!=lastJump){
            val max=(view.contentHeight*view.scale-view.height).toInt().coerceAtLeast(0);view.scrollTo(0,(max*jump).toInt());lastJump=jump
        }})
    }
}
