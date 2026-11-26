package com.dan.timelapse.stabilization

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure camera-path math for the stabilizer. No Android, no OpenCV, so every
 * invariant here is unit-tested on the JVM.
 *
 * All maps are 2x3 affines in "center-pivot" form: a rotation is expressed as
 * R(x - c) + c + t. Storing the map as a matrix (instead of a scalar
 * (dx, dy, angle) triple) makes composition exact. A scalar accumulator is only
 * valid for infinitesimal angles; at one degree of accumulated roll it is off by
 * |c| * theta ~= 19 px at 1080p.
 */
data class Affine(
    val m00: Double,
    val m01: Double,
    val m02: Double,
    val m10: Double,
    val m11: Double,
    val m12: Double
) {
    val angle: Double get() = atan2(m10, m00)

    /** this∘other: apply [other] first, then this. */
    fun compose(other: Affine): Affine = Affine(
        m00 * other.m00 + m01 * other.m10,
        m00 * other.m01 + m01 * other.m11,
        m00 * other.m02 + m01 * other.m12 + m02,
        m10 * other.m00 + m11 * other.m10,
        m10 * other.m01 + m11 * other.m11,
        m10 * other.m02 + m11 * other.m12 + m12
    )

    fun x(x: Double, y: Double): Double = m00 * x + m01 * y + m02
    fun y(x: Double, y: Double): Double = m10 * x + m11 * y + m12

    fun inverse(): Affine {
        val det = m00 * m11 - m01 * m10
        require(abs(det) > 1e-12) { "singular affine" }
        val i00 = m11 / det
        val i01 = -m01 / det
        val i10 = -m10 / det
        val i11 = m00 / det
        return Affine(
            i00, i01, -(i00 * m02 + i01 * m12),
            i10, i11, -(i10 * m02 + i11 * m12)
        )
    }

    /** How far the point (x, y) moves under this map. */
    fun driftAt(x: Double, y: Double): Double = hypot(x(x, y) - x, y(x, y) - y)

    companion object {
        val IDENTITY = Affine(1.0, 0.0, 0.0, 0.0, 1.0, 0.0)

        /**
         * OpenCV's `estimateAffinePartial2D` returns `[R | rawT]` with the origin
         * as pivot. This is the literal pixel map, kept as-is; use
         * `toCamState(cx, cy)` to read the motion of the image center.
         */
        fun fromRaw(rawDx: Double, rawDy: Double, angle: Double): Affine {
            val c = cos(angle)
            val s = sin(angle)
            return Affine(c, -s, rawDx, s, c, rawDy)
        }

        /**
         * Map that rotates about (cx, cy) and moves the image center by (tx, ty):
         *   x' = c + R(x - c) + t
         * This is the only form the stabilizer composes and applies, so rotation
         * can never leak into translation.
         */
        fun aboutCenter(
            cx: Double,
            cy: Double,
            angle: Double,
            tx: Double = 0.0,
            ty: Double = 0.0
        ): Affine {
            val c = cos(angle)
            val s = sin(angle)
            // c - Rc + t
            return Affine(
                c, -s, cx - (c * cx - s * cy) + tx,
                s, c, cy - (s * cx + c * cy) + ty
            )
        }

        fun zoom(cx: Double, cy: Double, factor: Double): Affine = Affine(
            factor, 0.0, cx - factor * cx,
            0.0, factor, cy - factor * cy
        )
    }
}

/** Rigid state of one frame relative to the first analyzed frame. */
data class CamState(
    val angle: Double = 0.0,
    val tx: Double = 0.0,
    val ty: Double = 0.0,
    val valid: Boolean = true
) {
    /** (tx, ty) is the displacement of the image center, in pixels. */
    fun toAffine(cx: Double, cy: Double): Affine = Affine.aboutCenter(cx, cy, angle, tx, ty)
}

fun Affine.toCamState(cx: Double, cy: Double, valid: Boolean = true): CamState =
    CamState(
        angle = angle,
        tx = x(cx, cy) - cx,
        ty = y(cx, cy) - cy,
        valid = valid
    )

/**
 * Result of a stabilization analysis: the warp to apply to each frame, the
 * uniform crop needed to keep the frame covered, and a quality figure.
 */
data class StabilizationPlan(
    val warps: List<Affine>,
    val zoom: Double,
    val residualRmsPx: Double,
    val analyzedFrames: Int
) {
    val isEmpty: Boolean get() = warps.isEmpty()
}

/** Hard ceiling on how much of the frame we will crop. */
const val MAX_AUTO_CROP_ZOOM = 1.10

/**
 * Estimation noise floor. A locked-off shot should not be resampled at all, so
 * corrections below these thresholds snap to identity and the warp is skipped.
 * Both are far below one pixel / the eye's detection threshold.
 */
const val DEADBAND_PX = 0.05
const val DEADBAND_ANGLE_RAD = 2e-4

private fun snapDeadband(state: CamState): CamState {
    if (abs(state.tx) <= DEADBAND_PX &&
        abs(state.ty) <= DEADBAND_PX &&
        abs(state.angle) <= DEADBAND_ANGLE_RAD
    ) {
        return CamState()
    }
    return state
}

/**
 * Smallest uniform zoom that keeps every output pixel inside the source for a
 * given correction. Derived analytically: the back-projected output corners must
 * land inside the source rect.
 *
 *   s >= (W|cos| + H|sin|) / (W - 2|t'x|)
 *   s >= (W|sin| + H|cos|) / (H - 2|t'y|)
 *
 * where t' is the translation expressed in the rotated frame.
 */
fun requiredZoom(width: Int, height: Int, state: CamState): Double {
    if (!state.valid) return 1.0
    val w = width.toDouble()
    val h = height.toDouble()
    val c = cos(state.angle)
    val s = sin(state.angle)
    val rotatedX = abs(state.tx * c + state.ty * s)
    val rotatedY = abs(-state.tx * s + state.ty * c)
    val denomX = w - 2.0 * rotatedX
    val denomY = h - 2.0 * rotatedY
    val zoomX = if (denomX > 1e-6) (w * abs(c) + h * abs(s)) / denomX else Double.MAX_VALUE
    val zoomY = if (denomY > 1e-6) (w * abs(s) + h * abs(c)) / denomY else Double.MAX_VALUE
    return max(1.0, max(zoomX, zoomY))
}

fun requiredZoom(width: Int, height: Int, states: List<CamState>): Double {
    var zoom = 1.0
    for (state in states) zoom = max(zoom, requiredZoom(width, height, state))
    return zoom
}

/**
 * Make a correction fit inside the crop budget by clamping its translation and,
 * only if even that cannot fit, its rotation. Clamping the translation is a
 * continuous operation, so the output never jumps.
 */
fun clampToCrop(width: Int, height: Int, state: CamState, zoom: Double): CamState {
    if (!state.valid) return state
    val w = width.toDouble()
    val h = height.toDouble()
    var angle = state.angle

    // Maximum rotation the crop can absorb at all.
    val maxAngle = maxFeasibleAngle(width, height, zoom)
    if (abs(angle) > maxAngle) angle = maxAngle * if (angle < 0) -1.0 else 1.0

    val c = cos(angle)
    val s = sin(angle)
    val marginX = (w - (w * abs(c) + h * abs(s)) / zoom) / 2.0
    val marginY = (h - (w * abs(s) + h * abs(c)) / zoom) / 2.0
    if (marginX < 0.0 || marginY < 0.0) return CamState(angle, 0.0, 0.0, state.valid)

    val rotatedX = state.tx * c + state.ty * s
    val rotatedY = -state.tx * s + state.ty * c
    val clampedX = rotatedX.coerceIn(-marginX, marginX)
    val clampedY = rotatedY.coerceIn(-marginY, marginY)
    val tx = clampedX * c - clampedY * s
    val ty = clampedX * s + clampedY * c

    // Ignore sub-hundredth-pixel noise so the sign never flickers.
    val fixedTx = if (abs(tx) < 1e-4) 0.0 else tx
    val fixedTy = if (abs(ty) < 1e-4) 0.0 else ty
    return CamState(angle, fixedTx, fixedTy, state.valid)
}

/** Largest |angle| (radians) a crop of [zoom] can cover with zero translation. */
fun maxFeasibleAngle(width: Int, height: Int, zoom: Double): Double {
    val w = width.toDouble()
    val h = height.toDouble()
    // W|cos| + H|sin| = L*sin(theta + phi),  phi = atan2(W, H)
    // W|sin| + H|cos| = L*sin(theta + psi),  psi = atan2(H, W) = pi/2 - phi
    val l = sqrt(w * w + h * h)
    val phi = atan2(w, h)
    val a = Math.asin(min(1.0, zoom * w / l)) - phi
    val b = Math.asin(min(1.0, zoom * h / l)) - (Math.PI / 2.0 - phi)
    return max(0.0, min(a, b))
}

/**
 * Zero-phase Gaussian smoothing of one channel.
 *
 * The signal is extended by point reflection at both ends. Reflection (rather
 * than clamping) keeps a linear ramp exactly linear, so a constant pan has no
 * edge attenuation and no lag.
 */
fun smoothChannel(values: DoubleArray, sigmaFrames: Double): DoubleArray {
    val n = values.size
    if (n < 2 || sigmaFrames <= 0.0) return values.copyOf()

    val radius = max(1, kotlin.math.ceil(sigmaFrames * 4.0).toInt())
    val extended = DoubleArray(n + radius * 2)
    for (i in 0 until n) extended[radius + i] = values[i]
    for (k in 1..radius) {
        // Point reflection: f(-k) = 2 f(0) - f(k)
        extended[radius - k] = 2.0 * values[0] - values[min(k, n - 1)]
        extended[radius + n - 1 + k] = 2.0 * values[n - 1] - values[max(n - 1 - k, 0)]
    }

    val twoSigmaSq = 2.0 * sigmaFrames * sigmaFrames
    val weights = DoubleArray(radius * 2 + 1)
    for (k in -radius..radius) weights[k + radius] = exp(-(k * k).toDouble() / twoSigmaSq)

    val out = DoubleArray(n)
    for (i in 0 until n) {
        var sum = 0.0
        var sumW = 0.0
        val center = i + radius
        for (k in -radius..radius) {
            val weight = weights[k + radius]
            sum += extended[center + k] * weight
            sumW += weight
        }
        out[i] = if (sumW < 1e-12) values[i] else sum / sumW
    }
    return out
}

/**
 * Smooth a camera path with a zero-phase Gaussian.
 *
 * The window is symmetric, so a linear pan passes through unchanged (no lag).
 * Indices are mirrored at the ends, which keeps the same property at the edges;
 * naive "renormalize at the edge" pulls a ramp toward zero.
 */
fun smoothZeroPhase(path: List<CamState>, sigmaFrames: Double): List<CamState> {
    if (path.size < 2 || sigmaFrames <= 0.0) return path

    val angles = smoothChannel(unwrapAngles(path.map { it.angle }), sigmaFrames)
    val xs = smoothChannel(DoubleArray(path.size) { path[it].tx }, sigmaFrames)
    val ys = smoothChannel(DoubleArray(path.size) { path[it].ty }, sigmaFrames)

    return List(path.size) { i ->
        CamState(
            angle = angles[i],
            tx = xs[i],
            ty = ys[i],
            valid = path[i].valid
        )
    }
}

/** Remove 2*pi jumps so a path can be filtered as a continuous signal. */
fun unwrapAngles(raw: List<Double>): DoubleArray {
    val out = DoubleArray(raw.size)
    if (raw.isEmpty()) return out
    out[0] = raw[0]
    for (i in 1 until raw.size) {
        var delta = raw[i] - raw[i - 1]
        while (delta > Math.PI) delta -= 2.0 * Math.PI
        while (delta < -Math.PI) delta += 2.0 * Math.PI
        out[i] = out[i - 1] + delta
    }
    return out
}

/** Replace invalid samples with the last known state (never invent motion). */
fun holdInvalid(path: List<CamState>): List<CamState> {
    if (path.isEmpty()) return path
    val out = ArrayList<CamState>(path.size)
    var last = CamState()
    for (state in path) {
        if (state.valid) last = state
        out.add(last)
    }
    return out
}

/**
 * Append one tracked frame to the camera path.
 *
 * INVARIANT: a rejected-but-fitted estimate must never be composed onto the path.
 * [StabilizationTracker] returns the fitted delta even when it fails its sanity gate
 * (only an outright failure returns identity). Folding that delta in offsets every
 * later frame permanently, because [holdInvalid] repairs only the invalid slot — the
 * frames after it were already accumulated from the bogus value. A rejected frame
 * therefore HOLDS the last valid state and contributes no motion.
 */
fun appendTrackedState(
    path: List<CamState>,
    delta: Affine,
    valid: Boolean,
    centerX: Double,
    centerY: Double
): CamState {
    val step = if (valid) delta else Affine.IDENTITY
    val previous = path.lastOrNull { it.valid } ?: CamState()
    return previous.toAffine(centerX, centerY)
        .compose(step)
        .toCamState(centerX, centerY, valid)
}

/**
 * Build the final warp list.
 *
 * @param cumulative per-frame map from that frame back to the first analyzed frame
 * @param anchor true = pin to the first frame, false = keep pans and remove jitter
 * @param smoothingWindow full window width in frames (sigma = window / 6)
 * @param maxCrop maximum zoom the user allows (1.0 = never crop)
 * @param cropOverride pin the crop instead of solving it; used by the causal
 *        preview path so the zoom cannot breathe while scrubbing
 */
fun buildPlan(
    cumulative: List<CamState>,
    width: Int,
    height: Int,
    anchor: Boolean,
    smoothingWindow: Int,
    maxCrop: Double,
    centerX: Double,
    centerY: Double,
    cropOverride: Double? = null
): StabilizationPlan {
    if (cumulative.isEmpty()) {
        return StabilizationPlan(emptyList(), 1.0, 0.0, 0)
    }

    // W_i: maps frame i back to the first analyzed frame (inverse camera path).
    val inversePath = holdInvalid(cumulative)
    // P_i: the camera path itself, i.e. where the content actually moved.
    val cameraPath = inversePath.map { state ->
        state.toAffine(centerX, centerY)
            .inverse()
            .toCamState(centerX, centerY, state.valid)
    }
    val sigma = (smoothingWindow.coerceIn(2, 400)) / 6.0
    val desiredPath = if (anchor) {
        List(cameraPath.size) { CamState() }
    } else {
        smoothZeroPhase(cameraPath, sigma)
    }

    // C_i = S_i ∘ W_i. Applying the full inverse pins the plane (anchor);
    // applying it through a smoothed desired path keeps pans and removes jitter.
    //
    // Composing the smoothed path with the inverse camera path is the exact form of
    // the high-pass correction. For pure translation this is C = S + W ~ 0 (the
    // identity, with the pan passing through); note it is NOT "W - S", which would
    // double the pan. Applying S alone (the old behaviour) cancels the pan and
    // leaves only the jitter.
    val corrections = List(inversePath.size) { i ->
        desiredPath[i].toAffine(centerX, centerY)
            .compose(inversePath[i].toAffine(centerX, centerY))
    }
    val correctionStates = corrections
        .map { it.toCamState(centerX, centerY) }
        .map { snapDeadband(it) }

    val rawZoom = requiredZoom(width, height, correctionStates)
    val cap = maxCrop.coerceIn(1.0, MAX_AUTO_CROP_ZOOM)
    val zoom = (cropOverride ?: rawZoom).coerceIn(1.0, cap)
    val crop = Affine.zoom(centerX, centerY, zoom)

    val warps = ArrayList<Affine>(corrections.size)
    var sumSq = 0.0
    for (i in corrections.indices) {
        // With a crop budget of 1.0 the correction cannot be covered by any zoom, so
        // the raw motion would be applied with BORDER_REPLICATE and smear one edge
        // column across the frame. "Max crop: None" must mean a clean passthrough.
        val clamped = if (zoom > 1.0000001) {
            clampToCrop(width, height, correctionStates[i], zoom)
        } else {
            CamState()
        }
        if (!anchor) {
            val residualX = cameraPath[i].tx - desiredPath[i].tx
            val residualY = cameraPath[i].ty - desiredPath[i].ty
            sumSq += residualX * residualX + residualY * residualY
        }
        warps.add(crop.compose(clamped.toAffine(centerX, centerY)))
    }
    val rms = if (warps.isEmpty()) 0.0 else sqrt(sumSq / warps.size)
    return StabilizationPlan(warps, zoom, rms, cumulative.size)
}
