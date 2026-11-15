package com.dan.timelapse.mfsr

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import com.dan.timelapse.framesinput.FramesInput
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * Abstraction over frame extraction — hides the difference between
 * videos (MediaMetadataRetriever) and image sequences (FramesInput.getFrame).
 *
 * All frames returned are RGB CV_8UC3 Mats. Caller is responsible for releasing them.
 */
interface FrameSource {
    /** Total number of frames available. */
    val totalFrames: Int

    /** Input dimensions (pre-any rotation/crop). */
    val width: Int
    val height: Int

    /** Extract a single frame as an RGB Mat. Returns null on failure. */
    fun getFrame(index: Int): Mat?

    /** Release any native resources. Safe to call multiple times. */
    fun release()
}

/**
 * Frame source backed by a video URI using MediaMetadataRetriever.
 * Preferred for video input — no VideoCapture temp file, no double-rotation bug.
 */
class VideoFrameSource(
    private val context: Context,
    private val videoUri: Uri
) : FrameSource {
    private val retriever = MediaMetadataRetriever()
    private var released = false

    override val totalFrames: Int
    override val width: Int
    override val height: Int

    init {
        // Retry setDataSource up to 3x — Scoped Storage FDs can be flaky on first call
        var ok = false
        var lastErr: Exception? = null
        for (attempt in 1..3) {
            try {
                retriever.setDataSource(context, videoUri)
                ok = true
                break
            } catch (e: Exception) {
                lastErr = e
                Thread.sleep(150L * attempt)
            }
        }
        if (!ok) throw lastErr ?: RuntimeException("Failed to open video")

        val frameCountStr = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT
        )
        totalFrames = frameCountStr?.toIntOrNull() ?: run {
            // Fallback estimate via duration + fps
            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            val fps = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE
            )?.toFloatOrNull() ?: 30f
            ((durationMs / 1000.0) * fps).toInt()
        }.coerceAtLeast(1)

        width = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
        )?.toIntOrNull() ?: 1920
        height = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
        )?.toIntOrNull() ?: 1080
    }

    private fun getFrameAtTimeMs(index: Int): Long {
        val fps = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE
        )?.toFloatOrNull() ?: 30f
        return (index / fps * 1_000_000).toLong()
    }

    private fun bitmapToRgbMat(bitmap: Bitmap): Mat? {
        val bmp = if (bitmap.config != Bitmap.Config.ARGB_8888) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else bitmap

        if (bmp == null) { bitmap.recycle(); return null }

        val mat = Mat()
        try {
            Utils.bitmapToMat(bmp, mat)
            return mat
        } catch (e: Exception) {
            mat.release()
            throw e
        } finally {
            if (bmp !== bitmap) bmp.recycle()
            bitmap.recycle()
        }
    }

    override fun getFrame(index: Int): Mat? {
        if (released) return null
        return try {
            var bitmap: Bitmap?
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                bitmap = retriever.getFrameAtIndex(index)
                // getFrameAtIndex() is unreliable on many devices (returns null for
                // HEVC / certain codecs). Fall back to getFrameAtTime when it fails.
                if (bitmap == null) {
                    Log.w("VideoFrameSource", "getFrameAtIndex($index) returned null, falling back to getFrameAtTime")
                    bitmap = retriever.getFrameAtTime(getFrameAtTimeMs(index), MediaMetadataRetriever.OPTION_CLOSEST)
                }
            } else {
                bitmap = retriever.getFrameAtTime(getFrameAtTimeMs(index), MediaMetadataRetriever.OPTION_CLOSEST)
            }
            if (bitmap == null) return null
            bitmapToRgbMat(bitmap)
        } catch (e: Exception) {
            Log.w("VideoFrameSource", "getFrame($index) failed: ${e.message}")
            null
        }
    }

    override fun release() {
        if (!released) {
            try { retriever.release() } catch (_: Exception) {}
            released = true
        }
    }
}

/**
 * Frame source backed by a FramesInput (image sequences or folders).
 * The FramesInput already handles reading images in RGB, so we just thin-wrap it.
 */
class ImagesFrameSource(private val framesInput: FramesInput) : FrameSource {
    override val totalFrames: Int = framesInput.size
    override val width: Int = framesInput.width
    override val height: Int = framesInput.height

    override fun getFrame(index: Int): Mat? {
        return try {
            val mat = framesInput.getFrame(index) ?: return null
            // framesInput.getFrame may return RGB, RGBA, or gray depending on source —
            // normalize to RGB here so MFSR engine gets consistent input.
            if (mat.channels() == 3) return mat

            val rgb = Mat(mat.rows(), mat.cols(), CvType.CV_8UC3)
            when (mat.channels()) {
                4 -> Imgproc.cvtColor(mat, rgb, Imgproc.COLOR_RGBA2RGB, 3)
                1 -> Imgproc.cvtColor(mat, rgb, Imgproc.COLOR_GRAY2RGB, 3)
                else -> mat.copyTo(rgb)
            }
            mat.release()
            rgb
        } catch (e: Exception) {
            Log.w("ImagesFrameSource", "getFrame($index) failed: ${e.message}")
            null
        }
    }

    override fun release() {
        // FramesInput is owned by the caller — don't release it here.
    }
}
