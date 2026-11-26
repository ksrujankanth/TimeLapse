package com.dan.timelapse.mfsr

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.util.Log
import com.dan.timelapse.framesinput.FramesInput
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Core
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

/** Data class holding frame metrics for selection */
private data class FrameStat(val index: Int, val mse: Double, val sharpness: Double, val brightness: Double)

/**
 * Clean MFSR Processor — works for both video input and image sequences.
 *
 * Factory methods:
 *   - forVideo(context, uri, anchor, scale) — uses MediaMetadataRetriever
 *   - forImages(framesInput, anchor, scale) — uses FramesInput.getFrame()
 *
 * Alignment + fusion happen in MFSREngineV2 (C++). This class owns:
 *   - proxy pre-scan (sharpness / exposure / similarity)
 *   - frame selection
 *   - the output scale policy (memory + frame-count aware)
 *   - progress, saving, telemetry
 */
class MFSRProcessor private constructor(
    private val context: Context,
    private val frameSource: FrameSource,
    private val anchorFrameIndex: Int,
    private val requestedScale: Float,
    private val trimStartFrame: Int = 0,
    private val trimEndFrame: Int = Int.MAX_VALUE
) {
    companion object {
        private const val TAG = "MFSRProcessor"

        // Wider window means more candidate frames to pick the best N from.
        const val SCAN_WINDOW_RADIUS = 90

        // Memory accounting lives in MfsrScalePolicy (BYTES_PER_HR_PIXEL / the 64 MP
        // cap). Duplicating it here drifted silently, so it was removed.

        private const val PROXY_WIDTH = 640
        private const val PROXY_HEIGHT = 360
        private const val PIXEL_THRESHOLD_4K = 1920 * 1080

        /**
         * Pick a scale factor that (a) fits the device's free RAM and (b) is actually
         * achievable with the number of frames available.
         *
         * Coverage of the HR grid by N randomly-phased samples is ~1-exp(-N/S^2):
         *   S=2 needs ~10 frames, S=3 needs ~40, S=4 needs ~75 for >99% coverage.
         * Asking for 4x from 10 photos leaves holes and cannot be resolved — the old
         * code did exactly that (4x on any >=8GB device, at any frame count).
         */
        fun chooseScale(
            context: Context,
            inputW: Int,
            inputH: Int,
            frameCount: Int,
            requested: Float
        ): Float {
            val availBytes = try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                val mi = android.app.ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                mi.availMem.toDouble()
            } catch (e: Exception) {
                2.0e9
            }

            val inputPixels = inputW.toDouble() * inputH.toDouble()

            // 32-bit processes have a small address space: a single 500MB+ Mat
            // allocation is not dependable there. Keep those devices conservative.
            val is64Bit = try { android.os.Process.is64Bit() } catch (e: Exception) { true }

            val decision = MfsrScalePolicy.decide(
                inputPixels = inputPixels,
                frameCount = frameCount,
                requested = requested,
                availableBytes = availBytes,
                is64Bit = is64Bit
            )
            val chosen = decision.scale

            Log.i(TAG, "Scale request=${requested}x -> ${chosen}x " +
                    "(memCap=${String.format(Locale.US, "%.2f", decision.byMemory)}x, " +
                    "frameCap=${decision.byFrames}x from $frameCount frames, " +
                    "avail=${String.format(Locale.US, "%.1f", availBytes / 1e9)}GB)")
            return chosen
        }

        /** Create a processor for a video URI. */
        fun forVideo(
            context: Context,
            videoUri: Uri,
            anchorFrameIndex: Int,
            scaleFactor: Float = 2.0f,
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
            scaleFactor: Float = 2.0f,
            trimStartFrame: Int = 0,
            trimEndFrame: Int = Int.MAX_VALUE
        ): MFSRProcessor {
            val source = ImagesFrameSource(framesInput)
            return MFSRProcessor(context, source, anchorFrameIndex, scaleFactor, trimStartFrame, trimEndFrame)
        }
    }

    /** Progress callback: (current, total, statusMessage). Values are frame units. */
    var onProgress: ((Int, Int, String) -> Unit)? = null

    /**
     * Optional learned-ETA job. When set, every phase reports its own work so the
     * estimator can learn real ms-per-megapixel rates instead of guessing from
     * the clip length. The caller owns the lifecycle (finish/abandon).
     */
    var etaJob: com.dan.timelapse.eta.EtaJob? = null

    /**
     * Written from the UI thread, read from the render thread. @Volatile: this is the
     * flag the Cancel button depends on, so it must never be a stale register value.
     */
    @Volatile
    var isCancelled: Boolean = false

    /**
     * The file [process] actually wrote. It can differ from the path passed in: a
     * 16-bit result is redirected to a sibling `.png`, because a JPEG target clips
     * CV_16U to white (see the save block). Callers MUST use this path.
     */
    var writtenOutputFile: File? = null
        private set

    /**
     * Live cancel source, polled inside the render loops.
     *
     * The Activity's cancel watcher lives in `lifecycleScope`, so it dies with the
     * Activity while `process()` (which has no suspension points) keeps running: the
     * old single `isCancelled` mirror therefore went stale and a render that outlived
     * its Activity could not be stopped. Reading the ViewModel's flag directly keeps
     * cancel live even after the watcher is gone.
     */
    @Volatile
    var cancelCheck: (() -> Boolean)? = null

    private fun shouldCancel(): Boolean = isCancelled || cancelCheck?.invoke() == true

    /** Scale actually used for this run (may be lower than the requested value). */
    var renderScale: Float = 1.0f
        private set

    /** Save baseline bicubic upscaled anchor for before/after comparison */
    private fun saveBaselineBicubic(anchorMat: Mat) {
        try {
            // Cap the comparison baseline at 2048 px. A full-resolution 40MP bitmap
            // would allocate >150 MB for a file that is only used for before/after
            // eyeballing.
            var w = anchorMat.cols() * renderScale.toDouble()
            var h = anchorMat.rows() * renderScale.toDouble()
            val maxDim = 2048.0
            val shrinking = kotlin.math.max(w, h) > maxDim
            if (shrinking) {
                val k = maxDim / kotlin.math.max(w, h)
                w *= k; h *= k
            }
            val targetSize = Size(kotlin.math.max(1.0, w), kotlin.math.max(1.0, h))
            val baselineMat = Mat()
            Imgproc.resize(anchorMat, baselineMat, targetSize, 0.0, 0.0,
                if (shrinking) Imgproc.INTER_AREA else Imgproc.INTER_CUBIC)

            val bitmap = Bitmap.createBitmap(baselineMat.cols(), baselineMat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(baselineMat, bitmap)
            baselineMat.release()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "MFSR_Baseline_$timestamp.jpg"

            Thread {
                try {
                    // Android 10+ enforces scoped storage for targetSdk 34: a plain
                    // FileOutputStream into the public Pictures dir throws EACCES
                    // (observed on a Moto X Play running Android 10, on every render).
                    // This artefact is a before/after debugging aid, so fall back to the
                    // app-specific Pictures dir instead of failing.
                    val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                    val publicFile = File(File(picturesDir, "TimeLapse"), fileName)
                    val outputFile = try {
                        publicFile.parentFile?.mkdirs()
                        FileOutputStream(publicFile).use { fos ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos)
                        }
                        publicFile
                    } catch (e: Exception) {
                        val privateDir = File(
                            context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.cacheDir,
                            "TimeLapse"
                        )
                        privateDir.mkdirs()
                        val fallback = File(privateDir, fileName)
                        FileOutputStream(fallback).use { fos ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos)
                        }
                        Log.i(TAG, "Public Pictures is not writable (scoped storage); " +
                                "baseline saved to app storage instead")
                        fallback
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
                } finally {
                    // The caller must NOT recycle this bitmap: it was still being
                    // compressed on this thread, which threw
                    // "Can't compress a recycled bitmap" on device.
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            }.start()
            // Do NOT recycle here; the worker owns the bitmap now.
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

            val effectiveTrimStart = trimStartFrame.coerceIn(0, totalFrames - 1)
            val effectiveTrimEnd = trimEndFrame.coerceIn(0, totalFrames - 1)
            // If the user explicitly picked an anchor OUTSIDE the trim range, the
            // anchor wins: scanning the trim range would not overlap its window and
            // the render would fail. Fall back to the full anchor neighbourhood.
            val anchorInTrim = safeAnchorIndex in effectiveTrimStart..effectiveTrimEnd
            val effectiveStart = if (anchorInTrim) maxOf(scanStart, effectiveTrimStart) else scanStart
            val effectiveEnd = if (anchorInTrim) minOf(scanEnd, effectiveTrimEnd) else scanEnd
            if (!anchorInTrim) {
                Log.w(TAG, "Anchor $safeAnchorIndex is outside trim [$effectiveTrimStart..$effectiveTrimEnd]; " +
                        "scanning the full anchor window instead")
            }
            val scanSize = effectiveEnd - effectiveStart + 1
            if (scanSize <= 0) {
                Log.e(TAG, "Trim bounds [$effectiveTrimStart..$effectiveTrimEnd] do not overlap scan window [$scanStart..$scanEnd]")
                return false
            }

            // Provisional total; refined after selection so the bar reaches 100%.
            var totalUnits = scanSize + 10

            // Monotonic progress reporter.
            //
            // `totalUnits` is refined after frame selection (the denominator grows), so
            // emitting raw (current, total) made the bar jump BACKWARDS (95% -> 62%) on
            // the first pass-1 callback. Always emit a percentage against a fixed
            // denominator of 100 and never let it decrease.
            val progressPercent = com.dan.timelapse.utils.MonotonicPercent()

            /**
             * Report progress weighted by the learned TIME of each phase, not by its
             * work units.
             *
             * Alignment is ~90% of an MFSR render but roughly half of the old unit
             * denominator, so the bar used to race through the scan, crawl through
             * align and then jump at the end while the ETA ticked smoothly. The ETA
             * job knows what each phase costs; use it. Falls back to the raw units
             * when no job is attached.
             */
            fun reportPhase(
                phase: com.dan.timelapse.eta.EtaPhase,
                fraction: Double,
                fallbackDone: Int,
                message: String
            ) {
                val percent = etaJob?.progressPercent(phase, fraction)
                val monotonic = if (percent != null) {
                    progressPercent.update(percent.toLong(), 100L)
                } else {
                    progressPercent.update(fallbackDone.toLong(), totalUnits.toLong())
                }
                onProgress?.invoke(monotonic, 100, message)
            }

            reportPhase(
                com.dan.timelapse.eta.EtaPhase.SCAN, 0.0, 0, "Extracting anchor frame..."
            )

            // STEP 1: anchor
            val anchorMat = frameSource.getFrame(safeAnchorIndex)
            if (anchorMat == null || anchorMat.empty()) {
                anchorMat?.release()
                Log.e(TAG, "Failed to extract anchor frame at index $safeAnchorIndex")
                return false
            }
            Log.i(TAG, "Anchor: ${anchorMat.cols()}x${anchorMat.rows()}, ch=${anchorMat.channels()}")

            // Choose the render scale from the ACTUAL anchor dimensions. frameSource.width/
            // height can be a stale default if resolution probing failed, and picking the
            // scale from those would over-allocate the HR grid.
            renderScale = chooseScale(
                context, anchorMat.cols(), anchorMat.rows(), scanSize, requestedScale
            )
            val pixelCount = anchorMat.cols() * anchorMat.rows()
            val maxContributingFrames = if (pixelCount > PIXEL_THRESHOLD_4K) 50 else 120

            // Work units for the learned ETA. Every MFSR phase is expressed as
            // (frames x megapixels) or (output megapixels), so a 12 MP phone burst
            // and a 4K video frame train the same statistic.
            val inputMp = com.dan.timelapse.eta.EtaUnits.megapixels(anchorMat.cols(), anchorMat.rows())
                .coerceAtLeast(0.01)
            var outputMp = inputMp * renderScale.toDouble() * renderScale.toDouble()
            val eta = etaJob
            eta?.planUnits(com.dan.timelapse.eta.EtaPhase.ALIGN, scanSize * inputMp)
            eta?.planUnits(
                com.dan.timelapse.eta.EtaPhase.FUSE,
                scanSize * (inputMp + outputMp)
            )
            eta?.planUnits(com.dan.timelapse.eta.EtaPhase.FINALIZE, outputMp)
            eta?.planUnits(com.dan.timelapse.eta.EtaPhase.SAVE, outputMp)
            Log.i(TAG, "MFSR start: anchor=$safeAnchorIndex, totalFrames=$totalFrames, " +
                    "trim=[$effectiveTrimStart..$effectiveTrimEnd], scan=[$effectiveStart..$effectiveEnd], " +
                    "anchor=${anchorMat.cols()}x${anchorMat.rows()}, renderScale=${renderScale}x")

            // STEP 2: proxy pre-scan
            val anchorProxyGray = buildProxyGray(anchorMat)
            val proxyFrame = Mat()
            val proxyGray = Mat()
            val diff = Mat()
            val squared = Mat()
            val proxySize = Size(PROXY_WIDTH.toDouble(), PROXY_HEIGHT.toDouble())

            val mseScores = mutableListOf<Pair<Int, Double>>()
            val frameStats = mutableListOf<FrameStat>()
            var lastProgressMs = 0L
            var scanned = 0

            eta?.beginPhase(com.dan.timelapse.eta.EtaPhase.SCAN, scanSize * inputMp)

            for (i in effectiveStart..effectiveEnd) {
                if (shouldCancel()) {
                    anchorProxyGray.release(); proxyFrame.release(); proxyGray.release()
                    diff.release(); squared.release(); anchorMat.release()
                    return false
                }

                val frameMat = frameSource.getFrame(i)
                if (frameMat != null && !frameMat.empty()) {
                    val laplacian = Mat()
                    val mean = MatOfDouble()
                    val stddev = MatOfDouble()
                    try {
                        Imgproc.resize(frameMat, proxyFrame, proxySize)
                        Imgproc.cvtColor(proxyFrame, proxyGray, Imgproc.COLOR_RGB2GRAY)
                        org.opencv.core.Core.absdiff(anchorProxyGray, proxyGray, diff)
                        org.opencv.core.Core.multiply(diff, diff, squared)
                        val sum = org.opencv.core.Core.sumElems(squared)
                        val mse = sum.`val`[0] / (PROXY_WIDTH.toDouble() * PROXY_HEIGHT)
                        mseScores.add(Pair(i, mse))

                        Imgproc.Laplacian(proxyGray, laplacian, CvType.CV_64F)
                        org.opencv.core.Core.meanStdDev(laplacian, mean, stddev)
                        val stdDevVal = stddev.get(0, 0)[0]
                        val sharpness = stdDevVal * stdDevVal

                        val brightnessMean = org.opencv.core.Core.mean(proxyGray).`val`[0]
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
                if (now - lastProgressMs > 250 || i == effectiveEnd) {
                    lastProgressMs = now
                    eta?.progress(scanned * inputMp)
                    reportPhase(
                        com.dan.timelapse.eta.EtaPhase.SCAN,
                        scanned.toDouble() / scanSize.coerceAtLeast(1),
                        scanned,
                        "Scanning frames: $scanned/$scanSize"
                    )
                }
            }

            anchorProxyGray.release(); proxyFrame.release(); proxyGray.release()
            diff.release(); squared.release()

            // STEP 3: frame selection
            // Previously: keep the sharpest 50%, then sort those by MSE-to-anchor ascending.
            // That discarded half the input and actively preferred near-duplicate frames,
            // i.e. the frames carrying the least sub-pixel diversity — the opposite of what
            // multi-frame SR needs. It alone made a 10-photo stack score worse than one frame.
            //
            // Now: drop only clearly motion-blurred frames (below a relative sharpness floor),
            // keep everything else, and rank by sharpness only.
            if (frameStats.isEmpty()) {
                Log.e(TAG, "Pre-scan produced no usable frames — releasing anchor")
                anchorMat.release()
                return false
            }

            val sortedSharp = frameStats.map { it.sharpness }.sorted()
            val medianSharp = sortedSharp[sortedSharp.size / 2]
            val sharpnessFloor = maxOf(1.0, medianSharp * 0.35)

            val usable = frameStats.filter { it.sharpness >= sharpnessFloor }
            // Rank ALL frames, not just the ones above the floor. A blurry frame still
            // carries a valid sub-pixel sample; the previous code could keep 4 of 60
            // and lose most of the burst's detail.
            val ranked = frameStats.sortedByDescending { it.sharpness }

            val medianMse = frameStats.map { it.mse }.sorted()[frameStats.size / 2]
            val p90Mse = frameStats.map { it.mse }.sorted()[
                (frameStats.size * 0.9).toInt().coerceAtMost(frameStats.size - 1)
            ]
            val spreadRatio = if (medianMse > 1.0) p90Mse / medianMse else 1.0
            val medianBrightness = frameStats.map { it.brightness }.sorted()[frameStats.size / 2]

            val adaptiveBudget = when {
                spreadRatio < 1.5 -> (maxContributingFrames * 1.5).toInt()
                spreadRatio < 2.5 -> maxContributingFrames
                else -> (maxContributingFrames * 0.75).toInt()
            }

            // HARD RULE: at least HALF of the scanned input frames contribute, even if
            // the best half is blurry. Aggressive sharpness rejection was throwing away
            // detail: a 60-frame scan that kept 4 frames cannot produce a better stack
            // than the same burst keeping 30. The only thing allowed to reduce this is
            // the per-input memory cap (maxContributingFrames), and that is logged.
            // The absolute ceiling the selection has ever used (memory bound). 1.5x the
            // nominal budget is what the low-spread branch already allowed.
            val hardCap = (maxContributingFrames * 1.5).toInt()
            val selection = MfsrSelectionPolicy.decide(
                scannedCount = frameStats.size,
                usableCount = usable.size,
                adaptiveBudget = adaptiveBudget,
                hardCap = hardCap
            )
            val targetCount = selection.targetCount
            val fiftyPercentFloorApplied = selection.floorApplied
            val cappedBelowHalf = selection.cappedBelowHalf

            val selectedIndices = ranked
                .take(targetCount)
                .map { it.index }
                .filter { it != safeAnchorIndex }
                .sorted()

            val mseByFrame: Map<Int, Double> = mseScores.toMap()

            // The anchor is splatted at weight 1 by initAccumulation, so the frames that
            // actually contribute are selected + 1. Reporting only `selected` reads like
            // an off-by-one against `target` in a bug report.
            val contributing = selectedIndices.size + 1

            Log.i(TAG, "Selection: ${frameStats.size} scanned, medianSharp=${String.format(Locale.US, "%.1f", medianSharp)}, " +
                    "floor=${String.format(Locale.US, "%.1f", sharpnessFloor)}, usable=${usable.size}, " +
                    "selected=${selectedIndices.size} (+anchor) = contributing $contributing, " +
                    "50%-floor-applied=$fiftyPercentFloorApplied, " +
                    "target=$targetCount, hardCap=$hardCap, cappedBelowHalf=$cappedBelowHalf")
            Log.i(TAG, "Selected frame indices: $selectedIndices")

            totalUnits = scanSize + selectedIndices.size * 2 + 10

            // Re-derive the render scale from the frames that will actually contribute.
            // scanSize counts everything the pre-scan looked at; a long video can scan
            // hundreds of frames but keep only ~50. S is limited by the number of
            // independent sub-pixel samples (coverage ~1-exp(-N/S^2)), not by the scan
            // window, so choosing from scanSize would over-allocate for long videos.
            val correctedScale = chooseScale(
                context, anchorMat.cols(), anchorMat.rows(), contributing, requestedScale
            )
            if (correctedScale < renderScale) {
                Log.i(TAG, "Scale corrected ${renderScale}x -> ${correctedScale}x " +
                        "($contributing contributing frames)")
                renderScale = correctedScale
            }

            // The scan predicted from the whole scan window; now we know how many
            // frames really contribute, so the remaining phases get honest units.
            // ALIGN/FUSE units count the SELECTED frames, not `contributing`
            // (selected + anchor): the anchor is the reference frame and is never
            // aligned or re-splatted, so counting it inflated every later estimate
            // by 1/N (11% on a 10-frame burst).
            outputMp = inputMp * renderScale.toDouble() * renderScale.toDouble()
            eta?.planUnits(com.dan.timelapse.eta.EtaPhase.ALIGN, selectedIndices.size * inputMp)
            eta?.planUnits(
                com.dan.timelapse.eta.EtaPhase.FUSE,
                selectedIndices.size * (inputMp + outputMp)
            )
            eta?.planUnits(com.dan.timelapse.eta.EtaPhase.FINALIZE, outputMp)
            eta?.planUnits(com.dan.timelapse.eta.EtaPhase.SAVE, outputMp)

            // Save the comparison baseline at the final scale.
            if (renderScale > 1.0f) saveBaselineBicubic(anchorMat)

            // STEP 4: engine
            enginePtr = MFSRNative.createEngine(renderScale, anchorMat.nativeObj)
            anchorMat.release()
            if (enginePtr == 0L) {
                Log.e(TAG, "Failed to create MFSR engine")
                return false
            }
            // True 16-bit fusion: pass 2 supplies CV_16U frames and the engine emits
            // CV_16UC3. The anchor above is still 8-bit, which is what the motion gate
            // samples, so every gate threshold keeps its calibrated units.
            val use16Bit = frameSource.isHighBitDepth
            if (use16Bit) {
                MFSRNative.setHighBitDepth(enginePtr, true)
                Log.i(TAG, "16-bit fusion enabled (CV_16U samples -> CV_16UC3 output)")
            }

            MFSRNative.setSceneStats(enginePtr, medianMse, p90Mse, spreadRatio, medianBrightness)

            // STEP 5: PASS 1 — align, gate, measure photometry
            val framesToProcess = selectedIndices.size
            var processed = 0
            var accepted = 0
            lastProgressMs = 0L

            eta?.beginPhase(
                com.dan.timelapse.eta.EtaPhase.ALIGN,
                framesToProcess * inputMp
            )

            // Exactly the frames that produced a native processFrame call, in order.
            // PASS 2 must replay this same list (call ordinal == native call ordinal),
            // even if a frame later decodes differently or fails.
            val attempted = mutableListOf<Int>()

            val thermalFilter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)

            for (frameIndex in selectedIndices) {
                if (shouldCancel()) {
                    MFSRNative.destroyEngine(enginePtr); enginePtr = 0L
                    return false
                }

                if (processed > 0 && processed % 5 == 0) {
                    val battery = context.registerReceiver(null, thermalFilter)
                    val temp = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
                    if (temp > 400) {
                        val tempStr = "${temp / 10.0}°C"
                        reportPhase(
                            com.dan.timelapse.eta.EtaPhase.ALIGN,
                            processed.toDouble() / framesToProcess.coerceAtLeast(1),
                            scanSize + processed,
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
                        attempted.add(frameIndex)
                        if (ok) accepted++
                    } finally {
                        frameMat.release()
                    }
                } else {
                    Log.w(TAG, "Frame $frameIndex unavailable during pass 1 — excluded from fusion")
                }

                processed++
                val now = System.currentTimeMillis()
                if (now - lastProgressMs > 250 || processed == framesToProcess) {
                    lastProgressMs = now
                    eta?.progress(processed * inputMp)
                    reportPhase(
                        com.dan.timelapse.eta.EtaPhase.ALIGN,
                        processed.toDouble() / framesToProcess.coerceAtLeast(1),
                        scanSize + processed,
                        "Calculating matrices: $processed/$framesToProcess ($accepted accepted)"
                    )
                }
            }

            Log.i(TAG, "PASS 1 done: $accepted/$framesToProcess accepted " +
                    "(${attempted.size} frames submitted to the engine)")

            // Pass 2 replays by native-call ordinal. If the native side recorded a
            // different number of calls than Kotlin made, the mapping would be shifted
            // and every later frame placed with the previous frame's matrix. Abort
            // loudly rather than save a misaligned image.
            val nativeCalls = try {
                MFSRNative.getProcessedCallCount(enginePtr)
            } catch (e: Exception) {
                Log.e(TAG, "Could not read native call count", e)
                -1
            }
            if (nativeCalls != attempted.size) {
                Log.e(TAG, "Native call count $nativeCalls != attempted ${attempted.size} " +
                        "— aborting to avoid a misaligned replay")
                MFSRNative.destroyEngine(enginePtr); enginePtr = 0L
                return false
            }

            // Scale is limited by sub-pixel phase diversity, not frame count. A locked-off
            // tripod can supply hundreds of frames that all sample the same HR phase, so
            // 4x would allocate a grid that is mostly unsampled. Measured on a real
            // 158-frame timelapse: 7/16 cells at 4x, 6/9 at 3x -> 2x is the honest choice.
            if (accepted > 0 && renderScale >= 2.0f) {
                try {
                    val suggested = MFSRNative.suggestMaxScale(enginePtr)
                    if (suggested in 2..4 && suggested.toFloat() < renderScale) {
                        Log.i(TAG, "Phase coverage caps scale ${renderScale}x -> ${suggested}x")
                        MFSRNative.setRenderScale(enginePtr, suggested.toFloat())
                        renderScale = suggested.toFloat()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Phase-coverage scale check failed", e)
                }
            }

            if (accepted == 0) {
                // Graceful degradation: nothing could be aligned (e.g. the camera
                // moved between every shot, or the scene is textureless). Still give
                // the user a result: the anchor, upscaled and sharpened.
                Log.w(TAG, "No frames accepted — falling back to anchor-only upscale")
                try {
                    MFSRNative.initAccumulation(enginePtr)
                } catch (e: Exception) {
                    Log.e(TAG, "Anchor fallback failed", e)
                }
            }

            // STEP 6: PASS 2 — splat every accepted frame onto the HR grid.
            // callIndex is the ordinal of the matching PASS-1 native call. The native
            // side maps it to the accepted transform (or skips it), so one rejected
            // frame can never shift every later frame onto the wrong matrix.
            reportPhase(
                com.dan.timelapse.eta.EtaPhase.FUSE,
                0.0,
                scanSize + framesToProcess,
                "Rendering stabilized frame..."
            )

            var splatProcessed = 0
            // How many pass-2 frames really arrived as CV_16U. `isHighBitDepth` only
            // means "some file decoded as 16-bit"; getFrame16 refuses any frame whose
            // geometry differs from the baseline, so a 16-bit PNG can contain nothing
            // but 8-bit samples (all values multiples of 257). Reporting the ratio is
            // the only way to tell from a log.
            var wideDepthSupplied = 0
            lastProgressMs = 0L
            val splatTotal = attempted.size

            // Fuse units are frames x (input + output) megapixels: decode + splat
            // happen at input resolution, density compensation at output resolution.
            val fuseUnitsPerFrame = inputMp + outputMp
            eta?.beginPhase(
                com.dan.timelapse.eta.EtaPhase.FUSE,
                splatTotal * fuseUnitsPerFrame
            )

            if (accepted > 0) {
                for ((callIndex, frameIndex) in attempted.withIndex()) {
                    if (shouldCancel()) {
                        MFSRNative.destroyEngine(enginePtr); enginePtr = 0L
                        return false
                    }

                    // Fusion may run at 16-bit; alignment (pass 1) always used 8-bit.
                    var frameMat: Mat? = null
                    if (use16Bit) {
                        frameMat = frameSource.getFrame16(frameIndex)
                        if (frameMat != null) wideDepthSupplied++
                    }
                    if (frameMat == null) frameMat = frameSource.getFrame(frameIndex)
                    if (frameMat != null && !frameMat.empty()) {
                        try {
                            MFSRNative.accumulateFrame(enginePtr, frameMat.nativeObj, callIndex)
                        } finally {
                            frameMat.release()
                        }
                    }

                    splatProcessed++
                    val now = System.currentTimeMillis()
                    if (now - lastProgressMs > 250 || splatProcessed == splatTotal) {
                        lastProgressMs = now
                        eta?.progress(splatProcessed * fuseUnitsPerFrame)
                        reportPhase(
                            com.dan.timelapse.eta.EtaPhase.FUSE,
                            splatProcessed.toDouble() / splatTotal.coerceAtLeast(1),
                            scanSize + framesToProcess + splatProcessed,
                            "Rendering stabilized frame: $splatProcessed/$splatTotal"
                        )
                    }
                }
            }

            Log.i(TAG, "PASS 2 done: $splatProcessed frames replayed" +
                    if (use16Bit) " ($wideDepthSupplied at CV_16U, " +
                            "${splatProcessed - wideDepthSupplied} fell back to 8-bit)" else "")

            // STEP 7: finalize
            reportPhase(
                com.dan.timelapse.eta.EtaPhase.FINALIZE,
                0.0,
                scanSize + framesToProcess,
                "Generating high-res image..."
            )

            eta?.beginPhase(com.dan.timelapse.eta.EtaPhase.FINALIZE, outputMp)
            val outputMat = Mat()
            MFSRNative.finalizeEngine(enginePtr, outputMat.nativeObj)

            val telemetryLines = mutableListOf<String>()
            var accumFrames = 0
            var holeCount = 0
            var meanDensity = 0.0
            var motionThresh = 0f
            var motionRejected = 0
            try {
                val count = MFSRNative.getTelemetryCount(enginePtr)
                for (i in 0 until count) {
                    telemetryLines.add(MFSRNative.getTelemetryLine(enginePtr, i))
                }
                accumFrames = MFSRNative.getAccumulatedFrames(enginePtr)
                holeCount = MFSRNative.getHoleCount(enginePtr)
                meanDensity = MFSRNative.getMeanDensity(enginePtr)
                motionThresh = MFSRNative.getMotionThreshold(enginePtr)
                motionRejected = MFSRNative.getMotionRejectedSamples(enginePtr)
            } catch (e: Exception) {
                Log.w(TAG, "Diagnostics capture failed", e)
            }

            MFSRNative.destroyEngine(enginePtr); enginePtr = 0L

            if (outputMat.empty()) {
                Log.e(TAG, "finalize produced empty output")
                outputMat.release()
                return false
            }
            // Belt and braces for the historical black-JPEG failure: an all-zero frame
            // is never a valid super-resolution result. Refuse to save it rather than
            // reporting success.
            val grayCheck = Mat()
            try {
                Imgproc.cvtColor(outputMat, grayCheck, Imgproc.COLOR_BGR2GRAY)
                if (Core.countNonZero(grayCheck) == 0) {
                    Log.e(TAG, "finalize produced an all-black output — refusing to save")
                    outputMat.release()
                    return false
                }
            } catch (e: Exception) {
                Log.w(TAG, "Black-output sanity check failed", e)
            } finally {
                grayCheck.release()
            }
            Log.i(TAG, "Output: ${outputMat.cols()}x${outputMat.rows()}, " +
                    "splattedFrames=$accumFrames, holes=$holeCount, " +
                    "density=${String.format(Locale.US, "%.2f", meanDensity)}, " +
                    "motionGate=${String.format(Locale.US, "%.1f", motionThresh)}, " +
                    "motionRejectedSamples=$motionRejected")

            // STEP 8: save
            reportPhase(
                com.dan.timelapse.eta.EtaPhase.SAVE, 0.0, totalUnits - 5, "Saving image..."
            )
            eta?.beginPhase(com.dan.timelapse.eta.EtaPhase.SAVE, outputMp)
            // A CV_16U Mat CANNOT be stored in a JPEG. OpenCV's imwrite fallback does
            // convertTo(CV_8U) with NO scale factor, so every value >= 1 clips to 255:
            // the file is a pure-white image while imwrite still returns true. The
            // target extension is chosen before the real depth is known (the full-res
            // export path hardcodes .jpg, and the pre-render flag only probes the first
            // few files), so redirect to a sibling .png instead of silently destroying
            // the 16-bit result the user asked for.
            val targetFile = if (outputMat.depth() == CvType.CV_16U &&
                !outputFile.name.endsWith(".png", ignoreCase = true)) {
                val png = File(outputFile.parentFile, outputFile.nameWithoutExtension + ".png")
                Log.w(TAG, "16-bit output cannot be written to ${outputFile.name} " +
                        "(JPEG clips CV_16U without scaling) — writing ${png.name} instead")
                png
            } else {
                outputFile
            }
            val params = if (targetFile.name.endsWith(".png", ignoreCase = true)) {
                MatOfInt(Imgcodecs.IMWRITE_PNG_COMPRESSION, 3)
            } else {
                MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 97)
            }
            val saved = Imgcodecs.imwrite(targetFile.absolutePath, outputMat, params)

            if (!saved) {
                Log.e(TAG, "imwrite failed")
                params.release()
                outputMat.release()
                System.gc()
                return false
            }
            writtenOutputFile = targetFile
            Log.i(TAG, "Saved raw: ${targetFile.absolutePath} (${outputMat.cols()}x${outputMat.rows()})")

            // 2048px preview for UI display
            try {
                val maxPreviewDim = 2048
                val ow = outputMat.cols()
                val oh = outputMat.rows()
                if (ow > maxPreviewDim || oh > maxPreviewDim) {
                    val scale = maxPreviewDim.toDouble() / kotlin.math.max(ow, oh)
                    val previewW = (ow * scale).toInt()
                    val previewH = (oh * scale).toInt()
                    val previewMat = Mat()
                    // A 16-bit output cannot be JPEG-encoded directly.
                    val previewSource = if (outputMat.depth() == CvType.CV_16U) {
                        Mat().also { outputMat.convertTo(it, CvType.CV_8UC3, 1.0 / 257.0) }
                    } else {
                        outputMat
                    }
                    Imgproc.resize(previewSource, previewMat, Size(previewW.toDouble(), previewH.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
                    if (previewSource !== outputMat) previewSource.release()
                    val previewFile = File(
                        targetFile.parent,
                        "${targetFile.nameWithoutExtension}_preview.jpg"
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

            // Telemetry log
            try {
                val logFile = File(targetFile.parent, "${targetFile.nameWithoutExtension}_log.txt")
                logFile.writeText(buildString {
                    appendLine("=== MFSR Telemetry ===")
                    appendLine("Output: ${targetFile.name}")
                    appendLine("Output depth: ${if (outputMat.depth() == CvType.CV_16U) "16-bit" else "8-bit"}")
                    appendLine("Output dimensions: ${outputMat.cols()}x${outputMat.rows()}")
                    appendLine("Requested scale: $requestedScale   Render scale: $renderScale")
                    appendLine("Input source: ${frameSource.javaClass.simpleName}")
                    appendLine("Input resolution: ${frameSource.width}x${frameSource.height}")
                    appendLine("Anchor frame: $anchorFrameIndex")
                    appendLine("Total frames available: $totalFrames")
                    appendLine("Trim bounds: [$effectiveTrimStart..$effectiveTrimEnd]")
                    appendLine("Scan window: [$effectiveStart..$effectiveEnd] (size=$scanSize)")
                    appendLine("Frames usable after sharpness gate: ${usable.size}")
                    appendLine("Frames selected: ${selectedIndices.size}")
                    appendLine("Frames accepted by alignment: $accepted")
                    appendLine("Frames splatted: $accumFrames")
                    appendLine("HR holes: $holeCount (filled by density compensation)")
                    appendLine("Mean samples per HR pixel: ${String.format(Locale.US, "%.2f", meanDensity)}")
                    appendLine("Motion gate threshold: ${String.format(Locale.US, "%.1f", motionThresh)} levels")
                    appendLine("Samples excluded as moving: $motionRejected")
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

            reportPhase(
                com.dan.timelapse.eta.EtaPhase.SAVE, 1.0, totalUnits, "Complete"
            )
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
