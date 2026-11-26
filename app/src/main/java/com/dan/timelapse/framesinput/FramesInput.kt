package com.dan.timelapse.framesinput

import android.net.Uri
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

abstract class FramesInput {
    abstract val name: String
    abstract var width: Int
    abstract var height: Int
    abstract val fps: Int
    abstract val size: Int
    open val videoUri: Uri? = null
    open val durationMs: Long = 0L // For videos

    /**
     * URI that must be persisted in the session so it can be restored.
     *
     * For a video this is the document URI. For a folder it is the *tree* URI:
     * only the tree URI is granted with takePersistableUriPermission(), so saving
     * the first child image instead made every folder session un-restorable and
     * made the startup check clear the session (and, before this fix, the whole
     * preferences file).
     */
    open val persistableUri: Uri? get() = videoUri

    /**
     * True when the input carries more than 8 bits per channel (16-bit TIFF/PNG), so
     * lossy output formats should be avoided.
     */
    open val isHighBitDepth: Boolean get() = false

    /**
     * Optional 16-bit (CV_16U) frame source used ONLY by MFSR's fusion pass.
     * Preview and effects keep using the 8-bit [getFrame] so nothing downstream has to
     * understand 16-bit Mats. Returns null when unavailable; callers fall back.
     */
    open fun getFrame16(index: Int): Mat? = null

    /**
     * Walk the sequence from [start].
     *
     * [step] is the input-speed stride: deliver source frames `start`, `start + step`,
     * `start + 2*step`, … Implementations that can skip cheaply SHOULD honour it —
     * `ImagesFramesInput` skips the decode entirely and `VideoFramesInput` uses
     * `grab()` instead of `read()`. The callback still receives the ABSOLUTE source
     * index, and the first delivered frame is always [start], so the caller can
     * compute the delivered count as `((end - start) / step) + 1`.
     */
    abstract fun forEachFrame(
        start: Int,
        step: Int = 1,
        progress: (Int, Int) -> Unit,
        callback: (Int, Int, Mat) -> Boolean
    )

    /**
     * Decode frames for motion analysis, downscaled to roughly [targetWidth].
     *
     * Stabilization analysis does not need full resolution, and decoding a 12 MP
     * JPEG at 1/4 scale is an order of magnitude cheaper than decoding it fully
     * and resizing. Inputs that can do better than the default override this.
     * The callback owns nothing: the frame is released by the input.
     */
    open fun forEachAnalysisFrame(
        start: Int,
        targetWidth: Int,
        step: Int = 1,
        callback: (Int, Mat) -> Boolean
    ) {
        forEachFrame(start, step, { _, _ -> }) { index, _, frame ->
            if (frame.cols() <= targetWidth) {
                callback(index, frame)
            } else {
                val scale = targetWidth.toDouble() / frame.cols()
                val small = Mat()
                try {
                    Imgproc.resize(
                        frame, small,
                        Size(targetWidth.toDouble(), frame.rows() * scale),
                        0.0, 0.0, Imgproc.INTER_AREA
                    )
                    callback(index, small)
                } finally {
                    small.release()
                }
            }
        }
    }

    // New method for random access (Playback/Scrubbing)
    abstract fun getFrame(index: Int): Mat?

    abstract fun firstFrame(): Mat
    abstract fun release()
}