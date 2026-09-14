package com.teamx.nightlock.lock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.teamx.nightlock.core.LockEvaluator
import com.teamx.nightlock.data.LockSettings
import com.teamx.nightlock.util.Permissions
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Wakes the app at 23:00 and at 06:00 (and earlier if an emergency grant expires first).
 *
 * Only ever one alarm is outstanding: each firing reschedules the next boundary, so a
 * changed curfew or a used emergency unlock takes effect immediately.
 */
object CurfewScheduler {

    private const val REQUEST_CODE = 4711

    fun schedule(context: Context) {
        val settings = LockSettings.getInstance(context)
        val nextWakeUp = LockEvaluator.nextWakeUp(
            config = settings.config,
            state = settings.emergencyState,
            now = LocalDateTime.now(),
            nowInstant = Instant.now(),
        )
        if (nextWakeUp == null) {
            cancel(context)
            return
        }

        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAtMillis = nextWakeUp.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pendingIntent = alarmIntent(context)

        if (Permissions.canScheduleExactAlarms(context)) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent,
            )
        } else {
            // Without the exact alarm permission the boundary can slip by a few minutes.
            // The service also re-checks on a timer, so the lock still lands.
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
        val intent = Intent(context, CurfewAlarmReceiver::class.java)
            .setAction(CurfewAlarmReceiver.ACTION_CURFEW_BOUNDARY)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }
}
