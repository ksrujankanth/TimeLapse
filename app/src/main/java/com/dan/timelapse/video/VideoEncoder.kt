package com.dan.timelapse.video

import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Range
import org.opencv.imgproc.Imgproc

class VideoEncoder(
    private val frameRate: Int,
    private val width: Int,
    private val height: Int,
    private val encoder: MediaCodec,
    private val muxer: MediaMuxer,
    private val surface: Surface
) {
    companion object {
        private const val TIMEOUT = 1000L
        private const val MIME_TYPE_H264 = "video/avc"
        private const val MIME_TYPE_H265 = "video/hevc"

        //ref ranges for 1080p / 30 fps
        private val BITRATE_REF_H264 = Range(3000000, 6000000)
        private val BITRATE_REF_H265 = Range(BITRATE_REF_H264.start / 2, BITRATE_REF_H264.end / 2)

        private fun getBitRate(refValue: Int, refRange: Range, width: Int, height: Int, fps_: Int): Int {
            val fps = if (fps_ < 30) 30 else fps_
            val alpha = (width.toDouble() * height * fps) / (1920.0 * 1080.0 * 30)
            val range = Range( (refRange.start * alpha).toInt(), (refRange.end * alpha).toInt())

            return when {
                0 == refValue || refValue > range.end -> range.end
                refValue < range.start -> range.start
                else -> refValue
            }
        }

        fun create(path: String, fps: Int, width: Int, height: Int, bitRate_: Int, h265: Boolean): VideoEncoder {
            val mimeType = if (h265) MIME_TYPE_H265 else MIME_TYPE_H264
            val bitRateRange = if (h265) BITRATE_REF_H265 else BITRATE_REF_H264
            val bitRate = getBitRate(bitRate_, bitRateRange, width, height, fps)
            val format = MediaFormat.createVideoFormat(mimeType, width, height)

            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5)
            format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
            format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)

            val encoder = MediaCodec.createEncoderByType(mimeType)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = encoder.createInputSurface()
            encoder.start()

            val muxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            return VideoEncoder(fps, width, height, encoder, muxer, surface)
        }
    }

    private val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val bitmapRect = Rect(0,0, width, height)
    private var frameIndex = 0
    private var open = true
    private var videoTrackIndex = -1
    private val bufferInfo = MediaCodec.BufferInfo()
    private val rgbaFrame = Mat()

    val isOpened: Boolean
        get() = open

    private fun drainEncoder(end: Boolean) {
        if (end) {
            try {
                encoder.signalEndOfInputStream()
            } catch (e: Exception) { e.printStackTrace() }
        }

        while (true) {
            val outBufferId = try {
                encoder.dequeueOutputBuffer(bufferInfo, if (end) 10000L else TIMEOUT)
            } catch (e: Exception) {
                -1
            }
            
            if (outBufferId >= 0) {
                val encodedBuffer = encoder.getOutputBuffer(outBufferId) ?: break
                if (videoTrackIndex >= 0) {
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && bufferInfo.size > 0) {
                        bufferInfo.presentationTimeUs = frameIndex.toLong() * 1000000L / frameRate.toLong()
                        try {
                            muxer.writeSampleData(videoTrackIndex, encodedBuffer, bufferInfo)
                            frameIndex++
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                }
                encoder.releaseOutputBuffer(outBufferId, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
            } else if (MediaCodec.INFO_TRY_AGAIN_LATER == outBufferId) {
                if (!end) break
            } else if (MediaCodec.INFO_OUTPUT_FORMAT_CHANGED == outBufferId) {
                if (videoTrackIndex < 0) {
                    try {
                        videoTrackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                    } catch (e: Exception) { e.printStackTrace() }
                }
            } else if (outBufferId < 0 && end) {
                break
            }
        }
    }

    fun release() {
        if (!open) return
        drainEncoder(true)
        open = false
        try {
            encoder.stop()
        } catch (e: Exception) { e.printStackTrace() }
        encoder.release()
        if (videoTrackIndex >= 0) {
            try {
                muxer.stop()
            } catch (e: Exception) { e.printStackTrace() }
        }
        muxer.release()
        surface.release()
        rgbaFrame.release()
    }

    fun write(frame: Mat) {
        if (!open) return
        
        // Ensure frame is in RGBA for Android Bitmap
        if (frame.channels() == 3) {
            Imgproc.cvtColor(frame, rgbaFrame, Imgproc.COLOR_RGB2RGBA)
        } else {
            frame.copyTo(rgbaFrame)
        }
        
        Utils.matToBitmap(rgbaFrame, bitmap)
        val canvas = surface.lockCanvas(null) ?: return
        canvas.drawBitmap(bitmap, null, bitmapRect, null)
        surface.unlockCanvasAndPost(canvas)
        drainEncoder(false)
    }
}
