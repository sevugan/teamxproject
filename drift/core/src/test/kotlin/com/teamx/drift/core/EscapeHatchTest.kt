package com.teamx.drift.core

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

class EscapeHatchTest {

    private val policy = EscapeHatchPolicy()
    private val tonight = LocalDate.of(2026, 3, 4)
    private val lastNight = tonight.minusDays(1)
    private val now: Instant = Instant.parse("2026-03-05T00:30:00Z")

    @Test
    fun `a fresh night starts with the full allowance`() {
        assertEquals(3, policy.usesLeft(EscapeHatchState(), tonight))
        assertIs<EscapeHatchDecision.Allowed>(policy.evaluate(EscapeHatchState(), tonight))
    }

    @Test
    fun `opening the hatch starts a window and spends one use`() {
        val opened = policy.open(EscapeHatchState(), tonight, now)

        assertEquals(tonight, opened.nightId)
        assertEquals(1, opened.usesThisNight)
        assertEquals(now.plus(Duration.ofMinutes(15)), opened.openUntil)
        assertTrue(opened.isOpen(now))
        assertEquals(2, policy.usesLeft(opened, tonight))
    }

    @Test
    fun `the window closes on its own`() {
        val opened = policy.open(EscapeHatchState(), tonight, now)

        assertTrue(opened.isOpen(now.plus(Duration.ofMinutes(14))))
        assertEquals(Duration.ofMinutes(1), opened.timeLeft(now.plus(Duration.ofMinutes(14))))
        assertFalse(opened.isOpen(now.plus(Duration.ofMinutes(15))))
        assertEquals(Duration.ZERO, opened.timeLeft(now.plus(Duration.ofHours(1))))
    }

    @Test
    fun `the allowance runs out after the configured number of opens`() {
        var state = EscapeHatchState()
        repeat(3) { state = policy.open(state, tonight, now) }

        assertEquals(0, policy.usesLeft(state, tonight))
        val decision = policy.evaluate(state, tonight)
        assertIs<EscapeHatchDecision.Exhausted>(decision)
        assertEquals(3, decision.maxPerNight)
    }

    @Test
    fun `a spent allowance cannot be spent again`() {
        var state = EscapeHatchState()
        repeat(3) { state = policy.open(state, tonight, now) }
        val closed = policy.close(state)

        assertEquals(closed, policy.open(closed, tonight, now))
        assertNull(policy.open(closed, tonight, now).openUntil)
    }

    @Test
    fun `the allowance refreshes on the next night`() {
        var state = EscapeHatchState()
        repeat(3) { state = policy.open(state, lastNight, now) }

        assertEquals(0, policy.usesLeft(state, lastNight))
        assertEquals(3, policy.usesLeft(state, tonight))
        assertEquals(1, policy.open(state, tonight, now).usesThisNight)
    }

    @Test
    fun `closing early keeps the use counted`() {
        val opened = policy.open(EscapeHatchState(), tonight, now)

        val closed = policy.close(opened)

        assertFalse(closed.isOpen(now))
        assertEquals(1, closed.usesThisNight)
        assertEquals(2, policy.usesLeft(closed, tonight))
    }

    @Test
    fun `an unlimited allowance never runs out`() {
        val unlimited = EscapeHatchPolicy(maxUsesPerNight = null)
        var state = EscapeHatchState()
        repeat(10) { state = unlimited.open(state, tonight, now) }

        assertNull(unlimited.usesLeft(state, tonight))
        assertIs<EscapeHatchDecision.Allowed>(unlimited.evaluate(state, tonight))
        assertEquals(10, state.usesThisNight)
    }

    @Test
    fun `a zero allowance refuses every open`() {
        val none = EscapeHatchPolicy(maxUsesPerNight = 0)

        assertIs<EscapeHatchDecision.Exhausted>(none.evaluate(EscapeHatchState(), tonight))
        assertNull(none.open(EscapeHatchState(), tonight, now).openUntil)
    }

    @Test
    fun `only the honest answer gets a second beat`() {
        assertTrue(UnlockReason.JUST_CHECKING.deservesSecondThought)
        assertFalse(UnlockReason.WORK.deservesSecondThought)
        assertFalse(UnlockReason.IMPORTANT.deservesSecondThought)
    }

    @Test
    fun `nonsense configuration is rejected at construction`() {
        assertFailsWith<IllegalArgumentException> { EscapeHatchPolicy(maxUsesPerNight = -1) }
        assertFailsWith<IllegalArgumentException> { EscapeHatchPolicy(grantDuration = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> {
            EscapeHatchPolicy(holdToConfirm = Duration.ofSeconds(-1))
        }
    }
}
