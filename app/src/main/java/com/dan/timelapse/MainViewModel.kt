package com.dan.timelapse

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.os.Looper
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.dan.timelapse.data.Codec
import com.dan.timelapse.data.CropRatio
import com.dan.timelapse.data.PreviewTransformState
import com.dan.timelapse.data.SessionState
import com.dan.timelapse.framesinput.FramesInput
import com.dan.timelapse.framesinput.ImagesFramesInput
import com.dan.timelapse.framesinput.VideoFramesInput
import com.dan.timelapse.utils.OutputParams
import com.dan.timelapse.utils.Settings
import com.dan.timelapse.utils.InputSpeed
import com.dan.timelapse.utils.HardwareUtils
import com.dan.timelapse.utils.getJvmUsedMemoryMB
import com.dan.timelapse.utils.getJvmMaxMemoryMB
import com.dan.timelapse.utils.getTimestamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.opencv.core.Mat
import java.io.File
import java.io.FileOutputStream

class MainViewModel(application: Application) : AndroidViewModel(application) {
    // ===== Source Type Enum =====
    enum class SourceType { VIDEO, IMAGES, FOLDER }

    // ===== Debug StateFlow for MFSR & Pipeline Monitoring =====
    private val _debugLog = MutableStateFlow("MainViewModel initialized")
    val debugLog: StateFlow<String> = _debugLog.asStateFlow()

    fun appendDebugLog(message: String) {
        val usedMemMB = getJvmUsedMemoryMB()
        val maxMemMB = getJvmMaxMemoryMB()
        val timestamp = getTimestamp()
        val entry = "[$timestamp] $message | JVM: ${usedMemMB}MB/${maxMemMB}MB"
        _debugLog.value = _debugLog.value + "\n" + entry
    }

    fun clearDebugLog() {
        _debugLog.value = "Log cleared"
    }

    fun resetDebugLog() {
        _debugLog.value = "=== Debug Session Started ==="
    }
    
    /**
     * TransformState: Consolidated source of truth for rotation and aspect ratio.
     * Both TextureVideoView and VideoProcessor read from this state.
     */
    data class TransformState(
        val rotation: Int = 0, // 0, 90, 180, 270
        val aspectNumerator: Int = 0, // e.g., 1 for 1:1
        val aspectDenominator: Int = 0 // e.g., 1 for 1:1
    ) {
        /**
         * Returns true if a valid aspect ratio is set (not SOURCE)
         */
        fun hasAspectRatio(): Boolean = aspectNumerator > 0 && aspectDenominator > 0
        
        /**
         * Returns the aspect ratio as a float, or null if not set
         */
        fun getAspectRatio(): Float? = if (hasAspectRatio()) aspectNumerator.toFloat() / aspectDenominator else null
        
        /**
         * Returns true if rotation requires dimension swap (90 or 270 degrees)
         */
        fun requiresDimensionSwap(): Boolean = rotation == 90 || rotation == 270
    }
    
    var lastGeneratedFilePath: String? = null
    var lastGeneratedFrameCount: Int = 0
    private val _toastMessage = MutableLiveData<String>()
    val toastMessage: LiveData<String> = _toastMessage
    private val _brushRadius = MutableLiveData(30f)
    val brushRadius: LiveData<Float> = _brushRadius
    private val _scanFileEvent = MutableLiveData<String>()
    val scanFileEvent: LiveData<String> = _scanFileEvent

    // ===== New Persistence State Variables =====
    private val _lastSourceType = MutableLiveData(SourceType.VIDEO)
    val lastSourceType: LiveData<SourceType> = _lastSourceType

    /**
     * True once a share-sheet payload has been consumed. A configuration change recreates the
     * activity with the SAME launch intent; without this flag the share would be replayed over
     * whatever the user has chosen since.
     */
    var shareIntentConsumed: Boolean = false

    /**
     * Child URIs of a restored multi-select IMAGES session. Empty for folders and for
     * single picks, where [lastMediaUri] is enough.
     */
    private val _lastMediaUris = MutableLiveData<List<Uri>>(emptyList())
    val lastMediaUris: LiveData<List<Uri>> = _lastMediaUris
    
    private val _lastCodec = MutableLiveData(Codec.H264)
    val lastCodec: LiveData<Codec> = _lastCodec
    
    private val _isRenderValid = MutableLiveData(false)
    val isRenderValid: LiveData<Boolean> = _isRenderValid
    
    private val _previewProgress = MutableLiveData(0.0f)
    val previewProgress: LiveData<Float> = _previewProgress
    
    private val _trimStartMs = MutableLiveData(0L)
    val trimStartMs: LiveData<Long> = _trimStartMs
    
    private val _trimEndMs = MutableLiveData(0L)
    val trimEndMs: LiveData<Long> = _trimEndMs

    // 0-based frame-index trim bounds (synced from trimRange in setTrimRange)
    private val _trimStart = MutableLiveData(0)
    val trimStart: LiveData<Int> = _trimStart

    private val _trimEnd = MutableLiveData(Int.MAX_VALUE)
    val trimEnd: LiveData<Int> = _trimEnd
    
    // Selected frame for image export, always a 0-based source frame index.
    private val _selectedFramePositionMs = MutableLiveData<Long?>(null)
    val selectedFramePositionMs: LiveData<Long?> = _selectedFramePositionMs

    fun clearSelectedFramePosition() {
        _selectedFramePositionMs.value = null
    }

    /**
     * The frame picker always stores a 0-based index into the ORIGINAL source,
     * regardless of whether it was driven by source frames or by a rendered
     * preview. That removes every index-vs-milliseconds ambiguity after a
     * render. Use [setSelectedFrameFromPicker] to write it.
     */
    fun resolveSelectedSourceFrameIndex(): Int? {
        val input = framesInput.value ?: return null
        val position = _selectedFramePositionMs.value ?: return null
        return position.toInt().coerceIn(0, (input.size - 1).coerceAtLeast(0))
    }

    /** True when the picker slider is showing a rendered video timeline (milliseconds). */
    private fun pickerUsesRenderedTimeline(): Boolean {
        val input = framesInput.value ?: return false
        return _processedVideoUri.value != null &&
            input !is com.dan.timelapse.framesinput.ImagesFramesInput
    }

    /**
     * Source frames per rendered frame. MUST match VideoProcessor's decimation:
     * every effect decimates except Super Resolution, which needs every frame for
     * sub-pixel coverage. If these two disagree, the seekbar maps the rendered
     * timeline onto the wrong source frames.
     */
    private fun pickerSkipFrames(): Int =
        InputSpeed.strideFor(_visualEffect.value ?: Effect.NONE, _skipFrames.value ?: 1)

    /** False when the current effect ignores input speed (Super Resolution). */
    fun isInputSpeedApplicable(): Boolean =
        InputSpeed.strideFor(_visualEffect.value ?: Effect.NONE, 2) > 1

    /**
     * Store a picker value. Millisecond values from a rendered timeline are
     * converted back to a source frame index.
     */
    fun setSelectedFrameFromPicker(value: Long) {
        val input = framesInput.value
        if (input == null) {
            _selectedFramePositionMs.value = value
            return
        }
        val lastIndex = (input.size - 1).coerceAtLeast(0)
        val sourceIndex = if (pickerUsesRenderedTimeline()) {
            val outFps = (_outputFps.value ?: _previewFps.value ?: 30).coerceAtLeast(1)
            val renderedFrame = ((value / 1000.0) * outFps).toInt()
            val startFrame = (_trimStart.value ?: 0).coerceAtLeast(0)
            startFrame + renderedFrame * pickerSkipFrames()
        } else {
            value.toInt()
        }
        _selectedFramePositionMs.value = sourceIndex.coerceIn(0, lastIndex).toLong()
    }

    /**
     * Inverse of [setSelectedFrameFromPicker]: the position in milliseconds on
     * the currently rendered preview that corresponds to a source frame.
     */
    fun renderedPositionMsForSourceFrame(sourceIndex: Int): Long {
        if (!pickerUsesRenderedTimeline()) return sourceIndex.toLong()
        val startFrame = (_trimStart.value ?: 0).coerceAtLeast(0)
        val renderedFrame = ((sourceIndex - startFrame).coerceAtLeast(0)) / pickerSkipFrames()
        val outFps = (_outputFps.value ?: _previewFps.value ?: 30).coerceAtLeast(1)
        return renderedFrame.toLong() * 1000L / outFps
    }
    
    private val _autoSaveAfterRender = MutableLiveData(false)
    val autoSaveAfterRender: LiveData<Boolean> = _autoSaveAfterRender

    private var suppressAutoSaveOnce = false

    // ===== Render Valid Check =====
    private var _lastRenderConfig: String = ""
    
    fun invalidateRender() {
        _isRenderValid.value = false
    }
    
    fun checkAndUpdateRenderValid(currentConfig: String) {
        if (_lastRenderConfig != currentConfig) {
            _lastRenderConfig = currentConfig
            _isRenderValid.value = true
        }
    }
    
    fun updatePreviewProgress(progress: Float) {
        _previewProgress.value = progress.coerceIn(0.0f, 1.0f)
    }

    // Exposed as MutableLiveData for external postValue access
    val isPlaying = MutableLiveData(false)

    private fun getScaledTargetDimensions(): Pair<Int, Int> {
        val rotation = _rotation.value ?: 0
        val originalW = framesInput.value?.width ?: 1920
        val originalH = framesInput.value?.height ?: 1080
        val w = if (rotation == 90 || rotation == 270) originalH else originalW
        val h = if (rotation == 90 || rotation == 270) originalW else originalH

        // Enforce safe memory boundaries based on resolution settings.
        // The cap applies to the LONG edge so portrait sources work too.
        val resSetting = _resolution.value ?: com.dan.timelapse.utils.Settings.RES_1080P
        val maxDim = when (resSetting) {
            com.dan.timelapse.utils.Settings.RES_720P -> 1280
            com.dan.timelapse.utils.Settings.RES_4K -> 3840
            else -> 1920
        }

        // If already smaller than target, keep original
        if (w <= maxDim && h <= maxDim) return Pair(w, h)

        // Scale down proportionally to prevent OOM
        val ratio = w.toFloat() / h.toFloat()
        return if (w > h) {
            Pair(maxDim, (maxDim / ratio).toInt())
        } else {
            Pair((maxDim * ratio).toInt(), maxDim)
        }
    }

    val outputWidth: Int get() = getScaledTargetDimensions().first
    val outputHeight: Int get() = getScaledTargetDimensions().second
    
    // Exposed as MutableLiveData for external postValue access
    val isProcessing = MutableLiveData(false)
    
    // Processing progress with time estimation
    private val _processingProgress = MutableLiveData<Pair<Int, Int>>(Pair(0, 0))
    val processingProgress: LiveData<Pair<Int, Int>> = _processingProgress
    
    // Full-res render progress (0-100)
    private val _renderProgress = MutableLiveData(0)
    val renderProgress: LiveData<Int> = _renderProgress
    
    fun updateRenderProgress(percent: Int) {
        _renderProgress.postValue(percent.coerceIn(0, 100))
    }
    
    // Time estimation for rendering
    private val _estimatedTimeRemaining = MutableLiveData<String>("")
    val estimatedTimeRemaining: LiveData<String> = _estimatedTimeRemaining
    
    // Status text for the generate dialog (avoids findViewById spam from callbacks)
    private val _processingStatusText = MutableLiveData<String>("")
    val processingStatusText: LiveData<String> = _processingStatusText

    fun updateProcessingStatusText(text: String) {
        _processingStatusText.postValue(text)
    }

    // Cancel flag
    private val _isProcessingCancelled = MutableLiveData(false)
    val isProcessingCancelled: LiveData<Boolean> = _isProcessingCancelled
    
    fun startRenderingTimer() {
        _estimatedTimeRemaining.postValue("")
    }
    
    fun updateProcessingProgress(current: Int, total: Int) {
        _processingProgress.postValue(Pair(current, total))
    }
    
    /**
     * Called by VideoProcessor with the authoritative ETA calculated via EMA.
     * This is the single source of truth for time remaining.
     */
    fun updateEstimatedTimeRemaining(remainingMs: Long) {
        _estimatedTimeRemaining.postValue(com.dan.timelapse.eta.EtaFormat.format(remainingMs))
    }
    
    fun cancelProcessing() {
        _isProcessingCancelled.postValue(true)
    }
    
    fun resetProcessingState() {
        // Callable from BOTH threads. MainActivity.clearSavedSession() runs inside
        // lifecycleScope.launch(Dispatchers.IO) on the stale-session paths, and
        // LiveData.setValue throws "Cannot invoke setValue on a background thread".
        // The startup path swallowed that into a bogus "Auto-load failed" ERROR and
        // cleared twice; onOpenLastSelection's catch bodies let it escape and crashed.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            _isProcessingCancelled.value = false
            _processingProgress.value = Pair(0, 0)
            _estimatedTimeRemaining.value = ""
            _processingStatusText.value = ""
        } else {
            _isProcessingCancelled.postValue(false)
            _processingProgress.postValue(Pair(0, 0))
            _estimatedTimeRemaining.postValue("")
            _processingStatusText.postValue("")
        }
    }

    // ── Legacy MFSR ETA cache (removed) ─────────────────────────────────
    // The per-phase cache that used to live here was replaced by the learned
    // model in com.dan.timelapse.eta. Its keys ignored the render scale, it
    // predicted matrices/accumulate from the whole clip instead of the selected
    // frames, and it divided frames by milliseconds when applying the scan
    // correction. Do not reintroduce a second estimator: one model, one place.

    fun loadMfsrEtaCache(context: Context) {
        Log.d("TimeLapse", "Legacy MFSR ETA cache ignored; using learned EtaEngine")
    }


    fun getZoomAdjustedRadius(): Float {
        val scale = _previewTransform.value?.scale ?: 1.0f
        return (_brushRadius.value ?: 30f) / scale
    }

    fun toggleEndless() {
        _isEndless.value = !(_isEndless.value ?: false)
    }

    fun shouldLoopFrame(currentIndex: Int, endFrame: Int): Boolean {
        return (_isEndless.value == true) && (currentIndex >= endFrame)
    }

    private val _framesInput = MutableLiveData<FramesInput?>()
    val framesInput: LiveData<FramesInput?> = _framesInput

    private val _firstFrame = MutableLiveData<Mat?>()
    val firstFrame: LiveData<Mat?> = _firstFrame

    private val _firstFrameMask = MutableLiveData<Mat?>()
    val firstFrameMask: LiveData<Mat?> = _firstFrameMask

    private val _trimRange = MutableLiveData<Pair<Float, Float>>(Pair(1f, 100f))
    val trimRange: LiveData<Pair<Float, Float>> = _trimRange

    private val _frameToPreview = MutableLiveData<Int?>()
    val frameToPreview: LiveData<Int?> = _frameToPreview

    // Frame duration in milliseconds (for image sequences)
    private val _frameDuration = MutableLiveData(33) // ~30fps default
    val frameDuration: LiveData<Int> = _frameDuration

    private var scrubJob: Job? = null
    private val scrubDelay = 250L

    private val _isStabilized = MutableLiveData(false)
    val isStabilized: LiveData<Boolean> = _isStabilized

    // Alignment strategy: true = align to first frame, false = sequential (frame-to-frame)
    // Default is SMOOTH mode (alignToFirstFrame = false). On a real panning clip
    // anchor mode measured jitter_rms 1.07 vs 0.70 for the unstabilized source — it
    // pins the pan, then pops when the pan reaches the crop clamp — while smooth
    // removes the jitter and keeps the pan. "Lock to first frame" remains available
    // for tripods via the mask editor's More menu.
    private val _alignToFirstFrame = MutableLiveData(false)
    val alignToFirstFrame: LiveData<Boolean> = _alignToFirstFrame

    // Stabilization zoom factor: 1.0 = keep original, >1.0 = auto-crop black borders
    // Maximum auto-crop the stabilizer may use, as a zoom factor.
    // 1.0 = never crop (black/replicated edges are possible), 1.10 = 10%.
    private val _stabilizationZoom = MutableLiveData(1.05f)
    val stabilizationZoom: LiveData<Float> = _stabilizationZoom

    // Smoothing window in frames. Sigma = window / 6, and the filter is
    // zero-phase, so this controls strength only, never lag.
    private val _stabilizationSmoothing = MutableLiveData(60)
    val stabilizationSmoothing: LiveData<Int> = _stabilizationSmoothing

    // Auto-enable stabilization when drawing a mask
    val autoStabilizeWithMask = MutableLiveData(true)

    private val _orientation = MutableLiveData(0)
    val orientation: LiveData<Int> = _orientation

    private val _cropRatio = MutableLiveData(CropRatio.SOURCE)
    val cropRatio: LiveData<CropRatio> = _cropRatio

    private val _rotation = MutableLiveData(0)
    val rotation: LiveData<Int> = _rotation
    
    // TransformState: Single source of truth for rotation + aspect ratio
    private val _transformState = MutableLiveData(TransformState())
    val transformState: LiveData<TransformState> = _transformState
    
    private val _isZoomToFill = MutableLiveData(false)
    val isZoomToFill: LiveData<Boolean> = _isZoomToFill

    private val _previewTransform = MutableLiveData(PreviewTransformState())
    val previewTransform: LiveData<PreviewTransformState> = _previewTransform

    private val _zoomFactor = MutableLiveData(1f)
    val zoomFactor: LiveData<Float> = _zoomFactor

    private val _translationX = MutableLiveData(0f)
    val translationX: LiveData<Float> = _translationX

    private val _translationY = MutableLiveData(0f)
    val translationY: LiveData<Float> = _translationY

    private val _visualEffect = MutableLiveData(Effect.AVERAGE)
    val visualEffect: LiveData<Effect> = _visualEffect

    private val _isEndless = MutableLiveData(false)
    val isEndless: LiveData<Boolean> = _isEndless

    private val _motionSampling = MutableLiveData(0)
    val motionSampling: LiveData<Int> = _motionSampling

    // G9-06: Trigger live effect preview when slider changes
    private val _triggerPreviewRefresh = MutableLiveData<Int>(0)
    val triggerPreviewRefresh: LiveData<Int> = _triggerPreviewRefresh

    private val _frameBlending = MutableLiveData(10)
    val frameBlending: LiveData<Int> = _frameBlending

    // Skip frames feature: 1 = process all frames, 2 = skip every other frame, etc.
    private val _skipFrames = MutableLiveData(1)
    val skipFrames: LiveData<Int> = _skipFrames

    // Speed multiplier: 19 sensible speed steps for the slider
    val speedOptions = intArrayOf(1, 2, 3, 4, 5, 6, 10, 12, 15, 20, 24, 30, 60, 90, 120, 150, 180, 240, 300)
    
    // The actual selected speed multiplier (default 1x)
    private val _speedMultiplier = MutableLiveData(1)
    val speedMultiplier: LiveData<Int> = _speedMultiplier

    fun setSpeedMultiplierFromIndex(index: Int) {
        val safeIndex = index.coerceIn(0, speedOptions.size - 1)
        val selectedSpeed = speedOptions[safeIndex]
        _speedMultiplier.value = selectedSpeed
        
        // Feed this directly into the skipFrames logic!
        // 6x speed = keep 1, skip 5. So skipFrames interval is 6.
        setSkipFrames(selectedSpeed)
        
        // Invalidate render cache so user knows a new render is needed
        invalidateRender()
    }

    private val _speed = MutableLiveData(1)
    val speed: LiveData<Int> = _speed

    // Preview FPS options for non-destructive playback speed control
    val previewFpsOptions = intArrayOf(1, 5, 10, 15, 30, 60)
    
    private val _previewFps = MutableLiveData(30)
    val previewFps: LiveData<Int> = _previewFps

    fun setPreviewFpsFromIndex(index: Int) {
        val safeIndex = index.coerceIn(0, previewFpsOptions.size - 1)
        _previewFps.value = previewFpsOptions[safeIndex]
        
        // CRITICAL: We MUST invalidate the render because the MP4 
        // Presentation Time Stamps (PTS) need to be rewritten.
        invalidateRender()
    }

    private val _triggerStudioProcess = MutableLiveData<Unit>()
    val triggerStudioProcess: LiveData<Unit> = _triggerStudioProcess

    private val _resolution = MutableLiveData(Settings.RES_1080P)
    val resolution: LiveData<Int> = _resolution

    private val _encodingH265 = MutableLiveData(true)
    val encodingH265: LiveData<Boolean> = _encodingH265

    private val _preserveAudio = MutableLiveData(true)
    val preserveAudio: LiveData<Boolean> = _preserveAudio
    
    private val _isH265Supported = MutableLiveData(false)
    val isH265Supported: LiveData<Boolean> = _isH265Supported

    private val _fileQuality = MutableLiveData(50)
    val fileQuality: LiveData<Int> = _fileQuality

    private val _outputFps = MutableLiveData(30)
    val outputFps: LiveData<Int> = _outputFps

    private val _processedVideoUri = MutableLiveData<Uri?>()
    val processedVideoUri: LiveData<Uri?> = _processedVideoUri

    private val _processedImageUri = MutableLiveData<Uri?>()
    val processedImageUri: LiveData<Uri?> = _processedImageUri

    private val _isSaveReady = MutableLiveData(false)
    val isSaveReady: LiveData<Boolean> = _isSaveReady

    val isMediaLoading = MutableLiveData<Boolean>(false)

    private val _triggerSaveVideo = MutableLiveData<Unit>()
    val triggerSaveVideo: LiveData<Unit> = _triggerSaveVideo

    private val _triggerSaveFrame = MutableLiveData<Unit>()
    val triggerSaveFrame: LiveData<Unit> = _triggerSaveFrame

    private val _triggerSaveStill = MutableLiveData<Unit>()
    val triggerSaveStill: LiveData<Unit> = _triggerSaveStill
    
    private val _triggerFullResRender = MutableLiveData<Unit?>()
    val triggerFullResRender: LiveData<Unit?> = _triggerFullResRender

    private var _lastProcessedParams: OutputParams? = null
    val lastProcessedParams: OutputParams? get() = _lastProcessedParams

    fun setLastProcessedParams(params: OutputParams?) {
        _lastProcessedParams = params
        _isSaveReady.value = params != null
    }

    /** Explicitly enable/disable Save/Share (e.g. after a cancelled render). */
    fun setSaveReady(ready: Boolean) {
        _isSaveReady.value = ready
    }
    
    // ===== Trim Helpers for Trim-Sync =====
    fun getTrimStartMs(): Long = _trimStartMs.value ?: 0L
    fun getTrimEndMs(): Long = _trimEndMs.value ?: Long.MAX_VALUE
    
    fun setTrimStartMs(ms: Long) {
        _trimStartMs.value = ms
        invalidateRender()
    }
    
    fun setTrimEndMs(ms: Long) {
        _trimEndMs.value = ms
        invalidateRender()
    }
    
    fun setLastSourceType(type: SourceType) {
        _lastSourceType.value = type
        invalidateRender()
    }
    
    fun setLastCodec(codec: Codec) {
        _lastCodec.value = codec
    }
    
    fun setAutoSaveAfterRender(autoSave: Boolean) {
        _autoSaveAfterRender.value = autoSave
        // Persist straight away. The switch lives in a bottom sheet and only onPause used
        // to write the session, so a force-stop could silently revert the user's choice.
        if (framesInput.value != null) {
            try {
                saveSession(getApplication())
            } catch (_: Exception) {
                // Never let a persistence hiccup break the toggle itself.
            }
        }
    }

    private fun gcd(a: Int, b: Int): Int {
        return if (b == 0) a else gcd(b, a % b)
    }

    private fun getAspectRatioString(width: Int, height: Int): String {
        if (width <= 0 || height <= 0) return "16:9"
        val common = gcd(width, height)
        return "${width / common}:${height / common}"
    }

    fun triggerSaveVideo() { _triggerSaveVideo.value = Unit }
    fun triggerSaveFrame() { _triggerSaveFrame.value = Unit }
    fun triggerSaveStill() { _triggerSaveStill.value = Unit }

    fun setFramesInput(input: FramesInput?) {
        isMediaLoading.postValue(true)
        lastGeneratedFilePath = null
        _framesInput.value?.release()
        _framesInput.value = input
        isMediaLoading.postValue(false)
        if (input != null) {
            _firstFrame.value = input.firstFrame()
            _firstFrameMask.value = Mat()
            
            // Reset UI states to defaults on media load
            _isEndless.value = false
            _visualEffect.value = Effect.NONE
            _frameBlending.value = minOf(10, input.size).coerceAtLeast(2)

            // A frame picked for the previous media must never leak into the new one.
            _selectedFramePositionMs.value = null
            
            // FIX 4: Reset skipFrames to 1 for new media
            _skipFrames.value = 1
            // ...and reset the visible speed with it. These two used to diverge:
            // the summary kept saying "60x" while the render silently used 1x.
            _speedMultiplier.value = 1
            
            // Set default preview FPS based on input type
            val defaultPreviewFps = when (input) {
                is VideoFramesInput -> input.fps
                is ImagesFramesInput -> if (input.size > 30) 30 else 5
                else -> 30
            }
            _previewFps.value = defaultPreviewFps
            
            // CONSTRAINT 1: Set default trim range based on input type
            if (input is VideoFramesInput) {
                // For video, default to 10 seconds max (min of 300 frames at 30fps or total frames)
                val defaultEnd = minOf(300f, input.size.toFloat())
                _trimRange.postValue(Pair(1f, defaultEnd))

                // THE FIX: Use floating-point division (1000.0) and initialize 0-based indices
                _trimStartMs.postValue(0L)
                val endMs = (defaultEnd * 1000.0 / (input.fps.coerceAtLeast(1))).toLong()
                _trimEndMs.postValue(endMs)
                _trimStart.postValue(0)
                _trimEnd.postValue(defaultEnd.toInt() - 1)
            } else {
                // Images: Default to 300 frames max (safe chunk to prevent device lock-up)
                val defaultEnd = minOf(300f, input.size.toFloat())
                _trimRange.postValue(Pair(1f, defaultEnd))

                // THE FIX: Use floating-point division and initialize 0-based indices
                _trimStartMs.postValue(0L)
                _trimEndMs.postValue(((input.size - 1) * 1000.0 / 30).toLong())
                _trimStart.postValue(0)
                _trimEnd.postValue(defaultEnd.toInt() - 1)
            }
            
            // Restore per-URI trim if previously saved
            val uri = input.videoUri
            if (uri != null) {
                val prefs = getApplication<Application>().getSharedPreferences("TimeLapsePrefs", Context.MODE_PRIVATE)
                val savedStart = prefs.getInt("trim_start_${uri.hashCode()}", -1)
                val savedEnd = prefs.getInt("trim_end_${uri.hashCode()}", -1)
                if (savedEnd > savedStart) {
                    _trimRange.postValue(Pair(savedStart.toFloat(), savedEnd.toFloat()))
                }
            }

            if (maxOf(input.width, input.height) < 3840 && _resolution.value == Settings.RES_4K) {
                _resolution.value = Settings.RES_1080P
            }

            _rotation.value = 0
            _cropRatio.value = CropRatio.SOURCE
            _transformState.value = TransformState() // Reset transform state
            _previewTransform.value = PreviewTransformState()
        } else {
            _firstFrame.value = null
            _firstFrameMask.value = null
            _trimRange.value = Pair(1f, 100.0f)
            _selectedFramePositionMs.value = null
        }
        setLastProcessedParams(null)
        _processedVideoUri.value = null
        _processedImageUri.value = null
        experimentalRefinedUri = null
        pendingFullResAction = null
    }

    fun getFormattedExportName(extension: String): String {
        val originalName = (_framesInput.value?.name ?: "Timelapse").substringBeforeLast(".")
        val effect = _visualEffect.value ?: Effect.NONE
        val endless = if (_isEndless.value == true && effect.supportsEndless) "_Endless" else ""
        val effectName = if (effect != Effect.NONE) "_${effect.friendlyName.replace(" ", "")}" else ""
        return "${originalName}${effectName}${endless}.${extension}"
    }

    fun triggerFullResRender() {
        _triggerFullResRender.value = Unit
    }

    fun clearTriggerFullResRender() {
        _triggerFullResRender.value = null
    }

    val isFullResExportEnabled = MutableLiveData(false)
    var pendingFullResAction: String? = null
    var experimentalRefinedUri: Uri? = null

    fun setProcessedImageUri(uri: Uri?) {
        _processedImageUri.value = uri
    }

    /**
     * Local file backing the currently displayed rendered image (MFSR output or a
     * full-res still). Used by Save/Share so they copy the real rendered pixels
     * instead of the downscaled preview bitmap.
     */
    fun getProcessedImageFile(): File? {
        val uri = _processedImageUri.value ?: return null
        val path = uri.path ?: return null
        val file = File(path)
        return if (file.exists() && file.length() > 0) file else null
    }

    private fun getUniqueFile(folder: File, baseName: String, extension: String): File {
        var file = File(folder, "$baseName.$extension")
        var counter = 1
        while (file.exists()) {
            file = File(folder, "${baseName}_${counter++}.$extension")
        }
        return file
    }

    fun saveVideoToGallery(baseNameWithExt: String) {
        val filePath = lastGeneratedFilePath

        if (filePath == null) {
            _toastMessage.value = "Error: No file to save."
            return
        }
        // Safety net: MFSR produces a JPEG. Never mux those bytes into the video collection.
        if (!filePath.endsWith(".mp4", ignoreCase = true)) {
            _toastMessage.value = "Error: current render is not a video."
            return
        }

        // Strip extension from baseName if present
        val baseName = baseNameWithExt.substringBeforeLast(".")
        
        // Capture for lambda
        val safeFilePath: String = filePath

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sourceFile = File(safeFilePath)
                if (!sourceFile.exists()) {
                    withContext(Dispatchers.Main) { _toastMessage.value = "Error: Rendered file missing" }
                    return@launch
                }

                // A one-frame MP4 reports a 0 ms duration: it is a corrupt entry in
                // every gallery and player. The renderer already refuses to publish
                // one, but Save/Share on the Finish screen can point at a stale or
                // partial file, so the last write before MediaStore re-checks.
                val sourceDurationMs = videoDurationMs(sourceFile)
                if (sourceDurationMs != null && sourceDurationMs <= 0L) {
                    Log.w("MainViewModel", "Refusing to save 0 ms video ${sourceFile.name}")
                    withContext(Dispatchers.Main) {
                        _toastMessage.value = "Not saved: this video has no playable frames."
                    }
                    return@launch
                }

                val contentResolver = getApplication<Application>().contentResolver
                
                // Generate unique filename to avoid overwriting
                val uniqueFileName = getUniqueFileName(contentResolver, baseName, "mp4")
                
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, uniqueFileName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/TimeLapse")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }

                val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val fileUri = contentResolver.insert(collection, values)

                // FIX 4: Use try-finally to prevent orphan files on failure
                fileUri?.let { uri ->
                    var isSuccess = false
                    try {
                        val outputStream = contentResolver.openOutputStream(uri)
                            ?: throw java.io.IOException("MediaStore returned no output stream")
                        outputStream.use { out ->
                            sourceFile.inputStream().use { inputStream ->
                                inputStream.copyTo(out)
                            }
                        }

                        values.clear()
                        values.put(MediaStore.Video.Media.IS_PENDING, 0)
                        contentResolver.update(uri, values, null, null)
                        isSuccess = true
                        // The MediaStore row is already live; scanning the cache copy again
                        // would import a duplicate video into the gallery.
                        withContext(Dispatchers.Main) { _toastMessage.value = "Video saved to Movies/TimeLapse" }
                    } catch (e: Exception) {
                        throw e
                    } finally {
                        if (!isSuccess) {
                            // ORPHAN PREVENTION: Delete the pending file entry so it doesn't permanently eat device storage
                            try {
                                contentResolver.delete(uri, null, null)
                            } catch (delEx: Exception) {
                                Log.e("MainViewModel", "Failed to cleanup orphan video file", delEx)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Save failed", e)
                withContext(Dispatchers.Main) { _toastMessage.value = "Save failed: ${e.message}" }
            }
        }
    }

    /**
     * Generates a unique filename by appending _01, _02, etc. if file already exists
     */
    private fun getUniqueFileName(contentResolver: android.content.ContentResolver, baseName: String, extension: String): String {
        var candidate = "$baseName.$extension"
        var counter = 0
        
        val collection = when (extension) {
            "mp4" -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            "jpg", "jpeg" -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else -> return candidate
        }
        
        while (true) {
            val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
            val selectionArgs = arrayOf(candidate)
            
            val cursor = contentResolver.query(collection, projection, selection, selectionArgs, null)
            val exists = cursor?.count ?: 0
            cursor?.close()
            
            if (exists == 0) {
                return candidate
            }
            
            counter++
            candidate = "${baseName}_${String.format("%02d", counter)}.$extension"
        }
    }

    /** Container duration in ms, or null when the file cannot be probed. */
    private fun videoDurationMs(file: File): Long? {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull()
        } catch (e: Exception) {
            Log.w("MainViewModel", "Could not read duration of ${file.name}", e)
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }
    
    fun saveImageToGallery(bitmap: Bitmap, baseNameWithExt: String) {
        // Strip extension from baseName if present
        val baseName = baseNameWithExt.substringBeforeLast(".")
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val contentResolver = getApplication<Application>().contentResolver
                
                // Generate unique filename to avoid overwriting
                val uniqueFileName = getUniqueFileName(contentResolver, baseName, "jpg")
                
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, uniqueFileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TimeLapse")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }

                val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val fileUri = contentResolver.insert(collection, values)

                // FIX 4: Use try-finally to prevent orphan files on failure
                fileUri?.let { uri ->
                    var isSuccess = false
                    try {
                        val outputStream = contentResolver.openOutputStream(uri)
                            ?: throw java.io.IOException("MediaStore returned no output stream")
                        outputStream.use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
                        }

                        values.clear()
                        values.put(MediaStore.Images.Media.IS_PENDING, 0)
                        contentResolver.update(uri, values, null, null)
                        isSuccess = true
                        _scanFileEvent.postValue(uri.toString())
                        withContext(Dispatchers.Main) { _toastMessage.value = "Image saved to Pictures/TimeLapse" }
                    } catch (e: Exception) {
                        throw e
                    } finally {
                        if (!isSuccess) {
                            // ORPHAN PREVENTION: Delete the pending file entry so it doesn't permanently eat device storage
                            try {
                                contentResolver.delete(uri, null, null)
                            } catch (delEx: Exception) {
                                Log.e("MainViewModel", "Failed to cleanup orphan image file", delEx)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Image save failed", e)
                withContext(Dispatchers.Main) { _toastMessage.value = "Save failed: ${e.message}" }
            }
        }
    }

    fun saveFileToGalleryAsImage(sourceFile: File, baseNameWithExt: String) {
        val baseName = baseNameWithExt.substringBeforeLast(".")
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!sourceFile.exists()) return@launch
                
                val contentResolver = getApplication<Application>().contentResolver
                // Preserve the source format: a PNG from a 16-bit TIFF/PNG sequence must
                // not be registered as JPEG, or the extra precision is thrown away and
                // some viewers refuse it.
                val extension = sourceFile.extension.lowercase().ifBlank { "jpg" }
                val mimeType = if (extension == "png") "image/png" else "image/jpeg"
                val uniqueFileName = getUniqueFileName(contentResolver, baseName, extension)
                
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, uniqueFileName)
                    put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TimeLapse")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }

                val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val fileUri = contentResolver.insert(collection, values)

                // FIX 4: Use try-finally to prevent orphan files on failure
                fileUri?.let { uri ->
                    var isSuccess = false
                    try {
                        val outputStream = contentResolver.openOutputStream(uri)
                            ?: throw java.io.IOException("MediaStore returned no output stream")
                        outputStream.use { out ->
                            sourceFile.inputStream().use { inputStream ->
                                inputStream.copyTo(out)
                            }
                        }

                        values.clear()
                        values.put(MediaStore.Images.Media.IS_PENDING, 0)
                        contentResolver.update(uri, values, null, null)
                        isSuccess = true
                        _scanFileEvent.postValue(uri.toString())
                        withContext(Dispatchers.Main) { _toastMessage.value = "Image saved to Pictures/TimeLapse" }
                    } catch (e: Exception) {
                        throw e
                    } finally {
                        if (!isSuccess) {
                            // ORPHAN PREVENTION: Delete the pending file entry so it doesn't permanently eat device storage
                            try {
                                contentResolver.delete(uri, null, null)
                            } catch (delEx: Exception) {
                                Log.e("MainViewModel", "Failed to cleanup orphan image file", delEx)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Image save failed", e)
                withContext(Dispatchers.Main) { _toastMessage.value = "Save failed: ${e.message}" }
            }
        }
    }

    fun setFirstFrameMask(mask: Mat) {
        _firstFrameMask.value = mask
    }

    fun setTrimFrames(start: Int, end: Int) {
        val uri = framesInput.value?.videoUri
        _trimRange.value = Pair(start.toFloat(), end.toFloat())
        _trimStart.value = (start - 1).coerceAtLeast(0)
        _trimEnd.value = (end - 1).coerceAtLeast(0)
        invalidateRender()
        
        // Persist per-URI so trim survives app restart
        if (uri != null) {
            val prefs = getApplication<Application>().getSharedPreferences("TimeLapsePrefs", Context.MODE_PRIVATE)
            prefs.edit().putInt("trim_start_${uri.hashCode()}", start)
                .putInt("trim_end_${uri.hashCode()}", end).apply()
        }
    }

    fun setTrimRange(start: Float, end: Float) {
        _trimRange.value = Pair(start, end)
        
        // Sync trimStartMs/trimEndMs based on framesInput fps
        val fps = framesInput.value?.fps ?: 30
        val startMs = ((start - 1) * 1000.0 / fps).toLong()
        val endMs = (end * 1000.0 / fps).toLong()
        _trimStartMs.value = startMs
        _trimEndMs.value = endMs
        
        // Sync 0-based frame-index trim bounds
        _trimStart.value = (start.toInt() - 1).coerceAtLeast(0)
        _trimEnd.value = (end.toInt() - 1).coerceAtLeast(0)
        
        // Invalidate render to force fresh processing with new trim
        invalidateRender()

        // CONSTRAINT 2 & 4: Store trim values according to type (Frames for Image, Percentage for Video)
        val input = framesInput.value
        val prefs = getApplication<Application>().getSharedPreferences("TimeLapsePrefs", Context.MODE_PRIVATE)
        
        if (input is ImagesFramesInput) {
            // CONSTRAINT 2: For images, store raw frame indices (1-based)
            prefs.edit().apply {
                putFloat("trim_start_frame_index", start)
                putFloat("trim_end_frame_index", end)
                putLong("trim_start_ms", startMs)
                putLong("trim_end_ms", endMs)
                apply()
            }
        } else {
            // For video, store percentage (as before)
            prefs.edit().apply {
                putFloat("trim_start", start)
                putFloat("trim_end", end)
                putLong("trim_start_ms", startMs)
                putLong("trim_end_ms", endMs)
                remove("trim_start_frame_index")
                remove("trim_end_frame_index")
                apply()
            }
        }
    }
    
    fun saveCurrentRenderFrame(bitmap: Bitmap, baseName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "TimeLapse/Photos")
            if (!folder.exists()) folder.mkdirs()
            
            val file = getUniqueFile(folder, baseName, "jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
            }
            _scanFileEvent.postValue(file.absolutePath)
        }
    }

    fun requestPreviewFrame(frame: Int) {
        scrubJob?.cancel()
        scrubJob = viewModelScope.launch {
            delay(scrubDelay)
            _frameToPreview.value = frame
        }
    }

    fun seekBy(frames: Int) {
        val current = _frameToPreview.value ?: 0
        val total = _framesInput.value?.size ?: 100
        val next = (current + frames).coerceIn(0, total - 1)
        _frameToPreview.value = next
    }

    fun setOrientation(orientation: Int) { _orientation.value = orientation }
    fun setCropRatio(ratio: CropRatio) { 
        _cropRatio.value = ratio
        // Update TransformState with new aspect ratio
        val (num, den) = when (ratio) {
            CropRatio.SOURCE -> Pair(0, 0)
            CropRatio.RATIO_1_1 -> Pair(1, 1)
            CropRatio.RATIO_4_3 -> Pair(4, 3)
            CropRatio.RATIO_3_4 -> Pair(3, 4)
            CropRatio.RATIO_16_9 -> Pair(16, 9)
            CropRatio.RATIO_9_16 -> Pair(9, 16)
            CropRatio.RATIO_4_5 -> Pair(4, 5)
            CropRatio.RATIO_5_4 -> Pair(5, 4)
            CropRatio.RATIO_21_9 -> Pair(21, 9)
            CropRatio.RATIO_9_21 -> Pair(9, 21)
        }
        _transformState.value = _transformState.value?.copy(
            aspectNumerator = num,
            aspectDenominator = den
        )
        // Guardrail: Invalidate render when ratio changes
        invalidateRender()
    }
    fun rotate() { 
        val newRotation = ((_rotation.value ?: 0) + 90) % 360
        _rotation.value = newRotation
        // Update TransformState with new rotation
        _transformState.value = _transformState.value?.copy(rotation = newRotation)
        // Guardrail: Invalidate render when rotation changes
        invalidateRender()
    }

    private var currentRatioIndex = 0

    fun cycleAspectRatio() {
        currentRatioIndex = (currentRatioIndex + 1) % CropRatio.values().size
        _cropRatio.value = CropRatio.values()[currentRatioIndex]
    }

    fun toggleStabilization() {
        _isStabilized.value = !(_isStabilized.value ?: false)
    }
    
    fun setStabilization(enabled: Boolean) {
        _isStabilized.value = enabled
    }

    fun toggleAlignToFirstFrame() {
        _alignToFirstFrame.value = !(_alignToFirstFrame.value ?: true)
    }

    fun setAlignToFirstFrame(align: Boolean) {
        _alignToFirstFrame.value = align
    }

    fun setStabilizationSmoothing(window: Int) {
        // 5..200 frames. 30 = light, 60 = medium, 120 = strong.
        _stabilizationSmoothing.value = window.coerceIn(5, 200)
    }

    fun setStabilizationZoom(zoom: Float) {
        // Interpreted as the maximum auto-crop: 1.0 (none) .. 1.10 (10%).
        _stabilizationZoom.value = zoom.coerceIn(1.0f, 1.1f)
    }

    /** @param percent 0, 5 or 10 */
    fun setStabilizationCropPercent(percent: Int) {
        _stabilizationZoom.value = 1.0f + percent.coerceIn(0, 10) / 100.0f
    }

    fun toggleStabilizationZoom() {
        // Retained for the existing mask menu: cycles 5% -> 10% -> off.
        val current = _stabilizationZoom.value ?: 1.05f
        _stabilizationZoom.value = when {
            current < 1.02f -> 1.05f
            current < 1.08f -> 1.10f
            else -> 1.0f
        }
    }

    fun setPreviewTransform(scale: Float, tx: Float, ty: Float) {
        _previewTransform.value = PreviewTransformState(scale, tx, ty)
    }

    fun setZoom(scale: Float, tx: Float, ty: Float) {
        _zoomFactor.value = scale
        _translationX.value = tx
        _translationY.value = ty
        // Also update _previewTransform so zoom state persists across tab switches
        _previewTransform.value = PreviewTransformState(scale, tx, ty)
    }

    fun setVisualEffect(ordinal: Int) {
        val effect = Effect.entries.getOrElse(ordinal) { Effect.NONE }
        
        // THE FIX: Stop UI re-binds from overwriting user adjustments
        if (_visualEffect.value == effect) return 
        
        _visualEffect.value = effect
        invalidateRender()
        
        // THE FIX: Endless must be turned off by default when switching effects
        _isEndless.value = false
        
        // Cancel any active processing immediately to prevent encoder buffer deadlock
        if (isProcessing.value == true) {
            cancelProcessing()
        }

        // Calculate the default frame window
        val startFrame = _trimRange.value?.first ?: 1f
        val endFrame = _trimRange.value?.second ?: 300f
        val totalSelectedFrames = (endFrame - startFrame + 1).toInt()

        // Default is half the selected chunk, maxed at 30, with a floor of 2
        val halfChunk = (totalSelectedFrames / 2).coerceIn(2, 30)

        // Apply specific effect defaults
        val newDefault = when (effect) {
            Effect.MOTION_EXTRACTION -> 3
            else -> halfChunk
        }

        setFrameBlending(newDefault)
    }

    fun setEndless(endless: Boolean) { 
        _isEndless.value = endless
        invalidateRender()
    }
    fun setMotionSampling(value: Int) { 
        _motionSampling.value = value
        triggerPreviewRefresh()
    }
    fun setFrameBlending(value: Int) {
        val maxFrames = framesInput.value?.size ?: 30
        // Force the value to stay within logical bounds (minimum 2 to match slider, maximum = total frames)
        val safeValue = value.coerceIn(2, maxFrames.coerceAtLeast(2))
        _frameBlending.value = safeValue
        invalidateRender()
        triggerPreviewRefresh()
    }

    private fun triggerPreviewRefresh() {
        _triggerPreviewRefresh.value = (_triggerPreviewRefresh.value ?: 0) + 1
    }
    fun setSkipFrames(value: Int) {
        // Skip frames must be at least 1 to prevent divide by zero
        val safeValue = value.coerceAtLeast(1)
        _skipFrames.value = safeValue
        invalidateRender()
    }
    fun setSpeed(speed: Int) { _speed.value = speed }
    fun setPreviewFps(fps: Int) { _previewFps.value = fps }
    fun setFrameDuration(ms: Int) { _frameDuration.value = ms }

    fun triggerStudioProcess(suppressAutoSave: Boolean = false) {
        suppressAutoSaveOnce = suppressAutoSave
        _triggerStudioProcess.value = Unit
    }

    fun consumeAutoSaveSuppression(): Boolean {
        val suppressed = suppressAutoSaveOnce
        suppressAutoSaveOnce = false
        return suppressed
    }

    fun setResolution(res: Int) { _resolution.value = res }
    fun setEncodingH265(h265: Boolean) { _encodingH265.value = h265 }
    fun setPreserveAudio(preserve: Boolean) { _preserveAudio.value = preserve }
    fun setFileQuality(quality: Int) { _fileQuality.value = quality }
    fun setOutputFps(fps: Int) { _outputFps.value = fps }
    fun setZoomToFill(zoom: Boolean) { 
        _isZoomToFill.value = zoom
        // Invalidate render cache so user knows a new render is needed
        invalidateRender()
    }

    fun resetTransform() {
        _rotation.value = 0
        _cropRatio.value = CropRatio.SOURCE
        _transformState.value = TransformState()
    }

    fun setProcessedVideo(uri: Uri?) { 
        _processedVideoUri.value = uri
        if (uri != null) {
            _isSaveReady.value = true
        }
    }

    // Pending partial render (cancelled but had frames) - kept in cache for UI playback only
    private var _pendingPartialRenderPath: String? = null
    var pendingPartialRenderPath: String? = null
        private set

    fun setPendingPartialRender(path: String) {
        _pendingPartialRenderPath = path
        pendingPartialRenderPath = path
    }

    fun clearPendingPartialRender() {
        _pendingPartialRenderPath?.let { path ->
            try { java.io.File(path).delete() } catch (e: Exception) { /* ignore */ }
        }
        _pendingPartialRenderPath = null
        pendingPartialRenderPath = null
    }

    fun checkHardwareSupport(context: Context) {
        _isH265Supported.value = HardwareUtils.isHEVCHardwareSupported(context)
    }

    private val PREFS_NAME = "TimeLapsePrefs"
    private val SESSION_STATE_KEY = "session_state_json"

    /**
     * Keys written by the pre-3.0 session format. They belong to the session, not to
     * the user's data, so they are removed together with [SESSION_STATE_KEY] — but
     * the per-URI trim memory (`trim_start_<hash>` / `trim_end_<hash>`) is NOT.
     */
    private val LEGACY_SESSION_KEYS = arrayOf(
        "last_effect", "last_sampling", "last_rotation", "trim_start", "trim_end",
        "is_zoom_to_fill", "last_source_type", "last_codec", "trim_start_ms",
        "trim_end_ms", "auto_save_after_render", "is_endless", "frame_blending",
        "speed", "resolution", "encoding_h265", "preserve_audio", "output_fps",
        "file_quality", "full_res_export", "zoom_scale", "pan_x", "pan_y",
        "trim_start_frame_index", "trim_end_frame_index", "last_media_uri"
    )

    /**
     * Remove ONLY the persisted session.
     *
     * This used to be `prefs.edit().clear()`, which also destroyed the per-URI trim
     * memory and every unrelated key in the file. A stale or un-restorable session
     * (for example a folder whose child-document URI was stored instead of its tree
     * URI) must never cost the user anything but the session itself.
     */
    fun clearPersistedSession(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit().remove(SESSION_STATE_KEY)
            LEGACY_SESSION_KEYS.forEach { editor.remove(it) }
            editor.commit()
        } catch (e: Exception) {
            Log.e("TimeLapse", "Failed to clear persisted session", e)
        }
    }

    /**
     * Tolerant codec for the persisted session. `ignoreUnknownKeys` matters because a
     * schema change used to make decodeFromString throw, which fell back to the legacy
     * loader (returns null) and left the stale key in place forever.
     */
    private val sessionJson = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    fun saveSession(context: Context) {
        try {
            val input = framesInput.value
            val trimStartFrameIndex = if (input is ImagesFramesInput) _trimRange.value?.first ?: 0f else 0f
            val trimEndFrameIndex = if (input is ImagesFramesInput) _trimRange.value?.second ?: 0f else 0f

            // Derive the persisted source type from the media that is ACTUALLY loaded,
            // not from _lastSourceType. That field is set before the picker opens, so
            // cancelling the picker left a session describing the wrong kind of media:
            // "type=IMAGES, uri=<video>" restored a video through the image loader, which
            // neither threw nor showed a preview, so the broken session was never cleared.
            val sourceType = when (input) {
                is VideoFramesInput -> SourceType.VIDEO
                is ImagesFramesInput -> if (input.isFolder) SourceType.FOLDER else SourceType.IMAGES
                else -> _lastSourceType.value ?: SourceType.VIDEO
            }

            val state = SessionState(
                // For a folder this is the TREE uri (the only persistable grant), not
                // the first child image — see ImagesFramesInput.persistableUri.
                lastMediaUri = input?.persistableUri?.toString(),
                lastMediaUris = (input as? ImagesFramesInput)?.childUris?.map { it.toString() }
                    ?: emptyList(),
                lastEffect = _visualEffect.value?.ordinal ?: 0,
                lastSampling = _motionSampling.value ?: 10,
                lastRotation = _rotation.value ?: 0,
                trimStart = _trimRange.value?.first ?: 0f,
                trimEnd = _trimRange.value?.second ?: 100f,
                isZoomToFill = _isZoomToFill.value ?: false,
                lastSourceType = sourceType.name,
                lastCodec = _lastCodec.value?.fourCC ?: Codec.H264.fourCC,
                trimStartMs = _trimStartMs.value ?: 0L,
                trimEndMs = _trimEndMs.value ?: 0L,
                autoSaveAfterRender = _autoSaveAfterRender.value ?: true,
                isEndless = _isEndless.value ?: false,
                frameBlending = _frameBlending.value ?: 10,
                speed = _speed.value ?: 1,
                resolution = _resolution.value ?: Settings.RES_1080P,
                encodingH265 = _encodingH265.value ?: false,
                preserveAudio = _preserveAudio.value ?: false,
                zoomScale = _previewTransform.value?.scale ?: 1.0f,
                panX = _previewTransform.value?.translateX ?: 0.0f,
                panY = _previewTransform.value?.translateY ?: 0.0f,
                trimStartFrameIndex = trimStartFrameIndex,
                trimEndFrameIndex = trimEndFrameIndex,
                outputFps = _outputFps.value ?: 30,
                fileQuality = _fileQuality.value ?: 50,
                fullResExport = isFullResExportEnabled.value ?: false
            )

            val json = sessionJson.encodeToString(state)
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            // commit(), not apply(): the session must survive an immediate force-stop
            // after the media load that wrote it. This is a tiny JSON write.
            prefs.edit().putString(SESSION_STATE_KEY, json).commit()
        } catch (e: SecurityException) {
            Log.w("TimeLapse", "Lost URI permission. Cannot persist session.", e)
            // Clear saved session to prevent crash-loop on next boot
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().remove(SESSION_STATE_KEY).apply()
            } catch (clearEx: Exception) {
                // Ignore
            }
        } catch (e: Exception) {
            Log.e("TimeLapse", "Failed to save session", e)
        }
    }

    fun loadLastSession(context: Context): Pair<Uri?, String?> {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(SESSION_STATE_KEY, null)
            var uri: Uri? = null

            if (json != null) {
                try {
                    val state = sessionJson.decodeFromString<SessionState>(json)
                    // Use setValue since loadLastSession is always called from main thread
                    _visualEffect.value = Effect.values().getOrElse(state.lastEffect) { Effect.NONE }
                    _motionSampling.value = state.lastSampling
                    _rotation.value = state.lastRotation
                    _trimRange.value = Pair(state.trimStart, state.trimEnd)
                    _isZoomToFill.value = state.isZoomToFill
                    _lastSourceType.value = try {
                        SourceType.valueOf(state.lastSourceType)
                    } catch (e: Exception) {
                        SourceType.VIDEO
                    }
                    _lastCodec.value = Codec.values().find { it.fourCC == state.lastCodec } ?: Codec.H264
                    _trimStartMs.value = state.trimStartMs
                    _trimEndMs.value = state.trimEndMs
                    _autoSaveAfterRender.value = state.autoSaveAfterRender
                    _isEndless.value = false
                    _frameBlending.value = state.frameBlending
                    _speed.value = state.speed
                    _resolution.value = state.resolution
                    _encodingH265.value = state.encodingH265
                    _preserveAudio.value = state.preserveAudio
                    _outputFps.value = state.outputFps
                    _fileQuality.value = state.fileQuality
                    isFullResExportEnabled.value = state.fullResExport
                    _previewTransform.value = PreviewTransformState(state.zoomScale, state.panX, state.panY)
                    // Sync trim ms and frame-index values for audio muxing
                    setTrimRange(state.trimStart, state.trimEnd)
                    uri = state.lastMediaUri?.let { Uri.parse(it) }
                    _lastMediaUris.value = state.lastMediaUris.mapNotNull {
                        try { Uri.parse(it) } catch (e: Exception) { null }
                    }
                    // NOTE: the "does this still exist" gate lives in MainActivity
                    // (isUriAccessible), which understands persisted TREE grants. A second
                    // DocumentFile/checkUriPermission check here rejected every document
                    // that lives inside a granted tree (DocumentFile.fromSingleUri(tree
                    // child) is false and checkUriPermission does not see tree descendants),
                    // which cleared perfectly valid sessions. The per-source loaders clear
                    // the session if the file really is gone.
                } catch (e: Exception) {
                    // Fallback to old method if JSON parsing fails
                    return loadLastSessionLegacy(context)
                }
            } else {
                // Fallback to old method if no JSON found
                return loadLastSessionLegacy(context)
            }

            return Pair(uri, null)
        } catch (e: SecurityException) {
            Log.w("TimeLapse", "Lost URI permission. Cannot load session.", e)
            // Clear saved session to prevent crash-loop
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().remove(SESSION_STATE_KEY).apply()
            } catch (clearEx: Exception) {
                // Ignore
            }
            return Pair(null, null)
        } catch (e: Exception) {
            Log.e("TimeLapse", "Failed to load session", e)
            return Pair(null, null)
        }
    }
    
    private fun loadLastSessionLegacy(context: Context): Pair<Uri?, String?> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // The legacy format has no multi-select list.
        _lastMediaUris.value = emptyList()
        
        // Load effect by ordinal directly (works for both regular and ENDLESS variants)
        val savedOrdinal = prefs.getInt("last_effect", 0)
        _visualEffect.value = Effect.values().getOrElse(savedOrdinal) { Effect.NONE }
        
        _motionSampling.value = prefs.getInt("last_sampling", 10)
        _rotation.value = prefs.getInt("last_rotation", 0)
        
        val trimStart = prefs.getFloat("trim_start", 0f)
        val trimEnd = prefs.getFloat("trim_end", 100f)
        _trimRange.value = Pair(trimStart, trimEnd)
        _isZoomToFill.value = prefs.getBoolean("is_zoom_to_fill", false)
        
        // Load new persistence fields
        _lastSourceType.value = try {
            SourceType.valueOf(prefs.getString("last_source_type", SourceType.VIDEO.name) ?: SourceType.VIDEO.name)
        } catch (e: Exception) {
            SourceType.VIDEO
        }
        _lastCodec.value = Codec.values().find { it.fourCC == prefs.getString("last_codec", "h264") } ?: Codec.H264
        _trimStartMs.value = prefs.getLong("trim_start_ms", 0L)
        _trimEndMs.value = prefs.getLong("trim_end_ms", 0L)
        _autoSaveAfterRender.value = prefs.getBoolean("auto_save_after_render", true)
        _isEndless.value = prefs.getBoolean("is_endless", false)
        _frameBlending.value = prefs.getInt("frame_blending", 10)
        _speed.value = prefs.getInt("speed", 1)
        _resolution.value = prefs.getInt("resolution", Settings.RES_1080P)
        _encodingH265.value = prefs.getBoolean("encoding_h265", false)
        _preserveAudio.value = prefs.getBoolean("preserve_audio", true)
        _outputFps.value = prefs.getInt("output_fps", 30)
        _fileQuality.value = prefs.getInt("file_quality", 50)
        isFullResExportEnabled.value = prefs.getBoolean("full_res_export", false)
        
        // Load zoom/pan
        val zoomScale = prefs.getFloat("zoom_scale", 1.0f)
        val panX = prefs.getFloat("pan_x", 0.0f)
        val panY = prefs.getFloat("pan_y", 0.0f)
        _previewTransform.value = PreviewTransformState(zoomScale, panX, panY)

        // Sync trim ms and frame-index values for audio muxing
        setTrimRange(trimStart, trimEnd)

        val uriString = prefs.getString("last_media_uri", null)
        val uri = uriString?.let { Uri.parse(it) }
        
        // Check if the saved media file still exists before loading
        if (uri != null) {
            try {
                if (DocumentFile.fromSingleUri(getApplication(), uri)?.exists() != true) {
                    Log.w("TimeLapse", "Saved media file no longer exists, clearing session")
                    clearSession()
                    return Pair(null, null)
                }
            } catch (e: Exception) {
                Log.w("TimeLapse", "Could not check file existence, proceeding anyway", e)
            }
        }
        
        return Pair(uri, null)
    }

    fun fillFullMask() {
        val currentMask = _firstFrameMask.value ?: return
        // Set the entire Mat to white (255) to disable masking for the whole frame
        org.opencv.imgproc.Imgproc.rectangle(
            currentMask, 
            org.opencv.core.Point(0.0, 0.0), 
            org.opencv.core.Point(currentMask.cols().toDouble(), currentMask.rows().toDouble()), 
            org.opencv.core.Scalar(255.0), 
            -1
        )
        _firstFrameMask.postValue(currentMask)
    }

    @Volatile
    var isUserPaused = false

    fun togglePlayback() {
        val newPlaying = !(isPlaying.value ?: false)
        isPlaying.value = newPlaying
        isUserPaused = !newPlaying
    }

    fun pausePlayback() {
        isPlaying.value = false
        isUserPaused = true
    }

    fun updateDimensions(width: Int, height: Int) {
        // STRICT BLOCK: Never allow the UI to set dimensions to 0x0
        if (width <= 0 || height <= 0) return 
        
        framesInput.value?.let {
            it.width = width
            it.height = height
        }
    }

    fun clearSession() {
        try {
            clearPersistedSession(getApplication())
            _trimRange.value = Pair(1f, 100f)
            _lastSourceType.value = SourceType.VIDEO
            _lastMediaUris.value = emptyList()
            _framesInput.value = null
            _processedVideoUri.value = null
        } catch (e: Exception) {
            Log.e("TimeLapse", "Failed to clear session", e)
        }
    }

    override fun onCleared() {
        _framesInput.value?.release()
        _firstFrame.value?.release()
        _firstFrameMask.value?.release()
        super.onCleared()
    }
}