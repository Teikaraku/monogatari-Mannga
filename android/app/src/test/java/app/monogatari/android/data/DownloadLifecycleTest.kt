package app.monogatari.android.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.monogatari.android.downloads.ArchivePageStore
import app.monogatari.android.downloads.DownloadService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class DownloadLifecycleTest {
    @Test fun pauseResumePreservesCompletePagesAndOnlyPromotesCompleteCbz() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();val repo=AppRepository(context)
        val source=SourceConfig("test-download","Test","komga","https://test.invalid/")
        val manga=Manga(UUID.randomUUID().toString(),source.id,"Test",chapters=listOf(Chapter("1","Chapter",pages=listOf("https://test.invalid/1","https://test.invalid/2","https://test.invalid/3"))))
        repo.change { AppState(manga=listOf(manga),sources=listOf(source),settings=JSONObject().put("Downloads.compress",true).put("Downloads.parallel",false).put("Downloads.background",false).toString()) }
        val secondStarted=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val counts=mutableMapOf<String,Int>()
        val service=DownloadService(context,repo,scheduleWork=false,pageLoader={_,chapter->chapter},imageLoader={ _,url ->
            counts[url]=(counts[url] ?: 0)+1
            if (url.endsWith("/2") && !release.isCompleted) { secondStarted.complete(Unit);release.await() }
            byteArrayOf(0x89.toByte(),0x50,0x4e,0x47,url.last().code.toByte())
        })
        service.setAppVisible(true);service.enqueueNow(manga.id,"1")
        val id=service.entries.value.single().id
        val firstRun=launch(Dispatchers.Default) { service.runQueue() }
        withTimeout(5000) { secondStarted.await() }
        assertTrue(File(context.filesDir,"downloads/.$id.partial/00000.image").isFile)
        service.pause(id);firstRun.join()
        assertEquals("paused",service.entries.value.single().status)
        assertFalse(repo.state.value.manga.single().chapters.single().downloaded)
        assertFalse(File(context.filesDir,"downloads/$id.cbz").exists())
        release.complete(Unit);service.resume(id);service.runQueue()
        val chapter=repo.state.value.manga.single().chapters.single()
        assertTrue(chapter.downloaded);assertEquals(3,chapter.pages.size)
        assertEquals(1,counts["https://test.invalid/1"])
        assertTrue(chapter.pages.all(ArchivePageStore::isArchive))
        assertEquals('1'.code.toByte(),ArchivePageStore.bytes(context,chapter.pages[0]).last())
        assertEquals('3'.code.toByte(),ArchivePageStore.bytes(context,chapter.pages[2]).last())
        assertFalse(File(context.filesDir,"downloads/.$id.partial").exists())
        service.deleteAll()
        assertFalse(repo.state.value.manga.single().chapters.single().downloaded)
        service.enqueueNow(manga.id,"1")
        assertEquals("queued",service.entries.value.single().status)
        service.cancelAll()
    }
    @Test fun failedPageNeverProducesCompletedChapter() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();val repo=AppRepository(context)
        val source=SourceConfig("test-failed","Test","komga","https://test.invalid/")
        val manga=Manga(UUID.randomUUID().toString(),source.id,"Test",chapters=listOf(Chapter("1","Chapter",pages=listOf("https://test.invalid/1","https://test.invalid/2"))))
        repo.change { AppState(manga=listOf(manga),sources=listOf(source),settings=JSONObject().put("Downloads.compress",true).put("Downloads.parallel",true).put("Downloads.background",false).toString()) }
        var failedPageRequested=false
        val service=DownloadService(context,repo,scheduleWork=false,pageLoader={_,chapter->chapter},imageLoader={ _,url -> if (url.endsWith("/2")) { failedPageRequested=true;error("fixture failure") } else byteArrayOf(1,2,3) })
        service.setAppVisible(true);service.enqueueNow(manga.id,"1");service.runQueue()
        assertEquals("failed",service.entries.value.single().status)
        assertTrue(failedPageRequested);assertEquals("fixture failure",service.entries.value.single().error)
        assertFalse(repo.state.value.manga.single().chapters.single().downloaded)
        service.cancelAll()
    }
    private fun cbz(context:Context,id:String,value:String) = File(context.filesDir,"downloads/$id.cbz").also { file ->
        file.parentFile!!.mkdirs();ZipOutputStream(file.outputStream()).use { zip -> zip.putNextEntry(ZipEntry("0001.txt"));zip.write(value.toByteArray());zip.closeEntry() }
    }
    @Test fun keyMigrationKeepsUnchangedChaptersAndHoldsOnlyAffectedQueueUntilRepositoryCommits() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();val repo=AppRepository(context);val source="migration-${UUID.randomUUID()}"
        val old=Manga("old",source,"Old");val archiveId=AppRepository.chapterDownloadId(old.id,"unchanged")
        cbz(context,archiveId,"preserved")
        val manga=old.copy(chapters=listOf(Chapter("unchanged","Saved",pages=ArchivePageStore.urls(context,archiveId),downloaded=true),Chapter("queued","Queued")))
        val other=Manga("other",source,"Other",chapters=listOf(Chapter("q","Other queued")))
        repo.change { AppState(manga=listOf(manga,other)) };val service=DownloadService(context,repo,scheduleWork=false)
        service.enqueueNow(manga.id,"queued");service.enqueueNow(other.id,"q")
        service.migrateKeys(source,mapOf("old" to ("new" to mapOf("queued" to "new-chapter","unchanged" to "unchanged"))),resume=false)
        val newArchiveId=AppRepository.chapterDownloadId("$source::new","unchanged")
        assertFalse(File(context.filesDir,"downloads/$archiveId.cbz").exists());assertTrue(File(context.filesDir,"downloads/$newArchiveId.cbz").exists())
        val saved=repo.state.value.manga.first { it.key=="old" }.chapters.first()
        assertEquals("preserved",ArchivePageStore.bytes(context,saved.pages.single()).toString(Charsets.UTF_8))
        assertEquals("migrating",service.entries.value.first { it.mangaId=="$source::new" }.status)
        assertEquals("queued",service.entries.value.first { it.mangaId==other.id }.status)
        repo.updateManga(manga.id) { it.copy(key="new",chapters=it.chapters.map { chapter -> if(chapter.key=="queued")chapter.copy(key="new-chapter") else chapter }) }
        service.resumeMigrated();assertEquals("queued",service.entries.value.first { it.mangaId=="$source::new" }.status)
        service.cancelAll()
    }
    @Test fun migrationTargetCollisionLeavesEveryOriginalFileAndReferenceUntouched() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();val repo=AppRepository(context);val source="collision-${UUID.randomUUID()}"
        val oldId=AppRepository.chapterDownloadId("$source::old","chapter");val newId=AppRepository.chapterDownloadId("$source::new","chapter")
        val original=cbz(context,oldId,"original");val target=cbz(context,newId,"target")
        val manga=Manga("old",source,"Old",chapters=listOf(Chapter("chapter","Saved",pages=ArchivePageStore.urls(context,oldId),downloaded=true)))
        repo.change { AppState(manga=listOf(manga)) };val service=DownloadService(context,repo,scheduleWork=false)
        try { service.migrateKeys(source,mapOf("old" to ("new" to mapOf("chapter" to "chapter"))));fail("Expected destination conflict") }catch(_:IllegalStateException){}
        assertTrue(original.exists());assertTrue(target.exists())
        assertEquals("original",ArchivePageStore.bytes(context,repo.state.value.manga.single().chapters.single().pages.single()).toString(Charsets.UTF_8))
    }
    @Test fun chapterKeySwapsUseTemporaryFilesAndNeverCascadeUrlReplacement() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();val repo=AppRepository(context);val source="swap-${UUID.randomUUID()}"
        val a=AppRepository.chapterDownloadId("$source::manga","a");val b=AppRepository.chapterDownloadId("$source::manga","b")
        cbz(context,a,"A");cbz(context,b,"B")
        val manga=Manga("manga",source,"Manga",chapters=listOf(Chapter("a","A",pages=ArchivePageStore.urls(context,a),downloaded=true),Chapter("b","B",pages=ArchivePageStore.urls(context,b),downloaded=true)))
        repo.change { AppState(manga=listOf(manga)) };val service=DownloadService(context,repo,scheduleWork=false)
        service.migrateKeys(source,mapOf("manga" to ("manga" to mapOf("a" to "b","b" to "a"))),resume=false)
        val chapters=repo.state.value.manga.single().chapters
        assertEquals(b,android.net.Uri.parse(chapters[0].pages.single()).path!!.trim('/'))
        assertEquals(a,android.net.Uri.parse(chapters[1].pages.single()).path!!.trim('/'))
        assertEquals("A",ArchivePageStore.bytes(context,chapters[0].pages.single()).toString(Charsets.UTF_8))
        assertEquals("B",ArchivePageStore.bytes(context,chapters[1].pages.single()).toString(Charsets.UTF_8))
        service.resumeMigrated()
    }
    @Test fun coldLaunchConsumesOriginalPendingDeleteIdentifiersAndClearsMarker() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();val repo=AppRepository(context)
        val manga=Manga("m-${UUID.randomUUID()}","source","M");val id=AppRepository.chapterDownloadId(manga.id,"c")
        cbz(context,id,"saved")
        val marker=org.json.JSONArray().put(JSONObject().put("sourceKey",manga.sourceId).put("mangaKey",manga.key).put("chapterKey","c"))
        val legacy=android.util.Base64.encodeToString(marker.toString().toByteArray(),android.util.Base64.NO_WRAP)
        repo.change { AppState(manga=listOf(manga.copy(chapters=listOf(Chapter("c","C",pages=ArchivePageStore.urls(context,id),downloaded=true)))),settings=JSONObject().put("Data.chaptersToBeDeleted",legacy).toString()) }
        val service=DownloadService(context,repo,scheduleWork=false);service.cleanupDeferredDeletions()
        assertFalse(File(context.filesDir,"downloads/$id.cbz").exists());assertFalse(repo.state.value.manga.single().chapters.single().downloaded)
        assertFalse(JSONObject(repo.state.value.settings).has("Data.chaptersToBeDeleted"))
    }
    @Test fun interruptedStagingPromotionAndRollbackRestoreBothSidesOfAKeySwap() {
        val context=ApplicationProvider.getApplicationContext<Context>();val folder=File(context.filesDir,"downloads").apply{mkdirs()}
        listOf("staging","promoting","restoring").forEach { phase ->
            val a=File(folder,AppRepository.hash("$phase-a")+".cbz").apply{writeText("A")};val b=File(folder,AppRepository.hash("$phase-b")+".cbz").apply{writeText("B")}
            val ta=File(folder,".migration-${UUID.randomUUID()}");val tb=File(folder,".migration-${UUID.randomUUID()}")
            check(a.renameTo(ta));if(phase!="staging")check(b.renameTo(tb))
            if(phase=="promoting")check(ta.renameTo(b))
            if(phase=="restoring")check(ta.renameTo(a))
            File(context.filesDir,"download-key-migration.json").writeText(JSONObject().put("phase",phase).put("moves",org.json.JSONArray()
                .put(JSONObject().put("from",a.name).put("to",b.name).put("temp",ta.name))
                .put(JSONObject().put("from",b.name).put("to",a.name).put("temp",tb.name))).toString())
            DownloadService.recoverPendingKeyFiles(context,committed=false)
            assertEquals("A",a.readText());assertEquals("B",b.readText());assertFalse(ta.exists());assertFalse(tb.exists())
        }
    }
    @Test fun committedJournalKeepsSwappedFilesAndRejectsPathsOutsideDownloads() {
        val context=ApplicationProvider.getApplicationContext<Context>();val folder=File(context.filesDir,"downloads").apply{mkdirs()}
        val old=File(folder,AppRepository.hash("committed-old")+".cbz");val target=File(folder,AppRepository.hash("committed-new")+".cbz").apply{writeText("A")}
        val temp=File(folder,".migration-${UUID.randomUUID()}")
        val journal=File(context.filesDir,"download-key-migration.json")
        journal.writeText(JSONObject().put("phase","promoting").put("moves",org.json.JSONArray().put(JSONObject().put("from",old.name).put("to",target.name).put("temp",temp.name))).toString())
        DownloadService.recoverPendingKeyFiles(context,true);assertEquals("A",target.readText());assertFalse(journal.exists())
        journal.writeText(JSONObject().put("phase","staging").put("moves",org.json.JSONArray().put(JSONObject().put("from","../sentinel").put("to",target.name).put("temp",temp.name))).toString())
        try{DownloadService.recoverPendingKeyFiles(context,false);fail("Expected containment rejection")}catch(_:IllegalArgumentException){}
        assertTrue(journal.exists());journal.delete()
    }
    @Test fun opaqueMangaAndChapterKeysCannotReuseAnotherChaptersDownload() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();val repo=AppRepository(context)
        val source=SourceConfig("collision-test-${UUID.randomUUID()}","Source","komga","https://test.invalid")
        val first=Manga("m",source.id,"First",chapters=listOf(Chapter("c::d","C",pages=listOf("https://test.invalid/first"))))
        val second=Manga("m::c",source.id,"Second",chapters=listOf(Chapter("d","D",pages=listOf("https://test.invalid/second"))))
        assertEquals(AppRepository.hash("${first.id}::c::d"),AppRepository.hash("${second.id}::d"))
        assertNotEquals(AppRepository.chapterDownloadId(first.id,"c::d"),AppRepository.chapterDownloadId(second.id,"d"))
        repo.change{AppState(manga=listOf(first,second),sources=listOf(source),settings=JSONObject().put("Downloads.parallel",false).put("Downloads.compress",true).put("Downloads.background",false).toString())}
        val service=DownloadService(context,repo,scheduleWork=false,pageLoader={_,chapter->chapter},imageLoader={_,url->url.toByteArray()});service.setAppVisible(true)
        service.enqueueNow(first.id,"c::d");service.enqueueNow(second.id,"d");service.runQueue()
        assertEquals("https://test.invalid/first",ArchivePageStore.bytes(context,repo.state.value.manga.first{it.id==first.id}.chapters.single().pages.single()).toString(Charsets.UTF_8))
        assertEquals("https://test.invalid/second",ArchivePageStore.bytes(context,repo.state.value.manga.first{it.id==second.id}.chapters.single().pages.single()).toString(Charsets.UTF_8))
        service.cancelAll()
    }
}
