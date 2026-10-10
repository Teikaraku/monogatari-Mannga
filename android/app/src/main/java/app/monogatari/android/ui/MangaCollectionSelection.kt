package app.monogatari.android.ui

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.unit.dp
import app.monogatari.android.data.Manga
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class MangaCollectionSelection(
    val active: Boolean,
    val selected: Set<String>,
    val enabled: Boolean,
    val scopeKey: String,
    val begin: () -> Unit,
    val set: (Manga, Boolean) -> Unit
)

/** Reverse a drag without losing selections that existed before this gesture. */
internal class MangaSelectionRange(
    private val keys: List<String>, private val anchor: Int,
    private val baseline: Set<String>, private val selecting: Boolean
) {
    private var previous = emptySet<String>()
    fun update(end: Int): Map<String, Boolean> {
        if (keys.isEmpty()) return emptyMap()
        val start = anchor.coerceIn(keys.indices)
        val finish = end.coerceIn(keys.indices)
        val range = keys.subList(minOf(start, finish), maxOf(start, finish) + 1).toSet()
        val changes = linkedMapOf<String, Boolean>()
        (previous - range).forEach { changes[it] = it in baseline }
        range.forEach { changes[it] = selecting }
        previous = range
        return changes
    }
}

internal suspend fun PointerInputScope.twoFingerMangaSelection(
    manga: List<Manga>, controller: () -> MangaCollectionSelection?,
    bounds: () -> Map<String, Rect>, scrollable: ScrollableState
) = coroutineScope {
    val books = manga.associateBy { it.id }
    val indexes = manga.mapIndexed { index, book -> book.id to index }.toMap()
    val keys = manga.map { it.id }
    val edge = 48.dp.toPx()
    val step = 18.dp.toPx()
    fun visibleBounds(): List<Map.Entry<String, Rect>> {
        val viewport = Rect(0f, 0f, size.width.toFloat(), size.height.toFloat())
        return bounds().entries.filter { it.key in indexes && it.value.overlaps(viewport) }
    }
    fun exact(point: Offset) = visibleBounds().firstOrNull { it.value.contains(point) }?.key
    fun nearest(point: Offset): String? {
        if (point.x < 0 || point.x >= size.width || size.height == 0) return null
        val p = Offset(point.x, point.y.coerceIn(0f, size.height.toFloat() - 0.01f))
        fun distance(rect: Rect): Float {
            val x = (p.x - p.x.coerceIn(rect.left, rect.right)).let { it * it }
            val y = (p.y - p.y.coerceIn(rect.top, rect.bottom)).let { it * it }
            return x + y
        }
        return visibleBounds().minByOrNull { distance(it.value) }?.key
    }
    awaitEachGesture {
        val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val firstPosition = first.position
        var singleFingerCommitted = false
        var start: Offset? = null
        var anchor: String? = null
        var active = false
        var count = 1
        var position: Offset? = null
        var range: MangaSelectionRange? = null
        fun applyAt(point: Offset) {
            if (controller()?.enabled != true) return
            val key = nearest(point) ?: return
            range?.update(indexes.getValue(key))?.forEach { (id, selected) ->
                if (controller()?.enabled == true) books[id]?.let { controller()?.set?.invoke(it, selected) }
            }
        }
        val autoScroll = launch {
            while (isActive) {
                delay(16)
                val point = position ?: continue
                if (!active || count != 2 || controller()?.enabled != true || point.x !in 0f..size.width.toFloat()) continue
                val delta = when {
                    point.y < edge -> -step * ((edge - point.y) / edge).coerceIn(0f, 1f)
                    point.y > size.height - edge -> step * ((point.y - (size.height - edge)) / edge).coerceIn(0f, 1f)
                    else -> 0f
                }
                if (delta != 0f) {
                    scrollable.scroll(MutatePriority.UserInput) { scrollBy(delta) }
                    applyAt(point)
                }
            }
        }
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.filter { it.pressed }
                count = pressed.size
                if (controller()?.enabled != true) {
                    if (active) event.changes.forEach { it.consume() }
                    break
                }
                if (!active && count == 1 && start == null && (pressed.first().position - firstPosition).getDistance() > viewConfiguration.touchSlop) singleFingerCommitted = true
                if (!active && !singleFingerCommitted && count == 2) {
                    val center = (pressed[0].position + pressed[1].position) / 2f
                    if (start == null) {
                        start = center
                        anchor = exact(pressed.firstOrNull { it.id == first.id }?.position ?: center) ?: exact(center)
                    } else if (anchor != null && (center - start!!).getDistance() > viewConfiguration.touchSlop) {
                        val selected = controller()!!
                        val baseline = if (selected.active) selected.selected else emptySet()
                        range = MangaSelectionRange(keys, indexes.getValue(anchor!!), baseline, anchor !in baseline)
                        selected.begin()
                        active = controller()?.enabled == true
                        if (active) {
                            range!!.update(indexes.getValue(anchor!!)).forEach { (id, value) -> books[id]?.let { controller()?.set?.invoke(it, value) } }
                        }
                    }
                    if (active) { position = center; applyAt(center) }
                } else if (active && count == 2) {
                    position = (pressed[0].position + pressed[1].position) / 2f
                    applyAt(position!!)
                }
                // Once selection owns a two-finger pan, consume the remaining finger
                // through its UP so it cannot become an accidental item tap/scroll.
                if (active) event.changes.forEach { it.consume() }
                if (event.changes.none { it.pressed }) break
            }
        } finally { autoScroll.cancel() }
    }
}
