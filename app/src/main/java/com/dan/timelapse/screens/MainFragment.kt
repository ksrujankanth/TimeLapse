package com.dan.timelapse.screens

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.view.*
import android.widget.AdapterView
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import com.dan.timelapse.*
import com.dan.timelapse.databinding.MainFragmentBinding
import com.dan.timelapse.filters.*
import com.dan.timelapse.framesinput.FramesInput
import com.dan.timelapse.framesinput.ImagesFramesInput
import com.dan.timelapse.framesinput.VideoFramesInput
import com.dan.timelapse.images.ImageWriter
import com.dan.timelapse.utils.OutputParams
import com.dan.timelapse.utils.Settings
import com.dan.timelapse.utils.UriFile
import com.dan.timelapse.video.VideoTools
import com.dan.timelapse.video.VideoWriter
import kotlinx.coroutines.*
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileNotFoundException
import java.lang.Integer.min
import android.content.Context
import android.provider.DocumentsContract
import android.util.Log
import android.util.LruCache
import android.widget.BaseAdapter
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.RangeSlider
import java.io.FileInputStream
import java.io.OutputStream
import kotlin.math.max


class MainFragment(activity: MainActivity) : AppFragment(activity) {
    private var wakeLock: PowerManager.WakeLock? = null
    companion object {
        private const val TAG = "MainFragment"
        private const val INTENT_OPEN_VIDEO = 2
        private const val INTENT_OPEN_IMAGES = 3
        private const val INTENT_OPEN_FOLDER = 4

        private const val TITLE_GENERATE = "Generate"
        private const val TITLE_SAVE = "Save"
        private const val TITLE_SAVE_RENDERED_FRAME = "Save last rendered frame"
        private const val TITLE_SAVE_FULL_RES = "Save full res image"

        private const val ORIENTATION_AUTO = 0
        private const val ORIENTATION_LANDSCAPE = 1
        private const val ORIENTATION_PORTRAIT = 2

        private val SPEED_VALUES = arrayOf(1, 2, 3, 4, 5, 6, 8, 10, 12, 15, 20, 24, 30, 45, 60, 90, 120, 180, 240, 300)

        enum class Effect {
            NONE,
            AVERAGE,
            ENDLESS_AVERAGE,
            AVERAGE_WEIGHTED_FOR_LAST,
            ENDLESS_AVERAGE_WEIGHTED_FOR_LAST,
            AVERAGE_WEIGHTED_FOR_LIGHT,
            ENDLESS_AVERAGE_WEIGHTED_FOR_LIGHT,
            LIGHTEST_PIXELS,
            ENDLESS_LIGHTEST_PIXELS,
            ADD,
            ENDLESS_ADD,
            HDR,
            TRANSITION,
            DARKEST_PIXELS,
            ENDLESS_DARKEST_PIXELS,
            MOTION_EXTRACTION,
            ENDLESS_MOTION_EXTRACTION,
            MEDIAN_STACK,
            ENDLESS_MEDIAN_STACK
        }

        private val effectsWithoutSize = setOf(
            Effect.NONE,
            Effect.ENDLESS_AVERAGE,
            Effect.ENDLESS_AVERAGE_WEIGHTED_FOR_LAST,
            Effect.ENDLESS_AVERAGE_WEIGHTED_FOR_LIGHT,
            Effect.ENDLESS_LIGHTEST_PIXELS,
            Effect.ENDLESS_ADD,
            Effect.ENDLESS_DARKEST_PIXELS,
            Effect.ENDLESS_MOTION_EXTRACTION,
            Effect.ENDLESS_MEDIAN_STACK
        )

        fun show(activity: MainActivity) {
            activity.pushView("TimeLapse", MainFragment(activity))
        }
    }

    private lateinit var binding: MainFragmentBinding
    private var menuSave: MenuItem? = null
    private var menuSaveLastRenderedFrame: MenuItem? = null
    private var menuSaveFullResImage: MenuItem? = null
    private var framesInput: FramesInput? = null
    private var firstFrame = Mat()
    private val firstFrameMask = Mat()
    private val alignCache = AlignCache()
    private val alignCacheForPhoto = AlignCache()
    private var outputParams: OutputParams? = null
    private var alignMaskId = 0
    private var previewJob: Job? = null
    private var previewAnimationJob: Job? = null
    private var orientationSpinnerPos = ORIENTATION_AUTO

    private val thumbCache = object : LruCache<Int, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()) {
        override fun sizeOf(key: Int, value: Bitmap): Int {
            return value.byteCount / 1024
        }
    }

    private val seekBarChangeListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            updateView()
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }

    private val spinnerOnItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>, view: View, position: Int, id: Long) {
            updateView()
        }
        override fun onNothingSelected(parent: AdapterView<*>) {}
    }

    private val switchOnCheckedChangeListener = object : CompoundButton.OnCheckedChangeListener {
        override fun onCheckedChanged(buttonView: CompoundButton?, isChecked: Boolean) {
            updateView()
        }
    }

    private val tmpFolder: File
        get() = requireContext().cacheDir
    private val tmpOutputVideo: File
        get() = File(tmpFolder, "tmp_video.mp4")

    private val currentEffect: Effect
        get() {
            val spinnerPos = binding.spinnerEffect.selectedItemPosition
            val isEndless = binding.switchEndless.isChecked
            return when (spinnerPos) {
                0 -> Effect.NONE
                1 -> if (isEndless) Effect.ENDLESS_AVERAGE else Effect.AVERAGE
                2 -> if (isEndless) Effect.ENDLESS_AVERAGE_WEIGHTED_FOR_LAST else Effect.AVERAGE_WEIGHTED_FOR_LAST
                3 -> if (isEndless) Effect.ENDLESS_AVERAGE_WEIGHTED_FOR_LIGHT else Effect.AVERAGE_WEIGHTED_FOR_LIGHT
                4 -> if (isEndless) Effect.ENDLESS_LIGHTEST_PIXELS else Effect.LIGHTEST_PIXELS
                5 -> if (isEndless) Effect.ENDLESS_ADD else Effect.ADD
                6 -> Effect.HDR
                7 -> Effect.TRANSITION
                8 -> if (isEndless) Effect.ENDLESS_DARKEST_PIXELS else Effect.DARKEST_PIXELS
                9 -> if (isEndless) Effect.ENDLESS_MOTION_EXTRACTION else Effect.MOTION_EXTRACTION
                10 -> if (isEndless) Effect.ENDLESS_MEDIAN_STACK else Effect.MEDIAN_STACK
                else -> Effect.NONE
            }
        }

    private fun videoPlayOriginal() {
        videoStop()
        val input = framesInput ?: return
        val rangeSlider = binding.rangeSliderDuration
        val range = rangeSlider.values
        val startTimeMs = ((range[0].toInt() - 1) * 1000 / input.fps).toInt()
        val endTimeMs = ((range[1].toInt() - 1) * 1000 / input.fps).toInt()

        if (input is VideoFramesInput) {
            binding.video.setVideoURI(input.videoUri)
            binding.video.setOnPreparedListener { mp ->
                mp.setVolume(0f, 0f)
                mp.isLooping = false
                binding.video.seekTo(startTimeMs)
                binding.video.start()
                
                GlobalScope.launch(Dispatchers.Main) {
                    while (binding.video.isPlaying) {
                        if (binding.video.currentPosition >= endTimeMs) {
                            binding.video.pause()
                            break
                        }
                        delay(50)
                    }
                }
            }
        } else {
            startImageRangePreview(range[0].toInt(), range[1].toInt())
        }
    }

    private fun startImageRangePreview(startIdx: Int, endIdx: Int) {
        previewAnimationJob?.cancel()
        binding.imagePreview.visibility = View.VISIBLE
        previewAnimationJob = GlobalScope.launch(Dispatchers.IO) {
            var showStart = true
            while (isActive) {
                val idx = if (showStart) startIdx else endIdx
                val bitmap = (framesInput as? ImagesFramesInput)?.loadThumbnail(idx - 1, 1080)
                if (bitmap != null) {
                    runOnUiThread { 
                        if (isActive) binding.imagePreview.setImageBitmap(bitmap) 
                    }
                }
                showStart = !showStart
                delay(500)
            }
        }
    }

    private fun acquireWakeLock() {
        activity?.let {
            val powerManager = it.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE, "${it.packageName}:TimelapseWakelockTag")
            try {
                if (wakeLock?.isHeld == false) {
                    wakeLock?.acquire()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            wakeLock = null
        }
    }

    private fun getCurrentOutputParams(framesInput: FramesInput, fullSize: Boolean): OutputParams {
        val outputParams = OutputParams()

        outputParams.set(OutputParams.KEY_H265, if(settings.h265) 1 else 0)
        outputParams.set(OutputParams.KEY_CROP, if(settings.crop) 1 else 0)

        val speedSlider = binding.seekBarSpeed
        val speedIndex = speedSlider.progress
        val speed = SPEED_VALUES[speedIndex]
        
        val alignSwitch = binding.switchAlign
        outputParams.set(OutputParams.KEY_SPEED, speed)
        outputParams.set(OutputParams.KEY_ALIGN, if (alignSwitch.isChecked) alignMaskId else -1)

        val effect = currentEffect
        outputParams.set(OutputParams.KEY_EFFECT, effect.ordinal)
        
        val rangeSlider = binding.rangeSliderDuration
        val range = rangeSlider.values
        val startFrame = range[0].toInt()
        val endFrame = range[1].toInt()
        val selectedInputCount = endFrame - startFrame + 1
        
        // Accurate total count calculation
        val finalProcessedCount = (selectedInputCount + speed - 1) / speed

        val effectSlider = binding.seekBarEffect
        var effectSize = effectSlider.progress + 2
        if (finalProcessedCount > 1) {
            effectSize = min(effectSize, min(30, finalProcessedCount))
        }
        
        outputParams.set(OutputParams.KEY_EFFECT_SIZE, if (effectsWithoutSize.contains(effect)) 0 else effectSize)

        outputParams.set(OutputParams.KEY_DURATION_START, startFrame)
        outputParams.set(OutputParams.KEY_DURATION_END, endFrame)

        outputParams.set(OutputParams.KEY_FPS, 30)

        var width: Int
        var height: Int

        if (fullSize) {
            width = framesInput.width
            height = framesInput.height
        } else {
            var res = settings.videoResolution
            // ensure that if the input video is not larger than 1080p then the 4k setting 
            // - even if set - should not be impacted or not attempt to try 4k
            if (res == Settings.RES_4K && framesInput.width <= 1920 && framesInput.height <= 1920) {
                res = Settings.RES_1080P
            }

            val baseSize = when(res) {
                Settings.RES_4K -> 2160
                Settings.RES_720P -> 720
                else -> 1080
            }
            val aspect = framesInput.width.toDouble() / framesInput.height.toDouble()
            
            when (orientationSpinnerPos) {
                ORIENTATION_LANDSCAPE -> {
                    width = (baseSize * 1.777777778).toInt()
                    height = baseSize
                }
                ORIENTATION_PORTRAIT -> {
                    width = baseSize
                    height = (baseSize * 1.777777778).toInt()
                }
                else -> {
                    if (aspect >= 1.0) {
                        width = (baseSize * aspect).toInt()
                        height = baseSize
                    } else {
                        width = baseSize
                        height = (baseSize / aspect).toInt()
                    }
                }
            }
            width = (width / 2) * 2
            height = (height / 2) * 2
        }

        outputParams.set(OutputParams.KEY_WIDTH, width)
        outputParams.set(OutputParams.KEY_HEIGHT, height)

        return outputParams
    }

    private fun stabChangeFpsAsync(outputParams: OutputParams) {
        this.outputParams = null
        VideoTools.changeFps(tmpOutputVideo.absolutePath, outputParams.get(OutputParams.KEY_FPS))
        if (tmpOutputVideo.exists()) this.outputParams = outputParams
    }

    private fun changeFps(outputParams: OutputParams) {
        runAsync(TITLE_GENERATE, -1, true) {
            stabChangeFpsAsync(outputParams)
        }
    }

    private fun videoPlayGenerated(generateIfNeeded: Boolean) {
        val framesInput = this.framesInput ?: return
        videoStop()
        val outputParams = getCurrentOutputParams(framesInput, false)

        if (framesInput is VideoFramesInput && isIdentityTransformation(outputParams, framesInput)) {
            this.outputParams = outputParams
            videoPlayOriginal()
            return
        }

        val changes = outputParams.compareWith(this.outputParams)
        if (!tmpOutputVideo.exists() || OutputParams.COMPARE_NOT_CHANGED != changes) {
            if (generateIfNeeded) {
                if (OutputParams.COMPARE_CHANGED_ONLY_FPS == changes) {
                    changeFps(outputParams)
                } else {
                    handleGenerate(outputParams)
                }
            }
            return
        }
        binding.video.setVideoURI(Uri.fromFile(tmpOutputVideo))
        binding.video.setOnPreparedListener { mp ->
            mp.isLooping = false
            binding.video.start()
        }
    }

    private fun isIdentityTransformation(params: OutputParams, input: FramesInput): Boolean {
        return params.get(OutputParams.KEY_EFFECT) == Effect.NONE.ordinal &&
               params.get(OutputParams.KEY_ALIGN) == -1 &&
               params.get(OutputParams.KEY_SPEED) == 1 &&
               params.get(OutputParams.KEY_DURATION_START) == 1 &&
               params.get(OutputParams.KEY_DURATION_END) == input.size &&
               params.get(OutputParams.KEY_FPS) == input.fps &&
               params.get(OutputParams.KEY_WIDTH) == input.width &&
               params.get(OutputParams.KEY_HEIGHT) == input.height
    }

    private fun videoStop() {
        binding.video.stopPlayback()
        binding.video.setVideoURI(null)
        previewAnimationJob?.cancel()
        binding.imagePreview.visibility = View.GONE
    }

    private fun cleanUp() {
        videoStop()
        try {
            if (tmpOutputVideo.exists()) tmpOutputVideo.delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        framesInput?.release()
        framesInput = null
        binding.imagePreview.setImageBitmap(null)
        binding.imagePreview.visibility = View.GONE
        updateView()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = MainFragmentBinding.inflate(inflater)
        binding.settings = settings

        binding.video.setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE)
        binding.video.setOnPreparedListener { mp -> mp.setVolume(0f, 0f) }
        binding.video.setOnErrorListener { _, _, _ -> true }

        // Standard Sliders
        binding.seekBarSpeed.setOnSeekBarChangeListener(seekBarChangeListener)
        binding.seekBarEffect.setOnSeekBarChangeListener(seekBarChangeListener)
        binding.rangeSliderDuration.setLabelFormatter { value -> 
            if (settings.uiMode == Settings.UI_MODE_EXPERT) value.toInt().toString() 
            else formatTime(value.toInt())
        }
        binding.rangeSliderDuration.addOnChangeListener { slider, value, fromUser ->
            if (fromUser) {
                updateView()
                if ((framesInput?.size ?: 0) < 150) showPreviewAt(value.toInt())
            }
        }
        binding.rangeSliderDuration.addOnSliderTouchListener(rangeSliderTouchListener(binding.rangeSliderDuration))

        binding.buttonPlayOriginal.setOnClickListener { videoPlayOriginal() }
        binding.buttonPlayGenerated.setOnClickListener { videoPlayGenerated(true) }
        binding.buttonStop.setOnClickListener { videoStop() }

        binding.switchAlign.setOnCheckedChangeListener { _, _ -> updateView()  }
        
        binding.buttonAlignMask.setOnClickListener { openMaskEditor() }

        binding.spinnerEffect.onItemSelectedListener = spinnerOnItemSelectedListener
        binding.switchEndless.setOnCheckedChangeListener(switchOnCheckedChangeListener)

        cleanUp()
        setHasOptionsMenu(true)
        
        binding.root.postDelayed({
            if (settings.lastFolderUri.isNotEmpty()) {
                try {
                    val uri = Uri.parse(settings.lastFolderUri)
                    val uriFolder = UriFile.fromTreeUri(requireContext(), uri)
                    if (null != uriFolder && uriFolder.isDirectory) {
                        openImageFolder(uriFolder)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restore last folder", e)
                }
            }
        }, 500)

        openIntent(activity.intent)
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        return binding.root
    }

    private fun rangeSliderTouchListener(slider: RangeSlider) = object : RangeSlider.OnSliderTouchListener {
        override fun onStartTrackingTouch(s: RangeSlider) { videoStop() }
        override fun onStopTrackingTouch(s: RangeSlider) {
            val values = s.values
            val input = framesInput
            if (input is ImagesFramesInput) {
                startImageRangePreview(values[0].toInt(), values[1].toInt())
            } else {
                showPreviewAt(s.activeThumbIndex.let { if (it == 0) values[0] else values[1] }.toInt())
            }
        }
    }

    private fun formatTime(frameIndex: Int): String {
        val fps = framesInput?.fps ?: 30
        val totalSeconds = frameIndex / fps
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d".format(minutes, seconds)
    }

    private fun openMaskEditor() {
        MaskEditFragment.show(activity, firstFrame, firstFrameMask) {
            alignMaskId++
            alignCache.reset()
            alignCacheForPhoto.reset()
        }
    }

    private fun showPreviewAt(frameIndex: Int) {
        val input = framesInput ?: return
        thumbCache.get(frameIndex)?.let {
            binding.imagePreview.setImageBitmap(it)
            binding.imagePreview.visibility = View.VISIBLE
            return
        }

        previewJob?.cancel()
        previewJob = GlobalScope.launch(Dispatchers.IO) {
            val thumbWidth = 640
            val bitmap = when (input) {
                is ImagesFramesInput -> input.loadThumbnail(frameIndex - 1, thumbWidth)
                is VideoFramesInput -> input.loadThumbnail(frameIndex - 1)
                else -> null
            }

            if (bitmap != null && isActive) {
                thumbCache.put(frameIndex, bitmap)
                runOnUiThread {
                    binding.imagePreview.setImageBitmap(bitmap)
                    binding.imagePreview.visibility = View.VISIBLE
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.app_menu, menu)
        menuSave = menu.findItem(R.id.menuSave)
        menuSaveLastRenderedFrame = menu.findItem(R.id.menuSaveLastRenderedFrame)
        menuSaveFullResImage = menu.findItem(R.id.menuSaveFullResImage)
        updateView()
    }

    override fun onDestroyOptionsMenu() {
        menuSave = null
        menuSaveLastRenderedFrame = null
        menuSaveFullResImage = null
        super.onDestroyOptionsMenu()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when(item.itemId) {
            R.id.menuOpenVideo -> { handleOpenVideo(); return true }
            R.id.menuOpenPhotos -> { handleOpenFiles(); return true }
            R.id.menuOpenFolder -> { handleOpenFolder(); return true }
            R.id.menuOrientation -> { handleOrientation(); return true }
            R.id.menuSave -> { handleSave(); return true }
            R.id.menuSaveLastRenderedFrame -> { handleSaveLastRenderedFrame(); return true }
            R.id.menuSaveFullResImage -> { handleSaveFullResImage(); return true }
            R.id.menuSettings -> { SettingsFragment.show(activity); return true }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun handleOrientation() {
        val items = arrayOf("Auto", "Landscape", "Portrait")
        AlertDialog.Builder(requireContext())
            .setTitle("Select Orientation")
            .setSingleChoiceItems(items, orientationSpinnerPos) { dialog, which ->
                orientationSpinnerPos = which
                updateView()
                dialog.dismiss()
            }
            .show()
    }

    private fun openIntent(intent: Intent?) {
        if (null == intent) return
        val context = requireContext()
        val uri = intent.data

        if (null != uri) {
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
                if (uri.toString().contains("tree")) {
                    settings.lastFolderUri = uri.toString()
                    settings.saveProperties()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to take permission", e)
            }

            val uriFile = UriFile.fromSingleUri(context, uri)
            if (null != uriFile) {
                if (uriFile.isVideo) openVideoFile(uriFile)
                else if (uriFile.isDirectory) openImageFolder(uriFile)
                return
            }

            val uriFolder = UriFile.fromTreeUri(context, uri)
            if (null != uriFolder && uriFolder.isDirectory) openImageFolder(uriFolder)
            return
        }

        intent.clipData?.let { clipData ->
            if (clipData.itemCount > 0) {
                val uriList = mutableListOf<Uri>()
                for (i in 0 until clipData.itemCount) uriList.add(clipData.getItemAt(i).uri)
                if (clipData.description.hasMimeType("video/*")) {
                    val uriFile = UriFile.fromSingleUri(context, uriList[0])
                    if (null != uriFile) openVideoFile(uriFile)
                } else if (clipData.description.hasMimeType("image/*")) {
                    val inputImages = ImagesFramesInput.fromFiles(requireContext(), uriList)
                    setFramesInput(inputImages)
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        if (AppCompatActivity.RESULT_OK == resultCode) openIntent(intent)
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, intent)
    }

    private fun handleOpenVideo() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("video/*")
        @Suppress("DEPRECATION")
        startActivityForResult(intent, INTENT_OPEN_VIDEO)
    }

    private fun handleOpenFiles() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("image/*")
        @Suppress("DEPRECATION")
        startActivityForResult(intent, INTENT_OPEN_IMAGES)
    }

    private class FolderAdapter(
        private val context: Context,
        private val uris: List<Uri>,
        private val folderNames: List<String>
    ) : BaseAdapter() {
        override fun getCount(): Int = uris.size + 1
        override fun getItem(position: Int): Any? = if (position < uris.size) uris[position] else null
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.recent_folder_item, parent, false)
            val icon = view.findViewById<ImageView>(R.id.icon)
            val text = view.findViewById<TextView>(R.id.text)
            val divider = view.findViewById<View>(R.id.divider)
            
            if (position < uris.size) {
                icon.setImageResource(R.drawable.ic_folder_outline)
                text.text = folderNames[position]
                divider.visibility = View.GONE
            } else {
                icon.setImageResource(R.drawable.ic_add)
                text.text = "Browse"
                divider.visibility = View.VISIBLE
            }
            return view
        }
    }

    private fun handleOpenFolder() {
        val context = requireContext()
        val permissions = context.contentResolver.persistedUriPermissions
        val uris = permissions.filter { DocumentsContract.isTreeUri(it.uri) }.map { it.uri }.take(6)
        
        if (uris.isEmpty()) {
            launchFolderPicker()
            return
        }

        val folderNames = uris.map { uri -> UriFile.fromTreeUri(context, uri)?.name ?: uri.toString() }
        val titleView = TextView(context).apply {
            text = "Choose a folder"
            gravity = Gravity.CENTER
            setPadding(0, 48, 0, 24)
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).getColor(0, 0))
        }

        val dialog = AlertDialog.Builder(context)
            .setCustomTitle(titleView)
            .setAdapter(FolderAdapter(context, uris, folderNames)) { _, which ->
                if (which < uris.size) {
                    val uri = uris[which]
                    UriFile.fromTreeUri(context, uri)?.let {
                        settings.lastFolderUri = uri.toString()
                        settings.saveProperties()
                        openImageFolder(it)
                    }
                } else launchFolderPicker()
            }.create()
        dialog.show()
    }
    
    private fun launchFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        @Suppress("DEPRECATION")
        startActivityForResult(intent, INTENT_OPEN_FOLDER)
    }

    private fun openVideoFile(uriFile: UriFile) {
        cleanUp()
        try { setFramesInput( VideoFramesInput(uriFile) ) } catch (e: Exception) { showToast("Failed to open video") }
    }

    private fun openImagesFiles(uris: List<Uri>) {
        cleanUp()
        try { setFramesInput( ImagesFramesInput.fromFiles(requireContext(), uris) ) } catch (e: Exception) { showToast("Failed to open images") }
    }

    private fun openImageFolder(folderUri: UriFile) {
        cleanUp()
        try { setFramesInput( ImagesFramesInput.fromFolder(folderUri) ) } catch (e: Exception) { showToast("Failed to open folder") }
    }

    private fun setFramesInput(framesInput: FramesInput) {
        outputParams = null
        alignCache.resetAll()
        alignCacheForPhoto.resetAll()
        this.framesInput?.release()
        this.framesInput = framesInput
        
        val inputSize = framesInput.size.toFloat()
        val sliders = listOf(binding.rangeSliderDuration)
        for (s in sliders) {
            s.values = listOf(1f, 1f)
            s.valueFrom = 1f
            s.valueTo = max(2f, inputSize)
            s.values = listOf(1f, inputSize)
            s.stepSize = 1f
        }

        binding.textFileName.text = framesInput.name

        firstFrame.release()
        firstFrame = framesInput.firstFrame()
        firstFrameMask.release()
        
        showPreviewAt(1)
        updateView()
    }

    private fun handleGenerate(outputParams: OutputParams) {
        runAsync(TITLE_GENERATE, 0, true) { generateAsync(outputParams, false) }
    }

    private fun handleSave() {
        videoStop()
        runAsync(TITLE_SAVE) { saveAsync() }
    }

    private fun handleSaveLastRenderedFrame() {
        val framesInput = this.framesInput ?: return
        videoStop()
        runAsync(TITLE_SAVE_RENDERED_FRAME) { generateAsync(getCurrentOutputParams(framesInput, false), true) }
    }

    private fun handleSaveFullResImage() {
        val framesInput = this.framesInput ?: return
        videoStop()
        runAsync(TITLE_SAVE_FULL_RES) { generateAsync(getCurrentOutputParams(framesInput, true), true) }
    }

    private fun saveAsync() {
        val framesInput = this.framesInput ?: return
        var success = false
        var savedUri: Uri? = null
        try {
            val contentResolver = requireContext().contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, framesInput.name + "_" + System.currentTimeMillis() + ".mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/TimeLapse")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }

            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }

            val uri = contentResolver.insert(collection, contentValues)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    FileInputStream(tmpOutputVideo).use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                    contentResolver.update(uri, contentValues, null, null)
                }
                savedUri = uri
                success = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, "Save failed", e)
        }
        
        val message = if (success) "Saved to Gallery" else "Failed !"
        showToast(message)
    }

    private fun getOutputFile(isVideo: Boolean): File {
        val framesInput = this.framesInput ?: throw FileNotFoundException()
        val ext = if (isVideo) Settings.EXT_VIDEO else Settings.EXT_PHOTO
        val folder = if (isVideo) Settings.VIDEO_SAVE_FOLDER else Settings.PHOTO_SAVE_FOLDER
        if (!folder.exists()) folder.mkdirs()
        var outputFile = buildFile(folder, framesInput.name, ext, 0)
        var counter = 0
        while(outputFile.exists() && counter < 998) {
            counter++
            outputFile = buildFile(folder, framesInput.name, ext, counter)
        }
        return outputFile
    }
    
    private fun buildFile(folder: File, suffix: String, ext: String, counter: Int): File {
        return File(folder, suffix + (if (0 == counter) "" else "_${String.format("%03d", counter)}") + "." + ext)
    }

    private fun generateAsync(outputParams: OutputParams, framesInput: FramesInput, finalConsumer: FramesConsumer, alignCache: AlignCache): Boolean {
        val videoWidth = outputParams.get(OutputParams.KEY_WIDTH)
        val videoHeight = outputParams.get(OutputParams.KEY_HEIGHT)
        var frameConsumer: FramesConsumer = finalConsumer
        val effectSize = min(outputParams.get(OutputParams.KEY_EFFECT_SIZE), framesInput.size)
        val selectedEffect = Effect.values().getOrElse(outputParams.get(OutputParams.KEY_EFFECT)) { Effect.NONE }

        when (selectedEffect) {
            Effect.NONE -> {}
            Effect.AVERAGE -> frameConsumer = AverageFramesFilter(effectSize, frameConsumer)
            Effect.ENDLESS_AVERAGE -> frameConsumer = EndlessAverageFramesFilter(frameConsumer)
            Effect.AVERAGE_WEIGHTED_FOR_LAST -> frameConsumer = AverageWeightedForLastFramesFilter(effectSize, frameConsumer)
            Effect.ENDLESS_AVERAGE_WEIGHTED_FOR_LAST -> frameConsumer = EndlessAverageWeightedForLastFramesFilter(frameConsumer)
            Effect.AVERAGE_WEIGHTED_FOR_LIGHT -> frameConsumer = AverageWeightedForLightFramesFilter(effectSize, frameConsumer)
            Effect.ENDLESS_AVERAGE_WEIGHTED_FOR_LIGHT -> frameConsumer = EndlessAverageWeightedForLightFramesFilter(frameConsumer)
            Effect.LIGHTEST_PIXELS -> frameConsumer = LightestPixelsFramesFilter(effectSize, frameConsumer)
            Effect.ENDLESS_LIGHTEST_PIXELS -> frameConsumer = EndlessLightestPixelsFramesFilter(frameConsumer)
            Effect.ADD -> frameConsumer = AddFramesFilter(effectSize, frameConsumer)
            Effect.ENDLESS_ADD -> frameConsumer = EndlessAddFramesFilter(frameConsumer)
            Effect.HDR -> frameConsumer = HDRFramesFilter(effectSize, frameConsumer)
            Effect.TRANSITION -> frameConsumer = TransitionFramesFilter(effectSize, frameConsumer)
            Effect.DARKEST_PIXELS -> frameConsumer = DarkestPixelsFramesFilter(effectSize, frameConsumer)
            Effect.ENDLESS_DARKEST_PIXELS -> frameConsumer = EndlessDarkestPixelsFramesFilter(frameConsumer)
            Effect.MOTION_EXTRACTION -> frameConsumer = MotionExtractionWindowFramesFilter(effectSize, frameConsumer)
            Effect.ENDLESS_MOTION_EXTRACTION -> frameConsumer = EndlessCumulativeMotionFramesFilter(frameConsumer)
            Effect.MEDIAN_STACK -> frameConsumer = MedianFramesFilter(effectSize, false, frameConsumer)
            Effect.ENDLESS_MEDIAN_STACK -> frameConsumer = EndlessMedianFramesFilter(requireContext(), settings.videoResolution == Settings.RES_4K, frameConsumer)
        }

        if (binding.switchAlign.isChecked) {
            alignCache.resetIfSizeChanged(videoWidth, videoHeight)
            frameConsumer = AlignFramesFilter(firstFrameMask, alignCache, frameConsumer)
        }
        frameConsumer = ScaleFramesFilter( settings.crop, videoWidth, videoHeight, frameConsumer )
        
        val speed = outputParams.get(OutputParams.KEY_SPEED)
        if (speed > 1) frameConsumer = SampleFramesFilter( speed, frameConsumer )

        val startFrame = outputParams.get(OutputParams.KEY_DURATION_START) - 1
        val endFrame = outputParams.get(OutputParams.KEY_DURATION_END) - 1
        val totalToProcess = endFrame - startFrame + 1
        val windowSize = frameConsumer.getWindowSize()
        val optimizedStart = if (finalConsumer is ImageWriter && windowSize > 0) max(startFrame, endFrame - windowSize + 1) else startFrame

        BusyDialog.showCancel()
        frameConsumer.start()
        framesInput.forEachFrame(optimizedStart, { seekIndex ->
            BusyDialog.show(TITLE_GENERATE, seekIndex - startFrame, totalToProcess)
        }) { index, _, frame ->
            BusyDialog.show(TITLE_GENERATE, index - startFrame, totalToProcess)
            if (BusyDialog.isCanceled()) return@forEachFrame false
            if (index > endFrame) return@forEachFrame false
            
            frameConsumer.consume(index - optimizedStart, frame)
            true
        }
        val canceled = BusyDialog.isCanceled()
        frameConsumer.stop(canceled)
        return !canceled
    }

    private fun generateVideoAsync(outputParams: OutputParams, framesInput: FramesInput) {
        this.outputParams = null
        var success = false
        try {
            if (tmpOutputVideo.exists()) tmpOutputVideo.delete()
            val fps = outputParams.get(OutputParams.KEY_FPS)
            val finalFrameConsumer = VideoWriter(tmpOutputVideo.absolutePath, fps, settings.h265 )
            success = generateAsync(outputParams, framesInput, finalFrameConsumer, alignCache)
            if (success) {
                this.outputParams = outputParams
            }
        } catch (e: Exception) { e.printStackTrace() }
        if (!success && tmpOutputVideo.exists()) tmpOutputVideo.delete()
    }

    private fun generatePhotoAsync(outputParams: OutputParams, framesInput: FramesInput) {
        var success = false
        var savedUri: Uri? = null
        try {
            val contentResolver = requireContext().contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, framesInput.name + "_" + System.currentTimeMillis() + ".jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/TimeLapse")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            val uri = contentResolver.insert(collection, contentValues)
            if (uri != null) {
                val tmpPhoto = File(requireContext().cacheDir, "tmp_photo.jpg")
                val finalFrameConsumer = ImageWriter(tmpPhoto, settings.jpegQuality)
                
                val changes = outputParams.compareWith(this.outputParams)
                if (tmpOutputVideo.exists() && (OutputParams.COMPARE_NOT_CHANGED == changes || OutputParams.COMPARE_CHANGED_ONLY_FPS == changes)) {
                    val mediaMetadataRetriever = MediaMetadataRetriever()
                    mediaMetadataRetriever.setDataSource(tmpOutputVideo.absolutePath)
                    val frameCount = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: throw FileNotFoundException()
                    val bitmap = mediaMetadataRetriever.getFrameAtIndex(frameCount-1) ?: throw FileNotFoundException()
                    val frame = Mat()
                    Utils.bitmapToMat(bitmap, frame)
                    finalFrameConsumer.start()
                    finalFrameConsumer.consume(0, frame)
                    finalFrameConsumer.stop(false)
                } else generateAsync(outputParams, framesInput, finalFrameConsumer, alignCacheForPhoto)
                
                if (finalFrameConsumer.success && tmpPhoto.exists()) {
                    contentResolver.openOutputStream(uri)?.use { outputStream ->
                        FileInputStream(tmpPhoto).use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        contentValues.clear()
                        contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                        contentResolver.update(uri, contentValues, null, null)
                    }
                    success = true
                    savedUri = uri
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, "Photo save failed", e)
        }
        
        if (success) {
            showToast("Saved to Gallery")
        } else {
            showToast("Failed !")
        }
    }

    private fun generateAsync(outputParams: OutputParams, saveAsPhoto: Boolean) {
        val framesInput = this.framesInput ?: return
        if (saveAsPhoto) generatePhotoAsync(outputParams, framesInput) else generateVideoAsync(outputParams, framesInput)
        runOnUiThread { updateView() }
    }

    private fun runAsync(initialMessage: String, progress: Int, playOnFinish: Boolean, asyncTask: () -> Unit) {
        videoStop()
        acquireWakeLock()
        GlobalScope.launch(Dispatchers.Default) {
            try { BusyDialog.show(initialMessage, progress, framesInput?.size ?: -1); asyncTask() } catch (e: Exception) { e.printStackTrace() } finally {
                runOnUiThread { releaseWakeLock(); updateView(); BusyDialog.dismiss(); if (playOnFinish) videoPlayGenerated(false) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateView()
    }

    override fun onPause() { super.onPause(); releaseWakeLock() }
    override fun onDestroyView() { releaseWakeLock(); framesInput?.release(); super.onDestroyView() }
    private fun runAsync(initialMessage: String, asyncTask: () -> Unit) { runAsync(initialMessage, -1, false, asyncTask) }
    private fun supportsEndless(spinnerPos: Int): Boolean = spinnerPos in 1..5 || spinnerPos in 8..10

    private fun hideSaveFab() {}
    private fun showSaveFab() {}

    private fun updateView() {
        if (!isAdded) return
        val framesInput = this.framesInput
        val enabled = null != framesInput
        val isExpert = settings.uiMode == Settings.UI_MODE_EXPERT
        
        // Refresh Data Binding variables
        binding.settings = settings
        binding.executePendingBindings()

        // Visibility
        binding.buttonAlignMask.visibility = if (binding.switchAlign.isChecked) View.VISIBLE else View.GONE

        // Calculations
        val speedSlider = binding.seekBarSpeed
        val speedIndex = speedSlider.progress
        val speedValue = SPEED_VALUES[speedIndex]
        
        val rangeSlider = binding.rangeSliderDuration
        val range = rangeSlider.values
        val start = range[0].toInt()
        val end = range[1].toInt()
        val finalProcessedCount = (end - start + 1 + speedValue - 1) / speedValue

        val effectSlider = binding.seekBarEffect
        val effect = currentEffect

        // UI Logic for Filter Controls
        val isNoneEffect = effect == Effect.NONE
        val isEndless = binding.switchEndless.isChecked
        
        // Always show the row, but disable elements if "None" is selected
        val enableControls = !isNoneEffect
        binding.seekBarEffect.isEnabled = enableControls && !isEndless
        binding.switchEndless.isEnabled = enableControls
        
        // Visual feedback for disabled states
        val alpha = if (enableControls) 1.0f else 0.4f
        binding.textEffectLabel.alpha = alpha
        binding.textEffect.alpha = if (enableControls && !isEndless) 1.0f else 0.4f
        binding.textOrLabel.alpha = alpha
        binding.textEndlessLabel.alpha = alpha
        
        if (isEndless) binding.seekBarEffect.alpha = 0.4f else binding.seekBarEffect.alpha = alpha

        if (enabled && !effectsWithoutSize.contains(effect)) {
            val maxEffectSize = min(28, max(0, finalProcessedCount - 2))
            if (effectSlider.max != maxEffectSize) effectSlider.max = maxEffectSize
        }

        // Labels
        val speedText = if (isExpert) "Step $speedValue" else "${speedValue}x"
        binding.textSpeed.text = speedText

        val displayEffectSize = if (enabled && framesInput != null) min(effectSlider.progress + 2, finalProcessedCount) else effectSlider.progress + 2
        val effectText = if (isExpert) "$displayEffectSize frames" else "${displayEffectSize}x"
        binding.textEffect.text = effectText

        val durationText = if (enabled && framesInput != null) {
            if (start == 1 && end == framesInput.size && speedValue == 1) "all" 
            else if (isExpert) "$start - $end ($finalProcessedCount)"
            else "${formatTime(start)} - ${formatTime(end)} ($finalProcessedCount)"
        } else ""
        binding.textDuration.text = durationText

        // Info text
        if (null == framesInput) {
            binding.textInfo.text = ""
            binding.textFileName.text = "Select video/images"
        } else {
            binding.textInfo.text = "${framesInput.width} x ${framesInput.height}, ${framesInput.fps} FPS"
            binding.textFileName.text = framesInput.name
        }

        // State
        val hasVideo = tmpOutputVideo.exists()
        menuSave?.isEnabled = enabled && hasVideo
        binding.buttonPlayOriginal.isEnabled = enabled
        binding.buttonPlayGenerated.isEnabled = enabled
        binding.buttonStop.isEnabled = enabled
    }
}
