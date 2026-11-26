package com.dan.timelapse.mfsr

/**
 * Frame-count policy for MFSR selection.
 *
 * The sharpness floor decides which frames are "usable", but it must never drop the
 * selection below HALF of the scanned input. A 60-frame scan that keeps 4 frames
 * cannot produce a better stack than the same burst keeping 30, even if the best half
 * is blurry: a blurry frame still carries a valid sub-pixel sample.
 *
 * The only thing allowed to reduce the result below half is the per-input memory cap
 * (1.5 * maxContributingFrames), and [Decision.cappedBelowHalf] reports when that
 * happens so a bug report can see it in the log.
 */
object MfsrSelectionPolicy {

    data class Decision(
        /** Number of frames to keep (including the anchor if it is in the ranked list). */
        val targetCount: Int,
        /** True when the 50% floor selected frames that failed the sharpness floor. */
        val floorApplied: Boolean,
        /** True when the memory cap forced the selection below half the input. */
        val cappedBelowHalf: Boolean
    )

    fun decide(
        scannedCount: Int,
        usableCount: Int,
        adaptiveBudget: Int,
        hardCap: Int
    ): Decision {
        if (scannedCount <= 0) return Decision(0, false, false)
        val halfOfInput = (scannedCount + 1) / 2
        val wanted = maxOf(usableCount, adaptiveBudget, halfOfInput)
        val target = minOf(scannedCount, wanted, hardCap).coerceAtLeast(0)
        return Decision(
            targetCount = target,
            floorApplied = target > usableCount,
            cappedBelowHalf = halfOfInput > hardCap
        )
    }
}
