package app.monogatari.android.library

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.monogatari.android.MonogatariViewModel
import app.monogatari.android.MainActivity
import app.monogatari.android.data.*
import app.monogatari.android.reader.ReaderFixtureContext
import app.monogatari.android.ui.MonogatariApp
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LibrarySelectionInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val gridTag = "app.monogatari.android:id/manga_grid"
    private val listTag = "app.monogatari.android:id/manga_list"
    private fun tag(book: Manga) = "app.monogatari.android:id/manga_${AppRepository.hash(book.id)}"
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?, depth: Int = 0) {
            if (node == null || depth > 30 || result.size > 600) return
            // UiAutomation can return cached text after Compose has already
            // rendered new labels. Query the actual current node before using it.
            val current = try { node.refresh() } catch (_: IllegalStateException) { false }
            if (!current) return
            result.add(node)
            for (index in 0 until node.childCount) visit(node.getChild(index), depth + 1)
        }
        visit(automation.rootInActiveWindow)
        return result
    }
    private fun id(value: String) = nodes().firstOrNull { it.isVisibleToUser && it.viewIdResourceName == value }
    private fun text(value: String) = nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString() == value || it.contentDescription?.toString() == value) }
    private fun hasLabel(node: AccessibilityNodeInfo, value: String, depth: Int = 0): Boolean {
        val current = try { node.refresh() } catch (_: IllegalStateException) { false }
        if (!current) return false
        if (node.text?.toString() == value || node.contentDescription?.toString() == value) return true
        return depth < 8 && (0 until node.childCount).any { index -> node.getChild(index)?.let { hasLabel(it, value, depth + 1) } == true }
    }
    // Disabled menu items still have enabled fake Text descendants. Assert and
    // touch the real clickable control's state, not its decorative label node.
    private fun labeledControl(value: String) = nodes().filter { it.isVisibleToUser && it.isClickable && hasLabel(it, value) }
        .minByOrNull { bounds(it).let { rect -> rect.width().toLong() * rect.height() } }
    private fun bounds(node: AccessibilityNodeInfo): Rect = Rect().also(node::getBoundsInScreen)
    private fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 12_000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            Thread.sleep(60)
        }
        assertTrue(message, condition())
    }
    private fun stableBounds(value: String, message: String, expected: Rect? = null): Rect {
        var previous: Rect? = null; var unchangedSince = 0L
        waitFor(message) {
            val current = id(value)?.let(::bounds)
            if (current == null || expected != null && current != expected) { previous = null; unchangedSince = 0L; false }
            else {
                if (current != previous) { previous = current; unchangedSince = SystemClock.uptimeMillis() }
                SystemClock.uptimeMillis() - unchangedSince >= 200
            }
        }
        return checkNotNull(previous)
    }
    private fun inject(down: Long, action: Int, points: List<Pair<Float, Float>>) {
        val properties = points.indices.map { index -> MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray()
        val coordinates = points.map { point -> MotionEvent.PointerCoords().apply { x = point.first; y = point.second; pressure = 1f; size = 1f } }.toTypedArray()
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, points.size, properties, coordinates,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { assertTrue("Native touchscreen event rejected: $action", automation.injectInputEvent(event, true)) } finally { event.recycle() }
    }
    private fun tap(rect: Rect, hold: Long = 30) {
        val down = SystemClock.uptimeMillis(); val point = rect.exactCenterX() to rect.exactCenterY()
        inject(down, MotionEvent.ACTION_DOWN, listOf(point)); Thread.sleep(hold)
        inject(down, MotionEvent.ACTION_UP, listOf(point)); Thread.sleep(80)
    }
    private fun click(value: String) {
        waitFor("Missing actual UI control: $value") { text(value) != null }
        tap(bounds(text(value)!!))
    }
    private fun back() {
        val now = SystemClock.uptimeMillis()
        assertTrue(automation.injectInputEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0, 0, 0, 0, 0, InputDevice.SOURCE_KEYBOARD), true))
        assertTrue(automation.injectInputEvent(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0, 0, 0, 0, 0, InputDevice.SOURCE_KEYBOARD), true))
    }
    private fun twoFingerDrag(from: Pair<Float, Float>, to: Pair<Float, Float>, holdAtEnd: Long = 0) {
        val down = SystemClock.uptimeMillis(); val spread = 10f
        fun pair(point: Pair<Float, Float>) = listOf(point.first - spread to point.second, point.first + spread to point.second)
        inject(down, MotionEvent.ACTION_DOWN, pair(from).take(1)); Thread.sleep(20)
        inject(down, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), pair(from))
        for (index in 1..16) {
            Thread.sleep(20)
            val fraction = index / 16f
            inject(down, MotionEvent.ACTION_MOVE, pair((from.first + (to.first - from.first) * fraction) to (from.second + (to.second - from.second) * fraction)))
        }
        if (holdAtEnd > 0) Thread.sleep(holdAtEnd)
        inject(down, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), pair(to))
        inject(down, MotionEvent.ACTION_UP, pair(to).take(1)); Thread.sleep(120)
    }
    private fun singleFingerSwipe(from: Pair<Float, Float>, to: Pair<Float, Float>) {
        val down = SystemClock.uptimeMillis(); inject(down, MotionEvent.ACTION_DOWN, listOf(from))
        for (index in 1..12) {
            Thread.sleep(25); val fraction = index / 12f
            inject(down, MotionEvent.ACTION_MOVE, listOf((from.first + (to.first - from.first) * fraction) to (from.second + (to.second - from.second) * fraction)))
        }
        inject(down, MotionEvent.ACTION_UP, listOf(to)); Thread.sleep(350)
    }
    private fun selectionCount(): Int? = nodes().firstNotNullOfOrNull {
        Regex("Selected \\((\\d+)/(\\d+)\\)").find(it.text?.toString().orEmpty())?.groupValues?.get(1)?.toInt()
    }
    private fun record(context: Context, name: String, data: JSONObject) {
        val directory = File(context.getExternalFilesDir(null), "verification").apply { mkdirs() }
        automation.takeScreenshot()?.let { image ->
            File(directory, "$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
        val cells = JSONArray()
        nodes().filter { it.viewIdResourceName?.startsWith("app.monogatari.android:id/manga_") == true }.forEach { node ->
            cells.put(JSONObject().put("id", node.viewIdResourceName).put("bounds", bounds(node).flattenToString())
                .put("checkable", node.isCheckable).put("checked", node.isChecked).put("visible", node.isVisibleToUser))
        }
        val controls = JSONArray(nodes().filter { it.isVisibleToUser }.map { node -> JSONObject()
            .put("text", node.text?.toString()).put("description", node.contentDescription?.toString()).put("enabled", node.isEnabled)
            .put("class", node.className?.toString()).put("editable", node.isEditable).put("focused", node.isFocused) })
        File(directory, "$name.json").writeText(data.put("cells", cells).put("controls", controls).toString(2))
    }
    private fun fixture(locked: Boolean, body: suspend (AppRepository, MonogatariViewModel, ReaderFixtureContext, List<Manga>) -> Unit) = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>(); val context = ReaderFixtureContext(base); val repo = AppRepository.get(context)
        val books = (1..24).map { index -> Manga("select-$index", "local", "Selection book %02d".format(index),
            chapters = listOf(Chapter("1", "Fixture chapter", 1.0, total = 2)), inLibrary = true, categories = listOf("Open", "Locked")) }
        val settings = JSONObject().put("General.useSystemAppearance", false).put("General.appearance", 0)
            .put("Library.currentCategory", "Open").put("Library.listView", false).put("Appearance.layout", "custom")
            .put("Appearance.customPortraitRows", 2).put("Appearance.customLandscapeRows", 2)
            // The original alphabetical persistent flag is inverted: false is A-Z.
            .put("Library.sortOption", 0).put("Library.sortAscending", false).put("Library.pinTitles", "manual").put("Library.filters", JSONArray())
            .put("Library.backgroundRefresh", false).put("Library.updateInterval", "never").put("Library.opensReaderView", false)
            .put("Library.lockLibrary", locked).put("Library.lockedCategories", JSONArray(listOf("Locked")))
        repo.change { AppState(manga = books, categories = listOf("Open", "Locked"), settings = settings.toString(), preferences = repo.settings.effectivePreferences(settings)) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm: MonogatariViewModel
                var composeFocus: FocusManager? = null
                var titleTouchBounds: Rect? = null
                var restoreDecorFocus: (() -> Unit)? = null
                var focusTransfer: JSONObject? = null
                fun inputSnapshot(): JSONObject {
                    val snapshot = JSONObject()
                    scenario.onActivity { activity ->
                        val decor = activity.window.decorView
                        val insets = ViewCompat.getRootWindowInsets(decor)
                        snapshot.put("windowFocused", decor.hasWindowFocus()).put("imeInsetsAvailable", insets != null)
                            .put("touchMode", decor.isInTouchMode)
                            .put("nonEditableDecorFocused", activity.currentFocus === decor)
                            .put("imeVisible", insets?.isVisible(WindowInsetsCompat.Type.ime()) ?: JSONObject.NULL)
                            .put("nativeFocusedView", activity.currentFocus?.javaClass?.name)
                    }
                    snapshot.put("nonInteractiveTitleTouch", titleTouchBounds?.flattenToString())
                        .put("api26FocusTransfer", focusTransfer)
                    val fields = nodes().filter { it.isVisibleToUser && it.isEditable }
                    snapshot.put("searchFields", JSONArray(fields.map { JSONObject().put("text", it.text?.toString().orEmpty())
                        .put("focused", it.isFocused).put("bounds", bounds(it).flattenToString()) }))
                    return snapshot
                }
                scenario.onActivity { activity ->
                    vm = context.model(repo)
                    val localized = context.ui(activity, "en")
                    activity.setContent { CompositionLocalProvider(LocalContext provides localized, LocalLayoutDirection provides LayoutDirection.Ltr,
                        LocalActivityResultRegistryOwner provides activity) {
                        val focus = LocalFocusManager.current
                        SideEffect { composeFocus = focus }
                        MonogatariApp(vm)
                    } }
                }
                try {
                    waitFor("Isolated actual library did not render") { id(gridTag) != null && id(tag(books[0])) != null }
                    waitFor("The actual library focus manager was not ready") { composeFocus != null }
                    // In non-touch mode API26 can refocus the first editor when
                    // native focus is cleared. Enter actual touchscreen mode via
                    // the non-interactive page title above the collection first.
                    waitFor("The actual non-interactive Library title must be available above the collection") {
                        val viewport = id(gridTag)?.let(::bounds)
                        titleTouchBounds = nodes().firstOrNull { node ->
                            val rect = bounds(node)
                            node.isVisibleToUser && !node.isClickable && !node.isEditable && node.text?.toString() == "Library" &&
                                viewport != null && !rect.isEmpty && rect.top < viewport.top && rect.bottom <= viewport.top
                        }?.let(::bounds)
                        titleTouchBounds != null
                    }
                    tap(checkNotNull(titleTouchBounds))
                    // A previous device scenario may leave the real IME open.
                    // Clear both Compose and native focus before touchscreen
                    // gestures, rather than sending swipes into keyboard keys.
                    scenario.onActivity { activity ->
                        if (Build.VERSION.SDK_INT == 26) {
                            val decor = activity.window.decorView as ViewGroup
                            val originalFocusable = decor.isFocusable
                            val originalTouchFocusable = decor.isFocusableInTouchMode
                            val originalDescendantFocusability = decor.descendantFocusability
                            restoreDecorFocus = {
                                decor.descendantFocusability = originalDescendantFocusability
                                decor.isFocusableInTouchMode = originalTouchFocusable
                                decor.isFocusable = originalFocusable
                            }
                            // FocusManager.clearFocus itself clears its native
                            // owner. On API26, clearing that same owner again can
                            // refocus its first editor. Transfer native focus to a
                            // real non-editable view instead of repeating clear.
                            val before = activity.currentFocus?.javaClass?.name
                            decor.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
                            decor.isFocusableInTouchMode = true
                            val accepted = decor.requestFocus()
                            focusTransfer = JSONObject().put("before", before).put("requestAccepted", accepted)
                                .put("after", activity.currentFocus?.javaClass?.name).put("actualDecorFocused", activity.currentFocus === decor)
                            assertTrue("The actual non-editable decor must accept native focus", accepted)
                            assertSame("Native focus must actually transfer away from the editor owner", decor, activity.currentFocus)
                        }
                        composeFocus!!.clearFocus(force = true)
                        if (Build.VERSION.SDK_INT != 26) activity.currentFocus?.clearFocus()
                        val input = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        input.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
                    }
                    var previousViewport: Rect? = null
                    var previousFirst: Rect? = null
                    var unchangedSince = 0L
                    waitFor("The real IME must be hidden, search empty and collection geometry stable before native gestures") {
                        val input = inputSnapshot()
                        val fields = input.getJSONArray("searchFields")
                        val viewport = id(gridTag)?.let(::bounds)
                        val first = id(tag(books[0]))?.let(::bounds)
                        val ready = input.optBoolean("windowFocused") && input.optBoolean("touchMode") && input.optBoolean("imeInsetsAvailable") && !input.optBoolean("imeVisible", true) &&
                            (Build.VERSION.SDK_INT != 26 || input.optBoolean("nonEditableDecorFocused")) &&
                            fields.length() == 1 && fields.getJSONObject(0).optString("text").isEmpty() && !fields.getJSONObject(0).optBoolean("focused") &&
                            viewport != null && !viewport.isEmpty && first != null && !first.isEmpty
                        if (!ready) {
                            previousViewport = null; previousFirst = null; unchangedSince = 0L; false
                        } else {
                            if (viewport != previousViewport || first != previousFirst) {
                                previousViewport = viewport; previousFirst = first; unchangedSince = SystemClock.uptimeMillis()
                            }
                            SystemClock.uptimeMillis() - unchangedSince >= 300
                        }
                    }
                    record(context, "library-selection-input-ready", inputSnapshot().put("viewport", previousViewport?.flattenToString())
                        .put("firstCell", previousFirst?.flattenToString()))
                    body(repo, vm, context, books)
                } catch (error: Throwable) {
                    record(context, "library-selection-failure", JSONObject().put("error", error.toString()).put("count", selectionCount())
                        .put("input", inputSnapshot())
                        .put("settings", JSONObject(repo.state.value.settings)).put("gridFound", id(gridTag) != null)
                        .put("firstFound", id(tag(books[0])) != null).put("firstMangaId", books[0].id)
                        .put("initialMangaOrder", JSONArray(app.monogatari.android.library.LibraryRules.select(repo.state.value, repo.settings, repo.settings.libraryCategory(), "").take(6).map { it.id })))
                    throw error
                } finally {
                    scenario.onActivity { restoreDecorFocus?.invoke() }
                }
            }
        } finally { Thread.sleep(400); context.clear() }
    }

    @Test fun nativeTwoFingerSelectionKeepsGridAndListAndEditsOnlySelectedBooks() = fixture(false) { repo, vm, context, books ->
        val firstBefore = stableBounds(tag(books[0]), "First actual grid cell never settled")
        val viewport = stableBounds(gridTag, "Actual grid viewport never settled")
        singleFingerSwipe(viewport.exactCenterX() to (viewport.bottom - 60f), viewport.exactCenterX() to (viewport.top + 60f))
        waitFor("Ordinary scrolling must still move the real collection") {
            val first = id(tag(books[0]))?.let(::bounds)
            val collection = id(gridTag)?.let(::bounds)
            collection == viewport && (first == null || first.top < firstBefore.top - 2 || first.height() < firstBefore.height() - 2)
        }
        assertNull("Ordinary one-finger scrolling must not enter selection", selectionCount())
        assertEquals("main", vm.screen)
        record(context, "library-selection-single-scroll", JSONObject().put("before", firstBefore.flattenToString())
            .put("after", id(tag(books[0]))?.let { bounds(it).flattenToString() }).put("viewportBefore", viewport.flattenToString())
            .put("viewportAfter", id(gridTag)?.let { bounds(it).flattenToString() }).put("enteredSelection", false))
        // Stop any continuing native fling in the collection's empty left padding.
        tap(Rect(viewport.left + 4, viewport.centerY() - 4, viewport.left + 12, viewport.centerY() + 4))
        repeat(12) {
            val first = id(tag(books[0]))?.let(::bounds)
            if (first != firstBefore) {
                val collection = id(gridTag)
                assertNotNull("The actual collection must remain available while returning to its first cell", collection)
                // The scroll is asynchronous: it can reach the boundary between
                // the position query and action. Validate the stable result below.
                collection!!.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD); Thread.sleep(250)
            }
        }
        val restoredFirst = stableBounds(tag(books[0]), "Native backward scrolling must restore the original first-cell position before long press", firstBefore)
        stableBounds(gridTag, "Restoring the first cell must preserve the viewport geometry", viewport)
        tap(restoredFirst, 650)
        waitFor("Ordinary one-finger long press must still open the manga context menu") { text("Manga Info") != null }
        assertNull("Long press must not be converted to a two-finger gesture", selectionCount())
        back()
        waitFor("Context menu did not dismiss") { text("Manga Info") == null && id(gridTag) != null }
        val firstGrid = bounds(id(tag(books[0]))!!); val secondGrid = bounds(id(tag(books[1]))!!)
        assertTrue("Fixture must use the original two-column grid", secondGrid.left > firstGrid.right && kotlin.math.abs(secondGrid.top - firstGrid.top) <= 2)
        twoFingerDrag(firstGrid.exactCenterX() to firstGrid.exactCenterY(), secondGrid.exactCenterX() to secondGrid.exactCenterY())
        waitFor("Genuine two-pointer MotionEvents must select the grid range") { selectionCount() == 2 }
        assertNotNull("Editing must preserve the grid collection", id(gridTag)); assertNull(id(listTag))
        val editingGrid = bounds(id(tag(books[0]))!!)
        assertEquals(firstGrid.width(), editingGrid.width()); assertEquals(firstGrid.height(), editingGrid.height())
        // Compose exposes a selected checkbox through native isChecked; native
        // isSelected is reserved for Role.Tab by its accessibility delegate.
        assertTrue(id(tag(books[0]))!!.isCheckable); assertTrue(id(tag(books[1]))!!.isChecked)
        assertTrue("Selected grid cells must expose their actual checkbox state", id(tag(books[0]))!!.isChecked)
        click("Bookstore"); waitFor("Native tab switch did not reach Bookstore") { vm.tab == 1 && id(gridTag) == null }
        click("Library"); waitFor("Grid edit mode and selection must survive a native tab round trip") { vm.tab == 0 && id(gridTag) != null && selectionCount() == 2 }
        assertNull(id(listTag)); assertEquals(firstGrid.width(), bounds(id(tag(books[0]))!!).width())
        assertTrue(id(tag(books[0]))!!.isChecked); assertTrue(id(tag(books[1]))!!.isChecked)
        tap(bounds(id(tag(books[1]))!!)); waitFor("Tap in edit mode must deselect without opening details") { selectionCount() == 1 }
        assertEquals("main", vm.screen)
        tap(bounds(id(tag(books[1]))!!)); waitFor("Tap in edit mode must reselect") { selectionCount() == 2 }
        record(context, "library-selection-grid", JSONObject().put("count", selectionCount()).put("pointerCount", 2).put("tabRoundTripVerified", true)
            .put("originalWidth", firstGrid.width()).put("originalHeight", firstGrid.height()))
        click("Mark as Read"); click("Continue")
        val expectedRead = books.take(2).map { it.id }.toSet()
        waitFor("Actual batch action must complete only the selected manga") {
            repo.state.value.manga.filter { it.chapters.any(Chapter::completed) }.map { it.id }.toSet() == expectedRead && selectionCount() == null
        }
        assertTrue("Other manga must keep their original unread progress", repo.state.value.manga.filter { it.id !in expectedRead }.all { it.chapters.none(Chapter::completed) })
        click("More"); click("List")
        waitFor("Original List menu must change the real collection") { id(listTag) != null && id(tag(books[0])) != null && id(tag(books[1])) != null }
        val firstList = bounds(id(tag(books[0]))!!); val secondList = bounds(id(tag(books[1]))!!)
        assertEquals(firstList.left, secondList.left); assertTrue(secondList.top >= firstList.bottom)
        twoFingerDrag(firstList.exactCenterX() to firstList.exactCenterY(), secondList.exactCenterX() to secondList.exactCenterY())
        waitFor("Genuine two-pointer MotionEvents must also select list rows") { selectionCount() == 2 }
        assertNotNull("Editing must preserve list rows", id(listTag)); assertNull(id(gridTag))
        assertEquals(firstList.width(), bounds(id(tag(books[0]))!!).width()); assertEquals(firstList.height(), bounds(id(tag(books[0]))!!).height())
        assertTrue(id(tag(books[0]))!!.isCheckable); assertTrue(id(tag(books[1]))!!.isChecked)
        click("Bookstore"); waitFor("Native list tab switch did not reach Bookstore") { vm.tab == 1 && id(listTag) == null }
        click("Library"); waitFor("List edit mode and selection must survive a native tab round trip") { vm.tab == 0 && id(listTag) != null && selectionCount() == 2 }
        assertNull(id(gridTag)); assertEquals(firstList.height(), bounds(id(tag(books[0]))!!).height())
        assertTrue(id(tag(books[0]))!!.isChecked); assertTrue(id(tag(books[1]))!!.isChecked)
        record(context, "library-selection-list", JSONObject().put("count", selectionCount()).put("tabRoundTripVerified", true).put("readManga", JSONArray(expectedRead.toList()))
            .put("originalWidth", firstList.width()).put("originalHeight", firstList.height()))
        click("Select All"); waitFor("Select All must include offscreen items in the current category") { selectionCount() == books.size }
        click("Deselect All"); waitFor("Deselect All must retain editing and clear all items") { selectionCount() == 0 }
        back(); waitFor("Back must exit selection without changing the layout") { selectionCount() == null && id(listTag) != null }
        val listViewport = bounds(id(listTag)!!)
        val initiallyVisibleEnd = books.indices.last { id(tag(books[it])) != null }
        val start = bounds(id(tag(books[0]))!!)
        // Keep both real fingers at the lower boundary while the collection scrolls.
        // Range updates must include rows that were not visible at gesture start.
        twoFingerDrag(start.exactCenterX() to start.exactCenterY(), listViewport.exactCenterX() to (listViewport.bottom - 8f), 1_600)
        waitFor("Two-finger boundary dragging must scroll and select previously offscreen rows") { (selectionCount() ?: 0) > initiallyVisibleEnd + 1 }
        assertNotNull(id(listTag)); assertNull(id(gridTag)); assertEquals("main", vm.screen)
        record(context, "library-selection-autoscroll", JSONObject().put("count", selectionCount()).put("initiallyVisible", initiallyVisibleEnd + 1)
            .put("readManga", JSONArray(repo.state.value.manga.filter { it.chapters.any(Chapter::completed) }.map { it.id })))
        back(); waitFor("Ending edit mode must clear its selection") { selectionCount() == null }
    }

    @Test fun lockedCategoryCancelsSelectionAndCannotStartOrApplyBatchEdits() = fixture(true) { repo, _, _, books ->
        val first = bounds(id(tag(books[0]))!!); val second = bounds(id(tag(books[1]))!!)
        twoFingerDrag(first.exactCenterX() to first.exactCenterY(), second.exactCenterX() to second.exactCenterY())
        waitFor("Two-finger selection did not begin in the accessible category") { selectionCount() == 2 }
        click("Locked")
        waitFor("Locked category must cancel edit mode and hide the collection") { selectionCount() == null && id(gridTag) == null && text("Category is locked.") != null }
        twoFingerDrag(first.exactCenterX() to first.exactCenterY(), second.exactCenterX() to second.exactCenterY())
        assertNull(selectionCount()); assertTrue(repo.state.value.manga.all { it.chapters.none(Chapter::completed) })
        click("More"); waitFor("Locked category Select control missing") { labeledControl("Select") != null }
        assertFalse("Locked library must disable the actual Select menu action", labeledControl("Select")!!.isEnabled)
        tap(bounds(labeledControl("Select")!!)); assertNull(selectionCount())
        assertTrue(repo.state.value.manga.all { it.chapters.none(Chapter::completed) })
        back(); click("Open")
        waitFor("Unlock-free category must restore browsing without stale selected items") { id(gridTag) != null && selectionCount() == null }
        click("All")
        waitFor("Whole-library lock must use its original distinct message and block editing") { id(gridTag) == null && selectionCount() == null && text("Library is locked.") != null }
        assertTrue(repo.state.value.manga.all { it.chapters.none(Chapter::completed) })
    }
}
