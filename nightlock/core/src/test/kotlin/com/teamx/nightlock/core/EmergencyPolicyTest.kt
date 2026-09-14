package com.teamx.nightlock.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmergencyPolicyTest {

    private val policy = EmergencyPolicy()
    private val tonight = LocalDate.of(2026, 3, 4)
    private val lastNight = tonight.minusDays(1)
    private val now: Instant = Instant.parse("2026-03-05T00:30:00Z")

    @Test
    fun `a fresh night starts with the full allowance`() {
        assertEquals(3, policy.usesLeft(EmergencyState(), tonight))
        assertIs<EmergencyDecision.Allowed>(policy.evaluate(EmergencyState(), tonight))
    }

    @Test
    fun `granting starts a timed bypass and spends one use`() {
        val granted = policy.grant(EmergencyState(), tonight, now)

        assertEquals(tonight, granted.nightId)
        assertEquals(1, granted.usesThisNight)
        assertEquals(now.plus(Duration.ofMinutes(15)), granted.bypassUntil)
        assertTrue(granted.isBypassActive(now))
        assertEquals(2, policy.usesLeft(granted, tonight))
    }

    @Test
    fun `the bypass expires on its own and the phone relocks`() {
        val granted = policy.grant(EmergencyState(), tonight, now)

        assertTrue(granted.isBypassActive(now.plus(Duration.ofMinutes(14))))
        assertEquals(Duration.ofMinutes(1), granted.bypassRemaining(now.plus(Duration.ofMinutes(14))))
        assertFalse(granted.isBypassActive(now.plus(Duration.ofMinutes(15))))
        assertEquals(Duration.ZERO, granted.bypassRemaining(now.plus(Duration.ofHours(1))))
    }

    @Test
    fun `the allowance runs out after the configured number of unlocks`() {
        var state = EmergencyState()
        repeat(3) { state = policy.grant(state, tonight, now) }

        assertEquals(3, state.usesThisNight)
        assertEquals(0, policy.usesLeft(state, tonight))
        val decision = policy.evaluate(state, tonight)
        assertIs<EmergencyDecision.Exhausted>(decision)
        assertEquals(3, decision.maxPerNight)
    }

    @Test
    fun `an exhausted allowance cannot be spent again`() {
        var state = EmergencyState()
        repeat(3) { state = policy.grant(state, tonight, now) }
        val spent = state.copy(bypassUntil = null)

        val afterRefusal = policy.grant(spent, tonight, now)

        assertEquals(spent, afterRefusal)
        assertNull(afterRefusal.bypassUntil)
    }

    @Test
    fun `the allowance refreshes on the next night`() {
        var state = EmergencyState()
        repeat(3) { state = policy.grant(state, lastNight, now) }

        assertEquals(0, policy.usesLeft(state, lastNight))
        assertEquals(3, policy.usesLeft(state, tonight))
        assertEquals(1, policy.grant(state, tonight, now).usesThisNight)
    }

    @Test
    fun `ending a bypass early keeps the use counted`() {
        val granted = policy.grant(EmergencyState(), tonight, now)

        val ended = policy.endBypass(granted)

        assertFalse(ended.isBypassActive(now))
        assertEquals(1, ended.usesThisNight)
        assertEquals(2, policy.usesLeft(ended, tonight))
    }

    @Test
    fun `an unlimited allowance never runs out`() {
        val unlimited = EmergencyPolicy(maxUnlocksPerNight = null)
        var state = EmergencyState()
        repeat(10) { state = unlimited.grant(state, tonight, now) }

        assertNull(unlimited.usesLeft(state, tonight))
        assertIs<EmergencyDecision.Allowed>(unlimited.evaluate(state, tonight))
        assertEquals(10, state.usesThisNight)
    }

    @Test
    fun `a zero allowance refuses every unlock`() {
        val none = EmergencyPolicy(maxUnlocksPerNight = 0)

        assertIs<EmergencyDecision.Exhausted>(none.evaluate(EmergencyState(), tonight))
        assertNull(none.grant(EmergencyState(), tonight, now).bypassUntil)
    }

    @Test
    fun `nonsense configuration is rejected at construction`() {
        assertFailsWith<IllegalArgumentException> { EmergencyPolicy(maxUnlocksPerNight = -1) }
        assertFailsWith<IllegalArgumentException> { EmergencyPolicy(grantDuration = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> {
            EmergencyPolicy(holdToConfirm = Duration.ofSeconds(-1))
        }
    }
}
