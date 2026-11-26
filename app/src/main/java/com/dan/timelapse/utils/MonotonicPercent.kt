package com.dan.timelapse.utils

/**
 * Monotonic percentage for a progress UI.
 *
 * A progress bar may never move backwards. MFSR originally emitted raw
 * (current, total) pairs, but its `totalUnits` denominator is refined after frame
 * selection — with 181 scanned and 10 provisional units the bar reached 95 %, then
 * the denominator grew and the first pass-1 callback reported 62 %. This converts
 * any (done, total) pair into a percentage that only ever increases.
 */
class MonotonicPercent {
    private var last = 0

    fun reset() {
        last = 0
    }

    @Synchronized
    fun update(done: Long, total: Long): Int {
        val pct = if (total <= 0L) 0 else ((done * 100L) / total).toInt().coerceIn(0, 100)
        if (pct > last) last = pct
        return last
    }
}
