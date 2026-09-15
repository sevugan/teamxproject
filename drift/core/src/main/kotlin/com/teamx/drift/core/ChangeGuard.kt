package com.teamx.drift.core

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * When the rules may be relaxed.
 *
 * The point of a limit is that the person setting it and the person hitting it are in
 * different states of mind. This window belongs to the first one.
 *
 * Fails open by design: a window that makes no sense (an end at or before its start, no
 * days selected) is treated as always open rather than locking someone out of their own
 * settings forever.
 */
data class EditWindow(
    val from: LocalTime = LocalTime.of(9, 0),
    val to: LocalTime = LocalTime.of(18, 0),
    val days: Set<DayOfWeek> = WEEKDAYS,
    /** Off by default: limits are a promise only once the user opts into keeping them. */
    val enabled: Boolean = false,
) {
    /** True when a misconfigured window should simply not lock anything. */
    val isUnusable: Boolean get() = to <= from || days.isEmpty()

    fun isOpen(at: LocalDateTime): Boolean {
        if (!enabled || isUnusable) return true
        if (at.dayOfWeek !in days) return false
        val time = at.toLocalTime()
        return time >= from && time < to
    }

    /** The next moment the window opens, or null when it is open now or never closes. */
    fun opensAfter(now: LocalDateTime): LocalDateTime? {
        if (isOpen(now)) return null
        // At most a week away, so walking forward a day at a time is plenty.
        for (dayOffset in 0..7) {
            val candidate = now.toLocalDate().plusDays(dayOffset.toLong()).atTime(from)
            if (candidate > now && candidate.dayOfWeek in days) return candidate
        }
        return null
    }

    companion object {
        val WEEKDAYS: Set<DayOfWeek> = setOf(
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY,
        )
    }
}

/**
 * Everything the user has promised themselves, in one comparable value.
 *
 * Kept as a snapshot so a proposed change can be judged as a whole: what matters is not
 * which screen was touched but whether the phone came out easier to use.
 */
data class Commitments(
    val enabled: Boolean = true,
    val schedule: NightSchedule = NightSchedule.DEFAULT,
    val limits: AppLimits = AppLimits(),
    val limitPolicy: LimitPolicy = LimitPolicy(),
    val distracting: Set<String> = emptySet(),
    val essential: Set<String> = emptySet(),
    val essentialRoles: Set<EssentialRole> = EssentialRole.SUPER_SAVER,
    val escapeHatch: EscapeHatchPolicy = EscapeHatchPolicy(),
    val blockSettingsFrom: NightPhase? = null,
) {
    /**
     * Whether moving to [other] would make the phone easier to use than it is now.
     *
     * Every dimension is compared, and any single relaxation makes the whole change a
     * loosening — otherwise a change could tighten one thing as cover for loosening
     * another.
     */
    fun isLoosenedBy(other: Commitments): Boolean = loosenings(other).isNotEmpty()

    /** The specific relaxations, so the UI can say exactly what is being asked for. */
    fun loosenings(other: Commitments): List<String> {
        val reasons = mutableListOf<String>()

        if (enabled && !other.enabled) reasons += "turning Drift off"

        // A later bedtime, an earlier morning, or a shorter ramp all buy phone time.
        if (schedule.sleepAt != other.schedule.sleepAt &&
            minutesLater(schedule.sleepAt, other.schedule.sleepAt) > 0
        ) {
            reasons += "a later bedtime"
        }
        if (schedule.wakeAt != other.schedule.wakeAt &&
            minutesLater(other.schedule.wakeAt, schedule.wakeAt) > 0
        ) {
            reasons += "an earlier morning"
        }
        if (other.schedule.windDownLead < schedule.windDownLead) reasons += "a shorter wind-down"
        if (other.schedule.quietLead < schedule.quietLead) reasons += "shorter quiet hours"

        // A limit raised, or dropped entirely.
        limits.perApp.forEach { (packageName, limit) ->
            val after = other.limits.limitFor(packageName)
            when {
                after == null -> reasons += "removing the limit on $packageName"
                after > limit -> reasons += "more time for $packageName"
            }
        }
        if (other.limitPolicy.maxExtensionsPerDay > limitPolicy.maxExtensionsPerDay ||
            other.limitPolicy.extensionLength > limitPolicy.extensionLength
        ) {
            reasons += "longer or more extensions"
        }

        // Apps taken off the put-away list, or added to what survives quiet hours.
        (distracting - other.distracting).forEach { reasons += "keeping $it at night" }
        (other.essential - essential).forEach { reasons += "keeping $it in quiet hours" }
        (other.essentialRoles - essentialRoles).forEach {
            reasons += "keeping ${it.name.lowercase()} all night"
        }

        // A more forgiving escape hatch.
        if (isMoreGenerous(escapeHatch.maxUsesPerNight, other.escapeHatch.maxUsesPerNight)) {
            reasons += "more openings a night"
        }
        if (other.escapeHatch.grantDuration > escapeHatch.grantDuration) {
            reasons += "longer openings"
        }
        if (other.escapeHatch.holdToConfirm < escapeHatch.holdToConfirm) {
            reasons += "a shorter hold"
        }

        // Settings reachable for longer, or always.
        val before = blockSettingsFrom
        val after = other.blockSettingsFrom
        if (before != null && (after == null || after.ordinal > before.ordinal)) {
            reasons += "reaching Settings for longer"
        }

        return reasons
    }

    /** null means unlimited, which is looser than any number. */
    private fun isMoreGenerous(before: Int?, after: Int?): Boolean = when {
        before == null -> false
        after == null -> true
        else -> after > before
    }

    /** Minutes [later] falls after [earlier] on the clock, wrapping at midnight. */
    private fun minutesLater(earlier: LocalTime, later: LocalTime): Long {
        val diff = later.toSecondOfDay().toLong() - earlier.toSecondOfDay().toLong()
        // Treat more than half a day apart as "earlier", so 23:00 -> 22:00 is not read
        // as a 23 hour delay.
        return when {
            diff > HALF_DAY_SECONDS -> (diff - DAY_SECONDS) / 60
            diff < -HALF_DAY_SECONDS -> (diff + DAY_SECONDS) / 60
            else -> diff / 60
        }
    }

    private companion object {
        const val DAY_SECONDS = 24L * 60 * 60
        const val HALF_DAY_SECONDS = DAY_SECONDS / 2
    }
}

sealed interface ChangeDecision {
    /** Go ahead: tightening, inside the window, or an opening is running. */
    data class Allowed(val reason: Why) : ChangeDecision {
        enum class Why { TIGHTENING, WINDOW_OPEN, ESCAPE_HATCH_OPEN, NO_WINDOW_SET }
    }

    /** Not now. [opensAt] is when it could be done without spending an opening. */
    data class Blocked(
        val loosenings: List<String>,
        val opensAt: LocalDateTime?,
        val canUseEscapeHatch: Boolean,
    ) : ChangeDecision

    val isAllowed: Boolean get() = this is Allowed
}

/**
 * Decides whether a proposed change is allowed to happen right now.
 *
 * The rule, in one line: **tightening is always allowed, loosening needs working hours or
 * an opening.** Nothing here can stop someone uninstalling the app, and nothing here ever
 * touches calling.
 */
object ChangeGuard {

    fun evaluate(
        from: Commitments,
        to: Commitments,
        window: EditWindow,
        now: LocalDateTime,
        escapeHatchOpen: Boolean,
        escapeHatchAvailable: Boolean = true,
    ): ChangeDecision {
        val loosenings = from.loosenings(to)

        // You may always make a promise harder to break.
        if (loosenings.isEmpty()) return ChangeDecision.Allowed(ChangeDecision.Allowed.Why.TIGHTENING)

        if (!window.enabled || window.isUnusable) {
            return ChangeDecision.Allowed(ChangeDecision.Allowed.Why.NO_WINDOW_SET)
        }
        if (window.isOpen(now)) {
            return ChangeDecision.Allowed(ChangeDecision.Allowed.Why.WINDOW_OPEN)
        }
        // An opening already bought with a reason and a five second hold counts.
        if (escapeHatchOpen) {
            return ChangeDecision.Allowed(ChangeDecision.Allowed.Why.ESCAPE_HATCH_OPEN)
        }

        return ChangeDecision.Blocked(
            loosenings = loosenings,
            opensAt = window.opensAfter(now),
            canUseEscapeHatch = escapeHatchAvailable,
        )
    }
}
