package com.teamx.drift.night

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.teamx.drift.ui.NightScreenActivity

/**
 * Notices which app came to the foreground and, when tonight's stage does not allow it,
 * shows the night screen instead.
 *
 * This is the only way to see foreground app changes without root or a device owner
 * setup, which is why Drift asks for the accessibility permission. It reads no screen
 * content: the service config sets canRetrieveWindowContent="false", so all it ever
 * learns is the package name of the window that just opened.
 */
class AppGuardAccessibilityService : AccessibilityService() {

    private var lastInterventionAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        NightController.invalidateAccessPolicy()
        DriftService.sync(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString()
        NightController.onForegroundPackage(packageName)

        val status = NightController.refresh(this)
        if (!status.restricts) return
        if (NightController.accessPolicy(this).isAllowed(packageName, status.enforced)) return

        // A blocked app can bounce between windows; one night screen is enough.
        val now = SystemClock.elapsedRealtime()
        if (now - lastInterventionAt < INTERVENTION_THROTTLE_MS) return
        lastInterventionAt = now

        NightScreenActivity.show(this, blockedPackage = packageName)
    }

    override fun onInterrupt() = Unit

    private companion object {
        const val INTERVENTION_THROTTLE_MS = 400L
    }
}
