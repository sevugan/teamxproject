package com.teamx.nightlock.lock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-arms the curfew after a reboot, an app update, or a change to the clock.
 *
 * Rebooting at 01:00 would otherwise be a way to spend the night unlocked.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> {
                LockController.invalidateAccessPolicy()
                LockForegroundService.sync(context)
            }
        }
    }
}
