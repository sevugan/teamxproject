package com.teamx.drift.night

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Fires at each step of the ramp, and when borrowed time runs out. */
class PhaseAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        DriftService.sync(context)
    }

    companion object {
        const val ACTION_PHASE_BOUNDARY = "com.teamx.drift.action.PHASE_BOUNDARY"
    }
}
