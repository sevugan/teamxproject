package com.teamx.drift.core

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NightScheduleTest {

    // Wind-down 22:00, quiet 22:30, asleep 23:00, up at 06:00.
    private val night = NightSchedule.DEFAULT

    private fun at(day: Int, hour: Int, minute: Int = 0) =
        LocalDateTime.of(2026, 3, day, hour, minute)

    @Test
    fun `the ramp is derived from the sleep target`() {
        assertEquals(LocalTime.of(22, 0), night.windDownAt)
        assertEquals(LocalTime.of(22, 30), night.quietAt)
        assertEquals(LocalTime.of(23, 0), night.sleepAt)
        assertEquals(LocalTime.of(6, 0), night.wakeAt)
        assertEquals(Duration.ofHours(7), night.sleepDuration)
        assertEquals(Duration.ofHours(8), night.nightDuration)
        assertFalse(night.isEmpty)
    }

    @Test
    fun `moving bedtime moves the whole ramp`() {
        val earlier = night.copy(sleepAt = LocalTime.of(22, 15))

        assertEquals(LocalTime.of(21, 15), earlier.windDownAt)
        assertEquals(LocalTime.of(21, 45), earlier.quietAt)
    }

    @Test
    fun `each phase owns its own boundary minute`() {
        assertEquals(NightPhase.OPEN, night.phaseAt(LocalTime.of(21, 59, 59)))
        assertEquals(NightPhase.WIND_DOWN, night.phaseAt(LocalTime.of(22, 0, 0)))
        assertEquals(NightPhase.WIND_DOWN, night.phaseAt(LocalTime.of(22, 29, 59)))
        assertEquals(NightPhase.QUIET, night.phaseAt(LocalTime.of(22, 30, 0)))
        assertEquals(NightPhase.QUIET, night.phaseAt(LocalTime.of(22, 59, 59)))
        assertEquals(NightPhase.SLEEP, night.phaseAt(LocalTime.of(23, 0, 0)))
        assertEquals(NightPhase.SLEEP, night.phaseAt(LocalTime.of(5, 59, 59)))
        assertEquals(NightPhase.OPEN, night.phaseAt(LocalTime.of(6, 0, 0)))
        assertEquals(NightPhase.OPEN, night.phaseAt(LocalTime.of(12, 0)))
    }

    @Test
    fun `the sleep phase carries across midnight`() {
        assertEquals(NightPhase.SLEEP, night.phaseAt(LocalTime.of(23, 30)))
        assertEquals(NightPhase.SLEEP, night.phaseAt(LocalTime.MIDNIGHT))
        assertEquals(NightPhase.SLEEP, night.phaseAt(LocalTime.of(3, 0)))
    }

    @Test
    fun `phases advance in order through the night`() {
        assertEquals(at(4, 22, 0), night.nextChange(at(4, 20, 0)))
        assertEquals(at(4, 22, 30), night.nextChange(at(4, 22, 0)))
        assertEquals(at(4, 23, 0), night.nextChange(at(4, 22, 30)))
        assertEquals(at(5, 6, 0), night.nextChange(at(4, 23, 0)))
        assertEquals(at(5, 6, 0), night.nextChange(at(5, 2, 0)))
        assertEquals(at(5, 22, 0), night.nextChange(at(5, 6, 0)))
    }

    @Test
    fun `a boundary moment schedules the next boundary, never itself`() {
        // Standing exactly on 22:00 we are already winding down; the next thing to wake
        // for is 22:30, not this same instant again.
        assertEquals(at(4, 22, 30), night.nextChange(at(4, 22, 0)))
    }

    @Test
    fun `a night keeps one identity across midnight`() {
        val evening = night.nightId(at(4, 23, 30))
        val smallHours = night.nightId(at(5, 2, 0))

        assertEquals(LocalDate.of(2026, 3, 4), evening)
        assertEquals(evening, smallHours)
    }

    @Test
    fun `the wind-down and the small hours of one night share an identity`() {
        assertEquals(LocalDate.of(2026, 3, 4), night.nightId(at(4, 22, 5)))
        assertEquals(LocalDate.of(2026, 3, 4), night.nightId(at(5, 5, 59)))
    }

    @Test
    fun `after waking, the identity is the night to come`() {
        assertEquals(LocalDate.of(2026, 3, 5), night.nightId(at(5, 6, 0)))
        assertEquals(LocalDate.of(2026, 3, 5), night.nightId(at(5, 14, 0)))
    }

    @Test
    fun `a night entirely before midnight still works`() {
        val early = NightSchedule(sleepAt = LocalTime.of(21, 0), wakeAt = LocalTime.of(23, 30))

        assertEquals(NightPhase.WIND_DOWN, early.phaseAt(LocalTime.of(20, 15)))
        assertEquals(NightPhase.QUIET, early.phaseAt(LocalTime.of(20, 45)))
        assertEquals(NightPhase.SLEEP, early.phaseAt(LocalTime.of(22, 0)))
        assertEquals(NightPhase.OPEN, early.phaseAt(LocalTime.of(23, 30)))
        assertEquals(LocalDate.of(2026, 3, 4), early.nightId(at(4, 22, 0)))
    }

    @Test
    fun `equal leads collapse the wind-down and go straight to quiet`() {
        val abrupt = night.copy(
            windDownLead = Duration.ofMinutes(30),
            quietLead = Duration.ofMinutes(30),
        )

        assertEquals(LocalTime.of(22, 30), abrupt.windDownAt)
        assertEquals(NightPhase.OPEN, abrupt.phaseAt(LocalTime.of(22, 29)))
        assertEquals(NightPhase.QUIET, abrupt.phaseAt(LocalTime.of(22, 30)))
        assertEquals(NightPhase.SLEEP, abrupt.phaseAt(LocalTime.of(23, 0)))
    }

    @Test
    fun `a zero quiet lead runs the wind-down straight into sleep`() {
        val noQuiet = night.copy(quietLead = Duration.ZERO)

        assertEquals(NightPhase.WIND_DOWN, noQuiet.phaseAt(LocalTime.of(22, 30)))
        assertEquals(NightPhase.WIND_DOWN, noQuiet.phaseAt(LocalTime.of(22, 59, 59)))
        assertEquals(NightPhase.SLEEP, noQuiet.phaseAt(LocalTime.of(23, 0)))
    }

    @Test
    fun `a schedule with no sleep in it is off`() {
        val none = NightSchedule(sleepAt = LocalTime.of(23, 0), wakeAt = LocalTime.of(23, 0))

        assertTrue(none.isEmpty)
        assertEquals(NightPhase.OPEN, none.phaseAt(LocalTime.of(2, 0)))
        assertNull(none.nextChange(at(4, 23, 30)))
    }

    @Test
    fun `a ramp longer than a day is rejected as off`() {
        val absurd = NightSchedule(
            sleepAt = LocalTime.of(23, 0),
            wakeAt = LocalTime.of(22, 30),
            windDownLead = Duration.ofHours(2),
            quietLead = Duration.ofHours(1),
        )

        assertTrue(absurd.isEmpty)
        assertEquals(NightPhase.OPEN, absurd.phaseAt(LocalTime.of(3, 0)))
    }

    @Test
    fun `quiet cannot start before the wind-down does`() {
        assertFailsWith<IllegalArgumentException> {
            NightSchedule(
                sleepAt = LocalTime.of(23, 0),
                wakeAt = LocalTime.of(6, 0),
                windDownLead = Duration.ofMinutes(15),
                quietLead = Duration.ofMinutes(30),
            )
        }
    }

    @Test
    fun `nonsense leads are rejected at construction`() {
        assertFailsWith<IllegalArgumentException> {
            NightSchedule(LocalTime.of(23, 0), LocalTime.of(6, 0), Duration.ofMinutes(-1))
        }
        assertFailsWith<IllegalArgumentException> {
            NightSchedule(
                LocalTime.of(23, 0),
                LocalTime.of(6, 0),
                Duration.ofHours(13),
                Duration.ofHours(13),
            )
        }
    }

    @Test
    fun `the upcoming milestones are reported as moments`() {
        assertEquals(at(4, 23, 0), night.nextSleepAt(at(4, 20, 0)))
        assertEquals(at(5, 6, 0), night.nextWakeAt(at(4, 23, 30)))
        assertEquals(at(4, 22, 0), night.nextWindDownAt(at(4, 12, 0)))
        assertEquals(at(5, 22, 0), night.nextWindDownAt(at(4, 23, 30)))
    }
}
