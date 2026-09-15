package com.teamx.drift.night

import android.content.Context
import com.teamx.drift.core.AppAccessPolicy
import com.teamx.drift.core.AppLimitEvaluator
import com.teamx.drift.core.ChangeDecision
import com.teamx.drift.core.ChangeGuard
import com.teamx.drift.core.Commitments
import com.teamx.drift.core.EscapeHatchDecision
import com.teamx.drift.core.EscapeHatchRecord
import com.teamx.drift.core.LimitVerdict
import com.teamx.drift.core.NightEvaluator
import com.teamx.drift.core.NightPhase
import com.teamx.drift.core.UsageDay
import com.teamx.drift.core.NightStatus
import com.teamx.drift.core.UnlockReason
import com.teamx.drift.data.DriftSettings
import com.teamx.drift.util.DevicePackages
import com.teamx.drift.util.UsageTracker
import java.time.Duration
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

    // ---- daily app limits -----------------------------------------------------

    /** The day limits are counted against: starts when you wake, not at midnight. */
    fun usageDayId(context: Context): LocalDate =
        UsageDay.idOf(LocalDateTime.now(), DriftSettings.getInstance(context).wakeAt)

    private fun usageDayStart(context: Context): LocalDateTime =
        UsageDay.startOf(LocalDateTime.now(), DriftSettings.getInstance(context).wakeAt)

    /** How long [packageName] has been on screen since the day began. */
    fun usedToday(context: Context, packageName: String): Duration =
        UsageTracker.usageOf(context, packageName, usageDayStart(context))

    /** Where [packageName] stands against its limit right now. */
    fun limitVerdict(context: Context, packageName: String): LimitVerdict {
        val settings = DriftSettings.getInstance(context)
        if (!settings.appLimits.isLimited(packageName)) return LimitVerdict.Untimed
        return AppLimitEvaluator.verdict(
            packageName = packageName,
            usedToday = usedToday(context, packageName),
            limits = settings.appLimits,
            extensions = settings.limitExtensions,
            policy = settings.limitPolicy,
            dayId = usageDayId(context),
        )
    }

    /**
     * Whether a limit should close [packageName] right now.
     *
     * An open escape hatch suspends limits as well as the night: one way past, not two.
     */
    fun isOutOfTime(context: Context, packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        if (_status.value.isBorrowingTime) return false
        return limitVerdict(context, packageName).closesTheApp
    }

    /** Borrows one extension for [packageName]. Returns false when the cap is reached. */
    fun extendLimit(context: Context, packageName: String): Boolean {
        val settings = DriftSettings.getInstance(context)
        val dayId = usageDayId(context)
        val before = settings.limitExtensions
        val after = settings.limitPolicy.extend(before, packageName, dayId)
        if (after == before) return false
        settings.limitExtensions = after
        return true
    }

    fun extensionsLeft(context: Context, packageName: String): Int {
        val settings = DriftSettings.getInstance(context)
        return settings.limitPolicy.extensionsLeft(
            settings.limitExtensions,
            packageName,
            usageDayId(context),
        )
    }

    // ---- changing the rules ---------------------------------------------------

    /**
     * Applies a change to the commitments, if it is allowed to happen now.
     *
     * Tightening always goes through. Loosening needs the edit window to be open, or an
     * escape hatch opening already running. Nothing is written when the answer is no.
     */
    fun proposeChange(context: Context, change: (Commitments) -> Commitments): ChangeDecision {
        val settings = DriftSettings.getInstance(context)
        val before = settings.commitments
        val after = change(before)

        val decision = ChangeGuard.evaluate(
            from = before,
            to = after,
            window = settings.editWindow,
            now = LocalDateTime.now(),
            escapeHatchOpen = _status.value.isBorrowingTime,
            escapeHatchAvailable = usesLeftTonight(context)?.let { it > 0 } ?: true,
        )

        if (decision.isAllowed) {
            settings.commitments = after
            invalidateAccessPolicy()
            refresh(context)
        }
        return decision
    }

    /** What a change would be judged as, without applying it. */
    fun previewChange(context: Context, change: (Commitments) -> Commitments): ChangeDecision {
        val settings = DriftSettings.getInstance(context)
        val before = settings.commitments
        return ChangeGuard.evaluate(
            from = before,
            to = change(before),
            window = settings.editWindow,
            now = LocalDateTime.now(),
            escapeHatchOpen = _status.value.isBorrowingTime,
            escapeHatchAvailable = usesLeftTonight(context)?.let { it > 0 } ?: true,
        )
    }
}
