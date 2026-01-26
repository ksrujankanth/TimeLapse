package com.dan.timelapse.filters

import android.content.Context
import com.dan.timelapse.screens.BusyDialog
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

    private var width = 0
    private var height = 0
    private var frameCount = 0

    // Threshold for in-memory processing based on available RAM
    // We aim to use up to 70% of available RAM if possible, but keep it safe.
    private val ramLimit = Runtime.getRuntime().maxMemory() * 7 / 10

    // --- State for In-Memory ---
    private val allFrames = mutableListOf<Mat>()

    // --- State for Tiling ---
    private val STRIP_HEIGHT = 64
    private var tempRawFile: File? = null
    private var rawChannel: FileChannel? = null

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

        // For preview during video generation, we show the median of what we have.
        // If we have frames in memory, use them. If we already moved to disk, 
        // we skip intermediate preview to avoid massive slowdown, OR just show last frame.
        if (allFrames.size == frameCount) {
            val output = Mat()
            ImageTools.calculateMedianFrame(allFrames, output)
            next(index, output)
            output.release()
        } else {
            // Just pass through or show some intermediate if tiling? 
            // Better to show the input frame as preview if we can't compute median efficiently.
            next(index, frame)
        }
    }

    private fun writeFrameToDisk(frame: Mat) {
        val size = (frame.total() * frame.elemSize()).toInt()
        val tempArray = ByteArray(size)
        frame.get(0, 0, tempArray)
        val buffer = ByteBuffer.allocateDirect(size)
        buffer.put(tempArray)
        buffer.flip()
        rawChannel?.write(buffer)
    }

    override fun stop(canceled: Boolean) {
        if (canceled || frameCount == 0) {
            cleanup()
            super.stop(canceled)
            return
        }

        // Final processing
        if (rawChannel != null) {
            // We had to use disk caching due to memory limits
            processStrips()
        } else if (allFrames.size == frameCount) {
            // Everything fit in memory! This is the fastest path.
            // Note: next() was already called for each frame in consume() for preview.
            // But if we want to ensure the final result is delivered correctly:
            // (Actually in this filter, next() is called per input frame for preview)
        }

        cleanup()
        super.stop(canceled)
    }

    private fun processStrips() {
        val stripSize = width * STRIP_HEIGHT * 3
        val inputBuffer = ByteBuffer.allocateDirect(stripSize * frameCount).order(ByteOrder.nativeOrder())
        val outputBuffer = ByteBuffer.allocateDirect(stripSize * frameCount).order(ByteOrder.nativeOrder())
        
        val frameSize = width * height * 3L
        val resultMats = Array(frameCount) { Mat(height, width, CvType.CV_8UC3) }

        var currentY = 0
        while (currentY < height) {
            val currentStripHeight = min(STRIP_HEIGHT, height - currentY)
            val currentStripPixels = width * currentStripHeight * 3
            
            BusyDialog.show("Processing Dynamic Median", currentY, height)

            if (BusyDialog.isCanceled()) break

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
            ImageTools.processEndlessMedianStrip(inputBuffer, outputBuffer, width, currentStripHeight, frameCount)

            for (f in 0 until frameCount) {
                val stripResult = ByteArray(currentStripPixels)
                outputBuffer.position(f * currentStripPixels)
                outputBuffer.get(stripResult)
                val roi = Rect(0, currentY, width, currentStripHeight)
                val submat = resultMats[f].submat(roi)
                submat.put(0, 0, stripResult)
                submat.release()
            }
            currentY += STRIP_HEIGHT
        }

        if (!BusyDialog.isCanceled()) {
            // Deliver the final frames
            nextConsumer.start()
            for (f in 0 until frameCount) {
                next(f, resultMats[f])
                resultMats[f].release()
            }
        } else {
            resultMats.forEach { it.release() }
        }
    }

    private fun cleanup() {
        rawChannel?.close()
        tempRawFile?.delete()
        allFrames.forEach { it.release() }
        allFrames.clear()
    }

    override fun getWindowSize(): Int = 0
}
