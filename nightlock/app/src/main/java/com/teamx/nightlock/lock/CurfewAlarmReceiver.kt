package com.teamx.nightlock.lock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Fires at 23:00, at 06:00, and whenever an emergency grant runs out. */
class CurfewAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        LockForegroundService.sync(context)
    }

    companion object {
        const val ACTION_CURFEW_BOUNDARY = "com.teamx.nightlock.action.CURFEW_BOUNDARY"
    }
}
