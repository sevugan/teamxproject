package com.teamx.drift.night

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.teamx.drift.core.NightEvaluator
import com.teamx.drift.data.DriftSettings
import com.teamx.drift.util.Permissions
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Wakes the app at each step of the ramp, and earlier if borrowed time runs out first.
 *
 * Only ever one alarm is outstanding: each firing schedules the next boundary, so a
 * changed bedtime or a used escape hatch takes effect immediately.
 */
object PhaseScheduler {

    private const val REQUEST_CODE = 4711

    fun schedule(context: Context) {
        val settings = DriftSettings.getInstance(context)
        val next = NightEvaluator.nextWakeUp(
            config = settings.config,
            state = settings.escapeHatchState,
            now = LocalDateTime.now(),
            nowInstant = Instant.now(),
        )
        if (next == null) {
            cancel(context)
            return
        }

        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAtMillis = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pendingIntent = alarmIntent(context)

        if (Permissions.canScheduleExactAlarms(context)) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent,
            )
        } else {
            // Without the exact alarm permission a boundary can slip by a few minutes.
            // The service also re-checks on a timer, so the phase still lands.
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent,
            )
        }
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(context))
    }

    private fun alarmIntent(context: Context): PendingIntent {
        val intent = Intent(context.applicationContext, PhaseAlarmReceiver::class.java)
            .setAction(PhaseAlarmReceiver.ACTION_PHASE_BOUNDARY)
        return PendingIntent.getBroadcast(
            context.applicationContext,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
