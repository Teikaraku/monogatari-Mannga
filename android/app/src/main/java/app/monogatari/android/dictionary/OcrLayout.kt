package app.monogatari.android.dictionary

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class OcrDirection { UNKNOWN, VERTICAL, LEFT_TO_RIGHT, RIGHT_TO_LEFT }
data class OcrGlyph(val offset: Int, val text: String, val bounds: OcrRegion)
data class OcrObservation(val region: OcrRegion, val characters: List<OcrGlyph>, val confidence: Float = 1f, val direction: OcrDirection = OcrDirection.UNKNOWN)
data class OcrHit(val text: String, val fullText: String, val primary: OcrObservation, val contextBounds: OcrRegion, val characters: List<OcrGlyph>, val contextOffset: Int = 0)

/** Port of original TextRecognizer+Clustering/Lookup thresholds, in normalized top-left image coordinates. */
object OcrLayout {
    private val OcrRegion.width get() = right - left
    private val OcrRegion.height get() = bottom - top
    private val OcrRegion.x get() = (right + left) / 2
    private val OcrRegion.y get() = (bottom + top) / 2
    private fun OcrRegion.contains(x: Float, y: Float) = x in left..right && y in top..bottom
    private fun orientation(observation: OcrObservation) = if (observation.direction == OcrDirection.UNKNOWN) if (observation.region.height > observation.region.width * 1.1f) OcrDirection.VERTICAL else OcrDirection.LEFT_TO_RIGHT else observation.direction
    private fun overlap(a0: Float, a1: Float, b0: Float, b1: Float, divisor: Float) = if (divisor <= 0) 0f else max(0f, min(a1, b1) - max(a0, b0)) / divisor
    private fun neighbors(a: OcrObservation, b: OcrObservation): Boolean {
        val x = a.region; val y = b.region; val distance = hypot(x.x - y.x, x.y - y.y)
        val scale = max(max(x.width, x.height), max(y.width, y.height)); if (distance > scale * 3.5f) return false
        val xGap = max(0f, max(y.left - x.right, x.left - y.right)); val yGap = max(0f, max(y.top - x.bottom, x.top - y.bottom))
        val xOverlap = overlap(x.left, x.right, y.left, y.right, min(x.width, y.width)); val yOverlap = overlap(x.top, x.bottom, y.top, y.bottom, min(x.height, y.height))
        val first = orientation(a); val second = orientation(b)
        if (first == OcrDirection.VERTICAL && second == OcrDirection.VERTICAL) {
            val thickness = max(min(x.width, x.height), min(y.width, y.height))
            return (xOverlap > .45f && yGap < thickness * 1.8f || yOverlap > .50f && xGap < thickness) && distance <= thickness * 3f
        }
        val connected = yOverlap > .28f && xGap < max(x.width, y.width) * 1.2f || xOverlap > .28f && yGap < max(x.height, y.height) * 1.6f || xOverlap > .28f && yGap < max(x.height, y.height) * 1.2f || yOverlap > .28f && xGap < max(x.width, y.width) * 1.6f
        return connected && distance <= scale * if (first == second) 3.5f else 1.15f
    }
    fun clusters(observations: List<OcrObservation>): List<List<Int>> {
        val unvisited = observations.indices.toMutableSet(); val result = mutableListOf<List<Int>>()
        while (unvisited.isNotEmpty()) { val cluster = mutableListOf<Int>(); val queue = java.util.ArrayDeque<Int>(); val seed = unvisited.first(); queue.add(seed); unvisited.remove(seed)
            while (queue.isNotEmpty()) { val current = queue.removeFirst(); cluster += current; val adjacent = unvisited.filter { neighbors(observations[current], observations[it]) }; adjacent.forEach { unvisited.remove(it); queue.add(it) } }
            result += ordered(observations, cluster)
        }; return result
    }
    private fun clusterDirection(observations: List<OcrObservation>, indices: List<Int>): OcrDirection {
        val scores = mutableMapOf(OcrDirection.VERTICAL to 0f, OcrDirection.LEFT_TO_RIGHT to 0f, OcrDirection.RIGHT_TO_LEFT to 0f)
        indices.forEach { index -> val o = observations[index]; val weight = max(o.confidence.takeIf { it.isFinite() } ?: 0f, .35f) * if (o.direction == OcrDirection.UNKNOWN) .7f else 1f; val direction = orientation(o); scores[direction] = scores.getValue(direction) + weight }
        return when { scores.getValue(OcrDirection.VERTICAL) >= scores.getValue(OcrDirection.LEFT_TO_RIGHT) && scores.getValue(OcrDirection.VERTICAL) >= scores.getValue(OcrDirection.RIGHT_TO_LEFT) -> OcrDirection.VERTICAL; scores.getValue(OcrDirection.RIGHT_TO_LEFT) > scores.getValue(OcrDirection.LEFT_TO_RIGHT) -> OcrDirection.RIGHT_TO_LEFT; else -> OcrDirection.LEFT_TO_RIGHT }
    }
    fun ordered(observations: List<OcrObservation>, indices: List<Int>): List<Int> {
        if (indices.isEmpty()) return emptyList()
        val direction = clusterDirection(observations, indices); val vertical = direction == OcrDirection.VERTICAL
        val dimensions = indices.map { if (vertical) observations[it].region.width else observations[it].region.height }.sorted()
        val band = max(dimensions[dimensions.size / 2] * if (vertical) .52f else .65f, if (vertical) .008f else .010f)
        data class Group(var center: Float, val members: MutableList<Int>)
        val groups = mutableListOf<Group>(); val sorted = indices.sortedWith(if (vertical) compareByDescending { observations[it].region.x } else compareBy { observations[it].region.y })
        for (index in sorted) { val coordinate = if (vertical) observations[index].region.x else observations[index].region.y; val nearest = groups.minByOrNull { abs(it.center - coordinate) }
            if (nearest != null && abs(nearest.center - coordinate) <= band) { nearest.members += index; nearest.center = (nearest.center * (nearest.members.size - 1) + coordinate) / nearest.members.size } else groups += Group(coordinate, mutableListOf(index))
        }
        val ordered = if (vertical) groups.sortedByDescending { it.center } else groups.sortedBy { it.center }
        return ordered.flatMap { group -> group.members.sortedWith { first, second -> val a = observations[first].region; val b = observations[second].region
            if (vertical) if (abs(a.y - b.y) > .004f) a.y.compareTo(b.y) else b.x.compareTo(a.x)
            else if (abs(a.x - b.x) > .004f) if (direction == OcrDirection.RIGHT_TO_LEFT) b.x.compareTo(a.x) else a.x.compareTo(b.x) else a.y.compareTo(b.y)
        } }
    }
    fun find(observations: List<OcrObservation>, x: Float, y: Float): OcrHit? {
        val primary = observations.indices.filter { observations[it].region.contains(x, y) }.minWithOrNull(compareBy<Int> { observations[it].region.width * observations[it].region.height }.thenByDescending { observations[it].confidence }.thenBy { it }) ?: return null
        val observation = observations[primary]; val offset = observation.characters.indexOfFirst { it.bounds.contains(x, y) }.takeIf { it >= 0 } ?: return null
        val cluster = clusters(observations).firstOrNull { primary in it } ?: listOf(primary); var include = false; val slice = StringBuilder(); val characters = mutableListOf<OcrGlyph>()
        for (index in cluster) { if (index == primary) include = true; if (!include) continue; val entry = observations[index]; val selected = if (index == primary) entry.characters.drop(offset) else entry.characters; val start = selected.firstOrNull()?.offset ?: continue
            if (start !in entry.region.text.indices) continue; val base = slice.length; slice.append(entry.region.text.substring(start)); characters += selected.map { it.copy(offset = base + it.offset - start) }
        }
        val raw = cluster.joinToString("") { observations[it].region.text }; val full = raw.trim(); val boxes = cluster.map { observations[it].region }
        val contextOffset = (cluster.takeWhile { it != primary }.sumOf { observations[it].region.text.length } + observation.characters[offset].offset - raw.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)).coerceIn(0, full.length)
        return OcrHit(slice.toString(), full.ifBlank { observation.region.text }, observation, OcrRegion(full, boxes.minOf { it.left }, boxes.minOf { it.top }, boxes.maxOf { it.right }, boxes.maxOf { it.bottom }), characters, contextOffset)
    }
}
