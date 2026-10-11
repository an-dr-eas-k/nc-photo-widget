package io.github.an_dr_eas_k.ncphotowidget

import kotlin.random.Random

/** Back/forward history of shown photo ids, plus the one already chosen (and buffered) for the next tap. */
class PhotoHistory(
    ids: List<Long> = emptyList(),
    pos: Int = 0,
    prefetched: Long? = null,
    private val random: Random = Random.Default,
) {
    val ids: List<Long> get() = entries
    var pos = pos
        private set
    var prefetched = prefetched
        private set

    private val entries = ids.toMutableList()

    val current: Long? get() = entries.getOrNull(pos)

    /** Moves forward in the history, or appends a new photo from [known] at its end. */
    fun next(known: LongArray): Long? {
        if (pos < entries.size - 1) return entries[++pos]
        val id = upcoming(known, entries.lastOrNull()) ?: return null
        show(id)
        prefetched = null
        return id
    }

    fun previous(): Long? {
        pos = (pos - 1).coerceAtLeast(0)
        return current
    }

    /** Appends [id] and makes it the current entry. */
    fun show(id: Long) {
        entries.add(id)
        while (entries.size > MAX_SIZE) entries.removeAt(0)
        pos = entries.size - 1
    }

    /** Removes the current entry, e.g. when the file no longer exists on the server. */
    fun dropCurrent() {
        if (pos in entries.indices) entries.removeAt(pos)
        pos = pos.coerceAtMost(entries.size - 1).coerceAtLeast(0)
    }

    /** Previous, current and upcoming id; picks the upcoming one from [known] when the history ends here. */
    fun neighbors(known: LongArray): Triple<Long?, Long?, Long?> {
        val next = entries.getOrNull(pos + 1) ?: upcoming(known, current)?.also { prefetched = it }
        return Triple(entries.getOrNull(pos - 1), current, next)
    }

    /** The prefetched photo while still valid, else a random one avoiding [last]. */
    private fun upcoming(known: LongArray, last: Long?): Long? {
        prefetched?.takeIf { it in known && it != last }?.let { return it }
        if (known.isEmpty()) return null
        var id = known.random(random)
        repeat(5) { if (known.size > 1 && id == last) id = known.random(random) }
        return id
    }

    companion object {
        const val MAX_SIZE = 30
    }
}
