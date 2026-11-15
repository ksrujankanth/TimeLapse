package com.dan.timelapse.filters

import android.content.Context
import com.dan.timelapse.images.ImageTools
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.min

/**
 * Endless Median Filter - Dynamic Strategy
 *
 * 1. Small Sequences (In-Memory): Uses calculateMedianFrameNative with all mats.
 * 2. Large Sequences (Tiled): Uses Disk-Streaming to process in strips.
 *
 * Always produces 100% accurate True Median (rank-order).
 */
class EndlessMedianFramesFilter(
    private val context: Context,
    private val is4K: Boolean,
    private val nextConsumer: FramesConsumer
) : FramesFilter(nextConsumer) {

    var onBusyStateChanged: ((Boolean) -> Unit)? = null
    var onProgressWithStatus: ((Int, Int, String) -> Unit)? = null // (current, total, statusText)

    private var width = 0
    private var height = 0
    private var frameCount = 0
    var totalInputFrames: Int = 0 // Public for direct access from VideoProcessor

    // Flag to track if we're in tiling mode
    var isTilingMode: Boolean = false // Public for VideoProcessor to check

    // Threshold for in-memory processing based on available RAM
    // FIX 3: Use ActivityManager to get actual physical RAM, not JVM heap limit
    private val ramLimit: Long by lazy {
        val activityManager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memoryInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        
        // Use 60% of ACTUAL available physical RAM for native OpenCV allocations
        (memoryInfo.availMem * 0.6).toLong()
    }

    // --- State for In-Memory ---
    private val allFrames = mutableListOf<Mat>()

    // --- State for Tiling ---
    private val STRIP_HEIGHT = 64
    private var tempRawFile: File? = null
    private var rawChannel: FileChannel? = null

    // Pre-allocated buffers for disk I/O optimization (avoids GC stutter)
    private var reusableByteArray: ByteArray? = null
    private var reusableByteBuffer: java.nio.ByteBuffer? = null

    /**
     * Estimates if we should use tiling (disk-streaming) instead of in-memory.
     * Tiling is significantly slower but memory-safe.
     * We prefer in-memory (calculateMedianFrame) if it fits.
     */
    private fun isUsingTiling(): Boolean {
        if (frameCount < 20) return false
        
        // Cost to hold all frames in memory: frameCount * width * height * 3 (CV_8UC3)
        // Plus some overhead for OpenCV and the final output Mat.
        val totalMemoryNeeded = frameCount.toLong() * width * height * 3
        
        // Check if total memory needed exceeds our ramLimit (70% of max heap)
        return totalMemoryNeeded > ramLimit
    }

    override fun consume(index: Int, frame: Mat) {
        if (width == 0) {
            width = frame.width()
            height = frame.height()
            
            // Allocate exactly once
            val frameSize = width * height * 3
            reusableByteArray = ByteArray(frameSize)
            reusableByteBuffer = java.nio.ByteBuffer.allocateDirect(frameSize)
        }

        frameCount++

        // During live generation/preview, we collect frames. 
        // We defer the decision to tile until we actually need to process all of them 
        // if they don't fit in memory.
        
        // However, for "Endless" mode, we want to show intermediate results.
        // For preview, we use in-memory median of whatever we have so far, 
        // but if it gets too large, we might have to stop collecting in RAM.

        val totalMemoryNeeded = (allFrames.size + 1).toLong() * width * height * 3
        
        if (totalMemoryNeeded < ramLimit) {
            allFrames.add(frame.clone())
        } else {
            // Memory pressure: start caching to disk if not already doing so
            if (rawChannel == null) {
                isTilingMode = true
                tempRawFile = File(context.cacheDir, "endless_median_cache.tmp")
                tempRawFile?.delete()
                rawChannel = RandomAccessFile(tempRawFile, "rw").channel
                
                // Flush existing in-memory frames to disk
                allFrames.forEach { m ->
                    writeFrameToDisk(m)
                    m.release()
                }
                allFrames.clear()
            }
            writeFrameToDisk(frame)
        }

        // Note: Progress during consume() is now handled by VideoProcessor to avoid race conditions

        // Only deliver preview if frames fit in memory (fast path)
        // If using tiling, don't deliver anything here - wait for stop() to do final processing
        if (rawChannel == null && allFrames.size == frameCount) {
            val output = Mat()
            ImageTools.Companion.calculateMedianFrameNative(allFrames.map { it.nativeObj }.toLongArray(), output.nativeObj)
            next(index, output)
            output.release()
        }
        // When tiling, we don't deliver preview during collection - wait for final result in stop()
    }

    private fun writeFrameToDisk(frame: Mat) {
        val array = reusableByteArray ?: return
        val buffer = reusableByteBuffer ?: return
        
        buffer.clear() // Reset position to 0
        frame.get(0, 0, array) // Extract pixels into the reused array
        buffer.put(array)
        buffer.flip() // Prepare for writing
        
        rawChannel?.write(buffer)
    }

    override fun stop(canceled: Boolean) {
        if (canceled || frameCount == 0) {
            cleanup()
            super.stop(canceled)
            return
        }

        // Final processing - do this BEFORE calling super.stop() 
        // because super.stop() stops the writer!
        if (rawChannel != null) {
            // We had to use disk caching due to memory limits
            processStrips()
        }
        // If in-memory (rawChannel == null), next() was already called for each frame in consume()
        // No additional processing needed

        cleanup()
        super.stop(canceled)
    }

    private fun processStrips() {
        onBusyStateChanged?.invoke(true)
        
        val stripSize = width * STRIP_HEIGHT * 3
        val inputBuffer = ByteBuffer.allocateDirect(stripSize * frameCount).order(ByteOrder.nativeOrder())
        val outputBuffer = ByteBuffer.allocateDirect(stripSize * frameCount).order(ByteOrder.nativeOrder())
        
        // FIX: Stream output to disk instead of holding all frames in memory
        // If we didn't have enough RAM for inputs, we definitely can't hold outputs!
        val tempOutputFile = File(context.cacheDir, "endless_median_output.tmp")
        tempOutputFile.delete()
        val outputChannel = RandomAccessFile(tempOutputFile, "rw").channel
        
        val frameSize = width * height * 3L

        val totalStrips = (height + STRIP_HEIGHT - 1) / STRIP_HEIGHT
        var currentStrip = 0

        var currentY = 0
        while (currentY < height) {
            val currentStripHeight = min(STRIP_HEIGHT, height - currentY)
            val currentStripPixels = width * currentStripHeight * 3
            
            inputBuffer.clear()
            for (f in 0 until frameCount) {
                val position = (f * frameSize) + (currentY.toLong() * width * 3)
                rawChannel?.position(position)
                val stripBuffer = inputBuffer.duplicate()
                stripBuffer.position(f * currentStripPixels)
                stripBuffer.limit(f * currentStripPixels + currentStripPixels)
                rawChannel?.read(stripBuffer)
            }

            inputBuffer.clear()
            outputBuffer.clear()
            ImageTools.Companion.processEndlessMedianStripNative(inputBuffer, outputBuffer, width, currentStripHeight, frameCount)

            // Write strips directly to output file instead of storing in resultMats
            outputBuffer.clear()
            val stripBytes = ByteArray(currentStripPixels)
            for (f in 0 until frameCount) {
                outputBuffer.position(f * currentStripPixels)
                outputBuffer.get(stripBytes)
                // Write each strip to output file at the correct position
                val outputPos = (f * frameSize) + (currentY.toLong() * width * 3)
                outputChannel.position(outputPos)
                outputChannel.write(ByteBuffer.wrap(stripBytes))
            }
            
            currentStrip++
            val progress = 50 + ((currentStrip.toFloat() / totalStrips) * 50).toInt()
            onProgressWithStatus?.invoke(progress, 100, "Applying Median Filter ($progress%)")
            
            currentY += STRIP_HEIGHT
        }
        
        outputChannel.close()
        
        // Now read from output file and deliver frames one at a time (streaming)
        onBusyStateChanged?.invoke(false)
        
        val inputChannel = RandomAccessFile(tempOutputFile, "r").channel
        val readBuffer = ByteBuffer.allocateDirect(width * height * 3).order(ByteOrder.nativeOrder())
        
        // Note: nextConsumer is already started by FramesFilter.start() — do NOT call start() again
        
        for (f in 0 until frameCount) {
            readBuffer.clear()
            inputChannel.read(readBuffer)
            readBuffer.flip()
            val frameBytes = ByteArray(width * height * 3)
            readBuffer.get(frameBytes)
            
            val outputMat = Mat(height, width, CvType.CV_8UC3)
            outputMat.put(0, 0, frameBytes)
            next(f, outputMat)
            outputMat.release()
        }
        
        inputChannel.close()
        tempOutputFile.delete()
    }

    private fun cleanup() {
        rawChannel?.close()
        tempRawFile?.delete()
        allFrames.forEach { it.release() }
        allFrames.clear()
    }

    fun reset() {
        rawChannel?.close()
        rawChannel = null
        tempRawFile?.delete()
        tempRawFile = null
        allFrames.forEach { it.release() }
        allFrames.clear()
        reusableByteArray = null
        reusableByteBuffer = null
        width = 0
        height = 0
        frameCount = 0
        isTilingMode = false
    }

    override fun getWindowSize(): Int = 0
}
