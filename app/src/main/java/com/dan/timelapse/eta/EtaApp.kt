package com.dan.timelapse.eta

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.dan.timelapse.utils.HardwareUtils
import com.dan.timelapse.utils.isDevicePluggedIn
import kotlin.math.roundToInt

/**
 * Glue between the Android app and the pure-Kotlin estimator: reads the facts a
 * signature needs (RAM, power, battery temperature) and builds signatures.
 */
object EtaApp {

    fun ramGb(context: Context): Int =
        HardwareUtils.getDeviceRAMGb(context).roundToInt().coerceIn(1, 32)

    fun isPlugged(context: Context): Boolean = try {
        isDevicePluggedIn(context)
    } catch (e: Exception) {
        false
    }

    /** Battery temperature in whole °C. 30 is a safe "unknown" default. */
    fun batteryTemperatureC(context: Context): Int = try {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 300) ?: 300
        if (tenths <= 0) 30 else tenths / 10
    } catch (e: Exception) {
        30
    }

    fun videoSignature(
        context: Context,
        effect: String,
        outWidth: Int,
        outHeight: Int,
        frames: Int,
        stabilized: Boolean
    ): EtaSignature = EtaSignature(
        kind = EtaKind.VIDEO_RENDER,
        effect = effect,
        megapixelBucket = EtaSignature.megapixelBucket(EtaUnits.megapixels(outWidth, outHeight)),
        frameBucket = EtaSignature.frameBucket(frames),
        stabilized = stabilized,
        scale = "1",
        ramGb = ramGb(context),
        plugged = isPlugged(context)
    )

    fun mfsrSignature(
        context: Context,
        inputWidth: Int,
        inputHeight: Int,
        frames: Int,
        scale: Float
    ): EtaSignature = EtaSignature(
        kind = EtaKind.MFSR_RENDER,
        effect = "SUPER_RESOLUTION",
        megapixelBucket = EtaSignature.megapixelBucket(EtaUnits.megapixels(inputWidth, inputHeight)),
        frameBucket = EtaSignature.frameBucket(frames),
        stabilized = false,
        scale = EtaSignature.scaleLabel(scale),
        ramGb = ramGb(context),
        plugged = isPlugged(context)
    )

    fun stillSignature(
        context: Context,
        effect: String,
        outWidth: Int,
        outHeight: Int
    ): EtaSignature = EtaSignature(
        kind = EtaKind.STILL_RENDER,
        effect = effect,
        megapixelBucket = EtaSignature.megapixelBucket(EtaUnits.megapixels(outWidth, outHeight)),
        frameBucket = 0,
        stabilized = false,
        scale = "1",
        ramGb = ramGb(context),
        plugged = isPlugged(context)
    )

    fun loadSignature(context: Context, items: Int): EtaSignature = EtaSignature(
        kind = EtaKind.LOAD_MEDIA,
        effect = "LOAD",
        megapixelBucket = 0,
        frameBucket = EtaSignature.frameBucket(items),
        stabilized = false,
        scale = "1",
        ramGb = ramGb(context),
        plugged = isPlugged(context)
    )
}
