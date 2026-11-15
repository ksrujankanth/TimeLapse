package com.dan.timelapse.utils

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecList
import android.os.Build

enum class DeviceTier {
    FLAGSHIP,   // 5.5GB+ RAM: Allow 4x, Use ECC
    MIDRANGE,   // 3.5GB RAM: Allow 2x, Use ECC/ORB Hybrid
    BUDGET      // <3.5GB RAM: Allow 2x, Force ORB
}

object HardwareUtils {
    
    fun getDeviceRAMGb(context: Context): Double {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return memoryInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
    }

    fun getDeviceTier(context: Context): DeviceTier {
        val totalRamGb = getDeviceRAMGb(context)
        return when {
            totalRamGb >= 5.5 -> DeviceTier.FLAGSHIP
            totalRamGb >= 3.5 -> DeviceTier.MIDRANGE
            else -> DeviceTier.BUDGET
        }
    }
    
    fun isLowEndDevice(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val totalRamGb = memoryInfo.totalMem / (1024 * 1024 * 1024.0)
        return totalRamGb < 4.0
    }
    
    fun isHEVCHardwareSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val totalRamGb = memoryInfo.totalMem / (1024 * 1024 * 1024.0)
        if (totalRamGb < 3.0) return false

        try {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            for (info in codecList.codecInfos) {
                if (!info.isEncoder) continue 

                for (type in info.supportedTypes) {
                    if (type.equals("video/hevc", ignoreCase = true)) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            if (info.isHardwareAccelerated) return true
                        } else {
                            val name = info.name.lowercase()
                            val isSoftware = name.startsWith("omx.google.") ||
                                             name.startsWith("c2.android.") ||
                                             name.contains(".sw.") ||
                                             name.contains(".google.") ||
                                             name.contains("software")
                            if (!isSoftware) return true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }
}