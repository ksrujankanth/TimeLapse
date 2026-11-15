package com.dan.timelapse

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.*

class MFSRService : LifecycleService() {
    companion object {
        const val CHANNEL_ID = "MFSRServiceChannel"
        const val NOTIFICATION_ID = 143
        const val ACTION_START = "ACTION_START_MFSR"
        const val ACTION_STOP = "ACTION_STOP_MFSR"
        const val ACTION_CANCEL = "ACTION_CANCEL_RENDER"
        
        const val EXTRA_INPUT_URI = "input_uri"
        const val EXTRA_OUTPUT_PATH = "output_path"
        const val EXTRA_SCALE = "scale"
        const val EXTRA_FRAME_INDEX = "frame_index"
        const val EXTRA_USE_ORB_FALLBACK = "use_orb_fallback"
        
        val progressLiveData = MutableLiveData<Int>()
        val statusLiveData = MutableLiveData<String>()
        val resultLiveData = MutableLiveData<Boolean?>()
        val etaLiveData = MutableLiveData<Long>() // Remaining time in milliseconds
    }
    
    private val binder = LocalBinder()
    private var wakeLock: PowerManager.WakeLock? = null
    private var serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var currentOutputPath: String? = null
    private var isCancelled = false
    
    inner class LocalBinder : Binder() {
        fun getService(): MFSRService = this@MFSRService
    }
    
    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerLocalReceiver()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        when (intent?.action) {
            ACTION_START -> {
                val inputUri = intent.getStringExtra(EXTRA_INPUT_URI) ?: return START_NOT_STICKY
                val outputPath = intent.getStringExtra(EXTRA_OUTPUT_PATH) ?: return START_NOT_STICKY
                val scale = intent.getFloatExtra(EXTRA_SCALE, 2.0f)
                val frameIndex = intent.getIntExtra(EXTRA_FRAME_INDEX, 0)
                val useOrbFallback = intent.getBooleanExtra(EXTRA_USE_ORB_FALLBACK, false)
                
                currentOutputPath = outputPath
                isCancelled = false
                
                startForegroundService()
                acquireWakeLock()
                runMFSRProcessing(inputUri, outputPath, scale, frameIndex, useOrbFallback)
            }
            ACTION_CANCEL -> {
                handleCancellation()
            }
            ACTION_STOP -> {
                handleCancellation()
            }
        }
        return START_NOT_STICKY
    }
    
    private fun handleCancellation() {
        isCancelled = true
        serviceScope.cancel()
        
        // Delete partial output file to prevent storage bloat
        currentOutputPath?.let { path ->
            try {
                val file = java.io.File(path)
                if (file.exists()) {
                    file.delete()
                    android.util.Log.d("MFSRService", "Deleted partial file: $path")
                } else {
                    // File doesn't exist, nothing to delete
                }
            } catch (e: Exception) {
                android.util.Log.e("MFSRService", "Failed to delete partial file", e)
            }
        }
        
        resultLiveData.postValue(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    
    private fun startForegroundService() {
        val notification = createNotification(0, "Initializing MFSR...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MFSR Processing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Multi-Frame Super Resolution processing"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(progress: Int, status: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        
        // Cancel action using ACTION_CANCEL
        val cancelIntent = Intent(this, MFSRService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPendingIntent = PendingIntent.getService(this, 1, cancelIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Rendering Timelapse")
            .setContentText("$status ${if (progress > 0) "$progress%" else ""}")
            .setSmallIcon(R.drawable.ic_movie)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setProgress(100, progress, false)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPendingIntent)
            .build()
    }
    
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TimeLapse::MFSRWakeLock").apply {
            acquire(30 * 60 * 1000L) // 30 minutes max
        }
    }
    
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }
    
    private fun runMFSRProcessing(inputUri: String, outputPath: String, scale: Float, frameIndex: Int, useOrbFallback: Boolean) {
        serviceScope.launch {
            try {
                updateNotification(5, "Loading frames...")
                
                // The actual MFSR processing will be handled by VideoProcessor
                // which is invoked via MainViewModel - we just monitor progress
                
                // Simulate processing progress based on lifecycle
                for (i in 0..100 step 10) {
                    delay(500)
                    updateProgress(i)
                }
                
                // Complete
                resultLiveData.postValue(true)
                updateNotification(100, "Complete")
                playCompletionSound()
                
            } catch (e: Exception) {
                resultLiveData.postValue(false)
                updateNotification(0, "Failed: ${e.message}")
                playCompletionSound()
            } finally {
                delay(2000)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }
    
    private fun updateNotification(progress: Int, status: String) {
        val notification = createNotification(progress, status)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }
    
    private fun updateProgress(progress: Int) {
        progressLiveData.postValue(progress)
    }
    
    private fun updateProgressWithETA(progress: Int, remainingTimeMillis: Long) {
        progressLiveData.postValue(progress)
        etaLiveData.postValue(remainingTimeMillis)
    }
    
    private fun playCompletionSound() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(200, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(200)
            }
        } catch (e: Exception) {
            // Ignore vibration errors
        }
    }
    
    private fun registerLocalReceiver() {
        val filter = IntentFilter("com.dan.timelapse.MFSR_PROGRESS")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mfsrReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(mfsrReceiver, filter)
        }
    }
    
    private val mfsrReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val progress = intent?.getIntExtra("progress", 0) ?: 0
            val status = intent?.getStringExtra("status") ?: ""
            updateProgress(progress)
            updateNotification(progress, status)
        }
    }
    
    override fun onDestroy() {
        try {
            unregisterReceiver(mfsrReceiver)
        } catch (e: Exception) {
            // Ignore if not registered
        }
        super.onDestroy()
        releaseWakeLock()
        serviceScope.cancel()
    }
}