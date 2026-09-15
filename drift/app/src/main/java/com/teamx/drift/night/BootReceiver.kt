package com.teamx.drift.night

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-arms the night after a reboot, an app update, or a change to the clock.
 *
 * Rebooting at 01:00 would otherwise be a way to spend the night awake.
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
                NightController.invalidateAccessPolicy()
                DriftService.sync(context)
            }
        }
    }
}
