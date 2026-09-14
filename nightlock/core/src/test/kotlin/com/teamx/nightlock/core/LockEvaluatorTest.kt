package com.teamx.nightlock.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LockEvaluatorTest {

    private val config = LockConfig()
    private val tonight = LocalDate.of(2026, 3, 4)

    private fun at(day: Int, hour: Int, minute: Int = 0) =
        LocalDateTime.of(2026, 3, day, hour, minute)

    private fun instant(dt: LocalDateTime) = dt.toInstant(ZoneOffset.UTC)

    private fun evaluate(dt: LocalDateTime, state: EmergencyState = EmergencyState()) =
        LockEvaluator.evaluate(config, state, dt, instant(dt))

    @Test
    fun `phone is locked at 2300 until 0600`() {
        val status = evaluate(at(4, 23, 1))

        assertIs<LockStatus.Locked>(status)
        assertEquals(at(5, 6), status.lockedUntil)
        assertTrue(status.shouldEnforceLockScreen)
    }

    @Test
    fun `phone is free at 0600`() {
        val status = evaluate(at(5, 6, 0))

        assertIs<LockStatus.Unlocked>(status)
        assertEquals(at(5, 23), status.nextLockAt)
        assertFalse(status.shouldEnforceLockScreen)
    }

    @Test
    fun `an emergency unlock suspends enforcement without ending the curfew`() {
        val nowLocal = at(5, 1, 0)
        val state = config.emergency.grant(EmergencyState(), tonight, instant(nowLocal))

        val status = evaluate(nowLocal, state)

        assertIs<LockStatus.Bypassed>(status)
        assertEquals(instant(nowLocal).plus(Duration.ofMinutes(15)), status.bypassUntil)
        assertEquals(at(5, 6), status.curfewEndsAt)
        assertFalse(status.shouldEnforceLockScreen)
    }

    @Test
    fun `the phone relocks by itself when the emergency grant expires`() {
        val grantedAt = at(5, 1, 0)
        val state = config.emergency.grant(EmergencyState(), tonight, instant(grantedAt))

        assertIs<LockStatus.Bypassed>(evaluate(at(5, 1, 14), state))
        assertIs<LockStatus.Locked>(evaluate(at(5, 1, 15), state))
        assertIs<LockStatus.Locked>(evaluate(at(5, 3, 0), state))
    }

    @Test
    fun `a stale grant from a previous night does not unlock tonight`() {
        val state = EmergencyState(
            nightId = tonight.minusDays(1),
            usesThisNight = 1,
            bypassUntil = instant(at(4, 1, 15)),
        )

        assertIs<LockStatus.Locked>(evaluate(at(4, 23, 30), state))
    }

    @Test
    fun `switching the curfew off reports Off and schedules nothing`() {
        val off = config.copy(enabled = false)

        assertEquals(LockStatus.Off, LockEvaluator.evaluate(off, EmergencyState(), at(4, 23, 30), instant(at(4, 23, 30))))
        assertNull(LockEvaluator.nextWakeUp(off, EmergencyState(), at(4, 23, 30), instant(at(4, 23, 30))))
    }

    @Test
    fun `a zero length window reports Off`() {
        val zero = config.copy(window = CurfewWindow(LocalTime.of(23, 0), LocalTime.of(23, 0)))

        assertEquals(LockStatus.Off, LockEvaluator.evaluate(zero, EmergencyState(), at(4, 23, 30), instant(at(4, 23, 30))))
    }

    @Test
    fun `the next wake up is the curfew boundary`() {
        assertEquals(
            at(4, 23),
            LockEvaluator.nextWakeUp(config, EmergencyState(), at(4, 20), instant(at(4, 20))),
        )
        assertEquals(
            at(5, 6),
            LockEvaluator.nextWakeUp(config, EmergencyState(), at(4, 23, 30), instant(at(4, 23, 30))),
        )
    }

    @Test
    fun `an expiring emergency grant wakes the app before the curfew ends`() {
        val nowLocal = at(5, 1, 0)
        val state = config.emergency.grant(EmergencyState(), tonight, instant(nowLocal))

        val wake = LockEvaluator.nextWakeUp(config, state, nowLocal, instant(nowLocal))

        assertEquals(at(5, 1, 15), wake)
    }

    @Test
    fun `a grant that outlasts the curfew does not delay the morning wake up`() {
        val longGrant = config.copy(emergency = EmergencyPolicy(grantDuration = Duration.ofHours(8)))
        val nowLocal = at(5, 1, 0)
        val state = longGrant.emergency.grant(EmergencyState(), tonight, instant(nowLocal))

        val wake = LockEvaluator.nextWakeUp(longGrant, state, nowLocal, instant(nowLocal))

        assertEquals(at(5, 6), wake)
    }

    @Test
    fun `an already expired grant is ignored when scheduling`() {
        val state = EmergencyState(tonight, 1, Instant.parse("2026-03-05T00:00:00Z"))
        val nowLocal = at(5, 2, 0)

        assertEquals(
            at(5, 6),
            LockEvaluator.nextWakeUp(config, state, nowLocal, instant(nowLocal)),
        )
    }
}
