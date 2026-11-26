package com.dan.timelapse.video

import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import android.view.Surface
import org.opencv.core.Mat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VideoEncoder private constructor(
    private val frameRate: Int,
    private val width: Int,
    private val height: Int,
    private val encoder: MediaCodec,
    private val muxer: MediaMuxer,
    private val surface: Surface,
    private val sourceIsImages: Boolean // true = RGB (Android images), false = BGR (video)
) {
    companion object {
        private const val TAG = "VideoEncoder"

        /**
         * Resolution- and frame-rate-aware bitrate. A fixed 5 Mbps is fine for
         * 1080p30 but starves 4K and over-spends on 720p. 0.10 bits/pixel/frame
         * for AVC and 0.07 for HEVC, scaled by the user's quality slider
         * (50% = the calibrated baseline).
         */
        private fun estimateBitRate(width: Int, height: Int, fps: Int, h265: Boolean, qualityPercent: Int): Int {
            val bitsPerPixel = if (h265) 0.07 else 0.10
            val baseline = width.toDouble() * height.toDouble() * fps.coerceAtLeast(1) * bitsPerPixel
            val scaled = baseline * (qualityPercent.coerceIn(1, 100) / 50.0)
            return scaled.toInt().coerceIn(500_000, 120_000_000)
        }

        /** Minimal legal AVC level for the frame size + rate (Level 4 caps at 1080p30). */
        private fun avcLevelFor(width: Int, height: Int, fps: Int): Int {
            val pixels = width.toLong() * height.toLong()
            val pixelsPerSecond = pixels * fps.coerceAtLeast(1)
            return when {
                pixels <= 1920L * 1088L && fps <= 30 -> MediaCodecInfo.CodecProfileLevel.AVCLevel4
                pixels <= 2048L * 1088L && fps <= 60 -> MediaCodecInfo.CodecProfileLevel.AVCLevel42
                pixelsPerSecond <= 4096L * 2304L * 30L -> MediaCodecInfo.CodecProfileLevel.AVCLevel51
                else -> MediaCodecInfo.CodecProfileLevel.AVCLevel52
            }
        }

        fun isH265Supported(): Boolean {
            return try {
                val codecs = MediaCodecList(MediaCodecList.ALL_CODECS)
                codecs.codecInfos.any { info ->
                    info.isEncoder && info.supportedTypes.any { type ->
                        type.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check H.265 support", e)
                false
            }
        }

        fun create(path: String, fps: Int, width: Int, height: Int, h265: Boolean, sourceIsImages: Boolean = false, qualityPercent: Int = 50): VideoEncoder {
            Log.d(TAG, "Initializing VideoEncoder: ${width}x${height} @ $fps fps (quality ${qualityPercent}%)")

            val finalWidth = (width / 2) * 2
            val finalHeight = (height / 2) * 2

            if (finalWidth != width || finalHeight != height) {
                Log.w(TAG, "Odd dimensions corrected: ${width}x${height} -> ${finalWidth}x${finalHeight}")
            }

            val mimeType = if (h265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            val bitRate = estimateBitRate(finalWidth, finalHeight, fps, h265, qualityPercent)
            Log.d(TAG, "Encoder bitrate: ${bitRate / 1_000_000.0} Mbps")

            val format = MediaFormat.createVideoFormat(mimeType, finalWidth, finalHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

                // Explicitly declare the stream as 0-255 Full Range
                // Prevents Android players from applying a 16-235 contrast stretch
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)

                if (!h265) {
                    // High profile above 1080p: Baseline is legal there but hardware
                    // encoders commonly reject Baseline + level 5.x for UHD.
                    val profile = if (finalWidth.toLong() * finalHeight > 1920L * 1088L) {
                        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                    } else {
                        MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                    }
                    setInteger(MediaFormat.KEY_PROFILE, profile)
                    // Level 4 only covers 1080p30. A 4K stream declared as level 4
                    // is invalid and strict encoders reject it.
                    setInteger(MediaFormat.KEY_LEVEL, avcLevelFor(finalWidth, finalHeight, fps))
                }
            }

            val encoder = MediaCodec.createEncoderByType(mimeType)
            try {
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e: Exception) {
                // Some encoders reject an explicit profile/level (common with UHD
                // Baseline + 5.x). Retry with the encoder's own defaults before
                // failing the whole render.
                Log.w(TAG, "configure() rejected profile/level, retrying defaults", e)
                try { encoder.reset() } catch (_: Exception) {}
                val relaxed = MediaFormat.createVideoFormat(mimeType, finalWidth, finalHeight)
                relaxed.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                relaxed.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                relaxed.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                relaxed.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                relaxed.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
                relaxed.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                encoder.configure(relaxed, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            val surface = encoder.createInputSurface()
            val muxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val ve = VideoEncoder(fps, finalWidth, finalHeight, encoder, muxer, surface, sourceIsImages)

encoder.setCallback(object : MediaCodec.Callback() {
    override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        try { ve.onOutputBuffer(codec, index, info) } catch (e: Exception) { Log.e(TAG, "onOutputBufferAvailable failed", e) }
    }
    override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
        try { ve.onFormatChanged(format) } catch (e: Exception) { Log.e(TAG, "onOutputFormatChanged failed", e) }
    }
    override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
        Log.e(TAG, "Encoder error", e)
    }
    override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}
})
            encoder.start()

            return ve
        }
    }

    private val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val bitmapRect = Rect(0, 0, width, height)
    private var frameIndex = 0L
    private var videoTrackIndex = -1
    private var isMuxerStarted = false
    private var reusableBuffer: ByteArray? = null
    private var reusablePixelArray: IntArray? = null
    private val eosLatch = CountDownLatch(1)

private fun onOutputBuffer(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
    val encodedData = codec.getOutputBuffer(index) ?: run { Log.w(TAG, "getOutputBuffer returned null at index $index"); return }
    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
        info.size = 0
    }
    if (info.size != 0) {
        if (!isMuxerStarted) {
            Log.w(TAG, "Output buffer before muxer started at frame $frameIndex, isMuxerStarted=$isMuxerStarted, videoTrackIndex=$videoTrackIndex")
            codec.releaseOutputBuffer(index, false)
            return
        }
        encodedData.position(info.offset)
        encodedData.limit(info.offset + info.size)
        Log.d(TAG, "Writing sample data: trackIndex=$videoTrackIndex, size=${info.size}, flags=${info.flags}")
        muxer.writeSampleData(videoTrackIndex, encodedData, info)
    }
    codec.releaseOutputBuffer(index, false)
    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
        Log.d(TAG, "End of Stream reached in encoder output")
        eosLatch.countDown()
    }
}

private fun onFormatChanged(format: MediaFormat) {
    if (isMuxerStarted) {
        Log.w(TAG, "Format changed after muxer started — ignoring")
        return
    }
    videoTrackIndex = muxer.addTrack(format)
    Log.d(TAG, "onFormatChanged: trackIndex=$videoTrackIndex")
    muxer.start()
    isMuxerStarted = true
    Log.d(TAG, "Muxer started with track index: $videoTrackIndex")
}

    fun write(frame: Mat) {
        try {
            if (frame.empty() || frame.cols() <= 0 || frame.rows() <= 0) {
                return
            }

            val channels = frame.channels()
            val w = frame.cols()
            val h = frame.rows()

            val softwareMat = Mat()
            frame.copyTo(softwareMat)

            val bufferSize = w * h * channels
            if (reusableBuffer == null || reusableBuffer!!.size < bufferSize) {
                reusableBuffer = ByteArray(bufferSize)
            }
            val bgrBytes = reusableBuffer!!
            softwareMat.get(0, 0, bgrBytes)
            softwareMat.release()

            val pixelCount = w * h
            if (reusablePixelArray == null || reusablePixelArray!!.size < pixelCount) {
                reusablePixelArray = IntArray(pixelCount)
            }
            val pixels = reusablePixelArray!!
            var byteIdx = 0
            when (channels) {
                3 -> {
                    if (sourceIsImages) {
                        for (i in 0 until w * h) {
                            val r = (bgrBytes[byteIdx++].toInt() and 0xFF)
                            val g = (bgrBytes[byteIdx++].toInt() and 0xFF)
                            val b = (bgrBytes[byteIdx++].toInt() and 0xFF)
                            pixels[i] = (255 shl 24) or (r shl 16) or (g shl 8) or b
                        }
                    } else {
                        for (i in 0 until w * h) {
                            val b = (bgrBytes[byteIdx++].toInt() and 0xFF)
                            val g = (bgrBytes[byteIdx++].toInt() and 0xFF)
                            val r = (bgrBytes[byteIdx++].toInt() and 0xFF)
                            pixels[i] = (255 shl 24) or (r shl 16) or (g shl 8) or b
                        }
                    }
                }
                4 -> {
                    if (sourceIsImages) {
                        for (i in 0 until w * h) {
                            val r = (bgrBytes[byteIdx++].toInt() and 0xFF)
                            val g = (bgrBytes[byteIdx++].toInt() and 0xFF)
                            val b = (bgrBytes[byteIdx++].toInt() and 0xFF)
                            val a = (bgrBytes[byteIdx++].toInt() and 0xFF)
                            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                        }
                    } else {
                        for (i in 0 until w * h) {
                            val b = (bgrBytes[byteIdx++].toInt() and 0xFF)
                            val g = (bgrBytes[byteIdx++].toInt() and 0xFF)
                            val r = (bgrBytes[byteIdx++].toInt() and 0xFF)
                            val a = (bgrBytes[byteIdx++].toInt() and 0xFF)
                            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                        }
                    }
                }
                else -> {
                    for (i in 0 until w * h) {
                        val v = (bgrBytes[byteIdx++].toInt() and 0xFF)
                        pixels[i] = (255 shl 24) or (v shl 16) or (v shl 8) or v
                    }
                }
            }

            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)

            // lockCanvas blocks naturally when encoder is full (async callback drains output)
            val canvas = try {
                surface.lockCanvas(null)
            } catch (e: Exception) {
                Log.e(TAG, "lockCanvas failed at frame $frameIndex", e)
                null
            }
            if (canvas != null) {
                canvas.drawBitmap(bitmap, null, bitmapRect, null)
                surface.unlockCanvasAndPost(canvas)
                frameIndex++
            }

        } catch (e: Exception) {
            Log.e(TAG, "Encode error: ${e.message}")
        }
    }

    fun release() {
        Log.d(TAG, "Releasing VideoEncoder. Total frames: $frameIndex")
        try {
            encoder.signalEndOfInputStream()
            if (!eosLatch.await(5, TimeUnit.SECONDS)) {
                Log.w(TAG, "EOS not received within 5s — forcing stop")
            }
            encoder.stop()
            encoder.release()
            surface.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing encoder", e)
        }

        try {
            if (isMuxerStarted) {
                muxer.stop()
            }
            muxer.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing muxer", e)
        }
        Log.d(TAG, "Release complete. Total frames processed: $frameIndex")
    }
}
