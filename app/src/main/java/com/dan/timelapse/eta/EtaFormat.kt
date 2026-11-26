package com.dan.timelapse.eta

import kotlin.math.ceil

/**
 * Display formatting for a remaining-time estimate.
 *
 * Two deliberate choices:
 *  - everything is prefixed with `~`, because a learned estimate is a
 *    prediction, not a promise;
 *  - values are quantised upward (ceil) in coarse steps, so the string does not
 *    flicker between "2m 31s" and "2m 30s" every update. Rounding up also means
 *    the countdown errs on the safe side of "it finished sooner than I was told".
 */
object EtaFormat {

    const val CALCULATING = "Calculating..."
    const val ALMOST_DONE = "Almost done"

    /** Full form for a dialog title, e.g. `~2m 30s`. */
    fun format(remainingMs: Long): String {
        if (remainingMs < 0L) return CALCULATING
        if (remainingMs <= 1200L) return ALMOST_DONE
        val seconds = quantize(remainingMs)
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return buildString {
            append('~')
            when {
                hours > 0 -> {
                    append(hours).append('h')
                    if (minutes > 0) append(' ').append(minutes).append('m')
                }
                minutes > 0 -> {
                    append(minutes).append('m')
                    if (secs > 0) append(' ').append(secs).append('s')
                }
                else -> append(secs).append('s')
            }
        }
    }

    /** Compact suffix for a status line, e.g. `~12s left`. */
    fun formatSuffix(remainingMs: Long): String {
        if (remainingMs < 0L) return ""
        if (remainingMs <= 1200L) return ""
        return "${format(remainingMs)} left"
    }

    private fun quantize(remainingMs: Long): Long {
        val seconds = ceil(remainingMs / 1000.0).toLong().coerceAtLeast(1)
        return when {
            seconds < 60 -> ((seconds + 4) / 5) * 5
            seconds < 600 -> ((seconds + 14) / 15) * 15
            else -> ((seconds + 59) / 60) * 60
        }
    }
}
