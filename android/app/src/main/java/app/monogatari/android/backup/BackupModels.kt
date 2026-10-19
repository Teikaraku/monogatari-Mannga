package app.monogatari.android.backup

import org.json.JSONArray
import org.json.JSONObject

enum class BackupSection(val key: String, val labelKey: String) {
    LIBRARY("library", "LIBRARY_ENTRIES"), HISTORY("history", "HISTORY"), CHAPTERS("chapters", "CHAPTERS"), TRACKING("trackItems", "TRACKING"),
    SESSIONS("readingSessions", "READING_SESSIONS"), VOCABULARY("vocabulary", "VOCABULARY"), UPDATES("updates", "MANGA_UPDATES"), CATEGORIES("categories", "CATEGORIES"),
    SETTINGS("settings", "SETTINGS"), SOURCE_LISTS("sourceLists", "SOURCE_LISTS"), SOURCES("sources", "SOURCES"), DICTIONARIES("dictionaries", "DICTIONARIES"), MEDIA("media", "DOWNLOADS")
}
enum class BackupFormat(val extension: String) { ANDROID_ZIP("monogatari.zip"), IOS_BINARY("aib"), IOS_JSON("json") }
data class BackupOptions(
    val selected: Set<BackupSection> = BackupSection.entries.toSet() - setOf(BackupSection.VOCABULARY, BackupSection.UPDATES),
    val sensitiveSettings: Boolean = false,
) {
    fun has(section: BackupSection) = section in selected
    fun json() = JSONObject().put("selected", JSONArray(selected.map { it.key })).put("sensitiveSettings", sensitiveSettings)
    companion object { fun parse(j: JSONObject) = BackupOptions(j.getJSONArray("selected").let { a -> (0 until a.length()).mapNotNull { i -> BackupSection.entries.firstOrNull { it.key == a.getString(i) } }.toSet() }, j.optBoolean("sensitiveSettings")) }
}
data class RestoreOptions(val selected: Set<BackupSection> = BackupSection.entries.toSet(), val merge: Boolean = false, val restoreSensitive: Boolean = false, val installMissingSources: Boolean = false)
data class BackupSummary(val file: java.io.File, val name: String, val date: Long, val version: String, val automatic: Boolean, val counts: Map<BackupSection, Int>, val size: Long, val format: BackupFormat) {
    fun json() = JSONObject().put("name", name).put("date", date / 1000.0).put("version", version).put("automatic", automatic).put("counts", JSONObject(counts.mapKeys { it.key.key }))
}
data class RestoreResult(val manga: Int, val warnings: List<String> = emptyList())
