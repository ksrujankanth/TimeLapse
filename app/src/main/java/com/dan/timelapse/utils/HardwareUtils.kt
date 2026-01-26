package com.dan.timelapse.utils

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecList
import android.os.Build

object HardwareUtils {

    fun isLowEndDevice(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val totalRamGb = memoryInfo.totalMem / (1024 * 1024 * 1024.0)
        return totalRamGb < 4.0 // Considering < 4GB as lower end for 4K video processing
    }
    
    fun isHEVCHardwareSupported(context: Context): Boolean {
        // 1. Android Version Check: HEVC hardware support was unstable/rare before Android 7.0 (API 24)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

        // 2. Memory Check: Stacking 4K HEVC frames is memory intensive. 
        // We disable it by default for "Budget" devices with less than 3GB of RAM.
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val totalRamGb = memoryInfo.totalMem / (1024 * 1024 * 1024.0)
        if (totalRamGb < 3.0) return false

        // 3. Hardware Acceleration Check
        try {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            for (info in codecList.codecInfos) {
                // We check for Encoders since the app saves/stacks into video files
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
