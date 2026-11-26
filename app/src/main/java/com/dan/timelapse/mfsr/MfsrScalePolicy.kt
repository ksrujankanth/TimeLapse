package com.dan.timelapse.mfsr

import kotlin.math.sqrt

/**
 * Pure MFSR render-scale policy.
 *
 * Kept out of [MFSRProcessor] so the ladder, the memory cap and the degenerate inputs
 * (0 frames, NaN request) can be unit-tested on the JVM. The processor only supplies
 * the device numbers (available RAM, 32/64-bit) and logs the decision.
 *
 * Coverage of the HR grid by N randomly-phased samples is ~1-exp(-N/S^2):
 * S=2 needs ~10 frames, S=3 needs ~40, S=4 needs ~75 for >99% coverage. Asking for
 * 4x from 10 photos leaves holes, which is why the frame ladder is an upper bound.
 */
object MfsrScalePolicy {

    const val BYTES_PER_HR_PIXEL = 17.0
    const val MAX_OUTPUT_PIXELS = 64_000_000.0
    const val THIRTY_TWO_BIT_OUTPUT_PIXELS = 16_000_000.0

    data class Decision(
        val scale: Float,
        val byMemory: Float,
        val byFrames: Float
    )

    /** Scale implied by the number of contributing frames alone. */
    fun frameCountScale(frameCount: Int): Float = when {
        frameCount >= 75 -> 4.0f
        frameCount >= 40 -> 3.0f
        frameCount >= 8 -> 2.0f
        frameCount >= 3 -> 1.5f
        else -> 1.0f
    }

    fun decide(
        inputPixels: Double,
        frameCount: Int,
        requested: Float,
        availableBytes: Double,
        is64Bit: Boolean
    ): Decision {
        val hardCapPixels = if (is64Bit) MAX_OUTPUT_PIXELS else THIRTY_TWO_BIT_OUTPUT_PIXELS
        val memFraction = if (is64Bit) 0.40 else 0.20

        val byMemory = if (inputPixels > 0.0) {
            sqrt((availableBytes * memFraction / BYTES_PER_HR_PIXEL) / inputPixels).toFloat()
        } else {
            1f
        }
        val byFrames = frameCountScale(frameCount)

        // minOf propagates NaN deliberately: an invalid request must land on 1x
        // (the loop below simply never matches and `chosen` stays 1f).
        val raw = minOf(requested, byMemory, byFrames)
        val steps = floatArrayOf(4f, 3f, 2f, 1.5f, 1f)
        var chosen = 1f
        for (step in steps) {
            if (raw >= step - 1e-3f) {
                chosen = step
                break
            }
        }

        // Final hard cap: never allocate a grid larger than the process can hold.
        while (chosen > 1f && inputPixels * chosen * chosen > hardCapPixels) {
            chosen = when {
                chosen >= 4f -> 3f
                chosen >= 3f -> 2f
                chosen >= 2f -> 1.5f
                else -> 1f
            }
        }
        return Decision(chosen, byMemory, byFrames)
    }
}
