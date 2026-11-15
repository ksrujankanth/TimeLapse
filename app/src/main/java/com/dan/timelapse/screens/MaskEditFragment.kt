package com.dan.timelapse.screens

import android.graphics.*
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.*
import android.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.dan.timelapse.MainActivity
import com.dan.timelapse.MainViewModel
import com.dan.timelapse.R
import com.dan.timelapse.databinding.MaskEditFragmentBinding
import com.dan.timelapse.widgets.MaskMode
import com.dan.timelapse.widgets.MaskView
import com.dan.timelapse.widgets.ZoomableFrameLayout
import org.opencv.android.Utils
import org.opencv.core.Mat

class MaskEditFragment : Fragment() {
    private val model: MainViewModel by activityViewModels()
    private var _binding: MaskEditFragmentBinding? = null
    private val binding get() = _binding!!
    private lateinit var maskView: MaskView
    private lateinit var videoContainer: ZoomableFrameLayout

    companion object {
        // Brush radius in dp - about 5mm radius (typical finger width)
        // This stays consistent across all screen densities
        private const val RADIUS_DP = 15f
    }

    private var currentMode = Mode.DRAW

    // Bitmaps for mask
    private var maskBitmap: Bitmap? = null
    private var imageWidth = 0
    private var imageHeight = 0
    
    // Auto-play state
    private var isAutoPlayEnabled = true

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = MaskEditFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val activity = requireActivity() as MainActivity
        maskView = activity.findViewById(R.id.maskOverlay)
        videoContainer = activity.findViewById(R.id.video_container)

        // Initialize mask bitmap from existing mask or create new
        initializeMaskBitmap()

        // Set brush radius
        maskView.setBrushRadius(convertDpToPixel(RADIUS_DP))
        
        // Mask stroke callback — the user controls stabilization independently via the Transform tab
        maskView.onStrokeDrawn = { }
        
        // Set up zoom listener to adjust brush size when zooming
        videoContainer.onZoomListener = object : ZoomableFrameLayout.OnZoomListener {
            override fun onZoom(scale: Float, translationX: Float, translationY: Float) {
                maskView.setZoomLevel(scale)
            }

            override fun onZoomReset() {
                maskView.setZoomLevel(1f)
            }
            
            override fun onZoomStart() {
                // Pause video during pinch/zoom for smoother performance
                if (isAutoPlayEnabled) {
                    activity.stopPreviewLoop()
                }
            }
            
            override fun onZoomEnd() {
                // Resume video when pinch/zoom ends
                if (isAutoPlayEnabled) {
                    activity.startPreviewLoop(Int.MAX_VALUE)
                }
            }
        }

        setupModeButtons()
        setupMoreMenu(activity)

        // Start video playback
        if (isAutoPlayEnabled) {
            activity.startPreviewLoop(Int.MAX_VALUE)
        }
    }

    private fun initializeMaskBitmap() {
        // Get dimensions from frames input
        val input = model.framesInput.value
        if (input != null) {
            imageWidth = input.width
            imageHeight = input.height
        } else {
            // Fallback dimensions
            imageWidth = 1920
            imageHeight = 1080
        }

        // Create or restore mask bitmap
        val existingMask = model.firstFrameMask.value
        if (existingMask != null && !existingMask.empty()) {
            maskBitmap = Bitmap.createBitmap(
                existingMask.cols(),
                existingMask.rows(),
                Bitmap.Config.ARGB_8888
            )
            Utils.matToBitmap(existingMask, maskBitmap)
            imageWidth = existingMask.cols()
            imageHeight = existingMask.rows()
        } else {
            maskBitmap = Bitmap.createBitmap(
                imageWidth,
                imageHeight,
                Bitmap.Config.ARGB_8888
            )
            // Initialize with black (transparent mask)
            val canvas = Canvas(maskBitmap!!)
            canvas.drawColor(Color.BLACK)
        }
        
        // Set the bitmap on the mask view
        if (::maskView.isInitialized) {
            maskView.setImageDimensions(imageWidth, imageHeight)
            maskView.setExternalMaskBitmap(maskBitmap)
        }
    }

    private fun setupModeButtons() {
        // Default to Draw mode
        setActiveMode(Mode.DRAW)

        // Zoom button - enables pinch/pan on video container
        binding.btnModeZoom.setOnClickListener {
            setActiveMode(Mode.ZOOM)
        }

        // Draw button - click to activate draw mode
        binding.btnModeDraw.setOnClickListener {
            setActiveMode(Mode.DRAW)
        }

        // Erase button - click to activate erase mode
        binding.btnModeErase.setOnClickListener {
            setActiveMode(Mode.ERASE)
        }

        // Top floating navigation handlers
        binding.btnMaskClose.setOnClickListener {
            (requireActivity() as? com.dan.timelapse.MainActivity)?.closeMaskEditorToTransform()
        }

binding.btnMaskDone.setOnClickListener {
            // FIX: Convert the drawn mask Bitmap back to a binary Mat and save it
            maskBitmap?.let { bmp ->
                val maskMat = Mat()
                org.opencv.android.Utils.bitmapToMat(bmp, maskMat)
                
                // Convert ARGB to grayscale for OpenCV tracking
                org.opencv.imgproc.Imgproc.cvtColor(maskMat, maskMat, org.opencv.imgproc.Imgproc.COLOR_RGBA2GRAY)
                
                // Threshold to ensure it's strictly binary (0 or 255)
                org.opencv.imgproc.Imgproc.threshold(maskMat, maskMat, 10.0, 255.0, org.opencv.imgproc.Imgproc.THRESH_BINARY)
                
                model.setFirstFrameMask(maskMat)
            }
            
            (requireActivity() as? com.dan.timelapse.MainActivity)?.closeMaskEditorToTransform()
        }
    }

    private enum class Mode { ZOOM, DRAW, ERASE }

    private fun setActiveMode(mode: Mode) {
        currentMode = mode
        
        // Reset all buttons
        binding.btnModeZoom.setBackgroundColor(Color.TRANSPARENT)
        binding.btnModeDraw.setBackgroundColor(Color.TRANSPARENT)
        binding.btnModeErase.setBackgroundColor(Color.TRANSPARENT)

        val activeColor = resources.getColor(R.color.colorPrimary, null)

        when (mode) {
            Mode.ZOOM -> {
                binding.btnModeZoom.setBackgroundColor(activeColor)
                // Enable zoom on video container
                videoContainer.isZoomEnabled = true
                // Set mask view to zoom mode (will pass touches through)
                maskView.setMode(MaskMode.ZOOM)
            }
            Mode.DRAW -> {
                binding.btnModeDraw.setBackgroundColor(activeColor)
                // Disable zoom
                videoContainer.isZoomEnabled = false
                // Set mask view to draw mode
                maskView.setMode(MaskMode.DRAW)
            }
            Mode.ERASE -> {
                binding.btnModeErase.setBackgroundColor(activeColor)
                // Disable zoom
                videoContainer.isZoomEnabled = false
                // Set mask view to erase mode
                maskView.setMode(MaskMode.ERASE)
            }
        }
    }

    private fun convertDpToPixel(dp: Float): Float {
        return dp * (resources.displayMetrics.densityDpi.toFloat() / DisplayMetrics.DENSITY_DEFAULT)
    }

    private fun setMask(value: Int) {
        maskBitmap?.let { bmp ->
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.rgb(value, value, value))
            maskView.invalidate()
        }
    }

    private fun setupMoreMenu(activity: MainActivity) {
        binding.btnMore.setOnClickListener { view ->
            val popup = PopupMenu(requireContext(), view, Gravity.TOP)
            popup.menuInflater.inflate(R.menu.menu_mask_more, popup.menu)
            
            // Set initial toggle states
            popup.menu.findItem(R.id.menu_auto_play)?.isChecked = isAutoPlayEnabled
            popup.menu.findItem(R.id.menu_auto_stabilize)?.isChecked = 
                model.autoStabilizeWithMask.value ?: true
            popup.menu.findItem(R.id.menu_zoom_stabilized)?.isChecked = 
                (model.stabilizationZoom.value ?: 1.0f) > 1.0f
            popup.menu.findItem(R.id.menu_align_to_first)?.isChecked = 
                model.alignToFirstFrame.value ?: true

            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.menu_auto_play -> {
                        item.isChecked = !item.isChecked
                        isAutoPlayEnabled = item.isChecked
                        if (isAutoPlayEnabled) {
                            activity.startPreviewLoop(Int.MAX_VALUE)
                        } else {
                            activity.stopPreviewLoop()
                        }
                        true
                    }
                    R.id.menu_auto_stabilize -> {
                        item.isChecked = !item.isChecked
                        model.autoStabilizeWithMask.value = item.isChecked
                        true
                    }
                    R.id.menu_zoom_stabilized -> {
                        item.isChecked = !item.isChecked
                        model.toggleStabilizationZoom()
                        true
                    }
                    R.id.menu_align_to_first -> {
                        item.isChecked = !item.isChecked
                        model.toggleAlignToFirstFrame()
                        // No toast - button state change is confirmation
                        true
                    }
                    R.id.menu_erase_mask -> {
                        setMask(0)
                        // No toast - visual canvas change is confirmation
                        true
                    }
                    R.id.menu_fill_mask -> {
                        setMask(255)
                        // No toast - visual canvas change is confirmation
                        true
                    }
                    R.id.menu_invert_mask -> {
                        invertMask()
                        // No toast - visual canvas change is confirmation
                        true
                    }
                    R.id.menu_save_mask -> {
                        saveMaskAndClose(activity)
                        true
                    }
                    else -> false
                }
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                popup.setForceShowIcon(true)
            }
            popup.show()
        }
    }

    private fun invertMask() {
        maskBitmap?.let { bmp ->
            // THE FIX: Simply invert all pixels (0 -> 255, 255 -> 0)
            val pixels = IntArray(bmp.width * bmp.height)
            bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            
            for (i in pixels.indices) {
                val pixel = pixels[i]
                val gray = Color.red(pixel)
                val inverted = 255 - gray
                pixels[i] = Color.argb(Color.alpha(pixel), inverted, inverted, inverted)
            }
            
            bmp.setPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            maskView.setExternalMaskBitmap(bmp)
            maskView.invalidate()
        }
    }

    private fun saveMask() {
        maskBitmap?.let { bmp ->
            val maskMat = Mat()
            Utils.bitmapToMat(bmp, maskMat)
            
            // Convert to grayscale if needed
            if (maskMat.channels() > 1) {
                val grayMat = Mat()
                org.opencv.imgproc.Imgproc.cvtColor(maskMat, grayMat, org.opencv.imgproc.Imgproc.COLOR_RGBA2GRAY)
                maskMat.release()
                model.setFirstFrameMask(grayMat)
            } else {
                model.setFirstFrameMask(maskMat)
            }
        }
    }

    private fun saveMaskAndClose(activity: MainActivity) {
        saveMask()
        activity.showToast("Mask saved")
        activity.closeMaskEditorToTransform()
    }

    override fun onDestroyView() {
        // Auto-save mask before closing
        val activity = requireActivity() as? MainActivity
        if (activity != null) {
            saveMask()
            activity.stopPreviewLoop()
        }
        
        super.onDestroyView()
        _binding = null
    }
}
