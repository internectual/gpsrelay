package net.internectual.gpsrelay.log

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.internectual.gpsrelay.protocol.Destination

enum class Direction { OUT, IN }

data class NavLogEntry(
    val id: Long,
    val time: Long,
    val direction: Direction,
    val navId: String?,
    val sourceApp: String?,
    val raw: String,
    val dest: Destination?,
    val status: String,
    val detail: String?,
)

/** Log of links intercepted (server, OUT) or received (client, IN). Capped at [MAX_ENTRIES]. */
class NavLogStore(context: Context) : SQLiteOpenHelper(context, "navlog.db", null, 1) {

    private val _entries = MutableStateFlow<List<NavLogEntry>>(emptyList())
    val entries: StateFlow<List<NavLogEntry>> = _entries

    init {
        refresh()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                time INTEGER NOT NULL,
                direction TEXT NOT NULL,
                nav_id TEXT,
                source_app TEXT,
                raw TEXT NOT NULL,
                lat REAL, lon REAL, query TEXT, label TEXT,
                status TEXT NOT NULL,
                detail TEXT
            )"""
        )
        db.execSQL("CREATE INDEX log_nav_id ON log(nav_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun add(direction: Direction, raw: String, sourceApp: String?, status: String, navId: String? = null, dest: Destination? = null, detail: String? = null): Long {
        val cv = ContentValues().apply {
            put("time", System.currentTimeMillis())
            put("direction", direction.name)
            put("nav_id", navId)
            put("source_app", sourceApp)
            put("raw", raw)
            put("status", status)
            put("detail", detail)
            putDest(dest)
        }
        val id = writableDatabase.insert("log", null, cv)
        writableDatabase.execSQL("DELETE FROM log WHERE id <= (SELECT id FROM log ORDER BY id DESC LIMIT 1 OFFSET $MAX_ENTRIES)")
        refresh()
        return id
    }

    @Synchronized
    fun update(rowId: Long, status: String? = null, detail: String? = null, navId: String? = null, dest: Destination? = null) {
        val cv = ContentValues().apply {
            status?.let { put("status", it) }
            detail?.let { put("detail", it) }
            navId?.let { put("nav_id", it) }
            dest?.let { putDest(it) }
        }
        if (cv.size() == 0) return
        writableDatabase.update("log", cv, "id = ?", arrayOf(rowId.toString()))
        refresh()
    }

    @Synchronized
    fun updateByNavId(navId: String, direction: Direction, status: String, detail: String? = null) {
        val cv = ContentValues().apply {
            put("status", status)
            detail?.let { put("detail", it) }
        }
        writableDatabase.update("log", cv, "nav_id = ? AND direction = ?", arrayOf(navId, direction.name))
        refresh()
    }

    @Synchronized
    fun clear() {
        writableDatabase.delete("log", null, null)
        refresh()
    }

    private fun ContentValues.putDest(d: Destination?) {
        put("lat", d?.lat)
        put("lon", d?.lon)
        put("query", d?.query)
        put("label", d?.label)
    }

    private fun refresh() {
        val list = mutableListOf<NavLogEntry>()
        readableDatabase.rawQuery("SELECT * FROM log ORDER BY id DESC LIMIT $MAX_ENTRIES", null).use { c ->
            while (c.moveToNext()) list += c.toEntry()
        }
        _entries.value = list
    }

    private fun Cursor.toEntry(): NavLogEntry {
        fun str(n: String) = getColumnIndexOrThrow(n).let { if (isNull(it)) null else getString(it) }
        fun dbl(n: String) = getColumnIndexOrThrow(n).let { if (isNull(it)) null else getDouble(it) }
        val lat = dbl("lat")
        val lon = dbl("lon")
        val query = str("query")
        val label = str("label")
        return NavLogEntry(
            id = getLong(getColumnIndexOrThrow("id")),
            time = getLong(getColumnIndexOrThrow("time")),
            direction = runCatching { Direction.valueOf(str("direction")!!) }.getOrDefault(Direction.OUT),
            navId = str("nav_id"),
            sourceApp = str("source_app"),
            raw = str("raw") ?: "",
            dest = if (lat == null && lon == null && query == null && label == null) null else Destination(lat, lon, query, label),
            status = str("status") ?: "",
            detail = str("detail"),
        )
    }

    companion object {
        const val MAX_ENTRIES = 500
    }
}
