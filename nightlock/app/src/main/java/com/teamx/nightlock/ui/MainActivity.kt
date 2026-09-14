package com.teamx.nightlock.ui

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
import com.teamx.nightlock.R
import com.teamx.nightlock.core.LockStatus
import com.teamx.nightlock.data.LockSettings
import com.teamx.nightlock.databinding.ActivityMainBinding
import com.teamx.nightlock.databinding.ItemPermissionBinding
import com.teamx.nightlock.lock.LockController
import com.teamx.nightlock.lock.LockForegroundService
import com.teamx.nightlock.util.Permissions
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Setup and status: when the curfew runs, how forgiving the emergency unlock is, which
 * permissions are still missing, and what has been unlocked in an emergency so far.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val settings by lazy { LockSettings.getInstance(this) }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.enabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            settings.enabled = isChecked
            LockForegroundService.sync(this)
            render()
        }

        binding.startTimeButton.setOnClickListener {
            pickTime(settings.startTime) { picked ->
                settings.startTime = picked
                afterScheduleChange()
            }
        }

        binding.endTimeButton.setOnClickListener {
            pickTime(settings.endTime) { picked ->
                settings.endTime = picked
                afterScheduleChange()
            }
        }

        binding.maxUnlocksButton.setOnClickListener {
            settings.maxUnlocksPerNight = nextMaxUnlocks(settings.maxUnlocksPerNight)
            render()
        }

        binding.grantMinutesButton.setOnClickListener {
            settings.grantMinutes = nextGrantMinutes(settings.grantMinutes)
            render()
        }

        binding.blockSettingsSwitch.setOnCheckedChangeListener { _, isChecked ->
            settings.blockSettings = isChecked
            LockController.invalidateAccessPolicy()
        }

        binding.previewButton.setOnClickListener { LockActivity.preview(this) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                LockController.status.collectLatest { renderStatus(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Permissions are granted in the system settings, so re-read them on every return.
        LockController.invalidateAccessPolicy()
        LockForegroundService.sync(this)
        render()
    }

    private fun afterScheduleChange() {
        LockForegroundService.sync(this)
        render()
    }

    private fun render() {
        binding.enabledSwitch.isChecked = settings.enabled
        binding.startTimeButton.text = settings.startTime.format(TIME_FORMAT)
        binding.endTimeButton.text = settings.endTime.format(TIME_FORMAT)
        binding.sameTimeWarning.isVisible = settings.window.isEmpty
        binding.maxUnlocksButton.text = settings.maxUnlocksPerNight
            ?.let { getString(R.string.setup_per_night, it) }
            ?: getString(R.string.setup_unlimited)
        binding.grantMinutesButton.text = getString(R.string.setup_minutes, settings.grantMinutes)
        binding.blockSettingsSwitch.isChecked = settings.blockSettings

        renderStatus(LockController.refresh(this))
        renderPermissions()
        renderHistory()
    }

    private fun renderStatus(status: LockStatus) {
        when (status) {
            is LockStatus.Locked -> {
                binding.statusHeadline.text =
                    getString(R.string.setup_status_locked, status.lockedUntil.format(TIME_FORMAT))
                binding.statusDetail.setText(R.string.setup_status_detail_locked)
            }

            is LockStatus.Bypassed -> {
                val minutesLeft = Duration.between(Instant.now(), status.bypassUntil)
                    .toMinutes()
                    .coerceAtLeast(0)
                binding.statusHeadline.text =
                    getString(R.string.setup_status_bypassed, minutesLeft)
                binding.statusDetail.setText(R.string.setup_status_detail_bypassed)
            }

            is LockStatus.Unlocked -> {
                binding.statusHeadline.text =
                    getString(R.string.setup_status_unlocked, status.nextLockAt.format(TIME_FORMAT))
                binding.statusDetail.text = getString(
                    R.string.setup_status_detail_unlocked,
                    settings.startTime.format(TIME_FORMAT),
                    settings.endTime.format(TIME_FORMAT),
                )
            }

            LockStatus.Off -> {
                binding.statusHeadline.setText(R.string.setup_status_off)
                binding.statusDetail.setText(R.string.setup_status_detail_off)
            }
        }
    }

    private fun renderPermissions() {
        binding.permissionList.removeAllViews()

        addPermissionRow(
            titleRes = R.string.setup_perm_accessibility,
            hintRes = R.string.setup_perm_accessibility_hint,
            granted = Permissions.isAccessibilityServiceEnabled(this),
        ) { Permissions.openAccessibilitySettings(this) }

        addPermissionRow(
            titleRes = R.string.setup_perm_overlay,
            hintRes = R.string.setup_perm_overlay_hint,
            granted = Permissions.canDrawOverlays(this),
        ) { Permissions.requestOverlayPermission(this) }

        addPermissionRow(
            titleRes = R.string.setup_perm_alarm,
            hintRes = R.string.setup_perm_alarm_hint,
            granted = Permissions.canScheduleExactAlarms(this),
        ) { Permissions.requestExactAlarmPermission(this) }

        addPermissionRow(
            titleRes = R.string.setup_perm_notifications,
            hintRes = R.string.setup_perm_notifications_hint,
            granted = Permissions.hasNotificationPermission(this),
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        addPermissionRow(
            titleRes = R.string.setup_perm_battery,
            hintRes = R.string.setup_perm_battery_hint,
            granted = Permissions.isIgnoringBatteryOptimizations(this),
        ) { Permissions.openBatteryOptimizationSettings(this) }
    }

    private fun addPermissionRow(
        titleRes: Int,
        hintRes: Int,
        granted: Boolean,
        onFix: () -> Unit,
    ) {
        val row = ItemPermissionBinding.inflate(layoutInflater, binding.permissionList, true)
        row.title.text = getString(titleRes)
        row.subtitle.text = getString(hintRes)
        row.action.isEnabled = !granted
        row.action.setText(
            if (granted) R.string.setup_permission_done else R.string.setup_permission_fix,
        )
        row.action.setOnClickListener {
            runCatching { onFix() }.onFailure {
                Toast.makeText(this, it.localizedMessage, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun renderHistory() {
        val history = settings.emergencyHistory()
        binding.history.text = if (history.isEmpty()) {
            getString(R.string.setup_history_empty)
        } else {
            history.joinToString("\n") { record ->
                val moment = record.grantedAt.atZone(ZoneId.systemDefault())
                    .format(HISTORY_FORMAT)
                val reason = record.reason.ifBlank { getString(R.string.setup_history_no_reason) }
                getString(R.string.setup_history_entry, moment, record.useOfNight, reason)
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

    private fun nextMaxUnlocks(current: Int?): Int? {
        val cycle = listOf(1, 2, 3, 5, null)
        val index = cycle.indexOf(current)
        return cycle[(index + 1) % cycle.size]
    }

    private fun nextGrantMinutes(current: Int): Int {
        val cycle = listOf(5, 10, 15, 30, 60)
        val index = cycle.indexOf(current)
        return cycle[(index + 1) % cycle.size]
    }

    private companion object {
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val HISTORY_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
    }
}
