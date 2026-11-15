package com.dan.timelapse.mfsr

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.util.Log
import com.dan.timelapse.framesinput.FramesInput
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfInt
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.media.MediaScannerConnection

/** Data class holding frame metrics for two-stage sorting */
private data class FrameStat(val index: Int, val mse: Double, val sharpness: Double, val brightness: Double)

/**
 * Clean MFSR Processor — works for both video input and image sequences.
 *
 * Factory methods:
 *   - forVideo(context, uri, anchor, scale) — uses MediaMetadataRetriever
 *   - forImages(framesInput, anchor, scale) — uses FramesInput.getFrame()
 *
 * Progress callback reports real frame counts (not percentages).
 */
class MFSRProcessor private constructor(
    private val context: Context,
    private val frameSource: FrameSource,
    private val anchorFrameIndex: Int,
    private val scaleFactor: Float,
    private val trimStartFrame: Int = 0,
    private val trimEndFrame: Int = Int.MAX_VALUE
) {
    companion object {
        private const val TAG = "MFSRProcessor"
        // Wider window means more candidate frames to pick the best N from.
        // ±90 = 180 frame scan which is ~6 seconds at 30fps. Still fast at 640x360 proxy.
        private const val SCAN_WINDOW_RADIUS = 90
        private const val MAX_CONTRIBUTING_FRAMES_HD = 120  // Up from 80 — more frames = more sub-pixel info
        private const val MAX_CONTRIBUTING_FRAMES_4K = 50   // Up from 40 slightly for 4K
        private const val PROXY_WIDTH = 640
        private const val PROXY_HEIGHT = 360
        private const val PIXEL_THRESHOLD_4K = 1920 * 1080

        /** Create a processor for a video URI. */
        fun forVideo(
            context: Context,
            videoUri: Uri,
            anchorFrameIndex: Int,
            scaleFactor: Float = 4.0f,
            trimStartFrame: Int = 0,
            trimEndFrame: Int = Int.MAX_VALUE
        ): MFSRProcessor {
            val source = VideoFrameSource(context, videoUri)
            return MFSRProcessor(context, source, anchorFrameIndex, scaleFactor, trimStartFrame, trimEndFrame)
        }

        /** Create a processor for an image sequence or folder. */
        fun forImages(
            context: Context,
            framesInput: FramesInput,
            anchorFrameIndex: Int,
            scaleFactor: Float = 4.0f,
            trimStartFrame: Int = 0,
            trimEndFrame: Int = Int.MAX_VALUE
        ): MFSRProcessor {
            val source = ImagesFrameSource(framesInput)
            return MFSRProcessor(context, source, anchorFrameIndex, scaleFactor, trimStartFrame, trimEndFrame)
        }
    }

    /** Progress callback: (current, total, statusMessage). Values are frame units. */
    var onProgress: ((Int, Int, String) -> Unit)? = null
    var isCancelled: Boolean = false

    /** Save baseline bicubic upscaled anchor for before/after comparison */
    private fun saveBaselineBicubic(anchorMat: Mat) {
        try {
            val targetSize = Size(
                anchorMat.cols() * scaleFactor.toDouble(),
                anchorMat.rows() * scaleFactor.toDouble()
            )
            val baselineMat = Mat()
            Imgproc.resize(anchorMat, baselineMat, targetSize, 0.0, 0.0, Imgproc.INTER_CUBIC)
            
            // Convert to Bitmap
            val bitmap = Bitmap.createBitmap(baselineMat.cols(), baselineMat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(baselineMat, bitmap)
            baselineMat.release()
            
            // Save in background thread (non-blocking)
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "MFSR_Baseline_$timestamp.jpg"
            
            Thread {
                try {
                    val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                    val mfsrDir = File(picturesDir, "TimeLapse")
                    if (!mfsrDir.exists()) mfsrDir.mkdirs()
                    val outputFile = File(mfsrDir, fileName)
                    FileOutputStream(outputFile).use { fos ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos)
                    }
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(outputFile.absolutePath),
                        arrayOf("image/jpeg"),
                        null
                    )
                    Log.i(TAG, "Baseline saved: ${outputFile.absolutePath}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save baseline", e)
                }
            }.start()
            bitmap.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Baseline save failed", e)
        }
    }

    fun process(outputFile: File): Boolean {
        var enginePtr = 0L
        try {
            val totalFrames = frameSource.totalFrames
            if (totalFrames <= 0) {
                Log.e(TAG, "Source has no frames")
                return false
            }

            val safeAnchorIndex = anchorFrameIndex.coerceIn(0, totalFrames - 1)
            val scanStart = maxOf(0, safeAnchorIndex - SCAN_WINDOW_RADIUS)
            val scanEnd = minOf(totalFrames - 1, safeAnchorIndex + SCAN_WINDOW_RADIUS)

            // Intersect scan window with user's trim bounds (0-based frame indices)
            val effectiveTrimStart = trimStartFrame.coerceIn(0, totalFrames - 1)
            val effectiveTrimEnd = trimEndFrame.coerceIn(0, totalFrames - 1)
            val effectiveStart = maxOf(scanStart, effectiveTrimStart)
            val effectiveEnd = minOf(scanEnd, effectiveTrimEnd)
            val scanSize = effectiveEnd - effectiveStart + 1
            if (scanSize <= 0) {
                Log.e(TAG, "Trim bounds [$effectiveTrimStart..$effectiveTrimEnd] do not overlap scan window [$scanStart..$scanEnd]")
                return false
            }

            // Adaptive frame budget: halve for 4K+ to prevent OOM
            val pixelCount = frameSource.width * frameSource.height
            val maxContributingFrames = if (pixelCount > PIXEL_THRESHOLD_4K) {
                MAX_CONTRIBUTING_FRAMES_4K
            } else {
                MAX_CONTRIBUTING_FRAMES_HD
            }
            Log.i(TAG, "Input ${frameSource.width}x${frameSource.height} (${pixelCount}px) → frame budget=$maxContributingFrames, scale=${scaleFactor}x")

            // Compute totalUnits = scan + pass1 + pass2 + buffer. This ensures the progress bar never overflows.
            // Using maxContributingFrames as the upper bound (actual may be lower after filtering).
            val totalUnits = scanSize + maxContributingFrames * 2 + 10
            Log.i(TAG, "MFSR start: anchor=$safeAnchorIndex, totalFrames=$totalFrames, trim=[$effectiveTrimStart..$effectiveTrimEnd], scan=[$effectiveStart..$effectiveEnd]")

            onProgress?.invoke(0, totalUnits, "Extracting anchor frame...")

            // ============================================================
            // STEP 1: Extract anchor
            // ============================================================
            val anchorMat = frameSource.getFrame(safeAnchorIndex)
            if (anchorMat == null || anchorMat.empty()) {
                anchorMat?.release()
                Log.e(TAG, "Failed to extract anchor frame at index $safeAnchorIndex")
                return false
            }
            Log.i(TAG, "Anchor: ${anchorMat.cols()}x${anchorMat.rows()}, ch=${anchorMat.channels()}")
            
            // ============================================================
            // PHASE 1: Save Baseline Bicubic (Before) for comparison
            // ============================================================
            saveBaselineBicubic(anchorMat)
            
            // ============================================================
            // STEP 2: Pre-scan MSE similarity
            // ============================================================
            val anchorProxyGray = buildProxyGray(anchorMat)
            val proxyFrame = Mat()
            val proxyGray = Mat()
            val diff = Mat()
            val squared = Mat()
            val proxySize = Size(PROXY_WIDTH.toDouble(), PROXY_HEIGHT.toDouble())

            val mseScores = mutableListOf<Pair<Int, Double>>()
            val frameStats = mutableListOf<FrameStat>()  // For sharpness-first selection
            var lastProgressMs = 0L
            var scanned = 0

            Log.i("MFSR_DEBUG", "Starting Pre-scan [trim=$effectiveTrimStart..$effectiveTrimEnd, scan=$effectiveStart..$effectiveEnd]")
            for (i in effectiveStart..effectiveEnd) {
                if (isCancelled) {
                    anchorProxyGray.release(); proxyFrame.release(); proxyGray.release()
                    diff.release(); squared.release(); anchorMat.release()
                    return false
                }

                val frameMat = frameSource.getFrame(i)
                if (frameMat != null && !frameMat.empty()) {
                    // FIX: Wrap in try/finally to guarantee frameMat.release() even if exception occurs
                    val laplacian = Mat()
                    val mean = MatOfDouble()
                    val stddev = MatOfDouble()
                    try {
                        Imgproc.resize(frameMat, proxyFrame, proxySize)
                        Imgproc.cvtColor(proxyFrame, proxyGray, Imgproc.COLOR_RGB2GRAY)
                        Core.absdiff(anchorProxyGray, proxyGray, diff)
                        Core.multiply(diff, diff, squared)
                        val sum = Core.sumElems(squared)
                        val mse = sum.`val`[0] / (PROXY_WIDTH.toDouble() * PROXY_HEIGHT)
                        mseScores.add(Pair(i, mse))
                        
                        // Compute Laplacian variance (sharpness metric)
                        Imgproc.Laplacian(proxyGray, laplacian, CvType.CV_64F)
                        Core.meanStdDev(laplacian, mean, stddev)
                        val stdDevVal = stddev.get(0, 0)[0]
                        val sharpness = stdDevVal * stdDevVal  // Variance = stddev squared

                        // Mean brightness of grayscale proxy (0-255)
                        val brightnessMean = Core.mean(proxyGray).`val`[0]

                        frameStats.add(FrameStat(i, mse, sharpness, brightnessMean))
                    } finally {
                        frameMat.release()
                        laplacian.release()
                        mean.release()
                        stddev.release()
                    }
                }

                scanned++
                val now = System.currentTimeMillis()
                if (now - lastProgressMs > 250 || i == scanEnd) {
                    lastProgressMs = now
                    onProgress?.invoke(scanned, totalUnits, "Scanning frames: $scanned/$scanSize")
                }
            }

            anchorProxyGray.release(); proxyFrame.release(); proxyGray.release()
            diff.release(); squared.release()

            // ============================================================
            // PHASE 2: Two-Stage Frame Selection (Sharpness-First)
            // ============================================================
            // Stage 1: Sort by sharpness (highest first), keep top 50%
            val sharpestFrames = frameStats.sortedByDescending { it.sharpness }
            val topHalfSharp = sharpestFrames.take(maxOf(1, sharpestFrames.size / 2))
            
            // Stage 2: Sort remaining by MSE (lowest first/most similar), apply adaptive budget
            val bestAlignedSharpFrames = topHalfSharp.sortedBy { it.mse }
            
            // Analyze MSE distribution to adapt frame budget
            val medianMse = if (bestAlignedSharpFrames.isNotEmpty())
                bestAlignedSharpFrames[bestAlignedSharpFrames.size / 2].mse else 0.0
            val p90Mse = if (bestAlignedSharpFrames.isNotEmpty())
                bestAlignedSharpFrames[(bestAlignedSharpFrames.size * 0.9).toInt().coerceAtMost(bestAlignedSharpFrames.size - 1)].mse
                else 0.0
            val spreadRatio = if (medianMse > 1.0) p90Mse / medianMse else 1.0

            // Median brightness of selected frames — used by low-light loosener
            val medianBrightness = if (bestAlignedSharpFrames.isNotEmpty()) {
                bestAlignedSharpFrames.map { it.brightness }.sorted()[bestAlignedSharpFrames.size / 2]
            } else 128.0

            val adaptiveBudget = when {
                spreadRatio < 1.5 -> (maxContributingFrames * 1.5).toInt()  // Very stable → +50%
                spreadRatio < 2.5 -> maxContributingFrames                   // Normal → default
                else -> (maxContributingFrames * 0.75).toInt()               // Noisy → -25%
            }.coerceAtMost(bestAlignedSharpFrames.size)

            Log.i(TAG, "Sharpness-first: median=$medianMse, p90=$p90Mse, spread=$spreadRatio → budget=$adaptiveBudget")

            val selectedIndices = bestAlignedSharpFrames
                .take(adaptiveBudget)
                .map { it.index }
                .filter { it != safeAnchorIndex }
                .sorted()
            // Map from frameIndex → MSE for telemetry
            val mseByFrame: Map<Int, Double> = mseScores.toMap()
            Log.i("MFSR_DEBUG", "Pre-scan finished. Frames selected: ${selectedIndices.size}")
            Log.i(TAG, "Pre-scan: ${frameStats.size} scanned, ${selectedIndices.size} selected (excluding anchor)")

            // ============================================================
            // STEP 4: Init engine with anchor
            // ============================================================
            enginePtr = MFSRNative.createEngine(scaleFactor, anchorMat.nativeObj)
            Log.i("MFSR_DEBUG", "Engine created with PTR: $enginePtr")
            anchorMat.release()
            if (enginePtr == 0L) {
                Log.e(TAG, "Failed to create MFSR engine")
                return false
            }

            // Pass scene statistics for dynamic thresholding (includes low-light loosener)
            MFSRNative.setSceneStats(enginePtr, medianMse, p90Mse, spreadRatio, medianBrightness)

            // ============================================================
            // STEP 5: PASS 1 — Calculate Matrices (fast, no memory caching)
            // ============================================================
            val framesToProcess = selectedIndices.size
            var processed = 0
            var accepted = 0
            lastProgressMs = 0L

            val thermalFilter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)

            for (frameIndex in selectedIndices) {
                if (isCancelled) {
                    MFSRNative.destroyEngine(enginePtr); enginePtr = 0L
                    return false
                }

                // Thermal check every 5 frames
                if (processed > 0 && processed % 5 == 0) {
                    val battery = context.registerReceiver(null, thermalFilter)
                    val temp = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
                    if (temp > 400) {
                        val tempStr = "${temp / 10.0}°C"
                        onProgress?.invoke(
                            scanSize + processed, totalUnits,
                            "Calculating matrices: cooling down ($tempStr)..."
                        )
                        Thread.sleep(500)
                    }
                }

                val frameMat = frameSource.getFrame(frameIndex)
                if (frameMat != null && !frameMat.empty()) {
                    try {
                        val mse = mseByFrame[frameIndex] ?: -1.0
                        val ok = MFSRNative.processFrameWithTelemetry(
                            enginePtr, frameMat.nativeObj, frameIndex, mse
                        )
                        if (ok) accepted++
                    } finally {
                        frameMat.release()
                    }
                }

                processed++
                val now = System.currentTimeMillis()
                if (now - lastProgressMs > 250 || processed == framesToProcess) {
                    lastProgressMs = now
                    onProgress?.invoke(
                        scanSize + processed, totalUnits,
                        "Calculating matrices: $processed/$framesToProcess ($accepted accepted)"
                    )
                }
            }

            Log.i(TAG, "PASS 1 done: $accepted/$framesToProcess accepted")
            if (accepted == 0) {
                Log.e(TAG, "No frames accepted — alignment failed for all")
                MFSRNative.destroyEngine(enginePtr); enginePtr = 0L
                return false
            }

            // ============================================================
            // STEP 5b: PASS 2 — Warp and Accumulate (low memory footprint)
            // ============================================================
            onProgress?.invoke(
                scanSize + framesToProcess, totalUnits,
                "Rendering stabilized frame..."
            )

            processed = 0
            lastProgressMs = 0L

            for ((index, frameIndex) in selectedIndices.withIndex()) {
                if (isCancelled) {
                    MFSRNative.destroyEngine(enginePtr); enginePtr = 0L
                    return false
                }

                val frameMat = frameSource.getFrame(frameIndex)
                if (frameMat != null && !frameMat.empty()) {
                    try {
                        MFSRNative.accumulateFrame(enginePtr, frameMat.nativeObj, index)
                    } finally {
                        frameMat.release()
                    }
                }

                processed++
                val now = System.currentTimeMillis()
                if (now - lastProgressMs > 250 || processed == framesToProcess) {
                    lastProgressMs = now
                    onProgress?.invoke(
                        scanSize + framesToProcess + processed, totalUnits,
                        "Rendering stabilized frame: $processed/$framesToProcess"
                    )
                }
            }

            Log.i(TAG, "PASS 2 done: accumulated $processed frames")

            // ============================================================
            // STEP 6: Finalize (tiled accumulation)
            // ============================================================
            onProgress?.invoke(
                scanSize + framesToProcess, totalUnits,
                "Generating high-res image..."
            )

            val outputMat = Mat()
            MFSRNative.finalizeEngine(enginePtr, outputMat.nativeObj)

            // Capture telemetry BEFORE destroying the engine
            val telemetryLines = mutableListOf<String>()
            try {
                val count = MFSRNative.getTelemetryCount(enginePtr)
                for (i in 0 until count) {
                    telemetryLines.add(MFSRNative.getTelemetryLine(enginePtr, i))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Telemetry capture failed", e)
            }

            /* DISABLED: Preserving raw original color and natural contrast
            try {
                MFSRNative.enhanceOutputContrast(enginePtr, outputMat.nativeObj)
            } catch (e: Exception) {
                Log.w(TAG, "CLAHE enhancement failed (non-fatal)", e)
            }
            */

            MFSRNative.destroyEngine(enginePtr); enginePtr = 0L

            if (outputMat.empty()) {
                Log.e(TAG, "finalize produced empty output")
                outputMat.release()
                return false
            }
            Log.i(TAG, "Output: ${outputMat.cols()}x${outputMat.rows()}")

            // ============================================================
            // STEP 7: Save
            // ============================================================
            onProgress?.invoke(totalUnits - 5, totalUnits, "Saving image...")
            val params = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 95)
            val saved = Imgcodecs.imwrite(outputFile.absolutePath, outputMat, params)

            if (!saved) {
                Log.e(TAG, "imwrite failed")
                params.release()
                outputMat.release()
                System.gc()
                return false
            }
            Log.i(TAG, "Saved raw: ${outputFile.absolutePath} (${outputMat.cols()}x${outputMat.rows()})")

            // Save 2048px preview for UI display (full-res would crash ImageView on many devices)
            try {
                val maxPreviewDim = 2048
                val ow = outputMat.cols()
                val oh = outputMat.rows()
                if (ow > maxPreviewDim || oh > maxPreviewDim) {
                    val scale = maxPreviewDim.toDouble() / kotlin.math.max(ow, oh)
                    val previewW = (ow * scale).toInt()
                    val previewH = (oh * scale).toInt()
                    val previewMat = Mat()
                    Imgproc.resize(outputMat, previewMat, Size(previewW.toDouble(), previewH.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
                    val previewFile = File(
                        outputFile.parent,
                        "${outputFile.nameWithoutExtension}_preview.jpg"
                    )
                    val previewParams = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 90)
                    Imgcodecs.imwrite(previewFile.absolutePath, previewMat, previewParams)
                    previewParams.release()
                    previewMat.release()
                    Log.i(TAG, "Saved preview: ${previewFile.absolutePath} (${previewW}x${previewH})")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Preview generation failed (non-fatal)", e)
            }

            // Bilateral Unsharp Mask sharpened version
            try {
                val blurred = Mat()
                val sharpened = Mat()
                try {
                    Imgproc.bilateralFilter(outputMat, blurred, 9, 75.0, 75.0)
                    Core.addWeighted(outputMat, 1.8, blurred, -0.8, 0.0, sharpened)
                    val sharpFile = File(
                        outputFile.parent,
                        "${outputFile.nameWithoutExtension}_Sharpened.jpg"
                    )
                    Imgcodecs.imwrite(sharpFile.absolutePath, sharpened, params)
                    Log.i(TAG, "Saved sharpened: ${sharpFile.absolutePath}")
                } finally {
                    blurred.release()
                    sharpened.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Sharpened version failed (non-fatal)", e)
            }

            // Telemetry log
            try {
                val logFile = File(outputFile.parent, "${outputFile.nameWithoutExtension}_log.txt")
                logFile.writeText(buildString {
                    appendLine("=== MFSR Telemetry ===")
                    appendLine("Output: ${outputFile.name}")
                    appendLine("Output dimensions: ${outputMat.cols()}x${outputMat.rows()}")
                    appendLine("Scale factor: $scaleFactor")
                    appendLine("Input source: ${frameSource.javaClass.simpleName}")
                    appendLine("Input resolution: ${frameSource.width}x${frameSource.height}")
                    appendLine("Anchor frame: $anchorFrameIndex")
                    appendLine("Total frames available: $totalFrames")
                    appendLine("Trim bounds: [$effectiveTrimStart..$effectiveTrimEnd]")
                    appendLine("Scan window: [$effectiveStart..$effectiveEnd] (size=$scanSize)")
                    appendLine("Frame budget: $maxContributingFrames")
                    appendLine("Frames selected: ${selectedIndices.size}")
                    appendLine("Frames accepted by alignment: $accepted")
                    appendLine("Selected frame indices: ${selectedIndices.joinToString(", ")}")
                    appendLine()
                    appendLine("=== Per-Frame Telemetry ===")
                    if (telemetryLines.isEmpty()) {
                        appendLine("(none captured)")
                    } else {
                        telemetryLines.forEach { appendLine(it) }
                    }
                })
                Log.i(TAG, "Saved log: ${logFile.absolutePath}")
            } catch (e: Exception) {
                Log.w(TAG, "Log file save failed (non-fatal)", e)
            }

            params.release()
            outputMat.release()
            System.gc()

            onProgress?.invoke(totalUnits, totalUnits, "Complete")
            return true

        } catch (e: Throwable) {
            Log.e("MFSR_DEBUG", "Pipeline completely failed!", e)
            return false
        } finally {
            if (enginePtr != 0L) MFSRNative.destroyEngine(enginePtr)
            frameSource.release()
        }
    }

    private fun buildProxyGray(frame: Mat): Mat {
        val proxy = Mat()
        Imgproc.resize(frame, proxy, Size(PROXY_WIDTH.toDouble(), PROXY_HEIGHT.toDouble()))
        val gray = Mat()
        Imgproc.cvtColor(proxy, gray, Imgproc.COLOR_RGB2GRAY)
        proxy.release()
        return gray
    }
}
