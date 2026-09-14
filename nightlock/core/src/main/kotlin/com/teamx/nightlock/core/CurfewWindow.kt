package com.teamx.nightlock.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * A recurring daily window such as 23:00 -> 06:00, which wraps past midnight.
 *
 * The window is half open: [start] is inside it, [end] is not. A phone locked at
 * 23:00:00 is therefore released the instant the clock reads 06:00:00.
 */
data class CurfewWindow(val start: LocalTime, val end: LocalTime) {

    /** True when the window runs past midnight into the next calendar day. */
    val wrapsMidnight: Boolean get() = end < start

    /** A zero length window covers nothing, i.e. the curfew is effectively off. */
    val isEmpty: Boolean get() = start == end

    fun contains(time: LocalTime): Boolean = when {
        isEmpty -> false
        wrapsMidnight -> time >= start || time < end
        else -> time >= start && time < end
    }

    fun contains(dateTime: LocalDateTime): Boolean = contains(dateTime.toLocalTime())

    /** The first moment strictly after [from] at which the curfew begins. */
    fun nextStart(from: LocalDateTime): LocalDateTime {
        val today = from.toLocalDate().atTime(start)
        return if (today > from) today else today.plusDays(1)
    }

    /** The first moment strictly after [from] at which the curfew ends. */
    fun nextEnd(from: LocalDateTime): LocalDateTime {
        val today = from.toLocalDate().atTime(end)
        return if (today > from) today else today.plusDays(1)
    }

    /** The next moment at which the locked/unlocked state flips. */
    fun nextTransition(from: LocalDateTime): LocalDateTime =
        if (contains(from)) nextEnd(from) else nextStart(from)

    /**
     * Stable identity for "the night this moment belongs to": the calendar date on
     * which the curfew covering (or next covering) [dateTime] begins.
     *
     * With a 23:00 -> 06:00 window, 23:30 on the 4th and 02:00 on the 5th are both
     * the night of the 4th, so the nightly emergency allowance resets once per night
     * rather than at midnight, in the middle of the lock.
     */
    fun nightId(dateTime: LocalDateTime): LocalDate {
        val date = dateTime.toLocalDate()
        return if (wrapsMidnight && dateTime.toLocalTime() < end) date.minusDays(1) else date
    }

    companion object {
        /** The default asked for: locked from 23:00, released at 06:00. */
        val DEFAULT = CurfewWindow(LocalTime.of(23, 0), LocalTime.of(6, 0))
    }
}
