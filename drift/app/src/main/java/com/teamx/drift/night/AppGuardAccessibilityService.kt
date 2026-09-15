package com.teamx.drift.night

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.teamx.drift.core.LimitVerdict
import com.teamx.drift.ui.NightScreenActivity
import com.teamx.drift.ui.TimeUpActivity

/**
 * Notices which app came to the foreground and acts on the two reasons Drift closes
 * something: the stage of the night, and the app's time for the day.
 *
 * This is the only way to see foreground app changes without root or a device owner
 * setup, which is why Drift asks for the accessibility permission. It reads no screen
 * content: the service config sets canRetrieveWindowContent="false", so all it ever
 * learns is the package name of the window that just opened.
 */
class AppGuardAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastInterventionAt = 0L
    private var watchedPackage: String? = null

    /** Fires at the moment the foreground app's limit is due to run out. */
    private val limitDue = Runnable { watchedPackage?.let { checkLimit(it) } }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        NightController.invalidateAccessPolicy()
        DriftService.sync(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        handler.removeCallbacksAndMessages(null)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString()
        NightController.onForegroundPackage(packageName)

        handler.removeCallbacks(limitDue)
        watchedPackage = packageName

        if (enforceNight(packageName)) return
        if (packageName != null) checkLimit(packageName)
    }

    /** Returns true when the night closed this app, so the limit need not be consulted. */
    private fun enforceNight(packageName: String?): Boolean {
        val status = NightController.refresh(this)
        if (!status.restricts) return false
        if (NightController.accessPolicy(this).isAllowed(packageName, status.enforced)) return false
        if (!throttle()) return true

        NightScreenActivity.show(this, blockedPackage = packageName)
        return true
    }

    /**
     * Closes the app when its day is spent, and otherwise schedules a wake-up for the
     * exact moment it will be.
     *
     * Scheduling beats polling here: the limit lands on the second rather than at the
     * next tick, and nothing runs at all while an unlimited app is open.
     */
    private fun checkLimit(packageName: String) {
        when (val verdict = NightController.limitVerdict(this, packageName)) {
            is LimitVerdict.Untimed -> Unit

            is LimitVerdict.Within -> {
                val delay = verdict.remaining.toMillis().coerceAtLeast(MIN_RECHECK_MS)
                handler.postDelayed(limitDue, delay)
            }

            is LimitVerdict.Spent -> {
                if (NightController.isOutOfTime(this, packageName) && throttle()) {
                    closeApp()
                    TimeUpActivity.show(this, packageName)
                }
            }
        }
    }

    /** Sends the foreground app away. The nearest thing to closing it without root. */
    private fun closeApp() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /** A blocked app can bounce between windows; one intervention is enough. */
    private fun throttle(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastInterventionAt < INTERVENTION_THROTTLE_MS) return false
        lastInterventionAt = now
        return true
    }

    override fun onInterrupt() = Unit

    companion object {
        private const val INTERVENTION_THROTTLE_MS = 400L

        /** Never let a rounding error turn into a busy loop. */
        private const val MIN_RECHECK_MS = 1_000L

        /**
         * The live service, when one is connected. Only an accessibility service can send
         * the foreground app home, so the rest of the app reaches it through here.
         */
        @Volatile
        var instance: AppGuardAccessibilityService? = null
            private set

        /** True when the guard is running and can act. */
        val isRunning: Boolean get() = instance != null

        /** Sends whatever is on screen home, if the guard is connected. */
        fun sendHome(): Boolean {
            val service = instance ?: return false
            service.closeApp()
            return true
        }
    }
}
