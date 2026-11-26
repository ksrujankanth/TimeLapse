package com.dan.timelapse.mfsr

/**
 * JNI bridge to MFSREngineV2 (C++).
 * Native library "timelapse" is loaded by TimeLapseApp.onCreate().
 */
object MFSRNative {
    external fun createEngine(scale: Float, anchorAddr: Long): Long
    external fun setSceneStats(engineAddr: Long, medianMse: Double, p90Mse: Double, sceneSpread: Double, medianBrightness: Double)
    external fun processFrame(engineAddr: Long, frameAddr: Long): Boolean
    /** Process a frame and record per-frame telemetry (absolute frame index and pre-scan MSE). */
    external fun processFrameWithTelemetry(engineAddr: Long, frameAddr: Long, frameIndex: Int, preScanMse: Double): Boolean
    external fun initAccumulation(engineAddr: Long)
    /** callIndex must be the ordinal of the matching processFrameWithTelemetry call. */
    external fun accumulateFrame(engineAddr: Long, frameAddr: Long, callIndex: Int)
    external fun finalizeEngine(engineAddr: Long, outputAddr: Long)
    external fun enhanceOutputContrast(engineAddr: Long, outputAddr: Long)
    external fun destroyEngine(engineAddr: Long)
    external fun getStoredFrameCount(engineAddr: Long): Int
    /**
     * Number of processFrame* calls the native side recorded. Pass 2 replays by call
     * ordinal, so this must equal the number of calls Kotlin made.
     */
    external fun getProcessedCallCount(engineAddr: Long): Int
    /** HR pixels that received no samples (before density compensation). */
    external fun getHoleCount(engineAddr: Long): Int
    /** Number of frames actually splatted into the HR grid. */
    external fun getAccumulatedFrames(engineAddr: Long): Int
    /** Mean samples per HR pixel. */
    external fun getMeanDensity(engineAddr: Long): Double
    /** Motion-rejection threshold in 8-bit levels (derived from the burst's noise floor). */
    external fun getMotionThreshold(engineAddr: Long): Float
    /**
     * Largest scale whose HR phase grid the accepted frames actually cover (2..4).
     * Coverage depends on sub-pixel diversity, not frame count.
     */
    external fun suggestMaxScale(engineAddr: Long): Int
    /** Lower the render scale before accumulation is allocated. */
    external fun setRenderScale(engineAddr: Long, scale: Float)
    /** True 16-bit fusion: frames are expected at CV_16U and the output is CV_16UC3. */
    external fun setHighBitDepth(engineAddr: Long, on: Boolean)
    /** Number of low-res samples excluded as moving content. */
    external fun getMotionRejectedSamples(engineAddr: Long): Int
    /** Number of telemetry entries recorded (one per processFrame call). */
    external fun getTelemetryCount(engineAddr: Long): Int
    /** Format telemetry entry N as a human-readable log line. */
    external fun getTelemetryLine(engineAddr: Long, index: Int): String
}
