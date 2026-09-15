package com.teamx.drift.night

import android.content.Context
import com.teamx.drift.core.AppAccessPolicy
import com.teamx.drift.core.EscapeHatchDecision
import com.teamx.drift.core.EscapeHatchRecord
import com.teamx.drift.core.NightEvaluator
import com.teamx.drift.core.NightPhase
import com.teamx.drift.core.NightStatus
import com.teamx.drift.core.UnlockReason
import com.teamx.drift.data.DriftSettings
import com.teamx.drift.util.DevicePackages
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one voice of truth about what the phone is doing tonight.
 *
 * The alarm receiver, the service, the accessibility guard and every screen go through
 * here, so none of them can disagree about which stage of the night it is.
 */
object NightController {

    private val _status = MutableStateFlow(NightStatus.OFF)
    val status: StateFlow<NightStatus> = _status.asStateFlow()

    @Volatile
    private var cachedPolicy: Pair<Long, AppAccessPolicy>? = null

    @Volatile
    private var lastForegroundPackage: String? = null

    /** Recomputes tonight's state from the clock and what is stored, and publishes it. */
    fun refresh(context: Context): NightStatus {
        val settings = DriftSettings.getInstance(context)
        val status = NightEvaluator.evaluate(
            config = settings.config,
            state = settings.escapeHatchState,
            now = LocalDateTime.now(),
            nowInstant = Instant.now(),
        )
        _status.value = status
        return status
    }

    fun currentNightId(context: Context): LocalDate =
        DriftSettings.getInstance(context).schedule.nightId(LocalDateTime.now())

    fun usesLeftTonight(context: Context): Int? {
        val settings = DriftSettings.getInstance(context)
        return settings.escapeHatchPolicy.usesLeft(
            settings.escapeHatchState,
            currentNightId(context),
        )
    }

    /** How many times the phone has already been reopened tonight. */
    fun usesSpentTonight(context: Context): Int {
        val settings = DriftSettings.getInstance(context)
        return settings.escapeHatchPolicy.usesSpent(
            settings.escapeHatchState,
            currentNightId(context),
        )
    }

    /** Recorded by the accessibility guard on every window change. */
    fun onForegroundPackage(packageName: String?) {
        if (!packageName.isNullOrBlank()) lastForegroundPackage = packageName
    }

    /**
     * Whether what is on screen right now is something tonight closes.
     *
     * Lets the service tell "they reopened Instagram" apart from "they are on a call at
     * 02:00", so the night screen is never thrown over an in-progress call.
     */
    fun isForegroundAppBlocked(context: Context): Boolean {
        val status = _status.value
        if (!status.restricts) return false
        return accessPolicy(context).isBlocked(lastForegroundPackage, status.enforced)
    }

    /**
     * Borrows time: opens the phone fully for a while, spends one of tonight's uses, and
     * writes down what was asked for.
     */
    fun openEscapeHatch(
        context: Context,
        reason: UnlockReason,
        note: String = "",
    ): EscapeHatchDecision {
        val settings = DriftSettings.getInstance(context)
        val policy = settings.escapeHatchPolicy
        val nightId = currentNightId(context)
        val now = Instant.now()
        val before = settings.escapeHatchState
        val phase = _status.value.phase

        val decision = policy.evaluate(before, nightId)
        if (decision is EscapeHatchDecision.Allowed) {
            val after = policy.open(before, nightId, now)
            settings.escapeHatchState = after
            settings.record(
                EscapeHatchRecord(
                    openedAt = now,
                    closesAt = after.openUntil ?: now,
                    reason = reason,
                    note = note.trim(),
                    nightId = nightId,
                    useOfNight = after.usesThisNight,
                    phase = phase,
                ),
            )
            refresh(context)
            DriftService.sync(context)
        }
        return decision
    }

    /** Hands the phone back before the borrowed time runs out. */
    fun closeEscapeHatch(context: Context) {
        val settings = DriftSettings.getInstance(context)
        settings.escapeHatchState = settings.escapeHatchPolicy.close(settings.escapeHatchState)
        refresh(context)
        DriftService.sync(context)
    }

    /**
     * Which apps may be in the foreground at each stage. Cached per settings revision:
     * the guard asks on every window change, and resolving the dialer and the launcher
     * hits the package manager.
     */
    fun accessPolicy(context: Context): AppAccessPolicy {
        val settings = DriftSettings.getInstance(context)
        val revision = settings.revision.value
        cachedPolicy?.let { (cachedRevision, policy) ->
            if (cachedRevision == revision) return policy
        }
        val policy = AppAccessPolicy(
            selfPackage = context.packageName,
            callPackages = DevicePackages.call(context),
            clockPackages = DevicePackages.clock(context),
            launcherPackages = DevicePackages.launchers(context),
            distractingPackages = settings.distractingPackages,
            essentialPackages = settings.essentialPackages,
            inputMethodPackages = DevicePackages.inputMethods(context),
            blockSettingsFrom = settings.blockSettingsFrom,
        )
        cachedPolicy = revision to policy
        return policy
    }

    /** Drops the cached allowlist, e.g. after the default dialer or launcher changes. */
    fun invalidateAccessPolicy() {
        cachedPolicy = null
    }

    /** The phase a blocked app was blocked by, for the night screen's copy. */
    fun enforcedPhase(): NightPhase = _status.value.enforced
}
