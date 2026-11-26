package com.dan.timelapse.data

import kotlinx.serialization.Serializable

@Serializable
data class SessionState(
    val lastMediaUri: String?,
    /**
     * Every selected image of a multi-select sequence. `lastMediaUri` can only hold
     * one, so without this a 50-photo selection reopened as one frame.
     * Empty for folders (the tree URI is re-listed) and for single picks.
     */
    val lastMediaUris: List<String> = emptyList(),
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
    val trimEndFrameIndex: Float = 0f,    // For image sequence frame indices
    val outputFps: Int = 30,
    val fileQuality: Int = 50,
    val fullResExport: Boolean = false
)
