package com.dan.timelapse.data

import kotlinx.serialization.Serializable

@Serializable
data class SessionState(
    val lastMediaUri: String?,
    val lastEffect: Int,
    val lastSampling: Int,
    val lastRotation: Int,
    val trimStart: Float,
    val trimEnd: Float,
    val isZoomToFill: Boolean,
    val lastSourceType: String,
    val lastCodec: String,
    val trimStartMs: Long,
    val trimEndMs: Long,
    val autoSaveAfterRender: Boolean,
    val isEndless: Boolean,
    val frameBlending: Int,
    val speed: Int,
    val resolution: Int,
    val encodingH265: Boolean,
    val preserveAudio: Boolean = false,
    val zoomScale: Float,
    val panX: Float,
    val panY: Float,
    val trimStartFrameIndex: Float = 0f,  // For image sequence frame indices
    val trimEndFrameIndex: Float = 0f     // For image sequence frame indices
)
