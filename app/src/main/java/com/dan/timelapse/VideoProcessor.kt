package com.dan.timelapse

import android.content.Context
import android.os.Environment
import android.util.Log
import com.dan.timelapse.filters.*
import com.dan.timelapse.video.VideoWriter
import com.dan.timelapse.images.ImageTools
import com.dan.timelapse.framesinput.ImagesFramesInput
import com.dan.timelapse.utils.HardwareUtils
import com.dan.timelapse.utils.DeviceTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import org.opencv.calib3d.Calib3d
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.core.Mat
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class VideoProcessor(
    private val model: MainViewModel,
    private val context: Context,
    private val width: Int,
    private val height: Int
) {
    var onProgress: ((Int, Int) -> Unit)? = null
    var onProgressWithStatus: ((Int, Int, String) -> Unit)? = null // (current, total, statusText)
    var onProgressWithETA: ((Int, Int, String, Long) -> Unit)? = null // (current, total, statusText, remainingTimeMillis)
    var onBusyStateChanged: ((Boolean) -> Unit)? = null

    // Single global watchdog (replaces per-frame Thread allocation to avoid pthread exhaustion)
    @Volatile private var lastFrameStartMs = 0L
    @Volatile private var lastFrameDebugInfo = ""
    @Volatile private var isRendering = false

    // Dual-Mode Stabilization Cache
    private val alignCache = AlignCache()
    
    // Padding frame for short videos
    private var lastSeenFrameForPadding: Mat? = null

    /**
     * Reset filter chain state for a clean workspace.
     * Called before and after each render to prevent stale state leaking between renders.
     */
    private fun resetFilterChain(framesConsumer: FramesConsumer?) {
        try {
            when (framesConsumer) {
                is MultiFramesFilter -> framesConsumer.reset()
                is EndlessMedianFramesFilter -> framesConsumer.reset()
                is EndlessAverageFramesFilter -> framesConsumer.reset()
                is EndlessAddFramesFilter -> framesConsumer.reset()
                is EndlessCumulativeMotionFramesFilter -> framesConsumer.reset()
                is EndlessLightestPixelsFramesFilter -> framesConsumer.reset()
                is EndlessDarkestPixelsFramesFilter -> framesConsumer.reset()
                is EndlessAverageWeightedForLastFramesFilter -> framesConsumer.reset()
                is EndlessAverageWeightedForLightFramesFilter -> framesConsumer.reset()
            }
        } catch (_: Exception) {
            // Some filters like VideoWriter don't have reset() - that's fine
        }
    }

    /**
     * Internal cache for stabilization data to prevent OOM and drift.
     */
    private class AlignCache {
        var referenceGrayFrame = Mat()
        var referenceFramePts = MatOfPoint2f()
        val alignmentMatrices = mutableMapOf<Int, Mat>()
        
        // Per-frame raw transforms (absolute for first-frame mode, delta for sequential)
        val rawTransforms = mutableListOf<TransformData>()
        
        // Absolute cumulative trajectory used for both alignment modes
        val absoluteTrajectory = mutableListOf<TransformData>()
        var smoothedTrajectory = mutableListOf<TransformData>()
        
        // Last known good transform — fallback when tracking fails (Fix 1)
        var lastSuccessfulTransform = TransformData()

        fun resetAll() {
            referenceGrayFrame.release()
            referenceGrayFrame = Mat()
            referenceFramePts.release()
            referenceFramePts = MatOfPoint2f()
            alignmentMatrices.values.forEach { it.release() }
            alignmentMatrices.clear()
            rawTransforms.clear()
            absoluteTrajectory.clear()
            smoothedTrajectory.clear()
            lastSuccessfulTransform = TransformData()
        }
    }
    
    private data class TransformData(
        val dx: Double = 0.0,
        val dy: Double = 0.0,
        val da: Double = 0.0, // angle
        val scale: Double = 1.0
    )

    private fun applyEffects(frame: Mat): Mat {
        var processedFrame = frame.clone() // Take ownership from provider

        // Get TransformState for rotation and aspect ratio
        val transformState = model.transformState.value ?: MainViewModel.TransformState()
        val rotation = transformState.rotation
        val aspectRatio = transformState.getAspectRatio()
        
        // Step 1: Apply rotation using Core.rotate
        if (rotation > 0) {
            val rotated = Mat()
            val rotationCode = when (rotation) {
                90 -> Core.ROTATE_90_CLOCKWISE
                180 -> Core.ROTATE_180
                270 -> Core.ROTATE_90_COUNTERCLOCKWISE
                else -> -1
            }
            if (rotationCode != -1) {
                Core.rotate(processedFrame, rotated, rotationCode)
                processedFrame.release()
                processedFrame = rotated
            }
        }
        
        // Step 2: Apply aspect ratio crop (center-crop) if specified
        if (aspectRatio != null && aspectRatio > 0) {
            val cropped = applyCenterCrop(processedFrame, aspectRatio)
            if (cropped.nativeObj != processedFrame.nativeObj) {
                processedFrame.release()
                processedFrame = cropped
            }
        }
        
        return processedFrame
    }
    
    /**
     * Apply center-crop to achieve target aspect ratio.
     * Maintains maximum possible area while matching target ratio.
     */
    private fun applyCenterCrop(frame: Mat, targetRatio: Float): Mat {
        val srcWidth = frame.cols()
        val srcHeight = frame.rows()
        val srcRatio = srcWidth.toFloat() / srcHeight
        
        // Calculate crop dimensions
        val (cropWidth, cropHeight) = if (srcRatio > targetRatio) {
            // Source is wider than target - crop width
            val h = srcHeight
            val w = (h * targetRatio).toInt()
            Pair(w, h)
        } else {
            // Source is taller than target - crop height
            val w = srcWidth
            val h = (w / targetRatio).toInt()
            Pair(w, h)
        }
        
        // Ensure even dimensions for encoder compatibility
        val finalCropWidth = (cropWidth / 2) * 2
        val finalCropHeight = (cropHeight / 2) * 2
        
        // Calculate center position
        val x = ((srcWidth - finalCropWidth) / 2).coerceAtLeast(0)
        val y = ((srcHeight - finalCropHeight) / 2).coerceAtLeast(0)
        
        // Apply submat crop
        return if (finalCropWidth < srcWidth || finalCropHeight < srcHeight) {
            val cropRect = Rect(x, y, finalCropWidth, finalCropHeight)
            Mat(frame, cropRect)
        } else {
            // No crop needed, return as-is
            frame
        }
    }

    /**
     * Stabilization Engine: Supports both Global (First Frame) and Sequential (Previous Frame) alignment.
     * Implements trajectory smoothing to prevent jumpy stabilization.
     */
    private fun applyStabilization(frame: Mat, frameIndex: Int): Mat {
        // Guard against empty input
        if (frame.empty()) return frame
        
        val isStabilized = model.isStabilized.value ?: false
        if (!isStabilized) return frame

        val alignToFirst = model.alignToFirstFrame.value ?: true
        
        // G7-04: Adjustable smoothing strength from UI
        val windowSize = model.stabilizationSmoothing.value?.coerceIn(5, 100) ?: 30
        
        // Mask safety: wrap in try-catch, fall back to empty mask on failure
        var activeMask: Mat
        try {
            val originalMask = model.firstFrameMask.value
            if (originalMask != null && !originalMask.empty()) {
                activeMask = applyEffects(originalMask)
            } else {
                activeMask = Mat()
            }
        } catch (e: Exception) {
            android.util.Log.w("Stabilization", "Mask initialization failed, using empty mask", e)
            activeMask = Mat()
        }

        // Frame 0: Initialize reference frame and reset trajectory
        if (frameIndex == 0) {
            alignCache.rawTransforms.clear()
            alignCache.smoothedTrajectory.clear()
            alignCache.absoluteTrajectory.clear()

            val gray = Mat()
            Imgproc.cvtColor(frame, gray, Imgproc.COLOR_RGB2GRAY)

            val pts = MatOfPoint()
            Imgproc.goodFeaturesToTrack(gray, pts, 500, 0.005, 20.0, if (activeMask.empty()) Mat() else activeMask)

            if (!pts.empty()) {
                val pts2f = MatOfPoint2f()
                pts.convertTo(pts2f, CvType.CV_32FC2)
                alignCache.referenceFramePts.release()
                alignCache.referenceFramePts = pts2f
                alignCache.referenceGrayFrame.release()
                gray.copyTo(alignCache.referenceGrayFrame)
            }
            gray.release()
            pts.release()

            val identity = TransformData(0.0, 0.0, 0.0, 1.0)
            alignCache.rawTransforms.add(identity)
            alignCache.absoluteTrajectory.add(identity)
            alignCache.smoothedTrajectory.add(identity)
            alignCache.lastSuccessfulTransform = identity

            return fixBorder(frame)
        }

        if (alignCache.referenceFramePts.empty()) {
            return fixBorder(frame)
        }

        if (alignCache.referenceGrayFrame.empty()) {
            android.util.Log.w("VideoProcessor", "Reference frame missing for preview. Skipping stabilization.")
            return fixBorder(frame)
        }

        val currentGray = Mat()
        Imgproc.cvtColor(frame, currentGray, Imgproc.COLOR_RGB2GRAY)

        val status = MatOfByte()
        val err = MatOfFloat()
        val nextPts = MatOfPoint2f()

        // 1. ADAPTIVE RESOLUTION SCALING
        val resFactor = frame.cols() / 1920.0

        // Dynamic window size: larger for higher resolutions. Must be odd.
        var lkWinSize = (25 * resFactor).toInt().coerceAtLeast(15)
        if (lkWinSize % 2 == 0) lkWinSize += 1

        // 2. ULTRA-PRECISE SUB-PIXEL OPTICAL FLOW
        val criteria = TermCriteria(TermCriteria.EPS or TermCriteria.MAX_ITER, 50, 0.005)

        try {
            Video.calcOpticalFlowPyrLK(
                alignCache.referenceGrayFrame, currentGray,
                alignCache.referenceFramePts, nextPts,
                status, err,
                Size(lkWinSize.toDouble(), lkWinSize.toDouble()),
                4,
                criteria, 0, 1e-4
            )
        } catch (e: Exception) {
            android.util.Log.w("Stabilization", "calcOpticalFlowPyrLK failed at frame $frameIndex", e)
        }

        val statusArr = status.toArray()
        val prevPtsArr = alignCache.referenceFramePts.toList()
        val nextPtsArr = nextPts.toList()

        val goodPrev = mutableListOf<Point>()
        val goodNext = mutableListOf<Point>()

        for (i in statusArr.indices) {
            if (statusArr[i].toInt() == 1) {
                goodPrev.add(prevPtsArr[i])
                goodNext.add(nextPtsArr[i])
            }
        }

        var stabilizedFrame = frame.clone()

        if (goodPrev.size >= 4) {
            val prevMat = MatOfPoint2f(*goodPrev.toTypedArray())
            val nextMat = MatOfPoint2f(*goodNext.toTypedArray())
            val inliers = Mat()

            // 3. ADAPTIVE RANSAC
            val dynamicRansac = 1.5 * resFactor
            val transformMat = try {
                Calib3d.estimateAffinePartial2D(nextMat, prevMat, inliers, Calib3d.RANSAC, dynamicRansac)
            } catch (e: Exception) {
                android.util.Log.w("Stabilization", "estimateAffinePartial2D failed at frame $frameIndex", e)
                inliers.release()
                Mat()
            }

            if (!transformMat.empty()) {
                val m00 = transformMat.get(0, 0)[0]
                val m10 = transformMat.get(1, 0)[0]
                val m01 = transformMat.get(0, 1)[0]
                val m11 = transformMat.get(1, 1)[0]
                val rawDx = transformMat.get(0, 2)[0]
                val rawDy = transformMat.get(1, 2)[0]

                val rawDa = kotlin.math.atan2(m10, m00)

                // FIX 1: The Pivot Shift.
                // Convert OpenCV's (0,0) origin translation to a Center-Based translation.
                // This mathematically prevents the video from swinging out of frame when rotation is applied.
                val cx = frame.cols() / 2.0
                val cy = frame.rows() / 2.0
                val centerDx = rawDx + m00 * cx + m01 * cy - cx
                val centerDy = rawDy + m10 * cx + m11 * cy - cy

                val hasMask = !activeMask.empty() && Core.countNonZero(activeMask) > 0

                // FIX 2: Fully restore rotation! We no longer need to force da = 0.0
                val safeData = TransformData(
                    dx = centerDx,
                    dy = centerDy,
                    da = rawDa,
                    scale = 1.0
                )

                // --- Outlier Rejection ---
                val maxJump = frame.cols() * 0.1
                val finalData = if (kotlin.math.abs(safeData.dx) > maxJump || kotlin.math.abs(safeData.dy) > maxJump) {
                    alignCache.lastSuccessfulTransform
                } else {
                    safeData
                }

                alignCache.lastSuccessfulTransform = finalData
                alignCache.rawTransforms.add(finalData)

                // --- Absolute Trajectory Accumulation ---
                val prevAbs = alignCache.absoluteTrajectory.last()
                val absData = TransformData(
                    dx = prevAbs.dx + finalData.dx,
                    dy = prevAbs.dy + finalData.dy,
                    da = normalizeAngle(prevAbs.da + finalData.da),
                    scale = 1.0
                )
                alignCache.absoluteTrajectory.add(absData)

                // FIX 3: Force Anchor Mode for Masks / Tripod / Short Burst.
                // If a mask is drawn, or tripod (minimal motion), or short burst (<30 frames), force anchor.
                val totalFrames = model.framesInput.value?.size ?: 100
                val isShortBurst = totalFrames < 30
                
                // Tripod detection: if accumulated trajectory magnitude is very small
                var isTripod = false
                if (alignCache.absoluteTrajectory.size > 1) {
                    val lastAbs = alignCache.absoluteTrajectory.last()
                    val trajectoryMag = kotlin.math.hypot(lastAbs.dx, lastAbs.dy)
                    isTripod = trajectoryMag < 5.0 // pixels of total drift
                }
                
                val isAnchorMode = alignToFirst || hasMask || isTripod || isShortBurst

                val correction = if (isAnchorMode) {
                    // Anchor Mode: Pin the pixels exactly to the reference frame
                    TransformData(dx = absData.dx, dy = absData.dy, da = absData.da, scale = 1.0)
                } else {
                    // Smooth Mode: Calculate moving average for handheld panning
                    val smoothed = calculateSmoothedTrajectory(
                        alignCache.absoluteTrajectory,
                        alignCache.absoluteTrajectory.lastIndex,
                        windowSize
                    )
                    alignCache.smoothedTrajectory.add(smoothed)

                    TransformData(
                        dx = smoothed.dx,
                        dy = smoothed.dy,
                        da = smoothed.da,
                        scale = 1.0
                    )
                }

                val maxShift = frame.cols() * 0.15
                val clampedCorrection = TransformData(
                    dx = correction.dx.coerceIn(-maxShift, maxShift),
                    dy = correction.dy.coerceIn(-maxShift, maxShift),
                    da = correction.da,
                    scale = correction.scale
                )

                val stabilized = applyTransformWithClampedScale(frame, clampedCorrection)
                stabilizedFrame.release()
                stabilizedFrame = stabilized
            } else {
                alignCache.rawTransforms.add(TransformData(0.0, 0.0, 0.0, 1.0))
                val prevAbs = alignCache.absoluteTrajectory.last()
                alignCache.absoluteTrajectory.add(prevAbs)
                alignCache.smoothedTrajectory.add(prevAbs)
            }

            inliers.release()
            prevMat.release()
            nextMat.release()
            transformMat.release()
        } else {
            alignCache.rawTransforms.add(TransformData(0.0, 0.0, 0.0, 1.0))
            val prevAbs = alignCache.absoluteTrajectory.last()
            alignCache.absoluteTrajectory.add(prevAbs)
            alignCache.smoothedTrajectory.add(prevAbs)
        }

        currentGray.release()
        status.release()
        err.release()
        nextPts.release()

        // ALWAYS track against the RAW frame for the next iteration. 
        // This prevents warping/cropping artifacts from ruining the optical flow.
        val gray = Mat()
        Imgproc.cvtColor(frame, gray, Imgproc.COLOR_RGB2GRAY)
        val pts = MatOfPoint()
        Imgproc.goodFeaturesToTrack(gray, pts, 500, 0.005, 20.0, activeMask)

        if (!pts.empty()) {
            val pts2f = MatOfPoint2f()
            pts.convertTo(pts2f, CvType.CV_32FC2)
            alignCache.referenceFramePts.release()
            alignCache.referenceFramePts = pts2f
            alignCache.referenceGrayFrame.release()
            gray.copyTo(alignCache.referenceGrayFrame)
        }
        gray.release()
        pts.release()
        
        // Prevent native memory leak from the mask clone
        activeMask.release()

        return fixBorder(stabilizedFrame)
    }

    private fun normalizeAngle(angle: Double): Double {
        return kotlin.math.atan2(kotlin.math.sin(angle), kotlin.math.cos(angle))
    }
    
    /**
     * Calculate smoothed trajectory using Gaussian-weighted moving average.
     * Gaussian weighting produces smoother motion than a flat average — nearby frames
     * contribute more than far-away frames, which reduces the "sudden unlock" artifact
     * you get when a transient jitter enters or leaves a flat-window average.
     *
     * This is a close approximation of L1-optimal trajectory smoothing for the translation
     * component, without the complexity of a full LP solve.
     */
    private fun calculateSmoothedTrajectory(transforms: List<TransformData>, currentIndex: Int, windowSize: Int): TransformData {
        if (transforms.isEmpty()) return TransformData(0.0, 0.0, 0.0, 1.0)

        val start = (currentIndex - windowSize + 1).coerceAtLeast(0)
        val end = currentIndex.coerceAtMost(transforms.lastIndex)

        if (start >= end) return transforms[currentIndex]

        // Gaussian sigma = windowSize / 3 (standard choice: 3σ covers the window)
        val sigma = windowSize / 3.0
        val twoSigmaSq = 2.0 * sigma * sigma

        var sumDx = 0.0
        var sumDy = 0.0
        var sumDa = 0.0
        var sumScale = 0.0
        var sumWeight = 0.0

        for (i in start..end) {
            val t = transforms[i]
            val distance = (i - currentIndex).toDouble()
            val weight = kotlin.math.exp(-(distance * distance) / twoSigmaSq)

            sumDx += t.dx * weight
            sumDy += t.dy * weight
            sumDa += t.da * weight
            sumScale += t.scale * weight
            sumWeight += weight
        }

        if (sumWeight < 1e-9) return transforms[currentIndex]

        return TransformData(
            sumDx / sumWeight,
            sumDy / sumWeight,
            sumDa / sumWeight,
            sumScale / sumWeight
        )
    }
    
    /**
     * Apply transform with scale definitively locked to 1.0 (no zoom breathing).
     * The only zoom applied is userZoom from the UI slider.
     * BORDER_CONSTANT (black) handles edge pixels from pan/crop.
     */
    private fun applyTransformWithClampedScale(frame: Mat, transform: TransformData): Mat {
        val m = Mat(2, 3, CvType.CV_64F)
        val cos = kotlin.math.cos(transform.da) * transform.scale
        val sin = kotlin.math.sin(transform.da) * transform.scale

        val userZoom = (model.stabilizationZoom.value ?: 1.0f).toDouble()
        val effectiveScale = 1.0 * userZoom

        val maxShiftX = frame.width() * 0.15
        val maxShiftY = frame.height() * 0.15
        val clampedDx = transform.dx.coerceIn(-maxShiftX, maxShiftX)
        val clampedDy = transform.dy.coerceIn(-maxShiftY, maxShiftY)

        m.put(0, 0, cos * effectiveScale, -sin * effectiveScale, clampedDx)
        m.put(1, 0, sin * effectiveScale, cos * effectiveScale, clampedDy)

        val result = Mat()
        Imgproc.warpAffine(frame, result, m, frame.size(), Imgproc.INTER_LANCZOS4 + Imgproc.WARP_FILL_OUTLIERS, Core.BORDER_CONSTANT, Scalar(0.0, 0.0, 0.0))

        m.release()
        return result
    }

    /**
     * Border fix: the 1.15x zoom built into applyTransformWithClampedScale (Fix 3)
     * handles black-border bleeding. This function is retained as a pass-through for
     * frame 0 and non-stabilized paths.
     */
    private fun fixBorder(frame: Mat): Mat {
        return frame
    }
    
    fun startRendering(outputPath: String): Boolean {
        // FIX: Acquire a Partial WakeLock so the CPU doesn't sleep when switching apps
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "TimeLapse::VideoRender")
        wakeLock.acquire(60 * 60 * 1000L) // Max 1 hour
        
        val input = model.framesInput.value ?: return false
        if (input.size == 0) {
            Log.e("VideoProcessor", "No frames to process")
            return false
        }
        var framesConsumer: FramesConsumer? = null

        try {
            val trimRange = model.trimRange.value ?: Pair(1f, input.size.toFloat())

            // THE FIX: Perfect translation of trim state to frame indices, bypassing millisecond drift
            val startFrame = (trimRange.first.toInt() - 1).coerceAtLeast(0)
            val endFrame = (trimRange.second.toInt() - 1).coerceAtMost(input.size - 1)

            val actualStartFrame = startFrame.coerceAtMost(endFrame)
            val actualEndFrame = endFrame.coerceAtMost(input.size - 1)

            // Get TransformState for rotation and aspect ratio
            val transformState = model.transformState.value ?: MainViewModel.TransformState()
            val rotation = transformState.rotation
            val aspectRatio = transformState.getAspectRatio()
            
            Log.d("VideoProcessor", "Render params: startFrame=$actualStartFrame, endFrame=$actualEndFrame, total frames=${actualEndFrame - actualStartFrame + 1}")
            Log.d("VideoProcessor", "Render params: output dims=${width}x${height}, rotation=$rotation, aspectRatio=$aspectRatio")
            
            // 1. Remove double-swap. width and height are already correctly oriented by MainViewModel
            var outW = width
            var outH = height

            // 2. Adjust output dimensions to match the requested Aspect Ratio Crop
            if (aspectRatio != null && aspectRatio > 0f) {
                val srcRatio = outW.toFloat() / outH.toFloat()
                if (srcRatio > aspectRatio) {
                    // Source is wider than target -> crop width
                    outW = (outH * aspectRatio).toInt()
                } else {
                    // Source is taller than target -> crop height
                    outH = (outW / aspectRatio).toInt()
                }
            }

            // 3. Mandatory Even Dimension Sanitization for Hardware Encoders
            val finalWidth = (outW / 2) * 2
            val finalHeight = (outH / 2) * 2

            // Use the user's selected preview FPS for export
            val outFps = (model.previewFps.value ?: 30).coerceAtLeast(1)

            // Check if source is images (RGB from Android) vs video (BGR from OpenCV)
            val sourceIsImages = input is ImagesFramesInput
            val writer = VideoWriter(outputPath, outFps, model.encodingH265.value ?: false, finalWidth, finalHeight, sourceIsImages)

            val baseEffect = model.visualEffect.value ?: Effect.NONE
            val isEndless = model.isEndless.value ?: false
            // CONVERT TO ENDLESS HERE BEFORE THE WHEN BLOCK
            val effect = if (isEndless) baseEffect.toEndless() else baseEffect
            val frameBlending = model.frameBlending.value ?: 10
            
            // FIX 4: Get skipFrames value (default 1 = process all frames)
            val skipFrames = model.skipFrames.value ?: 1
            Log.d("VideoProcessor", "Skip frames: $skipFrames")
            
            // Calculate total frames to process (needed for filter initialization)
            val totalFramesToProcess = kotlin.math.ceil((actualEndFrame - actualStartFrame + 1).toDouble() / skipFrames).toInt()
            
            // Push immediately so the UI shows "initializing..." instead of dead air
            onProgressWithStatus?.invoke(0, totalFramesToProcess, "Allocating buffers...")
            
            Log.d("VideoProcessor", "Using effect: $effect, isEndless: $isEndless, frameBlending: $frameBlending")

            framesConsumer = when (effect) {
                Effect.ENDLESS_MEDIAN_STACK -> {
                    val filter = EndlessMedianFramesFilter(context, height >= 2160, writer)
                    filter.onBusyStateChanged = onBusyStateChanged
                    filter.onProgressWithStatus = onProgressWithStatus
                    filter.totalInputFrames = totalFramesToProcess // DIRECT ACCESS. NO REFLECTION.
                    filter
                }
                Effect.ENDLESS_AVERAGE -> EndlessAverageFramesFilter(writer)
                Effect.ENDLESS_AVERAGE_WEIGHTED_FOR_LAST -> EndlessAverageWeightedForLastFramesFilter(writer)
                Effect.ENDLESS_AVERAGE_WEIGHTED_FOR_LIGHT -> EndlessAverageWeightedForLightFramesFilter(writer)
                Effect.ENDLESS_LIGHTEST_PIXELS -> EndlessLightestPixelsFramesFilter(writer)
                Effect.ENDLESS_ADD -> EndlessAddFramesFilter(writer)
                Effect.ENDLESS_DARKEST_PIXELS -> EndlessDarkestPixelsFramesFilter(writer)
                Effect.ENDLESS_MOTION_EXTRACTION -> EndlessCumulativeMotionFramesFilter(writer)

                Effect.AVERAGE -> AverageFramesFilter(frameBlending, writer)
                Effect.AVERAGE_WEIGHTED_FOR_LAST -> AverageWeightedForLastFramesFilter(frameBlending, writer)
                Effect.AVERAGE_WEIGHTED_FOR_LIGHT -> AverageWeightedForLightFramesFilter(frameBlending, writer)
                Effect.LIGHTEST_PIXELS -> LightestPixelsFramesFilter(frameBlending, writer)
                Effect.ADD -> AddFramesFilter(frameBlending, writer)
                Effect.HDR -> HDRFramesFilter(frameBlending, writer)
                Effect.TRANSITION -> TransitionFramesFilter(frameBlending, writer)
                Effect.DARKEST_PIXELS -> DarkestPixelsFramesFilter(frameBlending, writer)
                Effect.MOTION_EXTRACTION -> MotionExtractionWindowFramesFilter(frameBlending, writer)
                Effect.MEDIAN_STACK -> MedianFramesFilter(frameBlending, true, writer)
                else -> writer
            }

            // HARD RESET: Flush stale state from previous video renders
            alignCache.resetAll()
            
            // Reset filter chain state for a clean workspace (new instance per render, safety reset)
            resetFilterChain(framesConsumer)

            framesConsumer.start()

            // FIX: Calculate frame multiplier to ensure minimum 1 second duration
            // If output would be shorter than 1 second, duplicate frames to reach target FPS
            var framesProcessed = 0
            val totalFrames = totalFramesToProcess
            var encodedFrameCount = 0 
            
            var lastProgressUpdateTime = 0L
            val progressThrottleMs = 250L 
            
            // Capture baseline temperature
            val thermalFilter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            val initialTemp = context.registerReceiver(null, thermalFilter)?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            // Throttle only if 3°C above baseline, or hard-cap at 45°C
            val thermalThreshold = maxOf(420, initialTemp + 30)
            
            // ETA tracking using Exponential Moving Average (EMA)
            // EMA adapts to thermal throttling and varying frame complexity
            // without wild swings from single-frame outliers.
            val WARMUP_FRAMES = 5 // Don't show ETA until this many frames processed
            val EMA_ALPHA = 0.15  // Smoothing factor: 0.15 = recent frames weighted ~15%, history ~85%
            var emaMsPerFrame = 0.0 // Smoothed average ms per frame
            var lastFrameEndTime = 0L // Timestamp when previous frame finished
            var firstFrameStartTime = 0L // When the first frame actually starts processing
            var frameStartTime = System.currentTimeMillis() // Per-frame start for watchdog
            
            lastSeenFrameForPadding?.release()
            lastSeenFrameForPadding = null

            // Start single global watchdog thread (replaces per-frame Thread allocation)
            isRendering = true
            lastFrameStartMs = System.currentTimeMillis()
            lastFrameDebugInfo = "initializing"
            val frameWatchdogThread = Thread({
                while (isRendering) {
                    try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                    if (System.currentTimeMillis() - lastFrameStartMs > 30_000) {
                        android.util.Log.e("VideoProcessor", "FRAME HANG DETECTED. lastFrameDebugInfo=$lastFrameDebugInfo")
                        android.util.Log.e("VideoProcessor", "Thread dump: " +
                            Thread.getAllStackTraces().entries.joinToString("\n") { (t, ste) ->
                                "${t.name} (${t.state}): ${ste.take(5).joinToString(" <- ") { it.toString() }}"
                            })
                    }
                }
            }, "FrameWatchdog")
            frameWatchdogThread.isDaemon = true
            frameWatchdogThread.start()

            input.forEachFrame(start = actualStartFrame, progress = { current, total ->
                if (current % 30 == 0) Log.d("VideoProcessor", "Processing frame $current of $total")
            }) { index, total, frame ->
                Log.d("VideoProcessor", "RENDER FRAME: index=$index, total=$total, framesProcessed=$framesProcessed, encodedCount=$encodedFrameCount")
                // Declare OUTSIDE try block so finally can see them
                var processedFrame: Mat? = null
                var currentFrame: Mat? = null
                var finalFrame: Mat? = null

                // THE FIX: Check cancellation at the START of each frame iteration (live check)
                if (model.isProcessingCancelled.value == true) {
                    Log.d("VideoProcessor", "Render cancelled at frame $index")
                    return@forEachFrame false
                }
                
                // Sync to framesProcessed to prevent progress bar jumping
                if (framesProcessed % 5 == 0 && framesProcessed > 0) {
                    val batteryStatus = context.registerReceiver(null, thermalFilter)
                    val temp = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
                    
                    if (temp >= thermalThreshold || temp >= 450) {
                        val isPlugged = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, -1) ?: -1 != 0
                        val currentTempStr = "${temp / 10.0}°C"
                        val warningMsg = if (isPlugged) "Charging & Throttling ($currentTempStr)..." else "Cooling down ($currentTempStr)..."
                        
                        // THE FIX: Use framesProcessed instead of raw index
                        onProgressWithStatus?.invoke(framesProcessed, totalFramesToProcess, warningMsg)
                        
                        try { Thread.sleep(if (isPlugged) 1000L else 500L) } catch (_: InterruptedException) {}
                    }
                }
                
                if (index > actualEndFrame) return@forEachFrame false

                // THE FIX: Stacking filters (Darken, Average, Lightest, etc.) MUST ingest 100%
                // of the frames to blend them properly. Only skip frames for Effect.NONE.
                val isStackingEffect = effect != Effect.NONE
                
                if (isStackingEffect) {
                    // Feed ALL frames to stacking filters - don't skip
                } else if (skipFrames > 1 && index % skipFrames != 0) {
                    // Traditional Timelapse (Effect.NONE): Drop frames to speed up time
                    frame.release() // Free the memory instantly
                    return@forEachFrame true // Continue to next frame WITHOUT processing
                }
                
                frameStartTime = System.currentTimeMillis()
                lastFrameStartMs = frameStartTime
                lastFrameDebugInfo = "index=$index, framesProcessed=$framesProcessed, encodedCount=$encodedFrameCount"
                try {
                    if (!frame.empty()) {
                        // THE FIX: "None" Filter Color/Contrast Bypass
                        val isStabilized = model.isStabilized.value == true
                        val transform = model.transformState.value
                        val hasTransform = (transform?.rotation ?: 0) > 0 || (transform?.getAspectRatio() ?: 0f) > 0f
                        
                        if (effect == Effect.NONE && !isStabilized && !hasTransform && frame.cols() == finalWidth && frame.rows() == finalHeight) {
                            // 100% Pure Bypass: Trim only. No rotation, no crop, no stabilization. 
                            // Clones raw frame directly to encoder to guarantee 0% color shift.
                            finalFrame = frame.clone()
                        } else {
                            // 1. Effects (Rotation + Aspect Ratio Crop)
                            processedFrame = applyEffects(frame)

                            // 2. Stabilization
                            val stabilized = applyStabilization(processedFrame!!, index - actualStartFrame)
                            if (stabilized.nativeObj != processedFrame!!.nativeObj) {
                                processedFrame!!.release()
                            }
                            processedFrame = stabilized

                            // 3. Final Resize 
                            // For "None" filter + Crop: If dimensions perfectly match after crop, SKIP Imgproc.resize
                            // This stops OpenCV's interpolation from subtly altering the contrast.
                            if (effect == Effect.NONE && processedFrame!!.cols() == finalWidth && processedFrame!!.rows() == finalHeight) {
                                finalFrame = processedFrame!!.clone()
                            } else {
                                finalFrame = Mat()
                                Imgproc.resize(processedFrame, finalFrame, Size(finalWidth.toDouble(), finalHeight.toDouble()))
                            }
                            
                            processedFrame!!.release()
                            processedFrame = null
                        }

                        Log.d("VideoProcessor", "FEEDING FRAME to consumer: encodedCount=$encodedFrameCount")
                        framesConsumer?.consume(encodedFrameCount, finalFrame!!)
                        Log.d("VideoProcessor", "FRAME CONSUMED: encodedCount=$encodedFrameCount")
                        // Save a safe clone of the very last frame in case we need to pad the video
                        lastSeenFrameForPadding?.release()
                        lastSeenFrameForPadding = finalFrame!!.clone()
                        
                        encodedFrameCount++
                        finalFrame!!.release()
                        finalFrame = null
                        
                        framesProcessed++
                        // Throttled progress update with EMA-based ETA
                        val currentTime = System.currentTimeMillis()
                        
                        // Track per-frame time for EMA
                        if (framesProcessed == 1) {
                            firstFrameStartTime = currentTime // Mark actual processing start
                            lastFrameEndTime = currentTime
                        } else {
                            val frameDuration = currentTime - lastFrameEndTime
                            lastFrameEndTime = currentTime
                            
                            if (framesProcessed <= WARMUP_FRAMES) {
                                // During warmup: use simple average of all frames so far
                                // (excludes frame 1 which has init overhead)
                                val elapsed = currentTime - firstFrameStartTime
                                emaMsPerFrame = elapsed.toDouble() / (framesProcessed - 1)
                            } else {
                                // After warmup: apply EMA to adapt to changing conditions
                                emaMsPerFrame = EMA_ALPHA * frameDuration + (1 - EMA_ALPHA) * emaMsPerFrame
                            }
                        }
                        
                        if (currentTime - lastProgressUpdateTime > progressThrottleMs || framesProcessed == totalFrames) {
                            val remainingFrames = totalFrames - framesProcessed
                            val remainingTimeMillis = if (framesProcessed >= WARMUP_FRAMES && emaMsPerFrame > 0) {
                                (emaMsPerFrame * remainingFrames).toLong()
                            } else {
                                -1L // Signal: still calculating
                            }
                            
                            if (effect == Effect.ENDLESS_MEDIAN_STACK && (framesConsumer as? EndlessMedianFramesFilter)?.isTilingMode == true) {
                                // PASS 1 (Tiling): Scale 0 to 100 -> 0 to 50
                                val scaledProgress = ((framesProcessed.toFloat() / totalFrames) * 50).toInt()
                                onProgressWithStatus?.invoke(scaledProgress, 100, "Analyzing Frames...")
                                onProgressWithETA?.invoke(scaledProgress, 100, "Analyzing Frames...", remainingTimeMillis)
                            } else {
                                // STANDARD RENDER
                                onProgress?.invoke(framesProcessed, totalFrames)
                                val progressText = "Applying effects ($framesProcessed of $totalFrames frames)"
                                onProgressWithStatus?.invoke(framesProcessed, totalFrames, progressText)
                                onProgressWithETA?.invoke(framesProcessed, totalFrames, progressText, remainingTimeMillis)
                            }
                            
                            lastProgressUpdateTime = currentTime
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("VideoProcessor", "Frame processing failed at index $index", e)
                    throw e // Re-throw to let the caller handle cancellation
                } finally {
                    // FIX: Guarantee cleanup of processedFrame, currentFrame, and finalFrame even if exception occurs
                    // The input frame is managed by the provider, don't release it here
                    try {
                        processedFrame?.release()
                    } catch (_: Exception) {}
                    try {
                        currentFrame?.release()
                    } catch (_: Exception) {}
                    try {
                        finalFrame?.release()
                    } catch (_: Exception) {}
                }
                index < total
            }

            // 5. Pad short videos to at least 1 second to prevent MediaMuxer Error -38
            // REMOVED: Padding to fixed length causes trailing audio issues
            // Now using actual trimmed frame count for output duration

            alignCache.resetAll()
            
            // Reset filter chain state for a clean workspace
            resetFilterChain(framesConsumer)
            
            // 1. Flush the encoder to disk BEFORE retiming
            try { framesConsumer?.stop(false) } catch(e: Exception) {}
            framesConsumer = null // Prevent finally block from running it again
            
            // Check cancellation before retiming (live check)
            if (model.isProcessingCancelled.value == true) {
                Log.d("VideoProcessor", "Render cancelled. Finalizing partial video...")
                // THE FIX: Do NOT delete the file. We want to keep the partially rendered video.
                // However, we must ensure we only retime the frames we actually processed.
                if (encodedFrameCount == 0) {
                    java.io.File(outputPath).delete()
                    return false
                }
                // Continue downward to retiming so the MOOV atom is properly adjusted 
                // for the partial frame count, allowing the user to play what was rendered.
            }
            
            // 2. TIMESTAMPS OVERRIDE: The Canvas encoder writes timestamps based on CPU wall-clock time.
            // We must strictly retime the file so it plays at a perfect target FPS regardless of render time.
            try {
                val tempPath = "$outputPath.tmp"
                val originalFile = java.io.File(outputPath)
                
                if (originalFile.exists() && originalFile.length() > 0) {
                    originalFile.renameTo(java.io.File(tempPath))
                    
                    val extractor = android.media.MediaExtractor()
                    extractor.setDataSource(tempPath)
                    var videoTrackIndex = -1
                    
                    for (i in 0 until extractor.trackCount) {
                        if (extractor.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                            videoTrackIndex = i
                            break
                        }
                    }
                    
                    if (videoTrackIndex >= 0) {
                        extractor.selectTrack(videoTrackIndex)
                        val format = extractor.getTrackFormat(videoTrackIndex)
                        
                        val muxer = android.media.MediaMuxer(outputPath, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                        val outTrack = muxer.addTrack(format)
                        muxer.start()
                        
                        // Buffer size 10MB to safely hold 4K frames
                        val rawBufferSize = try { format.getInteger(android.media.MediaFormat.KEY_MAX_INPUT_SIZE) } catch(e: Exception) { 0 }
                        val safeBufferSize = if (rawBufferSize <= 0) 1024 * 1024 * 10 else rawBufferSize
                        
                        val buffer = java.nio.ByteBuffer.allocateDirect(safeBufferSize)
                        val bufferInfo = android.media.MediaCodec.BufferInfo()
                        
                        var newFrameIndex = 0L
                        val frameDurationUs = 1000000L / outFps
                        
                        // THE FIX: Clamp to actual encoded frame count (trimmed range)
                        val targetFrameCount = encodedFrameCount
                        
                        while (newFrameIndex < targetFrameCount) {
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) break
                            
                            bufferInfo.size = size
                            bufferInfo.offset = 0
                            bufferInfo.flags = extractor.sampleFlags
                            // FORCE PERFECT TIMESTAMPS
                            bufferInfo.presentationTimeUs = newFrameIndex * frameDurationUs
                            
                            muxer.writeSampleData(outTrack, buffer, bufferInfo)
                            
                            newFrameIndex++
                            extractor.advance()
                        }
                        
                        muxer.stop()
                        muxer.release()
                        extractor.release()
                        java.io.File(tempPath).delete()
                        Log.d("VideoProcessor", "Successfully retimed video to $outFps FPS with $targetFrameCount frames")
                    } else {
                        extractor.release()
                        java.io.File(tempPath).renameTo(originalFile) // Revert if no video track
                    }
                }
            } catch (e: Exception) {
                Log.e("VideoProcessor", "Retiming failed", e)
                // If it fails, try to recover original file
                val tempFile = java.io.File("$outputPath.tmp")
                if (tempFile.exists()) tempFile.renameTo(java.io.File(outputPath))
            }
            
            return true
        } catch(e: Exception) {
            Log.e("VideoProcessor", "Render failed", e)
            return false
        } catch(e: Throwable) {
            Log.e("VideoProcessor", "Render failed with Throwable", e)
            return false
        } finally {
            isRendering = false
            try {
                framesConsumer?.stop(false)
            } catch (e: Exception) {
                Log.e("VideoProcessor", "Error stopping framesConsumer in finally", e)
            }
            
            lastSeenFrameForPadding?.release()
            lastSeenFrameForPadding = null
            
            // FIX: Release the WakeLock when rendering finishes
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    suspend fun renderHighResSnapshot(targetFrameIndex: Int, outputFile: java.io.File): Boolean {
        val input = model.framesInput.value ?: return false
        var capturedMat: org.opencv.core.Mat? = null

        try {
            val snapshotConsumer = object : FramesConsumer {
                override fun start() {}
                override fun consume(index: Int, frame: org.opencv.core.Mat) {
                    if (capturedMat == null) capturedMat = org.opencv.core.Mat()
                    frame.copyTo(capturedMat)
                }
                override fun stop(canceled: Boolean) {}
                override fun getWindowSize(): Int = 0
            }

            val baseEffect = model.visualEffect.value ?: Effect.NONE
            val isEndless = model.isEndless.value == true
            val effect = if (isEndless) baseEffect.toEndless() else baseEffect
            val frameBlending = model.frameBlending.value ?: 10

            val framesConsumer = when (effect) {
                Effect.ENDLESS_AVERAGE -> EndlessAverageFramesFilter(snapshotConsumer)
                Effect.ENDLESS_AVERAGE_WEIGHTED_FOR_LAST -> EndlessAverageWeightedForLastFramesFilter(snapshotConsumer)
                Effect.ENDLESS_AVERAGE_WEIGHTED_FOR_LIGHT -> EndlessAverageWeightedForLightFramesFilter(snapshotConsumer)
                Effect.ENDLESS_LIGHTEST_PIXELS -> EndlessLightestPixelsFramesFilter(snapshotConsumer)
                Effect.ENDLESS_ADD -> EndlessAddFramesFilter(snapshotConsumer)
                Effect.ENDLESS_DARKEST_PIXELS -> EndlessDarkestPixelsFramesFilter(snapshotConsumer)
                Effect.ENDLESS_MOTION_EXTRACTION -> EndlessCumulativeMotionFramesFilter(snapshotConsumer)

                Effect.AVERAGE -> AverageFramesFilter(frameBlending, snapshotConsumer)
                Effect.AVERAGE_WEIGHTED_FOR_LAST -> AverageWeightedForLastFramesFilter(frameBlending, snapshotConsumer)
                Effect.AVERAGE_WEIGHTED_FOR_LIGHT -> AverageWeightedForLightFramesFilter(frameBlending, snapshotConsumer)
                Effect.LIGHTEST_PIXELS -> LightestPixelsFramesFilter(frameBlending, snapshotConsumer)
                Effect.ADD -> AddFramesFilter(frameBlending, snapshotConsumer)
                Effect.HDR -> HDRFramesFilter(frameBlending, snapshotConsumer)
                Effect.TRANSITION -> TransitionFramesFilter(frameBlending, snapshotConsumer)
                Effect.DARKEST_PIXELS -> DarkestPixelsFramesFilter(frameBlending, snapshotConsumer)
                Effect.MOTION_EXTRACTION -> MotionExtractionWindowFramesFilter(frameBlending, snapshotConsumer)
                Effect.MEDIAN_STACK -> MedianFramesFilter(frameBlending, true, snapshotConsumer)
                else -> snapshotConsumer
            }

            // HARD RESET: Flush stale state from previous video renders
            alignCache.resetAll()
            
            // Reset filter chain state for a clean workspace (new instance per render, safety reset)
            resetFilterChain(framesConsumer)

            framesConsumer.start()

            val trimRange = model.trimRange.value ?: Pair(1f, 100f)
            val startFrame: Int
            val endFrame: Int
            if (input is com.dan.timelapse.framesinput.ImagesFramesInput) {
                // Images: trimRange stores 1-based frame indices, convert to 0-based
                startFrame = trimRange.first.toInt().coerceAtLeast(1) - 1
                endFrame = trimRange.second.toInt().coerceAtLeast(1) - 1
            } else {
                // Video: trimRange stores frame indices (1-based), convert to 0-based
                startFrame = trimRange.first.toInt().coerceAtLeast(1) - 1
                endFrame = trimRange.second.toInt().coerceAtLeast(1) - 1
            }
            val actualEndFrame = targetFrameIndex.coerceAtMost(endFrame)
            val skipFrames = model.skipFrames.value ?: 1

            
            // ==========================================
            // PATH B: LEGACY NON-MFSR PIPELINE
            // ==========================================
            input.forEachFrame(start = startFrame, progress = { current, total ->
                val percent = if (total > 0) ((current.toFloat() / total) * 100).toInt() else 0
                model.updateRenderProgress(percent)
            }) { index, total, frame ->
                if (index > actualEndFrame) return@forEachFrame false
                
                val isStackingEffect = effect != Effect.NONE
                if (isStackingEffect || skipFrames == 1 || index % skipFrames == 0) {
                    var processedFrame = applyEffects(frame)
                    val stabilized = applyStabilization(processedFrame, index - startFrame)
                    if (stabilized.nativeObj != processedFrame.nativeObj) {
                        processedFrame.release()
                    }
                    processedFrame = stabilized
                    framesConsumer.consume(index - startFrame, processedFrame)
                    processedFrame.release()
                }
                true
            }
            framesConsumer.stop(false)
            
            // Legacy Bitmap Conversion & Save
            capturedMat?.let { mat ->
                val bmp = android.graphics.Bitmap.createBitmap(mat.cols(), mat.rows(), android.graphics.Bitmap.Config.ARGB_8888)
                org.opencv.android.Utils.matToBitmap(mat, bmp)

                val rotation = model.rotation.value ?: 0
                if (rotation != 0) {
                    val matrix = android.graphics.Matrix()
                    matrix.postRotate(rotation.toFloat())
                    val rotatedBmp = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                    bmp.recycle()
                    java.io.FileOutputStream(outputFile).use { out ->
                        rotatedBmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, out)
                    }
                    rotatedBmp.recycle()
                } else {
                    java.io.FileOutputStream(outputFile).use { out ->
                        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, out)
                    }
                    bmp.recycle()
                }
                mat.release()
                return true
                }

        } catch (e: Exception) {
            Log.e("VideoProcessor", "Deep render failed", e)
        } finally {
            capturedMat?.release()
        }
        return false
    }

    private fun experimentalPostProcess(rawFile: File, refinedFile: File): Boolean {
        var src: Mat? = null
        var lab: Mat? = null
        var claheBgr: Mat? = null
        var blurred: Mat? = null
        var dst: Mat? = null
        val channels = ArrayList<Mat>()
        var clahe: org.opencv.imgproc.CLAHE? = null

        try {
            src = Imgcodecs.imread(rawFile.absolutePath)
            if (src.empty()) return false

            lab = Mat()
            Imgproc.cvtColor(src, lab, Imgproc.COLOR_BGR2Lab)
            Core.split(lab, channels)
            
            clahe = Imgproc.createCLAHE(2.0, org.opencv.core.Size(8.0, 8.0))
            clahe.apply(channels[0], channels[0])
            
            Core.merge(channels, lab)
            claheBgr = Mat()
            Imgproc.cvtColor(lab, claheBgr, Imgproc.COLOR_Lab2BGR)

            blurred = Mat()
            Imgproc.GaussianBlur(claheBgr, blurred, org.opencv.core.Size(0.0, 0.0), 3.0)
            dst = Mat()
            Core.addWeighted(claheBgr, 1.5, blurred, -0.5, 0.0, dst)

            return Imgcodecs.imwrite(refinedFile.absolutePath, dst)

        } catch (e: Exception) {
            Log.e("MFSR_LAB", "Experimental processing failed", e)
            return false
        } finally {
            src?.release()
            lab?.release()
            claheBgr?.release()
            blurred?.release()
            dst?.release()
            for (m in channels) m.release()
            clahe?.collectGarbage()
        }
    }
}