package app.monogatari.android.library

import app.monogatari.android.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class MangaUpdateGroup(val day: LocalDate, val manga: Manga, val updates: List<JSONObject>)
object UpdateRecords {
    fun groups(state: AppState, zone: ZoneId = ZoneId.systemDefault()): List<MangaUpdateGroup> {
        val mangas = state.manga.associateBy { it.sourceId to it.key }
        return JSONObject(state.records).optJSONArray("updates").objects()
            .filter { it.optDouble("date").isFinite() && (it.optString("sourceId") to it.optString("mangaId")) in mangas }
            .groupBy { Instant.ofEpochMilli((it.optDouble("date") * 1000).toLong()).atZone(zone).toLocalDate() to (it.optString("sourceId") to it.optString("mangaId")) }
            .map { (key, updates) -> MangaUpdateGroup(key.first, mangas.getValue(key.second), updates.sortedBy { it.optDouble("date") }) }
            .sortedWith(compareByDescending<MangaUpdateGroup> { it.day }.thenByDescending { it.updates.firstOrNull()?.optDouble("date") ?: 0.0 })
    }
    fun remove(state: AppState, group: MangaUpdateGroup): AppState {
        val keys = group.updates.map { it.optString("chapterId") }.toSet()
        fun cleaned(raw: String): String {
            val data = JSONObject(raw)
            if (data.has("updates")) data.put("updates", JSONArray(data.optJSONArray("updates").objects().filterNot { row ->
                row.optString("sourceId") == group.manga.sourceId && row.optString("mangaId") == group.manga.key && row.optString("chapterId") in keys
            }))
            return data.toString()
        }
        return state.copy(records = cleaned(state.records), backupExtras = cleaned(state.backupExtras))
    }
}
