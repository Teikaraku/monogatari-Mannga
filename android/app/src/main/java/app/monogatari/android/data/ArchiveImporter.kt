package app.monogatari.android.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Html
import app.monogatari.android.local.LocalFileNameParser
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.*
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

data class ImportOptions(val mangaId: String? = null, val mangaTitle: String? = null,
    val chapterTitle: String? = null, val chapterNumber: Double? = null, val volume: Double? = null)

/** Archive paths and ComicInfo titles never become paths in the app's filesystem. */
object ArchiveImporter {
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic", "heif")
    private val textExtensions = setOf("txt", "md")
    private const val totalLimit = 1024L * 1024 * 1024
    internal val naturalOrder = Comparator<String> { a, b ->
        val parts = Regex("[0-9]+|[^0-9]+")
        val left = parts.findAll(a.lowercase()).map { it.value }.toList()
        val right = parts.findAll(b.lowercase()).map { it.value }.toList()
        var result = 0
        for (i in 0 until minOf(left.size, right.size)) {
            val x = left[i]; val y = right[i]
            result = if (x.first().isDigit() && y.first().isDigit()) {
                val xn = x.trimStart('0').ifEmpty { "0" }; val yn = y.trimStart('0').ifEmpty { "0" }
                xn.length.compareTo(yn.length).takeIf { it != 0 } ?: xn.compareTo(yn)
            } else x.compareTo(y)
            if (result != 0) break
        }
        result.takeIf { it != 0 } ?: left.size.compareTo(right.size)
    }
    private class Budget { var size = 0L; fun add(count: Long) { size += count; require(size <= totalLimit) { "解压内容超过 1 GB" } } }
    fun import(context: Context, uri: Uri, options: ImportOptions = ImportOptions()): Manga {
        val name = runCatching { context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } }.getOrNull() ?: run {
            val path=uri.path.orEmpty().removePrefix("/")
            // File URIs created by a Windows JVM retain backslash separators; content display names do not.
            if(uri.scheme=="file" && (Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(path) || path.startsWith("\\\\")))
                path.substringAfterLast('\\').substringAfterLast('/')
            else uri.lastPathSegment
        } ?: "导入漫画.cbz"
        val id = UUID.randomUUID().toString()
        val directory = File(context.filesDir, "local/$id").apply { check(mkdirs()) { "无法创建本地书籍目录" } }
        try {
            val archive = File(directory, "original.archive")
            context.contentResolver.openInputStream(uri)?.use { input -> archive.outputStream().use { copyBounded(input, it, totalLimit) } }
                ?: error("无法打开文件")
            return ZipFile(archive).use { zip ->
                require(zip.size() <= 25000) { "归档包含过多文件" }
                val epub = name.endsWith(".epub", true) || entry(zip, "META-INF/container.xml") != null
                val metadata = if (epub) epubMetadata(zip) else comicInfo(zip)
                val title = options.mangaTitle ?: metadata["Series"]?.takeIf(String::isNotBlank) ?: metadata["title"]?.takeIf(String::isNotBlank)
                    ?: LocalFileNameParser.parseMangaSeries(name).takeIf(String::isNotBlank) ?: name.substringBeforeLast('.')
                val budget = Budget()
                val language=metadata["LanguageISO"] ?: metadata["language"].orEmpty()
                val chapters = (if (epub) importEpub(zip, directory, budget) else importComic(zip, directory, name, options, budget))
                    .map { if(it.language.isBlank())it.copy(language=language) else it }
                require(chapters.isNotEmpty()) { "文件中没有可读取的图片或章节；支持 CBZ、ZIP 和 EPUB" }
                var cover = chapters.asSequence().flatMap { it.pages.asSequence() }.firstOrNull { page ->
                    Uri.parse(page).path?.substringAfterLast('.')?.lowercase() in imageExtensions
                }.orEmpty()
                if (epub) metadata["coverPath"]?.let { path -> entry(zip, path)?.let { e ->
                    val target = File(directory, "cover.${ext(e.name)}"); extract(zip, e, target, budget); cover = Uri.fromFile(target).toString()
                } }
                Manga(options.mangaId ?: id, "local", title, cover,
                    author = metadata["Writer"] ?: metadata["creator"].orEmpty(),
                    description = metadata["Summary"] ?: metadata["description"].orEmpty(),
                    tags = (metadata["Genre"].orEmpty() + "," + metadata["Tags"].orEmpty() + "," + metadata["subject"].orEmpty())
                        .split(',').map(String::trim).filter(String::isNotBlank).distinct(),
                    chapters = chapters, inLibrary = true,
                    viewer = if (metadata["Manga"] == "YesAndRightToLeft" || metadata["direction"] == "rtl") "rtl" else "auto",
                    language = metadata["LanguageISO"] ?: metadata["language"].orEmpty(),
                    artist=metadata["Penciller"] ?: metadata["artist"].orEmpty(),
                    metadata = org.json.JSONObject(metadata).put("archiveFolder", id).toString())
            }
        } catch (e: Exception) { directory.deleteRecursively(); throw e }
    }
    private fun visible(name: String) = name.split('/').none { it.startsWith('.') && it !in setOf(".", "..") || it == "__MACOSX" }
    private fun ext(path: String) = path.substringAfterLast('.', "").lowercase()
    private fun entry(zip: ZipFile, path: String): ZipEntry? {
        val cleaned = normalize(path)
        return zip.getEntry(cleaned) ?: zip.entries().asSequence().firstOrNull { runCatching { normalize(Uri.decode(it.name)) }.getOrNull().equals(cleaned, true) }
    }
    internal fun normalize(path: String): String {
        val pieces = mutableListOf<String>()
        path.replace('\\', '/').split('/').forEach { p -> when (p) { "", "." -> Unit; ".." -> { require(pieces.isNotEmpty()) { "归档路径越出根目录" }; pieces.removeAt(pieces.lastIndex) }; else -> pieces += p } }
        return pieces.joinToString("/")
    }
    private fun resolve(base: String, href: String): String {
        require(!Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(href) && !href.startsWith("//")) { "EPUB 资源必须来自归档" }
        val decoded = Uri.decode(href.substringBefore('#').substringBefore('?'))
        return normalize(if (decoded.startsWith('/')) decoded else base.substringBeforeLast('/', "").let { if (it.isEmpty()) decoded else "$it/$decoded" })
    }
    private fun extract(zip: ZipFile, e: ZipEntry, target: File, budget: Budget, limit: Long = 50L * 1024 * 1024) {
        target.parentFile?.mkdirs()
        budget.add(zip.getInputStream(e).use { input -> target.outputStream().use { copyBounded(input, it, limit) } })
    }
    private fun read(zip: ZipFile, e: ZipEntry, limit: Long = 5L * 1024 * 1024): ByteArray {
        return ByteArrayOutputStream().also { output -> zip.getInputStream(e).use { copyBounded(it, output, limit) } }.toByteArray()
    }
    private fun xml(bytes: ByteArray): Document = app.monogatari.android.local.ArchiveXml.parse(bytes)
    private fun comicInfo(zip: ZipFile, folder: String = ""): Map<String, String> {
        val e = zip.entries().asSequence().firstOrNull { !it.isDirectory && runCatching { normalize(it.name) }.getOrNull().equals(if (folder.isBlank()) "ComicInfo.xml" else "$folder/ComicInfo.xml", true) }
            ?: return emptyMap()
        return runCatching {
            val doc = xml(read(zip, e)); val nodes = doc.documentElement.childNodes
            buildMap { for (i in 0 until nodes.length) (nodes.item(i) as? Element)?.let { put(it.localName ?: it.tagName, it.textContent.trim()) } }
        }.getOrDefault(emptyMap())
    }
    private fun filenameNumber(name: String): Double? = LocalFileNameParser.chapterNumber(name)
        ?: Regex("(?:^|[ _-])([0-9]+(?:\\.[0-9]+)?)(?:[ _-]|$)").find(name.substringBeforeLast('.'))?.groupValues?.get(1)?.toDoubleOrNull()
    private fun importComic(zip: ZipFile, directory: File, name: String, options: ImportOptions, budget: Budget): List<Chapter> {
        val nested = zip.entries().asSequence().filter { !it.isDirectory && visible(it.name) && ext(it.name) in setOf("cbz", "zip") }.toList()
            .sortedWith { a, b -> naturalOrder.compare(a.name, b.name) }
        if (nested.isNotEmpty()) {
            require(nested.size <= 2000) { "归档章节过多" }
            return nested.flatMapIndexed { i, e ->
                val folder = File(directory, "chapter-$i").apply { mkdirs() }
                val archive = File(folder, "original.archive"); extract(zip, e, archive, budget, totalLimit)
                ZipFile(archive).use { inner -> importComicPages(inner, folder, e.name.substringAfterLast('/'), options, budget, allowFolders = false) }
            }
        }
        return importComicPages(zip, directory, name, options, budget, allowFolders = true)
    }
    private fun importComicPages(zip: ZipFile, directory: File, name: String, options: ImportOptions, budget: Budget, allowFolders: Boolean): List<Chapter> {
        val entries = zip.entries().asSequence().filter { !it.isDirectory && visible(it.name) &&
            (ext(it.name) in imageExtensions || (ext(it.name) in textExtensions && !it.name.endsWith("desc.txt", true))) }.toList()
            .sortedWith { a, b -> naturalOrder.compare(a.name, b.name) }
        require(entries.size <= 10000) { "漫画页数过多" }
        fun logicalPath(name: String) = runCatching { normalize(name) }.getOrElse { name.substringAfterLast('/') }
        val dirs = entries.map { logicalPath(it.name).substringBeforeLast('/', "") }.distinct()
        val groups = if (allowFolders && dirs.size > 1 && dirs.none(String::isEmpty)) entries.groupBy { logicalPath(it.name).substringBeforeLast('/') }
            else mapOf("" to entries)
        val descriptionFiles=zip.entries().asSequence().filter { !it.isDirectory && visible(it.name) && it.name.endsWith("desc.txt",true) }
            .map { logicalPath(it.name) to it }.toList()
        val global = comicInfo(zip)
        return groups.entries.filter { it.value.isNotEmpty() }.mapIndexed { index, (folder, pages) ->
            val info = global + comicInfo(zip, folder)
            val chapterFolder = File(directory, "pages-$index").apply { mkdirs() }
            val urls = mutableListOf<String>(); val text = mutableListOf<String>(); var hasImages = false
            val descriptions=org.json.JSONObject()
            // Aidoku CBZ descriptions identify a 1-based page ordinal, independently of its image filename.
            val ordinalFolder=folder.ifEmpty { pages.map { logicalPath(it.name).substringBeforeLast('/',"") }.distinct().singleOrNull().orEmpty() }
            val ordinalDescriptions=descriptionFiles.filter { (path,_) ->
                path.substringBeforeLast('/',"")==ordinalFolder || groups.size==1 && path.substringBeforeLast('/',"").isEmpty()
            }.mapNotNull { (path,entry) ->path.substringAfterLast('/').substringBefore('.').toIntOrNull()
                ?.takeIf { it in 1..pages.size }?.let { it-1 to entry } }.toMap()
            pages.forEachIndexed { i, e ->
                val dest = File(chapterFolder, "${i.toString().padStart(5, '0')}.${ext(e.name)}")
                if (ext(e.name) in textExtensions) { val bytes = read(zip, e); budget.add(bytes.size.toLong()); text += bytes.toString(Charsets.UTF_8); dest.writeBytes(bytes) }
                else { extract(zip, e, dest, budget); hasImages = true }
                urls += Uri.fromFile(dest).toString()
                val descriptionName=logicalPath(e.name).substringBeforeLast('.')+".desc.txt"
                (ordinalDescriptions[i] ?: descriptionFiles.firstOrNull { it.first.equals(descriptionName,true) }?.second)?.let { description ->
                    val bytes=read(zip,description);budget.add(bytes.size.toLong());descriptions.put(i.toString(),bytes.toString(Charsets.UTF_8))
                }
            }
            val filename = if (folder.isNotEmpty()) folder.substringAfterLast('/') else name.substringBeforeLast('.')
            val parsedChapter=LocalFileNameParser.parseMangaChapter(filename);val parsedVolume=LocalFileNameParser.parseMangaVolume(filename)
            val volume=options.volume ?: info["Volume"]?.toDoubleOrNull()?.takeIf(Double::isFinite) ?: LocalFileNameParser.volumeNumber(filename)
            val detectedChapter=options.chapterNumber ?: info["Number"]?.toDoubleOrNull()?.takeIf(Double::isFinite) ?: filenameNumber(filename)
            val autoNumber=detectedChapter==null && volume==null
            val number=detectedChapter ?: if(autoNumber)(index+1).toDouble() else null
            Chapter(UUID.randomUUID().toString(), options.chapterTitle ?: info["Title"] ?: filename,
                number ?: 0.0,
                urls, if (hasImages) "" else text.joinToString("\n\n"), total = urls.size.coerceAtLeast(1), downloaded = true,
                volume = volume, language = info["LanguageISO"].orEmpty(),
                scanlator = info["Translator"].orEmpty(), description = info["Summary"].orEmpty(), sourceOrder = index,
                metadata = org.json.JSONObject(info).put("archiveFolder", directory.name).put("pageDescriptions",descriptions)
                    .put("chapterNumber",number ?: org.json.JSONObject.NULL).put("localNumberAuto",autoNumber)
                    .put("localChapterRange",parsedChapter).put("localVolumeRange",parsedVolume).put("localSeries",LocalFileNameParser.parseMangaSeries(filename)).toString())
        }
    }
    private fun epubPackage(zip: ZipFile): Pair<String, Document> {
        val container = xml(read(zip, entry(zip, "META-INF/container.xml") ?: error("EPUB 缺少 container.xml")))
        val path = (container.getElementsByTagNameNS("*", "rootfile").item(0) as? Element)?.getAttribute("full-path") ?: error("EPUB 缺少书目")
        return path to xml(read(zip, entry(zip, path) ?: error("EPUB 缺少 $path")))
    }
    private fun epubMetadata(zip: ZipFile): Map<String, String> {
        val (opfPath, opf) = epubPackage(zip)
        return buildMap {
            listOf("title", "creator", "description", "subject", "language", "identifier", "publisher", "date").forEach { key ->
                val nodes = opf.getElementsByTagNameNS("*", key)
                if (nodes.length > 0) put(key, (0 until nodes.length).joinToString(", ") { nodes.item(it).textContent.trim() })
            }
            val spine = opf.getElementsByTagNameNS("*", "spine").item(0) as? Element
            spine?.getAttribute("page-progression-direction")?.takeIf(String::isNotBlank)?.let { put("direction", it) }
            val items = opf.getElementsByTagNameNS("*", "item")
            val meta = opf.getElementsByTagNameNS("*", "meta")
            val coverId = (0 until meta.length).map { meta.item(it) as Element }.firstOrNull { it.getAttribute("name") == "cover" }?.getAttribute("content")
            (0 until items.length).map { items.item(it) as Element }.firstOrNull { it.getAttribute("properties").split(' ').contains("cover-image") || it.getAttribute("id") == coverId }
                ?.let { put("coverPath", resolve(opfPath, it.getAttribute("href"))) }
        }
    }
    private fun importEpub(zip: ZipFile, directory: File, budget: Budget): List<Chapter> {
        val (opfPath, opf) = epubPackage(zip)
        val manifest = opf.getElementsByTagNameNS("*", "item")
        val paths = (0 until manifest.length).associate { val item = manifest.item(it) as Element; item.getAttribute("id") to resolve(opfPath, item.getAttribute("href")) }
        val assets = mutableMapOf<String, String>()
        paths.values.forEach { path ->
            if (ext(path) in imageExtensions + setOf("svg", "css", "ttf", "otf", "woff", "woff2")) entry(zip, path)?.let { e ->
                val target = File(directory, "resources/${AppRepository.hash(path)}.${ext(path)}"); extract(zip, e, target, budget)
                assets[path] = Uri.fromFile(target).toString()
            }
        }
        paths.values.filter { ext(it) == "css" }.forEach { path -> assets[path]?.let { url ->
            val file = File(Uri.parse(url).path!!)
            file.writeText(file.readText().replace(Regex("(?i)url\\(\\s*([\"']?)(.*?)\\1\\s*\\)")) { match ->
                val ref = runCatching { resolve(path, match.groupValues[2]) }.getOrNull()
                "url(\"${assets[ref].orEmpty()}\")"
            })
        } }
        val titles = mutableMapOf<String, String>()
        (0 until manifest.length).map { manifest.item(it) as Element }.filter { it.getAttribute("properties").split(' ').contains("nav") }.forEach { nav ->
            val path = paths[nav.getAttribute("id")] ?: return@forEach
            entry(zip, path)?.let { e ->
                val content = read(zip, e).toString(Charsets.UTF_8)
                Regex("(?is)<a\\b[^>]*href\\s*=\\s*([\"'])(.*?)\\1[^>]*>(.*?)</a>").findAll(content).forEach { link ->
                    runCatching { resolve(path, link.groupValues[2]) }.getOrNull()?.let { ref -> titles[ref] = Html.fromHtml(link.groupValues[3], Html.FROM_HTML_MODE_LEGACY).toString().trim() }
                }
            }
        }
        paths.values.filter { it.endsWith(".ncx", true) }.forEach { path -> runCatching {
            val toc = xml(read(zip, entry(zip, path) ?: return@runCatching)); val points = toc.getElementsByTagNameNS("*", "navPoint")
            for (i in 0 until points.length) { val point = points.item(i) as Element
                val src = (point.getElementsByTagNameNS("*", "content").item(0) as? Element)?.getAttribute("src") ?: continue
                titles[resolve(path, src)] = point.getElementsByTagNameNS("*", "text").item(0)?.textContent.orEmpty()
            }
        } }
        val spine = opf.getElementsByTagNameNS("*", "itemref"); require(spine.length <= 2000) { "EPUB 章节过多" }
        val chapterResources=(0 until spine.length).mapNotNull { index ->
            val item=spine.item(index) as Element
            if(item.getAttribute("linear")=="no")null else paths[item.getAttribute("idref")]?.let { path ->path to Uri.fromFile(File(directory,"epub-$index.html")).toString() }
        }.toMap()
        return (0 until spine.length).mapNotNull { index ->
            val item = spine.item(index) as Element
            if (item.getAttribute("linear") == "no") return@mapNotNull null
            val path = paths[item.getAttribute("idref")] ?: return@mapNotNull null
            val e = entry(zip, path) ?: error("EPUB 章节资源缺失：$path")
            val content = read(zip, e).also { budget.add(it.size.toLong()) }.toString(Charsets.UTF_8)
            val clean = content.replace(Regex("(?is)<script\\b.*?</script\\s*>"), "").replace(Regex("(?is)<iframe\\b.*?</iframe\\s*>"), "")
            val rewritten = clean.replace(Regex("(?i)(src|href)\\s*=\\s*([\"'])(.*?)\\2")) { match ->
                val resolved = runCatching { resolve(path, match.groupValues[3]) }.getOrNull()
                val original=match.groupValues[3]
                val target=assets[resolved] ?: chapterResources[resolved]
                target?.let { "${match.groupValues[1]}=\"$it${original.substringAfter('#',"").takeIf(String::isNotBlank)?.let { fragment ->"#$fragment" }.orEmpty()}\"" }
                    ?: if (match.groupValues[1].equals("src", true)) "src=\"\"" else match.value
            }
            val text = Html.fromHtml(clean.replace(Regex("(?is)<style\\b.*?</style\\s*>"), ""), Html.FROM_HTML_MODE_LEGACY).toString().trim()
            val html = File(directory, "epub-$index.html").apply { writeText(rewritten) }
            Chapter("epub-$index", titles[path]?.takeIf(String::isNotBlank) ?: text.lineSequence().firstOrNull(String::isNotBlank)?.take(80) ?: "第 ${index + 1} 章",
                (index + 1).toDouble(), text = text, total = 1, downloaded = true, html = Uri.fromFile(html).toString(), sourceOrder = index,
                metadata=org.json.JSONObject().put("chapterNumber",index+1).toString())
        }
    }
    internal fun copyBounded(input: InputStream, output: OutputStream, max: Long): Long {
        val buffer = ByteArray(32768); var total = 0L
        while (true) { val n = input.read(buffer); if (n < 0) return total; total += n
            require(total <= max) { "文件超过大小限制" }; output.write(buffer, 0, n) }
    }
}
