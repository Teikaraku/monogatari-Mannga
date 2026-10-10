package app.monogatari.android.reader

import app.monogatari.android.data.Chapter

/** Original reader navigation, applied to the chronological Android list (forward = +1). */
object ChapterNavigator {
    fun areDuplicates(first:Chapter,second:Chapter):Boolean {
        val number=ChapterListing.number(first)
        return number==ChapterListing.number(second) && first.volume==second.volume &&
            (!(number==null && first.volume==null) || first.title==second.title)
    }
    private fun scanlators(chapter:Chapter)=ChapterListing.scanlators(chapter).filter(String::isNotBlank).toSet()
    private fun readable(chapter:Chapter)=!chapter.locked || chapter.downloaded
    private fun best(chapters:List<Chapter>,start:Int,step:Int,current:Chapter,included:(Chapter)->Boolean):Chapter {
        val first=chapters[start];val groups=scanlators(current)
        var index=start
        while(index in chapters.indices) {
            val candidate=chapters[index]
            if(!areDuplicates(candidate,first))break
            val nextGroups=scanlators(candidate)
            val matches=if(groups.isEmpty())nextGroups.isEmpty() else groups.any { it in nextGroups }
            if(included(candidate) && readable(candidate) && matches)return candidate
            index+=step
        }
        return first
    }
    fun target(chapters:List<Chapter>,current:Chapter,direction:Int,skipDuplicates:Boolean,markDuplicates:Boolean,
        included:(Chapter)->Boolean={true}):Chapter? {
        require(direction==1 || direction == -1)
        val currentIndex=chapters.indexOfFirst { it.key==current.key }
        if(currentIndex<0)return null
        var index=currentIndex+direction
        var firstReadable:Chapter?=null
        while(index in chapters.indices) {
            val candidate=chapters[index]
            if(included(candidate) && readable(candidate)) {
                val duplicate=areDuplicates(candidate,current)
                if(direction<0) {
                    // The original previous-chapter action always crosses the current duplicate group.
                    if(!duplicate)return best(chapters,index,direction,current,included)
                } else {
                    if(firstReadable==null)firstReadable=candidate
                    if(!duplicate)return if(skipDuplicates)best(chapters,index,direction,current,included) else firstReadable
                    if(!skipDuplicates && !markDuplicates)return candidate
                }
            }
            index+=direction
        }
        return null
    }
}
