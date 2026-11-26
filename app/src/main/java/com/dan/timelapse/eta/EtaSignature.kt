package com.dan.timelapse.eta

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.roundToInt

/**
 * What kind of long-running job is being measured.
 *
 * Rates are expressed per *work unit* (see [EtaUnits]) and statistics never cross
 * a kind boundary, because a "unit" means something different for a video render
 * (frame-megapixels) and for a folder scan (files).
 */
enum class EtaKind {
    VIDEO_RENDER,
    MFSR_RENDER,
    STILL_RENDER,
    LOAD_MEDIA
}

/**
 * Phases a job can be split into. The names are persisted, so treat them as a
 * stable schema: adding is fine, renaming resets the history for that phase.
 */
enum class EtaPhase {
    /** Decode / probe the input before any real work. */
    LOAD,
    /**
     * Reading image HEADERS only (`inJustDecodeBounds`) to learn the baseline
     * resolution. Measured at ~1 ms per file, 20-30x cheaper than a full decode,
     * so it cannot share [LOAD]'s prior: that made a 0.1 s probe claim "~5s left".
     */
    PROBE,
    /** MFSR proxy pre-scan (sharpness / exposure / similarity). */
    SCAN,
    /** Folder listing / filtering. Units are files. */
    FILTER,
    /** Indexing the filtered files into URI handles. Units are files. */
    INDEX,
    /** Stabilization motion analysis (video pass 1). */
    ANALYZE,
    /** MFSR pass 1 — ECC/ORB alignment + photometry. */
    ALIGN,
    /** Main render loop (effects, splice, encode). */
    RENDER,
    /** MFSR pass 2 — splat every accepted frame onto the HR grid. */
    FUSE,
    /** Encoder flush / muxer stop. */
    ENCODE,
    /** MFSR finalize + density compensation + sharpening. */
    FINALIZE,
    /** MP4 timestamp rewrite. */
    RETIME,
    /** Audio time-stretch + mux. */
    MUX,
    /** Image / video write to storage. */
    SAVE,
    /**
     * Time inside a job that no phase covered: encoder/writer construction, the
     * first-frame setup, and the gaps between phases.
     *
     * Units are 1 (one job), so this is learned as "ms of setup per job". Without
     * it a 300-frame render measured 29.8 s while its phases only accounted for
     * ~14 s; the next prediction was 24% short. Recorded when a job finishes.
     */
    OVERHEAD
}

/**
 * Everything about a job that measurably changes its speed, bucketed so that
 * similar jobs share statistics.
 *
 * Bucketing is deliberate. An exact (width, height, frame count) key would never
 * repeat, so nothing would ever be learned; too coarse and a 12 MP render trains
 * a 640x360 thumbnail. The buckets are roughly half a stop of megapixels and one
 * octave of frame count.
 */
data class EtaSignature(
    val kind: EtaKind,
    /** Effect name, e.g. "NONE", "MEDIAN_STACK", "SUPER_RESOLUTION". */
    val effect: String,
    val megapixelBucket: Int,
    val frameBucket: Int,
    val stabilized: Boolean,
    /** Render scale as a short label: "1", "1.5", "2", "3", "4". */
    val scale: String,
    val ramGb: Int,
    /** Charger connected. Level-0 only; see [levelKey]. */
    val plugged: Boolean
) {
    /**
     * Backoff key for [level]. Level 0 is the full identity of the job; level 3
     * is just the kind, which is what a brand-new effect/resolution falls back to.
     */
    fun levelKey(level: Int): String = when (level) {
        0 -> "${kind.name}|$effect|mp$megapixelBucket|fr$frameBucket|" +
                "st${if (stabilized) 1 else 0}|sc$scale|ram$ramGb|pw${if (plugged) 1 else 0}"
        1 -> "${kind.name}|$effect|mp$megapixelBucket"
        2 -> "${kind.name}|mp$megapixelBucket"
        else -> kind.name
    }

    companion object {
        const val LEVELS = 4

        /** ~0.5 stop of megapixels per bucket. 12 MP -> 7, 2 MP -> 2, 0.3 MP -> -3. */
        fun megapixelBucket(megapixels: Double): Int {
            if (!megapixels.isFinite() || megapixels <= 0.0) return 0
            return (log2(megapixels) * 2.0).roundToInt().coerceIn(-6, 16)
        }

        /** One octave of frame count. 60 -> 6, 1500 -> 11, 1 -> 0. */
        fun frameBucket(frames: Int): Int {
            if (frames <= 1) return 0
            return (log2(frames.toDouble())).roundToInt().coerceIn(0, 20)
        }

        fun scaleLabel(scale: Float): String = when {
            abs(scale - 1.0f) < 0.01f -> "1"
            abs(scale - 1.5f) < 0.01f -> "1.5"
            else -> scale.roundToInt().toString()
        }
    }
}

/**
 * Turns a job into the scalar "work" that a phase's rate is measured against.
 *
 * The effect multipliers are priors, not measurements. Their only job is to stop
 * the coarse backoff levels from being biased when they mix a cheap bypass
 * render with an expensive median stack. The learned per-key rate remains the
 * authority — if the multiplier is wrong by 2x for some effect, that effect's
 * exact-key statistics absorb the whole error.
 */
object EtaUnits {

    fun megapixels(width: Int, height: Int): Double =
        (width.toDouble() * height.toDouble()) / 1_000_000.0

    /** Units for the video render loop: frames x output megapixels x effect cost. */
    fun videoRenderUnits(
        frames: Int,
        outWidth: Int,
        outHeight: Int,
        effect: String,
        stackWindow: Int
    ): Double {
        if (frames <= 0) return 0.0
        val mp = megapixels(outWidth, outHeight).coerceAtLeast(0.01)
        return frames.toDouble() * mp * effectCost(effect, stackWindow)
    }

    /** Units for stabilization analysis: it works at a fixed analysis width. */
    fun analysisUnits(frames: Int, outWidth: Int, outHeight: Int): Double {
        if (frames <= 0) return 0.0
        return frames.toDouble() * megapixels(outWidth, outHeight).coerceAtLeast(0.01)
    }

    fun stillUnits(outWidth: Int, outHeight: Int): Double =
        megapixels(outWidth, outHeight).coerceAtLeast(0.01)

    /**
     * Relative cost of one output frame for an effect.
     *
     * Two shapes, because the filters genuinely behave differently:
     *
     *  - **Running-sum filters** (`SumFramesFilter` and subclasses: Add, Average
     *    and the weighted averages) keep a 16-bit accumulator and add/subtract one
     *    frame per input. Their cost does NOT grow with the window.
     *  - **Window filters** (HDR, Transition) touch every frame in the window for
     *    every output, so the window is part of the cost.
     *  - **Median, Lightest, Darkest, Motion, Difference** are near-constant in the
     *    window: the merge is a per-pixel pass, not a per-output sort.
     *
     * Calibrated against the real render loop, window 10:
     *   AVERAGE w3, 14 x 960x540  -> 96 ms per frame-megapixel   (Pixel 4a)
     *   MEDIAN  w4, 40 x 1280x720 -> 325 ms per frame-megapixel  (Pixel 4a)
     *   MEDIAN w10, 40 x 1280x720 -> 332 ms per frame-megapixel  (Pixel 4a)
     *   NONE  300 x 1920x1080 loop -> 43.4 ms per frame-megapixel (Pixel 4a, in app)
     * The six effects below were measured on a Moto X Play RELATIVE to MEDIAN at
     * windows 2 and 10 (`EtaEffectCostBenchmark`), which is device-independent and
     * reproduced MEDIAN's known cost to 2%. At a base rate of 62 ms/unit these
     * give: AVERAGE 1.5, MEDIAN 5.2, NONE 0.80, Lightest 2.5, Darkest 2.5,
     * Motion 2.7, Difference 2.4, HDR 7.2+0.46/window, Transition 2.5+0.50/window.
     */
    fun effectCost(effect: String, stackWindow: Int = 1): Double {
        val window = stackWindow.coerceIn(1, 120)
        val extra = window - 1
        return when (effect) {
            // A "no effect" render is NOT free: it is a full 1080p MediaCodec
            // encode per frame. Measured in the app: 300 frames of 1920x1080 with
            // effect NONE took ~27 s in the render loop = 43.4 ms per
            // frame-megapixel. With base 62 ms/unit and deviceFactor 0.88 that is
            // a cost of 0.80. (The old 0.45 assumed bypass = clone + encode at
            // ~8 ms/frame and made the first-run prediction of a real render short.)
            "NONE" -> 0.80
            // Running-sum family: window-independent.
            "ADD", "ENDLESS_ADD" -> 1.3
            "AVERAGE", "ENDLESS_AVERAGE" -> 1.5
            "AVERAGE_WEIGHTED_FOR_LAST", "ENDLESS_AVERAGE_WEIGHTED_FOR_LAST" -> 1.75
            "AVERAGE_WEIGHTED_FOR_LIGHT", "ENDLESS_AVERAGE_WEIGHTED_FOR_LIGHT" -> 1.9
            // Native median: measured near-flat in the window.
            "MEDIAN_STACK", "ENDLESS_MEDIAN_STACK" -> 5.2 + 0.02 * extra
            // Per-pixel merges: also near-flat. Measured 2.3-2.7 at w2 and w10.
            "LIGHTEST_PIXELS", "ENDLESS_LIGHTEST_PIXELS" -> 2.5
            "DARKEST_PIXELS", "ENDLESS_DARKEST_PIXELS" -> 2.5
            "MOTION_EXTRACTION", "ENDLESS_MOTION_EXTRACTION" -> 2.7
            "DIFFERENCE", "ENDLESS_DIFFERENCE" -> 2.4
            // Genuinely per-pixel-per-window-frame.
            "HDR" -> 7.2 + 0.46 * extra
            "TRANSITION" -> 2.5 + 0.50 * extra
            else -> 1.5
        }
    }

    /** Units are positive and finite, or the sample is not usable. */
    fun sanitize(units: Double): Double =
        if (units.isFinite() && units > 0.0) units else 0.0

    internal fun logRatio(actual: Double, predicted: Double): Double =
        if (actual > 0 && predicted > 0 && actual.isFinite() && predicted.isFinite())
            ln(actual / predicted) else 0.0
}
