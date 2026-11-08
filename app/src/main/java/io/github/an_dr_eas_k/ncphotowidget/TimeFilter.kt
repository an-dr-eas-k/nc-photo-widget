package io.github.an_dr_eas_k.ncphotowidget

import java.time.LocalDate
import java.time.MonthDay

/** Stands for a photo whose day is unknown; days are counted since 1970-01-01. */
const val NO_DAY = Int.MIN_VALUE

/**
 * Applied when the photo list is built, so a long list refresh interval lets the span lag behind the
 * calendar. Keeps the photos taken in the last [days] days. With [repeatYears] it also keeps the same
 * stretch of the calendar in every earlier year: today - [days] until today, shifted back by whole years.
 */
data class TimeFilter(val days: Int = 0, val repeatYears: Boolean = false) {
    val active get() = days > 0

    /** Repeating needs a time span shorter than a year, or the stretches would overlap. */
    val canRepeat get() = days in 1..DAYS_PER_YEAR

    fun accepts(day: Int, today: LocalDate = LocalDate.now()): Boolean {
        if (!active) return true
        if (day == NO_DAY) return false
        val taken = LocalDate.ofEpochDay(day.toLong())
        if (!(repeatYears && canRepeat)) return inWindow(taken, today)
        // A stretch can reach into the year before its end date, so the taken year and the next both count.
        val monthDay = MonthDay.from(today)
        return (taken.year..taken.year + 1).any { year ->
            val end = monthDay.atYear(year)
            !end.isAfter(today) && inWindow(taken, end)
        }
    }

    /**
     * The same as [accepts], as ranges of days (inclusive) to be matched by a database query;
     * null when there is no time span. Photos from before 1970 are not matched when repeating.
     */
    fun dayRanges(today: LocalDate = LocalDate.now()): List<IntRange>? {
        if (!active) return null
        fun window(end: LocalDate) = end.minusDays(days.toLong()).toEpochDay().toInt()..end.toEpochDay().toInt()
        if (!(repeatYears && canRepeat)) return listOf(window(today))
        val monthDay = MonthDay.from(today)
        return (FIRST_YEAR..today.year).map { monthDay.atYear(it) }.filter { !it.isAfter(today) }.map { window(it) }
    }

    private fun inWindow(taken: LocalDate, end: LocalDate) = !taken.isAfter(end) && !taken.isBefore(end.minusDays(days.toLong()))

    companion object {
        const val DAYS_PER_YEAR = 365
        private const val FIRST_YEAR = 1970
    }
}
