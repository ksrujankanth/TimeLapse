package com.dan.timelapse.filters

import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc.*

class ScaleFramesFilter(private val crop: Boolean, private val width: Int, private val height: Int, nextConsumer: FramesConsumer) : FramesFilter(nextConsumer) {
    private val scaledMat = Mat()
    private var fullMat = Mat()

    private fun scaleFrame(frame: Mat): Mat {
        if (frame.width() == width && frame.height() == height) return frame

        if (crop) {
            val scaleUp = frame.width() < width || frame.height() < height
            val scaleAlgorithm = if (scaleUp) INTER_LANCZOS4 else INTER_AREA

            var newWidth = width
            var newHeight = (frame.height().toDouble() * width / frame.width()).toInt()

            if (newHeight < height) {
                newHeight = height
                newWidth = (frame.width().toDouble() * height / frame.height()).toInt()
            }

            resize( frame, scaledMat, org.opencv.core.Size(newWidth.toDouble(), newHeight.toDouble()), 0.0, 0.0, scaleAlgorithm )
            return scaledMat.submat(Rect((scaledMat.width() - width) / 2, (scaledMat.height() - height) / 2, width, height))
        }

        if (fullMat.empty() || fullMat.width() != width || fullMat.height() != height) {
            fullMat.release()
            fullMat = Mat(height, width, frame.type(), Scalar(0.0, 0.0, 0.0))
        } else {
            fullMat.setTo(Scalar(0.0, 0.0, 0.0))
        }

        val scaleUp = frame.width() < width && frame.height() < height
        val scaleAlgorithm = if (scaleUp) INTER_LANCZOS4 else INTER_AREA

        var newWidth = width
        var newHeight = (frame.height().toDouble() * width / frame.width()).toInt()

        if (newHeight > height) {
            newHeight = height
            newWidth = (frame.width().toDouble() * height / frame.height()).toInt()
        }

        resize( frame, scaledMat, org.opencv.core.Size(newWidth.toDouble(), newHeight.toDouble()), 0.0, 0.0, scaleAlgorithm )
        
        val x = (width - scaledMat.width()) / 2
        val y = (height - scaledMat.height()) / 2
        val subMat = fullMat.submat(Rect(x, y, scaledMat.width(), scaledMat.height()))
        scaledMat.copyTo(subMat)
        subMat.release()
        
        return fullMat
    }

    override fun stopFilter() {
        scaledMat.release()
        fullMat.release()
        super.stopFilter()
    }

    override fun consume(index: Int, frame: Mat) {
        next(index, scaleFrame(frame))
    }
}
