package com.teamx.nightlock.core

/**
 * Decides which app may be in the foreground while the phone is locked for the night.
 *
 * The rule the app is built around: while locked, only calling is available. Everything
 * that is part of placing or receiving a call stays reachable; everything else is sent
 * back to the lock screen.
 */
data class AppAccessPolicy(
    /** This app. Blocking ourselves would fight the lock screen. */
    val selfPackage: String,
    /** Dialer, telecom and in-call UI packages, resolved on the device at runtime. */
    val callPackages: Set<String>,
    /** System surfaces that must keep working: status bar, dialogs, keyboards. */
    val systemPackages: Set<String> = DEFAULT_SYSTEM_PACKAGES,
    /** Enabled input methods, so typing an emergency reason does not trip the guard. */
    val inputMethodPackages: Set<String> = emptySet(),
    /** Settings apps, blocked only when [blockSettings] is on. */
    val settingsPackages: Set<String> = DEFAULT_SETTINGS_PACKAGES,
    /**
     * Off by default. Blocking Settings makes the lock harder to walk around, and also
     * harder to turn off; the emergency unlock works either way.
     */
    val blockSettings: Boolean = false,
    /** Anything the user explicitly allowed, e.g. a medical or alarm app. */
    val userAllowed: Set<String> = emptySet(),
) {
    fun isAllowedWhileLocked(packageName: String?): Boolean {
        // An unknown package means the accessibility event told us nothing useful.
        // Never throw the lock screen at the user on a guess.
        if (packageName.isNullOrBlank()) return true
        if (packageName == selfPackage) return true
        if (packageName in callPackages) return true
        if (packageName in inputMethodPackages) return true
        if (packageName in userAllowed) return true
        if (packageName in settingsPackages) return !blockSettings
        return packageName in systemPackages
    }

    /** The inverse, for readability at call sites in the accessibility service. */
    fun isBlockedWhileLocked(packageName: String?): Boolean = !isAllowedWhileLocked(packageName)

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
    }
}
