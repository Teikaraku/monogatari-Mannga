package app.monogatari.android.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/** SQLite transactions replace Core Data transactions; chapter progress writes one chapter row. */
class AppDatabase(context: Context) : SQLiteOpenHelper(context, "monogatari.db", null, 1, android.database.DatabaseErrorHandler { db ->
    // Android's default handler deletes the database. Keep the original for recovery.
    throw android.database.sqlite.SQLiteException("Database corruption detected; preserved ${db.path}")
}) {
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true); db.enableWriteAheadLogging() }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE manga (id TEXT PRIMARY KEY, data TEXT NOT NULL)")
        db.execSQL("CREATE TABLE chapters (mangaId TEXT NOT NULL REFERENCES manga(id) ON DELETE CASCADE, chapterId TEXT NOT NULL, ordinal INTEGER NOT NULL, data TEXT NOT NULL, PRIMARY KEY(mangaId,chapterId))")
        db.execSQL("CREATE TABLE metadata (id INTEGER PRIMARY KEY CHECK(id=1), data TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { error("Unsupported database migration $oldVersion → $newVersion") }
    fun hasState() = readableDatabase.rawQuery("SELECT 1 FROM metadata WHERE id=1", null).use { it.moveToFirst() }
    fun load(): AppState {
        val db = readableDatabase
        val meta = db.rawQuery("SELECT data FROM metadata WHERE id=1", null).use { if (it.moveToFirst()) JSONObject(it.getString(0)) else return AppState() }
        val chapterMap = mutableMapOf<String, MutableList<Chapter>>()
        db.rawQuery("SELECT mangaId,data FROM chapters ORDER BY mangaId,ordinal", null).use { cursor ->
            while (cursor.moveToNext()) chapterMap.getOrPut(cursor.getString(0)) { mutableListOf() }.add(Chapter.parse(JSONObject(cursor.getString(1))))
        }
        val manga = mutableListOf<Manga>()
        db.rawQuery("SELECT id,data FROM manga", null).use { cursor ->
            while (cursor.moveToNext()) manga += Manga.parse(JSONObject(cursor.getString(1))).copy(chapters = chapterMap[cursor.getString(0)].orEmpty())
        }
        val order = meta.optJSONArray("_mangaOrder").strings()
        val orderedKeys = order.toHashSet()
        val entries = manga.associateBy { it.id }
        val sorted = (order.mapNotNull(entries::get) + manga.filter { it.id !in orderedKeys }).distinctBy { it.id }
        return AppState.parse(meta).copy(manga = sorted)
    }
    fun save(previous: AppState?, next: AppState) {
        require(next.manga.map { it.id }.distinct().size == next.manga.size) { "Duplicate manga identifiers" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val before = previous?.manga.orEmpty().associateBy { it.id }
            val after = next.manga.associateBy { it.id }
            (before.keys - after.keys).forEach { db.delete("manga", "id=?", arrayOf(it)) }
            next.manga.forEach { manga ->
                val old = before[manga.id]
                if (old === manga) return@forEach
                require(manga.chapters.map { it.key }.distinct().size == manga.chapters.size) { "Duplicate chapter identifiers" }
                if (old == null || old.copy(chapters = emptyList()) != manga.copy(chapters = emptyList())) {
                    val value = ContentValues().apply { put("data", manga.copy(chapters = emptyList()).json().toString()) }
                    if (db.update("manga", value, "id=?", arrayOf(manga.id)) == 0) {
                        value.put("id", manga.id); db.insertOrThrow("manga", null, value)
                    }
                }
                val oldChapters = old?.chapters.orEmpty().associateBy { it.key }
                val oldPositions = old?.chapters.orEmpty().mapIndexed { index, chapter -> chapter.key to index }.toMap()
                val newKeys = manga.chapters.map { it.key }.toSet()
                (oldChapters.keys - newKeys).forEach { db.delete("chapters", "mangaId=? AND chapterId=?", arrayOf(manga.id, it)) }
                manga.chapters.forEachIndexed { index, chapter ->
                    if (oldChapters[chapter.key] != chapter || oldPositions[chapter.key] != index) {
                        val values = ContentValues().apply { put("mangaId", manga.id); put("chapterId", chapter.key); put("ordinal", index); put("data", chapter.json().toString()) }
                        check(db.insertWithOnConflict("chapters", null, values, SQLiteDatabase.CONFLICT_REPLACE) >= 0) { "Unable to write chapter" }
                    }
                }
            }
            if (previous == null || previous.copy(manga = emptyList()) != next.copy(manga = emptyList()) || previous.manga.map { it.id } != next.manga.map { it.id }) {
                val values = ContentValues().apply { put("id", 1); put("data", next.copy(manga = emptyList()).json().put("_mangaOrder", org.json.JSONArray(next.manga.map { it.id })).toString()) }
                check(db.insertWithOnConflict("metadata", null, values, SQLiteDatabase.CONFLICT_REPLACE) >= 0) { "Unable to write metadata" }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
