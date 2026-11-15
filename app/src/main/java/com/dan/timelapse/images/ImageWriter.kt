package com.dan.timelapse.images

import com.dan.timelapse.filters.FramesConsumer
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.imgcodecs.Imgcodecs
import java.io.File

class ImageWriter(private val file: File, private val quality: Int) : FramesConsumer {
    var success = false
        private set

    override fun start() {
        success = false
    }

    override fun consume(index: Int, frame: Mat) {
        val params = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, quality)
        Imgcodecs.imwrite(file.absolutePath, frame, params)
        params.release()
        success = true
    }

    override fun stop(canceled: Boolean) {
    }
}