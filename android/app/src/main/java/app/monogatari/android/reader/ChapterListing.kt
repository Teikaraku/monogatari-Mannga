package app.monogatari.android.reader

import app.monogatari.android.data.Chapter
import app.monogatari.android.data.Manga
import app.monogatari.android.data.AppState
import app.monogatari.android.data.objects
import app.monogatari.android.data.strings
import org.json.JSONArray
import org.json.JSONObject

/** Uses the original chapterFlags bit layout so imported iOS filters remain active. */
object ChapterListing {
    /** The native scalar uses zero as a fallback; the SDK/backup metadata distinguishes nil from chapter 0. */
    fun number(chapter:Chapter):Double? {
        if(chapter.number!=0.0)return chapter.number.takeIf(Double::isFinite)
        val raw=JSONObject(chapter.metadata)
        return listOf("chapterNumber","chapter","chapterNum","Number").firstNotNullOfOrNull { key ->
            val value=raw.opt(key);(value as? Number)?.toDouble()?.takeIf(Double::isFinite) ?: (value as? String)?.toDoubleOrNull()?.takeIf(Double::isFinite)
        }
    }
    fun metadata(manga:Manga)=JSONObject(manga.metadata)
    fun orphans(state:AppState,manga:Manga):List<Chapter> = JSONObject(state.records).optJSONArray("history").objects()
        .filter { it.optString("sourceId")==manga.sourceId && it.optString("mangaId")==manga.key }
        .mapNotNull { row ->row.optJSONObject("_chapter")?.let { runCatching { Chapter.parse(it) }.getOrNull() } }
        .filter { it.downloaded && manga.chapters.none { c ->c.key==it.key } }.distinctBy { it.key }
    fun flags(manga:Manga)=metadata(manga).optInt("chapterFlags")
    fun ascending(manga:Manga)=flags(manga) and 1 != 0
    private fun volumeSeries(chapter: Chapter): String {
        val title = chapter.title
        val match = Regex("""(?:第\s*)?(?:[0-9]+(?:\.[0-9]+)?|[〇零一二两兩三四五六七八九十百千万萬]+)\s*[卷巻册冊]""").find(title) ?: return ""
        return title.substring(0, match.range.first).trim().lowercase(java.util.Locale.ROOT)
    }
    private val numberOrder = Comparator<Chapter> { a, b ->
        val an = number(a); val bn = number(b)
        var result = (an ?: -1.0).compareTo(bn ?: -1.0)
        if (result == 0 && an == null && bn == null && a.volume != null && b.volume != null)
            result = volumeSeries(a).compareTo(volumeSeries(b))
        if (result == 0) result = (a.volume ?: -1.0).compareTo(b.volume ?: -1.0)
        if (result == 0) result = b.sourceOrder.compareTo(a.sourceOrder)
        result
    }
    fun sorted(manga:Manga):List<Chapter> {
        val flags=flags(manga);val ascending=flags and 1 != 0
        return when((flags and 14) shr 1) {
            1 -> manga.chapters.sortedWith(numberOrder).let { if(ascending)it else it.asReversed() }
            2 -> manga.chapters.sortedWith(compareBy<Chapter> { it.dateUploaded }.thenByDescending { it.sourceOrder }).let { if(ascending)it else it.asReversed() }
            else -> manga.chapters.sortedBy { it.sourceOrder }.let { if(ascending)it.asReversed() else it }
        }
    }
    fun scanlators(chapter:Chapter):List<String> = JSONObject(chapter.metadata).optJSONArray("scanlators")?.strings()?.takeIf { it.isNotEmpty() }
        ?: chapter.scanlator.split(',').map(String::trim).filter(String::isNotEmpty).ifEmpty { listOf("") }
    fun includes(manga:Manga,chapter:Chapter):Boolean {
        val meta=metadata(manga);val flags=meta.optInt("chapterFlags")
        fun filter(enabled:Int,excluded:Int,value:Boolean)=flags and enabled == 0 || value != (flags and excluded != 0)
        val language=meta.optString("langFilter").takeUnless { it=="null" }.orEmpty()
        val selectedScanlators=meta.optJSONArray("scanlatorFilter")?.strings() ?: runCatching { JSONArray(meta.optString("scanlatorFilter")).strings() }.getOrDefault(emptyList())
        return filter(1 shl 4,1 shl 5,chapter.downloaded) && filter(1 shl 6,1 shl 7,!chapter.completed) && filter(1 shl 8,1 shl 9,chapter.locked)
            && (language.isEmpty() || chapter.language==language) && (selectedScanlators.isEmpty() || scanlators(chapter).any { it in selectedScanlators })
    }
    fun listed(manga:Manga)=sorted(manga).filter { includes(manga,it) }
    fun chronological(manga:Manga):List<Chapter> {
        val sorted=sorted(manga)
        return if(manga.sourceId=="local" && flags(manga) and 14 == 0) manga.chapters.sortedWith(compareBy<Chapter>{it.volume ?: 0.0}.thenBy{it.number}.thenBy{it.sourceOrder})
            else if(ascending(manga))sorted else sorted.asReversed()
    }
    fun next(manga:Manga):Chapter? {
        val eligible=listed(manga).filter { !it.completed && (!it.locked || it.downloaded) }
        return eligible.filter { it.readAt>0 }.maxByOrNull { it.readAt }
            ?: chronological(manga).firstOrNull { c -> eligible.any { it.key==c.key } }
    }
}
