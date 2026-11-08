package io.github.an_dr_eas_k.ncphotowidget

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/** The state of one widget: a cached list of image ids plus a back/forward history of shown ones. */
class PhotoState(private val c: Context, private val widgetId: Int) {
    private val prefs = c.getSharedPreferences(prefsName(widgetId), Context.MODE_PRIVATE)
    private val list get() = PhotoList.of(c, widgetId)

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

    // Workers of one widget can overlap (a scan showing a photo while a tap moves on), so a change is read,
    // made and written without another one in between.
    private fun <T> edit(change: PhotoHistory.() -> T): T = synchronized(historyLock) {
        val h = load()
        h.change().also { save(h) }
    }

    fun current(): Long? = load().current

    /** The photo on the widget; it differs from [current] while a download was started but never finished. */
    fun shown(): Long? = prefs.getLong(KEY_SHOWN, -1).takeIf { it >= 0 }

    fun markShown(id: Long, plain: Boolean = false) =
        prefs.edit().putLong(KEY_SHOWN, id).putBoolean(KEY_SHOWN_PLAIN, plain).putBoolean(KEY_FAILED, false)
            .remove(KEY_INFO).apply()

    /** The date and time shown over the picture after a tap on the bottom of the widget; null while hidden. */
    fun info(): String? = prefs.getString(KEY_INFO, null)

    fun setInfo(info: String?) = prefs.edit().apply { if (info == null) remove(KEY_INFO) else putString(KEY_INFO, info) }.apply()

    /** Whether the shown picture is to be drawn without the picture filter. */
    fun shownIsPlain(): Boolean = prefs.getBoolean(KEY_SHOWN_PLAIN, false)

    /** Whether the list marks [id] as a picture that already has a look (a Snapseed export). */
    fun isPreFiltered(id: Long): Boolean = list.isPlain(id)

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

    fun restore(backup: PhotoHistory) = synchronized(historyLock) { save(backup) }

    /** Forgets the shown pictures but keeps the photo list, e.g. when only the time span changed. */
    fun resetHistory() = synchronized(historyLock) {
        prefs.edit().remove("history").remove("pos").remove(KEY_PREFETCH).apply()
    }

    fun reset() {
        list.clear()
        prefs.edit().clear().apply()
    }

    private fun timeFilter() = WidgetConfig(c, widgetId).timeFilter

    /** Whether there are photos at all, including those outside the time span. */
    fun hasList() = !list.isEmpty()

    /** The photos of the list that are inside the time span now, or what a running first scan has found so far. */
    fun knownIds(): LongArray = list.ids(timeFilter(), LocalDate.now()).takeIf { it.isNotEmpty() } ?: partial[widgetId] ?: LongArray(0)

    /** Forgets a photo that is gone from the server. */
    fun removeFromList(id: Long) = list.remove(id)

    // The id marker is also what tells the list in its database from the text file of version 2.0, which is scanned again.
    fun hasScanned() = prefs.contains(KEY_LIST_TIME) && prefs.contains(KEY_LAST_ID)

    private fun recordSuccess(start: Long, lastId: Long) {
        val end = System.currentTimeMillis()
        prefs.edit()
            .putLong(KEY_LIST_TIME, end)
            .putLong(KEY_LIST_DURATION, end - start)
            .putLong(KEY_LAST_ID, lastId)
            .remove(KEY_LIST_ERROR)
            .apply()
        File(WidgetFiles.dir(c, widgetId), "ids.txt").delete()
    }

    /**
     * Scans the folders now and replaces the list, recording when it happened and how long it took.
     * Returns the ids inside the time span.
     */
    fun refreshList(
        client: NextcloudClient, folders: List<String>, tags: List<Tag>,
        onFirstImage: ((Long) -> Unit)? = null,
        onProgress: ((Int, Int) -> Unit)? = null,
        isStopped: () -> Boolean = { false },
    ): LongArray {
        val filter = timeFilter()
        val today = LocalDate.now()
        val start = System.currentTimeMillis()
        var lastPartial = 0L
        val fresh = try {
            client.listImages(folders, tags, { filter.accepts(it, today) }, onFirstImage, { scanned, ids ->
                val now = SystemClock.elapsedRealtime()
                if (now - lastPartial >= PARTIAL_INTERVAL_MS) {
                    lastPartial = now
                    partial[widgetId] = ids.toLongArray()
                }
                onProgress?.invoke(scanned, ids.size)
            }, isStopped)
        } catch (e: Exception) {
            // Kept so the settings screen can explain why the widget stays empty.
            if (!isStopped()) prefs.edit().putString(KEY_LIST_ERROR, e.describe()).apply()
            throw e
        } finally {
            partial.remove(widgetId)
        }
        list.replaceAll(fresh)
        recordSuccess(start, fresh.maxOfOrNull { it.id } ?: -1L)
        return list.ids(filter, today)
    }

    /**
     * Adds the photos that are new on the server since the last scan or update to the list. Nothing is removed,
     * and the time span needs no work here as it is applied whenever a photo is picked. Scans instead when there
     * is no list yet, or when tags are used: they can be put on old photos, so the (single) tag query is repeated
     * in full.
     */
    fun updateList(
        client: NextcloudClient, folders: List<String>, tags: List<Tag>,
        onFirstImage: ((Long) -> Unit)? = null,
        onProgress: ((Int, Int) -> Unit)? = null,
        isStopped: () -> Boolean = { false },
    ): LongArray {
        val lastId = prefs.getLong(KEY_LAST_ID, -1L)
        if (tags.isNotEmpty() || list.isEmpty() || lastId < 0) {
            return refreshList(client, folders, tags, onFirstImage, onProgress, isStopped)
        }
        val start = System.currentTimeMillis()
        val added = try {
            client.listNewImages(folders, lastId, isStopped)
        } catch (e: Exception) {
            if (!isStopped()) prefs.edit().putString(KEY_LIST_ERROR, e.describe()).apply()
            throw e
        }
        list.add(added)
        recordSuccess(start, maxOf(lastId, added.maxOfOrNull { it.id } ?: lastId))
        return list.ids(timeFilter(), LocalDate.now())
    }

    class ListStats(val timestamp: Long, val durationMs: Long, val count: Int, val error: String?)

    /** The count is that of photos inside the time span right now. */
    fun listStats(): ListStats? {
        val p = prefs
        if (!p.contains(KEY_LIST_TIME) && !p.contains(KEY_LIST_ERROR)) return null
        return ListStats(
            p.getLong(KEY_LIST_TIME, 0), p.getLong(KEY_LIST_DURATION, 0), list.count(timeFilter(), LocalDate.now()),
            p.getString(KEY_LIST_ERROR, null),
        )
    }

    companion object {
        private const val PARTIAL_INTERVAL_MS = 2000L
        private const val KEY_LIST_TIME = "list_time"
        private const val KEY_LIST_DURATION = "list_duration"
        private const val KEY_LAST_ID = "last_id"
        private const val KEY_LIST_ERROR = "list_error"
        private const val KEY_PREFETCH = "prefetch"
        private const val KEY_SHOWN = "shown"
        private const val KEY_SHOWN_PLAIN = "shown_plain"
        private const val KEY_FAILED = "failed"
        private const val KEY_INFO = "info"

        private val historyLock = Any()

        /** Photos found so far by running scans, usable before a scan has finished. */
        private val partial = ConcurrentHashMap<Int, LongArray>()

        private fun prefsName(widgetId: Int) = "state_$widgetId"

        /** Removes everything stored for a deleted widget. */
        fun delete(c: Context, widgetId: Int) {
            c.deleteSharedPreferences(prefsName(widgetId))
            PhotoList.close(widgetId)
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
            for (name in listOf("current.jpg", "buffer")) File(c.filesDir, name).renameTo(File(dir, name))
        }
    }
}

/** Where the files of one widget live. */
object WidgetFiles {
    fun dir(c: Context, widgetId: Int) = File(c.filesDir, "widgets/$widgetId").apply { mkdirs() }
}
