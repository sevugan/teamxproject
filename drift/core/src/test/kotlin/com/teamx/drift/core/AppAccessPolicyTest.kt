package com.teamx.drift.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppAccessPolicyTest {

    private val policy = AppAccessPolicy(
        selfPackage = "com.teamx.drift",
        callPackages = setOf("com.google.android.dialer", "com.android.server.telecom"),
        clockPackages = setOf("com.google.android.deskclock"),
        distractingPackages = setOf("com.instagram.android", "com.google.android.youtube"),
        essentialPackages = setOf("com.whatsapp", "com.google.android.apps.maps"),
        launcherPackages = setOf("com.google.android.apps.nexuslauncher"),
        inputMethodPackages = setOf("com.google.android.inputmethod.latin"),
    )

    @Test
    fun `the home screen stays reachable until the phone is asleep`() {
        val home = "com.google.android.apps.nexuslauncher"

        assertTrue(policy.isAllowed(home, NightPhase.OPEN))
        assertTrue(policy.isAllowed(home, NightPhase.WIND_DOWN))
        // Putting an app away has to leave somewhere to put it away to.
        assertTrue(policy.isAllowed(home, NightPhase.QUIET))
        // Asleep, the night screen stays up rather than a grid of icons.
        assertTrue(policy.isBlocked(home, NightPhase.SLEEP))
    }

    @Test
    fun `nothing is held back during the day`() {
        assertTrue(policy.isAllowed("com.instagram.android", NightPhase.OPEN))
        assertTrue(policy.isAllowed("com.google.android.youtube", NightPhase.OPEN))
        assertTrue(policy.isAllowed("com.any.app.at.all", NightPhase.OPEN))
    }

    @Test
    fun `the wind-down closes only what the user named`() {
        assertTrue(policy.isBlocked("com.instagram.android", NightPhase.WIND_DOWN))
        assertTrue(policy.isBlocked("com.google.android.youtube", NightPhase.WIND_DOWN))
        // Everything else is still there: the wind-down is a nudge, not a wall.
        assertTrue(policy.isAllowed("com.whatsapp", NightPhase.WIND_DOWN))
        assertTrue(policy.isAllowed("com.some.banking.app", NightPhase.WIND_DOWN))
        assertTrue(policy.isAllowed("com.google.android.apps.nexuslauncher", NightPhase.WIND_DOWN))
        assertTrue(policy.isAllowed("com.spotify.music", NightPhase.WIND_DOWN))
    }

    @Test
    fun `quiet keeps the essentials and closes the rest`() {
        assertTrue(policy.isAllowed("com.whatsapp", NightPhase.QUIET))
        assertTrue(policy.isAllowed("com.google.android.apps.maps", NightPhase.QUIET))
        assertTrue(policy.isBlocked("com.spotify.music", NightPhase.QUIET))
        assertTrue(policy.isBlocked("com.instagram.android", NightPhase.QUIET))
    }

    @Test
    fun `an app that is both distracting and essential stays closed`() {
        val conflicted = policy.copy(
            distractingPackages = setOf("com.whatsapp"),
            essentialPackages = setOf("com.whatsapp"),
        )

        assertTrue(conflicted.isBlocked("com.whatsapp", NightPhase.QUIET))
    }

    @Test
    fun `sleep leaves the bedside clock and nothing else`() {
        assertTrue(policy.isAllowed("com.google.android.deskclock", NightPhase.SLEEP))
        assertTrue(policy.isBlocked("com.whatsapp", NightPhase.SLEEP))
        assertTrue(policy.isBlocked("com.google.android.apps.maps", NightPhase.SLEEP))
        assertTrue(policy.isBlocked("com.instagram.android", NightPhase.SLEEP))
    }

    @Test
    fun `calling survives every stage of the night`() {
        NightPhase.entries.forEach { phase ->
            assertTrue(policy.isAllowed("com.google.android.dialer", phase), "dialer in $phase")
            assertTrue(policy.isAllowed("com.android.server.telecom", phase), "telecom in $phase")
            assertTrue(policy.isAllowed("com.android.incallui", phase), "in-call UI in $phase")
            assertTrue(policy.isAllowed("com.android.emergency", phase), "emergency in $phase")
        }
    }

    @Test
    fun `our own screens and the system ui survive every stage`() {
        NightPhase.entries.forEach { phase ->
            assertTrue(policy.isAllowed("com.teamx.drift", phase), "drift in $phase")
            assertTrue(policy.isAllowed("com.android.systemui", phase), "system ui in $phase")
            assertTrue(policy.isAllowed("android", phase), "android in $phase")
            assertTrue(
                policy.isAllowed("com.google.android.inputmethod.latin", phase),
                "keyboard in $phase",
            )
        }
    }

    @Test
    fun `an unknown package is never treated as blocked`() {
        NightPhase.entries.forEach { phase ->
            assertTrue(policy.isAllowed(null, phase))
            assertTrue(policy.isAllowed("", phase))
            assertTrue(policy.isAllowed("   ", phase))
        }
    }

    @Test
    fun `settings stays reachable unless the user closes it`() {
        NightPhase.entries.forEach { phase ->
            assertTrue(policy.isAllowed("com.android.settings", phase), "settings in $phase")
        }
    }

    @Test
    fun `closing settings takes effect from the chosen phase onwards`() {
        val tightened = policy.copy(blockSettingsFrom = NightPhase.QUIET)

        assertTrue(tightened.isAllowed("com.android.settings", NightPhase.OPEN))
        assertTrue(tightened.isAllowed("com.android.settings", NightPhase.WIND_DOWN))
        assertTrue(tightened.isBlocked("com.android.settings", NightPhase.QUIET))
        assertTrue(tightened.isBlocked("com.android.settings", NightPhase.SLEEP))
    }

    @Test
    fun `an app in no list at all is untouched until quiet`() {
        assertTrue(policy.isAllowed("com.example.notes", NightPhase.WIND_DOWN))
        assertFalse(policy.isAllowed("com.example.notes", NightPhase.QUIET))
        assertFalse(policy.isAllowed("com.example.notes", NightPhase.SLEEP))
    }
}
