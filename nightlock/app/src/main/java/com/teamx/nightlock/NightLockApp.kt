package com.teamx.nightlock

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.teamx.nightlock.lock.LockForegroundService

class NightLockApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Picks the schedule back up after the process was killed and restarted.
        LockForegroundService.sync(this)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_STATUS,
            getString(R.string.channel_status_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_status_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_STATUS = "night_lock_status"
    }
}
