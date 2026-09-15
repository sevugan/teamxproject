package com.teamx.drift.util

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * How long each app has been on screen since the day began.
 *
 * Read from the system's own usage records rather than counted here, so the totals
 * survive Drift being killed, and so they agree with what Digital Wellbeing reports.
 */
object UsageTracker {

    /** Granted by hand in Settings; there is no runtime prompt for this one. */
    fun hasPermission(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            }
        }.getOrDefault(AppOpsManager.MODE_ERRORED)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Foreground time per package between [since] and now.
     *
     * Built from resume/pause events rather than the daily buckets, because Drift's day
     * starts when you wake up and the system's buckets start at midnight.
     */
    fun usageSince(context: Context, since: LocalDateTime): Map<String, Duration> {
        if (!hasPermission(context)) return emptyMap()
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return emptyMap()

        val startMillis = since.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endMillis = System.currentTimeMillis()
        if (endMillis <= startMillis) return emptyMap()

        val totals = HashMap<String, Long>()
        val openedAt = HashMap<String, Long>()

        runCatching {
            val events = manager.queryEvents(startMillis, endMillis)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val packageName = event.packageName ?: continue
                when (event.eventType) {
                    @Suppress("DEPRECATION")
                    UsageEvents.Event.MOVE_TO_FOREGROUND ->
                        openedAt[packageName] = event.timeStamp

                    @Suppress("DEPRECATION")
                    UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                        val opened = openedAt.remove(packageName)
                        if (opened != null && event.timeStamp > opened) {
                            totals[packageName] = (totals[packageName] ?: 0L) +
                                (event.timeStamp - opened)
                        }
                    }
                }
            }
        }.onFailure { return emptyMap() }

        // Whatever is still open has been open until now.
        openedAt.forEach { (packageName, opened) ->
            if (endMillis > opened) {
                totals[packageName] = (totals[packageName] ?: 0L) + (endMillis - opened)
            }
        }

        return totals.mapValues { Duration.ofMillis(it.value) }
    }

    fun usageOf(context: Context, packageName: String, since: LocalDateTime): Duration =
        usageSince(context, since)[packageName] ?: Duration.ZERO
}
