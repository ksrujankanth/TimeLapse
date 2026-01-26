package com.dan.timelapse.filters

import org.opencv.core.Mat
import org.opencv.photo.Photo

class HDRFramesFilter(private val size: Int, nextConsumer: FramesConsumer)
    : MultiFramesFilter(size, true, nextConsumer) {
    private val hdrFrame = Mat()
    private val outputFrame = Mat()
    private val mergeMertens = Photo.createMergeMertens()

    override fun stopFilter() {
        hdrFrame.release()
        outputFrame.release()
        // mergeMertens.release() // REMOVE THIS LINE
        super.stopFilter()
    }
    override fun consume(index: Int, removedFrame: Mat, frames: List<Mat>) {

        if (frames.size < size) return // Not enough frames yet
        mergeMertens.process(frames, hdrFrame) // Pass the list of Mats

        if (!hdrFrame.empty()) {
            hdrFrame.convertTo(outputFrame, frames[0].type(), 255.0)
            next(index, outputFrame)
        }
    }
}
