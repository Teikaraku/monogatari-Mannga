package app.monogatari.android.dictionary

import app.monogatari.android.AppBrand
import app.monogatari.android.ui.Localization

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.text.TextUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** AnkiConnect supports the standard API v6, including models, decks, duplicate checks and media. */
class AnkiConnect(private val endpoint: String, private val apiKey: String = "", private val context: Context? = null) {
    private val http = OkHttpClient()
    suspend fun action(action: String, parameters: JSONObject = JSONObject()): Any? = withContext(Dispatchers.IO) {
        val uri = java.net.URI(endpoint); require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null) { "AnkiConnect 地址无效" }
        val payload = JSONObject().put("action", action).put("version", 6).put("params", parameters).apply { if (apiKey.isNotEmpty()) put("key", apiKey) }
        http.newCall(Request.Builder().url(endpoint).post(payload.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { response ->
            require(response.isSuccessful) { "AnkiConnect HTTP ${response.code}" }; val j = JSONObject(response.body!!.string()); require(j.isNull("error")) { j.optString("error") }; j.opt("result")
        }
    }
    suspend fun decks() = (action("deckNames") as JSONArray).let { a -> (0 until a.length()).map { a.getString(it) } }
    suspend fun models() = (action("modelNames") as JSONArray).let { a -> (0 until a.length()).map { a.getString(it) } }
    suspend fun fields(model: String) = (action("modelFieldNames", JSONObject().put("modelName", model)) as JSONArray).let { a -> (0 until a.length()).map { a.getString(it) } }
    suspend fun add(entry: VocabularyEntry, deck: String, model: String, fieldMapping: Map<String, String>, tags: List<String> = listOf(AppBrand.NAME)): Long {
        require(entry.id.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Invalid vocabulary identifier" }
        val values = AnkiContent.values(entry)
        val fields = JSONObject(); fieldMapping.forEach { (field, placeholder) -> fields.put(field, values[placeholder.removePrefix("{").removeSuffix("}")].orEmpty()) }
        if (entry.imagePath.isNotBlank()) {
            require(context != null) { "Anki media requires the application storage context" }
            val image = VocabularyMedia.image(context, entry.imagePath)
            val filename = "${AppBrand.NAME}_${entry.id}.png"
            action("storeMediaFile", JSONObject().put("filename", filename).put("data", android.util.Base64.encodeToString(image.readBytes(), android.util.Base64.NO_WRAP)))
            fieldMapping.filterValues { it == "image" }.keys.forEach { fields.put(it, "<img src=\"$filename\">") }
        }
        val note = JSONObject().put("deckName", deck).put("modelName", model).put("fields", fields).put("tags", JSONArray(tags)).put("options", JSONObject().put("allowDuplicate", false).put("duplicateScope", "collection"))
        val possible = action("canAddNotes", JSONObject().put("notes", JSONArray().put(note))) as JSONArray
        require(possible.optBoolean(0)) { "卡片已存在或字段/模型不匹配" }
        return (action("addNote", JSONObject().put("note", note)) as Number).toLong()
    }
}

/** Standard Anki package containing a v2 SQLite collection and media; importable by Anki/AnkiDroid. */
object AnkiExporter {
    suspend fun exportApkg(context: Context, entries: List<VocabularyEntry>, output: OutputStream, deckName: String = AppBrand.NAME): Unit = withContext(Dispatchers.IO) {
        require(entries.isNotEmpty()) { "生词本为空" }
        require(entries.all { it.id.matches(Regex("[A-Za-z0-9._-]{1,128}")) }) { "Invalid vocabulary identifier" }
        val temp = File(context.cacheDir, "anki-${UUID.randomUUID()}").apply { mkdirs() }; val database = File(temp, "collection.anki2")
        val deck = 1607392319000L; val model = 1607392319001L; val now = System.currentTimeMillis(); val seconds = now / 1000
        val media = JSONObject(); val mediaFiles = mutableListOf<File>()
        try {
            SQLiteDatabase.openOrCreateDatabase(database, null).use { db ->
                db.execSQL("CREATE TABLE col(id integer primary key,crt integer not null,mod integer not null,scm integer not null,ver integer not null,dty integer not null,usn integer not null,ls integer not null,conf text not null,models text not null,decks text not null,dconf text not null,tags text not null)")
                db.execSQL("CREATE TABLE notes(id integer primary key,guid text not null,mid integer not null,mod integer not null,usn integer not null,tags text not null,flds text not null,sfld integer not null,csum integer not null,flags integer not null,data text not null)")
                db.execSQL("CREATE TABLE cards(id integer primary key,nid integer not null,did integer not null,ord integer not null,mod integer not null,usn integer not null,type integer not null,queue integer not null,due integer not null,ivl integer not null,factor integer not null,reps integer not null,lapses integer not null,left integer not null,odue integer not null,odid integer not null,flags integer not null,data text not null)")
                db.execSQL("CREATE TABLE revlog(id integer primary key,cid integer not null,usn integer not null,ease integer not null,ivl integer not null,lastIvl integer not null,factor integer not null,time integer not null,type integer not null)")
                db.execSQL("CREATE TABLE graves(usn integer not null,oid integer not null,type integer not null)")
                db.execSQL("CREATE INDEX ix_notes_usn ON notes(usn)"); db.execSQL("CREATE INDEX ix_cards_usn ON cards(usn)"); db.execSQL("CREATE INDEX ix_cards_nid ON cards(nid)"); db.execSQL("CREATE INDEX ix_cards_sched ON cards(did,queue,due)"); db.execSQL("CREATE INDEX ix_revlog_usn ON revlog(usn)"); db.execSQL("CREATE INDEX ix_revlog_cid ON revlog(cid)"); db.execSQL("CREATE INDEX ix_notes_csum ON notes(csum)")
                val fields = listOf("Expression", "Reading", "Definition", "Sentence", "Image")
                val vocabularyName = "${AppBrand.NAME} ${Localization.string(context, "VOCABULARY")}"
                val modelJson = JSONObject().put("id", model).put("name", vocabularyName).put("type", 0).put("mod", seconds).put("usn", -1).put("sortf", 0).put("did", deck).put("latexPre", "").put("latexPost", "").put("latexsvg", false).put("vers", JSONArray()).put("tags", JSONArray())
                    .put("flds", JSONArray(fields.mapIndexed { i, name -> JSONObject().put("name", name).put("ord", i).put("sticky", false).put("rtl", false).put("font", "Arial").put("size", 20).put("media", JSONArray()) }))
                    .put("tmpls", JSONArray().put(JSONObject().put("name", "Vocabulary").put("ord", 0).put("qfmt", "<div class=word>{{Expression}}</div>{{Sentence}}").put("afmt", "{{FrontSide}}<hr id=answer>{{Reading}}<br>{{Definition}}<br>{{Image}}").put("bqfmt", "").put("bafmt", "").put("did", JSONObject.NULL)))
                    .put("req", JSONArray().put(JSONArray().put(0).put("all").put(JSONArray().put(0)))).put("css", ".card{font-family:Arial;font-size:22px;text-align:center;color:black;background:white}.word{font-size:36px}img{max-width:100%}")
                val deckJson = JSONObject().put("id", deck).put("name", deckName).put("mod", seconds).put("usn", -1).put("desc", vocabularyName).put("dyn", 0).put("conf", 1).put("collapsed", false).put("extendNew", 10).put("extendRev", 50)
                    .put("newToday", JSONArray().put(0).put(0)).put("revToday", JSONArray().put(0).put(0)).put("lrnToday", JSONArray().put(0).put(0)).put("timeToday", JSONArray().put(0).put(0))
                val conf = JSONObject().put("activeDecks", JSONArray().put(deck)).put("curDeck", deck).put("newSpread", 0).put("nextPos", entries.size + 1).put("sortType", "noteFld").put("sortBackwards", false).put("timeLim", 0).put("estTimes", true).put("dueCounts", true).put("curModel", model).put("collapseTime", 1200)
                db.execSQL("INSERT INTO col VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf(1, seconds, now, now, 11, 0, 0, 0, conf.toString(), JSONObject().put(model.toString(), modelJson).toString(), JSONObject().put(deck.toString(), deckJson).toString(), "{}", "{}"))
                entries.forEachIndexed { index, e ->
                    val id = now + index; val guid = MessageDigest.getInstance("SHA-256").digest(e.id.toByteArray()).take(10).joinToString("") { "%02x".format(it) }
                    val csum = MessageDigest.getInstance("SHA-1").digest(e.word.toByteArray()).take(4).fold(0L) { acc, byte -> (acc shl 8) or (byte.toInt() and 255).toLong() }
                    var image = ""
                    val f = if (e.imagePath.isBlank()) File("") else VocabularyMedia.image(context, e.imagePath)
                    if (e.imagePath.isNotBlank() && f.isFile) { val filename = "${AppBrand.NAME}_${e.id}.png"; media.put(mediaFiles.size.toString(), filename); mediaFiles += f; image = "<img src=\"$filename\">" }
                    val flds = listOf(e.word, e.reading, e.definition).map { TextUtils.htmlEncode(it).replace("\n", "<br>") } + AnkiContent.sentence(e) + image
                    db.execSQL("INSERT INTO notes VALUES(?,?,?,?,?,?,?,?,?,?,?)", arrayOf(id, guid, model, seconds, -1, " ${AppBrand.NAME} ", flds.joinToString("\u001f"), e.word, csum, 0, ""))
                    db.execSQL("INSERT INTO cards VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf(id, id, deck, 0, seconds, -1, 0, 0, index + 1, 0, 2500, 0, 0, 0, 0, 0, 0, ""))
                }
            }
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("collection.anki2")); database.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                zip.putNextEntry(ZipEntry("media")); zip.write(media.toString().toByteArray()); zip.closeEntry()
                mediaFiles.forEachIndexed { i, f -> zip.putNextEntry(ZipEntry(i.toString())); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
            }
        } finally { temp.deleteRecursively() }
    }
    fun csv(entries: List<VocabularyEntry>): String = (listOf(listOf("Expression", "Reading", "Definition", "Sentence", "Source", "Chapter", "Page")) + entries.map { listOf(it.word, it.reading, it.definition, it.sentence, it.mangaId, it.chapterKey, (it.page + 1).toString()) })
        .joinToString("\r\n") { row -> row.joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" } }
}
