package io.github.an_dr_eas_k.ncphotowidget

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * Every photo of one widget's folders or tags, with the day it was taken. The order is of no
 * importance: a photo is picked at random from those inside the time span.
 */
class PhotoList private constructor(c: Context, widgetId: Int) :
    SQLiteOpenHelper(c.applicationContext, File(WidgetFiles.dir(c, widgetId), "photos.db").path, null, VERSION) {

    init {
        // Readers (taps) do not wait for a writer (a scan).
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE photos (id INTEGER PRIMARY KEY, day INTEGER NOT NULL, plain INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX photos_day ON photos (day)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** The condition that keeps the photos inside [filter]'s time span; empty without a time span. */
    private fun where(filter: TimeFilter, today: LocalDate): String {
        val ranges = filter.dayRanges(today) ?: return ""
        if (ranges.isEmpty()) return "WHERE 0"
        return ranges.joinToString(" OR ", "WHERE ") { "day BETWEEN ${it.first} AND ${it.last}" }
    }

    private fun <T> query(sql: String, args: Array<String>? = null, read: (android.database.Cursor) -> T): T =
        readableDatabase.rawQuery(sql, args).use(read)

    fun isEmpty(): Boolean = query("SELECT 1 FROM photos LIMIT 1") { !it.moveToFirst() }

    /** How many photos are inside the time span. */
    fun count(filter: TimeFilter, today: LocalDate): Int =
        query("SELECT COUNT(*) FROM photos ${where(filter, today)}") { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** The ids of the photos inside the time span. */
    fun ids(filter: TimeFilter, today: LocalDate): LongArray =
        query("SELECT id FROM photos ${where(filter, today)}") { cursor ->
            LongArray(cursor.count).also { for (i in it.indices) if (cursor.moveToNext()) it[i] = cursor.getLong(0) }
        }

    /** Whether [id] is a picture that already has a look of its own (a Snapseed export). */
    fun isPlain(id: Long): Boolean =
        query("SELECT plain FROM photos WHERE id = ?", arrayOf(id.toString())) { it.moveToFirst() && it.getInt(0) != 0 }

    /** The day the photo was taken (or last changed), or null when it is not in the list or has none. */
    fun day(id: Long): Int? =
        query("SELECT day FROM photos WHERE id = ?", arrayOf(id.toString())) {
            if (it.moveToFirst()) it.getInt(0).takeIf { day -> day != NO_DAY } else null
        }

    fun remove(id: Long) {
        writableDatabase.delete("photos", "id = ?", arrayOf(id.toString()))
    }

    /** Makes [photos] the whole list, in one step: a reader sees either the old list or the new one. */
    fun replaceAll(photos: Collection<Photo>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("photos", null, null)
            insert(db, photos)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Adds the [photos] that are not in the list yet. */
    fun add(photos: Collection<Photo>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            insert(db, photos)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun insert(db: SQLiteDatabase, photos: Collection<Photo>) {
        val statement = db.compileStatement("INSERT OR IGNORE INTO photos (id, day, plain) VALUES (?, ?, ?)")
        try {
            for (photo in photos) {
                statement.bindLong(1, photo.id)
                statement.bindLong(2, photo.day.toLong())
                statement.bindLong(3, if (photo.plain) 1 else 0)
                statement.executeInsert()
            }
        } finally {
            statement.close()
        }
    }

    fun clear() {
        writableDatabase.delete("photos", null, null)
    }

    companion object {
        private const val VERSION = 1

        // One connection per widget for the whole process: taps, scans and the settings share it.
        private val open = ConcurrentHashMap<Int, PhotoList>()

        fun of(c: Context, widgetId: Int): PhotoList = open.getOrPut(widgetId) { PhotoList(c, widgetId) }

        /** Closes the database of a widget that is deleted. */
        fun close(widgetId: Int) {
            open.remove(widgetId)?.close()
        }
    }
}
