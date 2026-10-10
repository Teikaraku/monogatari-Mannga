@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,androidx.compose.foundation.ExperimentalFoundationApi::class)
package app.monogatari.android.ui

import android.content.*
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.monogatari.android.MonogatariViewModel
import app.monogatari.android.data.*
import app.monogatari.android.downloads.DownloadService
import app.monogatari.android.local.LocalLibrary
import app.monogatari.android.reader.ChapterListing
import app.monogatari.android.tracking.TrackingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable fun DetailScreen(vm:MonogatariViewModel,state:AppState) {
    val manga=state.manga.firstOrNull { it.id==vm.key }
    if(manga==null) { EmptyPage(Icons.Outlined.LibraryBooks,tr("LOADING_ELLIPSIS"),"");return }
    val context=LocalContext.current;val settings=remember { SettingsStore(vm.repo) }
    val service=remember { DownloadService.get(context) };val queue by service.entries.collectAsState()
    val source=state.sources.firstOrNull { it.id==manga.sourceId };val local=manga.sourceId=="local"
    var expanded by remember(manga.id){mutableStateOf(false)};var menu by remember{mutableStateOf(false)}
    var filters by remember{mutableStateOf(false)};var categories by remember{mutableStateOf(false)};var edit by remember{mutableStateOf(false)}
    var cover by remember{mutableStateOf(false)};var importing by remember{mutableStateOf(false)}
    var selected by remember(manga.id){mutableStateOf(emptySet<String>())};var selecting by remember(manga.id){mutableStateOf(false)}
    var chapterMenu by remember(manga.id){mutableStateOf<String?>(null)};var editChapter by remember(manga.id){mutableStateOf<String?>(null)}
    var removal by remember(manga.id){mutableStateOf<Set<String>?>(null)};var removeLibrary by remember{mutableStateOf(false)}
    var exporting by remember{mutableStateOf<String?>(null)}
    var cellularDownload by remember { mutableStateOf<Set<String>?>(null) }
    val chapters=ChapterListing.listed(manga);val meta=JSONObject(manga.metadata);val flags=ChapterListing.flags(manga)
    val otherDownloads=ChapterListing.orphans(state,manga);val allListed=chapters+otherDownloads
    val chapterList=rememberLazyListState()
    var focusedChapter by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(vm.pendingChapterFocus,allListed) {
        val focus=vm.pendingChapterFocus ?: return@LaunchedEffect
        val index=allListed.indexOfFirst{it.key==focus}
        if(index>=0) { chapterList.animateScrollToItem(2+(if(manga.description.isNotBlank())1 else 0)+(if(manga.tags.isNotEmpty())1 else 0)+index);focusedChapter=focus;vm.pendingChapterFocus=null }
    }
    val displayKey="Manga.chapterDisplayMode.${manga.id.replaceFirst("::",".")}"
    val displayMode=if(settings.value(displayKey)!=null)settings.int(displayKey) else settings.int("Manga.chapterDisplayMode")
    fun updateMeta(block:(JSONObject)->Unit) { vm.run { vm.repo.updateManga(manga.id) { current -> current.copy(metadata=JSONObject(current.metadata).also(block).toString()) } } }
    fun mark(keys:Set<String>,read:Boolean) { vm.run {
        val snapshot=vm.repo.state.value;val current=snapshot.manga.firstOrNull{it.id==manga.id} ?: return@run
        val eligible=(current.chapters+ChapterListing.orphans(snapshot,current)).filter { it.key in keys && (!read || !it.locked || it.downloaded) }.map { it.key }.toSet()
        vm.setChaptersRead(manga.id,eligible,read)
    } }
    fun download(keys:Set<String>,anyway:Boolean=false) {
        if(!anyway && service.wifiOnly && !app.monogatari.android.downloads.DownloadConnectivity.isWifi(context)) { cellularDownload=keys;return }
        manga.chapters.filter { it.key in keys && !it.downloaded && !it.locked }.forEach { service.enqueue(manga.id,it.key,allowCellular=anyway) }
    }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.comicbook+zip")) { uri ->
        exporting?.let { key -> if(uri!=null)vm.run { if(local)LocalLibrary.export(vm.repo,manga.id,key,uri) else service.export(manga.id,key,uri) } }
    }
    val uploadCover=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri!=null)vm.run {
        val file=withContext(Dispatchers.IO) {
            val bytes=context.contentResolver.openInputStream(uri)?.use { input -> java.io.ByteArrayOutputStream().also { ArchiveImporter.copyBounded(input,it,20L*1024*1024) }.toByteArray() } ?: error("Image could not be opened")
            val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true };BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            require(bounds.outWidth>0 && bounds.outHeight>0) { Localization.string(context,"IMPORT_ERROR") }
            var sample=1;while(bounds.outWidth/sample>2048 || bounds.outHeight/sample>2048)sample*=2
            val image=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inSampleSize=sample }) ?: error("Image could not be decoded")
            val file=File(context.filesDir,"covers/${java.util.UUID.randomUUID()}.png");file.parentFile!!.mkdirs()
            try { file.outputStream().use { require(image.compress(Bitmap.CompressFormat.PNG,100,it)) };file } finally { image.recycle() }
        }
        var previousCover=""
        try { vm.repo.updateManga(manga.id) { current -> previousCover=current.cover;val data=JSONObject(current.metadata);val edited=data.optJSONObject("editedFields") ?: JSONObject()
            if(!data.has("originalCover"))data.put("originalCover",current.cover)
            val url=Uri.fromFile(file).toString();edited.put("cover",url);data.put("editedFields",edited);current.copy(cover=url,metadata=data.toString())
        };LocalLibrary.cleanupCover(vm.repo,previousCover) } catch(e:Exception){file.delete();throw e}
    } }
    fun share(url:String) { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,url),null)) }
    fun openWebsite(url:String) { vm.run { val uri=Uri.parse(url);require(uri.scheme in setOf("http","https") && !uri.host.isNullOrBlank()){Localization.string(context,"INVALID_URL")};context.startActivity(Intent(Intent.ACTION_VIEW,uri)) } }
    val next=ChapterListing.next(manga)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(vm::back){Icon(Icons.AutoMirrored.Outlined.ArrowBack,tr("CANCEL"),tint=Pink)}
            Spacer(Modifier.weight(1f))
            if(source!=null && !local)IconButton({vm.run{vm.repo.refresh(manga)}}){Icon(Icons.Outlined.Refresh,tr("REFRESH_DETAILS"))}
            IconButton({menu=true}){Icon(Icons.Outlined.MoreHoriz,tr("MORE"))}
            DropdownMenu(menu,{menu=false}) {
                DropdownMenuItem({Text(tr(if(manga.pinned)"UNPIN" else "PIN"))},{menu=false;vm.run{vm.repo.updateManga(manga.id){it.copy(pinned=!it.pinned)}}})
                DropdownMenuItem({Text(tr("EDIT"))},{menu=false;edit=true})
                DropdownMenuItem({Text(tr("SELECT_CHAPTERS"))},{menu=false;selecting=true})
                DropdownMenuItem({Text(tr("MARK_READ"))},{menu=false;mark(allListed.map{it.key}.toSet(),true)})
                DropdownMenuItem({Text(tr("MARK_UNREAD"))},{menu=false;mark(allListed.map{it.key}.toSet(),false)})
                if(local)DropdownMenuItem({Text(tr("IMPORT_FILE"))},{menu=false;importing=true})
                else {
                    DropdownMenuItem({Text(tr("DOWNLOAD"))},{menu=false;download(chapters.filter{!it.completed}.map{it.key}.toSet())})
                    if((manga.chapters+otherDownloads).any{it.downloaded})DropdownMenuItem({Text(tr("REMOVE_ALL_DOWNLOADS"))},{menu=false;removal=(manga.chapters+otherDownloads).filter{it.downloaded}.map{it.key}.toSet()})
                }
                DropdownMenuItem({Text(tr("COVER"))},{menu=false;cover=true})
                if(manga.url.isNotBlank())DropdownMenuItem({Text(tr("SHARE"))},{menu=false;share(manga.url)})
                if(local)DropdownMenuItem({Text(tr("DELETE"))},{menu=false;removal=manga.chapters.map{it.key}.toSet()})
            }
        }
        LazyColumn(Modifier.weight(1f),state=chapterList) {
            item { Row(Modifier.padding(20.dp),verticalAlignment=Alignment.Top) {
                Cover(manga,vm.repo,Modifier.width(112.dp).height(168.dp).clickable{cover=true})
                Column(Modifier.weight(1f).padding(start=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(manga.title,fontSize=23.sp,fontWeight=FontWeight.Bold)
                    if(manga.author.isNotBlank())Text(manga.author,fontSize=14.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(manga.artist.isNotBlank() && manga.artist!=manga.author)Text(manga.artist,fontSize=14.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if(local)tr("LOCAL_FILES") else source?.name ?: tr("MISSING_SOURCE"),color=Pink,fontSize=14.sp)
                    Text(tr(statusKey(manga.status)),color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=14.sp)
                    if(manga.contentRating>1)Text(tr(if(manga.contentRating==2)"SUGGESTIVE" else "NSFW"),color=Pink,fontSize=12.sp)
                    Row {
                        IconButton({if(manga.inLibrary)removeLibrary=true else vm.run{val category=settings.string("Library.defaultCategory");vm.repo.updateManga(manga.id){it.copy(inLibrary=true,addedAt=System.currentTimeMillis(),categories=if(category in state.categories)listOf(category) else it.categories)};if(category!="none" && category !in state.categories && state.categories.isNotEmpty())categories=true}}) {
                            Icon(if(manga.inLibrary)Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,tr(if(manga.inLibrary)"REMOVE_FROM_LIBRARY" else "ADD_TO_LIBRARY"),tint=Pink)
                        }
                        IconButton({categories=true}){Icon(Icons.Outlined.Label,tr("EDIT_CATEGORIES"),tint=Pink)}
                        IconButton({vm.navigate("mangaTracking",manga.id)}){Icon(Icons.Outlined.Sync,tr("TRACKING"),tint=Pink)}
                    }
                }
            } }
            if(manga.description.isNotBlank())item {
                val description=remember(manga.description){if(manga.description.contains(Regex("</?[A-Za-z]")))android.text.Html.fromHtml(manga.description,android.text.Html.FROM_HTML_MODE_LEGACY).toString() else manga.description}
                Text(description,Modifier.padding(horizontal=20.dp).clickable{expanded=!expanded},fontSize=15.sp,lineHeight=22.sp,maxLines=if(expanded)Int.MAX_VALUE else 3,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton({expanded=!expanded},Modifier.padding(start=8.dp)){Icon(if(expanded)Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,tr("MORE"))}
            }
            if(manga.tags.isNotEmpty())item { Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                manga.tags.forEach { tag -> AssistChip({vm.searchQuery=tag;vm.showTabRoot(3)},{Text(tag,fontSize=12.sp)}) }
            } }
            item {
                Button({next?.let{vm.read(manga,it)}},Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp),enabled=next!=null && !vm.busy && (source!=null || next.downloaded)) {
                    Icon(Icons.AutoMirrored.Outlined.MenuBook,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp))
                    Text(tr(when{next!=null -> if(next.readAt>0)"CONTINUE_READING" else "START_READING";chapters.isEmpty()->"NO_CHAPTERS";chapters.all{it.locked && !it.downloaded}->"ALL_CHAPTERS_LOCKED";else->"ALL_CHAPTERS_READ"}))
                }
                Row(Modifier.fillMaxWidth().padding(start=20.dp,end=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text(if(chapters.size==1)tr("1_CHAPTER") else tr("%i_CHAPTERS",chapters.size),fontSize=20.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
                    IconButton({filters=true}){Icon(Icons.Outlined.FilterList,tr("FILTER_BY"),tint=Pink)}
                    IconButton({updateMeta{it.put("chapterFlags",it.optInt("chapterFlags") xor 1)}}){Icon(Icons.Outlined.SwapVert,tr("SORT_BY"),tint=Pink)}
                }
                HorizontalDivider(thickness=.5.dp)
            }
            itemsIndexed(allListed,key={_,chapter->chapter.key}) { index,chapter ->
                if(index==chapters.size && otherDownloads.isNotEmpty())Text(tr("DOWNLOADED_CHAPTERS"),Modifier.padding(20.dp),style=MaterialTheme.typography.titleMedium)
                val job=queue.firstOrNull{it.mangaId==manga.id && it.chapterKey==chapter.key && it.status!="finished"}
                Row(Modifier.fillMaxWidth().then(if(chapter.key==focusedChapter)Modifier.background(MaterialTheme.colorScheme.primaryContainer) else Modifier).combinedClickable(onClick={if(selecting)selected=if(chapter.key in selected)selected-chapter.key else selected+chapter.key else if(!chapter.locked || chapter.downloaded)vm.read(manga,chapter) else chapterMenu=chapter.key},onLongClick={chapterMenu=chapter.key}).padding(start=20.dp,end=4.dp,top=10.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically) {
                    if(selecting)Checkbox(chapter.key in selected,{checked->selected=if(checked)selected+chapter.key else selected-chapter.key})
                    Column(Modifier.weight(1f)) {
                        Text(chapterTitle(chapter,displayMode),fontSize=15.sp,color=if(chapter.completed)MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                        val details=buildList {
                            add(if(chapter.completed)tr("READ") else if(chapter.readAt>0 && chapter.total>0)"${chapter.page+1} / ${chapter.total}" else tr("UNREAD"))
                            if(chapter.scanlator.isNotBlank())add(chapter.scanlator)
                            if(chapter.language.isNotBlank())add(languageName(chapter.language))
                            if(chapter.dateUploaded>0)add(DateFormat.getDateInstance(DateFormat.SHORT).format(Date(chapter.dateUploaded)))
                        };Text(details.joinToString(" · "),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(job?.status=="running")LinearProgressIndicator(progress={if(job.total>0)job.progress.toFloat()/job.total else 0f},modifier=Modifier.fillMaxWidth().padding(top=4.dp))
                    }
                    if(chapter.locked && !chapter.downloaded)Icon(Icons.Outlined.Lock,tr("LOCKED"),Modifier.padding(8.dp).size(18.dp))
                    if(chapter.downloaded)Icon(Icons.Outlined.CheckCircleOutline,tr("DOWNLOADED"),Modifier.padding(8.dp).size(20.dp),tint=Pink)
                    else if(!local)IconButton({if(job==null)download(setOf(chapter.key)) else chapterMenu=chapter.key},enabled=!chapter.locked || job!=null){Icon(if(job?.status=="running")Icons.Outlined.Pause else if(job?.status=="failed")Icons.Outlined.ErrorOutline else Icons.Outlined.Download,tr("DOWNLOAD"),tint=Pink)}
                    IconButton({chapterMenu=chapter.key}){Icon(Icons.Outlined.MoreVert,tr("MORE"),Modifier.size(18.dp))}
                }
                HorizontalDivider(Modifier.padding(start=20.dp),thickness=.5.dp)
            }
            if(chapters.isEmpty())item{EmptyPage(Icons.AutoMirrored.Outlined.MenuBook,tr("NO_CHAPTERS_AVAILABLE"),"")}
        }
        if(selecting)Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            TextButton({selected=if(selected.containsAll(allListed.map{it.key}))emptySet() else allListed.map{it.key}.toSet()}){Text(tr(if(selected.containsAll(allListed.map{it.key}))"DESELECT_ALL" else "SELECT_ALL"))}
            if(!local)TextButton({download(selected)}){Text(tr("DOWNLOAD"))}
            TextButton({mark(selected,true)}){Text(tr("MARK_READ"))};TextButton({mark(selected,false)}){Text(tr("MARK_UNREAD"))}
            TextButton({removal=selected}){Text(tr(if(local)"DELETE" else "REMOVE_DOWNLOADS"))}
            TextButton({selecting=false;selected=emptySet()}){Text(tr("DONE"))}
        }
    }
    if(filters) {
        val languages=manga.chapters.map{it.language}.filter(String::isNotBlank).distinct().sorted()
        val translators=manga.chapters.flatMap(ChapterListing::scanlators).distinct().sorted();val selectedScanlators=meta.optJSONArray("scanlatorFilter").strings()
        OptionSheet(tr("FILTER_BY"),{filters=false},footer={
            SheetSecondaryButton(tr("RESET"),{updateMeta{it.put("chapterFlags",0);it.remove("langFilter");it.remove("scanlatorFilter")}})
            SheetPrimaryButton(tr("DONE"),LocalSheetClose.current)
        }) {
            SheetSection(tr("SORT_BY")) { SheetChips {
                listOf("SOURCE_ORDER","CHAPTER","UPLOAD_DATE").forEachIndexed { index,key -> val active=(flags and 14) shr 1==index
                    // Choosing the active order again flips its direction, as the arrow shows.
                    SheetChip(tr(key),active,{updateMeta { data -> val old=data.optInt("chapterFlags");data.put("chapterFlags",if((old and 14) shr 1==index)old xor 1 else (old and 15.inv()) or (index shl 1)) }},
                        checkmark=false,trailingIcon=if(!active)null else if(flags and 1!=0)Icons.Outlined.ArrowUpward else Icons.Outlined.ArrowDownward)
                }
            } }
            SheetSection(pt("Show","显示")) { SheetChips {
                listOf(4 to "DOWNLOADED",6 to "UNREAD",8 to "LOCKED").forEach { (shift,key) ->
                    val enabled=1 shl shift;val excluded=1 shl(shift+1);val phase=if(flags and enabled==0)0 else if(flags and excluded==0)1 else 2
                    SheetChip(tr(key),phase==1,{updateMeta { data ->val old=data.optInt("chapterFlags");data.put("chapterFlags",when{old and enabled==0->old or enabled;old and excluded==0->old or excluded;else->old and (enabled or excluded).inv()}) }},excluded=phase==2)
                }
            } }
            if(languages.isNotEmpty())SheetSection(tr("LANGUAGE")) { SheetChips { languages.forEach { language ->
                SheetChip(languageName(language),meta.optString("langFilter")==language,{updateMeta{if(it.optString("langFilter")==language)it.remove("langFilter") else it.put("langFilter",language)}})
            } } }
            if(translators.size>1)SheetSection(tr("SCANLATOR")) { SheetChips { translators.forEach { translator ->
                SheetChip(translator.ifBlank{tr("NO_SCANLATOR")},translator in selectedScanlators,{updateMeta{data->val old=data.optJSONArray("scanlatorFilter").strings();data.put("scanlatorFilter",JSONArray(if(translator !in old)(old+translator).distinct() else old-translator))}})
            } } }
            SheetSection(tr("TITLE_DISPLAY_MODE")) { SheetChips {
                listOf("DISPLAY_DEFAULT","DISPLAY_CHAPTER","DISPLAY_VOLUME").forEachIndexed { index,key -> SheetChip(tr(key),displayMode==index,{vm.run{settings.put(displayKey,if(index==0)null else index)}},checkmark=false) }
            } }
        }
    }
    if(categories) {
        val assignable=state.categories.filter{JSONObject(state.categoryMetadata).optJSONObject(it)?.optBoolean("group")!=true}
        OptionSheet(tr("EDIT_CATEGORIES"),{categories=false},footer={
            SheetSecondaryButton(pt("Manage","管理分类"),{categories=false;vm.navigate("categories")})
            SheetPrimaryButton(tr("DONE"),LocalSheetClose.current)
        }) {
            if(assignable.isEmpty())Text(tr("CATEGORY_ADD"),Modifier.fillMaxWidth().clickable{categories=false;vm.navigate("categories")}.padding(horizontal=20.dp,vertical=16.dp),color=Pink)
            else SheetGroup { assignable.forEachIndexed { index,category ->
                SheetOption(category,category in manga.categories,{vm.run{vm.repo.updateManga(manga.id){current->current.copy(categories=if(category !in current.categories)(current.categories+category).distinct() else current.categories-category,inLibrary=true)}}},multiple=true,divider=index<assignable.lastIndex)
            } }
        }
    }
    if(edit)MangaEditDialog(manga,{edit=false}) { title,author,artist,description,tags,status,viewer,language,url ->vm.run {
        vm.repo.updateManga(manga.id){current->val data=JSONObject(current.metadata);val edits=data.optJSONObject("editedFields") ?: JSONObject()
            edits.put("title",title).put("author",author).put("artist",artist).put("description",description).put("tags",JSONArray(tags)).put("status",status).put("viewer",viewer).put("language",language).put("url",url);data.put("editedFields",edits)
            current.copy(title=title,author=author,artist=artist,description=description,tags=tags,status=status,viewer=viewer,language=language,url=url,metadata=data.toString())
        };edit=false
    } }
    if(cover)OptionSheet(tr("COVER"),{cover=false}) {
        val close=LocalSheetClose.current
        Box(Modifier.fillMaxWidth().padding(top=4.dp,bottom=16.dp),contentAlignment=Alignment.Center){Cover(manga,vm.repo,Modifier.width(184.dp).aspectRatio(2f/3f))}
        SheetActions(buildList {
            add(SheetActionItem(tr("UPLOAD_CUSTOM_COVER"),Icons.Outlined.AddPhotoAlternate){close();uploadCover.launch(arrayOf("image/*"))})
            if(meta.has("originalCover"))add(SheetActionItem(tr("RESET_COVER"),Icons.Outlined.Restore){close();vm.run{var previous="";vm.repo.updateManga(manga.id){current->previous=current.cover;val data=JSONObject(current.metadata);val original=data.optString("originalCover");data.remove("originalCover");data.optJSONObject("editedFields")?.remove("cover");current.copy(cover=original,metadata=data.toString())};LocalLibrary.cleanupCover(vm.repo,previous)}})
            if(manga.url.isNotBlank())add(SheetActionItem(tr("OPEN_WEBSITE"),Icons.Outlined.Public){openWebsite(manga.url)})
        })
    }
    if(importing)LocalAppendDialog(vm,manga){importing=false}
    val selectedChapter=(manga.chapters+otherDownloads).firstOrNull{it.key==chapterMenu}
    if(selectedChapter!=null) {
        val job=queue.firstOrNull{it.mangaId==manga.id && it.chapterKey==selectedChapter.key && it.status!="finished"}
        val summary=listOfNotNull(if(selectedChapter.completed)tr("READ") else if(selectedChapter.readAt>0 && selectedChapter.total>0)"${selectedChapter.page+1} / ${selectedChapter.total}" else tr("UNREAD"),
            selectedChapter.scanlator.takeIf(String::isNotBlank),selectedChapter.dateUploaded.takeIf{it>0}?.let{DateFormat.getDateInstance(DateFormat.SHORT).format(Date(it))}).joinToString(" · ")
        OptionSheet(chapterTitle(selectedChapter,displayMode),{chapterMenu=null},subtitle=summary) {
            val close=LocalSheetClose.current
            SheetActions(buildList {
                if(!selectedChapter.locked || selectedChapter.downloaded)add(SheetActionItem(pt("Read","阅读"),Icons.AutoMirrored.Outlined.MenuBook){close();vm.read(manga,selectedChapter)})
                add(SheetActionItem(tr("SELECT"),Icons.Outlined.Checklist){selecting=true;selected=selected+selectedChapter.key;close()})
                add(SheetActionItem(tr(if(selectedChapter.completed)"MARK_UNREAD" else "MARK_READ"),if(selectedChapter.completed)Icons.Outlined.RadioButtonUnchecked else Icons.Outlined.CheckCircleOutline){mark(setOf(selectedChapter.key),!selectedChapter.completed);close()})
                add(SheetActionItem(tr("MARK_PREVIOUS"),Icons.Outlined.DoneAll){val before=ChapterListing.chronological(manga).takeWhile{it.key!=selectedChapter.key}.map{it.key}.toSet();mark(before,true);close()})
                if(local)add(SheetActionItem(tr("EDIT"),Icons.Outlined.Edit){editChapter=selectedChapter.key;close()})
            })
            val downloads=buildList {
                if(selectedChapter.downloaded)add(SheetActionItem(tr("EXPORT"),Icons.Outlined.SaveAlt){exporting=selectedChapter.key;close();export.launch("${manga.title.take(40)}-${selectedChapter.number}.cbz")})
                else if(!local && !selectedChapter.locked && job==null)add(SheetActionItem(tr("DOWNLOAD"),Icons.Outlined.Download){close();download(setOf(selectedChapter.key))})
                if(job!=null) {
                    if(job.status in setOf("queued","running"))add(SheetActionItem(tr("PAUSE"),Icons.Outlined.Pause){vm.run{service.pause(job.id)};close()})
                    else add(SheetActionItem(tr("RETRY"),Icons.Outlined.Refresh){vm.run{service.resume(job.id)};close()})
                    add(SheetActionItem(tr("CANCEL_DOWNLOAD"),Icons.Outlined.Close){vm.run{service.cancel(job.id)};close()})
                }
                if(local || selectedChapter.downloaded || job?.status=="failed")add(SheetActionItem(tr(if(local)"REMOVE" else "REMOVE_DOWNLOAD"),Icons.Outlined.DeleteOutline,destructive=true){removal=setOf(selectedChapter.key);close()})
            }
            if(downloads.isNotEmpty()){Spacer(Modifier.height(12.dp));SheetActions(downloads)}
            if(selectedChapter.url.isNotBlank()){Spacer(Modifier.height(12.dp));SheetActions(listOf(
                SheetActionItem(tr("OPEN_WEBSITE"),Icons.Outlined.Public){openWebsite(selectedChapter.url)},
                SheetActionItem(tr("COPY_URL"),Icons.Outlined.Link){(context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(selectedChapter.title,selectedChapter.url));close()}))}
            if(selectedChapter.description.isNotBlank())Text(selectedChapter.description,Modifier.padding(start=20.dp,end=20.dp,top=14.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    manga.chapters.firstOrNull{it.key==editChapter}?.let { chapter -> LocalChapterEditDialog(vm,manga,chapter){editChapter=null} }
    removal?.let { keys ->AlertDialog({removal=null},title={Text(tr(if(local)"DELETE" else "REMOVE_DOWNLOADS"))},text={Text(tr("REMOVE_DOWNLOADS_CONFIRM"))},confirmButton={TextButton({removal=null;vm.run {
        keys.forEach { key ->if(local)LocalLibrary.deleteChapter(vm.repo,manga.id,key) else service.remove(manga.id,key) }
        selected=selected-keys;if(vm.repo.state.value.manga.none{it.id==manga.id})vm.back()
    }}){Text(tr("REMOVE"))}},dismissButton={TextButton({removal=null}){Text(tr("CANCEL"))}}) }
    if(removeLibrary)AlertDialog({removeLibrary=false},title={Text(tr("REMOVE_FROM_LIBRARY_CONFIRM"))},text={Text(tr("REMOVE_FROM_LIBRARY_CONFIRM_TEXT"))},confirmButton={TextButton({removeLibrary=false;vm.run {
        vm.repo.updateManga(manga.id){it.copy(inLibrary=false,categories=emptyList(),pinned=false)}
        val tracking=TrackingRepository.get(context);tracking.data.value.bindings.filter{it.mangaId==manga.id}.forEach{tracking.unbind(it)}
    }}){Text(tr("REMOVE"))}},dismissButton={TextButton({removeLibrary=false}){Text(tr("CANCEL"))}})
    cellularDownload?.let { keys ->AlertDialog({cellularDownload=null},title={Text(tr("NO_WIFI_ALERT_TITLE"))},text={Text(tr("DOWNLOAD_ANYWAY_MESSAGE"))},confirmButton={TextButton({cellularDownload=null;download(keys,true)}){Text(tr("DOWNLOAD"))}},dismissButton={TextButton({cellularDownload=null}){Text(tr("CANCEL"))}}) }
}

private fun statusKey(status:Int)=when(status){1->"STATUS_ONGOING";2->"STATUS_COMPLETED";3->"STATUS_CANCELLED";4->"STATUS_HIATUS";5->"STATUS_ENDED";6->"STATUS_NOT_PUBLISHED";else->"UNKNOWN"}
@Composable private fun chapterTitle(chapter:Chapter,mode:Int):String = when(mode) {
    1->ChapterListing.number(chapter)?.let { tr("CHAPTER_X",it) } ?: chapter.volume?.let{tr("CHAPTER_X",it)} ?: chapter.title
    2->chapter.volume?.let{tr("VOLUME_X",it)} ?: ChapterListing.number(chapter)?.let { tr("VOLUME_X",it) } ?: chapter.title
    else->chapter.title.ifBlank{ChapterListing.number(chapter)?.let { tr("CHAPTER_X",it) } ?: tr("CHAPTER")}
}
@Composable private fun languageName(value:String):String = Locale.forLanguageTag(value).getDisplayName(LocalConfiguration.current.locales[0]).takeIf{it.isNotBlank()} ?: value

@Composable private fun MangaEditDialog(manga:Manga,dismiss:()->Unit,save:(String,String,String,String,List<String>,Int,String,String,String)->Unit) {
    var title by remember{mutableStateOf(manga.title)};var author by remember{mutableStateOf(manga.author)};var artist by remember{mutableStateOf(manga.artist)}
    var description by remember{mutableStateOf(manga.description)};var tags by remember{mutableStateOf(manga.tags.joinToString(", "))};var status by remember{mutableIntStateOf(manga.status)}
    var viewer by remember{mutableStateOf(manga.viewer)};var language by remember{mutableStateOf(manga.language)};var url by remember{mutableStateOf(manga.url)}
    AlertDialog(dismiss,title={Text(tr("EDIT"))},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(title,{title=it},label={Text(tr("SERIES_TITLE"))});OutlinedTextField(author,{author=it},label={Text(tr("AUTHOR"))})
        OutlinedTextField(artist,{artist=it},label={Text(tr("ARTIST"))});OutlinedTextField(description,{description=it},label={Text(tr("DESCRIPTION"))})
        OutlinedTextField(tags,{tags=it},label={Text(tr("TAG"))});OutlinedTextField(language,{language=it},label={Text(tr("LANGUAGE"))})
        OutlinedTextField(url,{url=it},label={Text(tr("URL"))})
        Text(tr("STATUS"),style=MaterialTheme.typography.titleSmall);Row(Modifier.horizontalScroll(rememberScrollState())){(0..6).forEach { value ->FilterChip(status==value,{status=value},{Text(tr(statusKey(value)))},Modifier.padding(end=8.dp))}}
        Text(tr("READING_MODE"),style=MaterialTheme.typography.titleSmall);Row(Modifier.horizontalScroll(rememberScrollState())){listOf("auto" to "AUTOMATIC","rtl" to "RTL","ltr" to "LTR","vertical" to "VERTICAL","webtoon" to "WEBTOON").forEach{(value,key)->FilterChip(viewer==value,{viewer=value},{Text(tr(key))},Modifier.padding(end=8.dp))}}
    }},confirmButton={TextButton({save(title.trim(),author,artist,description,tags.split(',').map(String::trim).filter(String::isNotBlank),status,viewer,language.trim(),url.trim())},enabled=title.isNotBlank()){Text(tr("SAVE"))}},dismissButton={TextButton(dismiss){Text(tr("CANCEL"))}})
}

@Composable private fun LocalAppendDialog(vm:MonogatariViewModel,manga:Manga,dismiss:()->Unit) {
    var title by remember{mutableStateOf("")};var number by remember{mutableStateOf("")};var volume by remember{mutableStateOf("")}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris ->if(uris.isNotEmpty())vm.run {
        uris.forEach { uri -> LocalLibrary.import(vm.repo,uri,ImportOptions(mangaId=manga.key,mangaTitle=manga.title,chapterTitle=title.takeIf(String::isNotBlank),chapterNumber=number.toDoubleOrNull(),volume=volume.toDoubleOrNull())) };dismiss()
    }}
    AlertDialog(dismiss,title={Text(tr("LOCAL_FILE_IMPORT"))},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(tr("ADD_TO_OTHER_SERIES_INFO"));Text(manga.title,fontWeight=FontWeight.Bold)
        OutlinedTextField(title,{title=it},label={Text(tr("CHAPTER_TITLE"))});OutlinedTextField(number,{number=it},label={Text(tr("CHAPTER"))})
        OutlinedTextField(volume,{volume=it},label={Text(tr("VOLUME"))})
    }},confirmButton={TextButton({picker.launch(arrayOf("application/zip","application/vnd.comicbook+zip","application/epub+zip","application/octet-stream"))},enabled=(number.isBlank() || number.toDoubleOrNull()?.isFinite()==true) && (volume.isBlank() || volume.toDoubleOrNull()?.isFinite()==true)){Text(tr("IMPORT_FILE"))}},dismissButton={TextButton(dismiss){Text(tr("CANCEL"))}})
}

@Composable private fun LocalChapterEditDialog(vm:MonogatariViewModel,manga:Manga,chapter:Chapter,dismiss:()->Unit) {
    var title by remember(chapter.key){mutableStateOf(chapter.title)};var number by remember(chapter.key){mutableStateOf(ChapterListing.number(chapter)?.toString().orEmpty())}
    var volume by remember(chapter.key){mutableStateOf(chapter.volume?.toString().orEmpty())};var language by remember(chapter.key){mutableStateOf(chapter.language)};var translator by remember(chapter.key){mutableStateOf(chapter.scanlator)}
    AlertDialog(dismiss,title={Text(tr("EDIT"))},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
        OutlinedTextField(title,{title=it},label={Text(tr("CHAPTER_TITLE"))});OutlinedTextField(number,{number=it},label={Text(tr("CHAPTER"))})
        OutlinedTextField(volume,{volume=it},label={Text(tr("VOLUME"))});OutlinedTextField(language,{language=it},label={Text(tr("LANGUAGE"))});OutlinedTextField(translator,{translator=it},label={Text(tr("SCANLATOR"))})
    }},confirmButton={TextButton({vm.run{vm.repo.updateChapter(manga.id,chapter.key){it.copy(title=title,number=number.toDoubleOrNull() ?: 0.0,volume=volume.toDoubleOrNull(),language=language,scanlator=translator,
        metadata=JSONObject(it.metadata).apply { listOf("chapter","chapterNum","Number").forEach(::remove);put("chapterNumber",number.toDoubleOrNull() ?: JSONObject.NULL) }.toString())};dismiss()}},enabled=title.isNotBlank() && (number.isBlank() || number.toDoubleOrNull()?.isFinite()==true) && (volume.isBlank() || volume.toDoubleOrNull()?.isFinite()==true)){Text(tr("SAVE"))}},dismissButton={TextButton(dismiss){Text(tr("CANCEL"))}})
}
