package app.monogatari.android.downloads

import android.content.Context
import android.net.Uri
import app.monogatari.android.data.*
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ChapterExporter {
    fun write(context: Context, manga: Manga, chapter: Chapter, output: OutputStream) {
        require(chapter.downloaded || manga.sourceId == "local") { "请先完整下载章节再导出" }
        fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        ZipOutputStream(output).use { zip ->
            fun item(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            val metadata = """<?xml version="1.0" encoding="UTF-8"?><ComicInfo><Series>${escape(manga.title)}</Series><Title>${escape(chapter.title)}</Title>${app.monogatari.android.reader.ChapterListing.number(chapter)?.let { "<Number>$it</Number>" }.orEmpty()}${chapter.volume?.let { "<Volume>$it</Volume>" }.orEmpty()}<Writer>${escape(manga.author)}</Writer><Penciller>${escape(manga.artist)}</Penciller><Summary>${escape(manga.description)}</Summary><Genre>${escape(manga.tags.joinToString(", "))}</Genre><LanguageISO>${escape(chapter.language.ifEmpty { manga.language })}</LanguageISO>${if(manga.viewer=="rtl")"<Manga>YesAndRightToLeft</Manga>" else ""}<PageCount>${chapter.pages.size}</PageCount></ComicInfo>"""
            item("ComicInfo.xml", metadata.toByteArray(Charsets.UTF_8))
            chapter.pages.forEachIndexed { index, page ->
                val uri = Uri.parse(page); require(uri.scheme in setOf("file", "content", "aidoku-archive")) { "章节尚未完整下载" }
                (if (ArchivePageStore.isArchive(page)) java.io.ByteArrayInputStream(ArchivePageStore.bytes(context, page)) else context.contentResolver.openInputStream(uri))?.use { input ->
                    val signature = ByteArray(16); val n = input.read(signature)
                    val ext = when {
                        n >= 3 && signature[0] == 0xff.toByte() && signature[1] == 0xd8.toByte() -> "jpg"
                        n >= 8 && signature[0] == 0x89.toByte() && signature[1] == 0x50.toByte() -> "png"
                        n >= 6 && String(signature, 0, 3) == "GIF" -> "gif"
                        n >= 12 && String(signature, 8, 4) == "WEBP" -> "webp"
                        else -> (if(ArchivePageStore.isArchive(page))uri.getQueryParameter("entry") else uri.lastPathSegment)
                            ?.substringAfterLast('.', "image")?.lowercase()?.takeIf { it in setOf("txt","md","avif","bmp","svg","heic","heif") } ?: "image"
                    }
                    zip.putNextEntry(ZipEntry("${(index + 1).toString().padStart(4, '0')}.$ext"))
                    if (n > 0) zip.write(signature, 0, n); ArchiveImporter.copyBounded(input, zip, 50L * 1024 * 1024); zip.closeEntry()
                } ?: error("页面文件缺失")
            }
            if (chapter.pages.isEmpty() && chapter.text.isNotBlank()) item("chapter.txt", chapter.text.toByteArray(Charsets.UTF_8))
            org.json.JSONObject(chapter.metadata).optJSONObject("pageDescriptions")?.let { descriptions -> descriptions.keys().forEach { key ->
                key.toIntOrNull()?.let { index -> item("${(index+1).toString().padStart(4,'0')}.desc.txt",descriptions.getString(key).toByteArray(Charsets.UTF_8)) }
            } }
        }
    }
}
