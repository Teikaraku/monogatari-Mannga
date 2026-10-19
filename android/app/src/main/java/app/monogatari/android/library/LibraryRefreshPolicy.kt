package app.monogatari.android.library

import app.monogatari.android.data.Chapter
import app.monogatari.android.data.ChapterMetrics
import app.monogatari.android.data.Manga
import org.json.JSONObject

/** Original refresh eligibility is independent of foreground/manual scheduling. */
object LibraryRefreshPolicy {
    fun eligible(manga:Manga,availableSources:Set<String>,skipTitles:Set<String>,excludedCategories:Set<String>,category:String?=null,nowSeconds:Long=System.currentTimeMillis()/1000):Boolean {
        val inCategory=category==null || if(category.isEmpty())manga.categories.isEmpty() else category in manga.categories
        if(!manga.inLibrary || manga.sourceId=="local" || manga.sourceId !in availableSources || !inCategory)return false
        val metadata=JSONObject(manga.metadata)
        if(metadata.optBoolean("neverUpdate") || metadata.optInt("updateStrategy")==1)return false
        if(!metadata.isNull("nextUpdateTime") && metadata.optLong("nextUpdateTime")>nowSeconds)return false
        if("completed" in skipTitles && manga.status==2)return false
        if("hasUnread" in skipTitles && ChapterMetrics.unreadCount(manga)>0)return false
        if("notStarted" in skipTitles && ChapterMetrics.readCount(manga)==0)return false
        return manga.categories.none { it in excludedCategories && it!=category }
    }
    fun versionMatches(manga:Manga,chapter:Chapter):Boolean = ChapterMetrics.selected(manga.copy(chapters=listOf(chapter)),includeLocked=true).isNotEmpty()
    fun newNotifiableChapters(before:Manga,after:Manga):List<Chapter> {
        if(before.chapters.isEmpty())return emptyList()
        val old=before.chapters.associateBy { it.key }
        return after.chapters.filter { chapter -> (old[chapter.key]==null || old[chapter.key]?.locked==true && !chapter.locked) && versionMatches(before,chapter) }
    }
    /** A source returning nil clears its old deadline instead of retaining a stale value. */
    fun applySourceSchedule(existing:JSONObject,returned:JSONObject) {
        existing.put("updateStrategy",returned.optInt("updateStrategy",0))
        existing.remove("nextUpdateTime")
        if(returned.has("nextUpdateTime") && !returned.isNull("nextUpdateTime"))existing.put("nextUpdateTime",returned.get("nextUpdateTime"))
    }
}
