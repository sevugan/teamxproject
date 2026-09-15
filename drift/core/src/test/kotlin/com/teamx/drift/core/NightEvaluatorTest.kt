package com.teamx.drift.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NightEvaluatorTest {

    private val config = DriftConfig()
    private val tonight = LocalDate.of(2026, 3, 4)

    private fun at(day: Int, hour: Int, minute: Int = 0) =
        LocalDateTime.of(2026, 3, day, hour, minute)

    private fun instant(dt: LocalDateTime) = dt.toInstant(ZoneOffset.UTC)

    private fun evaluate(dt: LocalDateTime, state: EscapeHatchState = EscapeHatchState()) =
        NightEvaluator.evaluate(config, state, dt, instant(dt))

    @Test
    fun `the evening walks down the ramp`() {
        assertEquals(NightPhase.OPEN, evaluate(at(4, 21, 0)).phase)
        assertEquals(NightPhase.WIND_DOWN, evaluate(at(4, 22, 10)).phase)
        assertEquals(NightPhase.QUIET, evaluate(at(4, 22, 40)).phase)
        assertEquals(NightPhase.SLEEP, evaluate(at(4, 23, 10)).phase)
        assertEquals(NightPhase.SLEEP, evaluate(at(5, 3, 0)).phase)
        assertEquals(NightPhase.OPEN, evaluate(at(5, 6, 0)).phase)
    }

    @Test
    fun `a restricting phase reports what it is holding back and when it ends`() {
        val status = evaluate(at(4, 23, 10))

        assertTrue(status.restricts)
        assertEquals(NightPhase.SLEEP, status.enforced)
        assertEquals(at(5, 6, 0), status.changesAt)
        assertFalse(status.isBorrowingTime)
        assertFalse(status.scheduleOff)
    }

    @Test
    fun `the open phase restricts nothing and points at the next wind-down`() {
        val status = evaluate(at(4, 12, 0))

        assertFalse(status.restricts)
        assertEquals(NightPhase.OPEN, status.enforced)
        assertEquals(at(4, 22, 0), status.changesAt)
    }

    @Test
    fun `borrowed time opens the phone without pretending the night paused`() {
        val nowLocal = at(5, 1, 0)
        val state = config.escapeHatch.open(EscapeHatchState(), tonight, instant(nowLocal))

        val status = evaluate(nowLocal, state)

        // The schedule still says we should be asleep...
        assertEquals(NightPhase.SLEEP, status.phase)
        // ...but nothing is being enforced for the next quarter of an hour.
        assertEquals(NightPhase.OPEN, status.enforced)
        assertFalse(status.restricts)
        assertTrue(status.isBorrowingTime)
        assertEquals(Duration.ofMinutes(15), status.borrowedTimeLeft(instant(nowLocal)))
        assertEquals(at(5, 6, 0), status.changesAt)
    }

    @Test
    fun `the night resumes by itself when borrowed time runs out`() {
        val openedAt = at(5, 1, 0)
        val state = config.escapeHatch.open(EscapeHatchState(), tonight, instant(openedAt))

        assertFalse(evaluate(at(5, 1, 14), state).restricts)
        assertTrue(evaluate(at(5, 1, 15), state).restricts)
        assertEquals(NightPhase.SLEEP, evaluate(at(5, 1, 15), state).enforced)
    }

    @Test
    fun `borrowed time taken during the wind-down resumes into the wind-down`() {
        val openedAt = at(4, 22, 5)
        val state = config.escapeHatch.open(EscapeHatchState(), tonight, instant(openedAt))

        assertEquals(NightPhase.OPEN, evaluate(at(4, 22, 10), state).enforced)
        assertEquals(NightPhase.WIND_DOWN, evaluate(at(4, 22, 21), state).enforced)
    }

    @Test
    fun `a stale window from a previous night opens nothing tonight`() {
        val state = EscapeHatchState(
            nightId = tonight.minusDays(1),
            usesThisNight = 1,
            openUntil = instant(at(4, 1, 15)),
        )

        assertTrue(evaluate(at(4, 23, 30), state).restricts)
    }

    @Test
    fun `switching Drift off reports off and schedules nothing`() {
        val off = config.copy(enabled = false)
        val moment = at(4, 23, 30)

        val status = NightEvaluator.evaluate(off, EscapeHatchState(), moment, instant(moment))

        assertEquals(NightStatus.OFF, status)
        assertTrue(status.scheduleOff)
        assertFalse(status.restricts)
        assertNull(NightEvaluator.nextWakeUp(off, EscapeHatchState(), moment, instant(moment)))
    }

    @Test
    fun `a schedule covering nothing reports off`() {
        val zero = config.copy(
            schedule = NightSchedule(LocalTime.of(23, 0), LocalTime.of(23, 0)),
        )
        val moment = at(4, 23, 30)

        assertEquals(
            NightStatus.OFF,
            NightEvaluator.evaluate(zero, EscapeHatchState(), moment, instant(moment)),
        )
    }

    @Test
    fun `the next wake up is the next phase boundary`() {
        assertEquals(
            at(4, 22, 0),
            NightEvaluator.nextWakeUp(config, EscapeHatchState(), at(4, 20, 0), instant(at(4, 20, 0))),
        )
        assertEquals(
            at(4, 22, 30),
            NightEvaluator.nextWakeUp(config, EscapeHatchState(), at(4, 22, 0), instant(at(4, 22, 0))),
        )
    }

    @Test
    fun `expiring borrowed time wakes the app before the next boundary`() {
        val nowLocal = at(5, 1, 0)
        val state = config.escapeHatch.open(EscapeHatchState(), tonight, instant(nowLocal))

        assertEquals(
            at(5, 1, 15),
            NightEvaluator.nextWakeUp(config, state, nowLocal, instant(nowLocal)),
        )
    }

    @Test
    fun `a window outlasting the night does not delay the morning`() {
        val generous = config.copy(escapeHatch = EscapeHatchPolicy(grantDuration = Duration.ofHours(8)))
        val nowLocal = at(5, 1, 0)
        val state = generous.escapeHatch.open(EscapeHatchState(), tonight, instant(nowLocal))

        assertEquals(
            at(5, 6, 0),
            NightEvaluator.nextWakeUp(generous, state, nowLocal, instant(nowLocal)),
        )
    }

    @Test
    fun `an already expired window is ignored when scheduling`() {
        val state = EscapeHatchState(tonight, 1, Instant.parse("2026-03-05T00:00:00Z"))
        val nowLocal = at(5, 2, 0)

        assertEquals(
            at(5, 6, 0),
            NightEvaluator.nextWakeUp(config, state, nowLocal, instant(nowLocal)),
        )
    }
}
