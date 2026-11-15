package com.dan.timelapse.filters

import org.opencv.core.Mat

abstract class MultiFramesFilter(private val size: Int, private val notifyOnPartial: Boolean, nextConsumer: FramesConsumer)
    : FramesFilter(nextConsumer) {

    private val frames = mutableListOf<Mat>()

    abstract fun consume(index: Int, removedFrame: Mat, frames: List<Mat>)

    override fun consume(index: Int, frame: Mat) {
        frames.add(frame.clone())

        val removedFrame = if (frames.size > size) frames.removeFirst() else Mat()

        if (frames.size >= size || notifyOnPartial) {
            consume(index, removedFrame, frames)
        }

        removedFrame.release()
    }

    override fun stopFilter() {
        frames.forEach{ frame -> frame.release() } //force to free memory immediately
        frames.clear()

        super.stopFilter()
    }

    open fun reset() {
        for (frame in frames) {
            if (frame != null && !frame.empty()) {
                frame.release()
            }
        }
        frames.clear()
    }

    override fun getWindowSize(): Int {
        val nextWindow = super.getWindowSize()
        if (nextWindow == 0) return 0
        return size + nextWindow - 1
    }
}
