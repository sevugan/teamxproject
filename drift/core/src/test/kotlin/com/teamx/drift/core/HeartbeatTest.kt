package com.teamx.drift.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeartbeatMonitorTest {

    private val beat: Instant = Instant.parse("2026-03-04T21:40:00Z")

    @Test
    fun `a fresh install has not had an outage`() {
        assertNull(HeartbeatMonitor.outageSince(lastBeat = null, now = beat))
    }

    @Test
    fun `an ordinary tick is not an outage`() {
        assertNull(HeartbeatMonitor.outageSince(beat, beat.plusSeconds(30)))
        assertNull(HeartbeatMonitor.outageSince(beat, beat.plus(Duration.ofMinutes(4))))
    }

    @Test
    fun `a long silence is an outage, measured end to end`() {
        val backAt = beat.plus(Duration.ofHours(9))

        val outage = HeartbeatMonitor.outageSince(beat, backAt)

        assertNotNull(outage)
        assertEquals(beat, outage.from)
        assertEquals(backAt, outage.to)
        assertEquals(Duration.ofHours(9), outage.duration)
    }

    @Test
    fun `the boundary is the tolerance itself`() {
        val tolerance = HeartbeatMonitor.DEFAULT_TOLERANCE

        assertNull(HeartbeatMonitor.outageSince(beat, beat.plus(tolerance)))
        assertNotNull(HeartbeatMonitor.outageSince(beat, beat.plus(tolerance).plusSeconds(1)))
    }

    @Test
    fun `a tighter tolerance can be asked for`() {
        val outage = HeartbeatMonitor.outageSince(
            beat,
            beat.plus(Duration.ofMinutes(2)),
            tolerance = Duration.ofMinutes(1),
        )

        assertNotNull(outage)
    }

    @Test
    fun `a clock that jumped backwards is not reported as an outage`() {
        // A timezone change or a manual clock edit should not invent a failure.
        assertNull(HeartbeatMonitor.outageSince(beat, beat.minus(Duration.ofHours(3))))
        assertNull(HeartbeatMonitor.outageSince(beat, beat))
    }

    @Test
    fun `an outage cannot end before it starts`() {
        assertFailsWith<IllegalArgumentException> { Outage(beat, beat.minusSeconds(1)) }
    }
}

class OutageImpactTest {

    private val night = NightSchedule.DEFAULT // wind-down 22:00, quiet 22:30, sleep 23:00, up 06:00

    private fun at(day: Int, hour: Int, minute: Int = 0) =
        LocalDateTime.of(2026, 3, day, hour, minute)

    @Test
    fun `an outage in the afternoon costs nothing`() {
        assertEquals(
            Duration.ZERO,
            OutageImpact.restrictedTimeMissed(at(4, 13), at(4, 17), night),
        )
        assertFalse(OutageImpact.missedANight(at(4, 13), at(4, 17), night))
    }

    @Test
    fun `an outage across the whole night costs the whole night`() {
        // 21:40 to 07:10 covers 22:00 to 06:00, which is eight hours of ramp.
        val missed = OutageImpact.restrictedTimeMissed(at(4, 21, 40), at(5, 7, 10), night)

        assertEquals(Duration.ofHours(8), missed)
        assertTrue(OutageImpact.missedANight(at(4, 21, 40), at(5, 7, 10), night))
    }

    @Test
    fun `an outage that only clips the start still counts`() {
        // 21:50 to 22:20 is ten minutes of unrestricted time and twenty of wind-down.
        val missed = OutageImpact.restrictedTimeMissed(at(4, 21, 50), at(4, 22, 20), night)

        assertEquals(Duration.ofMinutes(20), missed)
        assertTrue(OutageImpact.missedANight(at(4, 21, 50), at(4, 22, 20), night))
    }

    @Test
    fun `an outage entirely inside the night counts entirely`() {
        assertEquals(
            Duration.ofHours(2),
            OutageImpact.restrictedTimeMissed(at(5, 1), at(5, 3), night),
        )
    }

    @Test
    fun `an outage over several days stays bounded and still reports`() {
        // Three nights of eight hours each, sampled coarsely rather than walked minute by
        // minute. Exactness matters less here than the walk terminating.
        val missed = OutageImpact.restrictedTimeMissed(at(4, 12), at(7, 12), night)

        assertTrue(missed > Duration.ofHours(20), "expected roughly 24 hours, got $missed")
        assertTrue(missed < Duration.ofHours(28), "expected roughly 24 hours, got $missed")
    }

    @Test
    fun `a schedule that is off cannot be missed`() {
        val off = night.copy(wakeAt = night.sleepAt)

        assertTrue(off.isEmpty)
        assertEquals(
            Duration.ZERO,
            OutageImpact.restrictedTimeMissed(at(4, 21), at(5, 8), off),
        )
    }

    @Test
    fun `a zero length outage costs nothing`() {
        assertEquals(
            Duration.ZERO,
            OutageImpact.restrictedTimeMissed(at(4, 23), at(4, 23), night),
        )
    }
}
