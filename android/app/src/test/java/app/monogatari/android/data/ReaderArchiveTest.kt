package app.monogatari.android.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.monogatari.android.reader.*
import coil.size.Size
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReaderArchiveTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun archive(name: String, entries: Map<String, ByteArray>): File {
        return File(context.cacheDir, "${UUID.randomUUID()}-$name").also { file -> ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (path, bytes) -> zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry() }
        } }
    }
    @Test fun maliciousSeriesMetadataRemainsMetadataAndCannotEscapeStorage() {
        val sentinel = File(context.filesDir, "downloads/sentinel").apply { parentFile!!.mkdirs(); writeText("keep") }
        val file = archive("sample.cbz", mapOf("ComicInfo.xml" to "<ComicInfo><Series>../Downloads</Series><Title>Chapter</Title><Number>3.5</Number><Volume>2</Volume><Writer>Writer</Writer><Manga>YesAndRightToLeft</Manga></ComicInfo>".toByteArray(), "001.png" to byteArrayOf(1,2,3)))
        val manga = ArchiveImporter.import(context, Uri.fromFile(file))
        assertEquals("../Downloads", manga.title); assertEquals("rtl", manga.viewer); assertEquals("Writer", manga.author)
        assertEquals(3.5, manga.chapters.single().number, 0.0); assertEquals(2.0, manga.chapters.single().volume)
        assertTrue(manga.chapters.single().pages.all { File(Uri.parse(it).path!!).canonicalPath.startsWith(File(context.filesDir, "local").canonicalPath + File.separator) })
        assertEquals("keep", sentinel.readText())
    }
    @Test fun folderChaptersAndNaturalPageOrderArePreserved() {
        val file = archive("chapters.cbz", mapOf("Ch 2/10.png" to byteArrayOf(10), "Ch 2/2.png" to byteArrayOf(2), "Ch 1/1.png" to byteArrayOf(1)))
        val manga = ArchiveImporter.import(context, Uri.fromFile(file))
        assertEquals(listOf(1.0, 2.0), manga.chapters.map { it.number })
        assertEquals(2, File(Uri.parse(manga.chapters[1].pages[0]).path!!).readBytes()[0].toInt())
        assertEquals(10, File(Uri.parse(manga.chapters[1].pages[1]).path!!).readBytes()[0].toInt())
    }
    @Test fun epubPreservesPackageMetadataSpineAndDecodedRelativeResources() {
        val file = archive("book.epub", mapOf(
            "META-INF/container.xml" to "<container><rootfiles><rootfile full-path='OEBPS/package.opf'/></rootfiles></container>".toByteArray(),
            "OEBPS/package.opf" to "<package xmlns:dc='http://purl.org/dc/elements/1.1/'><metadata><dc:title>Book</dc:title><dc:creator>Author</dc:creator><dc:language>ja</dc:language></metadata><manifest><item id='c' href='text/ch%201.xhtml'/><item id='p' href='images/p.png'/></manifest><spine><itemref idref='c'/></spine></package>".toByteArray(),
            "OEBPS/text/ch 1.xhtml" to "<html><body><h1>First</h1><p>Readable text.</p><img src='../images/p.png'/><script>alert(1)</script></body></html>".toByteArray(),
            "OEBPS/images/p.png" to byteArrayOf(1,2,3)))
        val manga = ArchiveImporter.import(context, Uri.fromFile(file)); val chapter = manga.chapters.single()
        assertEquals("Book", manga.title); assertEquals("Author", manga.author); assertEquals("ja", manga.language)
        assertTrue(chapter.text.contains("Readable text."))
        val html = File(Uri.parse(chapter.html).path!!).readText()
        assertTrue(html.contains("file://")); assertFalse(html.contains("<script"))
    }
    @Test fun epubExternalEntitiesCannotReadLocalFiles() {
        val file = archive("bad.epub", mapOf("META-INF/container.xml" to "<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><container>&e;</container>".toByteArray()))
        try { ArchiveImporter.import(context, Uri.fromFile(file)); fail("Expected unsafe XML rejection") } catch (_: Exception) { }
    }
    @Test fun textPaginatorPartitionsAllCharactersWithoutLoss() {
        val text = ("第一行文字和标点。 A long English paragraph with shaping.\n\n".repeat(200))
        val pages = TextPaginator.paginate(text, 280, 400, 18f, 1.5f, "serif")
        assertTrue(pages.size > 1); assertEquals(0, pages.first().start); assertEquals(text.length, pages.last().end)
        assertEquals(text, pages.joinToString("") { text.substring(it.start, it.end) })
    }
    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun imageProcessingCropsBordersAndSplitsRightHalf() = runBlocking {
        val image = Bitmap.createBitmap(200, 120, Bitmap.Config.ARGB_8888)
        image.eraseColor(Color.WHITE)
        // The large margin survives the original quality-zero JPEG scan. Thin borders can acquire content-colored ringing.
        for (x in 60 until 140) for (y in 40 until 80) image.setPixel(x, y, if (x < 100) Color.RED else Color.BLUE)
        val cropped=CropBordersProcessor.crop(image)
        assertTrue("Expected a cropped full spread, got ${cropped.width}x${cropped.height}",cropped.width<image.width && cropped.height<image.height)
        val cut=cropped.width/2
        for(half in 0..1) {
            val output=PageTransformation(true,half,0).transform(image,Size.ORIGINAL)
            assertEquals(if(half==0)cut else cropped.width-cut,output.width);assertEquals(cropped.height,output.height)
            for(x in 0 until output.width)for(y in 0 until output.height)
                assertEquals(cropped.getPixel(x+(if(half==0)0 else cut),y),output.getPixel(x,y))
            assertEquals(if(half==0)Color.RED else Color.BLUE,output.getPixel(output.width/2,output.height/2));output.recycle()
        }
        cropped.recycle();image.recycle()
    }
    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun downsampleUsesCroppedFullPageBeforeSplittingAndTakesPriorityOverAi() = runBlocking {
        val image=Bitmap.createBitmap(200,120,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        for(x in 60 until 140)for(y in 40 until 80)image.setPixel(x,y,if(x<100)Color.RED else Color.BLUE)
        val options=ReaderOptions(cropBorders=true,downsample=true,upscale=true)
        val cropped=CropBordersProcessor.crop(image)
        assertTrue(cropped.width<image.width/2);assertTrue(cropped.height<image.height/2)
        val height=(cropped.height.toDouble()*40/cropped.width).roundToInt()
        val full=ReaderPageProcessing.transformation(context,options,null,"missing-model.onnx",false,40)!!
        assertTrue(full is DownsamplePageTransformation)
        val output=full.transform(image,Size.ORIGINAL)
        assertEquals(40,output.width);assertEquals(height,output.height)
        for(half in 0..1) {
            val split=ReaderPageProcessing.transformation(context,options,half,"missing-model.onnx",false,40)!!.transform(image,Size.ORIGINAL)
            assertEquals(20,split.width);assertEquals(height,split.height)
            for(x in 0 until split.width)for(y in 0 until split.height)
                assertEquals(output.getPixel(x+half*20,y),split.getPixel(x,y))
            assertEquals(if(half==0)Color.RED else Color.BLUE,split.getPixel(10,height/2));split.recycle()
        }
        output.recycle();cropped.recycle();image.recycle()
    }
    @Test fun originalAutomaticBackgroundClassifiesWhiteBlackAndGradientBorders() {
        val image=Bitmap.createBitmap(200,300,Bitmap.Config.ARGB_8888)
        image.eraseColor(Color.WHITE);assertEquals(listOf(Color.WHITE),PageBackground.choose(image,false))
        image.eraseColor(Color.BLACK);assertEquals(listOf(Color.BLACK),PageBackground.choose(image,false))
        for(y in 160 until 300)for(x in 0 until 200)image.setPixel(x,y,Color.WHITE)
        assertTrue(PageBackground.choose(image,false).size>1)
        assertEquals(1,PageBackground.choose(image,true).size)
    }
    @Test fun landscapeSpreadsRestartPairingAndOffsetIsolatesFirstPairablePage() {
        assertEquals(listOf(listOf(0),listOf(1),listOf(2,3),listOf(4),listOf(5,6)),
            ReaderLayout.spreads(listOf(false,true,true,true,false,true,true),true,true))
        assertEquals(listOf(listOf(0,1),listOf(2),listOf(3,4)),ReaderLayout.spreads(listOf(true,true,false,true,true),true,false))
    }
    @Test fun cbzRoundTripKeepsTextAndPageDescriptionsWithoutDuplicatingChapter() {
        val input=archive("text.cbz",mapOf("0001.txt" to "Real text.".toByteArray(),"0001.desc.txt" to "Description".toByteArray()))
        val manga=ArchiveImporter.import(context,Uri.fromFile(input));val chapter=manga.chapters.single()
        assertEquals("Description",org.json.JSONObject(chapter.metadata).getJSONObject("pageDescriptions").getString("0"))
        val output=File(context.cacheDir,"${UUID.randomUUID()}.cbz")
        output.outputStream().use { app.monogatari.android.downloads.ChapterExporter.write(context,manga,chapter,it) }
        val restored=ArchiveImporter.import(context,Uri.fromFile(output)).chapters.single()
        assertEquals(1,restored.pages.size);assertEquals(chapter.text,restored.text)
        assertEquals("Description",org.json.JSONObject(restored.metadata).getJSONObject("pageDescriptions").getString("0"))
    }
    @Test fun ordinalDescriptionsTakePriorityAndStayInTheirChapterAcrossExport() {
        fun png(color:Int)=java.io.ByteArrayOutputStream().also { output ->
            val bitmap=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));bitmap.recycle()
        }.toByteArray()
        val first=png(Color.RED);val second=png(Color.BLUE)
        val input=archive("ordinal.cbz",mapOf(
            "Chapter 1/scan-a.png" to first,"Chapter 1/scan-b.png" to second,
            "Chapter 1/2.desc.txt" to "First chapter second ordinal".toByteArray(),
            "Chapter 1/scan-b.desc.txt" to "Lower-priority matching stem".toByteArray(),
            "Chapter 1/scan-a.desc.txt" to "First chapter stem fallback".toByteArray(),
            "Chapter 2/scan-a.png" to second,"Chapter 2/scan-b.png" to first,
            "Chapter 2/2.desc.txt" to "Second chapter second ordinal".toByteArray()))
        val manga=ArchiveImporter.import(context,Uri.fromFile(input))
        assertEquals(2,manga.chapters.size)
        manga.chapters.forEachIndexed { index,chapter ->
            val expected=if(index==0)"First chapter second ordinal" else "Second chapter second ordinal"
            val descriptions=org.json.JSONObject(chapter.metadata).getJSONObject("pageDescriptions")
            assertEquals(expected,descriptions.getString("1"))
            if(index==0)assertEquals("First chapter stem fallback",descriptions.getString("0")) else assertFalse(descriptions.has("0"))
            assertArrayEquals(if(index==0)second else first,File(Uri.parse(chapter.pages[1]).path!!).readBytes())
            val output=File(context.cacheDir,"${UUID.randomUUID()}.cbz")
            output.outputStream().use { app.monogatari.android.downloads.ChapterExporter.write(context,manga,chapter,it) }
            val restored=ArchiveImporter.import(context,Uri.fromFile(output)).chapters.single()
            assertEquals(2,restored.pages.size)
            assertEquals(expected,org.json.JSONObject(restored.metadata).getJSONObject("pageDescriptions").getString("1"))
            assertArrayEquals(File(Uri.parse(chapter.pages[1]).path!!).readBytes(),File(Uri.parse(restored.pages[1]).path!!).readBytes())
        }
    }
    @Test fun originalDotIdentifiersOverrideLegacyCacheAndAndroidExtrasSurviveReopening() = runBlocking {
        val repo=AppRepository(context);val id="example.source::manga/part::chapter?lang=ja";val dot=id.replaceFirst("::",".")
        val extras=ReaderOptions(volumeKeys=true,brightness=.4f,contrast=1.2f)
        repo.change { AppState(settings=org.json.JSONObject().put("Reader.readingMode","rtl").put("Reader.readingMode.$id","vertical")
            .put("Reader.readingMode.$dot","ltr").put("Reader.pagedPageOffset.$dot",true).put("Android.Reader.options",extras.json().toString()).toString()) }
        val imported=ReaderOptions.fromLegacy(SettingsStore(repo),id)
        assertEquals("ltr",imported.mode);assertTrue(imported.pageOffset);assertTrue(imported.volumeKeys);assertEquals(.4f,imported.brightness)
        val saved=imported.copy(mode="webtoon").toLegacy(org.json.JSONObject(repo.state.value.settings),id)
        assertEquals("webtoon",saved.getString("Reader.readingMode.$dot"));assertFalse(saved.has("Reader.readingMode.$id"))
        repo.change { it.copy(settings=saved.toString()) };assertTrue(ReaderOptions.fromLegacy(SettingsStore(repo),id).volumeKeys)
    }
    @Test fun nativeExtrasArePerSeriesWhileOriginalReaderKeysRemainAuthoritative() = runBlocking {
        val repo=AppRepository(context);val first="sample.source::book/part::a";val second="sample.source::other"
        val global=ReaderOptions(mode="rtl",brightness=.2f,fit="width").toLegacy(org.json.JSONObject())
        repo.change { AppState(settings=global.toString()) }
        val changed=ReaderOptions.fromLegacy(SettingsStore(repo),first).copy(mode="ltr",brightness=.7f,fit="height",volumeKeys=true)
        val next=changed.toLegacy(global,first)
        assertEquals(.2,org.json.JSONObject(next.getString("Android.Reader.options")).getDouble("brightness"),.00001)
        assertTrue(next.has(ReaderOptions.seriesExtrasKey(first)));assertFalse(org.json.JSONObject(next.getString(ReaderOptions.seriesExtrasKey(first))).has("mode"))
        repo.change { it.copy(settings=next.toString()) }
        assertEquals(.7f,ReaderOptions.fromLegacy(SettingsStore(repo),first).brightness);assertTrue(ReaderOptions.fromLegacy(SettingsStore(repo),first).volumeKeys)
        assertEquals(.2f,ReaderOptions.fromLegacy(SettingsStore(repo),second).brightness);assertFalse(ReaderOptions.fromLegacy(SettingsStore(repo),second).volumeKeys)
        assertEquals("width",ReaderOptions.fromLegacy(SettingsStore(repo),second).fit);assertEquals("rtl",ReaderOptions.fromLegacy(SettingsStore(repo),second).mode)
        repo.change { it.copy(settings=org.json.JSONObject(it.settings).put("Reader.readingMode.${first.replaceFirst("::",".")}","vertical").toString()) }
        assertEquals("vertical",ReaderOptions.fromLegacy(SettingsStore(repo),first).mode)
    }
    @Test fun earlySeriesPreferenceExtrasMigrateWithoutOverwritingOriginalBackupValues() = runBlocking {
        val repo=AppRepository(context);val id="sample.source::book.with.dots::part";val cache=ReaderOptionStore(context)
        cache.save(ReaderOptions(mode="ltr",pageOffset=false,orientation="landscape",brightness=.65f,fit="fill"),id)
        repo.change { AppState(settings=org.json.JSONObject().put("Reader.readingMode.${id.replaceFirst("::",".")}","rtl")
            .put("Reader.pagedPageOffset.${id.replaceFirst("::",".")}",true).put("Reader.orientation","portrait").toString()) }
        try {
            val options=ReaderOptions.fromLegacy(SettingsStore(repo),id)
            assertEquals("rtl",options.mode);assertTrue(options.pageOffset);assertEquals("portrait",options.orientation)
            assertEquals(.65f,options.brightness);assertEquals("fill",options.fit)
            val saved=options.toLegacy(org.json.JSONObject(repo.state.value.settings),id)
            assertTrue(saved.has(ReaderOptions.seriesExtrasKey(id)));assertFalse(saved.has("Android.Reader.options.$id"))
            repo.change { it.copy(settings=saved.toString()) };cache.reset(id)
            assertEquals(.65f,ReaderOptions.fromLegacy(SettingsStore(repo),id).brightness)
        } finally { cache.reset(id) }
    }
    @Test fun editingKnownNativeOptionsRetainsGlobalAndPerSeriesUnknownNestedFields() {
        val id="source.with.dots::book/part::key";val key=ReaderOptions.seriesExtrasKey(id)
        val nested=org.json.JSONObject().put("future",org.json.JSONObject().put("flags",org.json.JSONArray().put(1).put("unknown")))
        val raw=org.json.JSONObject().put("Android.Reader.options",nested.toString()).put(key,nested.toString())
        val local=ReaderOptions(brightness=.8f).toLegacy(raw,id)
        val extras=org.json.JSONObject(local.getString(key))
        assertEquals("unknown",extras.getJSONObject("future").getJSONArray("flags").getString(1));assertEquals(.8,extras.getDouble("brightness"),.00001)
        assertEquals(nested.toString(),local.getString("Android.Reader.options"))
        val global=ReaderOptions(brightness=.3f).toLegacy(local)
        assertEquals("unknown",org.json.JSONObject(global.getString("Android.Reader.options")).getJSONObject("future").getJSONArray("flags").getString(1))
        assertEquals(local.getString(key),global.getString(key))
    }
    @Test fun importedChapterFlagBitmaskCombinesTriStateLanguageAndMultipleScanlators() {
        val first=Chapter("first","First",1.0,downloaded=true,language="ja",scanlator="A, B",sourceOrder=2)
        val completed=Chapter("completed","Completed",2.0,completed=true,downloaded=true,language="ja",scanlator="B",sourceOrder=1)
        val locked=Chapter("locked","Locked",3.0,downloaded=true,language="ja",scanlator="B",locked=true,sourceOrder=0)
        val english=Chapter("english","English",4.0,downloaded=true,language="en",scanlator="B",sourceOrder=3)
        val flags=1 or (1 shl 1) or (1 shl 4) or (1 shl 6) or (1 shl 8) or (1 shl 9)
        val meta=org.json.JSONObject().put("chapterFlags",flags).put("langFilter","ja").put("scanlatorFilter",org.json.JSONArray().put("B"))
        val manga=Manga("m","s","M",chapters=listOf(english,locked,completed,first),metadata=meta.toString())
        assertEquals(listOf("first"),ChapterListing.listed(manga).map{it.key})
        assertEquals(listOf("completed"),ChapterListing.listed(manga.copy(metadata=meta.put("chapterFlags",flags or (1 shl 7)).toString())).map{it.key})
    }
    @Test fun nextChapterPrefersRecentUnfinishedHistoryAndSkipsUndownloadedLockedChapter() {
        val first=Chapter("first","First",1.0,sourceOrder=2)
        val locked=Chapter("locked","Locked",2.0,locked=true,sourceOrder=1)
        val latest=Chapter("latest","Latest",3.0,sourceOrder=0)
        val manga=Manga("m","s","M",chapters=listOf(latest,locked,first))
        assertEquals("first",ChapterListing.next(manga)?.key)
        assertEquals("latest",ChapterListing.next(manga.copy(chapters=listOf(latest.copy(readAt=200),locked,first)))?.key)
        assertEquals("locked",ChapterListing.next(manga.copy(chapters=listOf(latest.copy(completed=true),locked.copy(downloaded=true,readAt=300),first)))?.key)
    }
    @Test fun localAppendAndDeletePreserveExistingHistoryAndOtherArchiveDirectories() = runBlocking {
        val repo=AppRepository(context);repo.change{AppState()}
        val firstArchive=archive("one.cbz",mapOf("ComicInfo.xml" to "<ComicInfo><Series>Same</Series><Number>1</Number></ComicInfo>".toByteArray(),"001.png" to byteArrayOf(1)))
        val first=app.monogatari.android.local.LocalLibrary.import(repo,Uri.fromFile(firstArchive))
        repo.updateChapter(first.id,first.chapters.single().key){it.copy(page=7,total=10,completed=true,readAt=123)}
        val secondArchive=archive("two.cbz",mapOf("002.png" to byteArrayOf(2)))
        val combined=app.monogatari.android.local.LocalLibrary.import(repo,Uri.fromFile(secondArchive),ImportOptions(mangaId=first.key,mangaTitle="Same",chapterNumber=2.0))
        assertEquals(first.id,combined.id);assertEquals(2,combined.chapters.size);assertEquals(7,combined.chapters.first().page);assertTrue(combined.chapters.first().completed)
        val firstPage=File(Uri.parse(combined.chapters.first().pages.first()).path!!);val secondPage=File(Uri.parse(combined.chapters.last().pages.first()).path!!)
        app.monogatari.android.local.LocalLibrary.deleteChapter(repo,first.id,combined.chapters.last().key)
        assertTrue(firstPage.exists());assertFalse(secondPage.exists());assertEquals(1,repo.state.value.manga.single().chapters.size)
        app.monogatari.android.local.LocalLibrary.deleteManga(repo,first.id);assertFalse(firstPage.exists());assertTrue(repo.state.value.manga.isEmpty())
    }
    @Test fun manualSpreadIsolationRestartsOnlyTheChosenSegment() {
        val pages=listOf(true,true,false,true,true,true)
        assertEquals(3,ReaderLayout.segmentHead(pages,4))
        assertEquals(listOf(listOf(0,1),listOf(2),listOf(3),listOf(4,5)),ReaderLayout.spreads(pages,true,false,setOf(3)))
        assertNull(ReaderLayout.segmentHead(pages,99))
    }
    @Test fun actualChapterZeroRemainsDistinctFromMissingNumberAndExportsComicInfoZero() {
        val missing=Chapter("missing","Special",0.0);val zero=Chapter("zero","",0.0,downloaded=true,metadata="{\"chapterNumber\":0}")
        assertNull(ChapterListing.number(missing));assertEquals(0.0,ChapterListing.number(zero))
        val file=File(context.cacheDir,"zero-export.cbz");file.outputStream().use{app.monogatari.android.downloads.ChapterExporter.write(context,Manga("m","s","M"),zero,it)}
        java.util.zip.ZipFile(file).use { zip ->val xml=zip.getInputStream(zip.getEntry("ComicInfo.xml")).bufferedReader().use{it.readText()};assertTrue(xml.contains("<Number>0.0</Number>")) }
    }
    @Test fun markdownPaginationRetainsFormattingLinksListsImagesAndAllCharacters() = runBlocking {
        val raw="# Heading\n\n**Bold** and *italic* [link](https://example.test/read).\n\n1. First\n2. Second\n\n![Real image](https://example.test/p.png)\n\n~~Deleted~~\n\n```\n  code line\n```\n\n"+"Long readable paragraph.\n\n".repeat(40)
        val html=ReaderMarkdown.materialize(context,raw){ Bitmap.createBitmap(70,80,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.BLUE)} }
        val spans=ReaderMarkdown.spans(context,html,250,250)
        assertTrue(spans.contains("1. First"));assertTrue(spans.contains("2. Second"));assertFalse(spans.contains("**"))
        assertTrue(spans.getSpans(0,spans.length,android.text.style.StyleSpan::class.java).isNotEmpty())
        assertEquals("https://example.test/read",spans.getSpans(0,spans.length,android.text.style.URLSpan::class.java).single().url)
        assertEquals(1,spans.getSpans(0,spans.length,android.text.style.ImageSpan::class.java).size)
        val pages=TextPaginator.paginate(spans,250,250,18f,1.5f,"serif")
        assertTrue(pages.size>1);assertEquals(spans.toString(),pages.joinToString(""){spans.subSequence(it.start,it.end).toString()})
        assertNull(ReaderFonts.file("file:${File(context.filesDir,"secret.ttf").path}"))
    }
    @Test fun epubChapterLinksResolveToActualPrivateHtmlAndRetainAnchor() {
        val file=archive("links.epub",mapOf(
            "META-INF/container.xml" to "<container><rootfiles><rootfile full-path='OEBPS/package.opf'/></rootfiles></container>".toByteArray(),
            "OEBPS/package.opf" to "<package><metadata/><manifest><item id='a' href='a.xhtml'/><item id='b' href='b.xhtml'/></manifest><spine><itemref idref='a'/><itemref idref='b'/></spine></package>".toByteArray(),
            "OEBPS/a.xhtml" to "<html><body><p>First</p><a href='b.xhtml#target'>Go</a></body></html>".toByteArray(),
            "OEBPS/b.xhtml" to "<html><body><p id='target'>Second</p></body></html>".toByteArray()))
        val manga=ArchiveImporter.import(context,Uri.fromFile(file));val html=File(Uri.parse(manga.chapters.first().html).path!!).readText()
        assertTrue(html.contains(manga.chapters.last().html+"#target"))
    }
    @Test fun mixedTextAndImageArchiveUsesItsFirstImageForTheCover() {
        val file=archive("mixed.cbz",mapOf("0001.txt" to "Opening text".toByteArray(),"0002.png" to byteArrayOf(0x89.toByte(),0x50,0x4e,0x47)))
        val manga=ArchiveImporter.import(context,Uri.fromFile(file));assertTrue(manga.chapters.single().pages.first().endsWith(".txt"))
        assertTrue(manga.cover.endsWith(".png"));assertEquals(manga.chapters.single().pages.last(),manga.cover)
    }
    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun sourceDescriptionsFollowShapedMarkdownCharacterRangesAfterRepagination() {
        val first=ReaderTextSources.wrap(0,ReaderMarkdown.html("**First source**. "+"Readable first paragraph. ".repeat(90)))
        val second=ReaderTextSources.wrap(1,ReaderMarkdown.html("# Second source\n\n"+"Second paragraph. ".repeat(90)))
        val spans=ReaderMarkdown.spans(context,first+second,250,300)
        val ranges=ReaderTextSources.ranges(spans)
        assertEquals(listOf(0,1),ranges.map{it.index});assertTrue(ranges[0].end<=ranges[1].start)
        assertTrue(spans.subSequence(ranges[0].start,ranges[0].end).contains("First source"))
        assertTrue(spans.subSequence(ranges[1].start,ranges[1].end).contains("Second source"))
        assertFalse(spans.subSequence(ranges[1].start,ranges[1].end).contains("First source"))
        val pages=TextPaginator.paginate(spans,250,300,18f,1.4f,"system")
        assertEquals(setOf(0),ReaderTextSources.indices(spans,listOf(pages.first())))
        assertEquals(setOf(1),ReaderTextSources.indices(spans,listOf(pages.last())))
        assertEquals(setOf(0,1),ReaderTextSources.indices(spans,pages))
        assertEquals(setOf(0),ReaderTextSources.descriptionIndices(setOf(1),setOf(0),true))
        assertTrue(ReaderTextSources.descriptionIndices(setOf(1),setOf(0),false).isEmpty())
        assertEquals(setOf(1),ReaderTextSources.descriptionIndices(setOf(1),setOf(0,1),true))
    }
    @Test fun lookupGestureMapsFitCropPanZoomAndRespectsOriginalLanguageRestriction() {
        assertNull(ReaderImageGeometry.normalizedTap(250f,20f,500f,1000f,400f,400f,"fit"))
        assertEquals(.5f,ReaderImageGeometry.normalizedTap(250f,500f,500f,1000f,400f,400f,"fit")!!.first,0f)
        val zoom=ReaderImageGeometry.normalizedTap(450f,500f,500f,1000f,400f,400f,"fit",2f,100f)
        assertEquals(.6f,zoom!!.first,.001f);assertEquals(.5f,zoom.second,.001f)
        assertEquals(.25f,ReaderImageGeometry.normalizedTap(0f,500f,500f,1000f,400f,400f,"fill")!!.first,.001f)
        assertFalse(ReaderImageGeometry.lookupEnabled(true,true,"ja",setOf("en")))
        assertTrue(ReaderImageGeometry.lookupEnabled(true,true,"",setOf("en")))
        assertTrue(ReaderImageGeometry.lookupEnabled(true,true,"ja",setOf("en"),text=true))
        assertEquals("ja",ReaderImageGeometry.language("ja","{\"info\":{\"languages\":[\"en\"]}}","ko"))
        assertEquals("en",ReaderImageGeometry.language("","{\"info\":{\"languages\":[\"en\",\"ja\"]}}","ja"))
        assertEquals("ko",ReaderImageGeometry.language("","{}","ko"))
        assertFalse(ReaderImageGeometry.lookupEnabled(true,true,ReaderImageGeometry.language("","{\"info\":{\"languages\":[\"en\"]}}"),setOf("ja")))
    }
    @Test fun completeFilenameParserGroupsSeriesAndKeepsVolumeOnlyAndRanges() = runBlocking {
        val folder=File(context.cacheDir,"parser-${UUID.randomUUID()}").apply{mkdirs()}
        fun book(name:String)=File(folder,name).also { file->ZipOutputStream(file.outputStream()).use{zip->zip.putNextEntry(ZipEntry("0001.png"));zip.write(byteArrayOf(1,2,3));zip.closeEntry()} }
        val repo=AppRepository(context);repo.change{AppState()}
        val first=app.monogatari.android.local.LocalLibrary.import(repo,Uri.fromFile(book("Grand Series v01 c001.cbz")))
        val second=app.monogatari.android.local.LocalLibrary.import(repo,Uri.fromFile(book("Grand Series v02 c002-c005.cbz")))
        assertEquals("Grand Series",first.title);assertEquals(first.id,second.id);assertEquals(2,second.chapters.size)
        assertEquals("2-5",org.json.JSONObject(second.chapters.last().metadata).getString("localChapterRange"))
        val volume=ArchiveImporter.import(context,Uri.fromFile(book("Volume Series v03.cbz"))).chapters.single()
        assertEquals(3.0,volume.volume);assertNull(ChapterListing.number(volume))
        val override=ArchiveImporter.import(context,Uri.fromFile(book("Name v02 c005.cbz")),ImportOptions(mangaTitle="Explicit",chapterNumber=7.0,volume=9.0))
        assertEquals("Explicit",override.title);assertEquals(7.0,override.chapters.single().number,0.0);assertEquals(9.0,override.chapters.single().volume)
    }
    @Test fun scrollHistoryUsesMeasuredViewportPagesAndKeepsPreciseNormalizedPositionSeparate() {
        val count=ReaderTextSources.scrollPageCount(4201f,1000f)
        assertEquals(5,count);assertEquals(0,ReaderTextSources.scrollPage(.01991,count));assertEquals(2,ReaderTextSources.scrollPage(.40001,count))
        assertEquals(4,ReaderTextSources.scrollPage(1.0,count));assertEquals(1,ReaderTextSources.scrollPageCount(300f,1000f))
        assertEquals(0,ReaderTextSources.scrollPage(Double.NaN.takeIf(Double::isFinite) ?: 0.0,count))
    }
    @Test fun removingCoverChapterSelectsRemainingImageAndPreservesSharedArchivePages() = runBlocking {
        val file=archive("shared.cbz",mapOf("Ch1/001.png" to byteArrayOf(1),"Ch2/001.txt" to "Text first".toByteArray(),"Ch2/002.png" to byteArrayOf(2)))
        val repo=AppRepository(context);repo.change{AppState()};val manga=app.monogatari.android.local.LocalLibrary.import(repo,Uri.fromFile(file))
        val remaining=manga.chapters.last();app.monogatari.android.local.LocalLibrary.deleteChapter(repo,manga.id,manga.chapters.first().key)
        val current=repo.state.value.manga.single();assertEquals(remaining.pages.last(),current.cover);assertTrue(current.cover.endsWith(".png"))
        assertTrue(remaining.pages.all{File(Uri.parse(it).path!!).isFile})
    }
}
