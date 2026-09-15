package com.teamx.drift.core

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * How long each app gets per day.
 *
 * A limit is a promise made in advance. Nothing here decides whether it can be changed —
 * that is [ChangeGuard]'s job — this only says how much is left.
 */
data class AppLimits(val perApp: Map<String, Duration> = emptyMap()) {

    fun limitFor(packageName: String): Duration? = perApp[packageName]

    fun isLimited(packageName: String): Boolean = perApp.containsKey(packageName)

    val limitedPackages: Set<String> get() = perApp.keys

    fun with(packageName: String, limit: Duration): AppLimits {
        require(!limit.isNegative) { "a limit cannot be negative" }
        return AppLimits(perApp + (packageName to limit))
    }

    fun without(packageName: String): AppLimits = AppLimits(perApp - packageName)
}

/**
 * Minutes borrowed against today's limits, per app.
 *
 * The cap is what makes a limit mean anything: without one, "add five minutes" is just a
 * slower way of having no limit at all.
 */
data class LimitExtensions(
    val dayId: LocalDate? = null,
    val timesUsed: Map<String, Int> = emptyMap(),
    val extraTime: Map<String, Duration> = emptyMap(),
) {
    fun forDay(dayId: LocalDate): LimitExtensions =
        if (this.dayId == dayId) this else LimitExtensions(dayId)

    fun timesUsedBy(packageName: String, dayId: LocalDate): Int =
        forDay(dayId).timesUsed[packageName] ?: 0

    fun extraFor(packageName: String, dayId: LocalDate): Duration =
        forDay(dayId).extraTime[packageName] ?: Duration.ZERO
}

data class LimitPolicy(
    /** Extensions allowed per app per day. Zero means a limit is final once spent. */
    val maxExtensionsPerDay: Int = 2,
    /** How much each extension adds. */
    val extensionLength: Duration = Duration.ofMinutes(5),
    /** How long before a limit runs out to start warning. */
    val warnAt: Duration = Duration.ofMinutes(2),
) {
    init {
        require(maxExtensionsPerDay >= 0) { "maxExtensionsPerDay must not be negative" }
        require(!extensionLength.isNegative) { "extensionLength must not be negative" }
        require(!warnAt.isNegative) { "warnAt must not be negative" }
    }

    fun extensionsLeft(
        extensions: LimitExtensions,
        packageName: String,
        dayId: LocalDate,
    ): Int = (maxExtensionsPerDay - extensions.timesUsedBy(packageName, dayId)).coerceAtLeast(0)

    fun canExtend(
        extensions: LimitExtensions,
        packageName: String,
        dayId: LocalDate,
    ): Boolean = extensionsLeft(extensions, packageName, dayId) > 0

    /** Borrows one extension. Returns the state unchanged when the cap is reached. */
    fun extend(
        extensions: LimitExtensions,
        packageName: String,
        dayId: LocalDate,
    ): LimitExtensions {
        if (!canExtend(extensions, packageName, dayId)) return extensions
        val today = extensions.forDay(dayId)
        return today.copy(
            dayId = dayId,
            timesUsed = today.timesUsed + (packageName to today.timesUsedBy(packageName, dayId) + 1),
            extraTime = today.extraTime +
                (packageName to today.extraFor(packageName, dayId).plus(extensionLength)),
        )
    }
}

sealed interface LimitVerdict {
    /** No limit was ever set for this app. */
    data object Untimed : LimitVerdict

    /** Still inside the limit. */
    data class Within(
        val used: Duration,
        val allowed: Duration,
        val warnAt: Duration,
    ) : LimitVerdict {
        val remaining: Duration get() = allowed.minus(used).coerceAtLeast(Duration.ZERO)

        /** Worth a heads-up rather than a closed app out of nowhere. */
        val isNearlyUp: Boolean get() = remaining <= warnAt
    }

    /** The day's time is gone. The app gets closed. */
    data class Spent(
        val used: Duration,
        val allowed: Duration,
        val extensionsLeft: Int,
    ) : LimitVerdict {
        val canExtend: Boolean get() = extensionsLeft > 0
    }

    val closesTheApp: Boolean get() = this is Spent
}

/**
 * Decides whether an app has run out of time today.
 *
 * Pure and fed with a measured usage total, so the same arithmetic runs in tests and on
 * the phone with no clock or system service involved.
 */
object AppLimitEvaluator {

    fun verdict(
        packageName: String,
        usedToday: Duration,
        limits: AppLimits,
        extensions: LimitExtensions,
        policy: LimitPolicy,
        dayId: LocalDate,
    ): LimitVerdict {
        val base = limits.limitFor(packageName) ?: return LimitVerdict.Untimed
        val allowed = base.plus(extensions.extraFor(packageName, dayId))
        return if (usedToday < allowed) {
            LimitVerdict.Within(used = usedToday, allowed = allowed, warnAt = policy.warnAt)
        } else {
            LimitVerdict.Spent(
                used = usedToday,
                allowed = allowed,
                extensionsLeft = policy.extensionsLeft(extensions, packageName, dayId),
            )
        }
    }
}

/**
 * When a day starts, for the purpose of resetting limits.
 *
 * Not midnight: a day runs from when you get up, so a limit spent at 01:00 belongs to the
 * evening it was spent in rather than resetting underneath you halfway through the night.
 */
object UsageDay {

    fun startOf(now: LocalDateTime, dayStartsAt: LocalTime): LocalDateTime {
        val todaysStart = now.toLocalDate().atTime(dayStartsAt)
        return if (now < todaysStart) todaysStart.minusDays(1) else todaysStart
    }

    fun idOf(now: LocalDateTime, dayStartsAt: LocalTime): LocalDate =
        startOf(now, dayStartsAt).toLocalDate()

    fun endOf(now: LocalDateTime, dayStartsAt: LocalTime): LocalDateTime =
        startOf(now, dayStartsAt).plusDays(1)
}
