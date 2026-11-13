package io.github.mot1us.spare

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Saved Movies/Series categories, one row per title. Browsing reads a page at a time instead of parsing a whole category.
 * Every title is sealed with the same data key as the other caches, so titles, artwork and stream paths are never plaintext
 * in the database file; only the category scope, provider order and generation are readable.
 */
class CatalogDb private constructor(context: Context) : SQLiteOpenHelper(context.applicationContext, "library.db", null, 1) {
    private val crypto = SecureStore(context.applicationContext)
    private val directory = context.applicationContext.filesDir

    data class Meta(val gen: String, val updated: Long, val complete: Boolean, val count: Int, val provider: String, val labels: Map<String, String>)

    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(false); db.enableWriteAheadLogging() }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE title(scope TEXT NOT NULL, gen TEXT NOT NULL, pos INTEGER NOT NULL, payload BLOB NOT NULL, PRIMARY KEY(scope, gen, pos)) WITHOUT ROWID")
        db.execSQL("CREATE TABLE meta(scope TEXT PRIMARY KEY, gen TEXT NOT NULL, updated INTEGER NOT NULL, complete INTEGER NOT NULL, count INTEGER NOT NULL, provider TEXT NOT NULL, labels BLOB NOT NULL)")
        removeLegacyCategoryFiles()
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** Category files written before the database: their index file ends "-<mode>-<category>-index.enc"; remove it with its pages. */
    private fun removeLegacyCategoryFiles() = runCatching {
        val names = directory.listFiles()?.map { it.name.removeSuffix(".new").removeSuffix(".bak") }?.toSet().orEmpty()
        val indexes = names.filter { Regex("catalog-[A-Za-z0-9_-]+-(?:movie|series)-[0-9]{1,20}-index\\.enc").matches(it) }
        indexes.forEach { index ->
            val prefix = index.removeSuffix("index.enc")
            names.filter { it.startsWith(prefix) }.forEach { name -> listOf("", ".new", ".bak").forEach { File(directory, name + it).delete() } }
        }
    }

    fun scopeKey(source: Source, mode: String, scope: String) = "${source.id}:${source.providerKey().take(16)}:$mode:$scope"

    fun insert(scope: String, gen: String, firstPos: Int, titles: List<LibraryCatalog.Title>) {
        val rows = titles.map { crypto.seal(it.json().toString().toByteArray()) }
        writableDatabase.transaction {
            val statement = compileStatement("INSERT OR REPLACE INTO title(scope, gen, pos, payload) VALUES (?, ?, ?, ?)")
            rows.forEachIndexed { index, payload ->
                statement.clearBindings(); statement.bindString(1, scope); statement.bindString(2, gen); statement.bindLong(3, (firstPos + index).toLong()); statement.bindBlob(4, payload)
                statement.executeInsert()
            }
        }
    }

    /** Make [gen] the saved copy of [scope] and drop every older generation of it. */
    fun publish(scope: String, gen: String, updated: Long, complete: Boolean, count: Int, provider: String, labels: Map<String, String>) {
        writableDatabase.transaction {
            val values = ContentValues().apply {
                put("scope", scope); put("gen", gen); put("updated", updated); put("complete", if (complete) 1 else 0); put("count", count); put("provider", provider)
                put("labels", crypto.seal(JSONObject(labels).toString().toByteArray()))
            }
            insertWithOnConflict("meta", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            delete("title", "scope = ? AND gen <> ?", arrayOf(scope, gen))
        }
    }

    fun meta(scope: String): Meta? = readableDatabase.rawQuery("SELECT gen, updated, complete, count, provider, labels FROM meta WHERE scope = ?", arrayOf(scope)).use { cursor ->
        if (!cursor.moveToFirst()) return null
        val labels = JSONObject(String(crypto.open(cursor.getBlob(5))))
        Meta(cursor.getString(0), cursor.getLong(1), cursor.getInt(2) == 1, cursor.getInt(3), cursor.getString(4), labels.keys().asSequence().associateWith { labels.getString(it) })
    }

    fun titles(scope: String, gen: String, offset: Int, limit: Int): List<LibraryCatalog.Title> =
        readableDatabase.rawQuery("SELECT payload FROM title WHERE scope = ? AND gen = ? AND pos >= ? ORDER BY pos LIMIT ?", arrayOf(scope, gen, offset.toString(), limit.toString())).use { cursor ->
            val result = ArrayList<LibraryCatalog.Title>(limit)
            while (cursor.moveToNext()) result.add(LibraryCatalog.Title.from(JSONArray(String(crypto.open(cursor.getBlob(0))))))
            result
        }

    fun deleteSource(sourceId: String) {
        writableDatabase.transaction {
            val range = arrayOf("$sourceId:", "$sourceId;")
            delete("title", "scope >= ? AND scope < ?", range); delete("meta", "scope >= ? AND scope < ?", range)
        }
    }

    companion object {
        @Volatile private var instance: CatalogDb? = null
        fun get(context: Context): CatalogDb = instance ?: synchronized(this) { instance ?: CatalogDb(context.applicationContext).also { instance = it } }
    }
}
