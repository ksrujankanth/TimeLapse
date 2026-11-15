package com.dan.timelapse.utils

import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc

object ImageUtils {
    fun applyZoomToFill(frame: Mat, targetWidth: Int, targetHeight: Int): Mat {
        val scaleW = targetWidth.toDouble() / frame.cols()
        val scaleH = targetHeight.toDouble() / frame.rows()
        val scale = Math.max(scaleW, scaleH)

        val resized = Mat()
        Imgproc.resize(frame, resized, org.opencv.core.Size(), scale, scale, Imgproc.INTER_LANCZOS4)
        
        val x = (resized.cols() - targetWidth) / 2
        val y = (resized.rows() - targetHeight) / 2
        val roi = Rect(x, y, targetWidth, targetHeight)
        return Mat(resized, roi)
    }
}