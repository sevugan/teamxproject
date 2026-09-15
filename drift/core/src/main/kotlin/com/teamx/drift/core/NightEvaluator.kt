package com.teamx.drift.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime

data class DriftConfig(
    val enabled: Boolean = true,
    val schedule: NightSchedule = NightSchedule.DEFAULT,
    val escapeHatch: EscapeHatchPolicy = EscapeHatchPolicy(),
)

/**
 * Where tonight stands, as one value.
 *
 * [phase] is what the schedule says; [enforced] is what the phone is actually doing,
 * which differs only while an escape hatch is open. Everything that has to make a
 * decision reads [enforced]; everything that has to explain itself to the user reads
 * [phase], so the Tonight screen can say "you are inside quiet hours, with 7 minutes of
 * borrowed time left" rather than pretending the night paused.
 */
data class NightStatus(
    val phase: NightPhase,
    val enforced: NightPhase,
    /** When [phase] next changes; null when the schedule is off. */
    val changesAt: LocalDateTime?,
    /** When borrowed time runs out, if any is running. */
    val bypassUntil: Instant? = null,
    /** True when the schedule is switched off or configured to cover nothing. */
    val scheduleOff: Boolean = false,
) {
    val isBorrowingTime: Boolean get() = bypassUntil != null

    /** Whether anything is being held back right now. */
    val restricts: Boolean get() = enforced.restricts

    fun borrowedTimeLeft(now: Instant): Duration =
        bypassUntil?.takeIf { now < it }?.let { Duration.between(now, it) } ?: Duration.ZERO

    companion object {
        val OFF = NightStatus(
            phase = NightPhase.OPEN,
            enforced = NightPhase.OPEN,
            changesAt = null,
            scheduleOff = true,
        )
    }
}

/**
 * The single place that answers "what is the phone doing right now?".
 *
 * Pure and clock injected, so the alarm, the service, the guard and every screen reach
 * the same verdict, and so each branch is testable without a device.
 */
object NightEvaluator {

    fun evaluate(
        config: DriftConfig,
        state: EscapeHatchState,
        now: LocalDateTime,
        nowInstant: Instant,
    ): NightStatus {
        if (!config.enabled || config.schedule.isEmpty) return NightStatus.OFF

        val phase = config.schedule.phaseAt(now)
        val borrowing = state.isOpen(nowInstant)

        return NightStatus(
            phase = phase,
            // Borrowed time opens the phone fully, and ends on its own.
            enforced = if (borrowing) NightPhase.OPEN else phase,
            changesAt = config.schedule.nextChange(now),
            bypassUntil = state.openUntil.takeIf { borrowing },
        )
    }

    /**
     * When the app should next wake itself: the next phase boundary, or sooner if
     * borrowed time runs out first.
     */
    fun nextWakeUp(
        config: DriftConfig,
        state: EscapeHatchState,
        now: LocalDateTime,
        nowInstant: Instant,
    ): LocalDateTime? {
        if (!config.enabled || config.schedule.isEmpty) return null
        val boundary = config.schedule.nextChange(now)
        val borrowEnds = state.openUntil
            ?.takeIf { it.isAfter(nowInstant) }
            ?.let { now.plus(Duration.between(nowInstant, it)) }
        return when {
            borrowEnds == null -> boundary
            boundary == null -> borrowEnds
            borrowEnds < boundary -> borrowEnds
            else -> boundary
        }
    }
}
