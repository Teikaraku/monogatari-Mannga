package app.monogatari.android.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.monogatari.android.MonogatariViewModel
import app.monogatari.android.data.AppState
import app.monogatari.android.library.ReadingInsights
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@Composable fun InsightsScreen(vm: MonogatariViewModel, state: AppState) {
    val data = remember(state.records, state.manga) { ReadingInsights.calculate(state) }
    var expanded by remember { mutableStateOf(-1) }
    var year by remember { mutableStateOf<Int?>(null) }
    var day by remember { mutableStateOf<java.time.LocalDate?>(null) }
    Column {
        PageTitle(tr("INSIGHTS"), vm::back)
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("STREAKS"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InsightCard(Modifier.weight(1f).height(134.dp)) {
                    if (data.currentStreak > 1) { Text(tr("CURRENT_STREAK")); Text(data.currentStreak.toString(), fontSize = 38.sp, fontWeight = FontWeight.Bold); Text(tr("DAYS")) }
                    else { Text(tr("NO_CURRENT_STREAK"), fontWeight = FontWeight.Bold); Text(tr("NO_CURRENT_STREAK_TEXT"), fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                }
                if (data.longestStreak > data.currentStreak && data.longestStreak > 1) InsightCard(Modifier.weight(1f).height(134.dp)) {
                    Text(tr("LONGEST_STREAK")); Text(data.longestStreak.toString(), fontSize = 38.sp, fontWeight = FontWeight.Bold); Text(tr("DAYS"))
                }
            }
            val heatScroll = rememberScrollState()
            LaunchedEffect(data.heatmap.size) { heatScroll.scrollTo(heatScroll.maxValue) }
            InsightCard(Modifier.fillMaxWidth()) {
                Row(Modifier.horizontalScroll(heatScroll).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    data.heatmap.entries.toList().chunked(7).forEach { week -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        week.forEach { cell -> Box(Modifier.size(12.dp).clip(RoundedCornerShape(2.dp)).background(if (cell.value == 0) MaterialTheme.colorScheme.surfaceVariant else Pink.copy(alpha = (0.25f + minOf(cell.value, 5) * 0.15f).coerceAtMost(1f))).clickable { day = cell.key }) }
                    } }
                }
                day?.let { Text("${it.format(DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))}: ${data.heatmap[it]} ${tr("CHAPTER_PLURAL")}", fontSize = 12.sp) }
            }
            Spacer(Modifier.height(4.dp)); Text(tr("STATS"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
            val chart = if (year == null) List(12) { month -> data.chart.values.sumOf { it[month] } } else data.chart[year] ?: List(12) { 0 }
            InsightCard(Modifier.fillMaxWidth().clickable { expanded = if (expanded == 3) -1 else 3 }.animateContentSize()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.width(100.dp)) { Text(chart.sum().toString(), fontSize = 54.sp, fontWeight = FontWeight.Bold); Text(tr(if (chart.sum() == 1) "CHAPTER_SINGULAR" else "CHAPTER_PLURAL")) }
                    Row(Modifier.weight(1f).height(100.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
                        val maximum = maxOf(10, chart.maxOrNull() ?: 0)
                        chart.forEachIndexed { i, value -> Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                            Box(Modifier.width(2.dp).height(maxOf(1f, value * 70f / maximum).dp).background(MaterialTheme.colorScheme.onSurface))
                            Spacer(Modifier.height(8.dp)); val month = java.time.Month.of(i + 1).getDisplayName(TextStyle.SHORT, Locale.getDefault())
                            Text(if (Locale.getDefault().language in listOf("ja", "zh", "ko")) { if (i % 2 == 0) month else "" } else month.take(1), fontSize = 9.sp, maxLines = 1)
                        } }
                    }
                }
                if (expanded == 3 && data.chart.size > 1) Row(Modifier.horizontalScroll(rememberScrollState())) {
                    FilterChip(year == null, { year = null }, { Text(tr("ALL_TIME")) }); data.chart.keys.forEach { y -> FilterChip(year == y, { year = y }, { Text(y.toString()) }) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                listOf(data.pages to "PAGE", data.series to "SERIES", data.hours to "HOUR").forEachIndexed { index, (metric, label) ->
                    InsightCard(Modifier.weight(if (expanded == index) 2f else 1f).clickable { expanded = if (expanded == index) -1 else index }.animateContentSize()) {
                        Text(metric.total.toString(), fontSize = 32.sp, fontWeight = FontWeight.Bold); Text(tr("${label}_${if (metric.total == 1L) "SINGULAR" else "PLURAL"}"), fontSize = 14.sp)
                        if (expanded == index) { Spacer(Modifier.height(20.dp)); Text("${metric.month} ${tr("THIS_MONTH")}", fontSize = 12.sp); Text("${metric.year} ${tr("THIS_YEAR")}", fontSize = 12.sp) }
                    }
                }
            }
        }
    }
}
@Composable private fun InsightCard(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, content = content)
}
