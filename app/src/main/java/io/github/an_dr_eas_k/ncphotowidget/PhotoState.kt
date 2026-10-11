package io.github.an_dr_eas_k.ncphotowidget

import android.content.Context
import android.os.SystemClock
import java.io.File

/** Shared widget state: a cached list of image ids plus a back/forward history of shown ones. */
object PhotoState {
    private const val HOUR_MS = 60L * 60 * 1000
    private const val PARTIAL_INTERVAL_MS = 2000L
    private const val KEY_LIST_TIME = "list_time"
    private const val KEY_LIST_DURATION = "list_duration"
    private const val KEY_LIST_COUNT = "list_count"
    private const val KEY_LIST_ERROR = "list_error"
    private const val KEY_PREFETCH = "prefetch"

    /** Photos found so far by a running scan, usable before the scan has finished. */
    @Volatile
    private var partial: LongArray? = null

    private fun prefs(c: Context) = c.getSharedPreferences("state", Context.MODE_PRIVATE)
    private fun listFile(c: Context) = File(c.filesDir, "ids.txt")

    private fun load(c: Context): PhotoHistory {
        val p = prefs(c)
        return PhotoHistory(
            (p.getString("history", "") ?: "").split(',').mapNotNull { it.toLongOrNull() },
            p.getInt("pos", 0),
            p.getLong(KEY_PREFETCH, -1).takeIf { it >= 0 },
        )
    }

    private fun save(c: Context, h: PhotoHistory) {
        val editor = prefs(c).edit().putString("history", h.ids.joinToString(",")).putInt("pos", h.pos)
        val prefetched = h.prefetched
        if (prefetched == null) editor.remove(KEY_PREFETCH) else editor.putLong(KEY_PREFETCH, prefetched)
        editor.apply()
    }

    private fun <T> edit(c: Context, change: PhotoHistory.() -> T): T {
        val h = load(c)
        return h.change().also { save(c, h) }
    }

    fun current(c: Context): Long? = load(c).current

    fun next(c: Context, ids: LongArray): Long? = edit(c) { next(ids) }

    fun neighbors(c: Context): Triple<Long?, Long?, Long?> = edit(c) { neighbors(knownIds(c)) }

    fun show(c: Context, id: Long) = edit(c) { show(id) }

    fun previous(c: Context): Long? = edit(c) { previous() }

    fun dropCurrent(c: Context) = edit(c) { dropCurrent() }

    fun backup(c: Context) = load(c)

    fun restore(c: Context, backup: PhotoHistory) = save(c, backup)

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
            if (!isStopped()) prefs(c).edit().putString(KEY_LIST_ERROR, e.describe()).apply()
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
