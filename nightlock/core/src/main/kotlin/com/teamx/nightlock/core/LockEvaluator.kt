package com.teamx.nightlock.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime

data class LockConfig(
    val enabled: Boolean = true,
    val window: CurfewWindow = CurfewWindow.DEFAULT,
    val emergency: EmergencyPolicy = EmergencyPolicy(),
)

sealed interface LockStatus {
    /** Enforce the lock screen now. [lockedUntil] is when the curfew ends on its own. */
    data class Locked(val lockedUntil: LocalDateTime) : LockStatus

    /** Inside the curfew, but an emergency unlock is running. */
    data class Bypassed(val bypassUntil: Instant, val curfewEndsAt: LocalDateTime) : LockStatus

    /** Outside the curfew. [nextLockAt] is when the next one begins. */
    data class Unlocked(val nextLockAt: LocalDateTime) : LockStatus

    /** The curfew is switched off, or configured as a zero length window. */
    data object Off : LockStatus

    val shouldEnforceLockScreen: Boolean get() = this is Locked
}

/**
 * The single place that answers "is the phone locked right now?".
 *
 * Pure and clock injected, so the service, the alarm receiver, the accessibility guard
 * and the UI all reach the same verdict, and so every branch is testable.
 */
object LockEvaluator {

    fun evaluate(
        config: LockConfig,
        state: EmergencyState,
        now: LocalDateTime,
        nowInstant: Instant,
    ): LockStatus {
        if (!config.enabled || config.window.isEmpty) return LockStatus.Off

        if (!config.window.contains(now)) {
            return LockStatus.Unlocked(nextLockAt = config.window.nextStart(now))
        }

        val curfewEndsAt = config.window.nextEnd(now)
        return if (state.isBypassActive(nowInstant)) {
            LockStatus.Bypassed(bypassUntil = state.bypassUntil!!, curfewEndsAt = curfewEndsAt)
        } else {
            LockStatus.Locked(lockedUntil = curfewEndsAt)
        }
    }

    /**
     * When the app should next wake itself up: the curfew boundary, or sooner if an
     * emergency grant expires first.
     */
    fun nextWakeUp(
        config: LockConfig,
        state: EmergencyState,
        now: LocalDateTime,
        nowInstant: Instant,
    ): LocalDateTime? {
        if (!config.enabled || config.window.isEmpty) return null
        val boundary = config.window.nextTransition(now)
        val bypassEnd = state.bypassUntil
            ?.takeIf { it.isAfter(nowInstant) }
            ?.let { now.plus(Duration.between(nowInstant, it)) }
        return when {
            bypassEnd == null -> boundary
            bypassEnd < boundary -> bypassEnd
            else -> boundary
        }
    }
}
