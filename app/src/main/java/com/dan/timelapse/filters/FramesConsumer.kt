package com.dan.timelapse.filters

import org.opencv.core.Mat

interface FramesConsumer {
    fun start()
    fun stop(canceled: Boolean)
    fun consume(index: Int, frame: Mat)
    
    /**
     * Returns the number of input frames needed to produce the next output frame.
     * 0 means all frames from the beginning are needed (cumulative/endless).
     * 1 means only the current frame is needed.
     * >1 means a sliding window of that size is needed.
     */
    fun getWindowSize(): Int = 0

    /**
     * Input frames this consumer must buffer before it can emit its FIRST
     * output frame. 0 means it emits from the first input (passthrough,
     * endless accumulators).
     *
     * A stack filter returns its window size, so a run of fewer inputs than
     * this produces ONE frame — and a one-frame MP4 is a 0-second file, not a
     * video. [com.dan.timelapse.VideoProcessor] refuses to publish that.
     */
    val stackingWindowFrames: Int get() = 0
}
