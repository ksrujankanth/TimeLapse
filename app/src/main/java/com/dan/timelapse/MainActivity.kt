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
import android.provider.DocumentsContract
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
import com.dan.timelapse.eta.Eta
import com.dan.timelapse.eta.EtaApp
import com.dan.timelapse.eta.EtaDialogText
import com.dan.timelapse.eta.EtaPhase
import com.dan.timelapse.eta.EtaSpecs
import com.dan.timelapse.utils.OutputParams
import com.dan.timelapse.utils.ShareIntentRules
import com.dan.timelapse.utils.SharedMediaMaterializer
import com.dan.timelapse.video.VideoTools
import java.io.File
import androidx.appcompat.app.AlertDialog

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val model: MainViewModel by viewModels()
    private lateinit var gestureDetector: GestureDetector
    private var playbackJob: Job? = null

    /** Upper bound on waiting for the preview loop to finish an in-flight frame decode. */
    private val PREVIEW_JOIN_TIMEOUT_MS = 3_000L
    
    private var isTrayVisible = false
    private var isTrayForcedOpen = false
    private var isFrameSelectionMode = false
    private var isMaskEditMode = false
    private var generateDialog: AlertDialog? = null
    private var processingJob: kotlinx.coroutines.Job? = null
    private var cancelPressCount = 0

    // Learned-ETA job for the media loading dialog (folder scan / share intake).
    private var loadingEtaJob: com.dan.timelapse.eta.EtaJob? = null
    private var loadingEtaPhase: EtaPhase? = null
    private var loadingEtaLastFraction: Double = 0.0
    private var lastLoadingDialogLog: String? = null

    /**
     * The app-wide zoom listener. MaskEditFragment swaps in its own listener while it is open,
     * so this has to be a field we can restore on the way out.
     */
    private val videoZoomListener = object : ZoomableFrameLayout.OnZoomListener {
        override fun onZoom(scale: Float, translationX: Float, translationY: Float) {
            model.setZoom(scale, translationX, translationY)
        }

        override fun onZoomReset() {
            model.setZoom(1f, 0f, 0f)
        }
    }
    
    // Loading dialog for huge folders/files
    private var loadingDialog: AlertDialog? = null
    private var loadingJob: Job? = null

    /**
     * True while the loaded media came from a share intent.
     *
     * A share hands over a ONE-SHOT read grant (Google Photos' cloud items included), so the
     * URI must never be written into the saved session: the grant is gone on the next launch
     * and the session would "restore" into nothing. Share loads are deliberately not persisted;
     * the media still lives in the app cache for the whole session.
     */
    private var isSharedMediaSession = false
    private val shareMaterializer by lazy { SharedMediaMaterializer(this) }

    /** What a share intent asked us to open. A null kind means "sniff the bytes". */
    private data class SharePayload(
        val kind: ShareIntentRules.Kind?,
        val uris: List<Uri>,
        val mime: String?
    )

    /** Test hook for the share-intake instrumented test. */
    internal fun loadedFrameCount(): Int = model.framesInput.value?.size ?: 0


    // Colors are handled via XML theme attributes (?attr/colorPrimary)
    
    // Video type tracking: true = original (trimmed), false = rendered
    var isPlayingOriginalVideo = true
        private set
    
    // Capture MediaPlayer for frame-accurate seeking
    private var activeMediaPlayer: android.media.MediaPlayer? = null
    private var previewLoopTarget: Int = 0
    private var previewLoopsDone: Int = 0

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
    
    /**
     * `@Synchronized`: this is called from the preview coroutine (Dispatchers.Default),
     * the scrubber (Dispatchers.IO) and frame selection. Unsynchronised, two callers could
     * both read `previewPoolIndex` and one could recycle() the very Bitmap the other was
     * about to hand to Utils.matToBitmap — a recycled Bitmap makes AndroidBitmap_getInfo
     * fail, which surfaced as "info.format == RGBA_8888" assertion failures.
     */
    @Synchronized
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
                // Real-time speed sync for the ORIGINAL video only. Rendered files
                // already carry their own timestamps; overriding their playbackParams
                // fought the short-clip speed boost applied in onPrepared.
                if (isPlayingOriginalVideo &&
                    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    try {
                        activeMediaPlayer?.let { mp ->
                            var baseFps = 30
                            val input = model.framesInput.value
                            if (input is com.dan.timelapse.framesinput.VideoFramesInput) {
                                baseFps = input.fps.coerceAtLeast(1)
                            }
                            val targetFps = model.previewFps.value ?: 30
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
                    if (handlePreviewLoopEnd(startMs)) {
                        return
                    }
                } else if (currentMs > 0 && currentMs < startMs) {
                    // Catch native MediaPlayer looping to position 0 (isLooping=true suppresses OnCompletionListener)
                    lastWatchdogSeekTime = System.currentTimeMillis()
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            activeMediaPlayer?.seekTo(startMs.toLong(), MediaPlayer.SEEK_CLOSEST)
                        } else {
                            binding.videoView.seekTo(startMs.toInt())
                        }
                    } catch (e: IllegalStateException) {
                        Log.w("MainActivity", "Ignored seekTo in watchdog", e)
                    } catch (e: Exception) {
                        Log.w("MainActivity", "Seek error in watchdog", e)
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

        // The "recreated with zoom disabled" repair lives in onPostResume(), not here:
        // at this point MaskEditFragment's onViewCreated has not run, so the flag is
        // always still true and the check was dead code.

        // A share intent takes precedence over the last session: the user tapped TimeLapse in
        // another app's share sheet, so restoring yesterday's media first would flash the wrong
        // file and fight the loader.
        val pendingShare = if (model.shareIntentConsumed) null else parseShareIntent(intent)
        if (pendingShare != null) {
            // Consume it: a config change recreates this activity with the SAME launch intent,
            // and replaying a share would replace media the user has since chosen by hand.
            // (Mutating the intent itself breaks ActivityScenario's intent matching, so the
            // flag lives in the ViewModel.)
            model.shareIntentConsumed = true
            loadSharedPayload(pendingShare)
        } else
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

        // Debug-only hook so a device run can start (and cancel) an SR render without
        // fighting the Effects tray's auto-hide:
        //   adb shell am start -n com.dan.timelapse/.MainActivity \
        //       --ez tl_debug_mfsr true --ez tl_debug_cancel true --el tl_debug_cancel_ms 5000
        val debuggable = (applicationInfo.flags and
                android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable && intent?.getBooleanExtra("tl_debug_mfsr", false) == true) {
            // One-shot: a config change recreates the activity with the SAME Intent
            // object, which would otherwise start a second render (and a second cancel).
            intent?.removeExtra("tl_debug_mfsr")
            lifecycleScope.launch(Dispatchers.Main) {
                var waited = 0L
                while (model.framesInput.value == null && waited < 240_000L) {
                    kotlinx.coroutines.delay(250)
                    waited += 250
                }
                if (model.framesInput.value == null) {
                    Log.e("TLDebug", "tl_debug_mfsr: no media after ${waited}ms")
                    return@launch
                }
                if (intent.getBooleanExtra("tl_debug_video_render", false)) {
                    Log.i("TLDebug", "tl_debug_video_render: keeping effect NONE")
                } else {
                    model.setVisualEffect(Effect.SUPER_RESOLUTION.ordinal)
                }
                if (intent.getBooleanExtra("tl_debug_full_trim", false)) {
                    // The Media pane owns a RangeSlider that pushes its own default
                    // (300-frame) values back into the model. Switch to the Effects pane,
                    // which has no trim slider, then apply the full range so nothing
                    // overwrites it before the render starts.
                    binding.bottomNavigation.selectedItemId = R.id.nav_studio
                    kotlinx.coroutines.delay(1200)
                    val n = model.framesInput.value?.size ?: 0
                    if (n > 0) {
                        model.setTrimRange(1f, n.toFloat())
                        Log.i("TLDebug", "tl_debug_full_trim: trim 1..$n (Effects pane)")
                    }
                }
                kotlinx.coroutines.delay(500)
                Log.i("TLDebug", "tl_debug_mfsr: triggering studio process, trim=${model.trimRange.value}")
                model.triggerStudioProcess()
                if (intent.getBooleanExtra("tl_debug_cancel", false)) {
                    val at = intent.getLongExtra("tl_debug_cancel_ms", 5000L)
                    kotlinx.coroutines.delay(at)
                    Log.i("TLDebug", "tl_debug_mfsr: cancelling after ${at}ms")
                    model.cancelProcessing()
                }
            }
        }

        binding.mainVideoView.setSurfaceReadyListener(object : OnSurfaceReadyListener {
            override fun onSurfaceReady() {
                // ...
            }
        })
         
        // NOTE: dimmerView / videoContainer / emptyStateOverlay must NOT get click listeners.
        // ZoomableFrameLayout intercepts every touch while zoom is enabled, so listeners
        // inside it can never fire — EXCEPT in the mask editor, where isZoomEnabled is
        // false and DRAW/ERASE needs touches to reach MaskView. That is why the dimmer
        // is force-hidden in mask mode (updateDimmer) instead of relying on a click
        // listener to remove it.
        // Taps are owned by binding.videoContainer.onTap (see onVideoSurfaceTapped).

        binding.videoContainer.onZoomListener = videoZoomListener

        binding.videoContainer.onTap = { onVideoSurfaceTapped() }
        
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
                
                // Image sequences are previewed on the image view; do not swap it out.
                if (model.framesInput.value !is com.dan.timelapse.framesinput.ImagesFramesInput) {
                    binding.mainVideoView.visibility = android.view.View.GONE
                    binding.videoView.visibility = android.view.View.VISIBLE
                }
                
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
                model.setSelectedFrameFromPicker(positionMs)
                if (model.framesInput.value is com.dan.timelapse.framesinput.ImagesFramesInput) {
                    binding.mainVideoView.visibility = android.view.View.VISIBLE
                    binding.videoView.visibility = android.view.View.GONE
                }
                updateFramePreview(positionMs.toInt())
                updateFrameInfoText(positionMs)
            }
        })

        binding.btnConfirmFrame.setOnClickListener {
            confirmSelectedFrame()
        }
        
        binding.switchFullRes.isChecked = model.isFullResExportEnabled.value == true
        model.isFullResExportEnabled.observe(this) { enabled ->
            if (binding.switchFullRes.isChecked != enabled) {
                binding.switchFullRes.isChecked = enabled
            }
        }
        binding.switchFullRes.setOnCheckedChangeListener { _, isChecked ->
            if (model.isFullResExportEnabled.value != isChecked) {
                model.isFullResExportEnabled.value = isChecked
            }
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
            // Only restart the preview loop when a preview is already running.
            // This prevents startPreviewLoop from firing during media load,
            // before the MediaPlayer is prepared (which previously crashed).
            if (model.isProcessing.value != true && !model.isUserPaused && playbackJob?.isActive == true) {
                startPreviewLoop(10) 
            }
        }
        
        model.framesInput.observe(this) { input ->
            binding.emptyStateOverlay.visibility = if (input == null) View.VISIBLE else View.GONE
            // With nothing loaded there is nothing to zoom: a pinch there only magnifies the
            // empty-state copy. Touches still belong to the container so tap-to-open keeps
            // working; only scale gestures are gated.
            binding.videoContainer.isZoomGestureEnabled = input != null
            if (input == null) {
                // Nothing loaded: make sure a stale transform (e.g. a leftover pinch) cannot
                // leave the empty-state copy magnified. post{} so the view has a size.
                binding.videoContainer.post { binding.videoContainer.restoreZoom(1f, 0f, 0f) }
                model.setZoom(1f, 0f, 0f)
            }
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
            val suppressAutoSave = model.consumeAutoSaveSuppression()
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
                    set(OutputParams.KEY_FPS, model.outputFps.value ?: model.previewFps.value ?: 30)
                    set(OutputParams.KEY_FILE_QUALITY, model.fileQuality.value ?: 50)
                    set(OutputParams.KEY_PRESERVE_AUDIO, if (model.preserveAudio.value == true) 1 else 0)
                    set(OutputParams.KEY_H265, if (model.encodingH265.value == true) 1 else 0)
                    
                    set(OutputParams.KEY_STABILIZATION, if (model.isStabilized.value == true) 1 else 0)
                    set(OutputParams.KEY_ALIGN_TO_FIRST, if (model.alignToFirstFrame.value == true) 1 else 0)
                    set(OutputParams.KEY_STABILIZATION_ZOOM, ((model.stabilizationZoom.value ?: 1.05f) * 100).toInt())
                    set(OutputParams.KEY_STABILIZATION_SMOOTHING, model.stabilizationSmoothing.value ?: 60)
                    
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
                        val targetFps = model.outputFps.value ?: model.previewFps.value ?: 30
                        // The remux only rewrites timestamps; it does not resample audio.
                        // With an audio track that desyncs/stutters, so re-render instead.
                        if (com.dan.timelapse.video.VideoTools.hasAudioTrack(inputPath)) {
                            Log.i("TimeLapse", "FPS change with audio track: full re-render (remux would desync audio)")
                        } else {
                            val outputFile = File(cacheDir, "fps_remux_${System.currentTimeMillis()}.mp4")
                            try {
                                com.dan.timelapse.video.VideoTools.fastRemuxVideoSpeed(inputPath, outputFile.absolutePath, sourceFps, targetFps)
                                model.setProcessedVideo(Uri.fromFile(outputFile))
                                model.lastGeneratedFilePath = outputFile.absolutePath
                                model.setLastProcessedParams(currentParams)
                                showToast("FPS changed from $sourceFps to $targetFps")
                            } catch (e: Exception) {
                                showToast("FPS remux failed, using existing file")
                                model.setProcessedVideo(Uri.fromFile(File(inputPath)))
                            }
                            return@launch
                        }
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
                // The preview loop was only *asked* to stop at the Generate click
                // (stopPreviewLoop() just cancels, it does not wait). If it is still
                // inside getFrame() the render would decode the same 16-bit TIFF/PNG
                // sequence on a second thread at the same time. Wait for it here.
                stopPreviewLoopAndJoin()
                val currentEffect = model.visualEffect.value
                val isMFSR = currentEffect == Effect.SUPER_RESOLUTION
                // Diagnostic: log the render path decision for first-run troubleshooting
                android.util.Log.d("RenderGuard", "Render trigger: currentEffect=$currentEffect, isMFSR=$isMFSR")
                // A 16-bit TIFF/PNG sequence keeps its extra bit depth as far as the
                // fusion input, so do not throw the result back through JPEG: PNG is
                // lossless. (The fused accumulator itself is still 8-bit-valued.)
                val losslessSource = model.framesInput.value?.isHighBitDepth == true
                val fileExt = if (isMFSR) (if (losslessSource) "png" else "jpg") else "mp4"
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
                    val totalFrames = input.size
                    val lastIndex = (totalFrames - 1).coerceAtLeast(0)

                    // Priority 1: the exact frame the user confirmed in the frame picker.
                    // The ViewModel knows whether that position is an image index, a
                    // source frame index, or milliseconds in a rendered preview.
                    val pickedFrameIndex = model.resolveSelectedSourceFrameIndex()

                    // Priority 2: center of the Tray 1 trim range.
                    val trimRange = model.trimRange.value

                    val targetFrameIndex = pickedFrameIndex
                        ?: if (trimRange != null) {
                            ((trimRange.first + trimRange.second) / 2).toInt().coerceIn(0, lastIndex)
                        } else {
                            (totalFrames / 2).coerceIn(0, lastIndex)
                        }

                    // NEW: Use clean MFSRProcessor. Works for both video and image sequences.
                    val videoUri = input.videoUri
                    
                    // Scale is chosen from free RAM AND from how many frames actually contribute.
                    // 4x from 10 photos leaves most HR cells unsampled (measured 92% coverage,
                    // 0.66 samples/px) — 2x is the honest maximum there. Long videos can reach 3x/4x.
                    val contributingFrames = minOf(input.size, 120)
                    val adaptiveScale = com.dan.timelapse.mfsr.MFSRProcessor.chooseScale(
                        this@MainActivity, input.width, input.height, contributingFrames, 4.0f
                    )
                    Log.i("MFSR", "Scale decision: ${adaptiveScale}x for ${input.width}x${input.height}, " +
                            "$contributingFrames contributing frames")
                    
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
                    // Cancel must survive this Activity: the watcher below dies with
                    // lifecycleScope, but process() keeps rendering. The processor polls
                    // the ViewModel's flag directly through cancelCheck.
                    mfsrProcessor.cancelCheck = { model.isProcessingCancelled.value == true }
                    
                    // ── Learned ETA ──────────────────────────────────────────────
                    // The job spans scan -> align -> fuse -> finalize -> save. The
                    // initial frame counts are an estimate; MFSRProcessor refines
                    // them with planUnits() as soon as the real selection is known,
                    // so this only has to be good enough for the scan phase.
                    val mfsrOutputW = (input.width * adaptiveScale).toInt()
                    val mfsrOutputH = (input.height * adaptiveScale).toInt()
                    val mfsrScanWindow = minOf(
                        input.size,
                        com.dan.timelapse.mfsr.MFSRProcessor.SCAN_WINDOW_RADIUS * 2 + 1
                    ).coerceAtLeast(1)
                    // Selection keeps at least half the scan and is capped by the
                    // per-input memory budget (50 above 1080p, 120 otherwise). The
                    // processor refines this at runtime; using the raw scan window
                    // here made the first scan phase predict ~10x too long.
                    val mfsrSelectionEstimate = minOf(
                        mfsrScanWindow,
                        if (input.width.toLong() * input.height > 1920L * 1080L) 50 else 120
                    )
                    val mfsrEtaSignature = com.dan.timelapse.eta.EtaApp.mfsrSignature(
                        this@MainActivity, input.width, input.height, mfsrScanWindow, adaptiveScale
                    )
                    val mfsrEtaSpec = com.dan.timelapse.eta.EtaSpecs.mfsrRender(
                        signature = mfsrEtaSignature,
                        scanFrames = mfsrScanWindow,
                        selectedFrames = mfsrSelectionEstimate,
                        inputWidth = input.width,
                        inputHeight = input.height,
                        outputWidth = mfsrOutputW,
                        outputHeight = mfsrOutputH,
                        temperatureC = com.dan.timelapse.eta.EtaApp
                            .batteryTemperatureC(this@MainActivity)
                    )
                    val mfsrEtaJob = try {
                        com.dan.timelapse.eta.Eta.startJob(this@MainActivity, mfsrEtaSpec)
                    } catch (e: Exception) {
                        Log.w("EtaEngine", "Could not start MFSR ETA job", e)
                        null
                    }
                    mfsrProcessor.etaJob = mfsrEtaJob
                    Log.i(
                        "EtaEngine",
                        "mfsr job: predicted=${mfsrEtaJob?.predictedTotalMs?.toLong() ?: -1}ms " +
                                "input=${input.width}x${input.height} scale=${adaptiveScale} " +
                                "scan=$mfsrScanWindow samples=${mfsrEtaJob?.priorSamples ?: 0} " +
                                "key=${mfsrEtaSignature.levelKey(0)}"
                    )

                    mfsrProcessor.onProgress = { current, total, status ->
                        if (currentRenderJobId == currentJobId) {
                            model.updateProcessingProgress(current, total)
                            if (status.isNotEmpty()) model.updateProcessingStatusText(status)
                            model.updateEstimatedTimeRemaining(mfsrEtaJob?.remainingMs() ?: -1L)
                        } else {
                            model.updateProcessingProgress(current, total)
                            if (status.isNotEmpty()) model.updateProcessingStatusText(status)
                            model.updateEstimatedTimeRemaining(-1L)
                        }
                    }
                    // Cancel must be LIVE: writing isCancelled once before process()
                    // left the button inert for the whole render (the loop has no
                    // suspension points, so cancelling the job cannot interrupt it).
                    val cancelWatch = lifecycleScope.launch(Dispatchers.Main) {
                        while (isActive) {
                            mfsrProcessor.isCancelled = model.isProcessingCancelled.value == true
                            kotlinx.coroutines.delay(100)
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
                        cancelWatch.cancel()
                        if (wakeLock.isHeld) wakeLock.release()
                    }

                    // Close the learned job: only a successful, non-cancelled render
                    // is allowed to train the model (a cancel point says nothing about
                    // how long the work would have taken).
                    if (success && model.isProcessingCancelled.value != true) {
                        mfsrEtaJob?.finish(true)
                    } else {
                        mfsrEtaJob?.abandon()
                    }
                    Log.i("EtaEngine", "mfsr done: success=$success ${mfsrEtaJob?.summaryLine() ?: ""}")

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
                                    activeMediaPlayer = null
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
                        
                        if (model.isProcessingCancelled.value == true) {
                            model.setSaveReady(false)
                        }

                        if (success) {
                            // A 16-bit render is written to a sibling .png (a JPEG
                            // target would clip CV_16U to white), so trust the file the
                            // processor actually wrote, not the requested path.
                            val writtenFile = mfsrProcessor.writtenOutputFile ?: outputFile
                            if (!writtenFile.exists()) {
                                Log.e("TimeLapse", "Render reported success but ${writtenFile.name} is missing")
                            }
                            try {
                                val toneGen = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 100)
                                toneGen.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 200)
                                toneGen.release()
                            } catch (e: Exception) {}
                            
                            model.setProcessedImageUri(Uri.fromFile(writtenFile))
                            // The previous video render belongs to another effect; it must
                            // not be reused by the frame picker or the Save/Share buttons.
                            model.setProcessedVideo(null)
                            model.setLastProcessedParams(paramsToSave)
                            
                            // Auto-save is the user's choice and applies to EVERY render,
                            // including Super Resolution. (It used to be forced for MFSR,
                            // so the switch silently did nothing for that effect.)
                            if (!suppressAutoSave && model.autoSaveAfterRender.value == true) {
                                // The extension follows writtenFile, not this name: a
                                // 16-bit render is saved as PNG even though the label says jpg.
                                val fileName = model.getFormattedExportName("jpg")
                                model.saveFileToGalleryAsImage(writtenFile, fileName)
                            } else if (isMFSR) {
                                // Auto-save is off — leave the render in the Save tab so it
                                // is still one tap away instead of silently disappearing.
                                showToast("Super-resolution image ready — tap Save to keep it")
                            }
                            
                        } else if (model.isProcessingCancelled.value != true) {
                            showToast("Processing Failed")
                        }
                    }
                } else {
                var renderCompleted = false
                try {
                    val success = processor.startRendering(outputFile.absolutePath)
                    renderCompleted = success
                    // THE FIX: Read cancellation from model (live check)
                    val wasCancelled = model.isProcessingCancelled.value == true

                    // The render loop and the retime are done; the job is still open
                    // because the audio mux and the gallery write below are part of
                    // the same work and are what the old ETA reported as "0s left".
                    val etaJob = processor.etaJob
                    etaJob?.beginPhase(com.dan.timelapse.eta.EtaPhase.MUX, 1.0)
                    
                    withContext(Dispatchers.Main) {
                        dismissGenerateDialog()
                        model.isProcessing.postValue(false)

                        // THE FIX: Handle partial renders (cancelled but had frames)
                        if (wasCancelled) {
                            // A partial file is for viewing only; never let Save/Share
                            // pick up the half-finished output.
                            model.setSaveReady(false)
                        }
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
                            // A previous MFSR/still render is no longer what the user is
                            // looking at; don't let Save Image write it.
                            model.setProcessedImageUri(null)
                            
                            if (model.preserveAudio.value == true) {
                                val input = model.framesInput.value
                                if (input is com.dan.timelapse.framesinput.VideoFramesInput) {
                                    val sourceUri = input.videoUri
                                    if (sourceUri != null) {
                                        // showToast("Syncing audio to time-lapse...")
                                        val speedMultiplier = model.speedMultiplier.value ?: 1
                                        val outputFps = (model.outputFps.value ?: 30).coerceAtLeast(1)
                                        val sourceFps = input.fps.coerceAtLeast(1)

                                        // Derive the audio stretch from the frame counts that
                                        // were ACTUALLY encoded. speedMultiplier alone is wrong
                                        // for stacking effects: they consume every frame and
                                        // produce one output per input, so nothing is skipped.
                                        val renderedFrames = model.lastGeneratedFrameCount
                                        val trimStartMs = model.getTrimStartMs()
                                        var trimEndMs = model.getTrimEndMs()
                                        if (trimEndMs == Long.MAX_VALUE || trimEndMs <= trimStartMs) {
                                            trimEndMs = input.size.toLong() * 1000L / sourceFps
                                        }
                                        val sourceDurationMs = (trimEndMs - trimStartMs).coerceAtLeast(1L)
                                        val renderedDurationMs = renderedFrames * 1000.0 / outputFps
                                        val speedFactor = if (renderedFrames > 0 && renderedDurationMs > 0.0) {
                                            (sourceDurationMs / renderedDurationMs).toFloat()
                                        } else {
                                            speedMultiplier.toFloat() * outputFps.toFloat() / sourceFps.toFloat()
                                        }.coerceIn(0.01f, 100f)

                                        val muxSuccess = com.dan.timelapse.video.VideoTools.muxTimeScaledAudio(
                                            this@MainActivity,
                                            sourceUri,
                                            outputFile,
                                            speedFactor,
                                            trimStartMs,
                                            trimEndMs
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

                            // Mux is finished; the gallery write is the last real phase.
                            etaJob?.beginPhase(com.dan.timelapse.eta.EtaPhase.SAVE, 1.0)
                            if (!suppressAutoSave && model.autoSaveAfterRender.value == true) {
                                val fileName = model.getFormattedExportName("mp4")
                                model.saveVideoToGallery(fileName)
                            }
                        } else if (!wasCancelled) {
                            // Nothing was published: lastGeneratedFilePath already
                            // points at the new (deleted) file, so Save/Share must not
                            // pretend there is something to export.
                            model.setSaveReady(false)
                            model.lastGeneratedFilePath = null
                            // The renderer knows exactly why it refused (a clip that
                            // would produce a single 0-second frame, a degenerate
                            // container); "Processing Failed" hid that.
                            showToastLong(processor.failureMessage ?: "Processing Failed")
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    withContext(Dispatchers.Main) {
                        dismissGenerateDialog()
                        model.isProcessing.postValue(false)
                    }
                    processor.etaJob?.abandon()
                    throw e
                } finally {
                    // Only a complete, uncancelled render is a usable timing sample.
                    val job = processor.etaJob
                    if (job != null) {
                        if (renderCompleted && model.isProcessingCancelled.value != true) {
                            job.finish(true)
                            Log.i("EtaEngine", "video done: ${job.summaryLine()}")
                        } else {
                            job.abandon()
                            Log.i("EtaEngine", "video abandoned: completed=$renderCompleted " +
                                    "cancelled=${model.isProcessingCancelled.value}")
                        }
                    }
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

                val pickedFrameIndex = model.resolveSelectedSourceFrameIndex()
                val targetFrameIndex = pickedFrameIndex
                    ?: (input.size / 2).coerceIn(0, (input.size - 1).coerceAtLeast(0))

                // 1. Setup the Dialog UI
                val dialogView = layoutInflater.inflate(R.layout.dialog_generate, null)
                val fullResTitle = dialogView.findViewById<android.widget.TextView>(R.id.dialogTitle)
                fullResTitle?.text = "Rendering Full-Res Image"
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
                var fullResEtaJob: com.dan.timelapse.eta.EtaJob? = null
                lifecycleScope.launch(Dispatchers.IO) {
                    var success = false
                    val tempImageFile = File(cacheDir, "full_res_render_${System.currentTimeMillis()}.jpg")
                    // The MFSR processor redirects a 16-bit result to a sibling .png (a
                    // JPEG target clips CV_16U to white). Everything below must use the
                    // file it actually wrote, not the requested path.
                    var outputImageFile = tempImageFile
                    
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
                            mfsrProcessor.cancelCheck = { model.isProcessingCancelled.value == true }

                            // Learned ETA for the still export, using the same phase
                            // plan as the main Super Resolution render.
                            val stillScanWindow = minOf(
                                input.size,
                                com.dan.timelapse.mfsr.MFSRProcessor.SCAN_WINDOW_RADIUS * 2 + 1
                            ).coerceAtLeast(1)
                            val stillOutputW = (input.width * adaptiveScale).toInt()
                            val stillOutputH = (input.height * adaptiveScale).toInt()
                            val stillEtaJob = try {
                                Eta.startJob(
                                    this@MainActivity,
                                    EtaSpecs.mfsrRender(
                                        signature = EtaApp.mfsrSignature(
                                            this@MainActivity, input.width, input.height,
                                            stillScanWindow, adaptiveScale
                                        ),
                                        scanFrames = stillScanWindow,
                                        selectedFrames = minOf(
                                            stillScanWindow,
                                            if (input.width.toLong() * input.height > 1920L * 1080L) 50 else 120
                                        ),
                                        inputWidth = input.width,
                                        inputHeight = input.height,
                                        outputWidth = stillOutputW,
                                        outputHeight = stillOutputH,
                                        temperatureC = EtaApp.batteryTemperatureC(this@MainActivity)
                                    )
                                )
                            } catch (e: Exception) { null }
                            mfsrProcessor.etaJob = stillEtaJob
                            fullResEtaJob = stillEtaJob
                            runOnUiThread {
                                fullResTitle?.text = com.dan.timelapse.eta.EtaDialogText.titleForMs(
                                    "Rendering Full-Res Image",
                                    stillEtaJob?.remainingMs() ?: -1L
                                )
                            }

                            mfsrProcessor.onProgress = { current, total, status ->
                                val remaining = stillEtaJob?.remainingMs() ?: -1L
                                runOnUiThread {
                                    val percent = if (total > 0) (current * 100 / total) else 0
                                    progressBar?.progress = percent
                                    progressText?.text = com.dan.timelapse.eta.EtaDialogText
                                        .statusWithEta(status, remaining)
                                    model.updateRenderProgress(percent)
                                }
                            }
                            
                            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                            val wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "TimeLapse::FullResRender")
                            wakeLock.acquire(30 * 60 * 1000L)
                            try {
                                success = mfsrProcessor.process(tempImageFile)
                                mfsrProcessor.writtenOutputFile?.let { outputImageFile = it }
                            } finally {
                                if (wakeLock.isHeld) wakeLock.release()
                            }
                        } else {
                            // PATH B: Use VideoProcessor to render selected effect at high resolution
                            val processor = com.dan.timelapse.VideoProcessor(model, this@MainActivity, input.width, input.height)

                            // A still is a short job, so the learned number is mostly
                            // useful as a "how long will this take" before the first
                            // frame lands. It is trained like every other phase.
                            val stillUnits = com.dan.timelapse.eta.EtaUnits.stillUnits(
                                input.width, input.height
                            )
                            val stillEtaJob = try {
                                Eta.startJob(
                                    this@MainActivity,
                                    EtaSpecs.stillRender(
                                        signature = EtaApp.stillSignature(
                                            this@MainActivity, currentEffect.name,
                                            input.width, input.height
                                        ),
                                        outWidth = input.width,
                                        outHeight = input.height,
                                        temperatureC = EtaApp.batteryTemperatureC(this@MainActivity)
                                    )
                                )
                            } catch (e: Exception) { null }
                            fullResEtaJob = stillEtaJob
                            stillEtaJob?.beginPhase(EtaPhase.RENDER, stillUnits)
                            runOnUiThread {
                                fullResTitle?.text = com.dan.timelapse.eta.EtaDialogText.titleForMs(
                                    "Rendering Full-Res Image",
                                    stillEtaJob?.remainingMs() ?: -1L
                                )
                            }

                            processor.onProgress = { current, total ->
                                val percent = if (total > 0) (current * 100 / total) else 0
                                val remaining = stillEtaJob?.remainingMs() ?: -1L
                                runOnUiThread {
                                    progressBar?.progress = percent
                                    progressText?.text = com.dan.timelapse.eta.EtaDialogText
                                        .statusWithEta("Processing... $percent%", remaining)
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
                        val job = fullResEtaJob
                        if (job != null) {
                            if (success) job.finish(true) else job.abandon()
                            Log.i("EtaEngine", "still done: success=$success ${job.summaryLine()}")
                        }
                        withContext(Dispatchers.Main) {
                            model.renderProgress.removeObserver(progressObserver)
                            fullResDialog.dismiss()
                            
                            if (success && outputImageFile.exists()) {
                                model.setProcessedImageUri(Uri.fromFile(outputImageFile))
                                
                                if (model.pendingFullResAction == "SAVE") {
                                    // This branch IS the Save button. It must save regardless of
                                    // the unrelated "auto-save after Generate" preference.
                                    val imageName = model.getFormattedExportName("jpg")
                                    model.saveFileToGalleryAsImage(outputImageFile, imageName)

                                    model.experimentalRefinedUri?.path?.let { refinedPath ->
                                        val refinedFile = java.io.File(refinedPath)
                                        if (refinedFile.exists()) {
                                            val refinedName = imageName.replace(".jpg", "_REFINED.jpg")
                                            model.saveFileToGalleryAsImage(refinedFile, refinedName)
                                            model.experimentalRefinedUri = null
                                        }
                                    }
                                    showToast("Full-res image saved to Pictures/TimeLapse")
                                } else if (model.pendingFullResAction == "SHARE") {
                                    shareFileUri(outputImageFile)
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
                
                activeMediaPlayer = null
                
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
                // The string is built by a unit-tested helper: the title is the only
                // place the estimate reaches the user.
                val title = EtaDialogText.title(prefix, timeEstimate)
                dialogTitle?.text = title
                Log.d("EtaEngine", "dialog title: $title")
            }
        }
        
        binding.emptyStateOverlay.visibility = if (model.framesInput.value == null) View.VISIBLE else View.GONE
        
        // The whole-media surface (empty state included) is handled by
        // binding.videoContainer.onTap -> onVideoSurfaceTapped(). Click listeners here would be
        // dead code because ZoomableFrameLayout intercepts every touch.
    }

    override fun onPause() {
        super.onPause()
        // Never persist a share session: its grant is one-shot and dies with this task.
        if (model.framesInput.value != null && !isSharedMediaSession) {
            model.saveSession(this)
        }
        trimWatchdogHandler.removeCallbacks(trimWatchdogRunnable)
    }

    /**
     * A second share while the app is already running reuses this instance
     * (`android:launchMode="singleTop"`), so the payload has to be handled here too.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val payload = parseShareIntent(intent) ?: return
        model.shareIntentConsumed = true
        loadingJob?.cancel()
        isFrameSelectionMode = false
        loadSharedPayload(payload)
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

    override fun onPostResume() {
        super.onPostResume()
        // The mask editor disables zoom/pan and swaps the zoom listener, while
        // isMaskEditMode is a plain field (not persisted). An activity recreated while
        // the editor was open could therefore come back with a dead media surface.
        //
        // This check MUST live here, not in onCreate: in onCreate the container's flag
        // was always still true (MaskEditFragment's onViewCreated, which disables zoom,
        // has not run yet), so the old onCreate block could never fire.
        if (!binding.videoContainer.isZoomEnabled) {
            Log.w("TimeLapse", "Media surface restored with zoom disabled — restoring")
            isMaskEditMode = false
            binding.maskOverlay.visibility = View.GONE
            binding.bottomNavigation.visibility = View.VISIBLE
            restoreMediaSurfaceAfterMaskEditor()
            updateDimmer()
        }
    }

    private fun initGlobalGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                return false
            }

            // Single taps on the media are owned by binding.videoContainer.onTap
            // (onVideoSurfaceTapped). Handling them here too would double-fire, and the
            // container already knows whether the gesture was a tap or the start of one.

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
        // Never dim the canvas while the user is painting a mask: the 10% black scrim
        // sits ABOVE maskOverlay, so it both hides true colours and (with the old
        // click listener gone) makes a stray tap paint a permanent dot the user
        // cannot see clearly.
        if (isMaskEditMode) {
            binding.dimmerView.visibility = View.GONE
            return
        }
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

                activeMediaPlayer = null

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
                    if (input is VideoFramesInput) { activeMediaPlayer = null; binding.videoView.setVideoURI(input.videoUri) }
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
                    activeMediaPlayer = null
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
        isSharedMediaSession = false
        val currentUri = model.framesInput.value?.videoUri
        if (currentUri != null && currentUri.toString() == uri.toString()) {
            return
        }
        
        persistUriPermission(uri)

        showLoadingDialog("Loading video...")
        
        loadingJob = lifecycleScope.launch {
            try {
                val input = withContext(Dispatchers.IO) {
                    VideoFramesInput(UriFile.fromSingleUri(this@MainActivity, uri)!!)
                }
                activeMediaPlayer = null
                model.setFramesInput(input)
                // Save only now: saveSession() reads framesInput.videoUri, so saving before
                // setFramesInput() persisted a null URI and the session never restored.
                model.saveSession(this@MainActivity)
                binding.videoView.isVisible = true
                binding.mainVideoView.isVisible = false
                applyVideoRotation(model.rotation.value ?: 0)
                applyVideoAspectRatio(model.transformState.value?.getAspectRatio())
                
                activeMediaPlayer = null
                
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
        isSharedMediaSession = false
        uris.forEach { persistUriPermission(it) }

        showLoadingDialog("Loading ${uris.size} images...")

        loadingJob = lifecycleScope.launch {
            try {
                val input = withContext(Dispatchers.IO) {
                    ImagesFramesInput(
                        uris.map { UriFile.fromSingleUri(this@MainActivity, it)!! },
                        model,
                        onProgress = { current, total, status ->
                            // Probing a big batch is the slowest part of opening
                            // images; without this the dialog sat on "Please wait..."
                            runOnUiThread { updateLoadingDialog(current, total, status) }
                        }
                    )
                }
                activeMediaPlayer = null
                model.setFramesInput(input)
                // After setFramesInput, so lastMediaUri is the first image, not null.
                model.saveSession(this@MainActivity)
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

    // ───────────────────────── Share-sheet intake ─────────────────────────
    //
    // The manifest has advertised ACTION_SEND / SEND_MULTIPLE for a long time, but nothing
    // ever read the incoming intent, and the picker path required DocumentFile columns that
    // Google Photos' cloud provider does not answer. Both are fixed here: the payload is read
    // from EXTRA_STREAM *or* ClipData, and image bytes are copied to cache before loading.

    /**
     * Parse a share/view intent. Returns null for a normal launcher start or an unsupported
     * payload (text/plain, PDF, ...), so callers can fall through to the normal startup.
     */
    private fun parseShareIntent(intent: Intent?): SharePayload? {
        if (intent == null) return null
        val action = intent.action ?: return null
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE &&
            action != Intent.ACTION_VIEW
        ) return null

        // EXTRA_STREAM is a Uri for SEND, a ParcelableArrayList<Uri> for SEND_MULTIPLE, and
        // some senders omit it entirely and rely on ClipData. Read it as Any and branch:
        // getParcelableExtra / getParcelableArrayListExtra each fail for the other shape.
        val raw = intent.extras?.get(Intent.EXTRA_STREAM)
        val single = (raw as? Uri)?.toString()
            ?: if (action == Intent.ACTION_VIEW) intent.data?.toString() else null
        val list = (raw as? List<*>)?.filterIsInstance<Uri>()?.map { it.toString() }
        val clip = intent.clipData?.let { c ->
            (0 until c.itemCount).map { c.getItemAt(it).uri.toString() }
        }

        val merged = ShareIntentRules.mergeStreams(single, list, clip).map(Uri::parse)
        if (merged.isEmpty()) {
            Log.w("TimeLapse", "Share intent had no stream: action=$action type=${intent.type}")
            return null
        }

        val first = merged.first()
        val isDirectory = merged.size == 1 && try {
            DocumentsContract.isTreeUri(first) ||
                contentResolver.getType(first) == DocumentsContract.Document.MIME_TYPE_DIR
        } catch (e: Exception) {
            false
        }
        val resolved = merged.take(4).map {
            try { contentResolver.getType(it) } catch (e: Exception) { null }
        }
        val mime = ShareIntentRules.pickMime(intent.type, resolved)
        val kind = ShareIntentRules.kindFor(mime, isDirectory)
        if (kind == null) {
            // A MIME-only router drops shares from providers that answer nothing useful
            // (application/octet-stream or */* for every item). Those are worth opening by
            // sniffing the bytes; a genuinely unsupported type (PDF, text) is not.
            val opaque = mime == null || mime == "*/*" ||
                mime == "application/octet-stream" || mime.startsWith("application/")
            if (!opaque) {
                Log.w("TimeLapse", "Unsupported share: action=$action type=${intent.type} mime=$mime")
                return null
            }
            Log.i("TimeLapse", "Share intake: opaque mime=$mime count=${merged.size} — sniffing bytes")
            return SharePayload(null, merged, mime)
        }
        Log.i("TimeLapse", "Share intake: kind=$kind count=${merged.size} mime=$mime")
        return SharePayload(kind, merged, mime)
    }

    private fun loadSharedPayload(payload: SharePayload) {
        if (model.isProcessing.value == true) {
            showToast("Finish or cancel the current render before sharing new media")
            return
        }
        when (payload.kind) {
            ShareIntentRules.Kind.VIDEO -> {
                if (payload.uris.size > 1) {
                    // The sheet can hand over several videos; this app opens one at a time.
                    showToast("TimeLapse opens one video at a time — loading the first")
                }
                loadSharedVideo(payload.uris.first())
            }
            ShareIntentRules.Kind.IMAGES -> loadSharedImages(payload.uris)
            ShareIntentRules.Kind.FOLDER -> onFolderSelected(payload.uris.first())
            null -> loadSharedBySniffing(payload.uris)
        }
    }

    /**
     * The sender declared no usable MIME. Read 16 bytes of the first item and route by
     * content, which is the difference between "appears in the share sheet and does nothing"
     * and a working share for opaque cloud providers.
     */
    private fun loadSharedBySniffing(uris: List<Uri>) {
        showLoadingDialog("Opening shared file...")
        loadingJob = lifecycleScope.launch {
            val kind = withContext(Dispatchers.IO) {
                try {
                    shareMaterializer.probeKind(uris.first())
                } catch (e: Exception) {
                    Log.w("TimeLapse", "Sniffing shared item failed", e)
                    null
                }
            }
            dismissLoadingDialog()
            if (model.isProcessing.value == true) {
                showToast("Finish or cancel the current render before sharing new media")
                return@launch
            }
            when (kind) {
                com.dan.timelapse.utils.MediaSniffer.Kind.IMAGE -> loadSharedImages(uris)
                com.dan.timelapse.utils.MediaSniffer.Kind.VIDEO -> loadSharedVideo(uris.first())
                null -> {
                    Log.w("TimeLapse", "Shared item is not a recognised image or video")
                    showToast("TimeLapse can't open this file type")
                }
            }
        }
    }

    /**
     * Copy every shared image into the cache first, then load from those files. That is one
     * fetch per cloud-only photo instead of one per probe/decode, and a grant expiring
     * mid-render can no longer break anything.
     */
    private fun loadSharedImages(uris: List<Uri>) {
        val label = if (uris.size == 1) "image" else "images"
        showLoadingDialog("Loading ${uris.size} shared $label...")
        loadingJob = lifecycleScope.launch {
            try {
                var skipped = 0
                val input = withContext(Dispatchers.IO) {
                    val dir = shareMaterializer.newSessionDir()
                    val files = ArrayList<UriFile>(uris.size)
                    uris.forEachIndexed { index, uri ->
                        runOnUiThread {
                            updateLoadingDialog(
                                index, uris.size,
                                "Copying shared $label (${index + 1}/${uris.size})..."
                            )
                        }
                        // One unreadable item must not throw away the other nine. A cloud
                        // gallery can hand over a photo whose download fails while the rest
                        // are fine; skip it and say so.
                        try {
                            files.add(shareMaterializer.materializeImage(uri, dir, index))
                        } catch (e: Exception) {
                            skipped++
                            Log.w("TimeLapse", "Skipping unreadable shared item ${index + 1}", e)
                        }
                    }
                    if (files.isEmpty()) {
                        throw java.io.IOException("None of the ${uris.size} shared items could be read")
                    }
                    ImagesFramesInput(files, model)
                }
                activeMediaPlayer = null
                isSharedMediaSession = true
                model.setFramesInput(input)
                model.setLastSourceType(MainViewModel.SourceType.IMAGES)
                binding.videoView.isVisible = false
                binding.mainVideoView.isVisible = true
                binding.videoSeekBar.max = (input.size - 1).coerceAtLeast(0)
                if (!isTrayVisible) binding.videoSeekBar.visibility = View.VISIBLE
                startPreviewLoop(10)
                if (skipped == 0) {
                    showToast("Loaded ${input.size} shared $label")
                } else {
                    showToast(
                        "Loaded ${input.size} of ${input.size + skipped} shared $label " +
                            "($skipped couldn't be read)"
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("TimeLapse", "Shared image load failed", e)
                isSharedMediaSession = false
                showToast("Couldn't load the shared $label")
            } finally {
                dismissLoadingDialog()
            }
        }
    }

    private fun loadSharedVideo(uri: Uri) {
        showLoadingDialog("Loading shared video...")
        loadingJob = lifecycleScope.launch {
            try {
                val input = withContext(Dispatchers.IO) {
                    VideoFramesInput(shareMaterializer.uriFileFor(uri, 0, "mp4"))
                }
                if (input.size <= 0) throw IllegalStateException("Shared video has no frames")
                activeMediaPlayer = null
                isSharedMediaSession = true
                model.setFramesInput(input)
                model.setLastSourceType(MainViewModel.SourceType.VIDEO)
                binding.videoView.isVisible = true
                binding.mainVideoView.isVisible = false
                applyVideoRotation(model.rotation.value ?: 0)
                applyVideoAspectRatio(model.transformState.value?.getAspectRatio())
                binding.videoView.setVideoURI(input.videoUri)
                binding.videoView.setOnPreparedListener { mp ->
                    mp.isLooping = true
                    binding.videoView.start()
                    activeMediaPlayer = mp
                    if (model.preserveAudio.value != true) mp.setVolume(0f, 0f)
                    val actualDuration = mp.duration
                    if (actualDuration > 0) binding.videoSeekBar.max = actualDuration
                    videoDuration = actualDuration.toLong()
                    binding.videoSeekBar.progress = 0
                    if (!isTrayVisible) binding.videoSeekBar.visibility = View.VISIBLE
                    startPreviewLoop(10)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("TimeLapse", "Shared video load failed", e)
                isSharedMediaSession = false
                showToast("Couldn't load the shared video")
            } finally {
                dismissLoadingDialog()
            }
        }
    }

    private fun onFolderSelected(uri: Uri) {
        isSharedMediaSession = false
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
                activeMediaPlayer = null
                model.setFramesInput(input)
                // Folder loads never persisted a session at all, so a folder selection was
                // lost on every restart.
                model.saveSession(this@MainActivity)
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
        // A previous job that never got dismissed must not leak into this one.
        loadingEtaJob?.abandon()
        loadingEtaJob = null
        loadingEtaPhase = null
        loadingEtaLastFraction = 0.0

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
        // Several distinct passes report into the same dialog (list/filter a
        // folder, copy shared files, probe image resolutions). They have very
        // different per-item costs, so the phase is chosen from the status rather
        // than assumed. The mapping is unit-tested.
        val phase = EtaDialogText.loadingPhaseFor(status)

        if (total > 0) {
            var job = loadingEtaJob
            if (job == null) {
                job = try {
                    Eta.startJob(
                        this,
                        EtaSpecs.loadMedia(
                            signature = EtaApp.loadSignature(this, total),
                            items = total,
                            temperatureC = EtaApp.batteryTemperatureC(this),
                            phase = phase
                        )
                    )
                } catch (e: Exception) {
                    null
                }
                loadingEtaJob = job
                loadingEtaPhase = phase
                job?.beginPhase(phase, total.toDouble())
            } else if (loadingEtaPhase != phase) {
                job.beginPhase(phase, total.toDouble())
                loadingEtaPhase = phase
            }
            job?.progress(current.toDouble())
            loadingEtaLastFraction = if (total > 0) current.toDouble() / total else 0.0
        }

        loadingDialog?.let { dialog ->
            val dialogText = com.dan.timelapse.eta.EtaDialogText.statusWithEta(
                status, loadingEtaJob?.remainingMs() ?: -1L
            )
            dialog.findViewById<TextView>(R.id.progressText)?.text = dialogText
            // Same rationale as the generate dialog's title log: the only way to
            // verify from a device that the estimate reached the user.
            if (dialogText != lastLoadingDialogLog) {
                lastLoadingDialogLog = dialogText
                Log.d("EtaEngine", "loading dialog: $dialogText")
            }
            if (total > 0) {
                val progressBar = dialog.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.progressBar)
                progressBar?.isIndeterminate = false
                progressBar?.progress = (current * 100 / total)
            }
        }
    }
    
    private fun dismissLoadingDialog() {
        loadingEtaJob?.let { job ->
            // A load cancelled halfway must not teach a rate for the items it
            // never reached: duration / full unit count would look slow forever.
            if (loadingEtaLastFraction >= 0.8) {
                job.finish(true)
                Log.i("EtaEngine", "load done: ${job.summaryLine()}")
            } else {
                job.abandon()
            }
        }
        loadingEtaJob = null
        loadingEtaPhase = null
        loadingEtaLastFraction = 0.0
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
        // Remove ONLY the session. This used to be prefs.edit().clear(), which also
        // destroyed the per-URI trim memory whenever a saved session could not be
        // validated (e.g. every folder session before the tree-URI fix).
        model.clearPersistedSession(this)
        model.resetProcessingState()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            binding.videoView.isVisible = false
            binding.mainVideoView.isVisible = false
        }
    }

    /**
     * Single tap on the media surface.
     *
     * Deliberate matrix (the container consumes every touch, so this is the only tap owner):
     *  - a render is running           -> do nothing (never stack a decoder on top of it)
     *  - mask canvas / frame picker    -> do nothing (those screens own their gestures)
     *  - nothing loaded, tray hidden   -> open the Media sheet (offers video + image sequence)
     *  - nothing loaded, Media showing -> run its primary action (the file picker), so a tap
     *                                     never feels dead
     *  - sheet is open                 -> dismiss it, like any bottom sheet
     *  - otherwise                     -> play / pause
     */
    private fun onVideoSurfaceTapped() {
        if (model.isProcessing.value == true) return
        if (isMaskEditMode || isFrameSelectionMode) return

        if (model.framesInput.value == null) {
            val sourceSheetShowing =
                isTrayVisible && binding.bottomNavigation.selectedItemId == R.id.nav_source
            if (sourceSheetShowing) {
                model.setLastSourceType(MainViewModel.SourceType.VIDEO)
                openPicker()
            } else {
                showSourceTray()
            }
            return
        }

        if (isTrayVisible) {
            hideTray()
            return
        }

        togglePlayback()
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
            try { binding.videoView.start() } catch (e: Exception) { }
            return
        }

        if (input is VideoFramesInput) {
            val trimRange = model.trimRange.value ?: Pair(1f, input.size.toFloat())
            val startFrame = (trimRange.first - 1).toInt().coerceIn(0, input.size - 1)
            val startMs = (startFrame * 1000 / input.fps).toLong()

            previewLoopTarget = loopCount
            previewLoopsDone = 0

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
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        activeMediaPlayer?.seekTo(startMs, MediaPlayer.SEEK_CLOSEST)
                    } else {
                        binding.videoView.seekTo(startMs.toInt())
                    }
                } catch (e: IllegalStateException) {
                    Log.w("MainActivity", "Ignored seekTo in startPreviewLoop: MediaPlayer not ready", e)
                } catch (e: Exception) {
                    Log.w("MainActivity", "Seek error in startPreviewLoop", e)
                }
                try {
                    binding.videoView.start()
                } catch (e: Exception) {
                    Log.w("MainActivity", "Ignored start in startPreviewLoop", e)
                }
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

    private fun handlePreviewLoopEnd(startMs: Long): Boolean {
        previewLoopsDone++
        val finiteTarget = previewLoopTarget > 0 && previewLoopTarget != Int.MAX_VALUE
        if (finiteTarget && previewLoopsDone >= previewLoopTarget) {
            try { binding.videoView.pause() } catch (e: Exception) { }
            model.isPlaying.postValue(false)
            trimWatchdogHandler.removeCallbacks(trimWatchdogRunnable)
            return true
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                activeMediaPlayer?.seekTo(startMs.toLong(), MediaPlayer.SEEK_CLOSEST)
            } else {
                binding.videoView.seekTo(startMs.toInt())
            }
        } catch (e: IllegalStateException) {
            Log.w("MainActivity", "Ignored seekTo in watchdog", e)
        } catch (e: Exception) {
            Log.w("MainActivity", "Seek error in watchdog", e)
        }
        try {
            binding.videoView.start()
        } catch (e: Exception) {
            // Swallow state desyncs
        }
        return false
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

    /**
     * Cancels the preview loop and then WAITS for its body to actually finish.
     *
     * `Job.cancel()` is cooperative: the loop only observes it at a suspension point, so a
     * coroutine that is already inside `ImagesFramesInput.getFrame()` — a blocking native
     * decode of a 16-bit TIFF/PNG — keeps running after `stopPreviewLoop()` returns. The
     * render used to start in exactly that window, leaving two threads decoding the same
     * sequence through OpenCV at once. That is the environment in which `cvtColor` once
     * reported a heap pointer (`0x5FD2xxxx`) as its output channel count.
     *
     * MUST be called from a background dispatcher: the loop's last statement is a
     * `withContext(Dispatchers.Main)`, so joining while holding Main would deadlock.
     */
    suspend fun stopPreviewLoopAndJoin() {
        val job = playbackJob
        withContext(Dispatchers.Main) { stopPreviewLoop() }
        if (job != null && !job.isCompleted) {
            // A decode of a large frame can legitimately take a while; do not wait forever.
            val joined = kotlinx.coroutines.withTimeoutOrNull(PREVIEW_JOIN_TIMEOUT_MS) { job.join() }
            if (joined == null) {
                Log.w("TimeLapse", "Preview loop still running after ${PREVIEW_JOIN_TIMEOUT_MS}ms; " +
                        "starting the render anyway")
            } else {
                Log.d("TimeLapse", "Preview loop joined before render")
            }
        }
        playbackJob = null
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

        val processedUri = model.processedVideoUri.value
        val input = model.framesInput.value
        val isImageInput = input is com.dan.timelapse.framesinput.ImagesFramesInput
        val usesRenderedTimeline = processedUri != null && !isImageInput

        // Image sequences are previewed on the image view; videos use the player.
        binding.mainVideoView.isVisible = isImageInput
        binding.videoView.isVisible = !isImageInput

        // Frame-index mode (images and videos with no rendered preview).
        if (input != null && !usesRenderedTimeline) {
            binding.fullScreenFrameSlider.valueFrom = 0f
            val maxValue = (input.size - 1).toFloat().coerceAtLeast(1f)
            binding.fullScreenFrameSlider.valueTo = maxValue
            binding.fullScreenFrameSlider.stepSize = 1.0f  // Force integer frame indices

            // Force slider to refresh its internal state
            binding.fullScreenFrameSlider.isEnabled = false
            binding.fullScreenFrameSlider.isEnabled = true

            val savedIndex = model.selectedFramePositionMs.value?.toInt()
            val savedPosition = savedIndex?.toFloat() ?: maxValue
            binding.fullScreenFrameSlider.value = savedPosition.coerceIn(0f, maxValue)
            updateFramePreview(savedPosition.toInt())
            updateFrameInfoText(savedPosition.toLong())
        }

        if (input is com.dan.timelapse.framesinput.VideoFramesInput) {
            if (processedUri != null) {
                // Slider is in rendered-video time mode (milliseconds).
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
                    activeMediaPlayer = null
                    binding.videoView.setVideoURI(processedUri)
                }
            } else {
                // No rendered preview (e.g. after an MFSR still tore the player down).
                // Reattach the SOURCE video so the picker can seek frame by frame.
                binding.videoView.setOnPreparedListener { mp ->
                    mp.isLooping = true
                    activeMediaPlayer = mp
                    val fps = input.fps.coerceAtLeast(1)
                    val frameIndex = binding.fullScreenFrameSlider.value.toLong()
                    mp.seekTo((frameIndex * 1000L / fps).toInt())
                    mp.pause()
                    binding.videoView.setOnPreparedListener(null)
                }
                applyVideoRotation(model.rotation.value ?: 0)
                activeMediaPlayer = null
                binding.videoView.setVideoURI(input.videoUri)
            }
        }
    }
    
    private fun setupSliderForVideo(duration: Int) {
        if (duration <= 0) return
        binding.fullScreenFrameSlider.valueFrom = 0f
        binding.fullScreenFrameSlider.valueTo = duration.toFloat()
        binding.fullScreenFrameSlider.stepSize = 1.0f  // Force integer frame indices
        val savedIndex = model.selectedFramePositionMs.value?.toInt()
        val startPosition = if (savedIndex != null) {
            model.renderedPositionMsForSourceFrame(savedIndex).toFloat()
        } else {
            duration.toFloat()
        }
        binding.fullScreenFrameSlider.value = startPosition.coerceIn(0f, duration.toFloat())
        binding.videoView.seekTo(startPosition.toInt())
        updateFrameInfoText(startPosition.toLong())
    }
    
    private fun updateFrameInfoText(positionMs: Long) {
        val input = model.framesInput.value
        val totalFrames = input?.size ?: 1
        
        if (input is com.dan.timelapse.framesinput.ImagesFramesInput) {
            // Slider is in frame index mode
            val frameIndex = positionMs.toInt()
            binding.textFrameInfo.text = "Frame ${frameIndex + 1}/$totalFrames"
        } else if (model.processedVideoUri.value == null) {
            // No rendered preview yet: the slider is in SOURCE frame-index mode.
            val frameIndex = positionMs.toInt().coerceIn(0, (totalFrames - 1).coerceAtLeast(0))
            binding.textFrameInfo.text = "Frame ${frameIndex + 1}/$totalFrames"
        } else {
            // Slider is in rendered-video time mode (milliseconds)
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
        model.setSelectedFrameFromPicker(selectedPositionMs)
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
        } else if (input is com.dan.timelapse.framesinput.VideoFramesInput) {
            // Source frame-index mode: seek the original video to the matching time.
            val fps = input.fps.coerceAtLeast(1)
            safeSeekTo((frameIndex.toLong() * 1000L / fps).toInt())
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
        if (!videoPath.endsWith(".mp4", ignoreCase = true)) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoPath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0
            val positionUs = when {
                model.selectedFramePositionMs.value != null -> {
                    val sourceIndex = model.selectedFramePositionMs.value!!.toInt()
                    val renderedMs = model.renderedPositionMsForSourceFrame(sourceIndex)
                    val safeMs = renderedMs.coerceAtMost((durationMs - 100).coerceAtLeast(0))
                    safeMs * 1000
                }
                binding.videoView.isPlaying -> binding.videoView.currentPosition.toLong() * 1000
                else -> (durationMs * 1000) / 2
            }
            // OPTION_CLOSEST decodes the exact requested frame. OPTION_CLOSEST_SYNC
            // snaps to the nearest keyframe, which can be up to ~1s away in these
            // renders and saved the wrong moment.
            retriever.getFrameAtTime(positionUs, MediaMetadataRetriever.OPTION_CLOSEST)
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }
    
    fun shareVideo() {
        val videoPath = model.lastGeneratedFilePath ?: return
        // MFSR / still renders must never be shared as video/mp4.
        if (!videoPath.endsWith(".mp4", ignoreCase = true)) return
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

    /** For messages the user has to read and act on (render refusals). */
    fun showToastLong(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
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
            val grants = contentResolver.persistedUriPermissions
            // Video / single-document session: the exact grant is on the URI.
            grants.any { it.uri == uri && it.isReadPermission } ||
                // Folder session: the grant is on the tree, and the saved URI may be
                // either that tree or a document inside it.
                grants.any { it.isReadPermission && isUnderTreeGrant(uri, it.uri) } ||
                // Belt and braces: the platform may still consider the grant live even
                // if it is not listed in persistedUriPermissions (provider quirks).
                checkUriPermission(
                    uri,
                    android.os.Process.myPid(),
                    android.os.Process.myUid(),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    /**
     * True when [uri] is the tree [grant] itself or a document inside it.
     *
     * SAF document URIs are `/tree/<treeId>/document/<treeId>/<relative>`, so a plain
     * path prefix test does not work: the first path segment differs. The document id
     * (not the path) is what carries the relationship.
     */
    private fun isUnderTreeGrant(uri: Uri, grant: Uri): Boolean {
        if (uri == grant) return true
        if (uri.scheme != grant.scheme || uri.authority != grant.authority) return false
        val grantSegments = grant.pathSegments
        val uriSegments = uri.pathSegments
        val treeIndex = grantSegments.indexOf("tree")
        val docIndex = uriSegments.indexOf("document")
        if (treeIndex < 0 || treeIndex + 1 >= grantSegments.size) return false
        val treeId = grantSegments[treeIndex + 1]
        val documentId = if (docIndex >= 0 && docIndex + 1 < uriSegments.size) {
            uriSegments[docIndex + 1]
        } else {
            // The URI itself may be a tree URI from a different picker path.
            val otherTree = uriSegments.indexOf("tree")
            if (otherTree < 0 || otherTree + 1 >= uriSegments.size) return false
            uriSegments[otherTree + 1]
        }
        return documentId == treeId || documentId.startsWith("$treeId/")
    }

    fun onOpenLastSelection() {
        isSharedMediaSession = false
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
            Log.e("TimeLapse", "Video session restore failed — file not found ($uri)", e)
            withContext(Dispatchers.Main + NonCancellable) {
                clearSavedSession()
                showToast("Selected video file not found")
            }
            return
        } catch (e: IOException) {
            withContext(Dispatchers.Main + NonCancellable) { showToast("Storage temporarily unavailable") }
            return
        } catch (e: Exception) {
            Log.e("TimeLapse", "Video session restore failed ($uri)", e)
            withContext(Dispatchers.Main + NonCancellable) {
                clearSavedSession()
                showToast("Cannot access last file (permission may have been revoked)")
            }
            return
        }
        withContext(Dispatchers.Main) {
            try {
                activeMediaPlayer = null
                model.setFramesInput(inputObj)
                binding.videoView.isVisible = true
                binding.mainVideoView.isVisible = false
                applyVideoRotation(model.rotation.value ?: 0)
                activeMediaPlayer = null
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
        // A multi-select session stores every child URI; a single pick (or an older
        // session) only has the one. Restoring just `uri` reopened 50 photos as 1 frame.
        val restoreUris = model.lastMediaUris.value?.takeIf { it.isNotEmpty() } ?: listOf(uri)

        val inputObj: ImagesFramesInput
        try {
            // UriFile.fromSingleUri runs a content query per URI and the constructor
            // probes up to 100 frames (stream opens + bounds decodes). All of that used
            // to run inside withContext(Main): a multi-second main-thread block, i.e. an
            // ANR risk on a slow DocumentsProvider.
            inputObj = withContext(Dispatchers.IO) {
                val uriFiles = restoreUris.map { u ->
                    UriFile.fromSingleUri(this@MainActivity, u)
                        ?: throw FileNotFoundException("Image URI not found: $u")
                }
                ImagesFramesInput(uriFiles, model)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: FileNotFoundException) {
            Log.e("TimeLapse", "Image session restore failed — file not found ($uri)", e)
            clearSavedSession()
            withContext(Dispatchers.Main + NonCancellable) { showToast("Image file not found") }
            return
        } catch (e: IOException) {
            withContext(Dispatchers.Main + NonCancellable) {
                showToast("Storage temporarily unavailable")
            }
            return
        } catch (e: Exception) {
            Log.e("TimeLapse", "Image session restore failed ($uri)", e)
            clearSavedSession()
            withContext(Dispatchers.Main + NonCancellable) { showToast("Failed to restore image session") }
            return
        }

        withContext(Dispatchers.Main) {
            activeMediaPlayer = null
            model.setFramesInput(inputObj)
            binding.videoView.isVisible = false
            binding.mainVideoView.isVisible = true
            binding.videoSeekBar.max = inputObj.size - 1
            if (!isTrayVisible) {
                binding.videoSeekBar.visibility = View.VISIBLE
            }
            if (restoreUris.size > 1) {
                showToast("Restored ${inputObj.size} images")
            }
            startPreviewLoop(10)
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
                
                // Everything below touches LiveData and Views: it MUST run on the main
                // thread. Running it on Dispatchers.IO threw
                // "Cannot invoke setValue on a background thread", which dropped into
                // the catch below, cleared the session and made folder restore
                // impossible (the restore always "failed").
                withContext(Dispatchers.Main) {
                    activeMediaPlayer = null
                    model.setFramesInput(inputObj)
                    binding.videoView.isVisible = false
                    binding.mainVideoView.isVisible = true
                    binding.videoSeekBar.max = inputObj.size - 1
                    if (!isTrayVisible) {
                        binding.videoSeekBar.visibility = View.VISIBLE
                    }
                    showToast("Restored: ${inputObj.name}")
                    startPreviewLoop(10)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: FileNotFoundException) {
                Log.e("TimeLapse", "Folder session restore: folder missing ($uri)", e)
                withContext(Dispatchers.Main + NonCancellable) {
                    dismissLoadingDialog()
                    clearSavedSession()
                    showToast("Folder not found — session cleared")
                }
            } catch (e: IOException) {
                Log.w("TimeLapse", "Folder session restore: storage unavailable ($uri)", e)
                withContext(Dispatchers.Main + NonCancellable) {
                    dismissLoadingDialog()
                    showToast("Storage temporarily unavailable")
                }
            } catch (e: Exception) {
                Log.e("TimeLapse", "Folder session restore failed ($uri)", e)
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
        // The dimmer is force-hidden in mask mode (see updateDimmer); call it here so
        // the scrim does not linger over the canvas after the tray opened it.
        updateDimmer()
        showTray()
    }
    
    fun closeMaskEditor() {
        restoreMediaSurfaceAfterMaskEditor()
        isMaskEditMode = false
        binding.bottomNavigation.visibility = View.VISIBLE
        binding.maskOverlay.visibility = View.GONE
        showSourceTray()
        // showTray() returns early when the tray is already visible (it is: the mask
        // editor opened it), so nothing would re-show the scrim. Without this the tray
        // stayed open over un-dimmed media until it was closed and reopened.
        updateDimmer()
    }
    
    fun closeMaskEditorToTransform() {
        restoreMediaSurfaceAfterMaskEditor()
        isMaskEditMode = false
        binding.bottomNavigation.visibility = View.VISIBLE
        binding.maskOverlay.visibility = View.GONE
        supportFragmentManager.beginTransaction()
            .replace(R.id.tray_content_holder, TransformFragment::class.java.getDeclaredConstructor().newInstance())
            .commitNow()
        binding.bottomNavigation.selectedItemId = R.id.nav_transform
        showTray()
        updateDimmer()
    }

    /**
     * MaskEditFragment disables zoom in DRAW/ERASE mode and replaces the zoom listener with its
     * own. Without this, zoom, pan and tap-to-play stay dead for the rest of the session after
     * the editor is closed.
     */
    private fun restoreMediaSurfaceAfterMaskEditor() {
        binding.videoContainer.isZoomEnabled = true
        binding.videoContainer.onZoomListener = videoZoomListener
        // The zoom was a drawing aid: return to a predictable fit-to-screen preview.
        binding.videoContainer.restoreZoom(1f, 0f, 0f)
        model.setZoom(1f, 0f, 0f)
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
            activeMediaPlayer = null
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
                activeMediaPlayer = null
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
                            val targetFps = model.previewFps.value ?: model.outputFps.value ?: 30
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
            activeMediaPlayer = null
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
        activeMediaPlayer = null
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
                activeMediaPlayer = null
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
                activeMediaPlayer = null
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
