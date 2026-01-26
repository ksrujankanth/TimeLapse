package com.dan.timelapse.framesinput

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.dan.timelapse.utils.UriFile
import com.dan.timelapse.video.VideoTools
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import org.opencv.videoio.VideoCapture
import org.opencv.videoio.Videoio.*
import java.io.File
import java.io.FileOutputStream
import java.io.FileNotFoundException
import java.util.UUID

class VideoFramesInput(private val _uriFile: UriFile) : FramesInput() {
    companion object {
        private const val TAG = "VideoFramesInput"
    }

    private val tempFile: File = File(_uriFile.context.cacheDir, "video_input_${UUID.randomUUID()}.mp4")
    private var _fps: Int = 0
    private var _width: Int = 0
    private var _height: Int = 0
    private var _size: Int = 0
    private var _rotation: Int = 0
    private val _name: String

    init {
        _name = fixName(_uriFile.name)
        
        try {
            _uriFile.context.contentResolver.openInputStream(_uriFile.uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: throw FileNotFoundException("Could not open video stream")
        } catch (e: Exception) {
            if (tempFile.exists()) tempFile.delete()
            throw FileNotFoundException("Failed to cache video: ${e.message}")
        }

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(tempFile.absolutePath)
            _rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
            _width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
            _height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
            val frameCount = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toInt() ?: 0
            _size = frameCount
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get metadata", e)
        } finally {
            retriever.release()
        }

        val videoCapture = VideoCapture(tempFile.absolutePath)
        if (videoCapture.isOpened) {
            if (_fps <= 0) _fps = videoCapture.get(CAP_PROP_FPS).toInt()
            if (_width <= 0) _width = videoCapture.get(CAP_PROP_FRAME_WIDTH).toInt()
            if (_height <= 0) _height = videoCapture.get(CAP_PROP_FRAME_HEIGHT).toInt()
            if (_size <= 0) _size = videoCapture.get(CAP_PROP_FRAME_COUNT).toInt()
            videoCapture.release()
        }

        if (_size <= 0) _size = VideoTools.countFrames(_uriFile.context, Uri.fromFile(tempFile))
        if (_fps <= 0) _fps = 30
    }

    override val fps: Int get() = _fps
    override val name: String get() = _name
    override val width: Int get() = if (_rotation == 90 || _rotation == 270) _height else _width
    override val height: Int get() = if (_rotation == 90 || _rotation == 270) _width else _height
    override val videoUri: Uri get() = _uriFile.uri
    override val size: Int get() = _size

    override fun forEachFrame(startFrom: Int, seekCallback: ((Int) -> Unit)?, callback: (Int, Int, Mat) -> Boolean) {
        val videoCapture = VideoCapture(tempFile.absolutePath)
        if (!videoCapture.isOpened) return

        try {
            videoCapture.set(CAP_PROP_ORIENTATION_AUTO, 1.0)
            
            var actualCapturePos = 0
            if (startFrom > 0) {
                videoCapture.set(CAP_PROP_POS_FRAMES, startFrom.toDouble())
                var currentPos = videoCapture.get(CAP_PROP_POS_FRAMES).toInt()
                
                if (currentPos < 0) currentPos = 0
                if (currentPos > startFrom) {
                    videoCapture.set(CAP_PROP_POS_FRAMES, 0.0)
                    currentPos = 0
                }
                
                var framesSkipped = currentPos
                while (framesSkipped < startFrom) {
                    if (!videoCapture.grab()) break
                    framesSkipped++
                    if (framesSkipped % 20 == 0) {
                        seekCallback?.invoke(framesSkipped)
                    }
                }
                actualCapturePos = framesSkipped
            }

            var counter = actualCapturePos
            val rawFrame = Mat()
            val rgbFrame = Mat()
            val rotatedFrame = Mat()

            while (counter < _size && videoCapture.read(rawFrame)) {
                if (counter < startFrom) {
                    counter++
                    continue
                }

                if (rawFrame.empty()) {
                    counter++
                    continue
                }
                
                // CRITICAL FIX: Enhanced stability for color conversion
                // The logs show OpenCV failing because it doesn't know the input channel count correctly.
                try {
                    val channels = rawFrame.channels()
                    when (channels) {
                        4 -> Imgproc.cvtColor(rawFrame, rgbFrame, Imgproc.COLOR_RGBA2RGB)
                        3 -> Imgproc.cvtColor(rawFrame, rgbFrame, Imgproc.COLOR_BGR2RGB)
                        1 -> Imgproc.cvtColor(rawFrame, rgbFrame, Imgproc.COLOR_GRAY2RGB)
                        else -> rawFrame.copyTo(rgbFrame)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "cvtColor failed, falling back to copy", e)
                    rawFrame.copyTo(rgbFrame)
                }

                if (rgbFrame.empty()) {
                    counter++
                    continue
                }

                val finalFrame = when (_rotation) {
                    90 -> {
                        Core.rotate(rgbFrame, rotatedFrame, Core.ROTATE_90_CLOCKWISE)
                        rotatedFrame
                    }
                    180 -> {
                        Core.rotate(rgbFrame, rotatedFrame, Core.ROTATE_180)
                        rotatedFrame
                    }
                    270 -> {
                        Core.rotate(rgbFrame, rotatedFrame, Core.ROTATE_90_COUNTERCLOCKWISE)
                        rotatedFrame
                    }
                    else -> rgbFrame
                }

                if (!callback(counter, _size, finalFrame)) break
                counter++
            }
            rawFrame.release()
            rgbFrame.release()
            rotatedFrame.release()
        } finally {
            videoCapture.release()
        }
    }

    fun loadThumbnail(index: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(tempFile.absolutePath)
            retriever.getFrameAtIndex(index)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get thumbnail at $index", e)
            null
        } finally {
            retriever.release()
        }
    }

    override fun release() {
        if (tempFile.exists()) {
            tempFile.delete()
        }
    }
}
