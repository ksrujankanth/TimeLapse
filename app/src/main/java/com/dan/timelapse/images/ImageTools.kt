package com.dan.timelapse.images

import android.util.Log
import org.opencv.core.Mat
import org.opencv.utils.Converters
import java.nio.ByteBuffer

class ImageTools {
    companion object {
        fun mergeLightestPixels(images: List<Mat>, output: Mat) {
            val imagesMat = Converters.vector_Mat_to_Mat(images)
            try {
                mergeLightestPixelsNative(imagesMat.nativeObj, output.nativeObj)
            } finally {
                imagesMat.release()
            }
        }

        fun mergeDarkestPixels(images: List<Mat>, output: Mat) {
            val imagesMat = Converters.vector_Mat_to_Mat(images)
            try {
                mergeDarkestPixelsNative(imagesMat.nativeObj, output.nativeObj)
            } finally {
                imagesMat.release()
            }
        }

        fun generateMotionExtractionFrame(currentFrame: Mat, previousFrame: Mat, outputFrame: Mat) {
            generateMotionExtractionFrameNative(currentFrame.nativeObj, previousFrame.nativeObj, outputFrame.nativeObj)
        }

        fun calculateMedianFrame(frames: List<Mat>, outputFrame: Mat) {
            if (frames.isEmpty()) return
            val nativeObjectAddresses = LongArray(frames.size) { i -> frames[i].nativeObj }
            calculateMedianFrameNative(nativeObjectAddresses, outputFrame.nativeObj)
        }

        fun accumulateMedian(median: Mat, frame: Mat) {
            accumulateMedianNative(median.nativeObj, frame.nativeObj)
        }

        fun processEndlessMedianStrip(
            inputBuffer: ByteBuffer,
            outputBuffer: ByteBuffer,
            width: Int,
            stripHeight: Int,
            frameCount: Int
        ) {
            processEndlessMedianStripNative(inputBuffer, outputBuffer, width, stripHeight, frameCount)
        }

        fun updateMedianHistogram(histogram: Mat, frame: Mat, output: Mat, frameCount: Int) {
            updateMedianHistogramNative(histogram.nativeObj, frame.nativeObj, output.nativeObj, frameCount)
        }

        private external fun calculateMedianFrameNative(sourceFramesAddrs: LongArray, outputFrameAddr: Long)
        private external fun accumulateMedianNative(medianAddr: Long, frameAddr: Long)
        private external fun mergeLightestPixelsNative(images: Long, output: Long)
        private external fun mergeDarkestPixelsNative(images: Long, output: Long)
        private external fun generateMotionExtractionFrameNative(currentFrameAddr: Long, previousFrameAddr: Long, outputFrameAddr: Long)
        
        private external fun processEndlessMedianStripNative(
            inputBuffer: ByteBuffer,
            outputBuffer: ByteBuffer,
            width: Int,
            stripHeight: Int,
            frameCount: Int
        )

        private external fun updateMedianHistogramNative(histogramAddr: Long, frameAddr: Long, outputAddr: Long, frameCount: Int)
    }
}
