package com.dan.timelapse

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.content.ContentValues
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.dan.timelapse.utils.HardwareUtils
import com.dan.timelapse.utils.DeviceTier
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.dan.timelapse.databinding.ActivityMainBinding
import com.dan.timelapse.framesinput.ImagesFramesInput
import com.dan.timelapse.framesinput.VideoFramesInput
import com.dan.timelapse.images.ImageLoaderUtils
import com.dan.timelapse.screens.*
import com.dan.timelapse.utils.UriFile
import com.dan.timelapse.widgets.OnSurfaceReadyListener
import com.dan.timelapse.widgets.TouchImageView
import com.dan.timelapse.widgets.ZoomableFrameLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import org.opencv.android.Utils
import org.opencv.core.CvException
import org.opencv.core.Core
import org.opencv.core.Mat
import com.dan.timelapse.utils.EtaCalibrator
import com.dan.timelapse.utils.PhaseProfileKey
import com.dan.timelapse.utils.isDevicePluggedIn
import com.dan.timelapse.utils.OutputParams
import com.dan.timelapse.video.VideoTools
import java.io.File
import androidx.appcompat.app.AlertDialog

private enum class MfsrPhase { EXTRACT, SCAN, MATRICES, ACCUMULATE, FINALIZE, COMPLETE }

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val model: MainViewModel by viewModels()
    private lateinit var gestureDetector: GestureDetector
    private var playbackJob: Job? = null
    
    private var isTrayVisible = false
    private var isTrayForcedOpen = false
    private var isFrameSelectionMode = false
    private var isMaskEditMode = false
    private var generateDialog: AlertDialog? = null
    private var processingJob: kotlinx.coroutines.Job? = null
    private var cancelPressCount = 0
    
    // Loading dialog for huge folders/files
    private var loadingDialog: AlertDialog? = null
    private var loadingJob: Job? = null

    // Colors are handled via XML theme attributes (?attr/colorPrimary)
    
    // Video type tracking: true = original (trimmed), false = rendered
    var isPlayingOriginalVideo = true
        private set
    
    // Capture MediaPlayer for frame-accurate seeking
    private var activeMediaPlayer: android.media.MediaPlayer? = null

    // Prevents watchdog from spamming seek commands before the player has time to move
    private var lastWatchdogSeekTime = 0L
    
    // Toast feedback handlers
    private val toastHandler = Handler(Looper.getMainLooper())
    private var tabStatusToastRunnable: Runnable? = null
    private var playPauseToastRunnable: Runnable? = null
    
    // FIX 12: Real-time memory monitor to track UI freezes
    private val monitorHandler = Handler(Looper.getMainLooper())
    private var tickCount = 0
    private val monitorRunnable = object : Runnable {
        override fun run() {
            try {
                tickCount++
                val runtime = Runtime.getRuntime()
                val jvmUsed = (runtime.totalMemory() - runtime.freeMemory()) / 1048576L
                val jvmMax = runtime.maxMemory() / 1048576L
                
                // Track C++/OpenCV native memory
                val nativeUsed = android.os.Debug.getNativeHeapAllocatedSize() / 1048576L
                
                binding.tvDebugMonitor.text = buildString {
                    append("Tick: $tickCount\n")
                    append("JVM: ${jvmUsed}MB / ${jvmMax}MB\n")
                    append("Native: ${nativeUsed}MB\n")
                    append("State: ${if (binding.videoContainer.isEnabled) "Zoom ENABLED" else "Zoom LOCKED"}")
                }
            } catch (e: Exception) {
                // Ignore errors during shutdown
            }
            monitorHandler.postDelayed(this, 1000)
        }
    }
    
    // Track current render job ID to ignore stale progress updates
    private var currentRenderJobId = 0
    
    // FIX 11: Pre-allocated Rect objects for touch dispatch to eliminate GC churn
    private val frameSelectorRect = android.graphics.Rect()
    private val navRect = android.graphics.Rect()
    private val trayRect = android.graphics.Rect()
    private var lastTouchAction = 0
    
    // Trim-Sync Handler: 100ms watchdog loop for video preview
    private val trimWatchdogHandler = Handler(Looper.getMainLooper())
    private var videoDuration = 0L  // Store video duration for progress calculation
    private var isScrubbing = false
    private var lastScrubTime = 0L  // Lock to prevent UI fighting during SeekBar drag
    
    // Screen timeout management
    private var isCharging = false
    private var isBatteryLow = false
    private val FIVE_MINUTES_MS = 5 * 60 * 1000L
    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleRunnable = Runnable {
        if (!isCharging) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    
    private val powerStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.action?.let { action ->
                when (action) {
                    Intent.ACTION_POWER_CONNECTED -> isCharging = true
                    Intent.ACTION_POWER_DISCONNECTED -> isCharging = false
                    Intent.ACTION_BATTERY_LOW -> isBatteryLow = true
                    Intent.ACTION_BATTERY_OKAY -> isBatteryLow = false
                }
                updateScreenLock()
            }
        }
    }
    
    // FIX 1: Bitmap pool to eliminate GC churn during image sequence playback
    private val previewBitmapPool = arrayOfNulls<android.graphics.Bitmap>(3)
    private var previewPoolIndex = 0
    
    private fun getPooledPreviewBitmap(width: Int, height: Int): android.graphics.Bitmap {
        val current = previewBitmapPool[previewPoolIndex]
        if (current == null || current.width != width || current.height != height) {
            current?.recycle()
            previewBitmapPool[previewPoolIndex] = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        }
        val bmp = previewBitmapPool[previewPoolIndex]!!
        previewPoolIndex = (previewPoolIndex + 1) % previewBitmapPool.size
        return bmp
    }
    
    // FIX 2: Job tracker for scrubbing to prevent IO queue flooding
    private var displayImageJob: kotlinx.coroutines.Job? = null
    
    private fun updateVideoProgress() {
        if (isScrubbing) return 
        
        if (generateDialog?.isShowing == true || binding.mainVideoView.visibility == android.view.View.VISIBLE) {
            return // Just return. The runnable will handle its own next cycle.
        }
        
        // CRITICAL FIX: Don't query a dead/absent player
        try {
            activeMediaPlayer?.let { mp ->
                if (!mp.isPlaying) return
            }
        } catch (e: IllegalStateException) {
            return
        }
        
        val videoDuration = try { binding.videoView.duration } catch(e: Exception) { -1 }
        
        // THE FIX: Strict State Guard
        val isPlayingSafely = try { binding.videoView.isPlaying } catch(e: Exception) { false }
        if (videoDuration <= 0 || videoDuration == 100 || !isPlayingSafely) {
            return
        }
        
        val currentPos = try { binding.videoView.currentPosition } catch(e: Exception) { -1 }
        if (currentPos < 0) return
        
        // Only update if dimensions are valid and we are within bounds
        if (currentPos in 0..videoDuration) {
            if (isPlayingOriginalVideo) {
                // 1. Get raw trim values
                val rawTrimStart = model.getTrimStartMs().toInt()
                val rawTrimEnd = model.getTrimEndMs().toInt()
                
                // 2. SAFEGUARD: If trim is uninitialized (0), default to the full video duration
                val safeTrimStart = if (rawTrimEnd == 0) 0 else rawTrimStart
                val safeTrimEnd = if (rawTrimEnd == 0) videoDuration else rawTrimEnd
                
                // 3. Scale the SeekBar to the exact width of the trim
                val trimmedDuration = safeTrimEnd - safeTrimStart
                if (trimmedDuration > 0) {
                    // Only update max if it changed (prevents UI stutter)
                    if (binding.videoSeekBar.max != trimmedDuration) {
                        binding.videoSeekBar.max = trimmedDuration
                    }
                    
                    // 4. Map the current position to start at 0 on the UI
                    val normalizedProgress = (currentPos - safeTrimStart).coerceIn(0, trimmedDuration)
                    binding.videoSeekBar.progress = normalizedProgress
                }
            } else {
                // RENDERED VIDEO: Maps perfectly to its own duration
                if (videoDuration > 0) {
                    binding.videoSeekBar.max = videoDuration
                    binding.videoSeekBar.progress = currentPos
                }
            }
        }
    }
    
    private val trimWatchdogRunnable = object : Runnable {
        override fun run() {
            if (generateDialog?.isShowing == true) {
                trimWatchdogHandler.postDelayed(this, 100L)
                return
            }

            val runtime = Runtime.getRuntime()
            val freeMem = runtime.freeMemory()
            if (freeMem < 32 * 1024 * 1024) {
                trimWatchdogHandler.postDelayed(this, 100L)
                return
            }

            updateVideoProgress()

            // CRITICAL FIX: Skip player ops if no active player
            val alive = try { activeMediaPlayer?.isPlaying ?: false } catch(e: IllegalStateException) { false }
            if (!alive) {
                trimWatchdogHandler.postDelayed(this, 100L)
                return
            }

            val videoDuration = try { binding.videoView.duration } catch(e: Exception) { -1 }
            if (videoDuration <= 0 || model.framesInput.value == null) {
                trimWatchdogHandler.postDelayed(this, 100L)
                return
            }
            
            // SEEK DEBOUNCE: Give async seekTo() time to land before enforcing bounds
            if (System.currentTimeMillis() - lastWatchdogSeekTime < 500) {
                trimWatchdogHandler.postDelayed(this, 100L)
                return
            }

            val isPlayingSafely = try { binding.videoView.isPlaying } catch(e: Exception) { false }
            if (isPlayingSafely) {
                // Real-time Video Speed Sync (For Original AND Rendered videos)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    try {
                        activeMediaPlayer?.let { mp ->
                            val targetFps = model.previewFps.value ?: 30
                            var baseFps = 30

                            if (isPlayingOriginalVideo) {
                                val input = model.framesInput.value
                                if (input is com.dan.timelapse.framesinput.VideoFramesInput) {
                                    baseFps = input.fps.coerceAtLeast(1)
                                }
                            } else {
                                baseFps = 30
                            }

                            val speedFactor = targetFps.toFloat() / baseFps.toFloat()
                            val currentSpeed = try { mp.playbackParams.speed } catch(e: Exception) { 1.0f }

                            if (kotlin.math.abs(currentSpeed - speedFactor) > 0.01f) {
                                val wasPlaying = mp.isPlaying
                                mp.playbackParams = mp.playbackParams.setSpeed(speedFactor)
                                if (!wasPlaying) mp.pause()
                            }
                        }
                    } catch (e: Exception) {
                    }
                }
                if (!isPlayingOriginalVideo) {
                    trimWatchdogHandler.postDelayed(this, 100L)
                    return
                }
                val currentMs = try { binding.videoView.currentPosition } catch(e: Exception) { -1 }
                val fps = (model.framesInput.value?.fps?.coerceAtLeast(1) ?: 30).toLong()
                val trimRange = model.trimRange.value ?: Pair(1f, Float.MAX_VALUE)
                val startFrame = trimRange.first.toInt().coerceAtLeast(1)
                val endFrame = trimRange.second.toInt().coerceAtLeast(startFrame + 1)
                val startMs = ((startFrame - 1) * 1000L) / fps
                val endMs = (endFrame * 1000L) / fps

                // Update preview progress
                val clipDuration = (endMs - startMs).coerceAtLeast(1L)
                val progress = ((currentMs - startMs).toFloat() / clipDuration).coerceIn(0f, 1f)
                model.updatePreviewProgress(progress)

                if (currentMs >= endMs && endMs > 0) {
                    lastWatchdogSeekTime = System.currentTimeMillis()
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        activeMediaPlayer?.seekTo(startMs.toLong(), MediaPlayer.SEEK_CLOSEST)
                    } else {
                        binding.videoView.seekTo(startMs.toInt())
                    }
                    try {
                        binding.videoView.start()
                    } catch (e: Exception) {
                        // Swallow state desyncs
                    }
                } else if (currentMs > 0 && currentMs < startMs) {
                    // Catch native MediaPlayer looping to position 0 (isLooping=true suppresses OnCompletionListener)
                    lastWatchdogSeekTime = System.currentTimeMillis()
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        activeMediaPlayer?.seekTo(startMs.toLong(), MediaPlayer.SEEK_CLOSEST)
                    } else {
                        binding.videoView.seekTo(startMs.toInt())
                    }
                    try {
                        binding.videoView.start()
                    } catch (e: Exception) { }
                }
            }
            trimWatchdogHandler.postDelayed(this, 100L)
        }
    }
    
    // Dimmer and Click Shield
    private var isDimmerActive = false

    private val openFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { onFileSelected(it) }
        } else {
            Toast.makeText(this, "Video selection cancelled or permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private val openMultipleImagesLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val uris = result.data?.clipData?.let {
                (0 until it.itemCount).map { i -> it.getItemAt(i).uri }
            } ?: result.data?.data?.let { listOf(it) }

            if (!uris.isNullOrEmpty()) onImagesSelected(uris)
        } else {
            Toast.makeText(this, "Image selection cancelled or permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private val openFolderLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { onFolderSelected(it) }
        } else {
            Toast.makeText(this, "Folder selection cancelled or permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    fun openPicker() = openFileLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "video/*"
        addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // Hint to use the photo picker on Android 13+ for better thumbnails
        putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
            android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
    })

    fun openImagePicker() = openMultipleImagesLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "image/*"
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
    })

    fun openFolderPicker() = openFolderLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            org.opencv.android.OpenCVLoader.initDebug()
            System.loadLibrary("timelapse")
        } catch (e: Exception) {
            Log.e("MainActivity", "JNI Load Error", e)
        }
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        clearMfsrCache()
        model.loadMfsrEtaCache(this)
        
        ViewCompat.setOnApplyWindowInsetsListener(binding.mainRoot) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.bottomNavigation.setPadding(0, 0, 0, insets.bottom)
            binding.videoContainer.setPadding(0, insets.top, 0, 0)
            WindowInsetsCompat.CONSUMED
        }
        
        setupNavigation()
        initGlobalGestures()
        setupTrayBehaviors()
        setupVideoSeekBar()
        setupBackPressHandler()
        
        // Register power state receiver
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
        }
        registerReceiver(powerStateReceiver, filter)
        
        // Initial battery state check
        val batteryStatus: Intent? = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
        isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                     status == android.os.BatteryManager.BATTERY_STATUS_FULL
        
        updateScreenLock()
        
        // Start memory monitor (hidden by default; set to VISIBLE during debugging)
        binding.tvDebugMonitor.visibility = View.GONE
        monitorHandler.post(monitorRunnable)
        
        supportFragmentManager.beginTransaction()
            .replace(R.id.tray_content_holder, SourceFragment::class.java.getDeclaredConstructor().newInstance())
            .commitNow()
        
        showTray()

        // Auto-load last session if exists
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // loadLastSession assigns LiveData values (setValue), which requires the main thread
                val (uri, _) = withContext(Dispatchers.Main) { model.loadLastSession(this@MainActivity) }
                if (uri != null) {
                    if (isUriAccessible(uri)) {
                        withContext(Dispatchers.Main) {
                            Log.d("TimeLapse", "Auto-loading last session: $uri")
                            onOpenLastSelection()
                        }
                    } else {
                        Log.w("TimeLapse", "Persisted URI permission revoked. Clearing stale session.")
                        clearSavedSession()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: FileNotFoundException) {
                Log.e("TimeLapse", "Auto-load failed — file not found, clearing session", e)
                withContext(Dispatchers.Main + NonCancellable) { clearSavedSession() }
            } catch (e: IOException) {
                Log.w("TimeLapse", "Auto-load failed — transient I/O error, keeping session", e)
            } catch (e: Exception) {
                Log.e("TimeLapse", "Auto-load failed — clearing stale session", e)
                withContext(Dispatchers.Main + NonCancellable) { clearSavedSession() }
            }
        }

        binding.mainVideoView.setSurfaceReadyListener(object : OnSurfaceReadyListener {
            override fun onSurfaceReady() {
                // ...
            }
        })
         
        binding.dimmerView.setOnClickListener {
            binding.dimmerView.visibility = View.GONE
            if (isTrayVisible) {
                hideTray()
            }
        }

        binding.videoContainer.onZoomListener = object : ZoomableFrameLayout.OnZoomListener {
            override fun onZoom(scale: Float, translationX: Float, translationY: Float) {
                model.setZoom(scale, translationX, translationY)
            }
            
            override fun onZoomReset() {
                model.setZoom(1f, 0f, 0f)
            }
        }
        
        binding.videoContainer.onGestureEnd = {
            // THE FIX: Only drop the force-open flag and auto-hide if the user was actually zooming.
            // Do not collapse the tray if they merely swiped up to open it (zoom == 1f).
            val currentZoom = model.zoomFactor.value ?: 1f
            if (currentZoom > 1.05f) {
                isTrayForcedOpen = false
                if (model.framesInput.value != null && shouldAutoHideTrayForVideo()) {
                    hideTray()
                }
            }
        }
        


        binding.fullScreenFrameSlider.setOnClickListener { }
        binding.fullScreenFrameSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val positionMs = value.toLong()
                // FIX 2: Removed heavy frame rendering from live drag - moved to onStopTrackingTouch
                updateFrameInfoText(positionMs)
            }
        }
        
        binding.fullScreenFrameSlider.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {
                isScrubbing = true
                
                binding.mainVideoView.visibility = android.view.View.GONE
                binding.videoView.visibility = android.view.View.VISIBLE
                
                try {
                    if (binding.videoView.isPlaying) {
                        binding.videoView.pause()
                    }
                } catch (e: Exception) {}
            }
            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                isScrubbing = false
                lastScrubTime = System.currentTimeMillis()
                val positionMs = slider.value.toLong()
                (model.selectedFramePositionMs as? androidx.lifecycle.MutableLiveData<Long>)?.postValue(positionMs)
                updateFramePreview(positionMs.toInt())
                updateFrameInfoText(positionMs)
            }
        })

        binding.btnConfirmFrame.setOnClickListener {
            confirmSelectedFrame()
        }
        
        binding.switchFullRes.isChecked = model.isFullResExportEnabled.value == true
        binding.switchFullRes.setOnCheckedChangeListener { _, isChecked ->
            model.isFullResExportEnabled.value = isChecked
        }

        model.rotation.observe(this) { degrees ->
            binding.mainVideoView.setRotation(degrees)
            applyVideoRotation(degrees)
            binding.videoView.requestLayout()
            
            binding.videoContainer.post {
                if (!isTrayForcedOpen && model.framesInput.value != null && shouldAutoHideTrayForVideo()) {
                    hideTray()
                }
            }
        }
        
        model.transformState.observe(this) { state ->
            val ratio = state.getAspectRatio()
            applyVideoAspectRatio(ratio)
            binding.videoView.requestLayout()
            binding.videoView.invalidate()
            binding.videoContainer.invalidate() 
            
            binding.videoContainer.post {
                if (!isTrayForcedOpen && model.framesInput.value != null && shouldAutoHideTrayForVideo()) {
                    hideTray()
                }
            }
        }

        model.scanFileEvent.observe(this) { notifyGallery(File(it)) }
        model.trimRange.observe(this) { 
            if (model.isProcessing.value != true && !model.isUserPaused) {
                startPreviewLoop(10) 
            }
        }
        
        model.framesInput.observe(this) { input ->
            binding.emptyStateOverlay.visibility = if (input == null) View.VISIBLE else View.GONE
        }
        
        model.isZoomToFill.observe(this) { _ ->
            val isPlaying = playbackJob?.isActive == true
            val input = model.framesInput.value
            val isImageSequence = input is ImagesFramesInput
            
            if (!isPlaying && isImageSequence && input != null) {
                val currentIndex = binding.videoSeekBar.progress.coerceIn(0, input.size - 1)
                displayImageFrame(currentIndex)
            }
        }

        // Real-time Image Sequence Speed Sync
        model.previewFps.observe(this) { _ ->
            val input = model.framesInput.value
            if (input is ImagesFramesInput && isPlayingOriginalVideo) {
                if (playbackJob?.isActive == true) {
                    startPreviewLoop(Int.MAX_VALUE)
                }
            }
        }

        // G9-06: Live effect preview - show current frame at slider position (effects applied on render)
        model.triggerPreviewRefresh.observe(this) { _ ->
            val input = model.framesInput.value
            if (input == null || model.isProcessing.value == true) return@observe
            if (model.processedVideoUri.value != null || model.processedImageUri.value != null) return@observe

            lifecycleScope.launch(Dispatchers.Default) {
                try {
                    val trimRange = model.trimRange.value ?: Pair(1f, input.size.toFloat())
                    val startFrame = (trimRange.first - 1).toInt().coerceIn(0, input.size - 1)
                    val endFrame = (trimRange.second - 1).toInt().coerceIn(0, input.size - 1)
                    val frameIndex = (startFrame + binding.videoSeekBar.progress).coerceIn(startFrame, endFrame)
                    
                    input.getFrame(frameIndex)?.let { frame ->
                        val bmp = getPooledPreviewBitmap(frame.cols(), frame.rows())
                        Utils.matToBitmap(frame, bmp)
                        runOnUiThread {
                            binding.mainVideoView.setImageBitmap(bmp)
                            binding.videoSeekBar.progress = frameIndex
                        }
                        frame.release()
                    }
                } catch (e: Exception) {
                    Log.w("LivePreview", "Preview refresh failed", e)
                }
            }
        }

        model.triggerStudioProcess.observe(this) {
            // FIX 3: Force media player cleanup on Main thread BEFORE heavy CPU work begins
            lifecycleScope.launch(Dispatchers.Main) {
                try {
                    if (binding.videoView.isPlaying) {
                        binding.videoView.pause()
                    }
                    binding.videoView.stopPlayback()
                } catch (e: Exception) { }
            }
            
            // Capture this IMMEDIATELY at the top of the observer, before dialogs or coroutines start
            val exactPausedMs = try { binding.videoView.currentPosition.toLong() } catch(e: Exception){ 0L }
            
            val input = model.framesInput.value
            val width = model.outputWidth
            val height = model.outputHeight
            
            if (input == null) {
                showToast("No media loaded")
                return@observe
            }
            if (width <= 0 || height <= 0) {
                showToast("Invalid video dimensions: ${width}x${height}")
                return@observe
            }
            
            // Signal old render to stop immediately, before awaiting it
            model.cancelProcessing()
            
            // Launch on Main to await old render cleanup, then start new render
            lifecycleScope.launch(Dispatchers.Main) {
                // CRITICAL: Wait for the old render's drainEncoder to fully release
                // the hardware MediaCodec before allocating a new encoder instance.
                // cancel() is cooperative; cancelAndJoin() guarantees the old
                // coroutine's finally block (and drainEncoder) has actually finished.
                processingJob?.cancelAndJoin()
                
                // Now safe — old MediaCodec is guaranteed released
                model.resetProcessingState()
                val currentJobId = ++currentRenderJobId
                
                val currentParams = OutputParams().apply {
                    set(OutputParams.KEY_EFFECT, model.visualEffect.value?.ordinal ?: 0)
                    set(OutputParams.KEY_ENDLESS, if (model.isEndless.value == true) 1 else 0)
                    set(OutputParams.KEY_EFFECT_SIZE, model.frameBlending.value ?: 10)
                    
                    val trimRange = model.trimRange.value ?: Pair(1f, 100f)
                    set(OutputParams.KEY_TRIM_START, trimRange.first.toInt())
                    set(OutputParams.KEY_TRIM_END, trimRange.second.toInt())
                    set(OutputParams.KEY_SPEED, model.speedMultiplier.value ?: 1)
                    set(OutputParams.KEY_ZOOM_TO_FILL, if (model.isZoomToFill.value == true) 1 else 0)
                    
                    set(OutputParams.KEY_WIDTH, width)
                    set(OutputParams.KEY_HEIGHT, height)
                    set(OutputParams.KEY_FPS, model.previewFps.value ?: 30)
                    set(OutputParams.KEY_H265, if (model.encodingH265.value == true) 1 else 0)
                    
                    set(OutputParams.KEY_STABILIZATION, if (model.isStabilized.value == true) 1 else 0)
                    set(OutputParams.KEY_ALIGN_TO_FIRST, if (model.alignToFirstFrame.value == true) 1 else 0)
                    set(OutputParams.KEY_STABILIZATION_ZOOM, ((model.stabilizationZoom.value ?: 1.0f) * 100).toInt())
                    
                    set("ROTATION_STATE", model.rotation.value ?: 0)
                    set("CROP_STATE", model.cropRatio.value?.ordinal ?: 0)
                    
                    val maskHash = if (model.isStabilized.value == true) {
                        model.firstFrameMask.value?.let { mask ->
                            if (!mask.empty()) mask.hashCode() else 0
                        } ?: 0
                    } else 0
                    set(OutputParams.KEY_MASK_HASH, maskHash)
                }
                
                val lastParams = model.lastProcessedParams
                val hasExistingFile = model.lastGeneratedFilePath?.let { File(it).exists() } ?: false
                
                if (hasExistingFile && lastParams != null) {
                    val compareResult = currentParams.compareWith(lastParams)
                    if (compareResult == OutputParams.COMPARE_NOT_CHANGED) {
                        if (model.lastGeneratedFrameCount == 0) {
                            model.lastGeneratedFilePath?.let { path ->
                                val retriever = android.media.MediaMetadataRetriever()
                                try {
                                    retriever.setDataSource(path)
                                    val frameCountStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                                    model.lastGeneratedFrameCount = frameCountStr?.toIntOrNull() ?: 0
                                } catch (e: Exception) {
                                    Log.e("TimeLapse", "Failed to get frame count from cached video", e)
                                } finally {
                                    retriever.release()
                                }
                            }
                        }
                        Toast.makeText(this@MainActivity, "Video already rendered with current settings", Toast.LENGTH_LONG).show()
                        model.lastGeneratedFilePath?.let { path ->
                            model.setProcessedVideo(Uri.fromFile(File(path)))
                        }
                        return@launch
                    } else if (compareResult == OutputParams.COMPARE_CHANGED_ONLY_FPS) {
                        val inputPath = model.lastGeneratedFilePath ?: return@launch
                        val sourceFps = lastParams.get(OutputParams.KEY_FPS).coerceAtLeast(1)
                        val targetFps = model.previewFps.value ?: 30
                        val outputFile = File(cacheDir, "fps_remux_${System.currentTimeMillis()}.mp4")
                        try {
                            com.dan.timelapse.video.VideoTools.fastRemuxVideoSpeed(inputPath, outputFile.absolutePath, sourceFps, targetFps)
                            model.setProcessedVideo(Uri.fromFile(outputFile))
                            model.lastGeneratedFilePath = outputFile.absolutePath
                            model.setLastProcessedParams(currentParams)
                            showToast("FPS changed from ${sourceFps}fps to ${targetFps}fps")
                        } catch (e: Exception) {
                            showToast("FPS remux failed, using existing file")
                            model.setProcessedVideo(Uri.fromFile(File(inputPath)))
                        }
                        return@launch
                    }
                }
                
                stopPreviewLoop()
                hideTray()
                
                // Pre-emptive video shutdown before heavy processing to prevent deadlock
                binding.mainVideoView.visibility = View.INVISIBLE
                binding.videoView.pause()
                binding.videoView.safeTeardown()
                
                model.isProcessing.value = true
                showGenerateDialog()
                model.startRenderingTimer()
                
                // Clear any pending partial render from previous cancelled render
                model.clearPendingPartialRender()
                
                val paramsToSave = currentParams
                
                processingJob = model.viewModelScope.launch(Dispatchers.IO) {
                val currentEffect = model.visualEffect.value
                val isMFSR = currentEffect == Effect.SUPER_RESOLUTION
                // Diagnostic: log the render path decision for first-run troubleshooting
                android.util.Log.d("RenderGuard", "Render trigger: currentEffect=$currentEffect, isMFSR=$isMFSR")
                val fileExt = if (isMFSR) "jpg" else "mp4"
                val timestamp = System.currentTimeMillis()
                val baseName = model.getFormattedExportName(fileExt).substringBeforeLast(".")
                val outputFile = File(cacheDir, "${baseName}_${timestamp}.${fileExt}")
                if (outputFile.exists()) {
                    outputFile.delete()
                }
                
                val processor = VideoProcessor(model, this@MainActivity, width, height)
                model.lastGeneratedFilePath = outputFile.absolutePath

                processor.onProgress = { current, total ->
                    if (currentRenderJobId == currentJobId) {
                        model.updateProcessingProgress(current, total)
                    }
                }
                
                processor.onProgressWithStatus = { current, total, statusText ->
                    if (currentRenderJobId == currentJobId) {
                        model.updateProcessingProgress(current, total)
                        if (statusText.isNotEmpty()) {
                            model.updateProcessingStatusText(statusText)
                        }
                    }
                    RenderService.instance?.updateProgress(current, total, statusText)
                }
                
                // Broadcast progress to MFSRService for background tracking
                processor.onProgressWithETA = { current, total, statusText, remainingTime ->
                    if (currentRenderJobId == currentJobId) {
                        model.updateProcessingProgress(current, total)
                        model.updateEstimatedTimeRemaining(remainingTime)
                        if (statusText.isNotEmpty()) {
                            model.updateProcessingStatusText(statusText)
                        }
                    }
                    // Also broadcast to MFSRService LiveData
                    val progressPercent = if (total > 0) (current * 100 / total) else 0
                    MFSRService.progressLiveData.postValue(progressPercent)
                    MFSRService.etaLiveData.postValue(remainingTime)
                }
                
                processor.onBusyStateChanged = { isBusy ->
                    runOnUiThread {
                        binding.globalProgressBar.visibility = if (isBusy) View.VISIBLE else View.GONE
                    }
                }
                
                if (isMFSR) {
                    val targetFrameIndex: Int
                    val fps = input.fps.coerceAtLeast(1)
                    val totalFrames = input.size
                    
                    // Priority 1: Use Frame Selection position (explicit user choice from Tray 4)
                    val frameSelectionMs = model.selectedFramePositionMs.value ?: 0L
                    
                    // Priority 2: Use center of Tray 1 trim range as fallback
                    val trimRange = model.trimRange.value
                    
                    targetFrameIndex = if (frameSelectionMs > 0L) {
                        // User explicitly selected a frame in Frame Selection mode (Tray 4)
                        ((frameSelectionMs / 1000.0) * fps).toInt().coerceIn(0, totalFrames - 1)
                    } else if (trimRange != null) {
                        // Fallback: use center of Tray 1 trim range
                        val centerFrame = ((trimRange.first + trimRange.second) / 2).toInt()
                        centerFrame.coerceIn(0, totalFrames - 1)
                    } else {
                        // Ultimate fallback: center of entire video
                        (totalFrames / 2).coerceIn(0, totalFrames - 1)
                    }

                    // NEW: Use clean MFSRProcessor. Works for both video and image sequences.
                    val videoUri = input.videoUri
                    
                    // Adaptive scale factor based on device RAM
                    val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                    val memInfo = android.app.ActivityManager.MemoryInfo()
                    activityManager.getMemoryInfo(memInfo)
                    val ramGB = memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
                    val adaptiveScale = when {
                        ramGB >= 8.0 -> 4.0f
                        ramGB >= 6.0 -> 3.0f
                        else -> 2.0f
                    }
                    Log.i("MFSR", "Device RAM: ${String.format("%.1f", ramGB)}GB → scale=${adaptiveScale}x")
                    
                    // Pass trim bounds (0-based frame indices from ViewModel)
                    val trimStartIdx = (model.trimStart.value ?: 0).coerceAtLeast(0)
                    val trimEndIdx = (model.trimEnd.value ?: input.size - 1).coerceAtMost(input.size - 1)

                    val mfsrProcessor = if (input is VideoFramesInput && videoUri != null) {
                        com.dan.timelapse.mfsr.MFSRProcessor.forVideo(
                            context = this@MainActivity,
                            videoUri = videoUri,
                            anchorFrameIndex = targetFrameIndex,
                            scaleFactor = adaptiveScale,
                            trimStartFrame = trimStartIdx,
                            trimEndFrame = trimEndIdx
                        )
                    } else {
                        // Image sequence or folder — use FramesInput-backed source
                        com.dan.timelapse.mfsr.MFSRProcessor.forImages(
                            context = this@MainActivity,
                            framesInput = input,
                            anchorFrameIndex = targetFrameIndex,
                            scaleFactor = adaptiveScale,
                            trimStartFrame = trimStartIdx,
                            trimEndFrame = trimEndIdx
                        )
                    }
                    
                    // ── EtaCalibrator (persistent learning MFSR ETA) ──
                    val etaCalibrator = EtaCalibrator(this@MainActivity)
                    var mfsrPhaseStartMs = 0L
                    var mfsrPrevPhase: MfsrPhase? = null
                    val mfsrPhaseWallTimes = mutableMapOf<MfsrPhase, Long>()
                    var mfsrScanSize = 0
                    var mfsrFramesToProcess = 0

                    // Snapshot device state at start of job
                    val isPluggedIn = isDevicePluggedIn(this@MainActivity)
                    val isStabOn = model.isStabilized.value == true
                    val isSavingToSd = outputFile.absolutePath.contains("ext_sd", ignoreCase = true) ||
                                       outputFile.absolutePath.contains("sdcard", ignoreCase = true)
                    val isInputRaw = if (input is com.dan.timelapse.framesinput.ImagesFramesInput) {
                        val firstName = input.name.lowercase()
                        firstName.endsWith(".dng") || firstName.endsWith(".raw") || firstName.endsWith(".cr2") ||
                        firstName.endsWith(".nef") || firstName.endsWith(".arw") || firstName.endsWith(".orf")
                    } else false
                    val sourceCodec = if (input is com.dan.timelapse.framesinput.VideoFramesInput) {
                        val vUri = input.videoUri
                        if (vUri != null) {
                            try {
                                val retriever = android.media.MediaMetadataRetriever()
                                retriever.setDataSource(this@MainActivity, vUri)
                                val mime = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: ""
                                retriever.release()
                                when {
                                    mime.contains("hevc", ignoreCase = true) || mime.contains("h265", ignoreCase = true) -> "hevc"
                                    mime.contains("h264", ignoreCase = true) || mime.contains("avc", ignoreCase = true) -> "h264"
                                    mime.contains("vp9", ignoreCase = true) -> "vp9"
                                    mime.contains("av1", ignoreCase = true) -> "av1"
                                    else -> "video"
                                }
                            } catch (e: Exception) { "video" }
                        } else "none"
                    } else "none"

                    // Output resolution for MFSR = input * scaleFactor
                    val mfsrOutputW = (input.width * adaptiveScale).toInt()
                    val mfsrOutputH = (input.height * adaptiveScale).toInt()

                    // Build unique keys for each phase using PhaseProfileKey
                    val scanKey = PhaseProfileKey("mfsr_scan", isStabOn, isPluggedIn, false, isInputRaw, sourceCodec).build()
                    val matricesKey = PhaseProfileKey("mfsr_matrices", isStabOn, isPluggedIn, false, isInputRaw, sourceCodec).build()
                    val accumulateKey = PhaseProfileKey("mfsr_accumulate", isStabOn, isPluggedIn, false, isInputRaw, sourceCodec).build()
                    val finalizeKey = PhaseProfileKey("mfsr_finalize", isStabOn, isPluggedIn, isSavingToSd, isInputRaw, sourceCodec).build()

                    // Predict phase durations from past learning (or -1 if first run)
                    // Scan/Matrices/Accumulate use Input Resolution; Finalize uses Output Resolution
                    val scanPredicted = etaCalibrator.predictPhaseDurationMs(
                        scanKey, input.width, input.height, input.size
                    )
                    val matricesPredicted = etaCalibrator.predictPhaseDurationMs(
                        matricesKey, input.width, input.height, input.size
                    )
                    val accumulatePredicted = etaCalibrator.predictPhaseDurationMs(
                        accumulateKey, input.width, input.height, input.size
                    )
                    val finalizePredicted = etaCalibrator.predictPhaseDurationMs(
                        finalizeKey, mfsrOutputW, mfsrOutputH, 1
                    )

                    mfsrProcessor.onProgress = { current, total, status ->
                        if (currentRenderJobId == currentJobId) {
                            model.updateProcessingProgress(current, total)
                            if (status.isNotEmpty()) model.updateProcessingStatusText(status)

                            val now = System.currentTimeMillis()
                            val phase = when {
                                status.contains("Extracting")  -> MfsrPhase.EXTRACT
                                status.contains("Scanning")    -> MfsrPhase.SCAN
                                status.contains("Calculating") -> MfsrPhase.MATRICES
                                status.contains("Rendering")   -> MfsrPhase.ACCUMULATE
                                status.contains("Generating") || status.contains("Saving") -> MfsrPhase.FINALIZE
                                status.contains("Complete")    -> MfsrPhase.COMPLETE
                                else -> MfsrPhase.SCAN
                            }

                            val fraction = try {
                                Regex("(\\d+)/(\\d+)").find(status)
                            } catch (_: Exception) { null }

                            if (phase == MfsrPhase.SCAN && fraction != null)
                                mfsrScanSize = fraction.groupValues[2].toInt()
                            if ((phase == MfsrPhase.MATRICES || phase == MfsrPhase.ACCUMULATE) && fraction != null)
                                mfsrFramesToProcess = fraction.groupValues[2].toInt()

                            val prev = mfsrPrevPhase
                            if (prev == null) {
                                mfsrPhaseStartMs = now
                            } else if (phase != prev) {
                                val dur = now - mfsrPhaseStartMs
                                mfsrPhaseWallTimes[prev] = dur
                                mfsrPhaseStartMs = now

                                // Record phase completion in calibrator
                                // Use the right key and resolution per phase:
                                // Scan/Matrices/Accumulate → input resolution
                                // Finalize → output resolution (upscaled)
                                val phaseKey: String
                                val phaseW: Int
                                val phaseH: Int
                                val phaseFrameCount: Int
                                when (prev) {
                                    MfsrPhase.SCAN -> {
                                        phaseKey = scanKey
                                        phaseW = input.width
                                        phaseH = input.height
                                        phaseFrameCount = mfsrScanSize.coerceAtLeast(1)
                                    }
                                    MfsrPhase.MATRICES -> {
                                        phaseKey = matricesKey
                                        phaseW = input.width
                                        phaseH = input.height
                                        phaseFrameCount = mfsrFramesToProcess.coerceAtLeast(1)
                                    }
                                    MfsrPhase.ACCUMULATE -> {
                                        phaseKey = accumulateKey
                                        phaseW = input.width
                                        phaseH = input.height
                                        phaseFrameCount = mfsrFramesToProcess.coerceAtLeast(1)
                                    }
                                    MfsrPhase.FINALIZE -> {
                                        phaseKey = finalizeKey
                                        phaseW = mfsrOutputW
                                        phaseH = mfsrOutputH
                                        phaseFrameCount = 1
                                    }
                                    else -> {
                                        phaseKey = ""
                                        phaseW = 0
                                        phaseH = 0
                                        phaseFrameCount = 0
                                    }
                                }
                                if (phaseKey.isNotEmpty()) {
                                    etaCalibrator.recordPhaseCompletion(
                                        phaseKey, dur, phaseW, phaseH, phaseFrameCount
                                    )
                                }
                            }
                            mfsrPrevPhase = phase

                            // Compute remaining ETA using calibrator predictions
                            var etaMs = -1L
                            val a = fraction?.groupValues?.get(1)?.toIntOrNull() ?: 0
                            val b = fraction?.groupValues?.get(2)?.toIntOrNull() ?: 0
                            when (phase) {
                                MfsrPhase.SCAN -> {
                                    if (b > 0 && a > 0) {
                                        etaMs = (b - a).toLong() * (now - mfsrPhaseStartMs) / a
                                    }
                                    if (b > 0 && matricesPredicted > 0) {
                                        val pc = (mfsrScanSize.toDouble() / scanPredicted.coerceAtLeast(1L).toDouble())
                                            .coerceIn(0.5, 2.0)
                                        etaMs += (matricesPredicted * pc).toLong()
                                        etaMs += (accumulatePredicted * pc).toLong()
                                        etaMs += finalizePredicted.coerceAtLeast(0)
                                    } else { etaMs = -1L }
                                }
                                MfsrPhase.MATRICES -> {
                                    if (b > 0 && a > 0) {
                                        etaMs = (b - a).toLong() * (now - mfsrPhaseStartMs) / a
                                    }
                                    if (accumulatePredicted > 0) {
                                        etaMs += accumulatePredicted
                                        etaMs += finalizePredicted.coerceAtLeast(0)
                                    } else { etaMs = -1L }
                                }
                                MfsrPhase.ACCUMULATE -> {
                                    if (b > 0 && a > 0) {
                                        etaMs = (b - a).toLong() * (now - mfsrPhaseStartMs) / a
                                    }
                                    etaMs += if (finalizePredicted > 0) finalizePredicted else 3000L
                                }
                                MfsrPhase.FINALIZE -> {
                                    etaMs += if (finalizePredicted > 0)
                                        (finalizePredicted * 2.0 / 3.0).toLong().coerceAtLeast(1000)
                                    else 3000L
                                }
                                MfsrPhase.COMPLETE -> etaMs = 0L
                                else -> etaMs = -1L
                            }
                            model.updateEstimatedTimeRemaining(etaMs)
                        } else {
                            model.updateProcessingProgress(current, total)
                            if (status.isNotEmpty()) model.updateProcessingStatusText(status)
                            model.updateEstimatedTimeRemaining(-1L)
                        }
                    }
                    mfsrProcessor.isCancelled = model.isProcessingCancelled.value == true
                    
                    // Wake lock to prevent CPU sleep during long render
                    val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                    val wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "TimeLapse::MFSRRender")
                    wakeLock.acquire(30 * 60 * 1000L)
                    val success = try {
                        mfsrProcessor.process(outputFile)
                    } finally {
                        if (wakeLock.isHeld) wakeLock.release()
                    }

                    // Log learned timings (EtaCalibrator persists via SharedPreferences automatically)
                    if (success && mfsrPhaseWallTimes.isNotEmpty()) {
                        Log.i("MFSR_ETA", "Learned timings for ${input.width}x${input.height} " +
                            "(stab=$isStabOn, pwr=$isPluggedIn, sd=$isSavingToSd, raw=$isInputRaw): " +
                            mfsrPhaseWallTimes.entries.joinToString(" ") { "${it.key.name.lowercase()}=${it.value}ms" })
                    }

                    withContext(Dispatchers.Main) {
                        dismissGenerateDialog()
                        model.isProcessing.postValue(false)
                        model.isPlaying.postValue(false)

                        // Reinitialize the video player after MFSR render (torn down by safeTeardown)
                        // so it responds to play/pause when user returns to Source tab.
                        if (input is VideoFramesInput) {
                            val videoUri = input.videoUri
                            if (videoUri != null) {
                                try {
                                    binding.videoView.setVideoURI(videoUri)
                                    binding.videoView.setOnPreparedListener { mp ->
                                        mp.isLooping = true
                                        activeMediaPlayer = mp
                                        val trimStartMs = model.getTrimStartMs()
                                        mp.seekTo(trimStartMs.toInt())
                                        mp.pause()
                                        binding.videoView.setOnPreparedListener(null)
                                    }
                                } catch (e: Exception) {
                                    Log.e("TimeLapse", "Failed to reinit player after MFSR", e)
                                }
                            }
                        }
                        
                        if (success && outputFile.exists()) {
                            try {
                                val toneGen = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 100)
                                toneGen.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 200)
                                toneGen.release()
                            } catch (e: Exception) {}
                            
                            model.setProcessedImageUri(Uri.fromFile(outputFile))
                            model.setLastProcessedParams(paramsToSave)
                            
                            // MFSR: Always auto-save. The image is the whole point — don't lose it.
                            if (isMFSR || model.autoSaveAfterRender.value == true) {
                                val fileName = model.getFormattedExportName("jpg")
                                model.saveFileToGalleryAsImage(outputFile, fileName)
                            }
                            
                        } else if (model.isProcessingCancelled.value != true) {
                            showToast("Processing Failed")
                        }
                    }
                } else {
                try {
                    val success = processor.startRendering(outputFile.absolutePath)
                    // THE FIX: Read cancellation from model (live check)
                    val wasCancelled = model.isProcessingCancelled.value == true
                    
                    withContext(Dispatchers.Main) {
                        dismissGenerateDialog()
                        model.isProcessing.postValue(false)

                        // THE FIX: Handle partial renders (cancelled but had frames)
                        if (wasCancelled && success && outputFile.exists() && outputFile.length() > 0) {
                            // Keep partial in cache for UI playback only. No toast, no device save.
                            // Delete on next Generate click.
                            model.setPendingPartialRender(outputFile.absolutePath)
                        }

                        if (!wasCancelled && success && outputFile.exists() && outputFile.length() > 0) {
                            // THE FIX: Play a safe, guaranteed system notification chime
                            try {
                                val toneGen = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 100)
                                toneGen.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 200)
                                toneGen.release()
                            } catch (e: Exception) {
                                android.util.Log.e("TimeLapse", "Failed to play completion chime", e)
                            }
                            
                            val retriever = android.media.MediaMetadataRetriever()
                            try {
                                retriever.setDataSource(outputFile.absolutePath)
                                val frameCountStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                                model.lastGeneratedFrameCount = frameCountStr?.toIntOrNull() ?: 0
                            } catch (e: Exception) {
                                Log.e("TimeLapse", "Failed to get frame count", e)
                                model.lastGeneratedFrameCount = 0
                            } finally {
                                retriever.release()
                            }
                            
                            // notifyGallery removed — saveVideoToGallery() below handles gallery visibility
                            model.setProcessedVideo(Uri.fromFile(outputFile))
                            
                            if (model.preserveAudio.value == true) {
                                val input = model.framesInput.value
                                if (input is com.dan.timelapse.framesinput.VideoFramesInput) {
                                    val sourceUri = input.videoUri
                                    if (sourceUri != null) {
                                        // showToast("Syncing audio to time-lapse...")
                                        val speedMultiplier = model.speedMultiplier.value ?: 1
                            val targetFps = model.outputFps.value ?: model.previewFps.value ?: 30
                                        val sourceFps = input.fps.coerceAtLeast(1)
                                        val speedFactor = speedMultiplier.toFloat() * targetFps.toFloat() / sourceFps.toFloat()
                                        
                                        // FIX Bug E: Pass the ACTUAL trim boundary of the original video to the extractor
                                        // The generated duration is artificially short due to speedup; we need full original range
                                        val trimStartMs = model.getTrimStartMs()
                                        val effectiveTrimEndMs = model.getTrimEndMs()
                                        
                                        val muxSuccess = com.dan.timelapse.video.VideoTools.muxTimeScaledAudio(
                                            this@MainActivity,
                                            sourceUri,
                                            outputFile,
                                            speedFactor,
                                            trimStartMs,
                                            effectiveTrimEndMs
                                        )
                                        if (muxSuccess) {
                                            model.setProcessedVideo(android.net.Uri.fromFile(outputFile))
                                            // showToast("Audio synced successfully")
                                        } else {
                                            // showToast("No audio track found, keeping silent video")
                                        }
                                    }
                                }
                            }
                            
                            model.setLastProcessedParams(paramsToSave)

                            if (model.autoSaveAfterRender.value == true) {
                                val fileName = model.getFormattedExportName("mp4")
                                model.saveVideoToGallery(fileName)
                            }
                        } else if (!wasCancelled) {
                            showToast("Processing Failed")
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    withContext(Dispatchers.Main) {
                        dismissGenerateDialog()
                        model.isProcessing.postValue(false)
                    }
                    throw e
                }
                }
            }
        }
        }

        model.triggerFullResRender.observe(this) { trigger ->
            if (trigger != null) {
                model.clearTriggerFullResRender()
                
                val input = model.framesInput.value
                if (input == null) {
                    showToast("No media loaded")
                    return@observe
                }

                val selectedFrameMs = model.selectedFramePositionMs.value
                var targetFrameIndex = (input.size / 2).coerceIn(0, input.size - 1)

                if (selectedFrameMs != null) {
                    val hasRenderedVideo = model.processedVideoUri.value != null
                    
                    if (hasRenderedVideo) {
                        val outFps = model.outputFps.value ?: 30
                        val renderedFrameIndex = ((selectedFrameMs / 1000.0) * outFps).toInt()
                        
                        val skipFrames = model.skipFrames.value ?: 1
                        val originalStartFrame = if (input is ImagesFramesInput) {
                            (model.trimRange.value?.first?.toInt()?.coerceAtLeast(1) ?: 1) - 1
                        } else {
                            val trimStartMs = model.getTrimStartMs()
                            ((trimStartMs / 1000.0) * input.fps.coerceAtLeast(1)).toInt().coerceAtLeast(0)
                        }
                        
                        targetFrameIndex = (originalStartFrame + (renderedFrameIndex * skipFrames)).coerceIn(0, input.size - 1)
                    } else {
                        targetFrameIndex = selectedFrameMs.toInt().coerceIn(0, input.size - 1)
                    }
                }

                // 1. Setup the Dialog UI
                val dialogView = layoutInflater.inflate(R.layout.dialog_generate, null)
                dialogView.findViewById<android.widget.TextView>(R.id.dialogTitle)?.text = "Rendering Full-Res Image"
                dialogView.findViewById<android.view.View>(R.id.btnCancel)?.visibility = android.view.View.GONE
                
                val progressText = dialogView.findViewById<android.widget.TextView>(R.id.progressText)
                progressText?.text = "Processing..."
                
                // Convert to a determinate progress bar
                val progressBar = dialogView.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.progressBar)
                progressBar?.isIndeterminate = false
                progressBar?.max = 100
                progressBar?.progress = 0
                
                val fullResDialog = androidx.appcompat.app.AlertDialog.Builder(this@MainActivity, R.style.Theme_TimeLapse_Dialog)
                    .setView(dialogView)
                    .setCancelable(false)
                    .create()
                fullResDialog.show()
                
                // 2. Attach Live Progress Observer
                val progressObserver = androidx.lifecycle.Observer<Int> { progress ->
                    progressBar?.progress = progress
                    progressText?.text = "Processing... $progress%"
                }
                model.renderProgress.observe(this@MainActivity, progressObserver)
                
                // 3. Bulletproof Coroutine
                lifecycleScope.launch(Dispatchers.IO) {
                    var success = false
                    val tempImageFile = File(cacheDir, "full_res_render_${System.currentTimeMillis()}.jpg")
                    
                    try {
                        val videoUri = input.videoUri
                        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                        val memInfo = android.app.ActivityManager.MemoryInfo()
                        activityManager.getMemoryInfo(memInfo)
                        val ramGB = memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
                        val adaptiveScale = when {
                            ramGB >= 8.0 -> 4.0f
                            ramGB >= 6.0 -> 3.0f
                            else -> 2.0f
                        }
                        
                        // Pass trim bounds (0-based frame indices from ViewModel)
                        val trimStartIdx = (model.trimStart.value ?: 0).coerceAtLeast(0)
                        val trimEndIdx = (model.trimEnd.value ?: input.size - 1).coerceAtMost(input.size - 1)

                        val currentEffect = model.visualEffect.value ?: com.dan.timelapse.Effect.NONE
                        
                        if (currentEffect == com.dan.timelapse.Effect.SUPER_RESOLUTION) {
                            // PATH A: MFSR pipeline
                            val mfsrProcessor = if (input is VideoFramesInput && videoUri != null) {
                                com.dan.timelapse.mfsr.MFSRProcessor.forVideo(
                                    context = this@MainActivity,
                                    videoUri = videoUri,
                                    anchorFrameIndex = targetFrameIndex,
                                    scaleFactor = adaptiveScale,
                                    trimStartFrame = trimStartIdx,
                                    trimEndFrame = trimEndIdx
                                )
                            } else {
                                com.dan.timelapse.mfsr.MFSRProcessor.forImages(
                                    context = this@MainActivity,
                                    framesInput = input,
                                    anchorFrameIndex = targetFrameIndex,
                                    scaleFactor = adaptiveScale,
                                    trimStartFrame = trimStartIdx,
                                    trimEndFrame = trimEndIdx
                                )
                            }
                            mfsrProcessor.onProgress = { current, total, status ->
                                runOnUiThread {
                                    val percent = if (total > 0) (current * 100 / total) else 0
                                    progressBar?.progress = percent
                                    progressText?.text = status
                                    model.updateRenderProgress(percent)
                                }
                            }
                            
                            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                            val wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "TimeLapse::FullResRender")
                            wakeLock.acquire(30 * 60 * 1000L)
                            try {
                                success = mfsrProcessor.process(tempImageFile)
                            } finally {
                                if (wakeLock.isHeld) wakeLock.release()
                            }
                        } else {
                            // PATH B: Use VideoProcessor to render selected effect at high resolution
                            val processor = com.dan.timelapse.VideoProcessor(model, this@MainActivity, input.width, input.height)
                            processor.onProgress = { current, total ->
                                val percent = if (total > 0) (current * 100 / total) else 0
                                runOnUiThread {
                                    progressBar?.progress = percent
                                    progressText?.text = "Processing... $percent%"
                                    model.updateRenderProgress(percent)
                                }
                            }
                            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                            val wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "TimeLapse::FullResRender")
                            wakeLock.acquire(30 * 60 * 1000L)
                            try {
                                success = processor.renderHighResSnapshot(targetFrameIndex, tempImageFile)
                            } finally {
                                if (wakeLock.isHeld) wakeLock.release()
                            }
                        }
                        
                    } catch (e: OutOfMemoryError) {
                        Log.e("TimeLapse", "OOM during full-res render", e)
                        withContext(Dispatchers.Main) {
                            showToast("Out of Memory: Image too large for this effect. Try standard resolution.")
                        }
                    } catch (e: Exception) {
                        Log.e("TimeLapse", "Error during full-res render", e)
                        withContext(Dispatchers.Main) {
                            showToast("Error processing high-res image.")
                        }
                    } finally {
                        withContext(Dispatchers.Main) {
                            model.renderProgress.removeObserver(progressObserver)
                            fullResDialog.dismiss()
                            
                            if (success && tempImageFile.exists()) {
                                model.setProcessedImageUri(Uri.fromFile(tempImageFile))
                                
                                if (model.pendingFullResAction == "SAVE") {
                                    if (model.autoSaveAfterRender.value == true) {
                                        val rawName = model.getFormattedExportName("jpg").replace(".jpg", "_RAW.jpg")
                                        model.saveFileToGalleryAsImage(tempImageFile, rawName)
                                        
                                        model.experimentalRefinedUri?.path?.let { refinedPath ->
                                            val refinedFile = java.io.File(refinedPath)
                                            if (refinedFile.exists()) {
                                                val refinedName = rawName.replace("_RAW", "_REFINED")
                                                model.saveFileToGalleryAsImage(refinedFile, refinedName)
                                                model.experimentalRefinedUri = null 
                                            }
                                        }
                                        showToast("A/B Test Images saved to Pictures/TimeLapse")
                                    } else {
                                        showToast("Render complete")
                                    }
                                } else if (model.pendingFullResAction == "SHARE") {
                                    shareFileUri(tempImageFile)
                                } else {
                                    showToast("Full Res Render Complete")
                                }
                            } else if (!success) {
                                if (tempImageFile.exists() == false) showToast("Render cancelled or failed.")
                            }
                            model.pendingFullResAction = null
                        }
                    }
                }
            }
        }

        model.isProcessing.observe(this) { isProcessing ->
            binding.loadingOverlay.isVisible = isProcessing && generateDialog == null
        }
        
        model.isMediaLoading.observe(this) { isLoading ->
            binding.loadingSpinner.visibility = if (isLoading) View.VISIBLE else View.GONE
        }
        
        model.processedVideoUri.observe(this) { uri ->
            if (uri != null) {
                val currentTab = binding.bottomNavigation.selectedItemId
                val shouldShowRendered = currentTab == R.id.nav_studio || currentTab == R.id.nav_finish
                if (!shouldShowRendered) {
                    return@observe
                }
                // Stop watchdog to avoid pinging a dead player during reinit
                trimWatchdogHandler.removeCallbacks(trimWatchdogRunnable)
                Log.d("TimeLapse", "Playing processed video: $uri")
                // Do NOT reset zoom — we want zoom/pan to persist across tabs
                isPlayingOriginalVideo = false
                // Rotation/crop are baked into the new pixels; isPlayingOriginalVideo=false
                // prevents double-application in applyVideoRotation / applyVideoAspectRatio.
                applyVideoRotation(model.rotation.value ?: 0)
                binding.videoContainer.isEnabled = true
                binding.videoView.isVisible = true
                binding.mainVideoView.isVisible = false
                applyVideoAspectRatio(model.transformState.value?.getAspectRatio())
                
                // Restore zoom/pan state so user sees the same view across tab switches
                val xform = model.previewTransform.value
                if (xform != null && xform.scale > 1.0f) {
                    binding.videoContainer.restoreZoom(xform.scale, xform.translateX, xform.translateY)
                }
                
                try {
                    if (binding.videoView.isPlaying) {
                        binding.videoView.pause()
                    }
                } catch (e: Exception) {}
                
                binding.videoView.setVideoURI(uri)
                binding.videoView.setOnPreparedListener { mp ->
                    mp.isLooping = true
                    binding.videoView.start()
                    activeMediaPlayer = mp
                    mp.isLooping = true
                    if (model.preserveAudio.value != true) {
                        mp.setVolume(0f, 0f)
                    }
                    
                    val actualDuration = mp.duration
                    if (actualDuration > 0) {
                        binding.videoSeekBar.max = actualDuration
                    }
                    videoDuration = actualDuration.toLong()
                    binding.videoSeekBar.progress = 0

                    // G2-09: Adjust playback speed for short renders
                    // <30 frames → 10fps (0.33x at 30fps); 31-60 frames → 30fps (1.0x); >60 → normal
                    val outputFps = model.outputFps.value ?: 30
                    val frameCount = Math.round(actualDuration.toDouble() * outputFps / 1000.0).toInt()
                    val playbackSpeed = when {
                        frameCount < 30 -> 10f / outputFps
                        frameCount < 60 -> 30f / outputFps
                        else -> 1.0f
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val params = mp.playbackParams
                        params.speed = playbackSpeed
                        mp.playbackParams = params
                    }

                    if (!isTrayVisible) {
                        binding.videoSeekBar.visibility = View.VISIBLE
                    }
                    binding.videoContainer.post {
                        if (!isTrayForcedOpen && shouldAutoHideTrayForVideo()) {
                            hideTray()
                        }
                    }

                    // G6-08: A/B comparison via long-press
                    // Long press = show original input, release = show processed
                    binding.videoView.setOnLongClickListener {
                        if (isPlayingOriginalVideo) return@setOnLongClickListener false
                        showOriginalInputForComparison()
                        true
                    }
                    binding.videoView.setOnTouchListener { v, event ->
                        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                            if (!isPlayingOriginalVideo && model.processedVideoUri.value != null) {
                                restoreProcessedVideoForComparison()
                            }
                        }
                        false
                    }
                }
            }
        }
        
        model.processedImageUri.observe(this) { uri ->
            if (uri != null) {
                Log.d("TimeLapse", "Displaying processed image: $uri")
                binding.videoContainer.isEnabled = true
                binding.videoView.isVisible = false
                binding.mainVideoView.isVisible = true
                isPlayingOriginalVideo = false
                
                // Use safeTeardown to marshal MediaPlayer operations to Main Looper
                binding.videoView.safeTeardown()
                
                // Restore zoom/pan state so user sees the same view across tab switches
                val xform = model.previewTransform.value
                if (xform != null && xform.scale > 1.0f) {
                    binding.videoContainer.restoreZoom(xform.scale, xform.translateX, xform.translateY)
                }
                
                lifecycleScope.launch {
                    try {
                        val rawPath = uri.path ?: run {
                            Log.e("TimeLapse", "Processed image URI path is null!")
                            withContext(Dispatchers.Main) {
                                showToast("Error: Could not load rendered image")
                            }
                            return@launch
                        }
                        
                        // Prefer the downscaled _preview.jpg if MFSRProcessor saved one.
                        // Full-res MFSR output can be 4320x7680+ which exceeds GL texture limits
                        // and causes black screens / crashes on many devices.
                        val rawFile = java.io.File(rawPath)
                        val previewFile = java.io.File(
                            rawFile.parent,
                            "${rawFile.nameWithoutExtension}_preview.jpg"
                        )
                        val displayPath = if (previewFile.exists()) {
                            Log.d("TimeLapse", "Using preview: ${previewFile.absolutePath}")
                            previewFile.absolutePath
                        } else {
                            rawPath
                        }
                        
                        val bmp = ImageLoaderUtils.loadDownsampledBitmapAsync(displayPath, maxDimension = 2048)
                        
                        withContext(Dispatchers.Main) {
                            if (bmp != null) {
                                binding.mainVideoView.setImageBitmap(bmp)
                                binding.mainVideoView.invalidate()
                                // Only show toast AFTER image is visible
                                showTabStatusToast("Rendered Image")
                                binding.videoContainer.post {
                                    if (!isTrayForcedOpen && shouldAutoHideTrayForVideo()) hideTray()
                                }
                            } else {
                                Log.e("TimeLapse", "Failed to load preview bitmap at $displayPath")
                                showToast("Rendering complete but image failed to load. Check gallery.")
                            }
                        }
                    } catch(e: Exception) {
                        Log.e("TimeLapse", "Failed to load rendered image", e)
                        withContext(Dispatchers.Main) {
                            showToast("Error displaying image: ${e.message}")
                        }
                    }
                }
                
                binding.videoSeekBar.visibility = View.GONE
            }
        }
        
        model.processingProgress.observe(this) { progress ->
            generateDialog?.let { dialog ->
                val current = progress.first
                val total = progress.second
                val progressBar = dialog.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.progressBar)
                if (total > 0) {
                    val percent = if (current >= total) 100 else (current * 100 / total)
                    progressBar?.progress = percent
                }
            }
        }
        
        model.processingStatusText.observe(this) { statusText ->
            generateDialog?.let { dialog ->
                val progressText = dialog.findViewById<TextView>(R.id.progressText)
                if (statusText.isNotEmpty()) {
                    progressText?.text = statusText
                }
            }
        }
        
        model.estimatedTimeRemaining.observe(this) { timeEstimate ->
            generateDialog?.let { dialog ->
                val dialogTitle = dialog.findViewById<TextView>(R.id.dialogTitle)
                val isStabilizing = model.isStabilized.value == true
                val prefix = if (isStabilizing) "Stabilizing & Rendering" else "Rendering"
                if (timeEstimate != null && timeEstimate.isNotEmpty()) {
                    dialogTitle?.text = "$prefix ($timeEstimate)"
                } else {
                    dialogTitle?.text = prefix
                }
            }
        }
        
        binding.emptyStateOverlay.visibility = if (model.framesInput.value == null) View.VISIBLE else View.GONE
        
        binding.emptyStateOverlay.setOnClickListener {
            model.setLastSourceType(MainViewModel.SourceType.VIDEO)
            openPicker()
        }
        
        binding.videoContainer.setOnClickListener {
            if (model.framesInput.value == null) {
                model.setLastSourceType(MainViewModel.SourceType.VIDEO)
                openPicker()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (model.framesInput.value != null) {
            model.saveSession(this)
        }
        trimWatchdogHandler.removeCallbacks(trimWatchdogRunnable)
    }

    override fun onStop() {
        super.onStop()
        stopPreviewLoop()
        try {
            if (binding.videoView.isPlaying) {
                binding.videoView.pause()
            }
        } catch (e: Exception) {
            Log.e("TimeLapse", "Error suspending VideoView", e)
        }
    }
    
    override fun onResume() {
        super.onResume()
        if (model.framesInput.value != null && !isFrameSelectionMode && !isMaskEditMode && !model.isUserPaused) {
            startPreviewLoop(10)
        }
        trimWatchdogHandler.post(trimWatchdogRunnable)
    }

    private fun initGlobalGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                return false
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // Don't handle taps in mask edit mode
                if (isMaskEditMode || isFrameSelectionMode) return false
                
                // Check if tap is inside visible tray - if so, don't dismiss
                if (isTrayVisible) {
                    val trayLocation = IntArray(2)
                    binding.trayContainer.getLocationOnScreen(trayLocation)
                    if (e.rawY >= trayLocation[1]) {
                        return false  // Let tray handle its own taps
                    }
                }
                
                val videoContainerRect = Rect()
                binding.videoContainer.getGlobalVisibleRect(videoContainerRect)
                val navRect = Rect()
                binding.bottomNavigation.getGlobalVisibleRect(navRect)
                
                if (videoContainerRect.contains(e.rawX.toInt(), e.rawY.toInt()) && 
                    !navRect.contains(e.rawX.toInt(), e.rawY.toInt())) {
                    
                    if (model.framesInput.value == null) {
                        showSourceTray()
                        return true
                    }
                    
                    // THE FIX: If tray is open, clicking video ONLY hides the tray (does not pause/play)
                    if (isTrayVisible) {
                        hideTray()
                    } else {
                        togglePlayback()
                    }
                    return true
                }
                return false
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vX: Float, vY: Float): Boolean {
                if (model.isProcessing.value == true || isMaskEditMode) return false
                
                if (Math.abs(vY) > Math.abs(vX) * 2.0f && Math.abs(vY) > 250) {
                    if (vY > 0) { 
                        isTrayForcedOpen = false
                        hideTray()
                    } else { 
                        isTrayForcedOpen = true
                        showTray()
                        reloadCurrentTrayContent()
                        binding.trayContainer.bringToFront()
                    }
                    return true
                }
                return false
            }
        })
    }
    
    private fun showTray() {
        if (model.isProcessing.value == true) return
        if (isTrayVisible) return
        isTrayVisible = true
        isTrayForcedOpen = true
        binding.trayContainer.translationY = 0f
        binding.trayContainer.visibility = View.VISIBLE
        binding.dimmerView.visibility = View.VISIBLE
        binding.dimmerView.alpha = 0.1f
        binding.videoSeekBar.visibility = View.GONE
        binding.videoSeekBar.isEnabled = false
        updateDimmer()
    }
    
    private fun reloadCurrentTrayContent() {
        val currentTabId = binding.bottomNavigation.selectedItemId
        val targetFragmentClass = when (currentTabId) {
            R.id.nav_source -> SourceFragment::class.java
            R.id.nav_transform -> TransformFragment::class.java
            R.id.nav_studio -> StudioFragment::class.java
            R.id.nav_finish -> FinishFragment::class.java
            else -> return
        }
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(0, 0)
            .replace(R.id.tray_content_holder, targetFragmentClass.getDeclaredConstructor().newInstance())
            .commitNow()
    }
    
    private fun hideTray() {
        if (isMaskEditMode) return
        if (!isTrayVisible) return
        isTrayVisible = false
        isTrayForcedOpen = false
        binding.trayContainer.translationY = 2000f
        binding.trayContainer.visibility = View.GONE
        binding.dimmerView.visibility = View.GONE
        if (model.framesInput.value != null && binding.videoView.isVisible) {
            binding.videoSeekBar.visibility = View.VISIBLE
            binding.videoSeekBar.isEnabled = true
        }
        
        val selectedOutputFps = model.outputFps.value ?: 30
        model.setPreviewFps(selectedOutputFps)
        
        val input = model.framesInput.value
        if (input is ImagesFramesInput && isPlayingOriginalVideo) {
            if (playbackJob?.isActive == true) {
                startPreviewLoop(Int.MAX_VALUE)
            }
        }
    }
    
    private fun updateDimmer() {
        val zoomScale = model.zoomFactor.value ?: 1f
        val isZoomed = zoomScale != 1.0f
        
        if (isTrayVisible && !isZoomed) {
            val videoRect = Rect()
            val trayRect = Rect()
            binding.videoContainer.getGlobalVisibleRect(videoRect)
            binding.trayContainer.getGlobalVisibleRect(trayRect)
            val isBehind = videoRect.top < trayRect.bottom
            if (isBehind) {
                binding.dimmerView.visibility = View.VISIBLE
            } else {
                binding.dimmerView.visibility = View.GONE
            }
        } else {
            binding.dimmerView.visibility = View.GONE
        }
    }

    private fun setupVideoSeekBar() {
        binding.videoSeekBar.setOnClickListener { }
        binding.videoSeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val input = model.framesInput.value
                    // FIX 2: Add 50ms throttle to prevent UI stutter during fast scrubbing
                    if (input is ImagesFramesInput && isPlayingOriginalVideo) {
                        if (System.currentTimeMillis() - lastScrubTime > 50) {
                            lastScrubTime = System.currentTimeMillis()
                            val safeFrame = progress.coerceIn(0, input.size - 1)
                            displayImageFrame(safeFrame)
                        }
                        return
                    }
                    
                    val targetMs: Int
                    if (isPlayingOriginalVideo) {
                        val rawTrimStart = model.getTrimStartMs().toInt()
                        val rawTrimEnd = model.getTrimEndMs().toInt()
                        val safeTrimStart = if (rawTrimEnd == 0) 0 else rawTrimStart
                        val duration = try { binding.videoView.duration } catch(e: Exception) { Int.MAX_VALUE }
                        targetMs = (progress + safeTrimStart).coerceAtMost(duration)
                    } else {
                        val duration = try { binding.videoView.duration } catch(e: Exception) { Int.MAX_VALUE }
                        targetMs = progress.coerceAtMost(duration)
                    }

                    if (targetMs >= 0) {
                        try {
                            if (binding.videoView.isPlaying || binding.videoView.duration > 0) {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && activeMediaPlayer != null) {
                                    activeMediaPlayer?.seekTo(targetMs.toLong(), android.media.MediaPlayer.SEEK_CLOSEST)
                                } else {
                                    binding.videoView.seekTo(targetMs)
                                }
                            }
                        } catch (e: IllegalStateException) {
                            android.util.Log.w("MainActivity", "Ignored seekTo: MediaPlayer is released or stopped.")
                        } catch (e: Exception) {
                            android.util.Log.e("MainActivity", "Seek error", e)
                        }
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {
                isScrubbing = true
                try {
                    if (binding.videoView.isPlaying) {
                        binding.videoView.pause()
                    }
                } catch (e: Exception) { }
            }

            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                isScrubbing = false
                lastScrubTime = System.currentTimeMillis()
                if (model.isPlaying.value == true) {
                    val input = model.framesInput.value
                    if (input is ImagesFramesInput && isPlayingOriginalVideo) {
                        startPreviewLoop(Int.MAX_VALUE)
                    } else {
                        try {
                            binding.videoView.start()
                        } catch (e: Exception) { }
                    }
                }
            }
        })
    }
    
    private fun displayImageFrame(frameIndex: Int) {
        val input = model.framesInput.value as? ImagesFramesInput ?: return
        
        displayImageJob?.cancel()
        displayImageJob = lifecycleScope.launch(Dispatchers.IO) {
            var frame: org.opencv.core.Mat? = null
            try {
                frame = input.getFrame(frameIndex)
                if (frame != null && !frame.empty()) {
                    val bmp = getPooledPreviewBitmap(frame.cols(), frame.rows())
                    Utils.matToBitmap(frame, bmp)
                    withContext(Dispatchers.Main) {
                        binding.mainVideoView.setImageBitmap(bmp)
                    }
                }
            } catch (e: Exception) {
                Log.e("TimeLapse", "Error displaying frame $frameIndex", e)
            } finally {
                // FIX 2: Guarantee native memory cleanup even on coroutine cancellation
                frame?.release()
            }
        }
    }

    private fun setupTrayBehaviors() {
        isTrayVisible = false
        binding.trayContainer.translationY = 2000f
        binding.trayContainer.visibility = View.GONE
        
        binding.trayContainer.setOnClickListener {
            // Consume click events on the tray to prevent gesture detector from processing them
            // This prevents the tray from collapsing when clicked
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Only run heavy visibility checks on ACTION_DOWN, not every move event
        if (ev.action == MotionEvent.ACTION_DOWN) {
            if (isFrameSelectionMode) {
                binding.frameSelectorOverlay.getGlobalVisibleRect(frameSelectorRect)
                if (frameSelectorRect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    return super.dispatchTouchEvent(ev)
                }
            }
            
            binding.bottomNavigation.getGlobalVisibleRect(navRect)
            if (navRect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                return super.dispatchTouchEvent(ev)
            }

            if (isTrayVisible) {
                // Get exact Y coordinate of tray on screen right now
                val location = IntArray(2)
                binding.trayContainer.getLocationOnScreen(location)
                val trayScreenY = location[1]
                
                // If touch is below top edge of tray, it belongs to the tray
                if (ev.rawY >= trayScreenY) {
                    isTrayForcedOpen = true
                    binding.trayContainer.requestFocus()
                    binding.trayContainer.requestLayout()
                    return super.dispatchTouchEvent(ev)
                }
            }
        }
        
        if (ev.action == MotionEvent.ACTION_DOWN) {
            updateScreenLock()
        }
        
        if (gestureDetector.onTouchEvent(ev)) {
            return true
        }

        return super.dispatchTouchEvent(ev)
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    isFrameSelectionMode -> {
                        exitFrameSelectionMode()
                    }
                    isMaskEditMode -> {
                        closeMaskEditorToTransform()
                    }
                    isTrayVisible -> {
                        hideTray()
                    }
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    private fun setupNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            val currentSelectedId = binding.bottomNavigation.selectedItemId
            if (currentSelectedId == item.itemId) {
                if (isTrayVisible) hideTray() else showTray()
                return@setOnItemSelectedListener true
            }

            val targetFragmentClass = when (item.itemId) {
                R.id.nav_source -> SourceFragment::class.java
                R.id.nav_transform -> TransformFragment::class.java
                R.id.nav_studio -> StudioFragment::class.java
                R.id.nav_finish -> FinishFragment::class.java
                else -> null
            } ?: return@setOnItemSelectedListener false

            supportFragmentManager.beginTransaction()
                .setCustomAnimations(0, 0)
                .replace(R.id.tray_content_holder, targetFragmentClass.getDeclaredConstructor().newInstance())
                .commitNow()

            isTrayForcedOpen = true

            val wantsRenderedTab = (item.itemId == R.id.nav_studio || item.itemId == R.id.nav_finish)
            val processedUri = model.processedVideoUri.value
            val processedImageUri = model.processedImageUri.value
            val wantsRenderedVideo = wantsRenderedTab && processedUri != null

            val wasPlaying = try { binding.videoView.isPlaying } catch(e: Exception) { false } || (playbackJob?.isActive == true)
            var progressPct = 0.0

            val input = model.framesInput.value
            val isImageSequence = input is ImagesFramesInput

            if (isPlayingOriginalVideo) {
                if (isImageSequence && input != null) {
                    progressPct = binding.videoSeekBar.progress.toDouble() / Math.max(1, input.size - 1)
                } else {
                    val trimStart = model.getTrimStartMs()
                    val trimEnd = model.getTrimEndMs()
                    val duration = if (trimEnd > trimStart) (trimEnd - trimStart) else 1L
                    val currentRawPos = try { binding.videoView.currentPosition } catch(e: Exception) { 0 }
                    progressPct = ((currentRawPos - trimStart).toDouble() / duration).coerceIn(0.0, 1.0)
                }
            } else {
                val duration = try { binding.videoView.duration } catch(e: Exception) { 1 }
                val currentRawPos = try { binding.videoView.currentPosition } catch(e: Exception) { 0 }
                if (duration > 0) progressPct = (currentRawPos.toDouble() / duration).coerceIn(0.0, 1.0)
            }

            stopPreviewLoop()

            // THE FIX: Check for MFSR processed image first (Tray 4/Finish)
            if (item.itemId == R.id.nav_finish && processedImageUri != null) {
                isPlayingOriginalVideo = false
                showTabStatusToast("Full-Res Image")
                binding.videoView.isVisible = false
                binding.mainVideoView.isVisible = true
                try { if (binding.videoView.isPlaying) binding.videoView.pause() } catch (e: Exception) {}
                model.isPlaying.postValue(false)
                
                processedImageUri.path?.let { rawPath ->
                    lifecycleScope.launch {
                        // Prefer _preview.jpg if available (for MFSR)
                        val rawFile = java.io.File(rawPath)
                        val previewFile = java.io.File(
                            rawFile.parent,
                            "${rawFile.nameWithoutExtension}_preview.jpg"
                        )
                        val displayPath = if (previewFile.exists()) previewFile.absolutePath else rawPath
                        val safeBmp = ImageLoaderUtils.loadDownsampledBitmapAsync(displayPath, maxDimension = 2048)
                        withContext(Dispatchers.Main) {
                            if (safeBmp != null) {
                                (binding.mainVideoView as com.dan.timelapse.widgets.TouchImageView).setImageBitmap(safeBmp)
                            }
                        }
                    }
                }
                
                binding.videoContainer.post {
                    if (!isTrayForcedOpen && shouldAutoHideTrayForVideo()) hideTray()
                }
                showTray()
                return@setOnItemSelectedListener true
            }

            if (wantsRenderedVideo && processedUri != null) {
                isPlayingOriginalVideo = false
                showTabStatusToast("Rendered")
                binding.videoView.isVisible = true
                binding.mainVideoView.isVisible = false

                applyVideoRotation(model.rotation.value ?: 0)
                applyVideoAspectRatio(null)

                binding.videoView.setVideoURI(processedUri)
                binding.videoView.setOnPreparedListener { mp ->
                    mp.isLooping = true
                    binding.videoView.start()
                    activeMediaPlayer = mp
                    if (model.preserveAudio.value != true) mp.setVolume(0f, 0f)
                    val actualDuration = mp.duration
                    if (actualDuration > 0) binding.videoSeekBar.max = actualDuration
                    videoDuration = actualDuration.toLong()
                    val targetPos = (actualDuration * progressPct).toInt()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        mp.seekTo(targetPos.toLong(), MediaPlayer.SEEK_CLOSEST)
                    } else {
                        binding.videoView.seekTo(targetPos)
                    }
                    binding.videoSeekBar.progress = targetPos
                    if (!isTrayVisible) binding.videoSeekBar.visibility = View.VISIBLE
                    if (wasPlaying) {
                        model.isPlaying.postValue(true)
                        mp.start()
                    } else {
                        model.isPlaying.postValue(false)
                        mp.pause()
                    }
                    binding.videoContainer.post {
                        if (!isTrayForcedOpen && shouldAutoHideTrayForVideo()) hideTray()
                    }
                }
                binding.videoView.setOnErrorListener { _, _, _ ->
                    binding.videoView.setOnErrorListener(null)
                    isPlayingOriginalVideo = true
                    binding.videoContainer.isEnabled = true
                    showTabStatusToast("Original")
                    if (input is VideoFramesInput) binding.videoView.setVideoURI(input.videoUri)
                    true
                }
            } else {
                isPlayingOriginalVideo = true
                binding.videoContainer.isEnabled = true
                showTabStatusToast("Original")
                binding.videoView.setOnErrorListener(null)
                if (input is VideoFramesInput) {
                    binding.videoView.isVisible = true
                    binding.mainVideoView.isVisible = false
                    binding.videoContainer.isEnabled = true
                    applyVideoRotation(model.rotation.value ?: 0)
                    applyVideoAspectRatio(model.transformState.value?.getAspectRatio())
                    binding.videoView.setVideoURI(input.videoUri)
binding.videoView.setOnPreparedListener { mp ->
                    mp.isLooping = true
                    activeMediaPlayer = mp
                    mp.isLooping = true
                    if (model.preserveAudio.value != true) mp.setVolume(0f, 0f)
                        val actualDuration = mp.duration
                        if (actualDuration > 0) binding.videoSeekBar.max = actualDuration
                        videoDuration = actualDuration.toLong()
                        val trimStart = model.getTrimStartMs()
                        val trimEnd = model.getTrimEndMs()
                        val duration = if (trimEnd > trimStart) (trimEnd - trimStart) else actualDuration.toLong()
                        val targetPos = (trimStart + (duration * progressPct)).toInt()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            mp.seekTo(targetPos.toLong(), MediaPlayer.SEEK_CLOSEST)
                        } else {
                            binding.videoView.seekTo(targetPos)
                        }
                        binding.videoSeekBar.progress = (targetPos - trimStart.toInt()).coerceAtLeast(0)
                        if (!isTrayVisible) binding.videoSeekBar.visibility = View.VISIBLE
                        if (wasPlaying) {
                            model.isPlaying.postValue(true)
                            mp.start()
                        } else {
                            model.isPlaying.postValue(false)
                            mp.pause()
                        }
                    }
                } else if (input is ImagesFramesInput) {
                    binding.videoView.isVisible = false
                    binding.mainVideoView.isVisible = true
                    binding.videoSeekBar.max = input.size - 1
                    binding.videoSeekBar.visibility = View.VISIBLE
                    val targetFrame = ((input.size - 1) * progressPct).toInt().coerceIn(0, input.size - 1)
                    binding.videoSeekBar.progress = targetFrame
                    displayImageFrame(targetFrame)
                    if (model.isPlaying.value == true) startPreviewLoop(Int.MAX_VALUE)
                }
            }
            // Restart the trim watchdog for original video preview (ensures bounds are respected)
            if (isPlayingOriginalVideo) {
                trimWatchdogHandler.removeCallbacks(trimWatchdogRunnable)
                trimWatchdogHandler.post(trimWatchdogRunnable)
            }
            showTray()
            
            // Restore zoom/pan state on tab switch so user sees the same view across tabs
            val xform = model.previewTransform.value
            if (xform != null && xform.scale > 1.0f) {
                binding.videoContainer.post {
                    binding.videoContainer.restoreZoom(xform.scale, xform.translateX, xform.translateY)
                }
            }
            
            return@setOnItemSelectedListener true
        }
    }

    private fun onFileSelected(uri: Uri) {
        val currentUri = model.framesInput.value?.videoUri
        if (currentUri != null && currentUri.toString() == uri.toString()) {
            return
        }
        
        persistUriPermission(uri)
        model.saveSession(this)

        showLoadingDialog("Loading video...")
        
        loadingJob = lifecycleScope.launch {
            try {
                val input = withContext(Dispatchers.IO) {
                    VideoFramesInput(UriFile.fromSingleUri(this@MainActivity, uri)!!)
                }
                model.setFramesInput(input)
                binding.videoView.isVisible = true
                binding.mainVideoView.isVisible = false
                applyVideoRotation(model.rotation.value ?: 0)
                applyVideoAspectRatio(model.transformState.value?.getAspectRatio())
                
                binding.videoView.setVideoURI(input.videoUri)
                binding.videoView.setOnPreparedListener { mp ->
                    mp.isLooping = true
                    binding.videoView.start()
                    activeMediaPlayer = mp
                    mp.isLooping = true
                    if (model.preserveAudio.value != true) mp.setVolume(0f, 0f)
                    val actualDuration = mp.duration
                    if (actualDuration > 0) {
                        binding.videoSeekBar.max = actualDuration
                    }
                    videoDuration = actualDuration.toLong()
                    binding.videoSeekBar.progress = 0
                    if (!isTrayVisible) {
                        binding.videoSeekBar.visibility = View.VISIBLE
                    }
                    startPreviewLoop(10)
                }
            } catch (e: Exception) {
                showToast("Error loading media: ${e.message}")
            } finally {
                dismissLoadingDialog()
            }
        }
    }

    private fun onImagesSelected(uris: List<Uri>) {
        uris.forEach { persistUriPermission(it) }
        model.saveSession(this)

        showLoadingDialog("Loading ${uris.size} images...")

        loadingJob = lifecycleScope.launch {
            try {
                val input = withContext(Dispatchers.IO) {
                    ImagesFramesInput(uris.map { UriFile.fromSingleUri(this@MainActivity, it)!! }, model)
                }
                model.setFramesInput(input)
                binding.videoView.isVisible = false
                binding.mainVideoView.isVisible = true
                binding.videoSeekBar.max = input.size - 1
                binding.videoSeekBar.visibility = View.VISIBLE
                startPreviewLoop(10)
            } catch (e: Exception) {
                showToast("Error loading media: ${e.message}")
            } finally {
                dismissLoadingDialog()
            }
        }
    }

    private fun onFolderSelected(uri: Uri) {
        persistUriPermission(uri)
        model.setLastSourceType(MainViewModel.SourceType.FOLDER)
        
        showLoadingDialog("Scanning folder...")
        
        loadingJob = lifecycleScope.launch {
            try {
                val input = withContext(Dispatchers.IO) {
                    val folderUriFile = UriFile.fromTreeUri(this@MainActivity, uri) 
                        ?: throw Exception("Could not parse Folder URI")
                    
                    ImagesFramesInput.fromFolder(folderUriFile, model) { current, total, status ->
                        runOnUiThread {
                            updateLoadingDialog(current, total, status)
                        }
                    }
                }
                model.setFramesInput(input)
                binding.videoView.isVisible = false
                binding.mainVideoView.isVisible = true
                binding.videoSeekBar.max = input.size - 1
                binding.videoSeekBar.visibility = View.VISIBLE
                startPreviewLoop(10)
                
                binding.videoContainer.post {
                    if (!isTrayForcedOpen && shouldAutoHideTrayForVideo()) {
                        hideTray()
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Folder load failed", e)
                showToast("Folder Error: ${e.localizedMessage ?: "Unknown"}")
            } finally {
                dismissLoadingDialog()
            }
        }
    }
    
    private fun showLoadingDialog(title: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_generate, null)
        dialogView.findViewById<TextView>(R.id.dialogTitle).text = title
        dialogView.findViewById<TextView>(R.id.progressText).text = "Please wait..."
        
        loadingDialog = AlertDialog.Builder(this, R.style.Theme_TimeLapse_Dialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()
        
        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancel).setOnClickListener {
            loadingJob?.cancel()
            dismissLoadingDialog()
            showToast("Loading cancelled")
        }
        
        loadingDialog?.show()
    }
    
    private fun updateLoadingDialog(current: Int, total: Int, status: String) {
        loadingDialog?.let { dialog ->
            dialog.findViewById<TextView>(R.id.progressText)?.text = status
            if (total > 0) {
                val progressBar = dialog.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.progressBar)
                progressBar?.isIndeterminate = false
                progressBar?.progress = (current * 100 / total)
            }
        }
    }
    
    private fun dismissLoadingDialog() {
        loadingDialog?.dismiss()
        loadingDialog = null
    }

    private fun persistUriPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            Log.e("TimeLapse", "Failed to persist URI permission for $uri", e)
        }
    }

    private fun clearSavedSession() {
        val prefs = getSharedPreferences("TimeLapsePrefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        model.resetProcessingState()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            binding.videoView.isVisible = false
            binding.mainVideoView.isVisible = false
        }
    }

    private fun togglePlayback() {
        if (System.currentTimeMillis() - lastScrubTime < 250L) return
        
        // Allow play/pause on original video even after MFSR render has set processedImageUri
        if (model.processedImageUri.value != null && !isPlayingOriginalVideo) return

        // Guard: reinitialize if player was destroyed by render (safeTeardown nulls mediaPlayer)
        val input = model.framesInput.value
        if (input is com.dan.timelapse.framesinput.VideoFramesInput) {
            val duration = try { binding.videoView.duration } catch (e: Exception) { 0 }
            if (duration == 0) {
                Log.i("TimeLapse", "Player dead — reinitializing...")
                reinitializeVideoPlayer()
                return
            } else if (duration == -1) {
                return
            }
        }
        
        var isPlaying: Boolean
        if (!isPlayingOriginalVideo || input is VideoFramesInput) {
            try {
                if (binding.videoView.isPlaying) {
                    binding.videoView.pause()
                    isPlaying = false
                } else {
                    // Snap to trim start if playhead is outside the selected chunk
                    val trimStart = model.getTrimStartMs()
                    val trimEnd = model.getTrimEndMs()
                    val currentPos = try { binding.videoView.currentPosition.toLong() } catch(e: Exception) { -1L }
                    if (currentPos >= 0 && trimEnd > trimStart && (currentPos < trimStart || currentPos >= (trimEnd - 100))) {
                        binding.videoView.seekTo(trimStart.toInt())
                    }
                    binding.videoView.start()
                    isPlaying = true
                }
                model.isPlaying.postValue(isPlaying)
            } catch (e: IllegalStateException) {
                Log.e("MFSR_UI", "MediaPlayer IllegalStateException, reinitializing...")
                reinitializeVideoPlayer()
                isPlaying = false
                model.isPlaying.postValue(false)
            }
        } else {
            if (playbackJob?.isActive == true) {
                stopPreviewLoop()
                isPlaying = false
                model.isPlaying.postValue(false)
            } else {
                startPreviewLoop(Int.MAX_VALUE)
                isPlaying = true
                model.isPlaying.postValue(true)
            }
        }
        showPlayPauseToast(isPlaying)
    }

    fun applyVideoRotation(degrees: Int) {
        if (!isPlayingOriginalVideo) {
            binding.videoView.setVideoRotation(0)
            (binding.mainVideoView as TouchImageView).setRotationDegrees(0)
        } else {
            binding.videoView.setVideoRotation(degrees)
            (binding.mainVideoView as TouchImageView).setRotationDegrees(degrees)
        }
    }

    fun applyVideoAspectRatio(ratio: Float?) {
        if (!isPlayingOriginalVideo) {
            binding.videoView.setAspectRatio(null)
            (binding.mainVideoView as TouchImageView).setAspectRatio(null)
        } else {
            binding.videoView.setAspectRatio(ratio)
            (binding.mainVideoView as TouchImageView).setAspectRatio(ratio)
        }
    }
    
    fun startPreviewLoop(loopCount: Int) {
        val input = model.framesInput.value ?: return
        
        // Guard: Don't manipulate video if we're showing an image
        if (model.processedImageUri.value != null) return
        
        stopPreviewLoop()
        model.isPlaying.postValue(true)

        if (!isPlayingOriginalVideo) {
            binding.videoView.start()
            return
        }

        if (input is VideoFramesInput) {
            val trimRange = model.trimRange.value ?: Pair(1f, input.size.toFloat())
            val startFrame = (trimRange.first - 1).toInt().coerceIn(0, input.size - 1)
            val startMs = (startFrame * 1000 / input.fps).toLong()
            
            var currentLoop = 0
            binding.videoView.setOnCompletionListener { 
                if (model.processedImageUri.value != null) return@setOnCompletionListener
                if (currentLoop < loopCount - 1) {
                    currentLoop++
                    lifecycleScope.launch {
                        delay(50)
                        if (model.processedImageUri.value == null) {
                            binding.videoView.seekTo(startMs.toInt())
                            try { binding.videoView.start() } catch (e: Exception) { }
                        }
                    }
                } else {
                    model.isPlaying.postValue(false)
                    try { binding.videoView.pause() } catch (e: Exception) { }
                }
            }
            if (model.processedImageUri.value == null) {
                lastWatchdogSeekTime = System.currentTimeMillis()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    activeMediaPlayer?.seekTo(startMs, MediaPlayer.SEEK_CLOSEST)
                } else {
                    binding.videoView.seekTo(startMs.toInt())
                }
                binding.videoView.start()
                trimWatchdogHandler.post(trimWatchdogRunnable)
            }
        } else if (input is ImagesFramesInput) {
            binding.videoSeekBar.max = input.size - 1
            binding.videoSeekBar.visibility = View.VISIBLE
            
            val previewFps = model.previewFps.value ?: 30
            val frameDelayMs = 1000 / previewFps
            
            // THE FIX: Use custom coroutine for 1 FPS playback (more reliable than MediaPlayer speed)
            playbackJob = lifecycleScope.launch(Dispatchers.Default) {
                var loops = 0
                while(isActive && loops < loopCount) {
                    if (model.processedImageUri.value != null) break
                    val trimRange = model.trimRange.value ?: Pair(1f, input.size.toFloat())
                    val startFrame = (trimRange.first - 1).toInt().coerceIn(0, input.size - 1)
                    val endFrame = (trimRange.second - 1).toInt().coerceIn(0, input.size - 1)

                    for (i in startFrame..endFrame) {
                        if (!isActive || model.processedImageUri.value != null) break
                        val frame = input.getFrame(i) ?: continue
                        try {
                            val bmp = getPooledPreviewBitmap(frame.cols(), frame.rows())
                            Utils.matToBitmap(frame, bmp)
                            withContext(Dispatchers.Main) {
                                binding.mainVideoView.setImageBitmap(bmp)
                                binding.videoSeekBar.progress = i
                            }
                        } catch (e: CvException) {
                        } finally {
                            frame.release()
                        }
                        delay(frameDelayMs.toLong())
                    }
                    delay(50)
                    if (loopCount != Int.MAX_VALUE) loops++
                }
                withContext(Dispatchers.Main) {
                    model.isPlaying.postValue(false)
                }
            }
        }
    }

    fun stopPreviewLoop() {
        playbackJob?.cancel()
        trimWatchdogHandler.removeCallbacks(trimWatchdogRunnable)
        // Guard: Don't manipulate video if we're showing an image
        if (model.processedImageUri.value != null) return
        try {
            if(binding.videoView.isPlaying) {
                binding.videoView.pause()
            }
        } catch (e: Exception) { }
        binding.videoView.setOnCompletionListener(null)
    }

    fun seekVideoTo(positionMs: Int) {
        if (model.processedImageUri.value != null) return
        safeSeekTo(positionMs)
    }
    
    fun enterFrameSelectionMode() {
        isFrameSelectionMode = true
        binding.frameSelectorOverlay.isVisible = true
        binding.bottomNavigation.isVisible = false
        hideTray()
        stopPreviewLoop()
        
        // Task 1: Show video player, hide TouchImageView for frame selection
        binding.mainVideoView.isVisible = false
        binding.videoView.isVisible = true
        
        val processedUri = model.processedVideoUri.value
        val input = model.framesInput.value
        
        // Force slider to use original video frame count - protect against any state corruption
        if (input != null) {
            binding.fullScreenFrameSlider.valueFrom = 0f
            val maxValue = (input.size - 1).toFloat().coerceAtLeast(1f)
            binding.fullScreenFrameSlider.valueTo = maxValue
            binding.fullScreenFrameSlider.stepSize = 1.0f  // Force integer frame indices
            
            // Force slider to refresh its internal state
            binding.fullScreenFrameSlider.isEnabled = false
            binding.fullScreenFrameSlider.isEnabled = true
            
            val savedPosition = model.selectedFramePositionMs.value?.toFloat() ?: maxValue
            binding.fullScreenFrameSlider.value = savedPosition.coerceIn(0f, maxValue)
            updateFramePreview(savedPosition.toInt())
            updateFrameInfoText(savedPosition.toLong())
        }
        
        if (processedUri != null) {
            binding.videoView.isVisible = true
            binding.mainVideoView.isVisible = false  // Task 1: Unblock video player
        }
        
        // Task 1: Ensure TouchImageView is hidden when showing video for frame selection
        if (model.processedImageUri.value == null) {
            binding.mainVideoView.isVisible = false
        }
        
        // Only set up slider in video time mode for actual video inputs, not image sequences
        // Image sequences always use frame index mode regardless of processedVideoUri
        if (processedUri != null && input !is com.dan.timelapse.framesinput.ImagesFramesInput) {
            val duration = try { binding.videoView.duration } catch(e: Exception) { 0 }
            if (duration > 0) {
                setupSliderForVideo(duration)
            } else {
                binding.videoView.setOnPreparedListener { mp ->
                    mp.isLooping = true
                    binding.videoView.start()
                    activeMediaPlayer = mp
                    setupSliderForVideo(mp.duration)
                    binding.videoView.setOnPreparedListener(null)
                }
                applyVideoRotation(model.rotation.value ?: 0)
                binding.videoView.setVideoURI(processedUri)
            }
        }
    }
    
    private fun setupSliderForVideo(duration: Int) {
        if (duration <= 0) return
        binding.fullScreenFrameSlider.valueFrom = 0f
        binding.fullScreenFrameSlider.valueTo = duration.toFloat()
        binding.fullScreenFrameSlider.stepSize = 1.0f  // Force integer frame indices
        val startPosition = model.selectedFramePositionMs.value?.toFloat() ?: duration.toFloat()
        binding.fullScreenFrameSlider.value = startPosition.coerceIn(0f, duration.toFloat())
        // Guard: Don't seek if we're showing an image
        if (model.processedImageUri.value == null) {
            binding.videoView.seekTo(startPosition.toInt())
        }
        updateFrameInfoText(startPosition.toLong())
    }
    
    private fun updateFrameInfoText(positionMs: Long) {
        val input = model.framesInput.value
        val totalFrames = input?.size ?: 1
        
        if (input is com.dan.timelapse.framesinput.ImagesFramesInput) {
            // Slider is in frame index mode
            val frameIndex = positionMs.toInt()
            binding.textFrameInfo.text = "Frame ${frameIndex + 1}/$totalFrames"
        } else {
            // Slider is in video time mode (milliseconds)
            val totalDuration = try { binding.videoView.duration.toLong() } catch(e: Exception) { 1L }.coerceAtLeast(1)
            val fps = model.outputFps.value ?: 30
            val totalVideoFrames = ((totalDuration * fps) / 1000).toInt().coerceAtLeast(1)
            val currentFrame = (((positionMs * fps) / 1000).toInt() + 1).coerceIn(1, totalVideoFrames)
            val totalSeconds = positionMs / 1000
            val minutes = totalSeconds / 60
            val secs = totalSeconds % 60
            binding.textFrameInfo.text = String.format("Frame %d/%d (%02d:%02d)", currentFrame, totalVideoFrames, minutes, secs)
        }
    }
    
    private fun confirmSelectedFrame() {
        val selectedPositionMs = binding.fullScreenFrameSlider.value.toLong()
        model.setSelectedFramePosition(selectedPositionMs)
        isFrameSelectionMode = false
        binding.frameSelectorOverlay.isVisible = false
        binding.bottomNavigation.isVisible = true
        binding.bottomNavigation.selectedItemId = R.id.nav_finish
        showTray()
    }

    private fun safeSeekTo(positionMs: Int) {
        try {
            if (binding.videoView.duration > 0) {
                binding.videoView.seekTo(positionMs)
            }
        } catch (e: IllegalStateException) {
        } catch (e: Exception) {
        }
    }

    private fun updateFramePreview(frameIndex: Int) {
        val input = model.framesInput.value
        val processedUri = model.processedVideoUri.value
        if (processedUri != null) {
            val positionMs = frameIndex.toLong()
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && activeMediaPlayer != null) {
                    activeMediaPlayer?.seekTo(positionMs, android.media.MediaPlayer.SEEK_CLOSEST)
                } else {
                    safeSeekTo(positionMs.toInt())
                }
            } catch (e: IllegalStateException) {
                Log.e("MFSR_UI", "MediaPlayer IllegalStateException in updateFramePreview")
                reinitializeVideoPlayer()
            } catch (e: Exception) { }
            return
        }
        if (input is com.dan.timelapse.framesinput.ImagesFramesInput) {
            try {
                val frame = input.getFrame(frameIndex)
                if (frame != null) {
                    val bmp = getPooledPreviewBitmap(frame.cols(), frame.rows())
                    Utils.matToBitmap(frame, bmp)
                    frame.release()
                    binding.mainVideoView.setImageBitmap(bmp)
                }
            } catch (e: Exception) { }
        }
    }

    fun exitFrameSelectionMode() {
        isFrameSelectionMode = false
        binding.frameSelectorOverlay.isVisible = false
        binding.bottomNavigation.isVisible = true
        showTray()
    }

    fun getCurrentPreviewBitmap(): Bitmap? {
        return (binding.mainVideoView as? TouchImageView)?.getBitmap()
    }
    
    fun getRenderedVideoFrame(): Bitmap? {
        val videoPath = model.lastGeneratedFilePath ?: return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoPath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0
            val positionUs = when {
                model.selectedFramePositionMs.value != null -> {
                    val safeMs = model.selectedFramePositionMs.value!!.coerceAtMost((durationMs - 100).coerceAtLeast(0))
                    safeMs * 1000
                }
                binding.videoView.isPlaying -> binding.videoView.currentPosition.toLong() * 1000
                else -> (durationMs * 1000) / 2
            }
            retriever.getFrameAtTime(positionUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }
    
    fun shareVideo() {
        val videoPath = model.lastGeneratedFilePath ?: return
        val videoFile = java.io.File(videoPath)
        if (!videoFile.exists()) return
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.provider", videoFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share Video"))
    }
    
    fun shareImage(bitmap: Bitmap) {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val cacheFile = java.io.File(cacheDir, "share_image.jpg")
            java.io.FileOutputStream(cacheFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(this@MainActivity, "${packageName}.provider", cacheFile)
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "Share Image"))
            }
        }
    }
    
    fun shareFileUri(file: java.io.File) {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val uri = androidx.core.content.FileProvider.getUriForFile(this@MainActivity, "${packageName}.provider", file)
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "Share Image"))
            }
        }
    }
    
    fun notifyGallery(file: File) {
        if (!file.exists()) return
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/TimeLapse")
        }
        contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)?.let { uri ->
            contentResolver.openOutputStream(uri)?.use { output ->
                file.inputStream().copyTo(output)
            }
        }
    }
    
    fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    
    private fun clearMfsrCache() {
        try {
            cacheDir.listFiles()?.forEach { file ->
                if (file.extension == "tmp" || file.name.contains("hrCanvas") || file.name.contains("hrWeights")) {
                    file.delete()
                }
            }
            Log.i("MFSR", "Cache cleaned")
        } catch (e: Exception) {
            Log.e("MFSR", "Cache cleanup failed", e)
        }
    }

    private fun showTabStatusToast(status: String) {
        tabStatusToastRunnable?.let { toastHandler.removeCallbacks(it) }
        val iconRes = if (isPlayingOriginalVideo) R.drawable.ic_media_play else R.drawable.ic_movie
        binding.tabStatusIcon.setImageResource(iconRes)
        binding.tabStatusText.text = status
        binding.tabStatusToast.visibility = View.VISIBLE
        binding.tabStatusToast.alpha = 1f
        tabStatusToastRunnable = Runnable {
            fadeOutAndHide(binding.tabStatusToast) { binding.tabStatusToast.visibility = View.GONE }
        }
        toastHandler.postDelayed(tabStatusToastRunnable!!, 2000)
    }

    private fun showPlayPauseToast(isPlaying: Boolean) {
        playPauseToastRunnable?.let { toastHandler.removeCallbacks(it) }
        val iconRes = if (isPlaying) R.drawable.ic_media_play else R.drawable.ic_media_pause
        binding.playPauseIcon.setImageResource(iconRes)
        binding.playPauseToast.visibility = View.VISIBLE
        binding.playPauseToast.alpha = 1f
        binding.playPauseToast.scaleX = 0.5f
        binding.playPauseToast.scaleY = 0.5f
        binding.playPauseToast.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
        playPauseToastRunnable = Runnable {
            fadeOutAndHide(binding.playPauseToast) { binding.playPauseToast.visibility = View.GONE }
        }
        toastHandler.postDelayed(playPauseToastRunnable!!, 800)
    }

    private fun fadeOutAndHide(view: View, onEnd: () -> Unit) {
        view.animate().alpha(0f).setDuration(300).withEndAction { onEnd() }.start()
}
    
    private fun isUriAccessible(uri: Uri): Boolean {
        return try {
            contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        } catch (e: Exception) {
            false
        }
    }

    fun onOpenLastSelection() {
        model.isUserPaused = false
        val (uri, _) = model.loadLastSession(this)
        if (uri != null) {
            if (!isUriAccessible(uri)) {
                Log.w("TimeLapse", "URI permission lost in onOpenLastSelection. Clearing stale session.")
                clearSavedSession()
                showToast("Permission lost — session cleared. Please re-select your media.")
                return
            }
            val sourceType = model.lastSourceType.value ?: MainViewModel.SourceType.VIDEO
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    when (sourceType) {
                        MainViewModel.SourceType.VIDEO -> loadVideoForLastSession(uri)
                        MainViewModel.SourceType.IMAGES -> loadImagesForLastSession(uri)
                        MainViewModel.SourceType.FOLDER -> loadFolderForLastSession(uri)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: FileNotFoundException) {
                    clearSavedSession()
                    withContext(Dispatchers.Main + NonCancellable) { showToast("File not found — session cleared") }
                } catch (e: IOException) {
                    withContext(Dispatchers.Main + NonCancellable) { showToast("Cannot access storage (transient error)") }
                } catch (e: Exception) {
                    clearSavedSession()
                    withContext(Dispatchers.Main + NonCancellable) { showToast("Failed to load last media: ${e.message}") }
                }
            }
        }
    }
    
    private suspend fun loadVideoForLastSession(uri: Uri) {
        val inputObj: VideoFramesInput
        try {
            inputObj = withContext(Dispatchers.IO) {
                val uriFile = UriFile.fromSingleUri(this@MainActivity, uri)
                    ?: throw FileNotFoundException("Video URI not found: $uri")
                VideoFramesInput(uriFile)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: FileNotFoundException) {
            withContext(Dispatchers.Main + NonCancellable) {
                clearSavedSession()
                showToast("Selected video file not found")
            }
            return
        } catch (e: IOException) {
            withContext(Dispatchers.Main + NonCancellable) { showToast("Storage temporarily unavailable") }
            return
        } catch (e: Exception) {
            withContext(Dispatchers.Main + NonCancellable) {
                clearSavedSession()
                showToast("Cannot access last file (permission may have been revoked)")
            }
            return
        }
        withContext(Dispatchers.Main) {
            try {
                model.setFramesInput(inputObj)
                binding.videoView.isVisible = true
                binding.mainVideoView.isVisible = false
                applyVideoRotation(model.rotation.value ?: 0)
                binding.videoView.setVideoURI(inputObj.videoUri)
                binding.videoView.setOnPreparedListener { mp ->
                    mp.isLooping = true
                    binding.videoView.start()
                    activeMediaPlayer = mp
                    if (model.preserveAudio.value != true) mp.setVolume(0f, 0f)
                    val actualDuration = mp.duration
                    if (actualDuration > 0) {
                        binding.videoSeekBar.max = actualDuration
                    }
                    videoDuration = actualDuration.toLong()
                    binding.videoSeekBar.progress = 0
                    if (!isTrayVisible) {
                        binding.videoSeekBar.visibility = View.VISIBLE
                    }
                    startPreviewLoop(10)
                }
                showToast("Restored: ${inputObj.name}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: FileNotFoundException) {
                clearSavedSession()
                showToast("Video file not found")
            } catch (e: IOException) {
                showToast("Storage temporarily unavailable")
            } catch (e: Exception) {
                clearSavedSession()
                showToast("Cannot access last file (permission may have been revoked)")
            }
        }
    }
    
    private suspend fun loadImagesForLastSession(uri: Uri) {
        withContext(Dispatchers.Main) {
            try {
                val uriFile = UriFile.fromSingleUri(this@MainActivity, uri)
                    ?: throw FileNotFoundException("Image URI not found: $uri")
                val inputObj = ImagesFramesInput(listOf(uriFile), model)
                model.setFramesInput(inputObj)
                binding.videoView.isVisible = false
                binding.mainVideoView.isVisible = true
                binding.videoSeekBar.max = inputObj.size - 1
                if (!isTrayVisible) {
                    binding.videoSeekBar.visibility = View.VISIBLE
                }
                startPreviewLoop(10)
            } catch (e: CancellationException) {
                throw e
            } catch (e: FileNotFoundException) {
                clearSavedSession()
                showToast("Image file not found")
            } catch (e: IOException) {
                showToast("Storage temporarily unavailable")
            } catch (e: Exception) {
                clearSavedSession()
                showToast("Failed to restore image session")
            }
        }
    }
    
    private suspend fun loadFolderForLastSession(uri: Uri) {
        withContext(Dispatchers.IO) {
            try {
                val folderUriFile = UriFile.fromTreeUri(this@MainActivity, uri) 
                    ?: throw FileNotFoundException("Folder URI not found: $uri")
                
                withContext(Dispatchers.Main) {
                    showLoadingDialog("Restoring folder...")
                }
                val inputObj = ImagesFramesInput.fromFolder(folderUriFile, model) { current, total, status ->
                    runOnUiThread { updateLoadingDialog(current, total, status) }
                }
                withContext(Dispatchers.Main) {
                    dismissLoadingDialog()
                }
                
                model.setFramesInput(inputObj)
                binding.videoView.isVisible = false
                binding.mainVideoView.isVisible = true
                binding.videoSeekBar.max = inputObj.size - 1
                if (!isTrayVisible) {
                    binding.videoSeekBar.visibility = View.VISIBLE
                }
                withContext(Dispatchers.Main) { showToast("Restored: ${inputObj.name}") }
                startPreviewLoop(10)
            } catch (e: CancellationException) {
                throw e
            } catch (e: FileNotFoundException) {
                withContext(Dispatchers.Main + NonCancellable) {
                    dismissLoadingDialog()
                    clearSavedSession()
                    showToast("Folder not found — session cleared")
                }
            } catch (e: IOException) {
                withContext(Dispatchers.Main + NonCancellable) {
                    dismissLoadingDialog()
                    showToast("Storage temporarily unavailable")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main + NonCancellable) {
                    dismissLoadingDialog()
                    clearSavedSession()
                    showToast("Cannot access last folder (permission may have been revoked)")
                }
            }
        }
    }
    
    fun showSourceTray() {
        binding.bottomNavigation.selectedItemId = R.id.nav_source
        val currentFragment = supportFragmentManager.findFragmentById(R.id.tray_content_holder)
        if (currentFragment !is SourceFragment) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.tray_content_holder, SourceFragment::class.java.getDeclaredConstructor().newInstance())
                .commitNow()
        }
        showTray()
    }
    
    fun openMaskEditor() {
        isMaskEditMode = true
        binding.bottomNavigation.visibility = View.GONE
        val currentFragment = supportFragmentManager.findFragmentById(R.id.tray_content_holder)
        if (currentFragment !is MaskEditFragment) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.tray_content_holder, MaskEditFragment::class.java.getDeclaredConstructor().newInstance())
                .commitNow()
        }
        binding.maskOverlay.visibility = View.VISIBLE
        showTray()
    }
    
    fun closeMaskEditor() {
        isMaskEditMode = false
        binding.bottomNavigation.visibility = View.VISIBLE
        binding.maskOverlay.visibility = View.GONE
        showSourceTray()
    }
    
    fun closeMaskEditorToTransform() {
        isMaskEditMode = false
        binding.bottomNavigation.visibility = View.VISIBLE
        binding.maskOverlay.visibility = View.GONE
        supportFragmentManager.beginTransaction()
            .replace(R.id.tray_content_holder, TransformFragment::class.java.getDeclaredConstructor().newInstance())
            .commitNow()
        binding.bottomNavigation.selectedItemId = R.id.nav_transform
        showTray()
    }
    
    private fun showGenerateDialog() {
        cancelPressCount = 0
        val dialogView = layoutInflater.inflate(R.layout.dialog_generate, null)
        generateDialog = AlertDialog.Builder(this, R.style.Theme_TimeLapse_Dialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()
        val isStabilizing = model.isStabilized.value == true
        val dialogTitle = dialogView.findViewById<TextView>(R.id.dialogTitle)
        dialogTitle?.text = if (isStabilizing) "Stabilizing & Rendering" else "Rendering"
        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancel).setOnClickListener { button ->
            
            if (model.isProcessingCancelled.value == true) {
                // SECOND TAP: The native layer is deadlocked. Execute Nuclear Reset.
                Toast.makeText(this@MainActivity, "Force killing deadlocked process...", Toast.LENGTH_LONG).show()
                
                val intent = Intent(this@MainActivity, MainActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(intent)
                Runtime.getRuntime().exit(0)
            } else {
                // FIRST TAP: Polite cancellation
                model.cancelProcessing()
                dialogTitle?.text = "Cancelling..."
                
                // Visually change the button so the user knows they can tap it again to force quit
                val cancelBtn = button as com.google.android.material.button.MaterialButton
                cancelBtn.text = "FORCE CLOSE"
                cancelBtn.setBackgroundColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_dark))
            }
        }
        
        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnContinueBackground)?.setOnClickListener {
            android.widget.Toast.makeText(this, "Running in background. You will be notified when complete.", android.widget.Toast.LENGTH_SHORT).show()
        }
        
        generateDialog?.show()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        val serviceIntent = Intent(this, RenderService::class.java).apply {
            action = RenderService.ACTION_START
        }
        androidx.core.content.ContextCompat.startForegroundService(this, serviceIntent)
        
        try {
            if (binding.videoView.isPlaying) {
                binding.videoView.pause()
            }
        } catch (e: Exception) {
            Log.e("MFSR_UI", "Error pausing VideoView", e)
        }
        
        model.isPlaying.postValue(false)
    }
    
    private fun dismissGenerateDialog() {
        generateDialog?.dismiss()
        generateDialog = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        val serviceIntent = Intent(this, RenderService::class.java).apply {
            action = RenderService.ACTION_STOP
        }
        startService(serviceIntent)
        
        try {
            updatePreviewDisplay()
        } catch (e: Exception) {
            Log.e("MFSR_UI", "Error updating preview", e)
        }
    }
    
    private fun updatePreviewDisplay() {
        // Phase 16: Capture the current play state before we tear down the video player
        val wasPlayingBeforeRefresh = try { binding.videoView.isPlaying } catch(e: Exception) { true }
        
        val processedImageUri = model.processedImageUri.value
        val processedVideoUri = model.processedVideoUri.value
        val input = model.framesInput.value
        val currentTabId = binding.bottomNavigation.selectedItemId
        val isEditingTab = (currentTabId == R.id.nav_source || currentTabId == R.id.nav_transform)
        
        // Phase 15: Tab-Aware Routing — never show processed video on editing tabs
        if (!isEditingTab && processedVideoUri != null) {
            isPlayingOriginalVideo = false
            binding.videoView.isVisible = true
            binding.mainVideoView.isVisible = false
            binding.videoView.setVideoURI(processedVideoUri)
            binding.videoView.setOnPreparedListener { mp ->
                mp.isLooping = true
                activeMediaPlayer = mp
                if (model.preserveAudio.value != true) mp.setVolume(0f, 0f)
                if (wasPlayingBeforeRefresh) {
                    model.isPlaying.postValue(true)
                    mp.start()
                } else {
                    model.isPlaying.postValue(false)
                    mp.pause()
                }
            }
            binding.videoSeekBar.isEnabled = true
            binding.videoSeekBar.visibility = View.VISIBLE
        } else if (!isEditingTab && processedImageUri != null) {
            isPlayingOriginalVideo = false
            binding.mainVideoView.visibility = View.VISIBLE
            binding.mainVideoView.setBackgroundColor(android.graphics.Color.BLACK)
            
            // FIX 2: Use explicit elevation instead of bringToFront() to prevent touch interception
            binding.videoView.elevation = 0f
            binding.mainVideoView.elevation = 5f
            binding.bottomNavigation.elevation = 16f
            
            // Use safeTeardown to marshal MediaPlayer operations to Main Looper
            binding.videoView.safeTeardown()
            model.isPlaying.postValue(false)
            
            binding.videoSeekBar.isEnabled = false
            binding.videoSeekBar.visibility = View.INVISIBLE
            
            model.processedImageUri.value?.path?.let { path ->
                // Image loading is now handled by processedImageUri.observe - do not duplicate
                // This prevents double memory usage and UI blocking
            }
        } else if (input != null) {
            isPlayingOriginalVideo = true
            binding.videoView.isVisible = true
            binding.mainVideoView.isVisible = false
            
            if (input is VideoFramesInput) {
                binding.videoView.setVideoURI(input.videoUri)
                binding.videoView.setOnPreparedListener { mp ->
                    mp.isLooping = true
                    activeMediaPlayer = mp
                    val trimStartMs = model.getTrimStartMs()
                    mp.seekTo(trimStartMs.toInt())
                    if (model.preserveAudio.value != true) mp.setVolume(0f, 0f)
                    if (wasPlayingBeforeRefresh) {
                    model.isPlaying.postValue(true)
                    mp.start()
                } else {
                    model.isPlaying.postValue(false)
                    mp.pause()
                }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        try {
                            val targetFps = model.outputFps.value ?: model.previewFps.value ?: 30
                            val baseFps = input.fps.coerceAtLeast(1)
                            val speedFactor = targetFps.toFloat() / baseFps.toFloat()
                            val wasPlaying = mp.isPlaying
                            mp.playbackParams = mp.playbackParams.setSpeed(speedFactor)
                            if (!wasPlaying) mp.pause()
                        } catch (e: Exception) {}
                    }
                }
            } else if (input is ImagesFramesInput) {
                binding.videoView.isVisible = false
                binding.mainVideoView.isVisible = true
            }
            binding.mainVideoView.post {
                applyVideoRotation(model.rotation.value ?: 0)
                applyVideoAspectRatio(model.transformState.value?.getAspectRatio())
            }
            binding.videoSeekBar.isEnabled = true
            binding.videoSeekBar.visibility = View.VISIBLE
        }
    }
    
    // G6-08: A/B Comparison - Long press shows original, release shows processed
    private fun showOriginalInputForComparison() {
        val input = model.framesInput.value ?: return
        val processedUri = model.processedVideoUri.value ?: return
        
        // Store current processed URI to restore later
        _comparisonOriginalUri = processedUri
        
        // Show original input
        isPlayingOriginalVideo = true
        binding.videoView.isVisible = true
        binding.mainVideoView.isVisible = false
        
        if (input is VideoFramesInput) {
            binding.videoView.setVideoURI(input.videoUri)
            binding.videoView.setOnPreparedListener { mp ->
                mp.isLooping = true
                activeMediaPlayer = mp
                val trimStartMs = model.getTrimStartMs()
                mp.seekTo(trimStartMs.toInt())
                if (model.preserveAudio.value != true) mp.setVolume(0f, 0f)
                model.isPlaying.postValue(true)
                mp.start()
                
                // Show comparison indicator
                runOnUiThread { showToast("Showing Original (Long-press to compare)") }
            }
        }
    }
    
    private var _comparisonOriginalUri: Uri? = null
    
    private fun restoreProcessedVideoForComparison() {
        val processedUri = _comparisonOriginalUri ?: model.processedVideoUri.value ?: return
        
        isPlayingOriginalVideo = false
        binding.videoView.setVideoURI(processedUri)
        binding.videoView.setOnPreparedListener { mp ->
            mp.isLooping = true
            activeMediaPlayer = mp
            if (model.preserveAudio.value != true) mp.setVolume(0f, 0f)
            model.isPlaying.postValue(true)
            mp.start()
            
            // Show comparison indicator
            runOnUiThread { showToast("Showing Rendered") }
        }
        
        _comparisonOriginalUri = null
    }
    
    private fun shouldAutoHideTrayForVideo(): Boolean {
        val videoRect = android.graphics.Rect()
        if (binding.videoView.isVisible) {
            binding.videoView.getGlobalVisibleRect(videoRect)
        } else if (binding.mainVideoView.isVisible) {
            binding.mainVideoView.getGlobalVisibleRect(videoRect)
        } else {
            return false
        }
        val trayRect = android.graphics.Rect()
        binding.trayContainer.getGlobalVisibleRect(trayRect)
        if (trayRect.height() == 0) return false
        return videoRect.bottom > trayRect.top && videoRect.top < trayRect.bottom
    }
    
    private fun updateScreenLock(forceOff: Boolean = false) {
        val isProcessing = model.isProcessing.value == true
        val isPlaying = try { binding.videoView.isPlaying } catch (e: Exception) { false }
        
        if (isProcessing) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            idleHandler.removeCallbacks(idleRunnable)
            return
        }

        if (isCharging || isPlaying) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            idleHandler.removeCallbacks(idleRunnable)
        } else if (isBatteryLow && !isCharging) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            idleHandler.removeCallbacks(idleRunnable)
        } else if (forceOff) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            idleHandler.removeCallbacks(idleRunnable)
            idleHandler.postDelayed(idleRunnable, FIVE_MINUTES_MS)
        }
    }
    
    override fun onDestroy() {
        try {
            try {
                binding.videoView.safeTeardown()
            } catch (e: Exception) {}
            activeMediaPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
            activeMediaPlayer = null
            try {
                if (binding.videoView.isPlaying) {
                    binding.videoView.pause()
                }
            } catch (e: Exception) {}
        } catch (e: Exception) {}
        
        clearMfsrCache()
        
        binding.globalProgressBar.visibility = View.GONE
        trimWatchdogHandler.removeCallbacksAndMessages(null)
        monitorHandler.removeCallbacks(monitorRunnable)
        idleHandler.removeCallbacks(idleRunnable)
        try {
            unregisterReceiver(powerStateReceiver)
        } catch (e: Exception) {}
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        playbackJob?.cancel()
        loadingJob?.cancel()
        
        // FIX 3: Cancel any lingering background UI updates and release native resources
        displayImageJob?.cancel()
        
        // FIX 3: Release the framesInput to free hardware decoders
        model.framesInput.value?.release()
        
        // FIX 3: Clear the bitmap pool to free JVM memory immediately
        for (i in previewBitmapPool.indices) {
            previewBitmapPool[i]?.recycle()
            previewBitmapPool[i] = null
        }
        
        // THE FIX: If rendering is in progress, cancel it and wait for cleanup
        if (model.isProcessing.value == true) {
            model.cancelProcessing()
            processingJob?.cancel()
            // Give the VideoProcessor time to finalize the MOOV atom
            Thread.sleep(500)
        }
        
        // FIX 3: Ensure media destruction runs safely on Main thread
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                binding.videoView.stopPlayback()
                val currentInput = model.framesInput.value
                currentInput?.release()
            } catch (e: Exception) { }
        }
        
        super.onDestroy()
    }
    
    // Task 3: Reinitialize MediaPlayer after IllegalStateException
    private fun reinitializeVideoPlayer() {
        try {
            try {
                if (binding.videoView.isPlaying) {
                    binding.videoView.pause()
                }
            } catch (e: Exception) {}
            
            val input = model.framesInput.value
            val processedUri = model.processedVideoUri.value
            val currentTabId = binding.bottomNavigation.selectedItemId
            val isEditingTab = (currentTabId == R.id.nav_source || currentTabId == R.id.nav_transform)
            
            binding.videoView.setOnErrorListener(null)
            
            if (!isEditingTab && processedUri != null) {
                binding.videoView.setVideoURI(processedUri)
                binding.videoView.setOnPreparedListener { mp ->
                    mp.isLooping = true
                    activeMediaPlayer = mp
                    if (model.preserveAudio.value != true) mp.setVolume(0f, 0f)
                    val actualDuration = mp.duration
                    if (actualDuration > 0) binding.videoSeekBar.max = actualDuration
                    
                    if (model.isPlaying.value == true) mp.start() else mp.pause()
                }
            } else if (input is VideoFramesInput) {
                binding.videoView.setVideoURI(input.videoUri)
                binding.videoView.setOnPreparedListener { mp ->
                    mp.isLooping = true
                    activeMediaPlayer = mp
                    if (model.preserveAudio.value != true) mp.setVolume(0f, 0f)
                    val actualDuration = mp.duration
                    if (actualDuration > 0) binding.videoSeekBar.max = actualDuration
                    
                    val trimStartMs = model.getTrimStartMs()
                    mp.seekTo(trimStartMs.toInt())
                    
                    if (model.isPlaying.value == true) mp.start() else mp.pause()
                }
            }
        } catch (e: Exception) {
            Log.e("MFSR_UI", "Failed to reinitialize video player", e)
        }
    }
}
