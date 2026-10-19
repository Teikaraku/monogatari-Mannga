package app.monogatari.android.tracking

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

enum class TrackStatus(val label: String) {
    READING("阅读中"), PLANNING("计划阅读"), COMPLETED("已完成"), PAUSED("暂停"), DROPPED("放弃"), REREADING("重读")
}

enum class TrackerProvider(val id: String, val title: String, val apiUrl: String, val authUrl: String = "", val tokenUrl: String = "", val defaultClientId: String = "", val pkce: String = "", val scope: String = "") {
    ANILIST("anilist", "AniList", "https://graphql.anilist.co", "https://anilist.co/api/v2/oauth/authorize", "https://anilist.co/api/v2/oauth/token", "8912"),
    MYANIMELIST("myanimelist", "MyAnimeList", "https://api.myanimelist.net/v2", "https://myanimelist.net/v1/oauth2/authorize", "https://myanimelist.net/v1/oauth2/token", "50cc1b37e2af29f668b087485ba46a46", "plain"),
    BANGUMI("bangumi", "Bangumi", "https://api.bgm.tv/v0", "https://bgm.tv/oauth/authorize", "https://bgm.tv/oauth/access_token", "bgm479068d16523bfca2"),
    SHIKIMORI("shikimori", "Shikimori", "https://shikimori.io", "https://shikimori.io/oauth/authorize", "https://shikimori.io/oauth/token", "0pRPZsB87w9mp0gQe1HZbSiGt7FfVzJohPGhJKjayW4", scope = "user_rates"),
    MANGABAKA("mangabaka", "MangaBaka", "https://api.mangabaka.org", "https://mangabaka.org/auth/oauth2/authorize", "https://mangabaka.org/auth/oauth2/token", "rhqyADkXfqdmFVZcslegTvgLvLeOpFdz", "S256", "library.read library.write profile offline_access openid"),
    KOMGA("komga", "Komga", ""), KAVITA("kavita", "Kavita", ""), SUWAYOMI("suwayomi", "Suwayomi", "");
    val server get() = authUrl.isEmpty()
    val supportsDates get() = this in setOf(ANILIST, MYANIMELIST, MANGABAKA)
    companion object { fun from(id: String) = entries.first { it.id == id } }
}

data class TrackerAccount(
    val id: String, val provider: TrackerProvider, val name: String = provider.title,
    val baseUrl: String = provider.apiUrl, val clientId: String = provider.defaultClientId,
    val redirectUri: String = "aidoku://${provider.id}-auth", val username: String = "",
    val userId: String = "", val connected: Boolean = false, val scoreFormat: String = "POINT_100",
    val sourceId: String = "", val autoSync: Boolean = true, val includeAdult: Boolean = false, val useVolumes: Boolean = false,
) {
    fun json() = JSONObject().put("id", id).put("provider", provider.id).put("name", name).put("baseUrl", baseUrl)
        .put("clientId", clientId).put("redirectUri", redirectUri).put("username", username).put("userId", userId)
        .put("connected", connected).put("scoreFormat", scoreFormat).put("sourceId", sourceId).put("autoSync", autoSync).put("includeAdult", includeAdult).put("useVolumes", useVolumes)
    companion object {
        fun parse(j: JSONObject) = TrackerAccount(j.getString("id"), TrackerProvider.from(j.getString("provider")), j.getString("name"),
            j.getString("baseUrl"), j.optString("clientId"), j.optString("redirectUri"), j.optString("username"), j.optString("userId"),
            j.optBoolean("connected"), j.optString("scoreFormat", "POINT_100"), j.optString("sourceId"), j.optBoolean("autoSync", true), j.optBoolean("includeAdult"), j.optBoolean("useVolumes"))
    }
}

/** Score is normalized to 0..100; dates use ISO local dates, an empty date explicitly clears it. */
data class TrackState(
    val status: TrackStatus = TrackStatus.PLANNING, val score: Double = 0.0, val chapter: Double = 0.0, val volume: Int = 0,
    val totalChapters: Int = 0, val totalVolumes: Int = 0, val startDate: String = "", val finishDate: String = "",
    val entryId: String = "", val registered: Boolean = false,
) {
    fun validated(): TrackState {
        require(score.isFinite() && score in 0.0..100.0 && chapter.isFinite() && chapter >= 0 && volume >= 0) { "评分或阅读进度超出范围" }
        listOf(startDate, finishDate).filter { it.isNotBlank() }.forEach { LocalDate.parse(it) }
        return this
    }
    fun json() = JSONObject().put("status", status.name).put("score", score).put("chapter", chapter).put("volume", volume)
        .put("totalChapters", totalChapters).put("totalVolumes", totalVolumes).put("startDate", startDate).put("finishDate", finishDate)
        .put("entryId", entryId).put("registered", registered)
    companion object { fun parse(j: JSONObject) = TrackState(TrackStatus.valueOf(j.optString("status", "PLANNING")), j.optDouble("score"),
        j.optDouble("chapter"), j.optInt("volume"), j.optInt("totalChapters"), j.optInt("totalVolumes"), j.optString("startDate"),
        j.optString("finishDate"), j.optString("entryId"), j.optBoolean("registered")).validated() }
}
data class TrackSearchResult(val id: String, val title: String, val cover: String = "", val description: String = "", val format: String = "", val tracked: Boolean = false)
data class ChapterRemoteProgress(val key: String, val page: Int, val total: Int, val completed: Boolean, val readAt: Long = 0, val updatedAt: Long = 0) {
    fun json() = JSONObject().put("key", key).put("page", page).put("total", total).put("completed", completed).put("readAt", readAt).put("updatedAt", updatedAt)
    companion object { fun parse(j: JSONObject) = ChapterRemoteProgress(j.getString("key"), j.optInt("page"), j.optInt("total"), j.optBoolean("completed"), j.optLong("readAt"), j.optLong("updatedAt")) }
}
data class TrackBinding(
    val mangaId: String, val accountId: String, val remoteId: String, val title: String,
    val state: TrackState = TrackState(), val chapterOffset: Int = 0, val lastSynced: Long = 0,
    val error: String = "", val pending: Boolean = false, val pendingPage: ChapterRemoteProgress? = null, val pendingPages: List<ChapterRemoteProgress> = emptyList(),
) {
    val id get() = "$mangaId|$accountId"
    fun json() = JSONObject().put("mangaId", mangaId).put("accountId", accountId).put("remoteId", remoteId).put("title", title)
        .put("state", state.json()).put("chapterOffset", chapterOffset).put("lastSynced", lastSynced).put("error", error).put("pending", pending).put("pendingPage", pendingPage?.json()).put("pendingPages", JSONArray(pendingPages.map { it.json() }))
    fun queuedPages(): List<ChapterRemoteProgress> = mergeQueuedPages(pendingPages, listOfNotNull(pendingPage))
    companion object { fun parse(j: JSONObject) = TrackBinding(j.getString("mangaId"), j.getString("accountId"), j.getString("remoteId"), j.getString("title"),
        TrackState.parse(j.getJSONObject("state")), j.optInt("chapterOffset"), j.optLong("lastSynced"), j.optString("error"), j.optBoolean("pending"), j.optJSONObject("pendingPage")?.let(ChapterRemoteProgress::parse), j.optJSONArray("pendingPages").list().map(ChapterRemoteProgress::parse)) }
}
internal fun mergeQueuedPages(old: List<ChapterRemoteProgress>, incoming: List<ChapterRemoteProgress>): List<ChapterRemoteProgress> = linkedMapOf<String, ChapterRemoteProgress>().also { map -> (old + incoming).forEach { item -> if (map[item.key]?.updatedAt?.let { it > item.updatedAt } != true) map[item.key] = item } }.values.toList()
data class TrackingData(val accounts: List<TrackerAccount> = emptyList(), val bindings: List<TrackBinding> = emptyList()) {
    fun json() = JSONObject().put("version", 1).put("accounts", JSONArray(accounts.map { it.json() })).put("bindings", JSONArray(bindings.map { it.json() }))
    companion object {
        fun parse(j: JSONObject): TrackingData {
            require(j.optInt("version", 1) == 1) { "不支持的追踪数据版本" }
            return TrackingData(j.optJSONArray("accounts").list().map(TrackerAccount::parse), j.optJSONArray("bindings").list().map(TrackBinding::parse))
        }
    }
}
internal fun JSONArray?.list(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
class TrackerHttpException(val status: Int, message: String) : java.io.IOException(message)
object TrackingScores {
    fun scale(a: TrackerAccount): Int = when {
        a.provider in setOf(TrackerProvider.MYANIMELIST, TrackerProvider.BANGUMI, TrackerProvider.SHIKIMORI) -> 10
        a.provider == TrackerProvider.ANILIST && a.scoreFormat in setOf("POINT_10", "POINT_10_DECIMAL") -> 10
        else -> 100
    }
    fun display(a: TrackerAccount, raw: Double): String = (raw * scale(a) / 100).let { if (a.scoreFormat == "POINT_10_DECIMAL") String.format(java.util.Locale.ROOT, "%.1f", it) else it.toInt().toString() }
    fun parse(a: TrackerAccount, displayed: String): Double { val n = displayed.toDouble(); require(n.isFinite() && n in 0.0..scale(a).toDouble()) { "评分超出范围" }; return n * 100 / scale(a) }
    fun options(a: TrackerAccount): List<Pair<String, Double>> = if (a.provider == TrackerProvider.ANILIST) when (a.scoreFormat) {
        "POINT_5" -> listOf("−" to 0.0, "★" to 10.0, "★★" to 30.0, "★★★" to 50.0, "★★★★" to 70.0, "★★★★★" to 90.0)
        "POINT_3" -> listOf("−" to 0.0, "😦" to 35.0, "😐" to 60.0, "😊" to 85.0)
        else -> emptyList()
    } else emptyList()
}
