package com.teamx.drift.ui

import android.Manifest
import android.app.TimePickerDialog
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.teamx.drift.R
import com.teamx.drift.core.ChangeDecision
import com.teamx.drift.core.Commitments
import com.teamx.drift.core.NightPhase
import com.teamx.drift.core.NightStatus
import com.teamx.drift.data.DriftSettings
import com.teamx.drift.databinding.ActivityTonightBinding
import com.teamx.drift.databinding.ItemPermissionBinding
import com.teamx.drift.night.DriftService
import com.teamx.drift.night.NightController
import com.teamx.drift.util.DevicePackages
import com.teamx.drift.util.Permissions
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Tonight.
 *
 * One screen that answers "what is my phone about to do, and what will it leave me?",
 * plus the few settings that shape it. Deliberately not a dashboard: nobody needs to
 * study a chart before going to bed.
 */
class TonightActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTonightBinding
    private val settings by lazy { DriftSettings.getInstance(this) }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTonightBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.enabledSwitch.setOnCheckedChangeListener { button, isChecked ->
            if (!button.isPressed) return@setOnCheckedChangeListener
            propose { it.copy(enabled = isChecked) }
        }

        binding.sleepAtButton.setOnClickListener {
            pickTime(settings.sleepAt) { picked ->
                propose { it.copy(schedule = it.schedule.copy(sleepAt = picked)) }
            }
        }

        binding.wakeAtButton.setOnClickListener {
            pickTime(settings.wakeAt) { picked ->
                propose { it.copy(schedule = it.schedule.copy(wakeAt = picked)) }
            }
        }

        binding.windDownLeadButton.setOnClickListener {
            val next = nextIn(LEAD_CYCLE, settings.windDownLeadMinutes).toLong()
            propose {
                val lead = Duration.ofMinutes(next)
                it.copy(
                    schedule = it.schedule.copy(
                        windDownLead = lead,
                        quietLead = minOf(it.schedule.quietLead, lead),
                    ),
                )
            }
        }

        binding.quietLeadButton.setOnClickListener {
            val next = nextIn(LEAD_CYCLE, settings.quietLeadMinutes).toLong()
            propose {
                it.copy(
                    schedule = it.schedule.copy(
                        quietLead = minOf(Duration.ofMinutes(next), it.schedule.windDownLead),
                    ),
                )
            }
        }

        binding.limitsRow.setOnClickListener { AppLimitsActivity.open(this) }

        binding.editWindowSwitch.setOnCheckedChangeListener { button, isChecked ->
            if (!button.isPressed) return@setOnCheckedChangeListener
            // Turning the window on is a tightening, so it is always allowed. Turning it
            // off is not a commitment change at all - it is the lock itself - so it is
            // only permitted while the window is open or an opening is running.
            if (!isChecked && !canRelax()) {
                binding.editWindowSwitch.isChecked = true
                LockedChangeDialog.show(this, blockedNow(listOf(getString(R.string.locked_reason_window))))
                return@setOnCheckedChangeListener
            }
            settings.editWindowEnabled = isChecked
            render()
        }

        binding.editWindowFromButton.setOnClickListener {
            if (!requireRelaxable()) return@setOnClickListener
            pickTime(settings.editWindowFrom) { picked ->
                settings.editWindowFrom = picked
                render()
            }
        }

        binding.editWindowToButton.setOnClickListener {
            if (!requireRelaxable()) return@setOnClickListener
            pickTime(settings.editWindowTo) { picked ->
                settings.editWindowTo = picked
                render()
            }
        }

        binding.editWindowDaysButton.setOnClickListener {
            if (!requireRelaxable()) return@setOnClickListener
            pickDays()
        }

        binding.essentialsRow.setOnClickListener { EssentialsActivity.open(this) }

        binding.putAwayRow.setOnClickListener {
            AppPickerActivity.open(this, AppPickerActivity.List.PUT_AWAY)
        }

        binding.keepRow.setOnClickListener {
            AppPickerActivity.open(this, AppPickerActivity.List.KEEP)
        }

        binding.maxUsesButton.setOnClickListener {
            val next = nextMaxUses(settings.maxUsesPerNight)
            propose { it.copy(escapeHatch = it.escapeHatch.copy(maxUsesPerNight = next)) }
        }

        binding.grantMinutesButton.setOnClickListener {
            val next = nextIn(GRANT_CYCLE, settings.grantMinutes).toLong()
            propose {
                it.copy(escapeHatch = it.escapeHatch.copy(grantDuration = Duration.ofMinutes(next)))
            }
        }

        binding.extensionsButton.setOnClickListener {
            val next = nextIn(EXTENSION_CYCLE, settings.maxExtensionsPerDay)
            propose { it.copy(limitPolicy = it.limitPolicy.copy(maxExtensionsPerDay = next)) }
        }

        binding.blockSettingsSwitch.setOnCheckedChangeListener { button, isChecked ->
            if (!button.isPressed) return@setOnCheckedChangeListener
            propose { it.copy(blockSettingsFrom = if (isChecked) NightPhase.QUIET else null) }
        }

        binding.vendorHintButton.setOnClickListener { Permissions.openAppDetails(this) }

        binding.previewButton.setOnClickListener { NightScreenActivity.preview(this) }

        binding.endBorrowedTime.setOnClickListener {
            NightController.closeEscapeHatch(this)
            render()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                NightController.status.collectLatest { renderStatus(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Permissions and app choices are granted elsewhere, so re-read on every return.
        NightController.invalidateAccessPolicy()
        DriftService.sync(this)
        render()
    }

    /**
     * Applies a change if the guard allows it, and explains itself if not.
     *
     * Every setting on this screen goes through here, so the rule is in one place:
     * tightening always works, loosening needs the window or an opening.
     */
    private fun propose(change: (Commitments) -> Commitments) {
        when (val decision = NightController.proposeChange(this, change)) {
            is ChangeDecision.Allowed -> {
                DriftService.sync(this)
                render()
            }

            is ChangeDecision.Blocked -> LockedChangeDialog.show(this, decision) { render() }
        }
    }

    /** Whether the rules may be relaxed at this moment. */
    private fun canRelax(): Boolean =
        NightController.previewChange(this) { it.copy(enabled = !it.enabled) }.isAllowed

    private fun requireRelaxable(): Boolean {
        if (canRelax()) return true
        LockedChangeDialog.show(this, blockedNow(listOf(getString(R.string.locked_reason_window))))
        return false
    }

    private fun blockedNow(reasons: List<String>) = ChangeDecision.Blocked(
        loosenings = reasons,
        opensAt = settings.editWindow.opensAfter(java.time.LocalDateTime.now()),
        canUseEscapeHatch = (NightController.usesLeftTonight(this) ?: 1) > 0,
    )

    private fun pickDays() {
        val all = DayOfWeek.entries.toTypedArray()
        val selected = settings.editWindowDays
        val checked = all.map { it in selected }.toBooleanArray()
        val labels = all.map { it.getDisplayName(TextStyle.FULL, Locale.getDefault()) }
            .toTypedArray()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.tonight_window_days)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(R.string.picker_done) { _, _ ->
                settings.editWindowDays = all.filterIndexed { index, _ -> checked[index] }.toSet()
                render()
            }
            .setNegativeButton(R.string.reason_cancel, null)
            .show()
    }

    private fun afterScheduleChange() {
        DriftService.sync(this)
        render()
    }

    private fun render() {
        binding.enabledSwitch.isChecked = settings.enabled
        binding.sleepAtButton.text = settings.sleepAt.format(TIME_FORMAT)
        binding.wakeAtButton.text = settings.wakeAt.format(TIME_FORMAT)
        binding.windDownLeadButton.text = leadLabel(settings.windDownLeadMinutes)
        binding.quietLeadButton.text = leadLabel(settings.quietLeadMinutes)
        binding.scheduleWarning.isVisible = settings.schedule.isEmpty

        binding.maxUsesButton.text = settings.maxUsesPerNight
            ?.let { getString(R.string.tonight_per_night, it) }
            ?: getString(R.string.tonight_unlimited)
        binding.grantMinutesButton.text =
            getString(R.string.tonight_minutes, settings.grantMinutes)
        binding.blockSettingsSwitch.isChecked = settings.blockSettingsFrom != null

        binding.extensionsButton.text = when (val count = settings.maxExtensionsPerDay) {
            0 -> getString(R.string.tonight_extensions_none)
            else -> getString(R.string.tonight_extensions, count, settings.extensionMinutes)
        }

        renderAppLists()
        renderLimits()
        renderEditWindow()
        renderStatus(NightController.refresh(this))
        renderPermissions()
        renderLastNight()
    }

    private fun renderLimits() {
        val limited = settings.appLimits.limitedPackages
        binding.limitsSummary.text = if (limited.isEmpty()) {
            getString(R.string.tonight_limits_empty)
        } else {
            val names = limited.take(3).map { DevicePackages.label(this, it) }.sorted()
            val rest = limited.size - names.size
            if (rest > 0) {
                getString(R.string.tonight_app_summary_more, names.joinToString(", "), rest)
            } else {
                names.joinToString(", ")
            }
        }
        binding.limitsNeedsUsage.isVisible =
            limited.isNotEmpty() && !Permissions.hasUsageAccess(this)
    }

    private fun renderEditWindow() {
        val window = settings.editWindow
        binding.editWindowSwitch.isChecked = window.enabled
        binding.editWindowFromButton.text = window.from.format(TIME_FORMAT)
        binding.editWindowToButton.text = window.to.format(TIME_FORMAT)
        binding.editWindowDaysButton.text = daysLabel(window.days)
        binding.editWindowRows.isVisible = window.enabled
        binding.editWindowWarning.isVisible = window.enabled && window.isUnusable
        binding.editWindowState.isVisible = window.enabled && !window.isUnusable
        binding.editWindowState.setText(
            if (canRelax()) R.string.tonight_window_open_now else R.string.tonight_window_closed_now,
        )
    }

    private fun daysLabel(days: Set<DayOfWeek>): String = when {
        days.isEmpty() -> getString(R.string.tonight_window_no_days)
        days == EditWindowDays.EVERY -> getString(R.string.tonight_window_every_day)
        days == EditWindowDays.WEEKDAYS -> getString(R.string.tonight_window_weekdays)
        else -> days.sorted().joinToString(", ") {
            it.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        }
    }

    private object EditWindowDays {
        val EVERY: Set<DayOfWeek> = DayOfWeek.entries.toSet()
        val WEEKDAYS: Set<DayOfWeek> = com.teamx.drift.core.EditWindow.WEEKDAYS
    }

    private fun renderAppLists() {
        val roles = settings.essentialRoles
        binding.essentialsSummary.text = if (roles.isEmpty()) {
            getString(R.string.tonight_essentials_hint)
        } else {
            roles.sortedBy { it.ordinal }
                .mapNotNull { DevicePackages.labelForRole(this, it) }
                .distinct()
                .joinToString(", ")
                .ifBlank { getString(R.string.tonight_essentials_hint) }
        }

        binding.putAwaySummary.text = summarise(settings.distractingPackages, R.string.tonight_put_away_empty)
        binding.keepSummary.text = summarise(settings.essentialPackages, R.string.tonight_keep_empty)
    }

    private fun summarise(packages: Set<String>, emptyRes: Int): String {
        if (packages.isEmpty()) return getString(emptyRes)
        val names = packages.take(3).map { DevicePackages.label(this, it) }.sorted()
        val rest = packages.size - names.size
        return if (rest > 0) {
            getString(R.string.tonight_app_summary_more, names.joinToString(", "), rest)
        } else {
            names.joinToString(", ")
        }
    }

    private fun renderStatus(status: NightStatus) {
        binding.endBorrowedTime.isVisible = status.isBorrowingTime

        if (status.scheduleOff) {
            binding.statusHeadline.setText(R.string.tonight_status_off)
            binding.statusDetail.setText(R.string.tonight_status_off_detail)
            binding.statusTimeline.text = ""
            binding.statusTimeline.isVisible = false
            return
        }

        binding.statusTimeline.isVisible = true
        binding.statusTimeline.text = getString(
            R.string.tonight_timeline,
            settings.schedule.windDownAt.format(TIME_FORMAT),
            settings.schedule.quietAt.format(TIME_FORMAT),
            settings.sleepAt.format(TIME_FORMAT),
            settings.wakeAt.format(TIME_FORMAT),
        )

        if (status.isBorrowingTime) {
            val left = Duration.between(Instant.now(), status.bypassUntil)
                .toMinutes()
                .coerceAtLeast(0)
            binding.statusHeadline.text = getString(R.string.tonight_status_borrowed, left)
            binding.statusDetail.setText(R.string.tonight_status_borrowed_detail)
            return
        }

        val changesAt = status.changesAt
        when (status.phase) {
            NightPhase.OPEN -> {
                val untilWindDown = changesAt
                    ?.let { Duration.between(LocalDateTime.now(), it) }
                    ?: Duration.ZERO
                binding.statusHeadline.text = getString(
                    R.string.tonight_status_open,
                    settings.sleepAt.format(TIME_FORMAT),
                )
                binding.statusDetail.text = getString(
                    R.string.tonight_status_open_detail,
                    formatRemaining(untilWindDown),
                )
            }

            NightPhase.WIND_DOWN -> {
                binding.statusHeadline.setText(R.string.tonight_status_wind_down)
                binding.statusDetail.text = getString(
                    R.string.tonight_status_wind_down_detail,
                    changesAt?.format(TIME_FORMAT).orEmpty(),
                )
            }

            NightPhase.QUIET -> {
                binding.statusHeadline.setText(R.string.tonight_status_quiet)
                binding.statusDetail.text = getString(
                    R.string.tonight_status_quiet_detail,
                    changesAt?.format(TIME_FORMAT).orEmpty(),
                )
            }

            NightPhase.SLEEP -> {
                binding.statusHeadline.setText(R.string.tonight_status_sleep)
                binding.statusDetail.text = getString(
                    R.string.tonight_status_sleep_detail,
                    changesAt?.format(TIME_FORMAT).orEmpty(),
                )
            }
        }
    }

    /**
     * A small amount of history, and no more. What happened last night is worth a line;
     * it is not worth a chart.
     */
    private fun renderLastNight() {
        val tonight = NightController.currentNightId(this)
        val previous = settings.history().firstOrNull { it.nightId != tonight }
        val takenTonight = settings.historyFor(tonight)

        binding.lastNight.text = when {
            takenTonight.isNotEmpty() -> resources.getQuantityString(
                R.plurals.tonight_opens_so_far,
                takenTonight.size,
                takenTonight.size,
            )

            previous == null -> getString(R.string.tonight_history_empty)

            else -> getString(
                R.string.tonight_history_last,
                previous.openedAt.atZone(ZoneId.systemDefault()).format(HISTORY_FORMAT),
            )
        }
    }

    private fun renderPermissions() {
        binding.permissionList.removeAllViews()

        addPermissionRow(
            R.string.perm_guard,
            R.string.perm_guard_hint,
            Permissions.isAccessibilityServiceEnabled(this),
        ) { Permissions.openAccessibilitySettings(this) }

        addPermissionRow(
            R.string.perm_overlay,
            R.string.perm_overlay_hint,
            Permissions.canDrawOverlays(this),
        ) { Permissions.requestOverlayPermission(this) }

        addPermissionRow(
            R.string.perm_alarms,
            R.string.perm_alarms_hint,
            Permissions.canScheduleExactAlarms(this),
        ) { Permissions.requestExactAlarmPermission(this) }

        addPermissionRow(
            R.string.perm_notifications,
            R.string.perm_notifications_hint,
            Permissions.hasNotificationPermission(this),
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        addPermissionRow(
            R.string.perm_usage,
            R.string.perm_usage_hint,
            Permissions.hasUsageAccess(this),
        ) { Permissions.openUsageAccessSettings(this) }

        addPermissionRow(
            R.string.perm_battery,
            R.string.perm_battery_hint,
            Permissions.isIgnoringBatteryOptimizations(this),
        ) { Permissions.openBatteryOptimizationSettings(this) }

        renderVendorHint()
    }

    private fun renderVendorHint() {
        val needed = Permissions.needsVendorBackgroundSetup()
        binding.vendorHint.isVisible = needed
        binding.vendorHintButton.isVisible = needed
        if (needed) {
            binding.vendorHint.text = getString(R.string.vendor_hint, android.os.Build.MANUFACTURER)
        }
    }

    private fun addPermissionRow(titleRes: Int, hintRes: Int, granted: Boolean, onFix: () -> Unit) {
        val row = ItemPermissionBinding.inflate(layoutInflater, binding.permissionList, true)
        row.title.text = getString(titleRes)
        row.subtitle.text = getString(hintRes)
        row.action.isEnabled = !granted
        row.action.setText(if (granted) R.string.perm_done else R.string.perm_grant)
        row.action.setOnClickListener {
            runCatching { onFix() }.onFailure {
                Toast.makeText(this, it.localizedMessage, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun pickTime(current: LocalTime, onPicked: (LocalTime) -> Unit) {
        TimePickerDialog(
            this,
            { _, hour, minute -> onPicked(LocalTime.of(hour, minute)) },
            current.hour,
            current.minute,
            true,
        ).show()
    }

    private fun leadLabel(minutes: Int): String = when {
        minutes == 0 -> getString(R.string.tonight_lead_none)
        minutes % 60 == 0 -> getString(R.string.tonight_lead_hours, minutes / 60)
        else -> getString(R.string.tonight_minutes, minutes)
    }

    private fun formatRemaining(duration: Duration): String {
        val total = duration.coerceAtLeast(Duration.ZERO)
        val hours = total.toHours()
        val minutes = total.toMinutes() % 60
        return if (hours > 0) {
            getString(R.string.duration_hours_minutes, hours, minutes)
        } else {
            getString(R.string.duration_minutes, minutes)
        }
    }

    private fun nextIn(cycle: List<Int>, current: Int): Int =
        cycle[(cycle.indexOf(current) + 1) % cycle.size]

    private fun nextMaxUses(current: Int?): Int? {
        val cycle = listOf(1, 2, 3, 5, null)
        return cycle[(cycle.indexOf(current) + 1) % cycle.size]
    }

    private companion object {
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val HISTORY_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        val LEAD_CYCLE = listOf(0, 15, 30, 45, 60, 90, 120)
        val GRANT_CYCLE = listOf(5, 10, 15, 30, 60)
        val EXTENSION_CYCLE = listOf(0, 1, 2, 3, 5)
    }
}
