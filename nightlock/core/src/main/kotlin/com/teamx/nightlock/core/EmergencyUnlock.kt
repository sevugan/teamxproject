package com.teamx.nightlock.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Persisted state of the emergency escape hatch.
 *
 * [nightId] scopes [usesThisNight]: when the current night differs, the allowance has
 * refreshed and the stored count no longer applies.
 */
data class EmergencyState(
    val nightId: LocalDate? = null,
    val usesThisNight: Int = 0,
    val bypassUntil: Instant? = null,
) {
    fun isBypassActive(now: Instant): Boolean = bypassUntil != null && now < bypassUntil

    fun bypassRemaining(now: Instant): Duration =
        if (isBypassActive(now)) Duration.between(now, bypassUntil) else Duration.ZERO
}

sealed interface EmergencyDecision {
    /** The unlock may be granted. [usesLeftAfter] is what remains once it is used. */
    data class Allowed(val usesLeftAfter: Int) : EmergencyDecision

    /** Tonight's allowance is spent. Calling still works; full access does not. */
    data class Exhausted(val maxPerNight: Int) : EmergencyDecision
}

/**
 * Rules for the emergency unlock.
 *
 * Deliberately cheap to use in a real emergency (no password, no waiting for 06:00)
 * but costly enough to be a poor habit: a press and hold confirmation, a capped number
 * of uses per night, and a grant that expires on its own.
 */
data class EmergencyPolicy(
    /** Unlocks allowed per night, or null for unlimited. */
    val maxUnlocksPerNight: Int? = 3,
    /** How long full access lasts once granted. */
    val grantDuration: Duration = Duration.ofMinutes(15),
    /** How long the confirm button must be held before the grant is issued. */
    val holdToConfirm: Duration = Duration.ofSeconds(5),
) {
    init {
        require(maxUnlocksPerNight == null || maxUnlocksPerNight >= 0) {
            "maxUnlocksPerNight must be null (unlimited) or >= 0"
        }
        require(!grantDuration.isNegative && !grantDuration.isZero) {
            "grantDuration must be positive"
        }
        require(!holdToConfirm.isNegative) { "holdToConfirm must not be negative" }
    }

    /** Uses already spent during [nightId], ignoring counts left over from other nights. */
    fun usesSpent(state: EmergencyState, nightId: LocalDate): Int =
        if (state.nightId == nightId) state.usesThisNight else 0

    /** Uses still available during [nightId]; null means unlimited. */
    fun usesLeft(state: EmergencyState, nightId: LocalDate): Int? =
        maxUnlocksPerNight?.let { (it - usesSpent(state, nightId)).coerceAtLeast(0) }

    fun evaluate(state: EmergencyState, nightId: LocalDate): EmergencyDecision {
        val left = usesLeft(state, nightId) ?: return EmergencyDecision.Allowed(Int.MAX_VALUE)
        return if (left > 0) {
            EmergencyDecision.Allowed(left - 1)
        } else {
            EmergencyDecision.Exhausted(maxUnlocksPerNight ?: 0)
        }
    }

    /**
     * Records a granted unlock. Returns the state unchanged when the allowance is spent,
     * so callers cannot accidentally hand out an unlock they just refused.
     */
    fun grant(state: EmergencyState, nightId: LocalDate, now: Instant): EmergencyState =
        when (evaluate(state, nightId)) {
            is EmergencyDecision.Exhausted -> state
            is EmergencyDecision.Allowed -> EmergencyState(
                nightId = nightId,
                usesThisNight = usesSpent(state, nightId) + 1,
                bypassUntil = now.plus(grantDuration),
            )
        }

    /** Ends an active grant early, keeping the use counted against tonight. */
    fun endBypass(state: EmergencyState): EmergencyState = state.copy(bypassUntil = null)
}

/** One line of the audit trail shown on the setup screen. */
data class EmergencyUnlockRecord(
    val grantedAt: Instant,
    val expiresAt: Instant,
    val reason: String,
    val nightId: LocalDate,
    val useOfNight: Int,
)
