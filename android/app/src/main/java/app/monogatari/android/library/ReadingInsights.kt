package app.monogatari.android.library

import app.monogatari.android.data.*
import org.json.JSONObject
import java.time.*
import java.time.temporal.WeekFields
import java.util.Locale

data class InsightMetric(val total: Long, val month: Long, val year: Long)
data class ReadingInsights(val currentStreak: Int, val longestStreak: Int, val heatmap: Map<LocalDate, Int>,
    val chart: Map<Int, List<Int>>, val pages: InsightMetric, val series: InsightMetric, val hours: InsightMetric) {
    companion object {
        fun calculate(state: AppState, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): ReadingInsights {
            data class Session(val manga: String, val chapter: String, val date: LocalDate, val pages: Int, val seconds: Double)
            val sessions = JSONObject(state.records).optJSONArray("readingSessions").objects().mapNotNull { j ->
                val start = j.optDouble("startDate", Double.NaN); val end = j.optDouble("endDate", Double.NaN)
                if (!start.isFinite() || !end.isFinite() || end < start || j.optInt("pagesRead") < 0) return@mapNotNull null
                val date = runCatching { Instant.ofEpochMilli((end * 1000).toLong()).atZone(zone).toLocalDate() }.getOrNull() ?: return@mapNotNull null
                Session("${j.optString("sourceId")}::${j.optString("mangaId")}", j.optString("chapterId"), date, j.optInt("pagesRead"), end - start)
            }
            val today = now.atZone(zone).toLocalDate()
            val days = sessions.map { it.date }.distinct().sorted()
            var current = 0; var longest = 0
            days.forEachIndexed { i, day -> current = if (i > 0 && days[i - 1].plusDays(1) == day) current + 1 else 1; longest = maxOf(longest, current) }
            if (days.size < 2 || longest < 2) { current = 0; longest = 0 }
            else if (days.last() !in listOf(today, today.minusDays(1))) current = 0
            val initial = today.minusDays(364)
            val first = WeekFields.of(Locale.getDefault()).firstDayOfWeek.value
            val start = initial.minusDays(((initial.dayOfWeek.value - first + 7) % 7).toLong())
            val byDay = sessions.groupBy { it.date }.mapValues { (_, s) -> s.map { it.manga to it.chapter }.distinct().size }
            val heat = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.associateWith { byDay[it] ?: 0 }
            val chapters = state.manga.associate { m -> m.id to m.chapters.associateBy { it.key } }
            val orphanHistory = JSONObject(state.records).optJSONArray("history").objects().associateBy { (it.optString("sourceId") + "::" + it.optString("mangaId")) to it.optString("chapterId") }
            val chart = mutableMapOf<Int, MutableList<Int>>()
            sessions.groupBy { Triple(it.manga to it.chapter, it.date.year, it.date.monthValue) }.forEach { (key, entries) ->
                val chapter = chapters[key.first.first]?.get(key.first.second)
                val orphan = orphanHistory[key.first]
                val total = chapter?.total ?: orphan?.optInt("total") ?: 0
                val completed = if (total > 0) entries.sumOf { it.pages.toLong() } >= total else chapter?.completed ?: orphan?.optBoolean("completed") ?: false
                if (completed) chart.getOrPut(key.second) { MutableList(12) { 0 } }[key.third - 1]++
            }
            fun metric(kind: Int): InsightMetric {
                fun value(s: List<Session>): Long = when (kind) {
                    0 -> s.sumOf { it.pages.toLong() }; 1 -> s.map { it.manga }.distinct().size.toLong(); else -> (s.sumOf { it.seconds } / 3600).toLong()
                }
                return InsightMetric(value(sessions), value(sessions.filter { it.date.year == today.year && it.date.month == today.month }), value(sessions.filter { it.date.year == today.year }))
            }
            return ReadingInsights(current, longest, heat, chart.toSortedMap(), metric(0), metric(1), metric(2))
        }
    }
}
