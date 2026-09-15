package com.teamx.drift.util

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager

/** One installed app, as the picker shows it. */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
)

/**
 * Works out, on this particular device, which packages are calling, clocks, home screens
 * and keyboards.
 *
 * Resolved at runtime rather than hard coded: these differ between manufacturers, and
 * getting the first one wrong would mean a phone that cannot make a call at 03:00.
 */
object DevicePackages {

    fun call(context: Context): Set<String> {
        val packages = mutableSetOf<String>()

        runCatching {
            context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        }.getOrNull()?.let(packages::add)

        val intents = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:")),
            Intent(Intent.ACTION_VIEW, Uri.parse("tel:")),
        )
        intents.forEach { intent -> packages += resolveAll(context, intent) }

        // Telecom, the in-call UI and the emergency dialer are part of a call even when
        // the dialer app itself is not what is on screen.
        packages += setOf(
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.incallui",
            "com.google.android.dialer",
            "com.android.dialer",
            "com.android.emergency",
        )

        return packages
    }

    fun clock(context: Context): Set<String> {
        val packages = mutableSetOf<String>()
        packages += resolveAll(context, Intent(AlarmClock.ACTION_SHOW_ALARMS))
        packages += resolveAll(context, Intent(AlarmClock.ACTION_SET_ALARM))
        packages += setOf("com.google.android.deskclock", "com.android.deskclock")
        return packages
    }

    fun launchers(context: Context): Set<String> =
        resolveAll(context, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))

    /** Keyboards, so that typing is never what trips the guard. */
    fun inputMethods(context: Context): Set<String> = runCatching {
        context.getSystemService(InputMethodManager::class.java)
            ?.enabledInputMethodList
            ?.map { it.packageName }
            ?.toSet()
            .orEmpty()
    }.getOrDefault(emptySet())

    /**
     * Everything with a launcher icon, for the app pickers. Sorted by label, and never
     * including Drift itself: putting this app away would be a strange thing to offer.
     */
    fun launchable(context: Context): List<InstalledApp> {
        val packageManager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return runCatching { packageManager.queryIntentActivities(intent, 0) }
            .getOrDefault(emptyList())
            .asSequence()
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != context.packageName }
            .map { info ->
                InstalledApp(
                    packageName = info.packageName,
                    label = runCatching { packageManager.getApplicationLabel(info).toString() }
                        .getOrDefault(info.packageName),
                    isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /** A human readable name for one package, falling back to the package itself. */
    fun label(context: Context, packageName: String): String = runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)

    private fun resolveAll(context: Context, intent: Intent): Set<String> = runCatching {
        context.packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }
            .toSet()
    }.getOrDefault(emptySet())
}
