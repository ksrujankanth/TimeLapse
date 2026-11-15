package com.dan.timelapse.filters

import com.dan.timelapse.images.ImageTools
import org.opencv.core.Mat
import org.opencv.core.Scalar

// For Effect.ENDLESS_MOTION_EXTRACTION (video output: diff between very first frame and current frame)
class EndlessCumulativeMotionFramesFilter(nextConsumer: FramesConsumer) : FramesFilter(nextConsumer) {
    private var firstEverFrame: Mat? = null
    private val outputFrame = Mat()

    override fun startFilter() {
        firstEverFrame?.release()
        firstEverFrame = null
        outputFrame.release()
        super.startFilter()
    }

    override fun consume(index: Int, currentFrame: Mat) {
        if (outputFrame.empty() || outputFrame.size() != currentFrame.size() || outputFrame.type() != currentFrame.type()) {
            outputFrame.create(currentFrame.size(), currentFrame.type())
        }

        if (firstEverFrame == null) {
            firstEverFrame = currentFrame.clone()
            // Skip index 0 output to avoid the grey/blank initial frame
            return
        } else {
            if (firstEverFrame!!.empty() || currentFrame.empty() ||
                firstEverFrame!!.size() != currentFrame.size() || firstEverFrame!!.type() != currentFrame.type()) {
                // Mismatch, output gray
                outputFrame.setTo(Scalar(127.0, 127.0, 127.0))
            } else {
                // Diff currentFrame - firstEverFrame using wrapper for proper error handling
                ImageTools.generateMotionExtractionFrame(currentFrame, firstEverFrame!!, outputFrame)
            }
        }
        next(index, outputFrame)
    }

    override fun stopFilter() {
        firstEverFrame?.release()
        firstEverFrame = null
        outputFrame.release()
        super.stopFilter()
    }

    fun reset() {
        firstEverFrame?.release()
        firstEverFrame = null
        if (!outputFrame.empty()) {
            outputFrame.release()
        }
    }
}
