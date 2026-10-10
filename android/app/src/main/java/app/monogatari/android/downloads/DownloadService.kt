package app.monogatari.android.downloads

import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.util.AtomicFile
import androidx.work.*
import app.monogatari.android.MainActivity
import app.monogatari.android.ui.Localization
import app.monogatari.android.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class DownloadEntry(val mangaId: String, val chapterKey: String, val sourceId: String,
    val title: String, val status: String = "queued", val progress: Int = 0, val total: Int = 0,
    val error: String = "", val addedAt: Long = System.currentTimeMillis(),val migrationStatus:String="",
    val migrationFromManga:String="",val migrationFromChapter:String="",val allowCellular:Boolean=false) {
    val id get() = AppRepository.chapterDownloadId(mangaId,chapterKey)
    fun json() = JSONObject().put("manga", mangaId).put("chapter", chapterKey).put("source", sourceId)
        .put("title", title).put("status", status).put("progress", progress).put("total", total).put("error", error).put("addedAt", addedAt)
        .put("migrationStatus",migrationStatus).put("migrationFromManga",migrationFromManga).put("migrationFromChapter",migrationFromChapter)
        .put("allowCellular",allowCellular)
        .put("idVersion",2)
    companion object { fun parse(j: JSONObject) = DownloadEntry(j.getString("manga"), j.getString("chapter"), j.getString("source"),
        j.optString("title"), j.optString("status", "queued"), j.optInt("progress"), j.optInt("total"), j.optString("error"), j.optLong("addedAt"),
        j.optString("migrationStatus"),j.optString("migrationFromManga"),j.optString("migrationFromChapter"),j.optBoolean("allowCellular")) }
}

/** The durable queue has exactly one owner per chapter; a cancelled worker can never promote staging files. */
class DownloadService internal constructor(private val context: Context,
    private val repository: AppRepository = AppRepository.get(context), private val scheduleWork: Boolean = true,
    private val imageLoader: (suspend (SourceConfig, String) -> ByteArray)? = null,
    private val pageLoader: (suspend (Manga, Chapter) -> Chapter)? = null) {
    private val file = AtomicFile(File(context.filesDir, "download-queue.json"))
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val storageReady=CompletableDeferred<Unit>()
    private val directory = File(context.filesDir, "downloads").apply { mkdirs() }
    private val savedQueue=runCatching { JSONArray(file.readFully().toString(Charsets.UTF_8)).objects() }.getOrDefault(emptyList())
    private val mutable = MutableStateFlow(runCatching {
        savedQueue.map(DownloadEntry::parse).map { entry ->
            if(entry.status=="migrating") {
                val state=repository.state.value
                val committed=state.manga.any { it.id==entry.mangaId && (it.chapters+app.monogatari.android.reader.ChapterListing.orphans(state,it)).any { c ->c.key==entry.chapterKey } }
                if(committed)entry.copy(status=if(entry.migrationStatus=="running")"queued" else entry.migrationStatus.ifBlank { "queued" },migrationStatus="",migrationFromManga="",migrationFromChapter="")
                else entry.copy(mangaId=entry.migrationFromManga.ifBlank { entry.mangaId },chapterKey=entry.migrationFromChapter.ifBlank { entry.chapterKey },status="paused",
                    error="Source key migration was interrupted; files have been preserved",migrationStatus="",migrationFromManga="",migrationFromChapter="")
            } else if(entry.status=="running")entry.copy(status="queued") else entry
        }
    }.getOrDefault(emptyList()))
    val entries = mutable.asStateFlow()
    var wifiOnly: Boolean
        get() = SettingsStore(repository).bool("Library.downloadOnlyOnWifi")
        set(value) { scope.launch { SettingsStore(repository).put("Library.downloadOnlyOnWifi", value);schedule() } }
    var compress: Boolean
        get() = SettingsStore(repository).bool("Downloads.compress")
        set(value) { scope.launch { SettingsStore(repository).put("Downloads.compress", value) } }
    val parallel get() = SettingsStore(repository).bool("Downloads.parallel")
    val background get() = SettingsStore(repository).bool("Downloads.background")
    @Volatile var appVisible = false; private set
    fun setAppVisible(visible: Boolean) {
        appVisible = visible
        if (visible && mutable.value.any { it.status == "queued" }) schedule()
        if (!visible && !background) jobs.values.toList().forEach { it.cancel() }
    }
    init { if(scheduleWork) {
        scope.launch { try { if(!app.monogatari.android.library.ResetRequest.pending(context)){migrateLegacyQueueStorage();cleanupDeferredDeletions()} }
            catch(error:Exception){app.monogatari.android.sources.SourceLogStore.get(context).add("Download cleanup: ${error.message}","ERROR")
                change { rows ->rows.map { if(it.status=="queued")it.copy(status="failed",error="Download storage recovery failed: ${error.message}") else it } }
            } finally { storageReady.complete(Unit) } }
        (context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).registerDefaultNetworkCallback(object:android.net.ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network:android.net.Network,capabilities:android.net.NetworkCapabilities) {
                if(capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) && jobs.isEmpty() && mutable.value.any{it.status=="queued"})schedule(ExistingWorkPolicy.REPLACE)
            }
        })
    } else storageReady.complete(Unit) }
    suspend fun migrateLegacyQueueStorage() {
        val state=repository.state.value
        val tuples=(state.manga.flatMap { manga ->(manga.chapters+app.monogatari.android.reader.ChapterListing.orphans(state,manga)).map { manga.id to it.key } }+
            savedQueue.map { it.getString("manga") to it.getString("chapter") }).distinct()
        val owners=tuples.groupBy { (manga,chapter)->AppRepository.hash("$manga::$chapter") }
        savedQueue.filter { it.optInt("idVersion",1)<2 }.forEach { raw ->
            val entry=DownloadEntry.parse(raw);val legacy=AppRepository.hash("${entry.mangaId}::${entry.chapterKey}")
            if(entry.status=="finished")return@forEach
            if(owners[legacy].orEmpty().size>1) {
                change { it.map { e->if(e.id==entry.id)e.copy(status="failed",error="Ambiguous legacy download identifier; partial files were preserved. Retry downloads this chapter with its unique identifier.") else e } }
            } else listOf(legacy to entry.id,"$legacy.cbz" to "${entry.id}.cbz", ".$legacy.partial" to ".${entry.id}.partial").forEach { (old,new) ->
                val from=File(directory,old);val to=File(directory,new)
                if(from.exists() && !to.exists())check(from.renameTo(to)){"Unable to migrate legacy download identifier"}
            }
        }
        change { it }
    }
    suspend fun markForDeletion(mangaId:String,chapterKey:String) {
        repository.updateChapter(mangaId,chapterKey) { chapter ->
            if(chapter.downloaded)chapter.copy(metadata=JSONObject(chapter.metadata).put("deleteAfterReading",true).toString()) else chapter
        }
    }
    suspend fun cleanupDeferredDeletions() {
        val state=repository.state.value
        val marked=state.manga.filter { it.sourceId!="local" }.flatMap { manga ->
            (manga.chapters+app.monogatari.android.reader.ChapterListing.orphans(state,manga)).filter { JSONObject(it.metadata).optBoolean("deleteAfterReading") }.map { manga.id to it.key }
        }.toMutableSet()
        val legacy=SettingsStore(repository).value("Data.chaptersToBeDeleted")
        val rows=when(legacy) {
            is JSONArray ->legacy
            is String ->runCatching { JSONArray(if(legacy.trimStart().startsWith('['))legacy else android.util.Base64.decode(legacy,android.util.Base64.DEFAULT).toString(Charsets.UTF_8)) }.getOrNull()
            else ->null
        }
        rows.objects().forEach { row ->
            val source=row.optString("sourceKey",row.optString("sourceId"));val manga=row.optString("mangaKey",row.optString("mangaId"));val chapter=row.optString("chapterKey",row.optString("chapterId"))
            if(source.isNotBlank() && source!="local" && manga.isNotBlank() && chapter.isNotBlank())marked+="$source::$manga" to chapter
        }
        marked.forEach { (manga,chapter) ->remove(manga,chapter) }
        if(rows!=null)SettingsStore(repository).put("Data.chaptersToBeDeleted",null)
    }
    private suspend fun change(block: (List<DownloadEntry>) -> List<DownloadEntry>) = mutex.withLock {
        val next = block(mutable.value)
        if(next.isNotEmpty() && app.monogatari.android.library.ResetRequest.pending(context))return@withLock
        val out = file.startWrite()
        try { out.write(JSONArray(next.map { it.json() }).toString().toByteArray()); file.finishWrite(out) }
        catch (e: Exception) { file.failWrite(out); throw e }
        mutable.value = next
    }
    fun enqueue(mangaId: String, chapterKey: String,allowCellular:Boolean=false) { scope.launch { enqueueNow(mangaId, chapterKey,allowCellular) } }
    suspend fun enqueueNow(mangaId: String, chapterKey: String,allowCellular:Boolean=false) {
        storageReady.await()
        val manga = repository.state.value.manga.firstOrNull { it.id == mangaId } ?: error("漫画已移除")
        val chapter = manga.chapters.firstOrNull { it.key == chapterKey } ?: error("章节已移除")
        if (chapter.downloaded || manga.sourceId == "local") return
        val entry = DownloadEntry(manga.id, chapter.key, manga.sourceId, "${manga.title} · ${chapter.title}",allowCellular=allowCellular)
        change { list -> if (list.any { it.id == entry.id && it.status in setOf("queued", "running", "paused") }) list
            else list.filterNot { it.id == entry.id } + entry }
        schedule()
    }
    private fun schedule(policy:ExistingWorkPolicy=ExistingWorkPolicy.APPEND_OR_REPLACE) {
        if (!scheduleWork || app.monogatari.android.library.ResetRequest.pending(context)) return
        val request = OneTimeWorkRequestBuilder<QueueDownloadWorker>().setConstraints(Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).addTag("downloads").build()
        WorkManager.getInstance(context).enqueueUniqueWork("monogatari-download-queue", policy, request)
    }
    suspend fun pause(id: String? = null) {
        change { it.map { e -> if ((id == null || e.id == id) && e.status in setOf("queued", "running")) e.copy(status = "paused") else e } }
        if (id == null) { jobs.values.toList().forEach { it.cancelAndJoin() }; if (scheduleWork) WorkManager.getInstance(context).cancelUniqueWork("monogatari-download-queue") }
        else jobs[id]?.cancelAndJoin()
    }
    suspend fun resume(id: String? = null,allowCellular:Boolean=false) {
        change { it.map { e -> if ((id == null || e.id == id) && e.status in setOf("paused", "failed","queued")) e.copy(status = "queued", error = "",allowCellular=e.allowCellular || allowCellular) else e } }
        schedule()
    }
    suspend fun retry(id: String) = resume(id)
    suspend fun restoreQueueSnapshot(snapshot:JSONArray,affectedMangaIds:Set<String>?=null,resumeWork:Boolean=true) {
        mutable.value.filter { affectedMangaIds==null || it.mangaId in affectedMangaIds }.forEach { pause(it.id) }
        val restored=snapshot.objects().map(DownloadEntry::parse).filter { affectedMangaIds==null || it.mangaId in affectedMangaIds }.map { entry ->
            val intended=entry.migrationStatus.ifBlank { if(entry.status=="running")"queued" else entry.status }
            entry.copy(status=if(resumeWork)intended else "migrating",migrationStatus=if(resumeWork)"" else intended,
                migrationFromManga=if(resumeWork)"" else entry.mangaId,migrationFromChapter=if(resumeWork)"" else entry.chapterKey)
        }
        change { live -> if(affectedMangaIds==null)restored else live.filterNot { it.mangaId in affectedMangaIds }+restored }
        if(resumeWork && mutable.value.any { it.status=="queued" })schedule()
    }
    /** Call before the repository's key transaction with resume=false; resumeMigrated follows its commit. */
    suspend fun migrateKeys(sourceId: String, migrations: Map<String, Pair<String, Map<String,String>>>, resume: Boolean = true) = withContext(Dispatchers.IO) {
        storageReady.await()
        check(!File(context.filesDir,"download-key-migration.json").exists()) { "Previous download key migration has not been finalized" }
        val affected=mutable.value.filter { it.sourceId==sourceId && it.mangaId.removePrefix("$sourceId::") in migrations }
        val originalStatus=affected.associate { it.id to it.migrationStatus.ifBlank { it.status } }
        val hashes=mutableMapOf<String,String>()
        migrations.forEach { (oldManga,migration) ->
            val queued=affected.filter { it.mangaId=="$sourceId::$oldManga" }.associate { it.chapterKey to it.chapterKey }
            (queued+migration.second).forEach { (oldChapter,newChapter) ->
                hashes[AppRepository.chapterDownloadId("$sourceId::$oldManga",oldChapter)]=AppRepository.chapterDownloadId("$sourceId::${migration.first}",newChapter)
            }
        }
        require(hashes.values.distinct().size==hashes.size) { "下载迁移目标冲突" }
        fun rewrite(url:String,map:Map<String,String>):String {
            if(ArchivePageStore.isArchive(url)) {
                val uri=Uri.parse(url);val old=uri.path.orEmpty().trim('/')
                return map[old]?.let { uri.buildUpon().path("/$it").build().toString() } ?: url
            }
            val uri=Uri.parse(url);if(uri.scheme!="file")return url
            val path=uri.path ?: return url;val prefix=directory.absolutePath+File.separator
            if(!path.startsWith(prefix))return url
            val relative=path.removePrefix(prefix);val old=relative.substringBefore(File.separator)
            return map[old]?.let { Uri.fromFile(File(directory,it+relative.removePrefix(old))).toString() } ?: url
        }
        affected.forEach { pause(it.id) }
        val moves=hashes.filter { it.key!=it.value }.flatMap { (old,new) ->
            listOf(old to new,"$old.cbz" to "$new.cbz", ".$old.partial" to ".$new.partial")
        }.map { (old,new) -> File(directory,old) to File(directory,new) }.filter { it.first.exists() }
        val sources=moves.map { it.first }.toSet()
        if(moves.any { it.second.exists() && it.second !in sources }) {
            change { list -> list.map { e -> originalStatus[e.id]?.let { status -> e.copy(status=if(status=="running")"queued" else status) } ?: e } }
            if(resume)resumeMigrated()
            error("下载目标已存在，未迁移任何文件")
        }
        val temporary=moves.map { (from,to) -> Triple(from,to,File(directory,".migration-${java.util.UUID.randomUUID()}")) }
        var rewritten=false
        val migrationJournal=JSONObject().put("phase","staging").put("sourceId",sourceId).put("hashes",JSONObject(hashes))
            .put("moves",JSONArray(temporary.map { (from,to,temp)->JSONObject().put("from",from.name).put("to",to.name).put("temp",temp.name) }))
        try {
            writeKeyJournal(context,migrationJournal)
            // Two passes also permit chapter-key swaps without overwriting either chapter.
            temporary.forEach { move -> check(move.first.renameTo(move.third)) { "无法暂存下载迁移" } }
            writeKeyJournal(context,migrationJournal.put("phase","promoting"))
            temporary.forEach { move -> check(move.third.renameTo(move.second)) { "无法迁移下载目录" } }
            repository.change { state -> state.copy(manga=state.manga.map { manga -> if(manga.sourceId!=sourceId) manga else manga.copy(chapters=manga.chapters.map { chapter ->
                chapter.copy(pages=chapter.pages.map { rewrite(it,hashes) })
            }) }) }
            rewritten=true
            change { list -> list.map { e ->
                val migration=migrations[e.mangaId.removePrefix("$sourceId::")]
                if(e.sourceId!=sourceId || migration==null) e else e.copy(mangaId="$sourceId::${migration.first}",chapterKey=migration.second[e.chapterKey] ?: e.chapterKey,
                    status="migrating",migrationStatus=originalStatus[e.id] ?: e.status,migrationFromManga=e.mangaId,migrationFromChapter=e.chapterKey)
            } }
        } catch(error:Throwable) {
            withContext(NonCancellable) {
                recoverPendingKeyFiles(context,committed=false)
                if(rewritten) { val inverse=hashes.entries.associate { it.value to it.key }
                    repository.change { state -> state.copy(manga=state.manga.map { manga -> if(manga.sourceId!=sourceId) manga else manga.copy(chapters=manga.chapters.map { chapter ->
                        chapter.copy(pages=chapter.pages.map { rewrite(it,inverse) })
                    }) }) }
                }
                change { list -> list.map { e -> originalStatus[e.id]?.let { status -> e.copy(status=if(status=="running")"queued" else status) } ?: e } }
                if(resume)resumeMigrated()
            }
            throw error
        }
        if(resume)resumeMigrated()
    }
    suspend fun resumeMigrated() {
        change { list -> list.map { e -> if(e.status!="migrating")e else e.copy(status=if(e.migrationStatus=="running")"queued" else e.migrationStatus.ifBlank { "queued" },
            migrationStatus="",migrationFromManga="",migrationFromChapter="") } }
        if(mutable.value.any { it.status=="queued" })schedule()
        finalizePendingKeyFiles(context)
    }
    suspend fun cancel(id: String) {
        change { it.map { e -> if (e.id == id) e.copy(status = "cancelled") else e } }
        jobs[id]?.cancelAndJoin()
        File(directory, ".$id.partial").deleteRecursively()
        change { it.filterNot { e -> e.id == id } }
    }
    suspend fun cancelAll() { mutable.value.toList().forEach { cancel(it.id) }; if (scheduleWork) WorkManager.getInstance(context).cancelUniqueWork("monogatari-download-queue") }
    suspend fun remove(mangaId: String, chapterKey: String) {
        val id = AppRepository.chapterDownloadId(mangaId,chapterKey)
        val manga=repository.state.value.manga.firstOrNull { it.id==mangaId }
        val references=manga?.let { (it.chapters+app.monogatari.android.reader.ChapterListing.orphans(repository.state.value,it)).firstOrNull { chapter ->chapter.key==chapterKey }?.pages }.orEmpty()
        cancel(id)
        repository.updateChapter(mangaId, chapterKey) { chapter ->val metadata=JSONObject(chapter.metadata);metadata.remove("deleteAfterReading");chapter.copy(pages = emptyList(), downloaded = false,metadata=metadata.toString()) }
        File(directory, id).deleteRecursively(); File(directory, "$id.cbz").delete()
        // Interrupted key migrations and restored backups can retain a valid archive under its previous hash.
        references.mapNotNull { url ->
            val uri=Uri.parse(url)
            if(ArchivePageStore.isArchive(url)) uri.path?.trim('/')?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }?.let { File(directory,"$it.cbz") }
            else if(uri.scheme=="file")uri.path?.let { path -> File(path).canonicalFile.takeIf { it.path.startsWith(directory.canonicalPath+File.separator) } }
                ?.let { file -> File(directory,file.path.removePrefix(directory.canonicalPath+File.separator).substringBefore(File.separator)) }
            else null
        }.distinct().forEach { target ->
            val state=repository.state.value;val otherReferences=state.manga.flatMap { it.chapters+app.monogatari.android.reader.ChapterListing.orphans(state,it) }.flatMap { it.pages }
            if(otherReferences.none { url -> if(ArchivePageStore.isArchive(url))Uri.parse(url).path?.trim('/')==target.name.removeSuffix(".cbz")
                else Uri.parse(url).path?.let { it==target.path || it.startsWith(target.path+File.separator) }==true })target.deleteRecursively()
        }
    }
    suspend fun deleteAll() {
        cancelAll()
        val repo = repository
        val state=repo.state.value
        state.manga.filter { it.sourceId!="local" }.forEach { manga ->app.monogatari.android.reader.ChapterListing.orphans(state,manga).forEach { orphan ->repo.updateChapter(manga.id,orphan.key){it.copy(pages=emptyList(),downloaded=false)} } }
        repo.change { s -> s.copy(manga = s.manga.map { m -> if (m.sourceId == "local") m else m.copy(chapters = m.chapters.map {
            if (it.downloaded) it.copy(pages = emptyList(), downloaded = false) else it
        }) }) }
        directory.listFiles()?.forEach { it.deleteRecursively() }
    }
    suspend fun export(mangaId: String, chapterKey: String, uri: Uri) = withContext(Dispatchers.IO) {
        val repo = repository; val manga = repo.state.value.manga.first { it.id == mangaId }
        val chapter = (manga.chapters+app.monogatari.android.reader.ChapterListing.orphans(repo.state.value,manga)).first { it.key == chapterKey }
        val archive = File(directory, "${AppRepository.chapterDownloadId(mangaId,chapterKey)}.cbz")
        context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
            if (archive.exists()) archive.inputStream().use { it.copyTo(output) } else ChapterExporter.write(context, manga, chapter, output)
        } ?: error("无法创建导出文件")
    }
    private class WifiRequired:Exception()
    private fun networkAllowed(entry:DownloadEntry)=!wifiOnly || entry.allowCellular || DownloadConnectivity.isWifi(context)
    fun waitingForWifi()=mutable.value.any { it.status=="queued" && !networkAllowed(it) }
    suspend fun runQueue() = coroutineScope {
        storageReady.await()
        if(app.monogatari.android.library.ResetRequest.pending(context))return@coroutineScope
        val limit = Semaphore(if (parallel) 3 else 1)
        mutable.value.filter { it.status == "queued" }.map { it.sourceId }.distinct().forEach { source -> launch(Dispatchers.IO) {
            limit.withPermit {
                while (isActive) {
                    if (app.monogatari.android.library.ResetRequest.pending(context) || !background && !appVisible) break
                    val next = mutable.value.firstOrNull { it.sourceId == source && it.status == "queued" && networkAllowed(it) } ?: break
                    val job = launch { runEntry(next) }; jobs[next.id] = job
                    try { job.join() } finally { jobs.remove(next.id, job) }
                }
            }
        } }
    }
    private suspend fun runEntry(entry: DownloadEntry) {
        val repo = repository
        suspend fun update(block: (DownloadEntry) -> DownloadEntry) = change { it.map { e -> if (e.id == entry.id) block(e) else e } }
        try {
            update { if (it.status == "queued") it.copy(status = "running", error = "") else it }
            if (mutable.value.firstOrNull { it.id == entry.id }?.status != "running") return
            val manga = repo.state.value.manga.firstOrNull { it.id == entry.mangaId } ?: error("漫画已移除")
            val original = manga.chapters.firstOrNull { it.key == entry.chapterKey } ?: error("章节已移除")
            if (original.downloaded) { update { it.copy(status = "finished") }; return }
            if (File(directory,"${entry.id}.cbz").exists()) {
                val saved=ArchivePageStore.urls(context,entry.id)
                if(saved.isNotEmpty()) { repo.updateChapter(manga.id,original.key) { it.copy(pages=saved,total=saved.size,downloaded=true) }
                    update { it.copy(status="finished",progress=saved.size,total=saved.size) };return }
            }
            val source = repo.state.value.sources.firstOrNull { it.id == manga.sourceId } ?: error("漫画源未安装")
            val chapter = pageLoader?.invoke(manga,original) ?: repo.pages(manga, original)
            val staging = File(directory, ".${entry.id}.partial").apply { mkdirs() }
            val manifest = File(staging, "pages.json")
            val urls = JSONArray(chapter.pages).toString()
            if (manifest.exists() && manifest.readText() != urls) { staging.deleteRecursively(); staging.mkdirs() }
            manifest.writeText(urls)
            update { it.copy(total = chapter.pages.size, progress = 0) }
            val parallelPages=parallel
            val pageLimit = Semaphore(if (parallelPages) JSONObject(source.configuration).optInt("maximumParallelRequests", 5).coerceIn(1, 5) else 1)
            val finishedPages = java.util.concurrent.atomic.AtomicInteger(0)
            suspend fun downloadPage(index:Int,url:String):File = pageLimit.withPermit {
                currentCoroutineContext().ensureActive()
                val extension = Uri.parse(url).path?.substringAfterLast('.')?.lowercase()?.takeIf { it in setOf("txt", "md") } ?: "image"
                val dest = File(staging, "${index.toString().padStart(5, '0')}.$extension")
                if (!dest.exists() || dest.length() == 0L) {
                    val partial = File(staging, "$index.writing")
                    try {
                        var failure: Exception? = null
                        for (attempt in 0..3) {
                            currentCoroutineContext().ensureActive()
                            try {
                                if(!networkAllowed(entry))throw WifiRequired()
                                val bytes = if (url.startsWith("file:") || url.startsWith("content:"))
                                    context.contentResolver.openInputStream(Uri.parse(url))?.use { input -> java.io.ByteArrayOutputStream().also { ArchiveImporter.copyBounded(input, it, 50L * 1024 * 1024) }.toByteArray() } ?: error("本地页面不可读")
                                else imageLoader?.invoke(source, url) ?: repo.client.imageBytes(source, url)
                                require(bytes.isNotEmpty() && bytes.size <= 50 * 1024 * 1024) { "图片为空或超过大小限制" }
                                partial.outputStream().use { it.write(bytes); it.fd.sync() }
                                currentCoroutineContext().ensureActive()
                                check(mutable.value.firstOrNull { it.id == entry.id }?.status == "running") { "下载已暂停" }
                                check(partial.renameTo(dest)) { "无法保存页面" }; failure = null; break
                            } catch (e: CancellationException) { throw e }
                            catch(e:WifiRequired){throw e}
                            catch (e: Exception) { failure = e; if (attempt < 3) delay(1000L shl attempt) }
                        }
                        failure?.let { throw it }
                    } finally { partial.delete() }
                }
                val done = finishedPages.incrementAndGet()
                update { it.copy(progress = maxOf(it.progress, done)) }; dest
            }
            val files = if(parallelPages)coroutineScope { chapter.pages.mapIndexed { index,url->async(Dispatchers.IO){downloadPage(index,url)} }.awaitAll() }
                else chapter.pages.mapIndexed { index,url->downloadPage(index,url) }
            val descriptions=JSONObject()
            if(source.type=="wasm") chapter.pages.forEachIndexed { index,url ->
                runCatching { repo.client.runtimes.runtime(source.id).getPageDescription(url) }.getOrNull()?.takeIf(String::isNotBlank)?.let { descriptions.put(index.toString(),it) }
            }
            val metadata=JSONObject(chapter.metadata).put("pageDescriptions",descriptions).toString()
            currentCoroutineContext().ensureActive()
            mutex.withLock {
                check(mutable.value.firstOrNull { it.id == entry.id }?.status == "running") { "下载已停止" }
                if (compress) {
                    val partialArchive = File(directory, ".${entry.id}.cbz.writing")
                    try {
                        partialArchive.outputStream().use { ChapterExporter.write(context, manga, chapter.copy(pages = files.map { Uri.fromFile(it).toString() }, downloaded = true, metadata=metadata), it) }
                        currentCoroutineContext().ensureActive()
                        check(partialArchive.renameTo(File(directory, "${entry.id}.cbz"))) { "无法保存章节归档" }
                        val paths = ArchivePageStore.urls(context, entry.id)
                        repo.updateChapter(manga.id, chapter.key) { it.copy(pages = paths, total = files.size, downloaded = true,metadata=metadata) }
                        staging.deleteRecursively()
                    } finally { partialArchive.delete() }
                } else {
                    val final = File(directory, entry.id)
                    if (final.exists()) final.deleteRecursively()
                    check(staging.renameTo(final)) { "无法完成下载目录" }
                    repo.updateChapter(manga.id, chapter.key) { it.copy(pages = files.map { f -> Uri.fromFile(File(final, f.name)).toString() }, total = files.size, downloaded = true,metadata=metadata) }
                }
            }
            update { it.copy(status = "finished", progress = files.size, total = files.size) }
        } catch (e: CancellationException) { update { if (it.status == "running") it.copy(status = "queued") else it }; throw e }
        catch(e:WifiRequired){update{if(it.status=="running")it.copy(status="queued") else it}}
        catch (e: Exception) { update { if (it.status == "running") it.copy(status = "failed", error = e.message ?: "下载失败") else it } }
    }
    companion object {
        private fun writeKeyJournal(context:Context,data:JSONObject) {
            val atomic=AtomicFile(File(context.filesDir,"download-key-migration.json"));val out=atomic.startWrite()
            try { out.write(data.toString().toByteArray());atomic.finishWrite(out) }catch(error:Exception){atomic.failWrite(out);throw error}
        }
        /** Called before repository load by the encompassing source-key data journal. */
        fun recoverPendingKeyFiles(context:Context,committed:Boolean) {
            val journal=AtomicFile(File(context.filesDir,"download-key-migration.json"));if(!journal.baseFile.exists())return
            val data=JSONObject(journal.readFully().toString(Charsets.UTF_8));val root=File(context.filesDir,"downloads").canonicalFile
            fun owned(name:String):File {
                require(name.matches(Regex("(?:[a-f0-9]{64}(?:\\.cbz)?|\\.[a-f0-9]{64}\\.partial|\\.migration-[a-f0-9-]{36})"))) { "Invalid download migration journal path" }
                return File(root,name).canonicalFile.also { require(it.parentFile==root) }
            }
            val moves=data.getJSONArray("moves").objects().map{Triple(owned(it.getString("from")),owned(it.getString("to")),owned(it.getString("temp")))}
            if(committed) {
                moves.forEach { (_,to,temp)->if(temp.exists()){check(!to.exists() && temp.renameTo(to)) { "Unable to complete committed download migration" }} }
            } else {
                if(data.optString("phase")=="promoting") {
                    moves.forEach { (_,to,temp)->if(!temp.exists() && to.exists()){check(to.renameTo(temp)) { "Unable to stage download migration rollback" }} }
                    writeKeyJournal(context,data.put("phase","restoring"))
                }
                moves.forEach { (from,_,temp)->if(temp.exists()){check(!from.exists() && temp.renameTo(from)) { "Unable to restore original download migration path" }} }
            }
            journal.delete()
        }
        fun finalizePendingKeyFiles(context:Context) = recoverPendingKeyFiles(context,committed=true)
        @Volatile private var singleton: DownloadService? = null
        fun get(context: Context): DownloadService {
            val app=context.applicationContext
            return singleton?.takeIf { it.context===app } ?: synchronized(this) { singleton?.takeIf { it.context===app } ?: DownloadService(app).also { singleton=it } }
        }
    }
}

class QueueDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun getForegroundInfo() = ForegroundInfo(2041, downloadNotification(applicationContext), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    override suspend fun doWork(): Result = coroutineScope {
        if (DownloadService.get(applicationContext).background) setForeground(getForegroundInfo())
        val updates = launch { DownloadService.get(applicationContext).entries.collect {
            (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(2041, downloadNotification(applicationContext))
        } }
        try { val service=DownloadService.get(applicationContext);service.runQueue();if(service.waitingForWifi())Result.retry() else Result.success() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.retry() }
        finally { updates.cancel() }
    }
}

private fun downloadNotification(context: Context): Notification {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.createNotificationChannel(NotificationChannel("downloads", Localization.string(context, "DOWNLOADS"), NotificationManager.IMPORTANCE_LOW))
    val entries = DownloadService.get(context).entries.value.filter { it.status in setOf("queued", "running") }
    val done = entries.sumOf { it.progress }; val total = entries.sumOf { it.total }
    val open = PendingIntent.getActivity(context, 2041, Intent(context, MainActivity::class.java).putExtra("screen", "downloads"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val pause = PendingIntent.getBroadcast(context, 2042, Intent(context, DownloadActionReceiver::class.java).setAction("pause"), PendingIntent.FLAG_IMMUTABLE)
    val cancel = PendingIntent.getBroadcast(context, 2043, Intent(context, DownloadActionReceiver::class.java).setAction("cancel"), PendingIntent.FLAG_IMMUTABLE)
    return Notification.Builder(context, "downloads").setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(Localization.string(context, "DOWNLOADING")).setContentText(entries.firstOrNull { it.status == "running" }?.title ?: "${entries.size}")
        .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setProgress(total, done, total == 0)
        .addAction(Notification.Action.Builder(null, Localization.string(context, "PAUSE"), pause).build())
        .addAction(Notification.Action.Builder(null, Localization.string(context, "CANCEL"), cancel).build()).build()
}

class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch { try {
            when (intent.action) { "pause" -> DownloadService.get(context).pause(); "cancel" -> DownloadService.get(context).cancelAll() }
        } finally { pending.finish() } }
    }
}
