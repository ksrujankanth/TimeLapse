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
}
