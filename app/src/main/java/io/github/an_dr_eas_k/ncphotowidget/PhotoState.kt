package io.github.an_dr_eas_k.ncphotowidget

import android.content.Context
import java.io.File
import java.io.IOException

/** Shared widget state: a cached list of image ids plus a back/forward history of shown ones. */
object PhotoState {
    private const val MAX_HISTORY = 30
    private const val HOUR_MS = 60L * 60 * 1000
    private const val KEY_LIST_TIME = "list_time"
    private const val KEY_LIST_DURATION = "list_duration"
    private const val KEY_LIST_COUNT = "list_count"

    class Backup(val history: String, val pos: Int)

    private fun prefs(c: Context) = c.getSharedPreferences("state", Context.MODE_PRIVATE)
    private fun listFile(c: Context) = File(c.filesDir, "ids.txt")

    private fun history(c: Context): MutableList<Long> =
        (prefs(c).getString("history", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }.toMutableList()

    private fun write(c: Context, history: List<Long>, pos: Int) {
        prefs(c).edit().putString("history", history.joinToString(",")).putInt("pos", pos).apply()
    }

    fun current(c: Context): Long? = history(c).getOrNull(prefs(c).getInt("pos", 0))

    fun next(c: Context, ids: LongArray): Long? {
        val h = history(c)
        var pos = prefs(c).getInt("pos", 0)
        if (pos < h.size - 1) {
            pos++
        } else {
            if (ids.isEmpty()) return null
            var id = ids.random()
            repeat(5) { if (ids.size > 1 && id == h.lastOrNull()) id = ids.random() }
            h.add(id)
            while (h.size > MAX_HISTORY) h.removeAt(0)
            pos = h.size - 1
        }
        write(c, h, pos)
        return h[pos]
    }

    fun previous(c: Context): Long? {
        val h = history(c)
        val pos = (prefs(c).getInt("pos", 0) - 1).coerceAtLeast(0)
        write(c, h, pos)
        return h.getOrNull(pos)
    }

    /** Removes the current entry, e.g. when the file no longer exists on the server. */
    fun dropCurrent(c: Context) {
        val h = history(c)
        val pos = prefs(c).getInt("pos", 0)
        if (pos in h.indices) h.removeAt(pos)
        write(c, h, pos.coerceAtMost(h.size - 1).coerceAtLeast(0))
    }

    fun backup(c: Context) = Backup(prefs(c).getString("history", "") ?: "", prefs(c).getInt("pos", 0))

    fun restore(c: Context, b: Backup) {
        prefs(c).edit().putString("history", b.history).putInt("pos", b.pos).apply()
    }

    fun reset(c: Context) {
        listFile(c).delete()
        prefs(c).edit().clear().apply()
    }

    /** Returns the cached id list, re-scanning the server when it is missing or older than [refreshHours]. */
    fun ids(c: Context, client: NextcloudClient, folders: List<String>, refreshHours: Int): LongArray {
        val cached = listFile(c).takeIf { it.exists() }
            ?.readLines()?.mapNotNull { it.toLongOrNull() }?.toLongArray() ?: LongArray(0)
        val age = System.currentTimeMillis() - prefs(c).getLong(KEY_LIST_TIME, 0)
        val stale = refreshHours > 0 && age >= refreshHours * HOUR_MS
        if (cached.isNotEmpty() && !stale) return cached
        return try {
            refreshList(c, client, folders)
        } catch (e: IOException) {
            // An old list beats no list when the server is unreachable.
            if (cached.isEmpty()) throw e
            cached
        }
    }

    /** Scans the folders now and records when it happened, how long it took and how many photos it found. */
    fun refreshList(c: Context, client: NextcloudClient, folders: List<String>): LongArray {
        val start = System.currentTimeMillis()
        val fresh = client.listImageIds(folders)
        val end = System.currentTimeMillis()
        listFile(c).writeText(fresh.joinToString("\n"))
        prefs(c).edit()
            .putLong(KEY_LIST_TIME, end)
            .putLong(KEY_LIST_DURATION, end - start)
            .putInt(KEY_LIST_COUNT, fresh.size)
            .apply()
        return fresh
    }

    class ListStats(val timestamp: Long, val durationMs: Long, val count: Int)

    fun listStats(c: Context): ListStats? {
        val p = prefs(c)
        if (!p.contains(KEY_LIST_TIME)) return null
        return ListStats(p.getLong(KEY_LIST_TIME, 0), p.getLong(KEY_LIST_DURATION, 0), p.getInt(KEY_LIST_COUNT, 0))
    }
}
