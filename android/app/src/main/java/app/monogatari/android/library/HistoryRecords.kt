package app.monogatari.android.library

import app.monogatari.android.data.*
import org.json.JSONArray
import org.json.JSONObject

/** History also retains offline chapters removed by a source; clearing progress must preserve those files. */
object HistoryRecords {
    fun clear(raw: String, matches: (JSONObject) -> Boolean): String {
        val data = JSONObject(raw)
        data.optJSONArray("history")?.let { rows -> data.put("history", JSONArray(rows.objects().mapNotNull { row ->
            if (!matches(row)) row else {
                val stored = row.optJSONObject("_chapter")
                if (stored?.optBoolean("downloaded") != true) null else {
                    val chapter = Chapter.parse(stored)
                    row.put("dateRead", 0).put("progress", 0).put("completed", false).remove("scrollPosition")
                    val metadata = JSONObject(chapter.metadata); metadata.remove("scrollPosition")
                    row.put("_chapter", chapter.copy(page = 0, completed = false, readAt = 0, metadata = metadata.toString()).json())
                }
            }
        })) }
        data.optJSONArray("readingSessions")?.let { rows -> data.put("readingSessions", JSONArray(rows.objects().filterNot(matches))) }
        return data.toString()
    }
}
