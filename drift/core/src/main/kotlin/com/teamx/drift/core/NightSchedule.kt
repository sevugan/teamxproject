package com.teamx.drift.core

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * One night, expressed the way a person thinks about it: "I want to be asleep by 22:45
 * and up at 06:00." The two quieter stages before sleep are leads off that target, so
 * moving bedtime moves the whole ramp with it.
 *
 * ```
 *  21:45        22:15        22:45                06:00
 *    |            |            |                    |
 *    +- WIND_DOWN +--- QUIET --+------ SLEEP -------+--> OPEN
 * ```
 *
 * Every boundary is half open: the phase starting at 22:45 owns 22:45:00 exactly.
 */
data class NightSchedule(
    /** The moment the phone should be finished with you. */
    val sleepAt: LocalTime,
    /** The moment it hands itself back. */
    val wakeAt: LocalTime,
    /** How far before [sleepAt] the wind-down begins. */
    val windDownLead: Duration = Duration.ofMinutes(60),
    /** How far before [sleepAt] the quiet stage begins. Never longer than the wind-down. */
    val quietLead: Duration = Duration.ofMinutes(30),
) {
    init {
        require(!windDownLead.isNegative) { "windDownLead must not be negative" }
        require(!quietLead.isNegative) { "quietLead must not be negative" }
        require(quietLead <= windDownLead) { "quiet cannot start before the wind-down does" }
        require(windDownLead < Duration.ofHours(12)) { "windDownLead must be under 12 hours" }
    }

    /** When the phone first starts getting quieter. */
    val windDownAt: LocalTime get() = sleepAt.minus(windDownLead)

    /** When only the essentials are left. */
    val quietAt: LocalTime get() = sleepAt.minus(quietLead)

    /** How long the phone stays fully asleep. */
    val sleepDuration: Duration
        get() = Duration.ofSeconds(secondsBetween(sleepAt, wakeAt))

    /** The whole ramp, from the first restriction to the morning release. */
    val nightDuration: Duration get() = windDownLead.plus(sleepDuration)

    /**
     * A night with no sleep in it is no night at all: the schedule is effectively off.
     * Also true when the ramp would be so long it swallows the following day.
     */
    val isEmpty: Boolean
        get() = sleepDuration.isZero || nightDuration > Duration.ofHours(24)

    fun phaseAt(time: LocalTime): NightPhase {
        if (isEmpty) return NightPhase.OPEN
        val elapsed = secondsBetween(windDownAt, time)
        val quietStarts = windDownLead.minus(quietLead).seconds
        val sleepStarts = windDownLead.seconds
        val nightEnds = nightDuration.seconds
        return when {
            elapsed < quietStarts -> NightPhase.WIND_DOWN
            elapsed < sleepStarts -> NightPhase.QUIET
            elapsed < nightEnds -> NightPhase.SLEEP
            else -> NightPhase.OPEN
        }
    }

    fun phaseAt(dateTime: LocalDateTime): NightPhase = phaseAt(dateTime.toLocalTime())

    /** The next moment the phase changes, or null when the schedule is off. */
    fun nextChange(from: LocalDateTime): LocalDateTime? {
        if (isEmpty) return null
        val current = phaseAt(from)
        return boundaries()
            .map { next(it, from) }
            .filter { phaseAt(it) != current }
            .minOrNull()
    }

    /** Tonight's sleep target as a moment, counting from [from]. */
    fun nextSleepAt(from: LocalDateTime): LocalDateTime = next(sleepAt, from)

    /** The next morning release, counting from [from]. */
    fun nextWakeAt(from: LocalDateTime): LocalDateTime = next(wakeAt, from)

    /** The next time the phone first starts getting quieter, counting from [from]. */
    fun nextWindDownAt(from: LocalDateTime): LocalDateTime = next(windDownAt, from)

    /**
     * Stable identity for "the night this moment belongs to": the date its wind-down
     * begins on. 23:30 on the 4th and 02:00 on the 5th are one night, so the escape
     * hatch's nightly allowance refreshes once a night rather than at midnight, in the
     * middle of the sleep phase.
     */
    fun nightId(dateTime: LocalDateTime): LocalDate {
        val date = dateTime.toLocalDate()
        if (isEmpty) return date
        val inTheNight = phaseAt(dateTime).restricts
        val clockPastMidnightOfTheNight = dateTime.toLocalTime() < windDownAt
        return if (inTheNight && clockPastMidnightOfTheNight) date.minusDays(1) else date
    }

    private fun boundaries(): List<LocalTime> =
        listOf(windDownAt, quietAt, sleepAt, wakeAt).distinct()

    /** The first occurrence of [time] strictly after [from]. */
    private fun next(time: LocalTime, from: LocalDateTime): LocalDateTime {
        val today = from.toLocalDate().atTime(time)
        return if (today > from) today else today.plusDays(1)
    }

    private fun secondsBetween(from: LocalTime, to: LocalTime): Long =
        Math.floorMod(to.toSecondOfDay().toLong() - from.toSecondOfDay().toLong(), SECONDS_PER_DAY)

    companion object {
        private const val SECONDS_PER_DAY = 24L * 60 * 60

        /** Wind-down at 22:00, quiet at 22:30, asleep 23:00, up at 06:00. */
        val DEFAULT = NightSchedule(
            sleepAt = LocalTime.of(23, 0),
            wakeAt = LocalTime.of(6, 0),
        )
    }
}
