package com.dan.timelapse

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class RenderService : Service() {
    companion object {
        const val CHANNEL_ID = "RenderServiceChannel"
        const val NOTIFICATION_ID = 142
        const val ACTION_START = "ACTION_START_RENDER"
        const val ACTION_STOP = "ACTION_STOP_RENDER"

        var instance: RenderService? = null
    }

    private var notificationBuilder: NotificationCompat.Builder? = null
    private var notificationManager: NotificationManager? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        notificationManager = getSystemService(NotificationManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            createNotificationChannel()
            
            notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Rendering Timelapse")
                .setContentText("Initializing engine...")
                .setSmallIcon(R.drawable.ic_movie)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setProgress(100, 0, true)

            val notification = notificationBuilder!!.build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    fun updateProgress(progress: Int, total: Int, status: String) {
        notificationBuilder?.let { builder ->
            builder.setContentText(status)
            if (total > 0) {
                builder.setProgress(total, progress, false)
            } else {
                builder.setProgress(0, 0, true)
            }
            notificationManager?.notify(NOTIFICATION_ID, builder.build())
        }
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Render Progress",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager?.createNotificationChannel(serviceChannel)
        }
    }
}