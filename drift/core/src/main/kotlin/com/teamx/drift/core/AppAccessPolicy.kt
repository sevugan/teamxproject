package com.teamx.drift.core

/**
 * Decides which app may be in the foreground during each stage of the night.
 *
 * The ramp, in one place:
 *
 * | Phase       | What is reachable                                      |
 * |-------------|--------------------------------------------------------|
 * | `OPEN`      | everything                                             |
 * | `WIND_DOWN` | everything except the apps you named as distracting    |
 * | `QUIET`     | your essentials, plus calling                          |
 * | `SLEEP`     | calling, alarms and the clock                          |
 *
 * Calling is never closed, in any phase. A phone that cannot be used to call for help
 * at 03:00 is a worse problem than any amount of scrolling.
 */
data class AppAccessPolicy(
    /** This app. Blocking ourselves would fight our own screens. */
    val selfPackage: String,
    /** Dialer, telecom and in-call UI packages, resolved on the device at runtime. */
    val callPackages: Set<String>,
    /** Clock and alarm apps, so the phone still works as a bedside clock. */
    val clockPackages: Set<String> = emptySet(),
    /**
     * Home screens. Reachable until SLEEP, so a quiet phone is still a navigable one;
     * closed during SLEEP, so the night screen stays up instead of a grid of icons.
     */
    val launcherPackages: Set<String> = emptySet(),
    /** The apps the user is trying to get away from: closed from WIND_DOWN onwards. */
    val distractingPackages: Set<String> = emptySet(),
    /** The apps the user wants kept through QUIET: messaging, maps, notes. */
    val essentialPackages: Set<String> = emptySet(),
    /** System surfaces that must keep working: status bar, dialogs, permission prompts. */
    val systemPackages: Set<String> = DEFAULT_SYSTEM_PACKAGES,
    /** Enabled input methods, so typing is never what trips the guard. */
    val inputMethodPackages: Set<String> = emptySet(),
    /** Settings apps, closed only from [blockSettingsFrom] onwards, and only if set. */
    val settingsPackages: Set<String> = DEFAULT_SETTINGS_PACKAGES,
    /**
     * Null by default, meaning Settings always stays reachable. Closing it makes the
     * night harder to walk around, and also harder to turn off; the escape hatch works
     * either way.
     */
    val blockSettingsFrom: NightPhase? = null,
) {
    fun isAllowed(packageName: String?, phase: NightPhase): Boolean {
        // An unknown package means the event told us nothing useful. Never put a screen
        // in someone's way on a guess.
        if (packageName.isNullOrBlank()) return true
        if (phase == NightPhase.OPEN) return true

        // Always reachable, at every stage of the night.
        if (packageName == selfPackage) return true
        if (packageName in callPackages) return true
        if (packageName in systemPackages) return true
        if (packageName in inputMethodPackages) return true

        if (packageName in settingsPackages) {
            val closedFrom = blockSettingsFrom ?: return true
            return !phase.isAtLeast(closedFrom)
        }

        // The home screen stays reachable while the phone is merely quiet, so putting an
        // app away still leaves somewhere to put it away to.
        if (packageName in launcherPackages) return phase != NightPhase.SLEEP

        return when (phase) {
            NightPhase.OPEN -> true
            // Only what you asked to be protected from steps aside.
            NightPhase.WIND_DOWN -> packageName !in distractingPackages
            // Essentials survive, and being distracting overrides being essential.
            NightPhase.QUIET ->
                packageName in essentialPackages && packageName !in distractingPackages
            // The bedside clock, and nothing else that is not a call.
            NightPhase.SLEEP -> packageName in clockPackages
        }
    }

    fun isBlocked(packageName: String?, phase: NightPhase): Boolean =
        !isAllowed(packageName, phase)

    companion object {
        val DEFAULT_SYSTEM_PACKAGES: Set<String> = setOf(
            "android",
            "com.android.systemui",
            "com.android.emergency",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.incallui",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
        )

        val DEFAULT_SETTINGS_PACKAGES: Set<String> = setOf(
            "com.android.settings",
            "com.samsung.android.settings",
            "com.miui.securitycenter",
        )

        /**
         * A reasonable opening guess at "apps you probably do not want at 23:30", offered
         * on the setup screen for the user to accept or edit. Only ones actually installed
         * are ever used.
         */
        val SUGGESTED_DISTRACTING: Set<String> = setOf(
            "com.instagram.android",
            "com.google.android.youtube",
            "com.zhiliaoapp.musically", // TikTok
            "com.reddit.frontpage",
            "com.twitter.android",
            "com.x.android",
            "com.facebook.katana",
            "com.snapchat.android",
            "com.netflix.mediaclient",
            "com.linkedin.android",
            "com.pinterest",
            "com.android.chrome",
        )
    }
}
