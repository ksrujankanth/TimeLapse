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
    private val sourceIsImages: Boolean = false, // true = RGB (Android images), false = BGR (video)
    private val qualityPercent: Int = 50) // 1..100, 50 = encoder default target
    : FramesConsumer {

    private var encoder: VideoEncoder? = null

    /**
     * Output frames actually handed to the encoder. The render validates this
     * before publishing: a stacking filter that never fills its window emits a
     * single frame, and a one-frame MP4 has a 0 ms duration.
     */
    var framesWritten: Int = 0
        private set

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
                encoder = VideoEncoder.create(path, fps, width, height, useH265, sourceIsImages, qualityPercent)
            } catch (e: Exception) {
                Log.e("VideoWriter", "Failed to create VideoEncoder", e)
                throw e // Re-throw to stop processing
            }
        }
        // Clamp to even dimensions to prevent YUV green strip from MediaCodec padding,
        // then guarantee the frame matches the configured encoder surface.
        val finalW = frame.cols()
        val finalH = frame.rows()
        val safeW = finalW - (finalW % 2)
        val safeH = finalH - (finalH % 2)
        var frameToWrite: Mat = frame
        if (safeW != finalW || safeH != finalH) {
            Log.w("VideoWriter", "Clamping odd frame ${finalW}x${finalH} -> ${safeW}x${safeH}")
            frameToWrite = Mat(frame, org.opencv.core.Rect(0, 0, safeW, safeH))
        }
        if (frameToWrite.cols() != width || frameToWrite.rows() != height) {
            Log.w("VideoWriter", "Resizing frame ${frameToWrite.cols()}x${frameToWrite.rows()} -> ${width}x${height}")
            val resized = Mat()
            org.opencv.imgproc.Imgproc.resize(frameToWrite, resized, org.opencv.core.Size(width.toDouble(), height.toDouble()))
            if (frameToWrite.nativeObj != frame.nativeObj) frameToWrite.release()
            frameToWrite = resized
        }
        encoder?.write(frameToWrite)
        framesWritten++
        if (frameToWrite.nativeObj != frame.nativeObj) {
            frameToWrite.release()
        }
    }

    override fun getWindowSize(): Int = 0
}