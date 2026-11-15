package com.dan.timelapse.utils

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager

// Helper to check power state
fun isDevicePluggedIn(context: Context): Boolean {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
    return plugged == BatteryManager.BATTERY_PLUGGED_AC ||
           plugged == BatteryManager.BATTERY_PLUGGED_USB ||
           plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS
}

// Utilitarian key generator
data class PhaseProfileKey(
    val phaseName: String,
    val isStabilizationOn: Boolean = false,
    val isPluggedIn: Boolean = false,
    val isTargetSdCard: Boolean = false,
    val isInputRaw: Boolean = false,
    val videoCodec: String = "none" // e.g., "h264", "hevc", or "none" for images
) {
    fun build(): String {
        return "${phaseName}_" +
               "stab_${if (isStabilizationOn) "1" else "0"}_" +
               "pwr_${if (isPluggedIn) "1" else "0"}_" +
               "sd_${if (isTargetSdCard) "1" else "0"}_" +
               "raw_${if (isInputRaw) "1" else "0"}_" +
               "codec_$videoCodec"
    }
}

class EtaCalibrator(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("eta_learning_cache", Context.MODE_PRIVATE)

    fun recordPhaseCompletion(phaseName: String, durationMs: Long, width: Int, height: Int, frameCount: Int) {
        val totalPixels = width.toLong() * height.toLong() * frameCount.toLong()
        if (totalPixels <= 0) return

        val currentRunMsPerPixel = durationMs.toFloat() / totalPixels
        val historicalMsPerPixel = prefs.getFloat(phaseName, currentRunMsPerPixel)
        val newCalibratedSpeed = (historicalMsPerPixel * 0.7f) + (currentRunMsPerPixel * 0.3f)

        prefs.edit().putFloat(phaseName, newCalibratedSpeed).apply()
    }

    fun predictPhaseDurationMs(phaseName: String, width: Int, height: Int, frameCount: Int): Long {
        val historicalMsPerPixel = prefs.getFloat(phaseName, -1f)
        if (historicalMsPerPixel < 0f) return -1L

        val totalPixels = width.toLong() * height.toLong() * frameCount.toLong()
        return (historicalMsPerPixel * totalPixels).toLong()
    }
}
