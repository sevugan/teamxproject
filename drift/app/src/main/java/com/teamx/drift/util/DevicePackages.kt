package com.teamx.drift.util

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import com.teamx.drift.core.EssentialRole

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

    /**
     * The app that fills one role on this device.
     *
     * Android has no way to read a manufacturer's super power saving allowlist — it is
     * vendor private on every ROM that has one. What it does publish is the standard
     * intent for "the default app for X", which is how those lists are built in the first
     * place, so asking the same questions gets the same answers for this phone.
     */
    fun forRole(context: Context, role: EssentialRole): Set<String> = when (role) {
        EssentialRole.PHONE -> call(context)

        EssentialRole.MESSAGES -> buildSet {
            runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()?.let(::add)
            addAll(appCategory(context, Intent.CATEGORY_APP_MESSAGING))
            addAll(resolveAll(context, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))))
        }

        EssentialRole.CONTACTS -> buildSet {
            addAll(appCategory(context, Intent.CATEGORY_APP_CONTACTS))
            addAll(
                resolveAll(
                    context,
                    Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI),
                ),
            )
        }

        EssentialRole.CLOCK -> clock(context)

        EssentialRole.CALCULATOR -> appCategory(context, Intent.CATEGORY_APP_CALCULATOR)

        EssentialRole.CAMERA -> resolveAll(
            context,
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
        )

        EssentialRole.EMAIL -> appCategory(context, Intent.CATEGORY_APP_EMAIL)

        EssentialRole.MAPS -> appCategory(context, Intent.CATEGORY_APP_MAPS)

        EssentialRole.CALENDAR -> appCategory(context, Intent.CATEGORY_APP_CALENDAR)

        EssentialRole.MUSIC -> appCategory(context, Intent.CATEGORY_APP_MUSIC)

        // Intent.CATEGORY_APP_FILES is API 29; the constant itself is just this string.
        EssentialRole.FILES -> appCategory(context, "android.intent.category.APP_FILES")
    }

    /** Every package filling any of [roles]. */
    fun kit(context: Context, roles: Set<EssentialRole>): Set<String> =
        roles.flatMapTo(mutableSetOf()) { forRole(context, it) }

    /**
     * A readable name for what fills a role, for the picker: "Messages", "Clock".
     * Null when nothing on this phone answers for it.
     */
    fun labelForRole(context: Context, role: EssentialRole): String? {
        val best = forRole(context, role).firstOrNull { packageName ->
            runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.isSuccess
        } ?: return null
        return label(context, best)
    }

    private fun appCategory(context: Context, category: String): Set<String> =
        resolveAll(context, Intent(Intent.ACTION_MAIN).addCategory(category))

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
