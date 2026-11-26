package com.dan.timelapse

import android.app.Application
import android.util.Log
import com.google.android.material.color.DynamicColors
import org.opencv.android.OpenCVLoader

class TimeLapseApp : Application() {
    companion object {
        private const val TAG = "TimeLapseApp"
        
        init {
            // MUST be in companion object init block to prevent n_delete crashes
            if (!OpenCVLoader.initDebug()) {
                Log.e(TAG, "FATAL: OpenCV failed to initialize.")
            } else {
                Log.d(TAG, "SUCCESS: OpenCV initialized correctly.")
                try {
                    System.loadLibrary("timelapse")
                } catch (e: UnsatisfiedLinkError) {
                    Log.e(TAG, "FATAL: Timelapse library failed.", e)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        
        // 🚨 THE MISSING SWITCH: Enable Material You dynamic colors globally
        // This applies the user's wallpaper colors to ?attr/colorPrimary throughout the app
        DynamicColors.applyToActivitiesIfAvailable(this)
        Log.d(TAG, "Dynamic colors enabled")

        // Read the learned ETA model off the main thread so the first render does
        // not pay for a SharedPreferences + JSON parse inside a progress dialog.
        Thread({
            try {
                com.dan.timelapse.eta.Eta.warmUp(this)
                Log.d(TAG, "ETA model warmed up (${com.dan.timelapse.eta.Eta.engine(this).jobsCompleted()} jobs learned)")
            } catch (e: Exception) {
                Log.w(TAG, "ETA warm-up failed", e)
            }
        }, "EtaWarmUp").start()
    }
}
