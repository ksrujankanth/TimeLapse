package com.dan.timelapse.utils

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * Power-state helper shared by the ETA model (see `com.dan.timelapse.eta`).
 *
 * The per-phase [EtaCalibrator] that used to live here was replaced in Nov 2026
 * by the learned model in `com.dan.timelapse.eta`. It keyed statistics by
 * resolution x frames and had three defects that made the number worse than no
 * number at all (matrices/accumulate predicted from the whole clip instead of
 * the selected frames, a frames/milliseconds unit mismatch, and a phase clock
 * that restarted on every status-text change). Do not reintroduce a second
 * estimator: one model, one place to learn.
 */
fun isDevicePluggedIn(context: Context): Boolean {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
    return plugged == BatteryManager.BATTERY_PLUGGED_AC ||
           plugged == BatteryManager.BATTERY_PLUGGED_USB ||
           plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS
}
