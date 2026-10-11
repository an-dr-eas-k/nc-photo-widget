package io.github.an_dr_eas_k.ncphotowidget

import android.content.Context
import android.os.SystemClock
import java.io.File

/** Shared widget state: a cached list of image ids plus a back/forward history of shown ones. */
object PhotoState {
    private const val MAX_HISTORY = 30
    private const val HOUR_MS = 60L * 60 * 1000
    private const val PARTIAL_INTERVAL_MS = 2000L
    private const val KEY_LIST_TIME = "list_time"
    private const val KEY_LIST_DURATION = "list_duration"
    private const val KEY_LIST_COUNT = "list_count"
    private const val KEY_LIST_ERROR = "list_error"
    private const val KEY_PREFETCH = "prefetch"

    class Backup(val history: String, val pos: Int)

    /** Photos found so far by a running scan, usable before the scan has finished. */
    @Volatile
    private var partial: LongArray? = null

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
            val id = prefetched(c)?.takeIf { it in ids && it != h.lastOrNull() } ?: pickRandom(c, ids) ?: return null
            h.add(id)
            while (h.size > MAX_HISTORY) h.removeAt(0)
            pos = h.size - 1
            setPrefetched(c, null)
        }
        write(c, h, pos)
        return h[pos]
    }

    /** Previous, current and upcoming id; picks the upcoming one when the history ends here. */
    fun neighbors(c: Context): Triple<Long?, Long?, Long?> {
        val h = history(c)
        val pos = prefs(c).getInt("pos", 0)
        val current = h.getOrNull(pos)
        val next = h.getOrNull(pos + 1) ?: run {
            val ids = knownIds(c)
            prefetched(c)?.takeIf { it in ids && it != current } ?: pickRandom(c, ids)?.also { setPrefetched(c, it) }
        }
        return Triple(h.getOrNull(pos - 1), current, next)
    }

    /** A random id, avoiding the most recent one. */
    fun pickRandom(c: Context, ids: LongArray): Long? {
        if (ids.isEmpty()) return null
        val last = history(c).lastOrNull()
        var id = ids.random()
        repeat(5) { if (ids.size > 1 && id == last) id = ids.random() }
        return id
    }

    /** Id of the photo already chosen (and buffered) for the next tap, if any. */
    fun prefetched(c: Context): Long? = prefs(c).getLong(KEY_PREFETCH, -1).takeIf { it >= 0 }

    fun setPrefetched(c: Context, id: Long?) {
        prefs(c).edit().apply { if (id == null) remove(KEY_PREFETCH) else putLong(KEY_PREFETCH, id) }.apply()
    }

    /** Appends [id] to the history and makes it the current entry. */
    fun show(c: Context, id: Long) {
        val h = history(c)
        h.add(id)
        while (h.size > MAX_HISTORY) h.removeAt(0)
        write(c, h, h.size - 1)
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

    private fun cachedIds(c: Context): LongArray = listFile(c).takeIf { it.exists() }
        ?.readLines()?.mapNotNull { it.toLongOrNull() }?.toLongArray() ?: LongArray(0)

    fun hasList(c: Context) = cachedIds(c).isNotEmpty()

    /** The last complete list, or what a running first scan has found so far. */
    fun knownIds(c: Context): LongArray = cachedIds(c).takeIf { it.isNotEmpty() } ?: partial ?: LongArray(0)

    fun hasScanned(c: Context) = prefs(c).contains(KEY_LIST_TIME)

    /** True when the list was never scanned or is older than [refreshHours] (0 = manual only). */
    fun needsRefresh(c: Context, refreshHours: Int): Boolean {
        if (!hasScanned(c)) return true
        val age = System.currentTimeMillis() - prefs(c).getLong(KEY_LIST_TIME, 0)
        return refreshHours > 0 && age >= refreshHours * HOUR_MS
    }

    /** Scans the folders now and records when it happened, how long it took and how many photos it found. */
    fun refreshList(
        c: Context, client: NextcloudClient, folders: List<String>,
        onFirstImage: ((Long) -> Unit)? = null,
        onProgress: ((Int, Int) -> Unit)? = null,
        isStopped: () -> Boolean = { false },
    ): LongArray {
        val start = System.currentTimeMillis()
        var lastPartial = 0L
        val fresh = try {
            client.listImageIds(folders, onFirstImage, { scanned, ids ->
                val now = SystemClock.elapsedRealtime()
                if (now - lastPartial >= PARTIAL_INTERVAL_MS) {
                    lastPartial = now
                    partial = ids.toLongArray()
                }
                onProgress?.invoke(scanned, ids.size)
            }, isStopped)
        } catch (e: Exception) {
            // Kept so the settings screen can explain why the widget stays empty.
            if (!isStopped()) prefs(c).edit().putString(KEY_LIST_ERROR, e.message ?: e.javaClass.simpleName).apply()
            throw e
        } finally {
            partial = null
        }
        val end = System.currentTimeMillis()
        // Written atomically: taps may read the list while it is replaced.
        val tmp = File(c.filesDir, "ids.tmp")
        tmp.writeText(fresh.joinToString("\n"))
        tmp.renameTo(listFile(c))
        prefs(c).edit()
            .putLong(KEY_LIST_TIME, end)
            .putLong(KEY_LIST_DURATION, end - start)
            .putInt(KEY_LIST_COUNT, fresh.size)
            .remove(KEY_LIST_ERROR)
            .apply()
        return fresh
    }

    class ListStats(val timestamp: Long, val durationMs: Long, val count: Int, val error: String?)

    fun listStats(c: Context): ListStats? {
        val p = prefs(c)
        if (!p.contains(KEY_LIST_TIME) && !p.contains(KEY_LIST_ERROR)) return null
        return ListStats(
            p.getLong(KEY_LIST_TIME, 0), p.getLong(KEY_LIST_DURATION, 0), p.getInt(KEY_LIST_COUNT, 0),
            p.getString(KEY_LIST_ERROR, null),
        )
    }
}
