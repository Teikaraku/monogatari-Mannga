package app.monogatari.android.reader

import app.monogatari.android.data.Chapter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ChapterNavigatorTest {
    private fun chapter(key:String,number:Double,group:String="",locked:Boolean=false,downloaded:Boolean=false)=
        Chapter(key,key,number,scanlator=group,locked=locked,downloaded=downloaded)
    @Test fun lockedFirstDuplicateCannotHideReadableVersionAndDownloadedLocksAreReadable() {
        val current=chapter("one-b",1.0,"B")
        val locked=chapter("two-a",2.0,"A",locked=true)
        val readable=chapter("two-b",2.0,"B")
        val later=chapter("three",3.0)
        assertEquals("two-b",ChapterNavigator.target(listOf(current,locked,readable,later),current,1,true,false)?.key)
        val downloaded=locked.copy(downloaded=true)
        assertEquals("two-a",ChapterNavigator.target(listOf(current,downloaded,readable,later),current.copy(scanlator="A"),1,true,false)?.key)
    }
    @Test fun candidateGroupPrefersAnySharedScanlatorThenFallsBackToFirstReadable() {
        val current=chapter("one",1.0).copy(metadata="{\"scanlators\":[\"B\",\"C\"]}")
        val first=chapter("two-a",2.0,"A")
        val matching=chapter("two-b",2.0).copy(metadata="{\"scanlators\":[\"D\",\"C\"]}")
        val noGroup=chapter("two-none",2.0)
        val list=listOf(current,first,matching,noGroup)
        assertEquals("two-b",ChapterNavigator.target(list,current,1,true,false)?.key)
        assertEquals("two-a",ChapterNavigator.target(list,current.copy(metadata="{}",scanlator="Z"),1,true,false)?.key)
        assertEquals("two-none",ChapterNavigator.target(list,current.copy(metadata="{}"),1,true,false)?.key)
    }
    @Test fun unnumberedDuplicatesUseTitleAndVolumeOnlyOrExplicitZeroRemainDistinct() {
        val special=Chapter("special-a","Special")
        assertTrue(ChapterNavigator.areDuplicates(special,special.copy(key="special-b")))
        assertFalse(ChapterNavigator.areDuplicates(special,special.copy(key="other",title="Afterword")))
        assertTrue(ChapterNavigator.areDuplicates(special.copy(volume=2.0),special.copy(key="other",title="Different title",volume=2.0)))
        assertFalse(ChapterNavigator.areDuplicates(special,special.copy(metadata="{\"chapterNumber\":0}")))
        val list=listOf(special,special.copy(key="duplicate"),special.copy(key="afterword",title="Afterword"))
        assertEquals("afterword",ChapterNavigator.target(list,special,1,true,false)?.key)
    }
    @Test fun nextHonorsOriginalSkipAndMarkSettingsWithoutDiscardingCandidateGroup() {
        val current=chapter("one-a",1.0,"A");val duplicate=chapter("one-b",1.0,"B");val next=chapter("two",2.0,"A")
        val list=listOf(current,duplicate,next)
        assertEquals("one-b",ChapterNavigator.target(list,current,1,false,false)?.key)
        assertEquals("two",ChapterNavigator.target(list,current,1,true,false)?.key)
        // Original getNextChapter retains the first readable duplicate if mark is on and skip is off.
        assertEquals("one-b",ChapterNavigator.target(list,current,1,false,true)?.key)
        assertNull(ChapterNavigator.target(list,next,1,true,true))
    }
    @Test fun previousCrossesCurrentDuplicatesAndRespectsReadabilityAndSelectedFilter() {
        val firstA=chapter("one-a",1.0,"A");val firstB=chapter("one-b",1.0,"B")
        val secondA=chapter("two-a",2.0,"A");val current=chapter("two-b",2.0,"B")
        val list=listOf(firstA,firstB,secondA,current)
        assertEquals("one-b",ChapterNavigator.target(list,current,-1,false,false)?.key)
        assertEquals("one-a",ChapterNavigator.target(list,current,-1,true,true){it.key!="one-b"}?.key)
        assertEquals("one-a",ChapterNavigator.target(listOf(firstA,firstB.copy(locked=true),secondA,current),current,-1,true,false)?.key)
        assertNull(ChapterNavigator.target(list,firstA,-1,true,false))
    }
}
