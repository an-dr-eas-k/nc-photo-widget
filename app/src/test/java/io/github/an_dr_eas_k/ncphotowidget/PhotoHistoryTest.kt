package io.github.an_dr_eas_k.ncphotowidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class PhotoHistoryTest {
    /** Returns the given indices in order instead of random ones. */
    private class Picks(vararg val indices: Int) : Random() {
        private var i = 0
        override fun nextBits(bitCount: Int) = 0
        override fun nextInt(until: Int) = indices[i++]
    }

    private val known = longArrayOf(1, 2, 3, 4, 5)

    @Test
    fun nextOnEmptyListReturnsNull() {
        val h = PhotoHistory()
        assertNull(h.next(LongArray(0)))
        assertNull(h.current)
    }

    @Test
    fun nextAppendsAndBecomesCurrent() {
        val h = PhotoHistory(random = Random(1))
        val id = h.next(known)
        assertEquals(id, h.current)
        assertEquals(listOf(id), h.ids)
    }

    @Test
    fun previousThenNextReplaysHistory() {
        val h = PhotoHistory(listOf(1, 2, 3), pos = 2)
        assertEquals(2L, h.previous())
        assertEquals(1L, h.previous())
        assertEquals(1L, h.previous())
        assertEquals(2L, h.next(known))
        assertEquals(3L, h.next(known))
        assertEquals(listOf(1L, 2L, 3L), h.ids)
    }

    @Test
    fun nextAvoidsRepeatingTheLastPhoto() {
        val h = PhotoHistory(listOf(1), random = Picks(0, 0, 1))
        assertEquals(2L, h.next(longArrayOf(1, 2)))
    }

    @Test
    fun singlePhotoMayRepeat() {
        val h = PhotoHistory(listOf(7))
        assertEquals(7L, h.next(longArrayOf(7)))
    }

    @Test
    fun nextUsesAndClearsValidPrefetch() {
        val h = PhotoHistory(listOf(1), prefetched = 4)
        assertEquals(4L, h.next(known))
        assertNull(h.prefetched)
    }

    @Test
    fun prefetchNoLongerInListIsIgnored() {
        val h = PhotoHistory(listOf(1), prefetched = 99, random = Random(1))
        assertNotEquals(99L, h.next(known))
    }

    @Test
    fun historyIsCapped() {
        val h = PhotoHistory()
        for (id in 1L..40L) h.show(id)
        assertEquals(PhotoHistory.MAX_SIZE, h.ids.size)
        assertEquals(40L, h.current)
        assertEquals(11L, h.ids.first())
    }

    @Test
    fun dropCurrentKeepsPositionValid() {
        val h = PhotoHistory(listOf(1, 2, 3), pos = 2)
        h.dropCurrent()
        assertEquals(2L, h.current)
        h.dropCurrent()
        h.dropCurrent()
        assertNull(h.current)
        assertEquals(0, h.pos)
    }

    @Test
    fun neighborsInsideHistory() {
        val h = PhotoHistory(listOf(1, 2, 3), pos = 1)
        assertEquals(Triple(1L, 2L, 3L), h.neighbors(known))
        assertNull(h.prefetched)
    }

    @Test
    fun neighborsAtEndPrefetchesAndNextUsesIt() {
        val h = PhotoHistory(listOf(1, 2), pos = 1, random = Random(3))
        val (previous, current, upcoming) = h.neighbors(known)
        assertEquals(1L, previous)
        assertEquals(2L, current)
        assertEquals(upcoming, h.prefetched)
        assertEquals(upcoming, h.next(known))
    }
}
