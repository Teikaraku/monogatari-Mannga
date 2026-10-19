package app.monogatari.android.tracking

import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.floor

/** Native implementations of the original eight Aidoku tracking adapters. Transport handles auth/retry. */
class TrackerApi(private val transport: suspend (TrackerAccount, String, String, RequestBody?) -> JSONObject) {
    private suspend fun call(a: TrackerAccount, path: String = "", method: String = "GET", data: JSONObject? = null, form: FormBody? = null): JSONObject {
        val url = if (path.startsWith("https://") || path.startsWith("http://")) path else a.baseUrl.trimEnd('/') + if (path.isEmpty()) "" else "/" + path.trimStart('/')
        return transport(a, url, method, form ?: data?.toString()?.toRequestBody("application/json".toMediaType())).also { response ->
            if (a.provider == TrackerProvider.MANGABAKA && response.optInt("status", 200) !in 200..299) throw java.io.IOException(response.optString("message", "MangaBaka 请求失败"))
        }
    }
    private suspend fun gql(a: TrackerAccount, query: String, variables: JSONObject = JSONObject(), path: String = ""): JSONObject {
        val response = call(a, path, "POST", JSONObject().put("query", query).put("variables", variables))
        val errors = response.optJSONArray("errors")
        if (errors != null && errors.length() > 0) throw java.io.IOException(errors.getJSONObject(0).optString("message", "GraphQL 请求失败"))
        return response.getJSONObject("data")
    }
    private fun query(path: String, pairs: Map<String, String>): String = ("https://placeholder.invalid/$path").toHttpUrl().newBuilder().apply { pairs.forEach { (k, v) -> addQueryParameter(k, v) } }.build().let { it.encodedPath.trimStart('/') + "?" + it.encodedQuery }
    suspend fun profile(a: TrackerAccount): TrackerAccount = when (a.provider) {
        TrackerProvider.ANILIST -> gql(a, "query { Viewer { id name mediaListOptions { scoreFormat } } }").getJSONObject("Viewer").let {
            a.copy(userId = it.getString("id"), username = it.optString("name"), connected = true, scoreFormat = it.optJSONObject("mediaListOptions")?.optString("scoreFormat") ?: "POINT_10")
        }
        TrackerProvider.MYANIMELIST -> call(a, "users/@me").let { a.copy(userId = it.getString("id"), username = it.optString("name"), connected = true) }
        TrackerProvider.BANGUMI -> call(a, "me").let { a.copy(userId = it.getString("id"), username = it.getString("username"), connected = true) }
        TrackerProvider.SHIKIMORI -> call(a, "api/users/whoami").let { a.copy(userId = it.getString("id"), username = it.optString("nickname"), connected = true) }
        TrackerProvider.MANGABAKA -> call(a, "v1/my/profile").getJSONObject("data").let { a.copy(userId = it.optString("id"), username = it.optString("username", it.optString("name", "MangaBaka")), connected = true) }
        TrackerProvider.KOMGA -> call(a, "api/v1/users/me").let { a.copy(username = it.optString("email", a.username), connected = true) }
        TrackerProvider.KAVITA -> { call(a, "api/Library/libraries"); a.copy(connected = true) }
        TrackerProvider.SUWAYOMI -> { gql(a, "query { mangas(condition:{inLibrary:true}) { totalCount } }", path = "api/graphql"); a.copy(connected = true) }
    }
    suspend fun search(a: TrackerAccount, title: String): List<TrackSearchResult> = when (a.provider) {
        TrackerProvider.ANILIST -> gql(a, "query(\$search:String,\$adult:Boolean){Page(perPage:25){media(search:\$search,type:MANGA,isAdult:\$adult){id title{userPreferred} coverImage{medium} description format mediaListEntry{id}}}}",
            JSONObject().put("search", title).put("adult", if (a.includeAdult) JSONObject.NULL else false)).getJSONObject("Page").getJSONArray("media").list().map {
                TrackSearchResult(it.getString("id"), it.getJSONObject("title").getString("userPreferred"), it.optJSONObject("coverImage")?.optString("medium").orEmpty(), it.optString("description"), it.optString("format"), !it.isNull("mediaListEntry"))
            }
        TrackerProvider.MYANIMELIST -> call(a, query("manga", mapOf("q" to title.take(64), "limit" to "25", "nsfw" to a.includeAdult.toString(), "fields" to "synopsis,media_type,my_list_status"))).getJSONArray("data").list().map { row ->
            val it = row.getJSONObject("node"); TrackSearchResult(it.getString("id"), it.getString("title"), it.optJSONObject("main_picture")?.optString("large").orEmpty(), it.optString("synopsis"), it.optString("media_type"), !it.isNull("my_list_status"))
        }
        TrackerProvider.BANGUMI -> call(a, "search/subjects?limit=25", "POST", JSONObject().put("keyword", title).put("sort", "match").put("filter", JSONObject().put("type", JSONArray().put(1)).apply { if (!a.includeAdult) put("nsfw", false) })).getJSONArray("data").list().map {
            TrackSearchResult(it.getString("id"), it.optString("name_cn").ifBlank { it.optString("name") }, it.optJSONObject("images")?.optString("common").orEmpty(), it.optString("summary"), "书籍")
        }
        TrackerProvider.SHIKIMORI -> gql(a, "query(\$search:String,\$censored:Boolean){mangas(search:\$search,limit:25,censored:\$censored){id name russian kind poster{mini2xUrl}}}", JSONObject().put("search", title).put("censored", !a.includeAdult), "api/graphql").getJSONArray("mangas").list().map {
            TrackSearchResult(it.getString("id"), it.optString("name"), it.optJSONObject("poster")?.optString("mini2xUrl").orEmpty(), it.optString("russian"), it.optString("kind"))
        }
        TrackerProvider.MANGABAKA -> {
            val url = (a.baseUrl + "/v1/series/search").toHttpUrl().newBuilder().addQueryParameter("q", title).apply {
                if (!a.includeAdult) { addQueryParameter("not_content_rating", "erotica"); addQueryParameter("not_content_rating", "pornographic") }
            }.build().toString()
            call(a, url).getJSONArray("data").list().map { TrackSearchResult(it.getString("id"), it.getString("title"), it.optJSONObject("cover")?.optJSONObject("raw")?.optString("url").orEmpty(), it.optString("description"), it.optString("type")) }
        }
        TrackerProvider.KOMGA -> call(a, "api/v1/series/list?size=40", "POST", JSONObject().put("fullTextSearch", title)).getJSONArray("content").list().map {
            TrackSearchResult(it.getString("id"), it.optJSONObject("metadata")?.optString("title") ?: it.optString("name"), a.baseUrl + "/api/v1/series/${it.getString("id")}/thumbnail")
        }
        TrackerProvider.KAVITA -> call(a, "api/series/v2", "POST", JSONObject().put("statements", JSONArray().put(JSONObject().put("comparison", 5).put("field", 1).put("value", title)))
            .put("combination", 1).put("sortOptions", JSONObject().put("sortField", 1).put("isAscending", true)).put("limitTo", 0)).getJSONArray("_array").list().map { TrackSearchResult(it.getString("id"), it.optString("name")) }
        TrackerProvider.SUWAYOMI -> gql(a, "query { mangas(condition:{inLibrary:true}){nodes{id title thumbnailUrl}} }", path = "api/graphql").getJSONObject("mangas").getJSONArray("nodes").list().filter { it.optString("title").contains(title, true) }.map { TrackSearchResult(it.getString("id"), it.getString("title"), it.optString("thumbnailUrl")) }
    }
    suspend fun read(a: TrackerAccount, remoteId: String, entryId: String = ""): TrackState = when (a.provider) {
        TrackerProvider.ANILIST -> gql(a, "query(\$id:Int){Media(id:\$id){chapters volumes mediaListEntry{id status score(format:POINT_100) progress progressVolumes startedAt{year month day} completedAt{year month day}}}}", JSONObject().put("id", remoteId.toInt())).getJSONObject("Media").let { media ->
            val s = media.optJSONObject("mediaListEntry"); TrackState(status(TrackerProvider.ANILIST, s?.optString("status")), s?.optDouble("score") ?: 0.0, s?.optDouble("progress") ?: 0.0, s?.optInt("progressVolumes") ?: 0,
                media.optInt("chapters"), media.optInt("volumes"), fuzzyDate(s?.optJSONObject("startedAt")), fuzzyDate(s?.optJSONObject("completedAt")), s?.optString("id").orEmpty(), s != null)
        }
        TrackerProvider.MYANIMELIST -> call(a, "manga/$remoteId?fields=num_volumes,num_chapters,my_list_status").let { media ->
            val s = media.optJSONObject("my_list_status"); TrackState(if (s?.optBoolean("is_rereading") == true) TrackStatus.REREADING else status(a.provider, s?.optString("status")), (s?.optDouble("score") ?: 0.0) * 10,
                s?.optDouble("num_chapters_read") ?: 0.0, s?.optInt("num_volumes_read") ?: 0, media.optInt("num_chapters"), media.optInt("num_volumes"), s?.optString("start_date").orEmpty(), s?.optString("finish_date").orEmpty(), registered = s != null)
        }
        TrackerProvider.BANGUMI -> {
            val media = call(a, "subjects/$remoteId")
            val s = optional404 { call(a, "users/${a.username}/collections/$remoteId") }
            TrackState(status(a.provider, s?.optString("type")), (s?.optDouble("rate") ?: 0.0) * 10, s?.optDouble("ep_status") ?: 0.0, s?.optInt("vol_status") ?: 0, media.optInt("total_episodes", media.optInt("eps")), media.optInt("volumes"), registered = s != null)
        }
        TrackerProvider.SHIKIMORI -> {
            val s = if (entryId.isNotEmpty()) optional404 { call(a, "api/v2/user_rates/$entryId") } else call(a, query("api/v2/user_rates", mapOf("user_id" to a.userId, "target_id" to remoteId, "target_type" to "Manga"))).optJSONArray("_array")?.optJSONObject(0)
            val media = call(a, "api/mangas/${s?.optString("target_id")?.takeIf { it.isNotEmpty() } ?: remoteId}")
            TrackState(status(a.provider, s?.optString("status")), (s?.optDouble("score") ?: 0.0) * 10, s?.optDouble("chapters") ?: 0.0, s?.optInt("volumes") ?: 0, media.optInt("chapters"), media.optInt("volumes"),
                startDate = s?.optString("created_at").orEmpty().take(10), finishDate = if (s?.optString("status") == "completed") s.optString("updated_at").take(10) else "", entryId = s?.optString("id").orEmpty(), registered = s != null)
        }
        TrackerProvider.MANGABAKA -> {
            val media = call(a, "v1/series/$remoteId").getJSONObject("data")
            val s = optional404 { call(a, "v1/my/library/$remoteId").optJSONObject("data") }
            TrackState(status(a.provider, s?.optString("state")), s?.optDouble("rating") ?: 0.0, s?.optDouble("progress_chapter") ?: 0.0, s?.optInt("progress_volume") ?: 0,
                if (media.optString("status") == "releasing") 0 else media.optString("total_chapters").toIntOrNull() ?: 0, media.optString("final_volume").toIntOrNull() ?: 0,
                s?.optString("start_date").orEmpty().take(10).takeUnless { it == "null" }.orEmpty(), s?.optString("finish_date").orEmpty().take(10).takeUnless { it == "null" }.orEmpty(), registered = s != null)
        }
        TrackerProvider.KOMGA -> call(a, "api/v2/series/$remoteId/read-progress/tachiyomi").let { if (a.useVolumes) TrackState(volume = floor(it.optDouble("lastReadContinuousNumberSort")).toInt(), totalVolumes = floor(it.optDouble("maxNumberSort")).toInt(), registered = true) else TrackState(chapter = it.optDouble("lastReadContinuousNumberSort"), totalChapters = floor(it.optDouble("maxNumberSort")).toInt(), registered = true) }
        TrackerProvider.KAVITA -> {
            val all = call(a, "api/Series/volumes?seriesId=$remoteId").getJSONArray("_array").list()
            val latest = optional404 { call(a, "api/Tachiyomi/latest-chapter?seriesId=$remoteId") }
            TrackState(chapter = latest?.optString("number")?.toDoubleOrNull() ?: 0.0, totalChapters = all.flatMap { it.optJSONArray("chapters").list() }.mapNotNull { it.optString("number").toDoubleOrNull()?.takeIf { n -> n < 100000 } }.maxOrNull()?.toInt() ?: 0,
                totalVolumes = all.maxOfOrNull { it.optInt("number").takeIf { n -> n < 100000 } ?: 0 } ?: 0, registered = true)
        }
        TrackerProvider.SUWAYOMI -> gql(a, "query(\$id:Int!){manga(id:\$id){chapters{totalCount} latestReadChapter{chapterNumber} highestNumberedChapter{chapterNumber}}}", JSONObject().put("id", remoteId.toInt()), "api/graphql").getJSONObject("manga").let {
            TrackState(chapter = it.optJSONObject("latestReadChapter")?.optDouble("chapterNumber") ?: 0.0, totalChapters = it.optJSONObject("highestNumberedChapter")?.optDouble("chapterNumber")?.toInt() ?: it.getJSONObject("chapters").optInt("totalCount"), registered = true)
        }
    }
    suspend fun update(a: TrackerAccount, remoteId: String, state: TrackState): TrackState {
        state.validated()
        var newEntryId = state.entryId
        when (a.provider) {
            TrackerProvider.ANILIST -> {
                val vars = JSONObject().put("id", remoteId.toInt()).put("status", statusName(a.provider, state.status)).put("progress", floor(state.chapter).toInt()).put("volumes", state.volume).put("score", state.score.toInt())
                    .put("started", fuzzy(state.startDate)).put("finished", fuzzy(state.finishDate))
                newEntryId = gql(a, "mutation(\$id:Int,\$status:MediaListStatus,\$progress:Int,\$volumes:Int,\$score:Int,\$started:FuzzyDateInput,\$finished:FuzzyDateInput){SaveMediaListEntry(mediaId:\$id,status:\$status,progress:\$progress,progressVolumes:\$volumes,scoreRaw:\$score,startedAt:\$started,completedAt:\$finished){id}}", vars).getJSONObject("SaveMediaListEntry").getString("id")
            }
            TrackerProvider.MYANIMELIST -> call(a, "manga/$remoteId/my_list_status", "PATCH", form = FormBody.Builder().add("status", statusName(a.provider, state.status)).add("is_rereading", (state.status == TrackStatus.REREADING).toString())
                .add("score", (state.score / 10).toInt().toString()).add("num_chapters_read", floor(state.chapter).toInt().toString()).add("num_volumes_read", state.volume.toString()).add("start_date", state.startDate).add("finish_date", state.finishDate).build())
            TrackerProvider.BANGUMI -> call(a, "users/-/collections/$remoteId", "POST", JSONObject().put("type", statusName(a.provider, state.status).toInt()).put("rate", (state.score / 10).toInt()).put("ep_status", floor(state.chapter).toInt()).put("vol_status", state.volume))
            TrackerProvider.SHIKIMORI -> {
                val rate = JSONObject().put("status", statusName(a.provider, state.status)).put("score", (state.score / 10).toInt()).put("chapters", floor(state.chapter).toInt()).put("volumes", state.volume)
                if (state.entryId.isEmpty()) rate.put("user_id", a.userId.toInt()).put("target_id", remoteId.toInt()).put("target_type", "Manga")
                newEntryId = call(a, "api/v2/user_rates" + if (state.entryId.isEmpty()) "" else "/${state.entryId}", if (state.entryId.isEmpty()) "POST" else "PATCH", JSONObject().put("user_rate", rate)).getString("id")
            }
            TrackerProvider.MANGABAKA -> call(a, "v1/my/library/$remoteId", if (state.registered) "PATCH" else "POST", mangaBakaPayload(state))
            TrackerProvider.KOMGA -> call(a, "api/v2/series/$remoteId/read-progress/tachiyomi", "PUT", JSONObject().put("lastBookNumberSortRead", if (a.useVolumes) state.volume.toDouble() else state.chapter))
            TrackerProvider.KAVITA -> call(a, "api/Tachiyomi/mark-chapter-until-as-read?seriesId=$remoteId&chapterNumber=${state.chapter}", "POST", JSONObject())
            TrackerProvider.SUWAYOMI -> {
                val nodes = gql(a, "query(\$id:Int!){chapters(condition:{mangaId:\$id}){nodes{id chapterNumber}}}", JSONObject().put("id", remoteId.toInt()), "api/graphql").getJSONObject("chapters").getJSONArray("nodes").list()
                val ids = nodes.filter { it.optDouble("chapterNumber") <= state.chapter }.map { it.getInt("id") }
                if (ids.isNotEmpty()) gql(a, "mutation(\$ids:[Int!]!,\$patch:UpdateChapterPatchInput!){updateChapters(input:{ids:\$ids,patch:\$patch}){chapters{id}}}", JSONObject().put("ids", JSONArray(ids)).put("patch", JSONObject().put("isRead", true)), "api/graphql")
            }
        }
        return state.copy(entryId = newEntryId, registered = true)
    }
    suspend fun page(a: TrackerAccount, seriesId: String, progress: ChapterRemoteProgress) {
        when (a.provider) {
            TrackerProvider.KOMGA -> if (!progress.completed && progress.page == 0 && progress.readAt == 0L) call(a, "api/v1/books/${progress.key}/read-progress", "DELETE") else call(a, "api/v1/books/${progress.key}/read-progress", "PATCH", JSONObject().put("page", if (progress.completed) progress.total else progress.page + 1).put("completed", progress.completed))
            TrackerProvider.KAVITA -> {
                val info = call(a, "api/reader/chapter-info?chapterId=${progress.key}")
                call(a, "api/reader/progress", "POST", JSONObject().put("libraryId", info.getInt("libraryId")).put("seriesId", seriesId.toInt()).put("volumeId", info.getInt("volumeId")).put("chapterId", progress.key.toInt()).put("pageNum", if (progress.completed) info.getInt("pages") else progress.page))
            }
            TrackerProvider.SUWAYOMI -> gql(a, "mutation(\$id:Int!,\$patch:UpdateChapterPatchInput!){updateChapter(input:{id:\$id,patch:\$patch}){chapter{id}}}", JSONObject().put("id", progress.key.toInt()).put("patch", JSONObject().put("isRead", progress.completed).put("lastPageRead", progress.page.coerceAtLeast(0))), "api/graphql")
            else -> Unit
        }
    }
    suspend fun pages(a: TrackerAccount, seriesId: String): List<ChapterRemoteProgress> = when (a.provider) {
        TrackerProvider.KOMGA -> call(a, "api/v1/series/$seriesId/books?unpaged=true").getJSONArray("content").list().mapNotNull { book ->
            val p = book.optJSONObject("readProgress") ?: return@mapNotNull null
            ChapterRemoteProgress(book.getString("id"), (p.optInt("page", 1) - 1).coerceAtLeast(0), book.optJSONObject("media")?.optInt("pagesCount") ?: 0, p.optBoolean("completed"), timestamp(p.optString("lastModified")))
        }
        TrackerProvider.KAVITA -> call(a, "api/Series/volumes?seriesId=$seriesId").getJSONArray("_array").list().flatMap { it.getJSONArray("chapters").list() }.filter { it.optInt("pagesRead") > 0 }.map {
            ChapterRemoteProgress(it.getString("id"), it.optInt("pagesRead").coerceAtLeast(0), it.optInt("pages"), it.optInt("pagesRead") >= it.optInt("pages") && it.optInt("pages") > 0, timestamp(it.optString("lastReadingProgressUtc")))
        }
        TrackerProvider.SUWAYOMI -> gql(a, "query(\$id:Int!){chapters(condition:{mangaId:\$id}){nodes{id isRead lastPageRead pageCount lastReadAt}}}", JSONObject().put("id", seriesId.toInt()), "api/graphql").getJSONObject("chapters").getJSONArray("nodes").list().filter { it.optBoolean("isRead") || it.optInt("lastPageRead") > 0 }.map {
            ChapterRemoteProgress(it.getString("id"), if (it.optBoolean("isRead")) maxOf(it.optInt("pageCount") - 1, it.optInt("lastPageRead")) else it.optInt("lastPageRead"), it.optInt("pageCount"), it.optBoolean("isRead"), it.optLong("lastReadAt"))
        }
        else -> emptyList()
    }
    fun webUrl(a: TrackerAccount, id: String) = when (a.provider) {
        TrackerProvider.ANILIST -> "https://anilist.co/manga/$id"; TrackerProvider.MYANIMELIST -> "https://myanimelist.net/manga/$id"; TrackerProvider.BANGUMI -> "https://bgm.tv/subject/$id"
        TrackerProvider.SHIKIMORI -> "https://shikimori.one/mangas/$id"; TrackerProvider.MANGABAKA -> "https://mangabaka.org/$id"; else -> a.baseUrl
    }
    private suspend fun <T> optional404(block: suspend () -> T): T? = try { block() } catch (e: TrackerHttpException) { if (e.status == 404) null else throw e }
    companion object {
        fun mangaBakaPayload(s: TrackState) = JSONObject().put("state", statusName(TrackerProvider.MANGABAKA, s.status)).put("rating", s.score.toInt()).put("progress_chapter", floor(s.chapter).toInt()).put("progress_volume", s.volume)
            .put("start_date", if (s.startDate.isEmpty()) JSONObject.NULL else s.startDate + "T00:00:00.000Z").put("finish_date", if (s.finishDate.isEmpty()) JSONObject.NULL else s.finishDate + "T00:00:00.000Z")
        fun statusName(p: TrackerProvider, s: TrackStatus): String = when (p) {
            TrackerProvider.ANILIST -> listOf("CURRENT", "PLANNING", "COMPLETED", "PAUSED", "DROPPED", "REPEATING")[s.ordinal]
            TrackerProvider.MYANIMELIST -> listOf("reading", "plan_to_read", "completed", "on_hold", "dropped", "reading")[s.ordinal]
            TrackerProvider.BANGUMI -> listOf("3", "1", "2", "4", "5", "3")[s.ordinal]
            TrackerProvider.SHIKIMORI -> listOf("watching", "planned", "completed", "on_hold", "dropped", "rewatching")[s.ordinal]
            else -> listOf("reading", "plan_to_read", "completed", "paused", "dropped", "rereading")[s.ordinal]
        }
        fun status(p: TrackerProvider, s: String?): TrackStatus = TrackStatus.entries.firstOrNull { statusName(p, it) == s } ?: TrackStatus.PLANNING
        fun fuzzy(value: String): JSONObject = if (value.isEmpty()) JSONObject().put("year", 0).put("month", 0).put("day", 0) else LocalDate.parse(value).let { JSONObject().put("year", it.year).put("month", it.monthValue).put("day", it.dayOfMonth) }
        fun fuzzyDate(j: JSONObject?): String = try { if (j == null || j.optInt("year") == 0) "" else LocalDate.of(j.getInt("year"), j.getInt("month"), j.getInt("day")).toString() } catch (_: Exception) { "" }
        private fun timestamp(value: String?): Long = try { if (value.isNullOrBlank()) 0 else java.time.Instant.parse(if (value.endsWith('Z') || Regex("[+-]\\d{2}:\\d{2}$").containsMatchIn(value)) value else value + "Z").toEpochMilli() } catch (_: Exception) { 0 }
    }
}
