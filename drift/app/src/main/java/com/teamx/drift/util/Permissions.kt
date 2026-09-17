package com.teamx.drift.util

import android.Manifest
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.teamx.drift.night.AppGuardAccessibilityService

/**
 * The four things the lock needs before it can do its job, and how to ask for each.
 *
 * Every check fails safe: if a permission cannot be read, it is reported as missing so
 * the setup screen tells the user rather than silently enforcing nothing.
 */
object Permissions {

    /** The guard that notices which app is in the foreground. Without it nothing is blocked. */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, AppGuardAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled.split(':').any { entry ->
            ComponentName.unflattenFromString(entry) == expected
        }
    }

    fun openAccessibilitySettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Lets the lock screen come to the front while the phone is showing another app. */
    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun requestOverlayPermission(context: Context) {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Without exact alarms the lock can start a few minutes late. */
    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return false
        return alarmManager.canScheduleExactAlarms()
    }

    fun requestExactAlarmPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        context.startActivity(
            Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * Whether Drift can do its job at all.
     *
     * Without the guard it cannot see which app is in the foreground; without the overlay
     * it cannot put a screen in front of one. Missing either means the app is inert, and
     * it should say so rather than showing a confident schedule it is not enforcing.
     */
    fun isOperational(context: Context): Boolean =
        isAccessibilityServiceEnabled(context) && canDrawOverlays(context)

    /** Needed to measure how long each app has been on screen today. */
    fun hasUsageAccess(context: Context): Boolean = UsageTracker.hasPermission(context)

    fun openUsageAccessSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * ROMs that kill background services regardless of the standard battery setting.
     *
     * ColorOS and OxygenOS (OnePlus), MIUI, One UI and Funtouch all add their own layer
     * on top of Android's, and on those phones granting "ignore battery optimisation" is
     * not enough on its own: the app also has to be allowed to auto-launch and run in the
     * background from the manufacturer's own screens.
     */
    fun needsVendorBackgroundSetup(): Boolean =
        Build.MANUFACTURER.lowercase() in AGGRESSIVE_VENDORS

    private val AGGRESSIVE_VENDORS = setOf(
        "oneplus", "oppo", "realme", "xiaomi", "redmi", "poco",
        "vivo", "iqoo", "huawei", "honor", "samsung", "meizu", "asus",
    )

    /** The app's own settings page, where those vendor toggles live. */
    fun openAppDetails(context: Context) {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Battery optimisation can delay the 23:00 alarm on some devices. */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return false
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun openBatteryOptimizationSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
