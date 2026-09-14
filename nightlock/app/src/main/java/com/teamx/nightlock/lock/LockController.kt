package com.teamx.nightlock.lock

import android.content.Context
import com.teamx.nightlock.core.AppAccessPolicy
import com.teamx.nightlock.core.EmergencyDecision
import com.teamx.nightlock.core.EmergencyUnlockRecord
import com.teamx.nightlock.core.LockEvaluator
import com.teamx.nightlock.core.LockStatus
import com.teamx.nightlock.data.LockSettings
import com.teamx.nightlock.util.CallPackages
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one voice of truth about whether the phone is locked right now.
 *
 * The alarm receiver, the foreground service, the accessibility guard and both screens
 * all go through here, so none of them can disagree about the state of the night.
 */
object LockController {

    private val _status = MutableStateFlow<LockStatus>(LockStatus.Off)
    val status: StateFlow<LockStatus> = _status.asStateFlow()

    @Volatile
    private var cachedPolicy: Pair<Long, AppAccessPolicy>? = null

    @Volatile
    private var lastForegroundPackage: String? = null

    /** Recorded by the accessibility guard on every window change. */
    fun onForegroundPackage(packageName: String?) {
        if (!packageName.isNullOrBlank()) lastForegroundPackage = packageName
    }

    /**
     * Whether what is on screen right now is something the curfew closes.
     *
     * Lets the service tell "the user reopened Instagram" apart from "the user is on a
     * call at 02:00", so the lock screen is never thrown over an in-progress call.
     */
    fun isForegroundAppBlocked(context: Context): Boolean =
        accessPolicy(context).isBlockedWhileLocked(lastForegroundPackage)

    /** Recomputes the status from the clock and the stored state, and publishes it. */
    fun refresh(context: Context): LockStatus {
        val settings = LockSettings.getInstance(context)
        val status = LockEvaluator.evaluate(
            config = settings.config,
            state = settings.emergencyState,
            now = LocalDateTime.now(),
            nowInstant = Instant.now(),
        )
        _status.value = status
        return status
    }

    fun currentNightId(context: Context): LocalDate =
        LockSettings.getInstance(context).window.nightId(LocalDateTime.now())

    fun emergencyUsesLeft(context: Context): Int? {
        val settings = LockSettings.getInstance(context)
        return settings.emergencyPolicy.usesLeft(settings.emergencyState, currentNightId(context))
    }

    /**
     * Spends one of tonight's emergency unlocks. Returns what the policy decided, so the
     * caller can tell the user that calling still works even when the answer is no.
     */
    fun requestEmergencyUnlock(context: Context, reason: String): EmergencyDecision {
        val settings = LockSettings.getInstance(context)
        val policy = settings.emergencyPolicy
        val nightId = currentNightId(context)
        val now = Instant.now()
        val before = settings.emergencyState

        val decision = policy.evaluate(before, nightId)
        if (decision is EmergencyDecision.Allowed) {
            val after = policy.grant(before, nightId, now)
            settings.emergencyState = after
            settings.recordEmergencyUnlock(
                EmergencyUnlockRecord(
                    grantedAt = now,
                    expiresAt = after.bypassUntil ?: now,
                    reason = reason.trim(),
                    nightId = nightId,
                    useOfNight = after.usesThisNight,
                ),
            )
            refresh(context)
            LockForegroundService.sync(context)
        }
        return decision
    }

    /** Hands the phone back to the lock before the grant runs out. */
    fun endBypass(context: Context) {
        val settings = LockSettings.getInstance(context)
        settings.emergencyState = settings.emergencyPolicy.endBypass(settings.emergencyState)
        refresh(context)
        LockForegroundService.sync(context)
    }

    /**
     * Which apps may be in the foreground while locked. Cached per settings revision:
     * the accessibility guard asks on every window change, and resolving the dialer
     * hits the package manager.
     */
    fun accessPolicy(context: Context): AppAccessPolicy {
        val settings = LockSettings.getInstance(context)
        val revision = settings.revision.value
        cachedPolicy?.let { (cachedRevision, policy) ->
            if (cachedRevision == revision) return policy
        }
        val policy = AppAccessPolicy(
            selfPackage = context.packageName,
            callPackages = CallPackages.resolve(context),
            inputMethodPackages = CallPackages.inputMethods(context),
            blockSettings = settings.blockSettings,
            userAllowed = settings.allowedPackages,
        )
        cachedPolicy = revision to policy
        return policy
    }

    /** Drops the cached allowlist, e.g. after the default dialer changes. */
    fun invalidateAccessPolicy() {
        cachedPolicy = null
    }
}
