package com.teamx.nightlock.lock

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
import com.teamx.nightlock.NightLockApp
import com.teamx.nightlock.R
import com.teamx.nightlock.core.LockStatus
import com.teamx.nightlock.ui.LockActivity
import com.teamx.nightlock.ui.MainActivity
import com.teamx.nightlock.util.Permissions
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Keeps the curfew running: holds the schedule, re-checks the clock on a timer, shows
 * what the lock is doing in a notification, and brings up the lock screen when 23:00
 * arrives while the phone is in use.
 *
 * The timer is a safety net rather than the mechanism. Alarms do the precise work; the
 * tick catches a boundary that a doze-delayed alarm missed.
 */
class LockForegroundService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastLockLaunchAt = 0L
    private var wasEnforcing = false

    private val ticker = object : Runnable {
        override fun run() {
            syncNow()
            handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    /**
     * Unlocking the screen is the moment a blocked app would come back into view, so
     * re-check there rather than waiting for the next tick.
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) = syncNow()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification(LockStatus.Off))
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
        if (intent?.action == ACTION_END_BYPASS) {
            LockController.endBypass(this)
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
        val status = LockController.refresh(this)

        if (status is LockStatus.Off) {
            CurfewScheduler.cancel(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        CurfewScheduler.schedule(this)
        if (Permissions.hasNotificationPermission(this)) {
            runCatching {
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(status))
            }
        }

        val enforcing = status.shouldEnforceLockScreen
        val curfewJustStarted = enforcing && !wasEnforcing
        wasEnforcing = enforcing

        // The curfew beginning is what puts the lock screen up. After that the
        // accessibility guard re-asserts it, so a tick during a call at 02:00 leaves the
        // in-call screen alone.
        if (enforcing && (curfewJustStarted || LockController.isForegroundAppBlocked(this))) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastLockLaunchAt > LOCK_LAUNCH_THROTTLE_MS) {
                lastLockLaunchAt = now
                LockActivity.launch(this)
            }
        }
    }

    private fun buildNotification(status: LockStatus): Notification {
        val builder = NotificationCompat.Builder(this, NightLockApp.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_night_lock)
            .setContentTitle(getString(R.string.app_name))
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )

        when (status) {
            is LockStatus.Locked -> {
                builder.setContentText(
                    getString(R.string.notification_locked, status.lockedUntil.format(TIME_FORMAT)),
                )
                // If the lock screen cannot be started from the background, the user can
                // still reach it from here.
                builder.setFullScreenIntent(lockScreenIntent(), true)
            }

            is LockStatus.Bypassed -> {
                val minutesLeft = Duration.between(Instant.now(), status.bypassUntil)
                    .toMinutes()
                    .coerceAtLeast(0)
                builder.setContentText(
                    getString(R.string.notification_bypassed, minutesLeft),
                )
                builder.addAction(
                    0,
                    getString(R.string.action_relock_now),
                    PendingIntent.getService(
                        this,
                        1,
                        Intent(this, LockForegroundService::class.java).setAction(ACTION_END_BYPASS),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }

            is LockStatus.Unlocked -> builder.setContentText(
                getString(R.string.notification_unlocked, status.nextLockAt.format(TIME_FORMAT)),
            )

            LockStatus.Off -> builder.setContentText(getString(R.string.notification_off))
        }

        return builder.build()
    }

    private fun lockScreenIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        2,
        Intent(this, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val TICK_INTERVAL_MS = 30_000L
        private const val LOCK_LAUNCH_THROTTLE_MS = 1_000L
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        const val ACTION_END_BYPASS = "com.teamx.nightlock.action.END_BYPASS"

        /**
         * Brings the service up to date. Safe to call from a receiver, a screen or the
         * accessibility guard; if the system refuses a background start, the caller
         * still gets a correct status from [LockController.refresh].
         */
        fun sync(context: Context) {
            LockController.refresh(context)
            val intent = Intent(context.applicationContext, LockForegroundService::class.java)
            runCatching {
                ContextCompat.startForegroundService(context.applicationContext, intent)
            }.onFailure {
                CurfewScheduler.schedule(context)
                if (LockController.status.value.shouldEnforceLockScreen) {
                    LockActivity.launch(context)
                }
            }
        }
    }
}
