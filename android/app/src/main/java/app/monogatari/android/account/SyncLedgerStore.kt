package app.monogatari.android.account

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/** The sync ledger in its own database, apart from the library's, so it can change without migrating that one. */
class SqliteSyncLedger(context: Context, name: String? = "account-sync.db") : SyncLedger,
    SQLiteOpenHelper(context, name, null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE agreed(kind TEXT NOT NULL, key TEXT NOT NULL, hash TEXT, updated INTEGER NOT NULL, PRIMARY KEY(kind, key))")
        db.execSQL("CREATE TABLE parked(kind TEXT NOT NULL, key TEXT NOT NULL, record TEXT NOT NULL, PRIMARY KEY(kind, key))")
        db.execSQL("CREATE TABLE meta(name TEXT PRIMARY KEY, value TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    private fun meta(name: String): String? = readableDatabase.rawQuery("SELECT value FROM meta WHERE name=?", arrayOf(name)).use { if (it.moveToFirst()) it.getString(0) else null }
    private fun setMeta(name: String, value: String?) {
        writableDatabase.insertWithOnConflict("meta", null, ContentValues().apply { put("name", name); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override var owner: String?
        get() = meta("owner")
        set(value) = setMeta("owner", value)

    override var cursor: Long
        get() = meta("cursor")?.toLongOrNull() ?: 0
        set(value) = setMeta("cursor", value.toString())

    /** When the last round finished, shown as the sync status. */
    var lastSynced: Long?
        get() = meta("last")?.toLongOrNull()
        set(value) = setMeta("last", value?.toString())

    override fun agreed(kind: String): Map<String, Agreed> =
        readableDatabase.rawQuery("SELECT key, hash, updated FROM agreed WHERE kind=?", arrayOf(kind)).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), Agreed(if (c.isNull(1)) null else c.getString(1), c.getLong(2))) }
        }

    override fun parked(): List<SyncRecord> = readableDatabase.rawQuery("SELECT record FROM parked", null).use { c ->
        buildList { while (c.moveToNext()) runCatching { add(SyncRecord.parse(JSONObject(c.getString(0)))) } }
    }

    override fun write(ops: List<LedgerOp>) {
        if (ops.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (op in ops) when (op) {
                is LedgerOp.Agree -> db.insertWithOnConflict("agreed", null, ContentValues().apply {
                    put("kind", op.kind); put("key", op.key); put("hash", op.hash); put("updated", op.updated)
                }, SQLiteDatabase.CONFLICT_REPLACE)
                is LedgerOp.Forget -> db.delete("agreed", "kind=? AND key=?", arrayOf(op.kind, op.key))
                is LedgerOp.Park -> db.insertWithOnConflict("parked", null, ContentValues().apply {
                    put("kind", op.record.kind); put("key", op.record.key); put("record", op.record.json().toString())
                }, SQLiteDatabase.CONFLICT_REPLACE)
                is LedgerOp.Unpark -> db.delete("parked", "kind=? AND key=?", arrayOf(op.kind, op.key))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    override fun clear() {
        val db = writableDatabase
        db.beginTransaction()
        try { db.delete("agreed", null, null); db.delete("parked", null, null); db.delete("meta", null, null); db.setTransactionSuccessful() }
        finally { db.endTransaction() }
    }
}
