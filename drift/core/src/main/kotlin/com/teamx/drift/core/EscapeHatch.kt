package com.teamx.drift.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Why someone is reaching past the night.
 *
 * The point of asking is not to judge the answer, it is that answering at all turns a
 * reflex into a decision. Most of the time the honest answer is the last one.
 */
enum class UnlockReason {
    /** Something that actually has to happen tonight. */
    WORK,

    /** Something important, not work. */
    IMPORTANT,

    /** The honest one. */
    JUST_CHECKING,
    ;

    /**
     * Whether to offer a way back before opening the phone. Only the honest answer gets
     * a second beat, and even then "continue" is right there: the app asks, it does not
     * refuse.
     */
    val deservesSecondThought: Boolean get() = this == JUST_CHECKING
}

/**
 * Persisted state of the escape hatch.
 *
 * [nightId] scopes [usesThisNight]: on a different night the allowance has refreshed and
 * the stored count no longer applies.
 */
data class EscapeHatchState(
    val nightId: LocalDate? = null,
    val usesThisNight: Int = 0,
    val openUntil: Instant? = null,
) {
    fun isOpen(now: Instant): Boolean = openUntil != null && now < openUntil

    fun timeLeft(now: Instant): Duration =
        if (isOpen(now)) Duration.between(now, openUntil) else Duration.ZERO
}

sealed interface EscapeHatchDecision {
    /** May be opened. [usesLeftAfter] is what remains once this one is spent. */
    data class Allowed(val usesLeftAfter: Int) : EscapeHatchDecision

    /** Tonight's allowance is spent. Calling still works; the rest of the phone does not. */
    data class Exhausted(val maxPerNight: Int) : EscapeHatchDecision
}

/**
 * The rules for reaching past the night.
 *
 * Deliberately easy in a real emergency (no password, no waiting for 06:00) and
 * deliberately inconvenient as a habit: name a reason, hold a button, spend one of a
 * small nightly allowance, get a window that closes by itself.
 *
 * An app that cannot be bypassed gets uninstalled, which protects nobody.
 */
data class EscapeHatchPolicy(
    /** Opens allowed per night, or null for unlimited. */
    val maxUsesPerNight: Int? = 3,
    /** How long the phone stays open once the hatch is used. */
    val grantDuration: Duration = Duration.ofMinutes(15),
    /** How long the confirm button must be held. */
    val holdToConfirm: Duration = Duration.ofSeconds(5),
) {
    init {
        require(maxUsesPerNight == null || maxUsesPerNight >= 0) {
            "maxUsesPerNight must be null (unlimited) or >= 0"
        }
        require(!grantDuration.isNegative && !grantDuration.isZero) {
            "grantDuration must be positive"
        }
        require(!holdToConfirm.isNegative) { "holdToConfirm must not be negative" }
    }

    /** Uses already spent tonight, ignoring counts left over from other nights. */
    fun usesSpent(state: EscapeHatchState, nightId: LocalDate): Int =
        if (state.nightId == nightId) state.usesThisNight else 0

    /** Uses still available tonight; null means unlimited. */
    fun usesLeft(state: EscapeHatchState, nightId: LocalDate): Int? =
        maxUsesPerNight?.let { (it - usesSpent(state, nightId)).coerceAtLeast(0) }

    fun evaluate(state: EscapeHatchState, nightId: LocalDate): EscapeHatchDecision {
        val left = usesLeft(state, nightId) ?: return EscapeHatchDecision.Allowed(Int.MAX_VALUE)
        return if (left > 0) {
            EscapeHatchDecision.Allowed(left - 1)
        } else {
            EscapeHatchDecision.Exhausted(maxUsesPerNight ?: 0)
        }
    }

    /**
     * Records an opened hatch. Returns the state unchanged when the allowance is spent,
     * so a caller cannot hand out time it just refused.
     */
    fun open(state: EscapeHatchState, nightId: LocalDate, now: Instant): EscapeHatchState =
        when (evaluate(state, nightId)) {
            is EscapeHatchDecision.Exhausted -> state
            is EscapeHatchDecision.Allowed -> EscapeHatchState(
                nightId = nightId,
                usesThisNight = usesSpent(state, nightId) + 1,
                openUntil = now.plus(grantDuration),
            )
        }

    /** Closes an open hatch early, keeping the use counted against tonight. */
    fun close(state: EscapeHatchState): EscapeHatchState = state.copy(openUntil = null)
}

/** One line of the record the Tonight screen shows back. */
data class EscapeHatchRecord(
    val openedAt: Instant,
    val closesAt: Instant,
    val reason: UnlockReason,
    val note: String,
    val nightId: LocalDate,
    val useOfNight: Int,
    val phase: NightPhase,
)
