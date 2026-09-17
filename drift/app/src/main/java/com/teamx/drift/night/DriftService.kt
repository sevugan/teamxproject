package com.teamx.drift.night

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.teamx.drift.DriftApp
import com.teamx.drift.R
import com.teamx.drift.core.HeartbeatMonitor
import com.teamx.drift.core.NightPhase
import com.teamx.drift.core.NightStatus
import com.teamx.drift.data.DriftSettings
import com.teamx.drift.ui.NightScreenActivity
import com.teamx.drift.ui.TonightActivity
import com.teamx.drift.util.Permissions
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Keeps tonight running: holds the schedule, re-checks the clock on a timer, says what
 * stage the night is at in a notification, and puts the night screen up when a stage
 * begins while the phone is in use.
 *
 * The timer is a safety net rather than the mechanism. Alarms do the precise work; the
 * tick catches a boundary that a doze-delayed alarm missed.
 */
class DriftService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastScreenLaunchAt = 0L
    private var lastEnforced: NightPhase = NightPhase.OPEN

    private val ticker = object : Runnable {
        override fun run() {
            syncNow()
            handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    /**
     * Unlocking the screen is the moment a closed app would come back into view, so
     * re-check there rather than waiting for the next tick.
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) = syncNow()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification(NightStatus.OFF))
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        handler.post(ticker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLOSE_HATCH) {
            NightController.closeEscapeHatch(this)
        }
        syncNow()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        runCatching { unregisterReceiver(screenReceiver) }
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun syncNow() {
        recordHeartbeat()
        val status = NightController.refresh(this)

        if (status.scheduleOff) {
            PhaseScheduler.cancel(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        PhaseScheduler.schedule(this)
        if (Permissions.hasNotificationPermission(this)) {
            runCatching {
                NotificationManagerCompat.from(this)
                    .notify(NOTIFICATION_ID, buildNotification(status))
            }
        }

        val steppedDown = status.enforced.isAtLeast(lastEnforced) && status.enforced != lastEnforced
        lastEnforced = status.enforced

        // A stage beginning is what puts the night screen up. After that the guard
        // re-asserts it, so a tick during a call at 02:00 leaves the call alone.
        if (status.restricts && (steppedDown || NightController.isForegroundAppBlocked(this))) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastScreenLaunchAt > SCREEN_LAUNCH_THROTTLE_MS) {
                lastScreenLaunchAt = now
                NightScreenActivity.show(this, blockedPackage = null)
            }
        }
    }

    /**
     * Leaves a timestamp, and notices if the last one is stale.
     *
     * A phone that force-stops the app gives no chance to record anything on the way
     * out, so the gap is only ever visible from the other side — the next time the
     * service runs. That is enough to tell the user their night did not happen.
     */
    private fun recordHeartbeat() {
        val settings = DriftSettings.getInstance(this)
        val now = Instant.now()

        val outage = HeartbeatMonitor.outageSince(settings.lastHeartbeat, now)
        // Only worth reporting when Drift was supposed to be doing something. Being
        // stopped while switched off is not a failure.
        if (outage != null && settings.enabled) {
            settings.lastOutage = outage
            settings.lastOutageSeen = false
        }

        settings.lastHeartbeat = now
    }

    private fun buildNotification(status: NightStatus): Notification {
        val builder = NotificationCompat.Builder(this, DriftApp.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_drift)
            .setContentTitle(getString(R.string.app_name))
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, TonightActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )

        val changesAt = status.changesAt?.format(TIME_FORMAT)

        // Nothing below is true if the app cannot act. Say that first.
        if (!Permissions.isOperational(this)) {
            return builder
                .setContentText(getString(R.string.notification_not_running))
                .build()
        }

        when {
            status.isBorrowingTime -> {
                val minutesLeft = Duration.between(Instant.now(), status.bypassUntil)
                    .toMinutes()
                    .coerceAtLeast(0)
                builder.setContentText(getString(R.string.notification_borrowed, minutesLeft))
                builder.addAction(
                    0,
                    getString(R.string.action_end_early),
                    PendingIntent.getService(
                        this,
                        1,
                        Intent(this, DriftService::class.java).setAction(ACTION_CLOSE_HATCH),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }

            status.phase == NightPhase.WIND_DOWN ->
                builder.setContentText(getString(R.string.notification_wind_down, changesAt))

            status.phase == NightPhase.QUIET ->
                builder.setContentText(getString(R.string.notification_quiet, changesAt))

            status.phase == NightPhase.SLEEP -> {
                builder.setContentText(getString(R.string.notification_sleep, changesAt))
                // If the night screen cannot be started from the background, this is
                // still a way back to it.
                builder.setFullScreenIntent(nightScreenIntent(), true)
            }

            else -> builder.setContentText(getString(R.string.notification_open, changesAt))
        }

        return builder.build()
    }

    private fun nightScreenIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        2,
        Intent(this, NightScreenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val TICK_INTERVAL_MS = 30_000L
        private const val SCREEN_LAUNCH_THROTTLE_MS = 1_000L
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        const val ACTION_CLOSE_HATCH = "com.teamx.drift.action.CLOSE_HATCH"

        /**
         * Brings the service up to date. Safe from a receiver, a screen or the guard; if
         * the system refuses a background start, the caller still gets a correct status
         * from [NightController.refresh].
         */
        fun sync(context: Context) {
            NightController.refresh(context)
            val intent = Intent(context.applicationContext, DriftService::class.java)
            runCatching {
                ContextCompat.startForegroundService(context.applicationContext, intent)
            }.onFailure {
                PhaseScheduler.schedule(context)
                if (NightController.status.value.restricts) {
                    NightScreenActivity.show(context, blockedPackage = null)
                }
            }
        }
    }
}
