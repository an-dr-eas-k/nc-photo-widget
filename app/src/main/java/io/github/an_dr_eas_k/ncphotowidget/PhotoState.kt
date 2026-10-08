package io.github.an_dr_eas_k.ncphotowidget

import android.content.Context
import java.io.File

/** Shared widget state: a cached list of image ids plus a back/forward history of shown ones. */
object PhotoState {
    private const val MAX_HISTORY = 30
    private const val LIST_MAX_AGE_MS = 24L * 60 * 60 * 1000

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

    /** Returns the cached id list, re-scanning the server when it is missing or older than a day. */
    fun ids(c: Context, client: NextcloudClient, folder: String): LongArray {
        val f = listFile(c)
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < LIST_MAX_AGE_MS) {
            val cached = f.readLines().mapNotNull { it.toLongOrNull() }.toLongArray()
            if (cached.isNotEmpty()) return cached
        }
        val fresh = client.listImageIds(folder)
        if (fresh.isNotEmpty()) f.writeText(fresh.joinToString("\n"))
        return fresh
    }
}
