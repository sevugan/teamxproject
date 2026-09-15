package com.teamx.drift.core

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AppLimitsTest {

    private val instagram = "com.instagram.android"
    private val youtube = "com.google.android.youtube"
    private val policy = LimitPolicy()
    private val today = LocalDate.of(2026, 3, 4)
    private val limits = AppLimits().with(instagram, Duration.ofMinutes(15))

    private fun verdict(
        used: Duration,
        packageName: String = instagram,
        extensions: LimitExtensions = LimitExtensions(),
    ) = AppLimitEvaluator.verdict(packageName, used, limits, extensions, policy, today)

    @Test
    fun `an app with no limit is never timed`() {
        assertIs<LimitVerdict.Untimed>(verdict(Duration.ofHours(3), packageName = youtube))
    }

    @Test
    fun `inside the limit reports what is left`() {
        val v = verdict(Duration.ofMinutes(9))

        assertIs<LimitVerdict.Within>(v)
        assertEquals(Duration.ofMinutes(6), v.remaining)
        assertFalse(v.isNearlyUp)
        assertFalse(v.closesTheApp)
    }

    @Test
    fun `the last couple of minutes are worth a warning`() {
        val v = verdict(Duration.ofMinutes(13))

        assertIs<LimitVerdict.Within>(v)
        assertEquals(Duration.ofMinutes(2), v.remaining)
        assertTrue(v.isNearlyUp)
    }

    @Test
    fun `hitting the limit exactly closes the app`() {
        val v = verdict(Duration.ofMinutes(15))

        assertIs<LimitVerdict.Spent>(v)
        assertTrue(v.closesTheApp)
        assertEquals(Duration.ofMinutes(15), v.allowed)
        assertEquals(2, v.extensionsLeft)
        assertTrue(v.canExtend)
    }

    @Test
    fun `going over the limit closes the app`() {
        assertIs<LimitVerdict.Spent>(verdict(Duration.ofMinutes(40)))
    }

    @Test
    fun `an extension buys five more minutes`() {
        val extended = policy.extend(LimitExtensions(), instagram, today)

        assertEquals(Duration.ofMinutes(5), extended.extraFor(instagram, today))
        val v = verdict(Duration.ofMinutes(16), extensions = extended)
        assertIs<LimitVerdict.Within>(v)
        assertEquals(Duration.ofMinutes(20), v.allowed)
        assertEquals(Duration.ofMinutes(4), v.remaining)
    }

    @Test
    fun `extensions run out, and then the limit is final`() {
        var extensions = LimitExtensions()
        repeat(2) { extensions = policy.extend(extensions, instagram, today) }

        assertFalse(policy.canExtend(extensions, instagram, today))
        assertEquals(0, policy.extensionsLeft(extensions, instagram, today))

        // A third attempt changes nothing at all.
        assertEquals(extensions, policy.extend(extensions, instagram, today))

        val v = verdict(Duration.ofMinutes(26), extensions = extensions)
        assertIs<LimitVerdict.Spent>(v)
        assertEquals(Duration.ofMinutes(25), v.allowed)
        assertFalse(v.canExtend)
    }

    @Test
    fun `extending one app does not extend another`() {
        val extended = policy.extend(LimitExtensions(), instagram, today)

        assertEquals(Duration.ZERO, extended.extraFor(youtube, today))
        assertEquals(2, policy.extensionsLeft(extended, youtube, today))
    }

    @Test
    fun `yesterday's extensions do not carry over`() {
        var extensions = LimitExtensions()
        repeat(2) { extensions = policy.extend(extensions, instagram, today.minusDays(1)) }

        assertEquals(Duration.ZERO, extensions.extraFor(instagram, today))
        assertTrue(policy.canExtend(extensions, instagram, today))
        assertEquals(2, policy.extensionsLeft(extensions, instagram, today))
    }

    @Test
    fun `a policy with no extensions makes a limit final`() {
        val strict = LimitPolicy(maxExtensionsPerDay = 0)

        assertFalse(strict.canExtend(LimitExtensions(), instagram, today))
        assertEquals(LimitExtensions(), strict.extend(LimitExtensions(), instagram, today))
    }

    @Test
    fun `limits can be added and removed`() {
        assertTrue(limits.isLimited(instagram))
        assertFalse(limits.isLimited(youtube))
        assertEquals(setOf(instagram), limits.limitedPackages)
        assertFalse(limits.without(instagram).isLimited(instagram))
        assertEquals(Duration.ofMinutes(30), limits.with(youtube, Duration.ofMinutes(30)).limitFor(youtube))
    }

    @Test
    fun `a negative limit is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            AppLimits().with(instagram, Duration.ofMinutes(-1))
        }
        assertFailsWith<IllegalArgumentException> { LimitPolicy(maxExtensionsPerDay = -1) }
    }

    @Test
    fun `a zero limit closes the app immediately`() {
        val blocked = AppLimits().with(instagram, Duration.ZERO)

        val v = AppLimitEvaluator.verdict(
            instagram, Duration.ZERO, blocked, LimitExtensions(), policy, today,
        )
        assertIs<LimitVerdict.Spent>(v)
    }
}

class UsageDayTest {

    private val wake: LocalTime = LocalTime.of(6, 0)

    private fun at(day: Int, hour: Int, minute: Int = 0) =
        LocalDateTime.of(2026, 3, day, hour, minute)

    @Test
    fun `the day starts when you get up`() {
        assertEquals(at(4, 6), UsageDay.startOf(at(4, 9), wake))
        assertEquals(LocalDate.of(2026, 3, 4), UsageDay.idOf(at(4, 9), wake))
    }

    @Test
    fun `late evening still belongs to the day that started that morning`() {
        assertEquals(at(4, 6), UsageDay.startOf(at(4, 23, 30), wake))
        assertEquals(LocalDate.of(2026, 3, 4), UsageDay.idOf(at(4, 23, 30), wake))
    }

    @Test
    fun `the small hours belong to the day before, not a fresh one`() {
        // Otherwise a limit spent at 23:50 would quietly refill ten minutes later.
        assertEquals(at(4, 6), UsageDay.startOf(at(5, 1, 0), wake))
        assertEquals(LocalDate.of(2026, 3, 4), UsageDay.idOf(at(5, 1, 0), wake))
    }

    @Test
    fun `the limit refills when you wake up`() {
        assertEquals(LocalDate.of(2026, 3, 4), UsageDay.idOf(at(5, 5, 59), wake))
        assertEquals(LocalDate.of(2026, 3, 5), UsageDay.idOf(at(5, 6, 0), wake))
    }

    @Test
    fun `the day ends a day after it starts`() {
        assertEquals(at(5, 6), UsageDay.endOf(at(4, 23), wake))
        assertEquals(at(5, 6), UsageDay.endOf(at(5, 2), wake))
    }
}
