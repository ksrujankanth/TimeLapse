package com.dan.timelapse

import android.content.Context
import android.os.Environment
import android.util.Log
import com.dan.timelapse.filters.*
import com.dan.timelapse.stabilization.Affine
import com.dan.timelapse.stabilization.CamState
import com.dan.timelapse.stabilization.MAX_AUTO_CROP_ZOOM
import com.dan.timelapse.stabilization.StabilizationTracker
import com.dan.timelapse.stabilization.appendTrackedState
import com.dan.timelapse.stabilization.buildPlan
import com.dan.timelapse.stabilization.toCamState
import com.dan.timelapse.video.VideoWriter
import com.dan.timelapse.images.ImageTools
import com.dan.timelapse.framesinput.ImagesFramesInput
import com.dan.timelapse.framesinput.FramesInput
import com.dan.timelapse.framesinput.VideoFramesInput
import com.dan.timelapse.eta.EtaJob
import com.dan.timelapse.eta.EtaUnits
import com.dan.timelapse.utils.HardwareUtils
import com.dan.timelapse.utils.DeviceTier
import com.dan.timelapse.utils.InputSpeed
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

    /**
     * The learned-ETA job for the render currently running, if any.
     *
     * Created inside [startRendering] (it is the only place that knows the
     * delivered frame count and the final output size) and deliberately NOT
     * finished there: the caller still performs the audio mux and the gallery
     * write, which are real phases of the same job. The caller must therefore
     * call `finish(true)` on success or `abandon()` on cancel/failure.
     */
    var etaJob: EtaJob? = null
        private set

    /**
     * Why the last [startRendering] returned false, in words the user can act on.
     * Null when the failure has no better explanation than "Processing Failed".
     */
    var failureMessage: String? = null
        private set

    // Single global watchdog (replaces per-frame Thread allocation to avoid pthread exhaustion)
    @Volatile private var lastFrameStartMs = 0L
    @Volatile private var lastFrameDebugInfo = ""
    @Volatile private var isRendering = false

    // ---------------- Stabilization (single-pass: preview, still export) ----------------
    // The video render builds an exact plan from the same primitives instead of
    // using this cache; see buildRenderPlan().
    private val stabilizerTracker = StabilizationTracker()
    private val stabilizationPath = ArrayList<CamState>()
    private var stabilizationLastIndex = -1
    /** True while rendering a single still from the legacy path (see renderHighResSnapshot). */
    private var stillExportMode = false
    private var stabilizationMaskCache: Mat? = null
    private var stabilizationMaskSource: Long = 0L
    
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

    // ==========================================================================
    // Stabilization
    // ==========================================================================

    /**
     * Interactive / single-frame path (preview, still export).
     *
     * Frames arrive one at a time, so the smoothing is causal and the crop is a
     * fixed conservative budget. The video render uses [buildRenderPlan] instead:
     * it sees the whole clip, so it can smooth with zero phase and compute the
     * exact crop. Both paths share the tracker and the geometry in
     * stabilization/StabilizationMath.kt.
     */
    private fun applyStabilization(frame: Mat, frameIndex: Int): Mat {
        if (frame.empty() || model.isStabilized.value != true) return frame
        // A still export is one frame: there is no path to smooth and no crop budget
        // that can be justified. Warping it would apply the 3% preview crop and a
        // CUBIC resample to a frame the user explicitly picked.
        if (stillExportMode) return frame

        // A backwards or repeated index means the caller restarted or scrubbed.
        if (frameIndex <= 0 || frameIndex <= stabilizationLastIndex) resetStabilizationState()
        stabilizationLastIndex = frameIndex

        val cx = frame.cols() / 2.0
        val cy = frame.rows() / 2.0
        val anchor = model.alignToFirstFrame.value ?: true
        val window = model.stabilizationSmoothing.value?.coerceIn(5, 200) ?: 60
        val maxCrop = userCropBudget()

        val estimate = stabilizerTracker.analyze(
            frame,
            processedMask(frame.cols(), frame.rows())
        )
        // A rejected-but-fitted frame still carries the bogus dx/dy/angle (only an
        // outright tracker failure returns identity). Composing that onto the path
        // baked a permanent offset into every later frame — holdInvalid repairs the
        // invalid slot only, not the frames accumulated from it. appendTrackedState
        // holds the last VALID state instead (unit-tested).
        stabilizationPath.add(appendTrackedState(stabilizationPath, estimate.delta, estimate.valid, cx, cy))

        val plan = buildPlan(
            cumulative = stabilizationPath,
            width = frame.cols(),
            height = frame.rows(),
            anchor = anchor,
            smoothingWindow = window,
            maxCrop = maxCrop,
            centerX = cx,
            centerY = cy,
            cropOverride = if (maxCrop > 1.0000001) minOf(maxCrop, 1.03) else 1.0
        )
        val warp = plan.warps.lastOrNull() ?: return frame
        return warpFrame(frame, warp)
    }

    /**
     * Analyze the whole clip, then return one warp per rendered frame.
     *
     * This is the offline path: smoothing runs over the full path (zero phase, no
     * lag, no edge settle) and the crop is solved from the actual correction
     * envelope, capped by the user's budget. Frames that fail the tracker are
     * held, never extrapolated.
     */
    private fun buildRenderPlan(
        input: FramesInput,
        startFrame: Int,
        endFrame: Int,
        step: Int,
        renderWidth: Int,
        renderHeight: Int,
        onAnalysisProgress: ((Int, Int) -> Unit)? = null
    ): RenderStabilizationPlan? {
        val anchor = model.alignToFirstFrame.value ?: true
        val window = model.stabilizationSmoothing.value?.coerceIn(5, 200) ?: 60
        val maxCrop = userCropBudget()

        stabilizerTracker.reset()

        val cumulativeStates = ArrayList<CamState>()
        var processedWidth = 0
        var analyzed = 0
        var accepted = 0

        try {
            input.forEachAnalysisFrame(startFrame, ANALYSIS_WIDTH, step = step) { index, rawFrame ->
                if (model.isProcessingCancelled.value == true) return@forEachAnalysisFrame false
                if (index > endFrame) return@forEachAnalysisFrame false
                // Must match the render loop exactly: sampling is a stride relative
                // to startFrame, not an absolute `index % step`, so the first kept
                // frame is always startFrame and both passes see the same indices.
                if (!InputSpeed.keeps(index - startFrame, step)) return@forEachAnalysisFrame true
                // Analysis can run for a while; keep the hang watchdog quiet.
                lastFrameStartMs = System.currentTimeMillis()

                // applyEffects owns its result.
                val processed = applyEffects(rawFrame)
                // Pass 2 consumes a stabilization ordinal only for a non-empty frame
                // (`if (!frame.empty())`). Appending a state for an empty frame here
                // would shift every later frame onto the previous frame's warp, i.e. a
                // permanent one-frame lag. Skip it so the two passes stay 1:1.
                if (processed.empty()) {
                    processed.release()
                    return@forEachAnalysisFrame true
                }
                try {
                    val estimate = stabilizerTracker.analyze(
                        processed,
                        processedMask(processed.cols(), processed.rows())
                    )
                    if (processedWidth == 0) processedWidth = processed.cols()
                    if (processedWidth == 0) return@forEachAnalysisFrame true

                    // The analysis pass may have decoded at a reduced resolution.
                    // Lift the delta into render pixels before accumulating.
                    val toRender = renderWidth.toDouble() / processedWidth
                    val delta = if (kotlin.math.abs(toRender - 1.0) < 1e-9) {
                        estimate.delta
                    } else {
                        Affine.fromRaw(
                            estimate.delta.m02 * toRender,
                            estimate.delta.m12 * toRender,
                            estimate.delta.angle
                        )
                    }

                    val cx = renderWidth / 2.0
                    val cy = renderHeight / 2.0
                    // Same rule as the causal path (see appendTrackedState): one bad fit
                    // must not be compounded into every later frame.
                    cumulativeStates.add(
                        appendTrackedState(cumulativeStates, delta, estimate.valid, cx, cy)
                    )
                    analyzed++
                    if (estimate.valid) accepted++
                    onAnalysisProgress?.invoke(index, endFrame)
                } finally {
                    processed.release()
                }
                true
            }

            if (cumulativeStates.isEmpty()) return null

            val plan = buildPlan(
                cumulative = cumulativeStates,
                width = renderWidth,
                height = renderHeight,
                anchor = anchor,
                smoothingWindow = window,
                maxCrop = maxCrop,
                centerX = renderWidth / 2.0,
                centerY = renderHeight / 2.0
            )
            Log.d(
                "Stabilization",
                "Plan: ${plan.warps.size} frames, crop ${"%.3f".format(plan.zoom)}, " +
                    "accepted $accepted/$analyzed, residual ${"%.2f".format(plan.residualRmsPx)} px"
            )
            return RenderStabilizationPlan(
                warps = plan.warps,
                zoom = plan.zoom,
                analyzedFrames = analyzed,
                acceptedFrames = accepted,
                residualPx = plan.residualRmsPx
            )
        } catch (e: Exception) {
            Log.e("Stabilization", "Render plan failed; falling back to pass-through", e)
            return null
        }
    }

    /** User's max-crop setting expressed as a zoom. 1.0 = never crop. */
    private fun userCropBudget(): Double =
        (model.stabilizationZoom.value ?: 1.05f).toDouble().coerceIn(1.0, MAX_AUTO_CROP_ZOOM)

    /**
     * Mask in processed-frame coordinates. Rotating/cropping a full-resolution
     * mask on every frame was one of the most expensive parts of the old loop;
     * do it once per mask and cache the result.
     */
    private fun processedMask(width: Int, height: Int): Mat? {
        val source = model.firstFrameMask.value
        if (source == null || source.empty()) return null
        if (stabilizationMaskSource == source.nativeObj &&
            stabilizationMaskCache?.cols() == width &&
            stabilizationMaskCache?.rows() == height
        ) {
            return stabilizationMaskCache
        }
        return try {
            val processed = applyEffects(source)
            stabilizationMaskCache?.release()
            stabilizationMaskCache = null
            if (processed.cols() == width && processed.rows() == height) {
                stabilizationMaskCache = processed
                stabilizationMaskSource = source.nativeObj
                processed
            } else {
                processed.release()
                stabilizationMaskSource = 0L
                null
            }
        } catch (e: Exception) {
            Log.w("Stabilization", "Mask preparation failed", e)
            null
        }
    }

    /**
     * Apply a center-pivot affine. BORDER_REPLICATE guarantees the output never
     * contains black bars; the crop normally keeps sampling inside the source.
     */
    private fun warpFrame(frame: Mat, warp: Affine): Mat {
        // A sub-pixel warp is pure resampling loss. Locked-off shots have an
        // identity plan, so pass the original pixels through bit-exact.
        val identity = kotlin.math.abs(warp.m00 - 1.0) < 1e-9 &&
            kotlin.math.abs(warp.m01) < 1e-9 &&
            kotlin.math.abs(warp.m10) < 1e-9 &&
            kotlin.math.abs(warp.m11 - 1.0) < 1e-9 &&
            kotlin.math.abs(warp.m02) < 0.05 &&
            kotlin.math.abs(warp.m12) < 0.05
        if (identity) return frame

        val m = Mat(2, 3, CvType.CV_64F)
        m.put(0, 0, warp.m00, warp.m01, warp.m02)
        m.put(1, 0, warp.m10, warp.m11, warp.m12)
        val out = Mat()
        try {
            Imgproc.warpAffine(
                frame, out, m, frame.size(),
                Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE, Scalar(0.0, 0.0, 0.0)
            )
        } finally {
            m.release()
        }
        return out
    }

    private fun resetStabilizationState() {
        stabilizerTracker.reset()
        stabilizationPath.clear()
        stabilizationLastIndex = -1
        stabilizationMaskCache?.release()
        stabilizationMaskCache = null
        stabilizationMaskSource = 0L
    }

    /** Precomputed warps for one render, indexed by rendered-frame ordinal. */
    private class RenderStabilizationPlan(
        val warps: List<Affine>,
        val zoom: Double,
        val analyzedFrames: Int,
        val acceptedFrames: Int,
        val residualPx: Double
    ) {
        fun warpAt(index: Int): Affine? = warps.getOrNull(index)
    }

    private companion object {
        /** Analysis width. 3-4x cheaper than full res, no measurable accuracy loss. */
        const val ANALYSIS_WIDTH = 960

        /**
         * Fewer output frames than this is not a video: the container reports a
         * 0 ms duration and no player or gallery can show it.
         */
        const val MIN_OUTPUT_FRAMES = 2
    }

    /** Container duration in ms, or null when the file cannot be probed. */
    private fun videoDurationMs(path: String): Long? {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull()
        } catch (e: Exception) {
            Log.w("VideoProcessor", "Could not read output duration", e)
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    fun startRendering(outputPath: String): Boolean {
        failureMessage = null
        val input = model.framesInput.value ?: return false
        if (input.size == 0) {
            Log.e("VideoProcessor", "No frames to process")
            return false
        }

        // FIX: Acquire a Partial WakeLock so the CPU doesn't sleep when switching apps.
        // Acquired AFTER the early returns so it can never leak un-released.
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "TimeLapse::VideoRender")
        wakeLock.acquire(60 * 60 * 1000L) // Max 1 hour

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

            // Export FPS is the user's output setting. previewFps only controls
            // the live preview; it must never silently override the saved file.
            val outFps = (model.outputFps.value ?: model.previewFps.value ?: 30).coerceAtLeast(1)
            val qualityPercent = (model.fileQuality.value ?: 50).coerceIn(1, 100)

            // Check if source is images (RGB from Android) vs video (BGR from OpenCV)
            val sourceIsImages = input is ImagesFramesInput
            val writer = VideoWriter(outputPath, outFps, model.encodingH265.value ?: false, finalWidth, finalHeight, sourceIsImages, qualityPercent)

            val baseEffect = model.visualEffect.value ?: Effect.NONE
            val isEndless = model.isEndless.value ?: false
            // CONVERT TO ENDLESS HERE BEFORE THE WHEN BLOCK
            val effect = if (isEndless) baseEffect.toEndless() else baseEffect
            val frameBlending = model.frameBlending.value ?: 10
            // Input speed: keep one source frame in every N, for EVERY effect.
            // This used to be limited to Effect.NONE ("stacking filters must ingest
            // 100% of the frames"), which made the slider a no-op in the Effects tab.
            // Super Resolution is the one deliberate exception: MFSR needs every
            // frame it can get for sub-pixel coverage, and it is rendered by
            // MFSRProcessor, not here.
            val skipFrames = InputSpeed.strideFor(effect, model.skipFrames.value ?: 1)
            Log.d("VideoProcessor", "Input speed: ${skipFrames}x (effect=$effect)")

            // Sampling is relative to actualStartFrame, so the first frame of the
            // trimmed range is always kept and this count is exact — it is the
            // number the progress dialog will actually reach.
            val totalFramesToProcess =
                InputSpeed.deliveredCount(actualEndFrame - actualStartFrame + 1, skipFrames)

            // A stacking window larger than the delivered frame count emits ZERO
            // output frames (SumFramesFilter only outputs at `frames.size >= size`).
            // At 60x a 100-frame clip delivers 2 frames, so the window must follow.
            val effectiveFrameBlending =
                InputSpeed.effectiveWindow(frameBlending, totalFramesToProcess)

            // ...but clamping the window to the delivered count means a run of
            // `delivered <= window` frames fills it exactly once and emits EXACTLY
            // ONE output frame. That is a 0-second MP4: MediaStore publishes it,
            // no gallery plays it, and auto-save used to copy it to the user's
            // library. Refuse before the work starts. (Measured: a 10 s / 300-frame
            // clip at 10x delivers 30 frames, which is exactly the default 30-frame
            // Darken/Average window.)
            val plannedOutputFrames = if (effect.usesStackWindow) {
                InputSpeed.windowedOutputCount(totalFramesToProcess, effectiveFrameBlending)
            } else {
                totalFramesToProcess
            }
            if (plannedOutputFrames < MIN_OUTPUT_FRAMES) {
                failureMessage = if (effect.usesStackWindow) {
                    // Report the REQUESTED intensity: the window was clamped down
                    // to the delivered count precisely because it could not fill.
                    "Not enough frames: $totalFramesToProcess frame(s) reach a " +
                        "$frameBlending-frame ${effect.friendlyName} stack, " +
                        "which yields a single 0-second frame. Lower Input speed or Intensity."
                } else {
                    "The selected range has only $totalFramesToProcess frame(s) — " +
                        "that is not a video."
                }
                Log.w("VideoProcessor", "Render refused: ${failureMessage}")
                return false
            }

            // ── Learned ETA ──────────────────────────────────────────────
            // One job covers every phase this render will execute, including the
            // post-loop retime that the progress bar never shows. The job is left
            // open (no finish()) so the caller can add the audio mux and gallery
            // write it performs after startRendering() returns.
            val stabilizedNow = model.isStabilized.value == true
            val hasAudioMux = (model.preserveAudio.value == true) && input is VideoFramesInput
            val etaSignature = com.dan.timelapse.eta.EtaApp.videoSignature(
                context = context,
                effect = effect.name,
                outWidth = finalWidth,
                outHeight = finalHeight,
                frames = totalFramesToProcess,
                stabilized = stabilizedNow
            )
            val etaSpec = com.dan.timelapse.eta.EtaSpecs.videoRender(
                signature = etaSignature,
                deliveredFrames = totalFramesToProcess,
                outWidth = finalWidth,
                outHeight = finalHeight,
                effect = effect.name,
                stackWindow = effectiveFrameBlending,
                stabilized = stabilizedNow,
                hasAudioMux = hasAudioMux,
                temperatureC = com.dan.timelapse.eta.EtaApp.batteryTemperatureC(context)
            )
            val job = try {
                com.dan.timelapse.eta.Eta.startJob(context, etaSpec)
            } catch (e: Exception) {
                Log.w("EtaEngine", "Could not start ETA job", e)
                null
            }
            etaJob = job
            Log.i(
                "EtaEngine",
                "video job: predicted=${job?.predictedTotalMs?.toLong() ?: -1}ms " +
                        "frames=$totalFramesToProcess out=${finalWidth}x$finalHeight effect=$effect " +
                        "stab=$stabilizedNow audio=$hasAudioMux samples=${job?.priorSamples ?: 0} " +
                        "key=${etaSignature.levelKey(0)}"
            )

            val renderUnitsPerFrame = com.dan.timelapse.eta.EtaUnits.megapixels(finalWidth, finalHeight)
                .coerceAtLeast(0.01) *
                com.dan.timelapse.eta.EtaUnits.effectCost(effect.name, effectiveFrameBlending)
            val analysisUnits = com.dan.timelapse.eta.EtaUnits.analysisUnits(
                totalFramesToProcess, finalWidth, finalHeight
            )

            // Push immediately so the UI shows "initializing..." instead of dead air
            onProgressWithStatus?.invoke(0, totalFramesToProcess, "Allocating buffers...")
            job?.let {
                onProgressWithETA?.invoke(
                    0, totalFramesToProcess, "Allocating buffers...", it.remainingMs()
                )
            }

            // Pass 1: motion analysis over the whole clip. This runs before the
            // encoder starts so the smoothing can be zero-phase and the crop can
            // be solved from the actual correction envelope. Reserving a slice of
            // the progress bar keeps the two passes monotonic.
            val progressHead = (totalFramesToProcess / 8).coerceIn(0, totalFramesToProcess)
            val progressTotal = totalFramesToProcess + progressHead
            var renderStabilization: RenderStabilizationPlan? = null
            var stabilizationOrdinal = 0
            // One-shot diagnostic: if pass 1 produced fewer states than pass 2 consumes,
            // every later frame silently renders unstabilized. Never silent.
            var warpExhaustedLogged = false
            if (model.isStabilized.value == true) {
                val probe = input.firstFrame()
                val probeProcessed = try {
                    if (probe.empty()) null else applyEffects(probe)
                } finally {
                    probe.release()
                }
                if (probeProcessed != null && !probeProcessed.empty()) {
                    val probeWidth = probeProcessed.cols()
                    val probeHeight = probeProcessed.rows()
                    probeProcessed.release()
                    val step = skipFrames
                    val span = (actualEndFrame - actualStartFrame + 1).coerceAtLeast(1)
                    onProgressWithStatus?.invoke(0, progressTotal, "Analyzing motion...")
                    job?.beginPhase(com.dan.timelapse.eta.EtaPhase.ANALYZE, analysisUnits)
                    renderStabilization = buildRenderPlan(
                        input = input,
                        startFrame = actualStartFrame,
                        endFrame = actualEndFrame,
                        step = step,
                        renderWidth = probeWidth,
                        renderHeight = probeHeight
                    ) { index, _ ->
                        val progressed = (index - actualStartFrame + 1).coerceIn(0, span)
                        val scaled = (progressed.toLong() * progressHead / span).toInt()
                        val remaining = job?.progress(analysisUnits * progressed / span)
                            ?: -1L
                        onProgressWithStatus?.invoke(
                            scaled, progressTotal,
                            "Analyzing motion ($progressed/$span)"
                        )
                        onProgressWithETA?.invoke(
                            scaled, progressTotal,
                            "Analyzing motion ($progressed/$span)", remaining
                        )
                    }
                    job?.endPhase()
                    if (renderStabilization == null) {
                        // Analysis failed: fall back to the causal path rather than
                        // rendering without stabilization.
                        Log.w("Stabilization", "Two-pass analysis unavailable; using causal fallback")
                    }
                }
            }
            if (model.isProcessingCancelled.value == true) return false
            
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

                Effect.AVERAGE -> AverageFramesFilter(effectiveFrameBlending, writer)
                Effect.AVERAGE_WEIGHTED_FOR_LAST -> AverageWeightedForLastFramesFilter(effectiveFrameBlending, writer)
                Effect.AVERAGE_WEIGHTED_FOR_LIGHT -> AverageWeightedForLightFramesFilter(effectiveFrameBlending, writer)
                Effect.LIGHTEST_PIXELS -> LightestPixelsFramesFilter(effectiveFrameBlending, writer)
                Effect.ADD -> AddFramesFilter(effectiveFrameBlending, writer)
                Effect.HDR -> HDRFramesFilter(effectiveFrameBlending, writer)
                Effect.TRANSITION -> TransitionFramesFilter(effectiveFrameBlending, writer)
                Effect.DARKEST_PIXELS -> DarkestPixelsFramesFilter(effectiveFrameBlending, writer)
                Effect.MOTION_EXTRACTION -> MotionExtractionWindowFramesFilter(effectiveFrameBlending, writer)
                Effect.MEDIAN_STACK -> MedianFramesFilter(effectiveFrameBlending, true, writer)
                else -> writer
            }

            // HARD RESET: Flush stale state from previous video renders
            resetStabilizationState()
            
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

            val renderUnits = renderUnitsPerFrame * totalFramesToProcess
            job?.beginPhase(com.dan.timelapse.eta.EtaPhase.RENDER, renderUnits)

            input.forEachFrame(start = actualStartFrame, step = skipFrames, progress = { current, total ->
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
                        onProgressWithStatus?.invoke(framesProcessed + progressHead, progressTotal, warningMsg)
                        
                        try { Thread.sleep(if (isPlugged) 1000L else 500L) } catch (_: InterruptedException) {}
                    }
                }
                
                if (index > actualEndFrame) return@forEachFrame false

                // Input speed decimation. The frame source normally honours the
                // stride itself (and skips the decode); this is the correctness
                // backstop for a provider that cannot, and it uses the SAME rule:
                // a stride relative to actualStartFrame, so the first frame is kept.
                if (!InputSpeed.keeps(index - actualStartFrame, skipFrames)) {
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
                            // Prefer the precomputed (zero-phase, auto-cropped) plan.
                            // The render loop keeps its own ordinal so trimmed or
                            // skipped clips can never desynchronize pass 1 and pass 2.
                            val plan = renderStabilization
                            val stabilized = if (plan != null) {
                                val warp = plan.warpAt(stabilizationOrdinal)
                                stabilizationOrdinal++
                                if (warp != null) {
                                    warpFrame(processedFrame!!, warp)
                                } else {
                                    if (!warpExhaustedLogged) {
                                        warpExhaustedLogged = true
                                        Log.w("VideoProcessor",
                                            "No stabilization warp for ordinal $stabilizationOrdinal " +
                                            "— the analysis pass produced fewer states than the " +
                                            "render consumes. Remaining frames render unstabilized.")
                                    }
                                    processedFrame!!
                                }
                            } else {
                                applyStabilization(processedFrame!!, index - actualStartFrame)
                            }
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
                                // Downscaling (e.g. 1080p -> 720p) must use INTER_AREA;
                                // INTER_LINEAR aliases fine detail.
                                val downscaling = finalWidth < processedFrame!!.cols() ||
                                    finalHeight < processedFrame!!.rows()
                                Imgproc.resize(
                                    processedFrame,
                                    finalFrame,
                                    Size(finalWidth.toDouble(), finalHeight.toDouble()),
                                    0.0,
                                    0.0,
                                    if (downscaling) Imgproc.INTER_AREA else Imgproc.INTER_LINEAR
                                )
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
                            val remainingFrames = (totalFrames - framesProcessed).coerceAtLeast(0)
                            // The learned job is authoritative when it exists: it knows
                            // the retime/mux/encode tail that "frames left x ms/frame"
                            // always reported as zero. The EMA below stays as the
                            // fallback for callers that run without an engine.
                            val learnedRemaining = job
                                ?.progress(renderUnitsPerFrame * framesProcessed)
                            val remainingTimeMillis = learnedRemaining
                                ?: if (framesProcessed >= WARMUP_FRAMES && emaMsPerFrame > 0) {
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
                                onProgress?.invoke(framesProcessed + progressHead, progressTotal)
                                val progressText = "Applying effects ($framesProcessed of $totalFrames frames)"
                                onProgressWithStatus?.invoke(framesProcessed + progressHead, progressTotal, progressText)
                                onProgressWithETA?.invoke(framesProcessed + progressHead, progressTotal, progressText, remainingTimeMillis)
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

            resetStabilizationState()
            
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
            job?.let {
                it.beginPhase(
                    com.dan.timelapse.eta.EtaPhase.RETIME,
                    encodedFrameCount.toDouble().coerceAtLeast(1.0)
                )
                onProgressWithETA?.invoke(
                    progressTotal, progressTotal, "Finalizing video...", it.remainingMs()
                )
            }
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

            // Never publish a degenerate video. `encodedFrameCount` counts INPUT
            // frames, so a filter that never filled its window can still have
            // swallowed hundreds of them; only the writer knows how many frames
            // reached the encoder, and only the container knows the real duration.
            // A 1-frame MP4 reports 0 ms and cannot be played — it must not reach
            // the Finish screen, the auto-save, or MediaStore.
            if (model.isProcessingCancelled.value != true) {
                val producedFrames = writer.framesWritten
                val durationMs = videoDurationMs(outputPath)
                if (producedFrames < MIN_OUTPUT_FRAMES || (durationMs != null && durationMs <= 0L)) {
                    failureMessage = "Nothing was saved: the render produced " +
                        "$producedFrames frame(s)" +
                        (durationMs?.let { " / $it ms" } ?: "") +
                        ". Lower Input speed or Intensity."
                    Log.e("VideoProcessor", "Degenerate output rejected: $failureMessage")
                    java.io.File(outputPath).delete()
                    return false
                }
                Log.d("VideoProcessor", "Output verified: $producedFrames frames, ${durationMs ?: -1} ms")
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
            stillExportMode = true
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
            resetStabilizationState()
            
            // Reset filter chain state for a clean workspace (new instance per render, safety reset)
            resetFilterChain(framesConsumer)

            framesConsumer.start()

            val trimRange = model.trimRange.value ?: Pair(1f, input.size.toFloat())
            // trimRange is 1-based, convert to 0-based (same for images and video).
            val trimStartFrame = trimRange.first.toInt().coerceAtLeast(1) - 1
            val trimEndFrame = trimRange.second.toInt().coerceAtLeast(1) - 1
            val lastIndex = (input.size - 1).coerceAtLeast(0)
            val targetFrame = targetFrameIndex.coerceIn(0, lastIndex)

            // An explicitly picked frame wins over the trim range. If it lies
            // outside the trim, scan just enough frames for the stack to fill
            // instead of producing an empty bitmap.
            val startFrame = if (targetFrame in trimStartFrame..trimEndFrame) {
                trimStartFrame
            } else {
                (targetFrame - framesConsumer.getWindowSize().coerceAtLeast(1) + 1).coerceAtLeast(0)
            }
            val actualStartFrame = minOf(startFrame, targetFrame)
            val actualEndFrame = targetFrame

            
            // ==========================================
            // PATH B: LEGACY NON-MFSR PIPELINE
            // ==========================================
            input.forEachFrame(start = actualStartFrame, progress = { current, total ->
                val percent = if (total > 0) ((current.toFloat() / total) * 100).toInt() else 0
                model.updateRenderProgress(percent)
            }) { index, total, frame ->
                if (index > actualEndFrame) return@forEachFrame false
                
                val isStackingEffect = effect != Effect.NONE
                // A still export is never decimated: input speed is a video-only
                // transform. Stacking effects need every frame so their window
                // fills; for NONE we take exactly the frame the user picked rather
                // than the first multiple of the stride.
                if (isStackingEffect || index == actualEndFrame) {
                    var processedFrame = applyEffects(frame)
                    val stabilized = applyStabilization(processedFrame, index - actualStartFrame)
                    if (stabilized.nativeObj != processedFrame.nativeObj) {
                        processedFrame.release()
                    }
                    processedFrame = stabilized
                    framesConsumer.consume(index - actualStartFrame, processedFrame)
                    processedFrame.release()
                }
                true
            }
            framesConsumer.stop(false)
            
            // Legacy Bitmap Conversion & Save
            capturedMat?.let { mat ->
                val bmp = android.graphics.Bitmap.createBitmap(mat.cols(), mat.rows(), android.graphics.Bitmap.Config.ARGB_8888)
                org.opencv.android.Utils.matToBitmap(mat, bmp)

                // applyEffects() already applied the TransformState rotation to the
                // Mat. Rotating the bitmap again here exported 90/180/270-degree
                // stills upside down or sideways.
                java.io.FileOutputStream(outputFile).use { out ->
                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, out)
                }
                bmp.recycle()
                mat.release()
                return true
                }

        } catch (e: Exception) {
            Log.e("VideoProcessor", "Deep render failed", e)
        } finally {
            stillExportMode = false
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
