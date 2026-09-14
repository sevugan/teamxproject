package com.teamx.nightlock.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppAccessPolicyTest {

    private val policy = AppAccessPolicy(
        selfPackage = "com.teamx.nightlock",
        callPackages = setOf("com.google.android.dialer", "com.android.server.telecom"),
        inputMethodPackages = setOf("com.google.android.inputmethod.latin"),
    )

    @Test
    fun `calling stays available while locked`() {
        assertTrue(policy.isAllowedWhileLocked("com.google.android.dialer"))
        assertTrue(policy.isAllowedWhileLocked("com.android.server.telecom"))
        assertTrue(policy.isAllowedWhileLocked("com.android.incallui"))
        assertTrue(policy.isAllowedWhileLocked("com.android.emergency"))
    }

    @Test
    fun `everything else is blocked while locked`() {
        assertTrue(policy.isBlockedWhileLocked("com.instagram.android"))
        assertTrue(policy.isBlockedWhileLocked("com.google.android.youtube"))
        assertTrue(policy.isBlockedWhileLocked("com.android.chrome"))
        assertTrue(policy.isBlockedWhileLocked("com.google.android.apps.nexuslauncher"))
    }

    @Test
    fun `the lock screen and the system ui are never blocked`() {
        assertTrue(policy.isAllowedWhileLocked("com.teamx.nightlock"))
        assertTrue(policy.isAllowedWhileLocked("com.android.systemui"))
        assertTrue(policy.isAllowedWhileLocked("android"))
    }

    @Test
    fun `the keyboard is allowed so the emergency reason can be typed`() {
        assertTrue(policy.isAllowedWhileLocked("com.google.android.inputmethod.latin"))
    }

    @Test
    fun `an unknown package is never treated as blocked`() {
        assertTrue(policy.isAllowedWhileLocked(null))
        assertTrue(policy.isAllowedWhileLocked(""))
        assertTrue(policy.isAllowedWhileLocked("   "))
    }

    @Test
    fun `settings is reachable by default and blocked when the user tightens the lock`() {
        assertTrue(policy.isAllowedWhileLocked("com.android.settings"))
        assertTrue(policy.copy(blockSettings = true).isBlockedWhileLocked("com.android.settings"))
    }

    @Test
    fun `a user allowlisted app is reachable`() {
        val withAlarmApp = policy.copy(userAllowed = setOf("com.example.insulin"))

        assertTrue(withAlarmApp.isAllowedWhileLocked("com.example.insulin"))
        assertFalse(withAlarmApp.isAllowedWhileLocked("com.example.other"))
    }
}
