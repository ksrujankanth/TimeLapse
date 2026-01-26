package com.dan.timelapse.filters

import com.dan.timelapse.images.ImageTools
import org.opencv.core.Mat

class MedianFramesFilter(
    size: Int,
    skipIncompleteWindows: Boolean = true,
    nextConsumer: FramesConsumer
) : MultiFramesFilter(size, !skipIncompleteWindows, nextConsumer) {

    private val outputFrame = Mat()

    override fun stopFilter() {
        outputFrame.release()
        super.stopFilter()
    }

    override fun consume(index: Int, removedFrame: Mat, frames: List<Mat>) {
        if (frames.isNotEmpty()) {
            ImageTools.calculateMedianFrame(frames, outputFrame)
            next(index, outputFrame)
        }
    }
}
