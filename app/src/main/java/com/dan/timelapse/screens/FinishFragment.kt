package com.dan.timelapse.screens

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.lifecycle.Observer
import com.dan.timelapse.Effect
import com.dan.timelapse.MainActivity
import com.dan.timelapse.MainViewModel
import com.dan.timelapse.R
import com.dan.timelapse.databinding.TrayFinishBinding

class FinishFragment : AppFragment() {

    private var _binding: TrayFinishBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = TrayFinishBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupSplitButtons()
        setupH265Support()
        setupAdvancedPanel()

        model.isSaveReady.observe(viewLifecycleOwner, Observer { isReady ->
            updateView(isReady)
        })

        model.toastMessage.observe(viewLifecycleOwner) { message ->
            showToast(message)
        }
        
        // Observe processed image for MFSR output
        model.processedImageUri.observe(viewLifecycleOwner) { uri ->
            if (uri != null) {
                model.isPlaying.postValue(false)
                // The image display is handled in MainActivity's processedImageUri observer
                // VideoView is NOT hidden - it stays visible but covered by the high-res image
            }
        }
        
        // Update image helper text when selected frame changes
        model.selectedFramePositionMs.observe(viewLifecycleOwner) {
            updateImageHelperText()
        }

        // Observe all video settings for dynamic subtext
        model.resolution.observe(viewLifecycleOwner) { updateHelperText() }
        model.outputFps.observe(viewLifecycleOwner) { updateHelperText() }
        model.encodingH265.observe(viewLifecycleOwner) { updateHelperText() }

        updateView(model.isSaveReady.value ?: false)
        // Don't auto-play - wait for user to click
        
        // Disable video buttons for MFSR (image-only output)
        model.visualEffect.observe(viewLifecycleOwner) { effect ->
            val isMFSR = effect == Effect.SUPER_RESOLUTION
            binding.btnShareVideo.isEnabled = !isMFSR && model.isSaveReady.value == true
            binding.btnSaveVideoGallery.isEnabled = !isMFSR && model.isSaveReady.value == true
            binding.btnVideoSettings.isEnabled = !isMFSR
            val alpha = if (isMFSR) 0.38f else (if (model.isSaveReady.value == true) 1.0f else 0.4f)
            binding.btnShareVideo.alpha = alpha
            binding.btnSaveVideoGallery.alpha = alpha
            binding.btnVideoSettings.alpha = alpha
        }
    }

    private fun setupSplitButtons() {
        val activity = requireActivity() as? MainActivity

        // Share Video Button (60%)
        binding.btnShareVideo.setOnClickListener {
            if (model.isSaveReady.value == true) {
                activity?.shareVideo()
            }
        }

        // Save to Gallery Button (20%)
        binding.btnSaveVideoGallery.setOnClickListener {
            if (model.isSaveReady.value == true) {
                model.saveVideoToGallery(model.getFormattedExportName("mp4"))
                showToast("Saving video to device...")
            }
        }

        // Video Settings Button (20%)
        binding.btnVideoSettings.setOnClickListener { view ->
            showVideoSettingsMenu(view)
        }

        // Share Image Button (60%)
        binding.btnShareImage.setOnClickListener {
            if (model.isFullResExportEnabled.value == true) {
                model.pendingFullResAction = "SHARE"
                showToast("Rendering full resolution image...")
                model.triggerFullResRender()
            } else {
                val bitmap = activity?.getRenderedVideoFrame() ?: activity?.getCurrentPreviewBitmap()
                bitmap?.let { activity?.shareImage(it) } ?: showToast("No image available")
            }
        }

        // Save Image to Gallery Button (20%)
        binding.btnSaveImageGallery.setOnClickListener {
            if (model.isFullResExportEnabled.value == true) {
                model.pendingFullResAction = "SAVE"
                showToast("Rendering full resolution image...")
                model.triggerFullResRender()
            } else {
                val cacheFile = java.io.File(requireContext().cacheDir, model.getFormattedExportName("jpg"))
                if (cacheFile.exists()) {
                    model.saveFileToGalleryAsImage(cacheFile, model.getFormattedExportName("jpg"))
                    showToast("Saving image to device...")
                } else {
                    val bitmap = activity?.getRenderedVideoFrame() ?: activity?.getCurrentPreviewBitmap()
                    bitmap?.let {
                        model.saveImageToGallery(it, model.getFormattedExportName("jpg"))
                        showToast("Saving image to device...")
                    } ?: showToast("No image available")
                }
            }
        }

        // Image Settings Button (20%)
        binding.btnImageSettings.setOnClickListener {
            (requireActivity() as? MainActivity)?.enterFrameSelectionMode()
        }
    }

    private fun showVideoSettingsMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.menu_finish_video_settings, popup.menu)
        
        // Update H.265 toggle state
        popup.menu.findItem(R.id.menu_h265)?.isChecked = model.encodingH265.value == true
        
        // Update resolution selection
        val currentRes = model.resolution.value
        when (currentRes) {
            com.dan.timelapse.utils.Settings.RES_720P -> popup.menu.findItem(R.id.menu_res_720p)?.isChecked = true
            com.dan.timelapse.utils.Settings.RES_1080P -> popup.menu.findItem(R.id.menu_res_1080p)?.isChecked = true
            com.dan.timelapse.utils.Settings.RES_4K -> popup.menu.findItem(R.id.menu_res_4k)?.isChecked = true
        }
        
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_h265 -> {
                    item.isChecked = !item.isChecked
                    model.setEncodingH265(item.isChecked)
                    true
                }
                R.id.menu_res_720p -> {
                    model.setResolution(com.dan.timelapse.utils.Settings.RES_720P)
                    updateHelperText()
                    true
                }
                R.id.menu_res_1080p -> {
                    model.setResolution(com.dan.timelapse.utils.Settings.RES_1080P)
                    updateHelperText()
                    true
                }
                R.id.menu_res_4k -> {
                    model.setResolution(com.dan.timelapse.utils.Settings.RES_4K)
                    updateHelperText()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun updateHelperText() {
        val resolution = when (model.resolution.value) {
            com.dan.timelapse.utils.Settings.RES_720P -> "720p"
            com.dan.timelapse.utils.Settings.RES_1080P -> "1080p"
            com.dan.timelapse.utils.Settings.RES_4K -> "4K"
            else -> "1080p"
        }
        val fps = model.outputFps.value ?: 30
        
        // Build modifiers string dynamically
        val modifiers = StringBuilder()
        if (model.encodingH265.value == true) {
            modifiers.append(", HEVC")
        }
        
        binding.textVideoHelper.text = getString(R.string.helper_video_specs, resolution, fps, modifiers.toString())
    }

    // showImageSettingsMenu removed in favor of direct UI overlay

    private fun setupH265Support() {
        model.checkHardwareSupport(requireContext())
        model.isH265Supported.observe(viewLifecycleOwner) { supported ->
            binding.switchH265.isEnabled = supported
            binding.switchH265Sublabel.visibility = if (supported) View.GONE else View.VISIBLE
            if (!supported) {
                binding.switchH265.isChecked = false
            }
        }

        model.encodingH265.observe(viewLifecycleOwner) { isEnabled ->
            if (binding.switchH265.isChecked != isEnabled) {
                binding.switchH265.isChecked = isEnabled
            }
            updateHelperText()
        }

        binding.switchH265.setOnCheckedChangeListener { _, isChecked ->
            model.setEncodingH265(isChecked)
        }
    }

    private fun setupAdvancedPanel() {
        // Advanced panel is hidden by default, toggle via settings menu
        binding.panelAdvancedVideo.visibility = View.GONE
        
        // Setup bitrate slider
        binding.sliderBitrate.addOnChangeListener { slider, value, fromUser ->
            if (fromUser) {
                model.setFileQuality(value.toInt())
            }
            binding.textBitrateLabel.text = "Bitrate: ${value.toInt()}%"
        }
        
        // Setup FPS slider
        binding.sliderFps.addOnChangeListener { slider, value, fromUser ->
            if (fromUser) {
                model.setOutputFps(value.toInt())
            }
            binding.textFpsLabel.text = "Frame Rate: ${value.toInt()} fps"
        }
    }

    private fun updateView(isReady: Boolean) {
        // Update button states based on whether render is ready
        binding.btnShareVideo.isEnabled = isReady
        binding.btnShareImage.isEnabled = isReady
        binding.btnSaveVideoGallery.isEnabled = isReady
        binding.btnSaveImageGallery.isEnabled = isReady

        if (isReady) {
            binding.btnShareVideo.alpha = 1.0f
            binding.btnShareImage.alpha = 1.0f
            binding.btnSaveVideoGallery.alpha = 1.0f
            binding.btnSaveImageGallery.alpha = 1.0f
        } else {
            binding.btnShareVideo.alpha = 0.4f
            binding.btnShareImage.alpha = 0.4f
            binding.btnSaveVideoGallery.alpha = 0.4f
            binding.btnSaveImageGallery.alpha = 0.4f
        }
        
        // Update helper text with resolution info
        val resolution = when (model.resolution.value) {
            com.dan.timelapse.utils.Settings.RES_720P -> "720p"
            com.dan.timelapse.utils.Settings.RES_1080P -> "1080p"
            com.dan.timelapse.utils.Settings.RES_4K -> "4K"
            else -> "1080p"
        }
        val fps = model.outputFps.value ?: 30
        
        // Build modifiers string dynamically
        val modifiers = StringBuilder()
        if (model.encodingH265.value == true) {
            modifiers.append(", HEVC")
        }
        
        binding.textVideoHelper.text = getString(R.string.helper_video_specs, resolution, fps, modifiers.toString())
        
        // Update image helper text with selected frame info
        updateImageHelperText()
    }
    
    private fun updateImageHelperText() {
        val selectedFrame = model.selectedFramePositionMs.value
        val helperText = if (selectedFrame != null) {
            val seconds = selectedFrame / 1000.0
            "Frame at %.2fs".format(seconds)
        } else {
            getString(R.string.finish_image_helper_text)
        }
        binding.textImageHelper.text = helperText
    }

    override fun onResume() {
        super.onResume()
        updateView(model.isSaveReady.value ?: false)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
