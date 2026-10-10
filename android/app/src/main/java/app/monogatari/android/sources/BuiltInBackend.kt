package app.monogatari.android.sources

import app.monogatari.android.data.*
import app.monogatari.android.ui.Localization
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneOffset

/** Server-native implementations of the same SourceSession contract supplied by extension packages. */
class BuiltInBackend(private val repo: AppRepository, private val source: SourceConfig, private val client: SourceClient, providedPreferences: SourcePreferences? = null) : SourceSession {
    override val sourceId = source.id
    override val legacy = false
    override val exports = if (source.type in setOf("komga", "kavita")) setOf("get_home", "get_search_manga_list", "get_manga_list", "get_settings") else setOf("get_search_manga_list", "get_settings")
    override val preferences = providedPreferences ?: SourcePreferences(repo.context, sourceId)
    private val kavita get() = client.kavita()
    private var workingMirror: String? = null
    private var komgaTags: List<String>? = null
    private var kavitaGenres: List<JSONObject>? = null
    private var kavitaTags: List<JSONObject>? = null
    private fun tr(key: String) = Localization.string(repo.context, key)
    init {
        if (preferences.get("name") == null) preferences.set("name", source.name)
        if (preferences.get("server") == null) preferences.set("server", source.baseUrl)
    }
    private fun config() = source.copy(name = preferences.get("name")?.toString() ?: source.name,
        baseUrl = preferences.get("server")?.toString() ?: source.baseUrl)
    private fun request(path: String, body: JSONObject? = null): String {
        val current = config()
        val mirrors = (preferences.get("mirrors") as? JSONArray).strings()
        val urls = (listOfNotNull(workingMirror, current.baseUrl) + mirrors).distinct()
        var failure: Exception? = null
        for (url in urls) try {
            val copy = current.copy(baseUrl = url)
            val timeout = if(mirrors.isNotEmpty()) 5L else null
            val result = when (source.type) { "kavita" -> client.kavita(timeout).request(copy, path, body); "suwayomi" -> client.suwayomi(copy, preferences).request(copy, body ?: error("缺少GraphQL请求")); else -> client.request(copy, path, body,timeout) }
            workingMirror = url; return result
        } catch (e: java.io.IOException) { failure = e; workingMirror=null }
        throw failure ?: IllegalStateException("Server is not configured")
    }
    override fun baseUrl() = workingMirror ?: config().baseUrl
    override fun search(query: String, page: Int, filters: List<JSONObject>): Pair<List<Manga>, Boolean> = when (source.type) {
        "local" -> repo.state.value.manga.filter { it.sourceId == "local" && (it.title.contains(query, true) || it.author.contains(query, true)) } to false
        "komga" -> {
            val (sort, conditions) = komgaConditions(filters)
            val body = JSONObject().put("condition", JSONObject().put("allOf", JSONArray(conditions)))
            if (query.isNotBlank()) body.put("fullTextSearch", query)
            val result = JSONObject(request("api/v1/series/list?page=${page - 1}&size=20&sort=$sort", body))
            result.getJSONArray("content").objects().map(::komgaSeries) to (result.optInt("totalPages") > page)
        }
        "kavita" -> {
            val body = kavitaFilter(query, filters)
            JSONArray(request("api/series/v2", body)).objects().map { kavita.manga(config().copy(baseUrl = baseUrl()), it) } to false
        }
        "suwayomi" -> suwayomiSearch(query, filters)
        else -> error("Unsupported native source ${source.type}")
    }
    override fun listing(listing: JSONObject, page: Int): Pair<List<Manga>, Boolean> {
        val id = listing.getString("id")
        if (source.type == "komga") return komgaListing(id, page)
        if (source.type == "kavita") {
            val (path, body) = when {
                id == "on_deck" -> "api/series/on-deck?libraryId=0&pageNumber=$page&pageSize=20" to JSONObject()
                id == "recently_added" -> "api/series/recently-added-v2?pageNumber=$page&pageSize=20" to JSONObject()
                id == "recently_updated" -> "api/series/recently-updated-series" to JSONObject()
                id.startsWith("library-") -> "api/series/v2" to filterBody(JSONArray().put(statement(19, 0, id.removePrefix("library-"))))
                id.startsWith("morein-") -> "api/recommended/more-in?libraryId=0&genreId=${id.removePrefix("morein-")}&pageNumber=$page&pageSize=20" to null
                id.startsWith("filter-") -> {
                    val filter = JSONObject(request("api/filter/decode", JSONObject().put("encodedFilter", id.removePrefix("filter-"))))
                    "api/series/all-v2?context=4&pageNumber=$page&pageSize=20" to filter
                }
                else -> error("Unknown Kavita listing $id")
            }
            val array = JSONArray(request(path, body)).objects()
            return array.map { raw ->
                val series = if (id == "recently_updated") JSONObject().put("id", raw.get("seriesId")).put("libraryId", raw.get("libraryId")).put("name", raw.getString("seriesName")) else raw
                kavita.manga(config().copy(baseUrl = baseUrl()), series)
            } to (array.size == 20 && id != "recently_updated" && !id.startsWith("library-"))
        }
        return search("", page)
    }
    private fun listingValue(id: String, title: String) = JSONObject().put("id", id).put("name", title).put("kind", 0)
    override fun listings(): List<JSONObject> = when (source.type) {
        "komga" -> JSONArray(request("api/v1/libraries")).objects().map { listingValue("library-${it.get("id")}", it.getString("name")) }
        "kavita" -> JSONArray(request("api/library/libraries")).objects().map { listingValue("library-${it.get("id")}", it.getString("name")) }
        else -> emptyList()
    }
    override fun listingHome(listing: JSONObject): JSONObject? {
        if (source.type != "komga") return null
        val id = listing.optString("id")
        if (!id.startsWith("library-") || listOf("keep_reading", "on_deck", "recently_added_books", "recently_added_series", "recently_updated_series", "recently_read_books").any { id.endsWith("-$it") }) return null
        val components = JSONArray()
        listOf("keep_reading" to "KEEP_READING", "on_deck" to "ON_DECK", "recently_added_books" to "RECENTLY_ADDED_BOOKS", "recently_added_series" to "RECENTLY_ADDED_SERIES", "recently_updated_series" to "RECENTLY_UPDATED_SERIES").forEach { (section, title) ->
            val child = listingValue("$id-$section", tr(title)); val manga = komgaListing(child.getString("id"), 1).first
            if (manga.isNotEmpty()) components.put(scroller(tr(title), child, manga))
        }
        return JSONObject().put("components", components)
    }
    fun details(manga: Manga): Manga = when (source.type) {
        "kavita" -> {
            val mirrors = (listOfNotNull(workingMirror, config().baseUrl) + (preferences.get("mirrors") as? JSONArray).strings()).distinct()
            var result: Manga? = null; var failure: Exception? = null
            for (mirror in mirrors) { try { result = kavita.details(config().copy(baseUrl = mirror), manga); workingMirror = mirror; break } catch (e: Exception) { failure = e } }
            result ?: throw (failure ?: IllegalStateException("Server is not configured"))
        }
        "komga" -> {
            val series = komgaSeries(JSONObject(request("api/v1/series/${manga.key}")))
            val body = JSONObject().put("condition", JSONObject().put("allOf", JSONArray().put(condition("seriesId", "is", manga.key)).put(condition("deleted", "isFalse"))))
            val books = JSONObject(request("api/v1/books/list?unpaged=true&sort=metadata.numberSort%2Cdesc", body)).getJSONArray("content").objects()
            series.copy(chapters = books.filter { book -> book.optJSONObject("media")?.let { it.optString("mediaProfile") != "EPUB" || it.optBoolean("epubDivinaCompatible") } ?: true }.mapIndexed { index, book -> komgaChapter(book).copy(sourceOrder = index) })
        }
        "suwayomi" -> {
            val payload = JSONObject().put("query", "query GetMangaUpdate(\$mangaId:Int!) { manga(id:\$mangaId) { id title artist author description status thumbnailUrl url realUrl genre } chapters(condition:{mangaId:\$mangaId},orderBy:SOURCE_ORDER,orderByType:DESC) { nodes { id url chapterNumber name uploadDate scanlator sourceOrder mangaId } } }").put("variables", JSONObject().put("mangaId", manga.key.toInt()))
            val data = JSONObject(request("api/graphql", payload)).getJSONObject("data")
            suwayomiManga(data.getJSONObject("manga")).copy(chapters = data.getJSONObject("chapters").getJSONArray("nodes").objects().map { j ->
                Chapter(j.get("id").toString(), j.stringOrNull("name").orEmpty(), (j.value("chapterNumber") as? Number)?.toDouble() ?: 0.0, scanlator = j.stringOrNull("scanlator").orEmpty(),
                    dateUploaded = j.stringOrNull("uploadDate")?.toDoubleOrNull()?.let { if (it > 10_000_000_000) it.toLong() else (it * 1000).toLong() } ?: 0,
                    url = j.stringOrNull("url")?.let { client.url(config().copy(baseUrl = baseUrl()), it) }.orEmpty(), sourceOrder = j.optInt("sourceOrder"), metadata = j.toString())
            })
        }
        else -> manga
    }
    private fun komgaChapter(book: JSONObject): Chapter {
        val m = book.optJSONObject("metadata") ?: JSONObject(); val id = book.getString("id")
        val number = (m.value("numberSort") as? Number)?.toDouble(); val useChapters = preferences.get("useChapters") == true
        val raw = JSONObject(book.toString()).put("chapterNumber", if (useChapters) number else JSONObject.NULL).put("volumeNumber", if (useChapters) JSONObject.NULL else number)
        val current = config().copy(baseUrl = baseUrl())
        return Chapter(id, m.optString("title").ifBlank { book.optString("name") }, if (useChapters) number ?: 0.0 else 0.0,
            total = book.optJSONObject("media")?.optInt("pagesCount") ?: 0, volume = if (useChapters) null else number, language = "en",
            scanlator = m.optJSONArray("authors").objects().filter { it.optString("role") == "translator" }.joinToString(", ") { it.optString("name") },
            dateUploaded = timestamp(m.stringOrNull("releaseDate") ?: m.optString("created")), url = client.url(current, "book/$id"), thumbnail = client.url(current, "api/v1/books/$id/thumbnail"), metadata = raw.toString())
    }
    fun pages(chapter: Chapter): List<String> = when (source.type) {
        "kavita" -> {
            val mirrors = (listOfNotNull(workingMirror, config().baseUrl) + (preferences.get("mirrors") as? JSONArray).strings()).distinct()
            var result: List<String>? = null; var failure: Exception? = null
            for (mirror in mirrors) { try { result = kavita.pages(config().copy(baseUrl = mirror), chapter); workingMirror = mirror; break } catch (e: Exception) { failure = e } }
            result ?: throw (failure ?: IllegalStateException("Server is not configured"))
        }
        "komga" -> {
            val result = JSONArray(request("api/v1/books/${chapter.key}/pages")).objects()
            result.map { j -> val convert = if (j.optString("mediaType") in setOf("image/jpeg", "image/png", "image/gif", "image/webp", "image/avif")) "" else "?convert=png"
                client.url(config().copy(baseUrl = baseUrl()), "api/v1/books/${chapter.key}/pages/${j.getInt("number")}$convert") }
        }
        "suwayomi" -> {
            val body = JSONObject().put("query", "mutation GetPages(\$chapterId:Int!) { fetchChapterPages(input:{chapterId:\$chapterId}) { pages } }").put("variables", JSONObject().put("chapterId", chapter.key.toInt()))
            JSONObject(request("api/graphql", body)).getJSONObject("data").getJSONObject("fetchChapterPages").getJSONArray("pages").strings().map { client.url(config().copy(baseUrl = baseUrl()), it) }
        }
        else -> chapter.pages
    }
    private fun timestamp(value: String): Long = runCatching { java.time.Instant.parse(if (value.endsWith('Z') || Regex("[+-]\\d\\d:\\d\\d$").containsMatchIn(value)) value else value + "Z").toEpochMilli() }
        .getOrElse { runCatching { LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrDefault(0) }
    override fun home(): JSONObject {
        val components = JSONArray()
        if (source.type == "komga") {
            listOf("keep_reading" to "KEEP_READING", "on_deck" to "ON_DECK", "recently_added_books" to "RECENTLY_ADDED_BOOKS",
                "recently_added_series" to "RECENTLY_ADDED_SERIES", "recently_updated_series" to "RECENTLY_UPDATED_SERIES", "recently_read_books" to "RECENTLY_READ_BOOKS").forEach { (id, title) ->
                val listing = listingValue(id, tr(title)); val manga = komgaListing(id, 1).first
                if (manga.isNotEmpty()) components.put(scroller(tr(title), listing, manga))
            }
        } else if (source.type == "kavita") JSONArray(request("api/stream/dashboard?visibleOnly=true")).objects().forEach { section ->
            val (id, title) = when (section.getInt("streamType")) {
                1 -> "on_deck" to tr("ON_DECK"); 2 -> "recently_updated" to tr("RECENTLY_UPDATED_SERIES"); 3 -> "recently_added" to tr("RECENTLY_ADDED_SERIES")
                4 -> "filter-${section.optString("smartFilterEncoded")}" to section.optString("name")
                5 -> {
                    val genres = JSONArray(request("api/metadata/genres?context=4")).objects()
                    val genre = genres.randomOrNull() ?: return@forEach
                    "morein-${genre.get("id")}" to Localization.string(repo.context, "MORE_IN_%@", genre.getString("title"))
                }
                else -> return@forEach
            }
            val listing = listingValue(id, title); val manga = listing(listing, 1).first
            if (manga.isNotEmpty()) components.put(scroller(title, listing, manga))
        }
        return JSONObject().put("components", components)
    }
    private fun scroller(title: String, listing: JSONObject, entries: List<Manga>) = JSONObject().put("type", 2).put("title", title).put("listing", listing)
        .put("entries", JSONArray(entries.map { m -> val metadata = JSONObject(m.metadata); JSONObject().put("title", m.title).put("imageUrl", m.cover).put("subtitle", metadata.stringOrNull("homeSubtitle") ?: if (metadata.has("booksCount")) { val count = metadata.optInt("booksCount"); if (count == 1) tr("1_BOOK") else Localization.string(repo.context, "%@_BOOKS", count.toString()) } else m.author)
            .put("value", JSONObject().put("type", 2).put("data", m.json())) }))
    override fun manga(raw: JSONObject): Manga = if (raw.has("sourceId") && raw.has("key")) Manga.parse(raw) else when (source.type) {
        "komga" -> komgaSeries(raw); "kavita" -> kavita.manga(config(), raw); else -> error("Invalid manga")
    }
    private fun komgaSeries(raw: JSONObject): Manga {
        val m = raw.optJSONObject("metadata") ?: JSONObject()
        val bookMetadata = raw.optJSONObject("booksMetadata") ?: JSONObject()
        val authors = bookMetadata.optJSONArray("authors").objects().ifEmpty { m.optJSONArray("authors").objects() }
        val key = raw.get("id").toString()
        return Manga(key, sourceId, m.optString("title").ifBlank { raw.optString("name") },
            client.url(config().copy(baseUrl = baseUrl()), "api/v1/series/$key/thumbnail"),
            authors.filter { it.optString("role") == "writer" }.map { it.optString("name") }.distinct().joinToString(", "),
            m.optString("summary").ifEmpty { bookMetadata.optString("summary") }.replace("\n", "  \n"),
            m.optJSONArray("genres").strings().filter(String::isNotBlank).sorted() + m.optJSONArray("tags").strings().filter(String::isNotBlank).sorted() + (bookMetadata.optJSONArray("tags").strings() - m.optJSONArray("tags").strings().toSet()).filter(String::isNotBlank).distinct().sorted(),
            when (m.optString("status")) { "ONGOING" -> 1; "ENDED" -> 2; "ABANDONED" -> 3; "HIATUS" -> 4; else -> 0 },
            artist = authors.filter { it.optString("role") == "penciller" }.map { it.optString("name") }.distinct().joinToString(", "),
            url = client.url(config().copy(baseUrl = baseUrl()), "series/$key"), language = m.optString("language"),
            contentRating = when { m.optInt("ageRating") >= 18 -> 3; m.optInt("ageRating") >= 16 -> 2; else -> 1 },
            viewer = when (m.optString("readingDirection")) { "LEFT_TO_RIGHT" -> "ltr"; "RIGHT_TO_LEFT" -> "rtl"; "VERTICAL" -> "vertical"; "SCROLL", "WEBTOON" -> "webtoon"; else -> "auto" }, metadata = raw.toString())
    }
    private fun komgaBook(raw: JSONObject): Manga {
        val id = raw.getString("seriesId")
        val series = JSONObject(request("api/v1/series/$id"))
        val metadata = raw.optJSONObject("metadata") ?: JSONObject(); val seriesManga = komgaSeries(series)
        return seriesManga.copy(cover = client.url(config().copy(baseUrl = baseUrl()), "api/v1/books/${raw.getString("id")}/thumbnail"),
            author = metadata.optJSONArray("authors").objects().map { it.optString("name") }.joinToString(", "), tags = metadata.optJSONArray("tags").strings(),
            metadata = JSONObject(series.toString()).put("openChapter", raw.getString("id")).put("homeSubtitle", "${metadata.optString("number")} - ${metadata.optString("title").ifBlank { raw.optString("name") }}").toString())
    }
    private fun komgaListing(id: String, page: Int): Pair<List<Manga>, Boolean> {
        val conditions = mutableListOf(condition("deleted", "isFalse"))
        val suffixes = listOf("keep_reading", "on_deck", "recently_added_books", "recently_added_series", "recently_updated_series", "recently_read_books")
        val section = suffixes.firstOrNull { id.startsWith("library-") && id.endsWith("-$it") }
        val actualId = section ?: id
        val library = if (id.startsWith("library-")) id.removePrefix("library-").let { if (section != null) it.removeSuffix("-$section") else it } else null
        if (library != null) conditions += condition("libraryId", "is", library)
        val libraryParameter = if (library != null) "&library_id=${java.net.URLEncoder.encode(library, "UTF-8")}" else ""
        var body: JSONObject? = null; var book = false
        val path = when (actualId) {
            "keep_reading", "recently_read_books", "recently_added_books" -> {
                book = true
                if (actualId != "recently_added_books") conditions += condition("readStatus", "is", if (actualId == "keep_reading") "IN_PROGRESS" else "READ")
                body = JSONObject().put("condition", JSONObject().put("allOf", JSONArray(conditions)))
                "api/v1/books/list?page=${page - 1}&size=20&sort=${if (actualId == "recently_added_books") "createdDate" else "readProgress.readDate"}%2Cdesc"
            }
            "on_deck" -> { book = true; "api/v1/books/ondeck?page=${page - 1}&size=20&sort=createdDate%2Cdesc$libraryParameter" }
            "recently_added_series" -> "api/v1/series/new?page=${page - 1}&size=20&oneshot=false&deleted=false$libraryParameter"
            "recently_updated_series" -> "api/v1/series/updated?page=${page - 1}&size=20&oneshot=false&deleted=false$libraryParameter"
            else -> {
                require(library != null) { "Unknown Komga listing $id" }
                body = JSONObject().put("condition", JSONObject().put("allOf", JSONArray(conditions)))
                "api/v1/series/list?page=${page - 1}&size=20&sort=metadata.titleSort%2Casc"
            }
        }
        val result = JSONObject(request(path, body))
        return result.getJSONArray("content").objects().map { if (book) komgaBook(it) else komgaSeries(it) } to (result.optInt("totalPages") > page)
    }
    private fun condition(key: String, operator: String, value: Any? = null) = JSONObject().put(key, JSONObject().put("operator", operator).apply { if (value != null) put("value", value) })
    private fun komgaConditions(filters: List<JSONObject>): Pair<String, List<JSONObject>> {
        val result = mutableListOf(condition("deleted", "isFalse")); var sort = "metadata.titleSort%2Casc"
        val fields = mapOf("library" to "libraryId", "genre" to "genre", "tag" to "tag", "publisher" to "publisher", "language" to "language", "sharing_label" to "sharingLabel", "status" to "seriesStatus", "age_rating" to "ageRating")
        filters.forEach { f ->
            val id = f.optString("id")
            when (f.optInt("type")) {
                0 -> if (id in setOf("author", "artist") && f.optString("value").isNotBlank()) {
                    val authors = JSONObject(request("api/v2/authors?search=${java.net.URLEncoder.encode(f.optString("value"), "UTF-8")}&role=${if (id == "author") "writer" else "penciller"}")).optJSONArray("content").objects()
                    if (authors.isNotEmpty()) result += JSONObject().put("anyOf", JSONArray(authors.map { condition("author", "is", JSONObject().put("name", it.getString("name")).put("role", it.getString("role"))) }))
                }
                1 -> {
                    val sorts = listOf("metadata.titleSort", "createdDate", "lastModifiedDate", "readDate", "booksMetadata.releaseDate", "name", "booksCount", "random")
                    sort = sorts.getOrElse(f.optInt("index")) { sorts.first() } + "%2C" + if (f.optBoolean("ascending")) "asc" else "desc"
                }
                4 -> {
                    val included = f.optJSONArray("included").strings(); val excluded = f.optJSONArray("excluded").strings()
                    fun one(value: String, exclude: Boolean): JSONObject? { return if (id == "release_date") {
                        if (value.isBlank()) condition("releaseDate", if (exclude) "isNull" else "isNotNull")
                        else {
                            val year = value.toIntOrNull() ?: return null
                            fun first(y: Int) = LocalDate.of(y, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC)
                            val all = if (exclude) JSONArray()
                                .put(JSONObject().put("releaseDate", JSONObject().put("operator", "before").put("dateTime", first(year).toString())))
                                .put(JSONObject().put("releaseDate", JSONObject().put("operator", "after").put("dateTime", first(year + 1).minusSeconds(1).toString())))
                                .put(condition("releaseDate", "isNull"))
                                else JSONArray().put(JSONObject().put("releaseDate", JSONObject().put("operator", "after").put("dateTime", first(year).minusSeconds(1).toString())))
                                .put(JSONObject().put("releaseDate", JSONObject().put("operator", "before").put("dateTime", first(year + 1).toString())))
                            JSONObject().put(if (exclude) "anyOf" else "allOf", all)
                        }
                    } else fields[id]?.let { field -> when {
                        id == "age_rating" && value == "None" -> condition(field, if (exclude) "isNotNull" else "isNull")
                        value.isEmpty() && id in setOf("genre", "tag", "sharing_label") -> condition(field, if (exclude) "isNull" else "isNotNull")
                        else -> condition(field, if (if (value.isEmpty()) !exclude else exclude) "isNot" else "is", if (id == "age_rating") value.toIntOrNull() else value)
                    } } }
                    val inc = included.mapNotNull { one(it, false) }; val exc = excluded.mapNotNull { one(it, true) }
                    if (inc.isNotEmpty() || exc.isNotEmpty()) result += JSONObject().put(if (exc.isEmpty()) "anyOf" else "allOf", JSONArray(inc + exc))
                }
                3 -> if (id == "genre" && f.optString("value").isNotBlank()) {
                    val value = f.optString("value"); val tags = komgaTags ?: JSONArray(request("api/v1/tags")).strings().also { komgaTags = it }
                    result += JSONObject().put("anyOf", JSONArray().put(condition(if (value in tags) "tag" else "genre", "is", value)))
                }
            }
        }; return sort to result
    }
    private fun statement(field: Int, comparison: Int, value: String) = JSONObject().put("field", field).put("comparison", comparison).put("value", value)
    private fun filterBody(statements: JSONArray, sort: JSONObject? = null) = JSONObject().put("statements", statements).put("combination", 1).put("sortOptions", sort).put("limitTo", 0)
    private fun kavitaFilter(query: String, filters: List<JSONObject>): JSONObject {
        val statements = JSONArray(); var sort: JSONObject? = null
        if (query.isNotBlank()) statements.put(statement(1, 7, query))
        filters.forEach { f -> val id = f.optString("id"); when (f.optInt("type")) {
            0 -> if (id in setOf("author", "artist") && f.optString("value").isNotBlank()) {
                val people = JSONArray(request("api/metadata/people-by-role?role=${if (id == "author") 3 else 4}")).objects()
                people.firstOrNull { it.optString("name") == f.optString("value") }?.let { statements.put(statement(if (id == "author") 17 else 16, 0, it.get("id").toString())) }
            }
            1 -> sort = JSONObject().put("sortField", f.optInt("index") + 1).put("isAscending", f.optBoolean("ascending"))
            4 -> id.toIntOrNull()?.let { field ->
                f.optJSONArray("included").strings().takeIf { it.isNotEmpty() }?.let { statements.put(statement(field, 5, it.joinToString(","))) }
                f.optJSONArray("excluded").strings().takeIf { it.isNotEmpty() }?.let { statements.put(statement(field, 8, it.joinToString(","))) }
            }
            3 -> if (id == "genre" && f.optString("value").isNotBlank()) {
                val value = f.getString("value")
                val genres = kavitaGenres ?: JSONArray(request("api/metadata/genres?context=1")).objects().also { kavitaGenres = it }
                val tags = kavitaTags ?: JSONArray(request("api/metadata/tags")).objects().also { kavitaTags = it }
                genres.firstOrNull { it.optString("title") == value }?.let { statements.put(statement(18, 5, it.get("id").toString())) }
                    ?: tags.firstOrNull { it.optString("title") == value }?.let { statements.put(statement(6, 5, it.get("id").toString())) }
            }
        } }
        return filterBody(statements, sort)
    }
    private fun suwayomiSearch(query: String, filters: List<JSONObject>): Pair<List<Manga>, Boolean> {
        val conditions = JSONArray()
        if (query.isNotBlank()) conditions.put(JSONObject().put("or", JSONArray(listOf("title", "url", "artist", "author", "description").map { JSONObject().put(it, JSONObject().put("includesInsensitive", query)) })))
        var sortIndex = 3; var ascending = false
        filters.forEach { f -> val id = f.optString("id"); when (f.optInt("type")) {
            0 -> if (id in setOf("author", "artist") && f.optString("value").isNotBlank()) conditions.put(JSONObject().put(id, JSONObject().put("includesInsensitive", f.optString("value"))))
            1 -> { sortIndex = f.optInt("index", 3).coerceIn(0, 6); ascending = f.optBoolean("ascending") }
            3 -> if (id == "category") { val value = f.optString("value"); if (value == "0") conditions.put(JSONObject().put("categoryId", JSONObject().put("isNull", true))) else value.toIntOrNull()?.let { conditions.put(JSONObject().put("categoryId", JSONObject().put("equalTo", it))) } }
                else if (id == "genre" && f.optString("value").isNotBlank()) conditions.put(JSONObject().put("genre", JSONObject().put("includesInsensitive", f.optString("value"))))
            4 -> {
                val included = f.optJSONArray("included").strings(); val excluded = f.optJSONArray("excluded").strings()
                if (id == "status") {
                    if (included.isNotEmpty()) conditions.put(JSONObject().put("status", JSONObject().put("in", JSONArray(included))))
                    if (excluded.isNotEmpty()) conditions.put(JSONObject().put("status", JSONObject().put("notEqualToAny", JSONArray(excluded))))
                } else if (id == "genre") {
                    included.forEach { conditions.put(JSONObject().put("genre", JSONObject().put("includesInsensitive", it))) }
                    excluded.forEach { conditions.put(JSONObject().put("genre", JSONObject().put("notIncludesInsensitive", it))) }
                }
            }
        } }
        val serverSort = mapOf(2 to "TITLE", 3 to "IN_LIBRARY_AT", 5 to "LAST_FETCHED_AT")[sortIndex]
        val variables = JSONObject().put("filter", if (conditions.length() == 0) JSONObject.NULL else JSONObject().put("and", conditions))
        if (serverSort != null) variables.put("orderBy", serverSort).put("orderByType", if (ascending) "ASC" else "DESC")
        val payload = JSONObject().put("query", "query SearchManga(\$filter: MangaFilterInput, \$orderBy: MangaOrderBy, \$orderByType: SortOrder) { mangas(condition:{inLibrary:true},filter:\$filter,orderBy:\$orderBy,orderByType:\$orderByType){nodes{id title artist author description genre status realUrl url thumbnailUrl inLibraryAt chapters{totalCount} latestUploadedChapter{uploadDate} latestFetchedChapter{fetchedAt} lastReadChapter{lastReadAt} unreadCount downloadCount}} }")
            .put("variables", variables)
        val result = JSONObject(request("api/graphql", payload)); result.optJSONArray("errors")?.takeIf { it.length() > 0 }?.let { error(it.toString()) }
        var nodes = result.getJSONObject("data").getJSONObject("mangas").getJSONArray("nodes").objects()
        if (serverSort == null) { nodes = nodes.sortedBy { j -> when (sortIndex) {
            0 -> j.optLong("unreadCount"); 1 -> j.optJSONObject("chapters")?.optLong("totalCount") ?: 0
            4 -> j.optJSONObject("lastReadChapter")?.optString("lastReadAt")?.toLongOrNull() ?: 0
            6 -> j.optJSONObject("latestUploadedChapter")?.optString("uploadDate")?.toLongOrNull() ?: 0
            else -> 0
        } }; if (!ascending) nodes = nodes.reversed() }
        return nodes.filter { !it.isNull("id") && !it.isNull("title") }.map(::suwayomiManga) to false
    }
    private fun suwayomiManga(j: JSONObject): Manga = Manga(j.get("id").toString(), sourceId, j.getString("title"),
        j.stringOrNull("thumbnailUrl")?.let { client.url(config().copy(baseUrl = baseUrl()), it) }.orEmpty(), j.stringOrNull("author").orEmpty(), j.stringOrNull("description").orEmpty(), j.optJSONArray("genre").strings(),
        status = when (j.optString("status")) { "ONGOING" -> 1; "COMPLETED", "PUBLISHING_FINISHED" -> 2; "CANCELLED" -> 3; "HIATUS", "ON_HIATUS" -> 4; else -> 0 },
        artist = j.stringOrNull("artist").orEmpty(), url = (j.stringOrNull("realUrl") ?: j.stringOrNull("url"))?.let { client.url(config().copy(baseUrl = baseUrl()), it) }.orEmpty(), metadata = j.toString())
    private fun textFilter(id: String, title: String) = JSONObject().put("id", id).put("type", "text").put("title", tr(title))
    private fun multiFilter(id: String, title: String, values: List<String>, ids: List<String> = values) = JSONObject().put("id", id).put("type", "multi-select")
        .put("title", tr(title)).put("options", JSONArray(values)).put("ids", JSONArray(ids)).put("canExclude", true)
    override fun filters(): List<JSONObject> {
        val result = mutableListOf(textFilter("author", "AUTHOR"), textFilter("artist", "ARTIST"))
        val sorts = if (source.type == "komga") listOf("SORT_NAME", "SORT_DATE_ADDED", "SORT_DATE_UPDATED", "SORT_DATE_READ", "SORT_RELEASE_DATE", "SORT_FOLDER_NAME", "SORT_BOOKS_COUNT", "SORT_RANDOM")
            else if (source.type == "kavita") listOf("SORT_NAME", "SORT_DATE_ADDED", "SORT_DATE_UPDATED", "SORT_CHAPTER_ADDED", "SORT_TIME_TO_READ", "SORT_RELEASE_DATE", "SORT_READ_PROGRESS", "SORT_AVERAGE_RATING", "SORT_RANDOM")
            else listOf("SORT_UNREAD_CHAPTERS", "SORT_TOTAL_CHAPTERS", "SORT_TITLE", "SORT_DATE_ADDED", "SORT_LAST_READ", "SORT_LAST_UPDATED", "SORT_CHAPTER_ADDED")
        if (source.type == "local") return emptyList()
        result += JSONObject().put("id", "sort").put("type", "sort").put("title", tr("SORT")).put("options", JSONArray(sorts.map(::tr))).put("canAscend", true).put("default", JSONObject().put("index", if (source.type == "suwayomi") 3 else 0).put("ascending", source.type != "suwayomi"))
        if (source.type == "komga") {
            result += multiFilter("status", "STATUS", listOf("STATUS_COMPLETED", "STATUS_ONGOING", "STATUS_CANCELLED", "STATUS_HIATUS").map(::tr), listOf("ENDED", "ONGOING", "ABANDONED", "HIATUS"))
            val libraries = JSONArray(request("api/v1/libraries")).objects()
            if (libraries.size > 1) result += multiFilter("library", "LIBRARY", listOf(tr("ALL")) + libraries.map { it.getString("name") }, listOf("") + libraries.map { it.getString("id") })
            listOf("genres" to ("genre" to "GENRE"), "tags" to ("tag" to "TAGS"), "publishers" to ("publisher" to "PUBLISHER"),
                "languages" to ("language" to "LANGUAGE"), "age-ratings" to ("age_rating" to "AGE_RATING"), "series/release-dates" to ("release_date" to "RELEASE_DATE"), "sharing-labels" to ("sharing_label" to "SHARING_LABEL")).forEach { (path, field) ->
                val values = JSONArray(request("api/v1/$path")).let { a -> (0 until a.length()).map { if (a.isNull(it)) "None" else a.get(it).toString() } }
                if (field.first == "tag") komgaTags = values
                if (values.isNotEmpty()) {
                    val ids = if (field.first == "age_rating") values else listOf("") + values
                    val titles = if (field.first == "language") listOf(tr("ANY")) + values.map { "${java.util.Locale.forLanguageTag(it).getDisplayName(java.util.Locale.getDefault())} ($it)" }
                        else if (field.first == "age_rating") values else listOf(tr("ANY")) + values
                    result += multiFilter(field.first, field.second, titles, ids)
                }
            }
        } else if (source.type == "kavita") {
            result += multiFilter("2", "STATUS", listOf("STATUS_ONGOING", "STATUS_HIATUS", "STATUS_COMPLETED", "STATUS_CANCELLED").map(::tr), listOf("0", "1", "2,4", "3"))
            listOf("library/libraries" to ("19" to "LIBRARY"), "metadata/genres?context=1" to ("18" to "GENRE"), "metadata/tags" to ("6" to "TAGS"), "metadata/people-by-role?role=10" to ("10" to "PUBLISHER"), "metadata/languages" to ("3" to "LANGUAGE"), "metadata/age-ratings" to ("4" to "AGE_RATING")).forEach { (path, field) ->
                val values = JSONArray(request("api/$path"))
                val labels = (0 until values.length()).map { values.optJSONObject(it)?.let { j -> j.optString("name", j.optString("title")) } ?: values.get(it).toString() }
                val ids = (0 until values.length()).map { values.optJSONObject(it)?.let { j -> j.stringOrNull("isoCode") ?: j.value("id")?.toString() ?: j.value("value")?.toString() ?: "0" } ?: values.get(it).toString() }
                if (field.first == "18") kavitaGenres = values.objects(); if (field.first == "6") kavitaTags = values.objects()
                if (labels.isNotEmpty()) result += multiFilter(field.first, field.second, labels, ids)
            }
        } else if (source.type == "suwayomi") {
            result += multiFilter("status", "STATUS", listOf("UNKNOWN", "STATUS_ONGOING", "STATUS_COMPLETED", "STATUS_LICENSED", "STATUS_PUBLISHING_FINISHED", "STATUS_CANCELLED", "STATUS_HIATUS").map(::tr), listOf("UNKNOWN", "ONGOING", "COMPLETED", "LICENSED", "PUBLISHING_FINISHED", "CANCELLED", "ON_HIATUS"))
            val categories = JSONObject(request("api/graphql", JSONObject().put("query", "query GetCategories(\$orderBy:CategoryOrderBy,\$orderByType:SortOrder){categories(orderBy:\$orderBy,orderByType:\$orderByType){nodes{id name}}}").put("variables", JSONObject().put("orderBy", "ORDER").put("orderByType", "ASC"))))
                .getJSONObject("data").getJSONObject("categories").getJSONArray("nodes").objects()
            if (categories.size > 1) result += JSONObject().put("type", "select").put("id", "category").put("title", tr("CATEGORY")).put("options", JSONArray(listOf(tr("ALL"), tr("NONE")) + categories.map { it.getString("name") }))
                .put("ids", JSONArray(listOf("", "0") + categories.map { it.get("id").toString() })).put("default", "")
            val tags = JSONObject(request("api/graphql", JSONObject().put("query", "query GetMangaTags { mangas(condition:{inLibrary:true}) { nodes { genre } } }"))).getJSONObject("data").getJSONObject("mangas").getJSONArray("nodes").objects()
                .flatMap { it.optJSONArray("genre").strings() }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
            if (tags.isNotEmpty()) result += multiFilter("genre", "TAG", tags)
        }
        return result
    }
    override fun settings(): List<JSONObject> {
        fun text(key: String, title: String, default: String, notification: String) = JSONObject().put("key", key).put("title", title).put("type", "text").put("default", default).put("notification", notification).put("refreshes", JSONArray(listOf("settings", "content")))
        if (source.type == "local") return listOf(JSONObject().put("type", "note").put("text", "LOCAL_FILE_IMPORT_TEXT"))
        fun group(title: String, items: List<JSONObject>, footer: String = "") = JSONObject().put("type", "group").put("title", title).put("items", JSONArray(items)).put("footer", footer)
        fun login(key: String, title: String, method: String = "basic") = JSONObject().put("key", key).put("title", title).put("type", "login").put("method", method).put("notification", key).put("requires", "server")
            .put("refreshes", JSONArray(listOf("content", "listings", "filters", "settings")))
        val settings = mutableListOf(group("SOURCE_NAME", listOf(text("name", "SOURCE_NAME", source.name, "name_change"))),
            group("SERVER_URL", listOf(text("server", "SERVER_URL", source.baseUrl, "server_change").put("keyboardType", 3).put("autocapitalizationType", 0)), "SERVER_URL_INFO"))
        if (source.type in setOf("komga", "kavita") && config().baseUrl.isNotBlank()) settings += group("MIRRORS", listOf(JSONObject().put("key", "mirrors").put("title", "MIRRORS").put("type", "editable-list").put("lineLimit", 1).put("inline", true).put("notification", "mirrors_changed").put("default", JSONArray())), "MIRRORS_INFO")
        when (source.type) {
            "komga" -> { settings += group("", listOf(login("login", "LOGIN").put("useEmail", true)))
                settings += group("OTHER_SETTINGS", listOf(JSONObject().put("key", "useChapters").put("title", "USE_CHAPTERS").put("subtitle", "USE_CHAPTERS_TEXT").put("type", "toggle").put("default", false).put("notification", "chapters_changed"))) }
            "suwayomi" -> if (preferences.get("login") != null || runCatching { client.suwayomi(config(), preferences).loginType(config()) }.getOrNull()?.let { it != SuwayomiClient.LoginType.NONE } == true) settings += group("", listOf(login("login", "LOGIN")))
            "kavita" -> {
                settings += group("", listOf(text("login_key", "API_KEY", "", "login_key").put("secure", true).put("requires", "server")), "API_KEY_LOGIN_INFO")
                val oidc = runCatching { client.http.newBuilder().callTimeout(5, java.util.concurrent.TimeUnit.SECONDS).build().newCall(Request.Builder().url(client.url(config(), "api/settings/oidc")).header("Accept", "application/json").build()).execute().use { r -> check(r.isSuccessful); JSONObject(r.body?.string() ?: "{}") } }.getOrNull()
                if (oidc?.optBoolean("disablePasswordAuthentication") != true && preferences.get("login_oidc") == null) settings += group("", listOf(login("login", "BASIC_LOGIN").put("requiresFalse", "login_oidc")))
                if (oidc?.optBoolean("enabled") == true && preferences.get("login") == null) settings += group("", listOf(login("login_oidc", "LOGIN_VIA_OIDC", "web").put("url", (config().baseUrl.trimEnd('/')+"/").toHttpUrl().resolve("oidc/login")!!.newBuilder().addQueryParameter("returnURL", "aidoku://oidc-auth").build().toString()).put("requiresFalse", "login").put("clearCookiesOnLogOut", true)))
            }
        }
        preferences.initialize(JSONArray(settings)); return settings
    }
    override fun notify(key: String) {
        if (key in setOf("name_change", "server_change")) {
            val settingKey = if (key == "name_change") "name" else "server"
            val normalized = preferences.get(settingKey)?.toString().orEmpty().trim().let { if (settingKey == "server") it.trimEnd('/') else it }
            if (settingKey == "server" && normalized.isNotEmpty()) require(runCatching { normalized.toHttpUrl() }.isSuccess) { "请输入完整的HTTP或HTTPS服务器地址" }
            preferences.set(settingKey, normalized)
            kotlinx.coroutines.runBlocking {
            val updated = config(); repo.change { s -> s.copy(sources = s.sources.map { if (it.id == sourceId) updated else it }) }
            }
        }
        if (key in setOf("login", "login_oidc") && preferences.get(key) == null) {
            client.clearAuthentication(sourceId)
        }
        if (key == "login_key" && source.type == "kavita") {
            val apiKey = preferences.get("login_key")?.toString().orEmpty()
            if (apiKey.isBlank()) client.clearAuthentication(sourceId)
            else { kavita.authenticate(config(), "", apiKey); listOf("login", "login_oidc").forEach { name -> listOf("", ".username", ".password", ".keys", ".values").forEach { preferences.set(name + it, null) } } }
        }
        if (key == "chapters_changed" && source.type == "komga") kotlinx.coroutines.runBlocking { repo.change { state -> state.copy(manga = state.manga.map { manga -> if (manga.sourceId != sourceId) manga else manga.copy(chapters = manga.chapters.map { chapter ->
            val meta = JSONObject(chapter.metadata); val number = (meta.optJSONObject("metadata")?.value("numberSort") as? Number)?.toDouble() ?: chapter.volume ?: chapter.number
            val useChapters = preferences.get("useChapters") == true
            meta.put("chapterNumber", if (useChapters) number else JSONObject.NULL).put("volumeNumber", if (useChapters) JSONObject.NULL else number)
            chapter.copy(number = if (useChapters) number else 0.0, volume = if (useChapters) null else number, metadata = meta.toString())
        }) }) } }
        workingMirror = null
    }
    override fun basicLogin(key: String, username: String, password: String): Boolean = try { client.authenticate(config(), username, password)
        preferences.set("$key.username", username); preferences.set("$key.password", password); preferences.set(key, "logged_in"); if (source.type == "kavita") preferences.set("login_key", null); true } catch (_: Exception) { false }
    override fun webLogin(key: String, cookies: Map<String, String>): Boolean {
        if (source.type != "kavita" || !kavita.webAuthenticate(config(), cookies)) return false
        preferences.set(key, "logged_in"); preferences.set("$key.keys", JSONArray(cookies.keys)); preferences.set("$key.values", JSONArray(cookies.values)); preferences.set("login_key", null)
        return true
    }
    override fun importBrowserCookies(url: String, userAgent: String, cookies: String) {
        val origin = baseUrl().toHttpUrl(); val target = url.toHttpUrl()
        if (origin.scheme == target.scheme && origin.host == target.host && origin.port == target.port) client.saveCookie(sourceId, cookies)
    }
    override fun importCookieRecords(url: String, cookies: List<Cookie>) { importBrowserCookies(url, "", cookies.filter { it.matches(url.toHttpUrl()) }.joinToString("; ") { "${it.name}=${it.value}" }) }
    override fun clearCookies() { client.saveCookie(sourceId, ""); SourceWebProfiles.clear(repo.context, sourceId); client.http.cache?.evictAll() }
    override fun resetSettings() {
        client.clearAuthentication(sourceId)
        preferences.keys().forEach { preferences.set(it,null) }
        preferences.set("server", "")
        preferences.initialize(JSONArray(settings()))
        notify("server_change")
        SourceWebProfiles.clear(repo.context,sourceId)
    }
    override fun fetch(request: Request): HostResponse = client.http.newCall(request).execute().use { response ->
        HostResponse(response.code, response.body?.byteStream()?.use { SourceHost.readBounded(it, 50 * 1024 * 1024) } ?: byteArrayOf(), response.headers.names().associateWith { response.header(it).orEmpty() },
            response.request.url.toString(), request.headers.names().associateWith { request.header(it).orEmpty() })
    }
}
