package com.teamx.nightlock.lock

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.teamx.nightlock.ui.LockActivity

/**
 * Notices which app came to the foreground and, while the phone is locked for the night,
 * sends everything except calling back to the lock screen.
 *
 * This is the only way to see foreground app changes without root or a device owner
 * setup, which is why the app asks for the accessibility permission. It reads no screen
 * content: the service config sets canRetrieveWindowContent="false", so all it ever
 * learns is the package name of the window that just opened.
 */
class AppGuardAccessibilityService : AccessibilityService() {

    private var lastInterventionAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        LockController.invalidateAccessPolicy()
        LockForegroundService.sync(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString()
        LockController.onForegroundPackage(packageName)

        val status = LockController.refresh(this)
        if (!status.shouldEnforceLockScreen) return
        if (LockController.accessPolicy(this).isAllowedWhileLocked(packageName)) return

        // A blocked app can bounce between windows; one lock screen is enough.
        val now = SystemClock.elapsedRealtime()
        if (now - lastInterventionAt < INTERVENTION_THROTTLE_MS) return
        lastInterventionAt = now

        LockActivity.launch(this)
    }

    override fun onInterrupt() = Unit

    private companion object {
        const val INTERVENTION_THROTTLE_MS = 400L
    }
}
