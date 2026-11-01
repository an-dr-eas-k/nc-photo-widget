package io.github.an_dr_eas_k.ncphotowidget

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/** The state of one widget: a cached list of image ids plus a back/forward history of shown ones. */
class PhotoState(private val c: Context, private val widgetId: Int) {
    private val prefs = c.getSharedPreferences(prefsName(widgetId), Context.MODE_PRIVATE)
    private val listFile = File(WidgetFiles.dir(c, widgetId), "ids.txt")
    private val listTmp = File(WidgetFiles.dir(c, widgetId), "ids.tmp")

    private fun load(): PhotoHistory {
        val p = prefs
        return PhotoHistory(
            (p.getString("history", "") ?: "").split(',').mapNotNull { it.toLongOrNull() },
            p.getInt("pos", 0),
            p.getLong(KEY_PREFETCH, -1).takeIf { it >= 0 },
        )
    }

    private fun save(h: PhotoHistory) {
        val editor = prefs.edit().putString("history", h.ids.joinToString(",")).putInt("pos", h.pos)
        val prefetched = h.prefetched
        if (prefetched == null) editor.remove(KEY_PREFETCH) else editor.putLong(KEY_PREFETCH, prefetched)
        editor.apply()
    }

    private fun <T> edit(change: PhotoHistory.() -> T): T {
        val h = load()
        return h.change().also { save(h) }
    }

    fun current(): Long? = load().current

    /** The photo on the widget; it differs from [current] while a download was started but never finished. */
    fun shown(): Long? = prefs.getLong(KEY_SHOWN, -1).takeIf { it >= 0 }

    fun markShown(id: Long, plain: Boolean = false) =
        prefs.edit().putLong(KEY_SHOWN, id).putBoolean(KEY_SHOWN_PLAIN, plain).putBoolean(KEY_FAILED, false).apply()

    /** Whether the shown picture is to be drawn without the picture filter. */
    fun shownIsPlain(): Boolean = prefs.getBoolean(KEY_SHOWN_PLAIN, false)

    /** Whether the list marks [id] as a picture that already has a look (a Snapseed export). */
    fun isPreFiltered(id: Long): Boolean = listFile.takeIf { it.exists() }?.useLines { lines -> lines.any { it == "$id,s" } } ?: false

    /** Whether the last request for a picture failed; the widget then shows a warning. */
    fun failed(): Boolean = prefs.getBoolean(KEY_FAILED, false)

    fun setFailed(failed: Boolean) = prefs.edit().putBoolean(KEY_FAILED, failed).apply()

    /** The photo whose download was interrupted, to be retried instead of picking another one. */
    fun pending(): Long? = current()?.takeIf { it != shown() }

    /** Every photo of the history; their pictures are kept for walking back and forth, also offline. */
    fun historyIds(): List<Long> = load().ids

    fun next(ids: LongArray): Long? = edit { next(ids) }

    fun neighbors(): Triple<Long?, Long?, Long?> = edit { neighbors(knownIds()) }

    fun show(id: Long) = edit { show(id) }

    fun previous(): Long? = edit { previous() }

    fun dropCurrent() = edit { dropCurrent() }

    fun backup() = load()

    fun restore(backup: PhotoHistory) = save(backup)

    fun reset() {
        listFile.delete()
        prefs.edit().clear().apply()
    }

    // The first number of a line; lists written by a development build also had a day after a comma.
    private fun cachedIds(): LongArray = listFile.takeIf { it.exists() }
        ?.readLines()?.mapNotNull { it.substringBefore(',').toLongOrNull() }?.toLongArray() ?: LongArray(0)

    fun hasList() = cachedIds().isNotEmpty()

    /** The last complete list, or what a running first scan has found so far. */
    fun knownIds(): LongArray = cachedIds().takeIf { it.isNotEmpty() } ?: partial[widgetId] ?: LongArray(0)

    fun hasScanned() = prefs.contains(KEY_LIST_TIME)

    /** True when the list was never scanned or is older than [refreshHours] (0 = manual only). */
    fun needsRefresh(refreshHours: Int): Boolean {
        if (!hasScanned()) return true
        val age = System.currentTimeMillis() - prefs.getLong(KEY_LIST_TIME, 0)
        return refreshHours > 0 && age >= refreshHours * HOUR_MS
    }

    /** Scans the folders now and records when it happened, how long it took and how many photos it found. */
    fun refreshList(
        client: NextcloudClient, folders: List<String>, tags: List<Tag>,
        onFirstImage: ((Long) -> Unit)? = null,
        onProgress: ((Int, Int) -> Unit)? = null,
        isStopped: () -> Boolean = { false },
    ): LongArray {
        val preFiltered = HashSet<Long>()
        val filter = WidgetConfig(c, widgetId).timeFilter
        val today = LocalDate.now()
        val start = System.currentTimeMillis()
        var lastPartial = 0L
        val fresh = try {
            client.listImageIds(folders, tags, { filter.accepts(it, today) }, onFirstImage, { scanned, ids ->
                val now = SystemClock.elapsedRealtime()
                if (now - lastPartial >= PARTIAL_INTERVAL_MS) {
                    lastPartial = now
                    partial[widgetId] = ids.toLongArray()
                }
                onProgress?.invoke(scanned, ids.size)
            }, isStopped, preFiltered)
        } catch (e: Exception) {
            // Kept so the settings screen can explain why the widget stays empty.
            if (!isStopped()) prefs.edit().putString(KEY_LIST_ERROR, e.describe()).apply()
            throw e
        } finally {
            partial.remove(widgetId)
        }
        val end = System.currentTimeMillis()
        // Written atomically: taps may read the list while it is replaced.
        // A line is the id, followed by ",s" for a Snapseed picture.
        listTmp.writeText(fresh.joinToString("\n") { if (it in preFiltered) "$it,s" else "$it" })
        listTmp.renameTo(listFile)
        prefs.edit()
            .putLong(KEY_LIST_TIME, end)
            .putLong(KEY_LIST_DURATION, end - start)
            .putInt(KEY_LIST_COUNT, fresh.size)
            .remove(KEY_LIST_ERROR)
            .apply()
        return fresh
    }

    class ListStats(val timestamp: Long, val durationMs: Long, val count: Int, val error: String?)

    fun listStats(): ListStats? {
        val p = prefs
        if (!p.contains(KEY_LIST_TIME) && !p.contains(KEY_LIST_ERROR)) return null
        return ListStats(
            p.getLong(KEY_LIST_TIME, 0), p.getLong(KEY_LIST_DURATION, 0), p.getInt(KEY_LIST_COUNT, 0),
            p.getString(KEY_LIST_ERROR, null),
        )
    }

    companion object {
        private const val HOUR_MS = 60L * 60 * 1000
        private const val PARTIAL_INTERVAL_MS = 2000L
        private const val KEY_LIST_TIME = "list_time"
        private const val KEY_LIST_DURATION = "list_duration"
        private const val KEY_LIST_COUNT = "list_count"
        private const val KEY_LIST_ERROR = "list_error"
        private const val KEY_PREFETCH = "prefetch"
        private const val KEY_SHOWN = "shown"
        private const val KEY_SHOWN_PLAIN = "shown_plain"
        private const val KEY_FAILED = "failed"

        /** Photos found so far by running scans, usable before a scan has finished. */
        private val partial = ConcurrentHashMap<Int, LongArray>()

        private fun prefsName(widgetId: Int) = "state_$widgetId"

        /** Removes everything stored for a deleted widget. */
        fun delete(c: Context, widgetId: Int) {
            c.deleteSharedPreferences(prefsName(widgetId))
            WidgetFiles.dir(c, widgetId).deleteRecursively()
            partial.remove(widgetId)
        }

        /** Moves the state of version 1.x, which was shared by all widgets, to [widgetId]. */
        fun migrateLegacy(c: Context, widgetId: Int) {
            val old = c.getSharedPreferences("state", Context.MODE_PRIVATE)
            val target = c.getSharedPreferences(prefsName(widgetId), Context.MODE_PRIVATE).edit()
            for ((key, value) in old.all) when (value) {
                is String -> target.putString(key, value)
                is Int -> target.putInt(key, value)
                is Long -> target.putLong(key, value)
            }
            target.apply()
            old.edit().clear().apply()
            val dir = WidgetFiles.dir(c, widgetId)
            for (name in listOf("ids.txt", "current.jpg", "buffer")) File(c.filesDir, name).renameTo(File(dir, name))
        }
    }
}

/** Where the files of one widget live. */
object WidgetFiles {
    fun dir(c: Context, widgetId: Int) = File(c.filesDir, "widgets/$widgetId").apply { mkdirs() }
}
