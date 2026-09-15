package com.teamx.drift.core

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditWindowTest {

    private val window = EditWindow(enabled = true) // 09:00-18:00, Mon-Fri

    // 2026-03-04 is a Wednesday, 2026-03-07 a Saturday.
    private fun at(day: Int, hour: Int, minute: Int = 0) =
        LocalDateTime.of(2026, 3, day, hour, minute)

    @Test
    fun `open during working hours on a working day`() {
        assertEquals(DayOfWeek.WEDNESDAY, at(4, 12).dayOfWeek)
        assertTrue(window.isOpen(at(4, 9, 0)))
        assertTrue(window.isOpen(at(4, 12, 0)))
        assertTrue(window.isOpen(at(4, 17, 59)))
    }

    @Test
    fun `closed before, after, and at the end of the working day`() {
        assertFalse(window.isOpen(at(4, 8, 59)))
        assertFalse(window.isOpen(at(4, 18, 0)))
        assertFalse(window.isOpen(at(4, 23, 30)))
        assertFalse(window.isOpen(at(5, 2, 0)))
    }

    @Test
    fun `closed at the weekend`() {
        assertEquals(DayOfWeek.SATURDAY, at(7, 12).dayOfWeek)
        assertFalse(window.isOpen(at(7, 12, 0)))
        assertFalse(window.isOpen(at(8, 12, 0)))
    }

    @Test
    fun `a disabled window never locks anything`() {
        val off = window.copy(enabled = false)

        assertTrue(off.isOpen(at(4, 23, 30)))
        assertTrue(off.isOpen(at(7, 3, 0)))
    }

    @Test
    fun `a nonsense window fails open rather than locking someone out`() {
        assertTrue(EditWindow(from = LocalTime.of(18, 0), to = LocalTime.of(9, 0), enabled = true).isUnusable)
        assertTrue(EditWindow(from = LocalTime.of(18, 0), to = LocalTime.of(9, 0), enabled = true).isOpen(at(4, 23)))
        assertTrue(EditWindow(days = emptySet(), enabled = true).isOpen(at(4, 23)))
    }

    @Test
    fun `it says when it next opens`() {
        // Wednesday 23:30 -> Thursday 09:00.
        assertEquals(at(5, 9, 0), window.opensAfter(at(4, 23, 30)))
        // Wednesday 07:00 -> later the same morning.
        assertEquals(at(4, 9, 0), window.opensAfter(at(4, 7, 0)))
        // Saturday -> Monday.
        assertEquals(at(9, 9, 0), window.opensAfter(at(7, 12, 0)))
    }

    @Test
    fun `when it is already open there is nothing to wait for`() {
        assertNull(window.opensAfter(at(4, 12, 0)))
    }
}

class ChangeGuardTest {

    private val instagram = "com.instagram.android"
    private val window = EditWindow(enabled = true)
    private val before = Commitments(
        limits = AppLimits().with(instagram, Duration.ofMinutes(15)),
        distracting = setOf(instagram),
    )

    private fun at(day: Int, hour: Int, minute: Int = 0) =
        LocalDateTime.of(2026, 3, day, hour, minute)

    private val workingHours = at(4, 12, 0)
    private val lateEvening = at(4, 23, 30)

    private fun decide(
        to: Commitments,
        now: LocalDateTime = lateEvening,
        escapeHatchOpen: Boolean = false,
    ) = ChangeGuard.evaluate(before, to, window, now, escapeHatchOpen)

    @Test
    fun `tightening is always allowed, at any hour`() {
        val tighter = before.copy(
            limits = before.limits.with(instagram, Duration.ofMinutes(5)),
        )

        val decision = decide(tighter)

        assertIs<ChangeDecision.Allowed>(decision)
        assertEquals(ChangeDecision.Allowed.Why.TIGHTENING, decision.reason)
    }

    @Test
    fun `raising a limit at 2330 is refused`() {
        val looser = before.copy(
            limits = before.limits.with(instagram, Duration.ofMinutes(60)),
        )

        val decision = decide(looser)

        assertIs<ChangeDecision.Blocked>(decision)
        assertEquals(listOf("more time for $instagram"), decision.loosenings)
        assertEquals(at(5, 9, 0), decision.opensAt)
        assertTrue(decision.canUseEscapeHatch)
    }

    @Test
    fun `removing a limit at 2330 is refused`() {
        val decision = decide(before.copy(limits = before.limits.without(instagram)))

        assertIs<ChangeDecision.Blocked>(decision)
        assertEquals(listOf("removing the limit on $instagram"), decision.loosenings)
    }

    @Test
    fun `the same change during working hours is allowed`() {
        val looser = before.copy(limits = before.limits.with(instagram, Duration.ofMinutes(60)))

        val decision = decide(looser, now = workingHours)

        assertIs<ChangeDecision.Allowed>(decision)
        assertEquals(ChangeDecision.Allowed.Why.WINDOW_OPEN, decision.reason)
    }

    @Test
    fun `an opening already bought lets the change through`() {
        val looser = before.copy(limits = before.limits.with(instagram, Duration.ofMinutes(60)))

        val decision = decide(looser, escapeHatchOpen = true)

        assertIs<ChangeDecision.Allowed>(decision)
        assertEquals(ChangeDecision.Allowed.Why.ESCAPE_HATCH_OPEN, decision.reason)
    }

    @Test
    fun `with no window set nothing is ever locked`() {
        val looser = before.copy(limits = before.limits.with(instagram, Duration.ofMinutes(60)))

        val decision = ChangeGuard.evaluate(
            before, looser, EditWindow(enabled = false), lateEvening, escapeHatchOpen = false,
        )

        assertIs<ChangeDecision.Allowed>(decision)
        assertEquals(ChangeDecision.Allowed.Why.NO_WINDOW_SET, decision.reason)
    }

    @Test
    fun `turning Drift off is a loosening`() {
        assertIs<ChangeDecision.Blocked>(decide(before.copy(enabled = false)))
    }

    @Test
    fun `a later bedtime is a loosening, an earlier one is not`() {
        val later = before.copy(
            schedule = before.schedule.copy(sleepAt = LocalTime.of(23, 30)),
        )
        val earlier = before.copy(
            schedule = before.schedule.copy(sleepAt = LocalTime.of(22, 30)),
        )

        assertEquals(listOf("a later bedtime"), before.loosenings(later))
        assertTrue(before.loosenings(earlier).isEmpty())
    }

    @Test
    fun `bedtime either side of midnight is compared the short way round`() {
        val past = before.copy(schedule = before.schedule.copy(sleepAt = LocalTime.of(0, 30)))
        val brought = Commitments(schedule = NightSchedule(LocalTime.of(0, 30), LocalTime.of(6, 0)))
        val backTo23 = brought.copy(schedule = brought.schedule.copy(sleepAt = LocalTime.of(23, 0)))

        // 23:00 -> 00:30 is 90 minutes later, not 22 and a half hours earlier.
        assertEquals(listOf("a later bedtime"), before.loosenings(past))
        // 00:30 -> 23:00 is earlier, so it tightens.
        assertTrue(brought.loosenings(backTo23).isEmpty())
    }

    @Test
    fun `an earlier morning is a loosening`() {
        val earlier = before.copy(schedule = before.schedule.copy(wakeAt = LocalTime.of(5, 0)))
        val later = before.copy(schedule = before.schedule.copy(wakeAt = LocalTime.of(7, 0)))

        assertEquals(listOf("an earlier morning"), before.loosenings(earlier))
        assertTrue(before.loosenings(later).isEmpty())
    }

    @Test
    fun `a shorter ramp is a loosening`() {
        val shorter = before.copy(
            schedule = before.schedule.copy(
                windDownLead = Duration.ofMinutes(30),
                quietLead = Duration.ofMinutes(10),
            ),
        )

        assertEquals(
            listOf("a shorter wind-down", "shorter quiet hours"),
            before.loosenings(shorter),
        )
    }

    @Test
    fun `taking an app off the put-away list is a loosening`() {
        assertEquals(
            listOf("keeping $instagram at night"),
            before.loosenings(before.copy(distracting = emptySet())),
        )
    }

    @Test
    fun `adding an app to the essentials is a loosening`() {
        assertEquals(
            listOf("keeping com.whatsapp in quiet hours"),
            before.loosenings(before.copy(essential = setOf("com.whatsapp"))),
        )
    }

    @Test
    fun `a more forgiving escape hatch is a loosening`() {
        assertEquals(
            listOf("more openings a night"),
            before.loosenings(before.copy(escapeHatch = EscapeHatchPolicy(maxUsesPerNight = 5))),
        )
        assertEquals(
            listOf("more openings a night"),
            before.loosenings(before.copy(escapeHatch = EscapeHatchPolicy(maxUsesPerNight = null))),
        )
        assertEquals(
            listOf("longer openings"),
            before.loosenings(
                before.copy(escapeHatch = EscapeHatchPolicy(grantDuration = Duration.ofMinutes(30))),
            ),
        )
        assertEquals(
            listOf("a shorter hold"),
            before.loosenings(
                before.copy(escapeHatch = EscapeHatchPolicy(holdToConfirm = Duration.ofSeconds(1))),
            ),
        )
    }

    @Test
    fun `fewer openings tightens, and unlimited cannot be loosened further`() {
        assertTrue(before.loosenings(before.copy(escapeHatch = EscapeHatchPolicy(maxUsesPerNight = 1))).isEmpty())

        val unlimited = before.copy(escapeHatch = EscapeHatchPolicy(maxUsesPerNight = null))
        assertTrue(unlimited.loosenings(unlimited.copy(escapeHatch = EscapeHatchPolicy(maxUsesPerNight = 3))).isEmpty())
    }

    @Test
    fun `reaching Settings for longer is a loosening`() {
        val locked = before.copy(blockSettingsFrom = NightPhase.WIND_DOWN)

        assertEquals(
            listOf("reaching Settings for longer"),
            locked.loosenings(locked.copy(blockSettingsFrom = NightPhase.SLEEP)),
        )
        assertEquals(
            listOf("reaching Settings for longer"),
            locked.loosenings(locked.copy(blockSettingsFrom = null)),
        )
        assertTrue(before.loosenings(before.copy(blockSettingsFrom = NightPhase.QUIET)).isEmpty())
    }

    @Test
    fun `tightening one thing does not smuggle a loosening past the guard`() {
        val mixed = before.copy(
            // Looks stricter...
            schedule = before.schedule.copy(sleepAt = LocalTime.of(22, 0)),
            // ...but doubles the app limit in the same edit.
            limits = before.limits.with(instagram, Duration.ofMinutes(30)),
        )

        val decision = decide(mixed)

        assertIs<ChangeDecision.Blocked>(decision)
        assertEquals(listOf("more time for $instagram"), decision.loosenings)
    }

    @Test
    fun `a change to nothing at all is allowed`() {
        assertIs<ChangeDecision.Allowed>(decide(before))
        assertTrue(before.loosenings(before).isEmpty())
    }

    @Test
    fun `adding a new limit for an app that had none is tightening`() {
        val withNewLimit = before.copy(
            limits = before.limits.with("com.google.android.youtube", Duration.ofMinutes(20)),
        )

        assertIs<ChangeDecision.Allowed>(decide(withNewLimit))
    }

    @Test
    fun `a blocked decision says both ways out`() {
        val decision = decide(before.copy(enabled = false))

        assertIs<ChangeDecision.Blocked>(decision)
        assertEquals(at(5, 9, 0), decision.opensAt)
        assertTrue(decision.canUseEscapeHatch)

        val spent = ChangeGuard.evaluate(
            before, before.copy(enabled = false), window, lateEvening,
            escapeHatchOpen = false, escapeHatchAvailable = false,
        )
        assertIs<ChangeDecision.Blocked>(spent)
        assertFalse(spent.canUseEscapeHatch)
    }
}
