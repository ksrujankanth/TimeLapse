package com.dan.timelapse.utils

import com.dan.timelapse.Effect

/**
 * The input-speed (frame decimation) rule, in one place.
 *
 * "High speed = fewer frames used in render" means, literally, N source frames
 * become 1 output frame. That applies to every effect: a timelapse of a stack is
 * still a timelapse, and the stack simply sees N times fewer frames.
 *
 * Super Resolution is the single deliberate exception. MFSR needs as many frames
 * as it can get for sub-pixel phase coverage, and it is rendered by
 * MFSRProcessor, never by VideoProcessor, so it always reports 1x.
 *
 * Sampling is a stride relative to the FIRST frame of the trimmed range, never
 * `index % stride`. An absolute modulo silently drops the first frame of a clip
 * whose trim start is not on a stride boundary, which both loses frame 0 and makes
 * the delivered count disagree with the progress denominator.
 */
object InputSpeed {

    const val NO_SKIP = 1

    /** Effective stride for [effect] given the user's requested multiplier. */
    fun strideFor(effect: Effect, requested: Int): Int =
        if (effect == Effect.SUPER_RESOLUTION) NO_SKIP
        else requested.coerceAtLeast(NO_SKIP)

    /** Exact number of frames delivered from a [spanFrames] window at this stride. */
    fun deliveredCount(spanFrames: Int, stride: Int): Int {
        val span = spanFrames.coerceAtLeast(1)
        val step = stride.coerceAtLeast(NO_SKIP)
        return ((span - 1) / step) + 1
    }

    /** True when the frame at [offset] from the window start is kept. */
    fun keeps(offset: Int, stride: Int): Boolean {
        val step = stride.coerceAtLeast(NO_SKIP)
        return step == NO_SKIP || offset % step == 0
    }

    /**
     * A stacking window larger than the number of delivered frames emits ZERO
     * output frames (`SumFramesFilter` only outputs once `frames.size >= size`).
     * At 60x a 100-frame clip delivers 2 frames, so a 10-frame Average window
     * would produce an empty video. Clamp the window to what actually exists.
     */
    fun effectiveWindow(framesBlended: Int, deliveredFrames: Int): Int =
        framesBlended.coerceIn(1, deliveredFrames.coerceAtLeast(1))

    /**
     * Output frames a sliding-window filter emits for [deliveredFrames] inputs.
     *
     * A window of N is only full on input N and slides by one afterwards, so the
     * count is `delivered - window + 1`. Because [effectiveWindow] clamps the
     * window to the delivered count, a clip that delivers no more frames than the
     * window collapses to EXACTLY ONE output frame — a 0-second MP4 that no
     * gallery can play. Callers must treat anything below 2 as no video at all.
     */
    fun windowedOutputCount(deliveredFrames: Int, framesBlended: Int): Int {
        val delivered = deliveredFrames.coerceAtLeast(0)
        val window = effectiveWindow(framesBlended, delivered)
        return (delivered - window + 1).coerceAtLeast(0)
    }
}
