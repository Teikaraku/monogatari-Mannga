package app.monogatari.android.library

import app.monogatari.android.ui.MangaSelectionRange
import org.junit.Assert.*
import org.junit.Test

class MangaSelectionRangeTest {
    private val keys = (0..7).map { "book-$it" }
    @Test fun selectingAndReversingRestoresThePreviousSelection() {
        var selected = setOf("book-0", "book-4", "book-7")
        val gesture = MangaSelectionRange(keys, 2, selected, true)
        fun move(end: Int) { gesture.update(end).forEach { (key, value) -> selected = if (value) selected + key else selected - key } }
        move(5); assertEquals(setOf("book-0", "book-2", "book-3", "book-4", "book-5", "book-7"), selected)
        move(2); assertEquals(setOf("book-0", "book-2", "book-4", "book-7"), selected)
    }
    @Test fun deselectingAndReversingDoesNotInventSelections() {
        var selected = setOf("book-1", "book-2", "book-4", "book-6")
        val gesture = MangaSelectionRange(keys, 4, selected, false)
        fun move(end: Int) { gesture.update(end).forEach { (key, value) -> selected = if (value) selected + key else selected - key } }
        move(1); assertEquals(setOf("book-6"), selected)
        move(3); assertEquals(setOf("book-1", "book-2", "book-6"), selected)
    }
    @Test fun sparseVisibleEndpointsStillSelectTheWholeRangeAndClampBoundaries() {
        val gesture = MangaSelectionRange(keys, 1, emptySet(), true)
        assertEquals(keys.drop(1).toSet(), gesture.update(100).filterValues { it }.keys)
        val reversed = gesture.update(-10)
        assertEquals(setOf("book-0", "book-1"), reversed.filterValues { it }.keys)
        assertEquals(keys.drop(2).toSet(), reversed.filterValues { !it }.keys)
    }
}
