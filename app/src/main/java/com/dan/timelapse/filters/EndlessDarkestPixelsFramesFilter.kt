package com.dan.timelapse.filters

import com.dan.timelapse.images.ImageTools
import org.opencv.core.Mat

class EndlessDarkestPixelsFramesFilter(nextConsumer: FramesConsumer): FramesFilter(nextConsumer) {
    private val accumulator = Mat()

    override fun startFilter() {
        accumulator.release()
        super.startFilter()
    }

    override fun stopFilter() {
        accumulator.release()
        super.stopFilter()
    }

    override fun consume(index: Int, frame: Mat) {
        // Accumulate darkest pixels using pure OpenCV Core.min
        ImageTools.accumulateDarkest(accumulator, frame)
        
        // Pass the accumulated canvas to the writer
        val outputFrame = Mat()
        accumulator.copyTo(outputFrame)
        next(index, outputFrame)
        outputFrame.release()
    }

    override fun getWindowSize(): Int = 0

    fun reset() {
        if (!accumulator.empty()) {
            accumulator.release()
        }
    }
}
