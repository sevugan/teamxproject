package com.teamx.drift

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.teamx.drift.night.DriftService

class DriftApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Picks tonight back up after the process was killed and restarted.
        DriftService.sync(this)
    }

    private fun createNotificationChannel() {
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
        const val CHANNEL_STATUS = "drift_status"
    }
}
