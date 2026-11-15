package com.dan.timelapse.framesinput

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.dan.timelapse.utils.UriFile
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.videoio.VideoCapture
import org.opencv.videoio.Videoio
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class VideoFramesInput(private val _uriFile: UriFile) : FramesInput() {
    companion object {
        private const val TAG = "VideoFramesInput"
    }

    // V3.0 Architecture: The Temp File Bridge
    private var tempFile: File? = null

    private var _fps: Int = 0
    private var _width: Int = 0
    private var _height: Int = 0
    private var _size: Int = 0
    private var _rotation: Int = 0
    private val _name: String

    init {
        _name = _uriFile.name

        // 1. Create the Temp File Bridge immediately
        tempFile = File(_uriFile.context.cacheDir, "vid_${UUID.randomUUID()}.mp4")
        copyToTempFile()
        // Log whether the file actually exists (handles silent null-inputStream case)
        if (tempFile?.exists() == true && (tempFile?.length() ?: 0L) > 0L) {
            Log.d(TAG, "Temp file created at: ${tempFile?.absolutePath}")
        } else {
            Log.w(TAG, "Temp file missing after init — will retry on first VideoCapture open")
        }

        // 2. Extract Metadata natively
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(_uriFile.context, _uriFile.uri)
            _rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
            _width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
            _height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
            _size = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toInt() ?: 0
            
            // Extract FPS from metadata if available
            val fpsStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
            _fps = fpsStr?.toIntOrNull() ?: 30
        } catch (e: Exception) {
            Log.e(TAG, "Metadata extraction failed", e)
            _fps = 30 // Default fallback
        } finally {
            retriever.release()
        }

        // 3. Fallback size verification + FPS extraction
        if (_size <= 0 || _fps <= 0) {
            val testCapture = openVideoCapture()
            if (testCapture.isOpened) {
                if (_size <= 0) _size = testCapture.get(Videoio.CAP_PROP_FRAME_COUNT).toInt()
                
                // Extract true FPS directly from the decoder
                val realFps = testCapture.get(Videoio.CAP_PROP_FPS).toInt()
                if (realFps > 0) _fps = realFps 
                
                testCapture.release()
            }
        }

        if (_size <= 0) _size = 100 // Final fallback
        if (_fps <= 0) _fps = 30 // Final fallback
        Log.d(TAG, "Initialized: ${_width}x${_height}, size=$_size, fps=$_fps, rotation=$_rotation")
    }

    override val fps: Int get() = _fps
    override val name: String get() = _name
    override var width: Int = if (_rotation == 90 || _rotation == 270) _height else _width
    override var height: Int = if (_rotation == 90 || _rotation == 270) _width else _height
    override val videoUri: Uri get() = _uriFile.uri
    override val size: Int get() = _size

    private fun copyToTempFile() {
        val file = tempFile ?: return
        try {
            _uriFile.context.contentResolver.openInputStream(_uriFile.uri)?.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy to temp file", e)
        }
    }

    private fun ensureTempFile(): Boolean {
        val file = tempFile ?: return false
        // Fast path: file already exists and has content
        if (file.exists() && file.length() > 0L) return true
        // Slow path: re-copy from original URI
        Log.w(TAG, "Temp file missing or empty, re-copying from original URI")
        copyToTempFile()
        val ok = file.exists() && file.length() > 0L
        if (!ok) Log.e(TAG, "Temp file still missing after re-copy")
        return ok
    }

    private fun openVideoCapture(): VideoCapture {
        val path = tempFile?.absolutePath ?: return VideoCapture()

        // Ensure the temp file exists before trying VideoCapture
        if (!ensureTempFile()) {
            Log.e(TAG, "Cannot open VideoCapture — temp file unavailable")
            return VideoCapture()
        }

        // Priority 1: Hardware-accelerated CAP_ANDROID decoding
        var cap = VideoCapture(path, Videoio.CAP_ANDROID)
        if (cap.isOpened) {
            Log.d(TAG, "Opened via CAP_ANDROID: $path")
            return cap
        }
        cap.release()

        // Priority 2: Standard decoding fallback
        cap = VideoCapture(path, Videoio.CAP_ANY)
        if (cap.isOpened) {
            Log.d(TAG, "Opened via CAP_ANY: $path")
            return cap
        }
        cap.release()

        Log.e(TAG, "Failed to open VideoCapture for path: $path")
        return VideoCapture()
    }

    override fun firstFrame(): Mat {
        return getFrame(0) ?: Mat()
    }

    override fun forEachFrame(start: Int, progress: (Int, Int) -> Unit, callback: (Int, Int, Mat) -> Boolean) {
        val videoCapture = openVideoCapture()
        if (!videoCapture.isOpened) {
            Log.e(TAG, "forEachFrame: Failed to open video capture.")
            return
        }

        try {
            videoCapture.set(Videoio.CAP_PROP_CONVERT_RGB, 1.0)
            if (start > 0) {
                videoCapture.set(Videoio.CAP_PROP_POS_FRAMES, start.toDouble())
                
                // Fix 1: Fast-forward to exact start frame (keyframe trap)
                var currentPos = videoCapture.get(Videoio.CAP_PROP_POS_FRAMES).toInt()
                while (currentPos < start) {
                    if (!videoCapture.grab()) break
                    currentPos++
                }
            }

            var counter = start
            val totalFramesToProcess = (_size - start).coerceAtLeast(0)
            while (counter < _size) {
                try { Thread.sleep(1) } catch (_: InterruptedException) {}
                
                val rawFrame = Mat()
                try {
                    if (!videoCapture.read(rawFrame) || rawFrame.empty()) {
                        // Skip corrupted frames but keep the loop alive to fulfill the required frame count
                        counter++ // Still increment to keep position accurate
                        continue
                    }

                    val processedFrame = Mat()
                    try {
                        when (_rotation) {
                            90 -> Core.rotate(rawFrame, processedFrame, Core.ROTATE_90_CLOCKWISE)
                            180 -> Core.rotate(rawFrame, processedFrame, Core.ROTATE_180)
                            270 -> Core.rotate(rawFrame, processedFrame, Core.ROTATE_90_COUNTERCLOCKWISE)
                            else -> rawFrame.copyTo(processedFrame)
                        }

                        progress(counter - start, totalFramesToProcess)
                        if (!callback(counter, _size, processedFrame)) break
                    } finally {
                        processedFrame.release()
                    }
                } finally {
                    rawFrame.release()
                }
                counter++
            }
        } finally {
            videoCapture.release()
        }
    }

    override fun getFrame(index: Int): Mat? {
        val videoCapture = openVideoCapture()
        return try {
            videoCapture.set(Videoio.CAP_PROP_CONVERT_RGB, 1.0)
            videoCapture.set(Videoio.CAP_PROP_POS_FRAMES, index.toDouble())

            // Fix 1: Check where the decoder actually landed (keyframe trap)
            var currentPos = videoCapture.get(Videoio.CAP_PROP_POS_FRAMES).toInt()

            // Fast-forward silently until we hit the exact requested index
            while (currentPos < index) {
                if (!videoCapture.grab()) break
                currentPos++
            }

            val rawFrame = Mat()
            if (!videoCapture.read(rawFrame) || rawFrame.empty()) {
                rawFrame.release()
                null
            } else {
                val processedFrame = Mat()
                when (_rotation) {
                    90 -> Core.rotate(rawFrame, processedFrame, Core.ROTATE_90_CLOCKWISE)
                    180 -> Core.rotate(rawFrame, processedFrame, Core.ROTATE_180)
                    270 -> Core.rotate(rawFrame, processedFrame, Core.ROTATE_90_COUNTERCLOCKWISE)
                    else -> rawFrame.copyTo(processedFrame)
                }
                rawFrame.release()
                processedFrame
            }
        } finally {
            videoCapture.release()
        }
    }

    override fun release() {
        tempFile?.let {
            if (it.exists()) it.delete()
        }
    }
}
