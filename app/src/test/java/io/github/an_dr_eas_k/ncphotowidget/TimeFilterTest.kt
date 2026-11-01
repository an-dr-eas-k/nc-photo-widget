package io.github.an_dr_eas_k.ncphotowidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TimeFilterTest {
    private val today = LocalDate.of(2026, 11, 1)

    private fun day(year: Int, month: Int, dayOfMonth: Int) = LocalDate.of(year, month, dayOfMonth).toEpochDay().toInt()

    private fun TimeFilter.accepts(year: Int, month: Int, dayOfMonth: Int) = accepts(day(year, month, dayOfMonth), today)

    @Test
    fun inactiveFilterAcceptsEverything() {
        assertTrue(TimeFilter(0).accepts(day(1999, 1, 1), today))
        assertTrue(TimeFilter(0).accepts(NO_DAY, today))
    }

    @Test
    fun lastDaysWithoutRepeat() {
        val filter = TimeFilter(20)
        assertTrue(filter.accepts(2026, 11, 1))
        assertTrue(filter.accepts(2026, 10, 12))
        assertFalse(filter.accepts(2026, 10, 11))
        assertFalse(filter.accepts(2025, 10, 20))
        assertFalse(filter.accepts(2026, 11, 2))
        assertFalse(filter.accepts(NO_DAY, today))
    }

    @Test
    fun repeatedInEarlierYears() {
        val filter = TimeFilter(20, repeatYears = true)
        assertTrue(filter.accepts(2025, 10, 25))
        assertTrue(filter.accepts(2024, 11, 1))
        assertFalse(filter.accepts(2024, 11, 2))
        assertFalse(filter.accepts(2025, 6, 1))
        assertFalse(filter.accepts(2026, 11, 2))
    }

    @Test
    fun repeatedStretchAcrossNewYear() {
        val filter = TimeFilter(20, repeatYears = true)
        val early = LocalDate.of(2026, 1, 5)
        assertTrue(filter.accepts(day(2025, 12, 20), early))
        assertTrue(filter.accepts(day(2024, 12, 20), early))
        assertFalse(filter.accepts(day(2025, 12, 10), early))
        // Next year's stretch lies in the future for a photo of late December 2026.
        assertFalse(filter.accepts(day(2026, 12, 20), early))
    }

    @Test
    fun leapDayEndsOnTheLastFebruaryDayInOtherYears() {
        val filter = TimeFilter(3, repeatYears = true)
        val leapDay = LocalDate.of(2028, 2, 29)
        assertTrue(filter.accepts(day(2027, 2, 27), leapDay))
        assertTrue(filter.accepts(day(2027, 2, 28), leapDay))
    }

    @Test
    fun repeatIsIgnoredForSpansOverAYear() {
        val filter = TimeFilter(400, repeatYears = true)
        assertFalse(filter.canRepeat)
        assertFalse(filter.accepts(2020, 11, 1))
        assertTrue(filter.accepts(2025, 11, 1))
        assertEquals(true, TimeFilter(365).canRepeat)
        assertEquals(false, TimeFilter(366).canRepeat)
        assertEquals(false, TimeFilter(0).canRepeat)
    }
}
