package com.teamx.nightlock.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager

/**
 * Works out, on this particular device, which packages count as "calling" and therefore
 * stay reachable while the phone is locked for the night.
 *
 * Resolved at runtime rather than hard coded: the dialer differs between manufacturers,
 * and getting this wrong would mean a phone that cannot make a call.
 */
object CallPackages {

    fun resolve(context: Context): Set<String> {
        val packages = mutableSetOf<String>()

        runCatching {
            context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        }.getOrNull()?.let(packages::add)

        val packageManager = context.packageManager
        val dialIntents = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:")),
            Intent(Intent.ACTION_VIEW, Uri.parse("tel:")),
        )
        dialIntents.forEach { intent ->
            runCatching { packageManager.queryIntentActivities(intent, 0) }
                .getOrDefault(emptyList())
                .mapTo(packages) { it.activityInfo.packageName }
        }

        // Telecom, the in-call UI and the emergency dialer are part of a call even when
        // the dialer app itself is not in the foreground.
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

    /** Keyboards, so that typing a reason into the emergency dialog is not blocked. */
    fun inputMethods(context: Context): Set<String> = runCatching {
        context.getSystemService(InputMethodManager::class.java)
            ?.enabledInputMethodList
            ?.map { it.packageName }
            ?.toSet()
            .orEmpty()
    }.getOrDefault(emptySet())
}
