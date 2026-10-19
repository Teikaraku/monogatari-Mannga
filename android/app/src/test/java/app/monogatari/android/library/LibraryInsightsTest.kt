package app.monogatari.android.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.monogatari.android.data.*
import app.monogatari.android.ui.Localization
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryInsightsTest {
    @Before fun initializeWorkers() { runCatching { androidx.work.WorkManager.initialize(ApplicationProvider.getApplicationContext(), androidx.work.Configuration.Builder().build()) } }
    @Test fun disposedReaderCannotInvalidateAnActiveWindowsPendingProgress() = runBlocking {
        val repo = AppRepository(ApplicationProvider.getApplicationContext<Context>())
        val manga = Manga("lifecycle", "fixture", "Lifecycle", chapters = listOf(Chapter("c", "C", total = 10, page = 3, readAt = 1000)))
        repo.change { AppState(manga = listOf(manga)) }
        val store = androidx.lifecycle.ViewModelStore()
        val model = androidx.lifecycle.ViewModelProvider(store, object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = app.monogatari.android.MonogatariViewModel(repo) as T
        })[app.monogatari.android.MonogatariViewModel::class.java]
        val live = repo.reserveProgress(manga.id, "c")
        store.clear()
        model.progress(manga.id, "c", 2, 10)
        repo.progress(manga.id, "c", 7, 10, completed = false) { repo.currentProgress(manga.id, "c", live) }
        assertEquals(7, repo.state.value.manga.single().chapters.single().page)
        repo.finishProgress(manga.id, "c", live)
    }
    @Test fun tabNavigationRetainsStacksAndSurvivesSavedStateRestoration() {
        val repo = AppRepository(ApplicationProvider.getApplicationContext<Context>())
        val model = app.monogatari.android.MonogatariViewModel(repo)
        model.navigate("source", "fixture")
        model.selectTab(4); model.navigate("setting", "Reader"); model.navigate("downloads")
        model.selectTab(0)
        assertEquals("source", model.screen); assertEquals("fixture", model.key)
        val saved = android.os.Bundle(); model.saveNavigation(saved)
        val restored = app.monogatari.android.MonogatariViewModel(repo); restored.restoreNavigation(saved)
        restored.selectTab(4)
        assertEquals("downloads", restored.screen)
        restored.back(); assertEquals("setting", restored.screen); assertEquals("Reader", restored.key)
        restored.back(); assertEquals("main", restored.screen)
    }
    @Test fun delayedOldProgressCannotOverwriteNewLocationAndSplitLastPageCompletesExplicitly() = runBlocking {
        val repo = AppRepository(ApplicationProvider.getApplicationContext<Context>())
        val manga = Manga("progress", "fixture", "Progress", chapters = listOf(Chapter("chapter", "Chapter", total = 8)))
        repo.change { AppState(manga = listOf(manga)) }
        val previous = repo.reserveProgress(manga.id, "chapter")
        val latest = repo.reserveProgress(manga.id, "chapter")
        repo.progress(manga.id, "chapter", 7, 8, 0.9, false) { repo.currentProgress(manga.id, "chapter", latest) }
        repo.progress(manga.id, "chapter", 2, 8, 0.25, false) { repo.currentProgress(manga.id, "chapter", previous) }
        val partial = repo.state.value.manga.single().chapters.single()
        assertEquals(7, partial.page)
        assertEquals(0.9, JSONObject(partial.metadata).getDouble("scrollPosition"), 0.0)
        assertFalse(partial.completed)
        repo.progress(manga.id, "chapter", 7, 8, 1.0, true) { repo.currentProgress(manga.id, "chapter", latest) }
        assertTrue(repo.state.value.manga.single().chapters.single().completed)
        repo.finishProgress(manga.id, "chapter", latest)
        repo.progress(manga.id, "chapter", 2, 8, .25, false) { repo.currentProgress(manga.id, "chapter", previous) }
        assertEquals(7, repo.state.value.manga.single().chapters.single().page)
    }
    @Test fun repeatedMarkReadPreservesCompletedDateAndUnreadClearsScrollPosition() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>(); val repo = AppRepository(context)
        val manga = Manga("book", "fixture", "Book", chapters = listOf(
            Chapter("old", "Already read", total = 10, completed = true, readAt = 1000, metadata = JSONObject().put("scrollPosition", 0.8).toString()),
            Chapter("new", "Not read", total = 10, page = 2, readAt = 2000)))
        val row = JSONObject().put("sourceId", "fixture").put("mangaId", "book").put("chapterId", "old").put("completed", true).put("dateRead", 1.0).put("progress", 10)
        repo.change { AppState(manga = listOf(manga), records = JSONObject().put("history", JSONArray(listOf(row))).toString()) }
        val model = app.monogatari.android.MonogatariViewModel(repo)
        model.setChaptersRead(manga.id, setOf("old", "new"), true)
        assertEquals(1000L, repo.state.value.manga.single().chapters.first().readAt)
        assertEquals(1.0, JSONObject(repo.state.value.records).getJSONArray("history").getJSONObject(0).getDouble("dateRead"), 0.0)
        assertTrue(repo.state.value.manga.single().chapters.last().completed)
        model.setChaptersRead(manga.id, setOf("old", "new"), false)
        assertEquals(0L, repo.state.value.manga.single().lastRead)
        assertFalse(JSONObject(repo.state.value.manga.single().chapters.first().metadata).has("scrollPosition"))
    }
    @Test fun sessionsCountCalendarDaysAcrossDstAndUseCompoundIdentity() {
        val zone = ZoneId.of("America/Los_Angeles")
        fun session(source: String, chapter: String, day: String, pages: Int): JSONObject {
            val end = LocalDate.parse(day).atTime(12, 0).atZone(zone).toEpochSecond()
            return JSONObject().put("sourceId", source).put("mangaId", "same").put("chapterId", chapter).put("endDate", end).put("startDate", end - 3600).put("pagesRead", pages)
        }
        val state = AppState(manga = listOf(
            Manga("same", "one", "One", chapters = listOf(Chapter("c", "C", total = 3, completed = true))),
            Manga("same", "two", "Two", chapters = listOf(Chapter("c", "C", total = 5, completed = true)))),
            records = JSONObject().put("readingSessions", JSONArray(listOf(session("one", "c", "2026-03-07", 2), session("one", "c", "2026-03-08", 1), session("two", "c", "2026-03-08", 5)))).toString())
        val data = ReadingInsights.calculate(state, LocalDate.parse("2026-03-09").atStartOfDay(zone).toInstant(), zone)
        assertEquals(2, data.currentStreak); assertEquals(2, data.longestStreak)
        assertEquals(8L, data.pages.total); assertEquals(2L, data.series.total); assertEquals(3L, data.hours.total)
        assertEquals(2, data.heatmap[LocalDate.parse("2026-03-08")])
        assertEquals(2, data.chart[2026]!![2])
    }
    @Test fun chapterChartRequiresEnoughSessionPagesRatherThanMerelyCompletedFlag() {
        val end = Instant.parse("2026-10-08T12:00:00Z").epochSecond
        val sessions = JSONArray().put(JSONObject().put("sourceId", "s").put("mangaId", "m").put("chapterId", "c").put("startDate", end - 60).put("endDate", end).put("pagesRead", 9))
        val state = AppState(manga = listOf(Manga("m", "s", "M", chapters = listOf(Chapter("c", "C", total = 10, completed = true)))), records = JSONObject().put("readingSessions", sessions).toString())
        assertTrue(ReadingInsights.calculate(state, Instant.ofEpochSecond(end), ZoneOffset.UTC).chart.isEmpty())
    }
    @Test fun rulesOrWithinTypeAndAndAcrossTypesWithoutCrossSourceIdentityCollision() {
        val manga = Manga("same", "b", "B", inLibrary = true, chapters = listOf(Chapter("c", "C")), categories = listOf("Favorite"))
        assertTrue(LibraryRules.matches(manga, listOf(LibraryRule(5, "a"), LibraryRule(5, "b"), LibraryRule(7, "Favorite"))))
        assertFalse(LibraryRules.matches(manga, listOf(LibraryRule(5, "b"), LibraryRule(7, "Favorite", true))))
        assertFalse(LibraryRules.matches(manga, listOf(LibraryRule(1)), setOf("a::same")))
    }
    @Test fun originalCategoryNullEmptyAndLiteralNamesRemainDistinct() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>(); val repo = AppRepository(context)
        val books = listOf(Manga("free", "s", "Free", inLibrary = true), Manga("named", "s", "Named", inLibrary = true, categories = listOf("__uncategorized")))
        repo.change { AppState(manga = books, categories = listOf("__uncategorized")) }
        assertNull(repo.settings.libraryCategory())
        assertEquals(2, LibraryRules.select(repo.state.value, repo.settings, null, "").size)
        repo.settings.put("Library.currentCategory", "")
        assertEquals("", repo.settings.libraryCategory())
        assertEquals(listOf("free"), LibraryRules.select(repo.state.value, repo.settings, "", "").map { it.key })
        assertEquals(listOf("named"), LibraryRules.select(repo.state.value, repo.settings, "__uncategorized", "").map { it.key })
    }
    @Test fun ascendingUnreadPlacesZeroAtEndAndPinnedUpdatedUsesLastOpened() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>(); val repo = AppRepository(context)
        val list = listOf(0, 2, 1).map { count -> Manga(count.toString(), "s", count.toString(), inLibrary = true, chapters = List(count) { Chapter(it.toString(), "C") }) }
        repo.change { AppState(manga = list, settings = JSONObject().put("Library.sortOption", 6).put("Library.sortAscending", true).toString()) }
        assertEquals(listOf("1", "2", "0"), LibraryRules.select(repo.state.value, repo.settings, "", "", emptySet()).map { it.key })
    }
    @Test fun originalObjCFormatAndSettingsRemainEffectiveAfterImport() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>(); val repo = AppRepository(context)
        assertFalse(Localization.string(context, "PAGE_X_OF_X", 3, 20).contains("%i"))
        repo.change { AppState() }; repo.settings.import(JSONObject().put("General.useSystemAppearance", false).put("General.appearance", 1).put("General.incognitoMode", true))
        assertEquals("dark", repo.state.value.preferences.theme); assertTrue(repo.state.value.preferences.incognito)
    }
}
