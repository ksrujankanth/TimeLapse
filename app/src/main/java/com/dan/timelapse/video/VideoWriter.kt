package com.dan.timelapse.video

import android.util.Log
import com.dan.timelapse.filters.FramesConsumer
import org.opencv.core.Mat

class VideoWriter(
    private val path: String,
    private val fps: Int,
    private val h265: Boolean,
    private val width: Int,
    private val height: Int,
    private val sourceIsImages: Boolean = false) // true = RGB (Android images), false = BGR (video)
    : FramesConsumer {

    private var encoder: VideoEncoder? = null

    override fun start() {}

    override fun stop(canceled: Boolean) {
        encoder?.let {
            it.release() // This triggers the MediaMuxer stop/release
            encoder = null
        }
    }

    override fun consume(index: Int, frame: Mat) {
        if (encoder == null) {
            try {
                val useH265 = h265 && VideoEncoder.isH265Supported()
                encoder = VideoEncoder.create(path, fps, width, height, useH265, sourceIsImages)
            } catch (e: Exception) {
                Log.e("VideoWriter", "Failed to create VideoEncoder", e)
                throw e // Re-throw to stop processing
            }
        }
        // Clamp to even dimensions to prevent YUV green strip from MediaCodec padding
        val finalW = frame.cols()
        val finalH = frame.rows()
        val safeW = finalW - (finalW % 2)
        val safeH = finalH - (finalH % 2)
        val frameToWrite = if (safeW != finalW || safeH != finalH) {
            Log.w("VideoWriter", "Clamping odd frame ${finalW}x${finalH} -> ${safeW}x${safeH}")
            Mat(frame, org.opencv.core.Rect(0, 0, safeW, safeH))
        } else {
            frame
        }
        encoder?.write(frameToWrite)
        if (frameToWrite.nativeObj != frame.nativeObj) {
            frameToWrite.release()
        }
    }

    override fun getWindowSize(): Int = 0
}