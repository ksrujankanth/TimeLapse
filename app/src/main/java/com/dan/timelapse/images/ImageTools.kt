package com.dan.timelapse.images

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import java.nio.ByteBuffer

class ImageTools {
    companion object {
        // Native libraries are loaded in TimeLapseApp.onCreate() before this class is used
        // No need to load again here

        // Wrapper for mergeLightestPixels - uses LongArray to pass native pointers
        fun mergeLightestPixels(images: List<Mat>, output: Mat) {
            if (images.isEmpty()) {
                Log.w("ImageTools", "mergeLightestPixels: images list is empty!")
                return
            }
            try {
                Log.d("ImageTools", "mergeLightestPixels: ${images.size} frames, output empty=${output.empty()}, channels=${images[0].channels()}")
                
                // Pure Kotlin implementation - bulletproof for any channel count
                images[0].copyTo(output)
                for (i in 1 until images.size) {
                    org.opencv.core.Core.max(output, images[i], output)
                }
                
                Log.d("ImageTools", "mergeLightestPixels: done, output empty=${output.empty()}")
            } catch (e: Exception) {
                Log.e("ImageTools", "mergeLightestPixels failed", e)
            }
        }

        // Wrapper for mergeDarkestPixels - uses LongArray to pass native pointers
        fun mergeDarkestPixels(images: List<Mat>, output: Mat) {
            if (images.isEmpty()) {
                Log.w("ImageTools", "mergeDarkestPixels: images list is empty!")
                return
            }
            try {
                Log.d("ImageTools", "mergeDarkestPixels: ${images.size} frames, output empty=${output.empty()}, channels=${images[0].channels()}")
                
                // Pure Kotlin implementation - bulletproof for any channel count
                images[0].copyTo(output)
                for (i in 1 until images.size) {
                    org.opencv.core.Core.min(output, images[i], output)
                }
                
                Log.d("ImageTools", "mergeDarkestPixels: done, output empty=${output.empty()}")
            } catch (e: Exception) {
                Log.e("ImageTools", "mergeDarkestPixels failed", e)
            }
        }

        // Pure Kotlin implementation for motion extraction - no JNI dependency
        fun generateMotionExtractionFrame(currentFrame: Mat, previousFrame: Mat, outputFrame: Mat) {
            val current16S = Mat()
            val previous16S = Mat()
            val diff16S = Mat()

            try {
                // Convert to 16-bit signed to safely handle negative subtraction values
                currentFrame.convertTo(current16S, org.opencv.core.CvType.CV_16SC3)
                previousFrame.convertTo(previous16S, org.opencv.core.CvType.CV_16SC3)
                
                // Perform the subtraction
                org.opencv.core.Core.subtract(current16S, previous16S, diff16S)
                
                // Convert back to 8-bit with 0.5 scaling and 127.5 grey offset
                diff16S.convertTo(outputFrame, org.opencv.core.CvType.CV_8UC3, 0.5, 127.5)
            } catch (e: Exception) {
                Log.e("ImageTools", "generateMotionExtractionFrame failed", e)
            } finally {
                // Mandatory memory cleanup
                current16S.release()
                previous16S.release()
                diff16S.release()
            }
        }

        // Accumulate Lightest pixels - persistent canvas using Core.max
        fun accumulateLightest(accumulator: Mat, currentFrame: Mat) {
            if (accumulator.empty()) {
                currentFrame.copyTo(accumulator)
                return
            }
            
            try {
                // Core.max keeps the brightest pixel at every coordinate
                org.opencv.core.Core.max(accumulator, currentFrame, accumulator)
            } catch (e: Exception) {
                Log.e("ImageTools", "accumulateLightest failed", e)
            }
        }

        // Accumulate Darkest pixels - persistent canvas using Core.min
        fun accumulateDarkest(accumulator: Mat, currentFrame: Mat) {
            if (accumulator.empty()) {
                currentFrame.copyTo(accumulator)
                return
            }
            
            try {
                // Core.min keeps the darkest pixel at every coordinate
                org.opencv.core.Core.min(accumulator, currentFrame, accumulator)
            } catch (e: Exception) {
                Log.e("ImageTools", "accumulateDarkest failed", e)
            }
        }

        fun calculateMedianFrame(frames: List<Mat>, outputFrame: Mat) {
            if (frames.isEmpty()) return
            try {
                Log.d("ImageTools", "calculateMedianFrame: ${frames.size} frames")
                val nativeObjectAddresses = LongArray(frames.size) { i -> frames[i].nativeObj }
                calculateMedianFrameNative(nativeObjectAddresses, outputFrame.nativeObj)
            } catch (e: Exception) {
                Log.e("ImageTools", "calculateMedianFrame failed", e)
            }
        }

        fun accumulateMedian(median: Mat, frame: Mat) {
            try {
                accumulateMedianNative(median.nativeObj, frame.nativeObj)
            } catch (e: Exception) {
                Log.e("ImageTools", "accumulateMedian failed", e)
            }
        }

        fun processEndlessMedianStrip(
            inputBuffer: ByteBuffer,
            outputBuffer: ByteBuffer,
            width: Int,
            stripHeight: Int,
            frameCount: Int
        ) {
            try {
                processEndlessMedianStripNative(inputBuffer, outputBuffer, width, stripHeight, frameCount)
            } catch (e: Exception) {
                Log.e("ImageTools", "processEndlessMedianStrip failed", e)
            }
        }

        // Safe C++ bypass for RGBA to RGB conversion
        fun dropAlphaChannel(rgba: Mat, rgb: Mat) {
            try {
                dropAlphaChannelNative(rgba.nativeObj, rgb.nativeObj)
            } catch (e: Exception) {
                android.util.Log.w("ImageTools", "Native dropAlphaChannel failed, using Kotlin fallback", e)
                // Fallback: manual RGBA to RGB conversion using Core
                try {
                    if (!rgba.empty() && rgba.channels() == 4) {
                        rgb.create(rgba.rows(), rgba.cols(), CvType.CV_8UC3)
                        val fromTo = MatOfInt(0, 0, 1, 1, 2, 2)
                        Core.mixChannels(listOf(rgba), listOf(rgb), fromTo)
                        fromTo.release()
                    }
                } catch (e2: Exception) {
                    android.util.Log.e("ImageTools", "Kotlin fallback also failed", e2)
                }
            }
        }

        // Native declarations - external functions
        external fun mergeLightestPixelsNative(images: LongArray, output: Long)
        external fun mergeDarkestPixelsNative(images: LongArray, output: Long)
        external fun calculateMedianFrameNative(sourceFramesAddrs: LongArray, outputFrameAddr: Long)
        external fun accumulateMedianNative(medianAddr: Long, frameAddr: Long)
        external fun processEndlessMedianStripNative(inputBuffer: ByteBuffer, outputBuffer: ByteBuffer, width: Int, stripHeight: Int, frameCount: Int)
        external fun dropAlphaChannelNative(rgbaAddr: Long, rgbAddr: Long)

        // MFSR Engine - Stateful Multi-Frame Super-Resolution
        // Tier flag: true = force ORB fallback (for BUDGET devices), false = use ECC first
        // cacheDirPath: for memory-mapped file storage on large canvases
        // isGrayscale: if true, allocate 1-channel matrices (CV_8UC1 / CV_32FC1)
        // isHdr: if true, use 32-bit floating point accumulator to prevent clipping
        external fun createMFSREngineNative(scale: Float, anchorAddr: Long): Long
        external fun createMFSREngineWithTierNative(scale: Float, anchorAddr: Long, useOrbFallback: Boolean, cacheDirPath: String): Long
        external fun createMFSREngineWithFlagsNative(scale: Float, anchorAddr: Long, useOrbFallback: Boolean, cacheDirPath: String, isGrayscale: Boolean, isHdr: Boolean): Long
        external fun resetMFSREngineAnchorNative(enginePtr: Long)
        external fun processMFSRFrameNative(enginePtr: Long, frameAddr: Long): Boolean
        external fun finalizeMFSREngineNative(enginePtr: Long, outputAddr: Long)
        external fun destroyMFSREngineNative(enginePtr: Long)
    }
}

object ImageLoaderUtils {
    fun loadSafePreviewBitmap(imagePath: String, maxDimension: Int = 4096): Bitmap? {
        val options = BitmapFactory.Options()
        
        // 1. Read only the dimensions first (does not allocate memory for pixels)
        options.inJustDecodeBounds = true
        BitmapFactory.decodeFile(imagePath, options)

        // 2. Calculate the subsampling scale factor
        var scale = 1
        while (options.outWidth / scale > maxDimension || options.outHeight / scale > maxDimension) {
            scale *= 2
        }

        // 3. Decode the actual bitmap, scaled down to fit safely in the UI Canvas
        options.inJustDecodeBounds = false
        options.inSampleSize = scale
        options.inPreferredConfig = Bitmap.Config.RGB_565
        return BitmapFactory.decodeFile(imagePath, options)
    }

    suspend fun loadDownsampledBitmapAsync(path: String, maxDimension: Int = 2048): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            BitmapFactory.decodeFile(path, options)

            val outWidth = options.outWidth
            val outHeight = options.outHeight
            if (outWidth <= 0 || outHeight <= 0) {
                android.util.Log.e("ImageLoaderUtils", "Invalid bitmap dimensions for $path")
                return@withContext null
            }

            // Compute inSampleSize so BOTH dimensions fit within maxDimension.
            // Use || so either dimension exceeding the limit triggers more subsampling.
            var inSampleSize = 1
            while (outWidth / inSampleSize > maxDimension || outHeight / inSampleSize > maxDimension) {
                inSampleSize *= 2
            }

            options.inJustDecodeBounds = false
            options.inSampleSize = inSampleSize
            options.inPreferredConfig = Bitmap.Config.RGB_565
            val bmp = BitmapFactory.decodeFile(path, options)
            if (bmp == null) {
                android.util.Log.e("ImageLoaderUtils", "decodeFile returned null for $path")
            } else {
                android.util.Log.d("ImageLoaderUtils", "Loaded ${bmp.width}x${bmp.height} from ${outWidth}x${outHeight} (inSampleSize=$inSampleSize)")
            }
            return@withContext bmp
        } catch (e: OutOfMemoryError) {
            android.util.Log.e("ImageLoaderUtils", "OOM loading $path", e)
            System.gc()
            return@withContext null
        } catch (e: Exception) {
            android.util.Log.e("ImageLoaderUtils", "Failed to load $path", e)
            return@withContext null
        }
    }
}
