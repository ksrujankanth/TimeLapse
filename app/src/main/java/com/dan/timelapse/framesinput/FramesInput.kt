package com.dan.timelapse.framesinput

import android.net.Uri
import org.opencv.core.Mat

abstract class FramesInput {
    abstract val name: String
    abstract var width: Int
    abstract var height: Int
    abstract val fps: Int
    abstract val size: Int
    open val videoUri: Uri? = null
    open val durationMs: Long = 0L // For videos

    // Corrected signature for the processing loop - now provides (current, total) for accurate progress
    abstract fun forEachFrame(
        start: Int,
        progress: (Int, Int) -> Unit,
        callback: (Int, Int, Mat) -> Boolean
    )

    // New method for random access (Playback/Scrubbing)
    abstract fun getFrame(index: Int): Mat?

    abstract fun firstFrame(): Mat
    abstract fun release()
}