package app.monogatari.android.library

import app.monogatari.android.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class LibraryRefreshPolicyTest {
    private fun manga(chapters:List<Chapter> = emptyList(),metadata:JSONObject=JSONObject())=Manga("m","source","Manga",chapters=chapters,inLibrary=true,categories=listOf("A","B"),metadata=metadata.toString())
    private fun eligible(manga:Manga,skip:Set<String> = emptySet(),excluded:Set<String> = emptySet(),category:String?=null)=LibraryRefreshPolicy.eligible(manga,setOf("source"),skip,excluded,category,nowSeconds=2000)
    @Test fun sourceStrategyAndSecondsDeadlineAlwaysApply() {
        assertFalse(eligible(manga(metadata=JSONObject().put("updateStrategy",1))))
        assertFalse(eligible(manga(metadata=JSONObject().put("nextUpdateTime",2001))))
        assertTrue(eligible(manga(metadata=JSONObject().put("nextUpdateTime",2000))))
        assertFalse(eligible(manga().copy(sourceId="missing")));assertFalse(eligible(manga().copy(inLibrary=false)))
    }
    @Test fun partialReadIsNotStartedUntilASelectedChapterCompletes() {
        val filter=JSONObject().put("langFilter","en").put("scanlatorFilter",JSONArray().put("Group B"))
        val partial=Chapter("partial","Partial",language="en",scanlator="Group A, Group B",readAt=1000,page=1)
        val hidden=Chapter("hidden","Hidden",language="ja",scanlator="Group B",completed=true)
        assertFalse(eligible(manga(listOf(partial,hidden),filter),setOf("notStarted")))
        assertTrue(eligible(manga(listOf(partial.copy(completed=true,locked=true),hidden),filter),setOf("notStarted")))
    }
    @Test fun unreadSkipExcludesLockedAndOtherLanguagesAndUsesIndividualGroups() {
        val filter=JSONObject().put("langFilter","en").put("scanlatorFilter",JSONArray().put("B"))
        val chapter=Chapter("selected","Selected",language="en",scanlator="A, B")
        assertFalse(eligible(manga(listOf(chapter),filter),setOf("hasUnread")))
        assertTrue(eligible(manga(listOf(chapter.copy(locked=true),chapter.copy(key="other",language="ja")),filter),setOf("hasUnread")))
    }
    @Test fun currentRealCategoryOverridesItsExclusionAndRestrictsItems() {
        assertFalse(eligible(manga(),excluded=setOf("A")))
        assertTrue(eligible(manga(),excluded=setOf("A"),category="A"))
        assertFalse(eligible(manga(),excluded=setOf("A","B"),category="A"))
        assertFalse(eligible(manga(),category="Other"))
    }
    @Test fun emptyCategorySelectsOnlyUncategorizedManga() {
        assertTrue(eligible(manga().copy(categories=emptyList()),category=""))
        assertFalse(eligible(manga(),category=""))
        assertTrue(eligible(manga(),category=null))
        assertTrue(eligible(manga().copy(categories=emptyList()),category=null))
    }
    @Test fun completedSkipRemainsIndependentOfManualCategorySelection() {
        assertFalse(eligible(manga().copy(status=2),setOf("completed"),category="A"))
        assertTrue(eligible(manga().copy(status=2),category="A"))
    }
    @Test fun sourceReturningNilClearsPreviousDeadline() {
        val current=JSONObject().put("updateStrategy",1).put("nextUpdateTime",9000).put("editedFields",JSONObject().put("title","Mine"))
        LibraryRefreshPolicy.applySourceSchedule(current,JSONObject().put("updateStrategy",0))
        assertEquals(0,current.getInt("updateStrategy"));assertFalse(current.has("nextUpdateTime"));assertEquals("Mine",current.getJSONObject("editedFields").getString("title"))
        LibraryRefreshPolicy.applySourceSchedule(current,JSONObject().put("updateStrategy",1).put("nextUpdateTime",3000));assertEquals(3000L,current.getLong("nextUpdateTime"))
    }
    @Test fun notificationsIncludeNewAndUnlockedChaptersInSelectedVersionOnly() {
        val meta=JSONObject().put("langFilter","en").put("scanlatorFilter",JSONArray().put("B"))
        val old=Chapter("old","Old",language="en",scanlator="A, B",locked=true)
        val before=manga(listOf(old),meta);val after=before.copy(chapters=listOf(old.copy(locked=false),old.copy(key="new",locked=false),old.copy(key="other",language="ja")))
        assertEquals(listOf("old","new"),LibraryRefreshPolicy.newNotifiableChapters(before,after).map { it.key })
        assertTrue(LibraryRefreshPolicy.newNotifiableChapters(before.copy(chapters=emptyList()),after).isEmpty())
    }
}
