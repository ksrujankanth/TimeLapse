package com.dan.timelapse.filters

import com.dan.timelapse.images.ImageTools
import org.opencv.core.Mat
import org.opencv.core.Scalar

/**
 * MotionExtractionWindowFramesFilter applies a sliding window motion extraction.
 * It computes the difference between the first and last frame in the window.
 */
class MotionExtractionWindowFramesFilter(
    size: Int,
    nextConsumer: FramesConsumer
) : MultiFramesFilter(size, false, nextConsumer) {

    private val outputFrame = Mat()

    override fun stopFilter() {
        outputFrame.release()
        super.stopFilter()
    }

    override fun consume(index: Int, removedFrame: Mat, frames: List<Mat>) {
        if (frames.isEmpty()) return

        val firstInWindow = frames.first()
        val lastInWindow = frames.last()

        if (outputFrame.empty() || outputFrame.size() != firstInWindow.size() || outputFrame.type() != firstInWindow.type()) {
            outputFrame.create(firstInWindow.size(), firstInWindow.type())
        }

        // Safety: If window is incomplete or slider > total frames, output gray
        if (frames.size < 2) {
            outputFrame.setTo(Scalar(127.0, 127.0, 127.0))
        } else {
            // Use the wrapper function for proper error handling
            ImageTools.generateMotionExtractionFrame(lastInWindow, firstInWindow, outputFrame)
        }
        next(index, outputFrame)
    }
}
