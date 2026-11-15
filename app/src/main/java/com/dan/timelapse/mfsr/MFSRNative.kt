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
    external fun accumulateFrame(engineAddr: Long, frameAddr: Long, frameIndex: Int)
    external fun finalizeEngine(engineAddr: Long, outputAddr: Long)
    external fun enhanceOutputContrast(engineAddr: Long, outputAddr: Long)
    external fun destroyEngine(engineAddr: Long)
    external fun getStoredFrameCount(engineAddr: Long): Int
    /** Number of telemetry entries recorded (one per processFrame call). */
    external fun getTelemetryCount(engineAddr: Long): Int
    /** Format telemetry entry N as a human-readable log line. */
    external fun getTelemetryLine(engineAddr: Long, index: Int): String
}
