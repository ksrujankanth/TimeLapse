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
        model.visualEffect.observe(viewLifecycleOwner) {
            updateView(model.isSaveReady.value == true)
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
                // Prefer the actual rendered image on disk (MFSR output or a previous
                // full-res still). Falling back to the preview bitmap downscales a
                // 40MP MFSR result to 2048px.
                val renderedImage = model.getProcessedImageFile()
                if (renderedImage != null) {
                    (requireActivity() as? MainActivity)?.shareFileUri(renderedImage)
                } else {
                    val bitmap = activity?.getRenderedVideoFrame() ?: activity?.getCurrentPreviewBitmap()
                    bitmap?.let { activity?.shareImage(it) } ?: showToast("No image available")
                }
            }
        }

        // Save Image to Gallery Button (20%)
        binding.btnSaveImageGallery.setOnClickListener {
            if (model.isFullResExportEnabled.value == true) {
                model.pendingFullResAction = "SAVE"
                showToast("Rendering full resolution image...")
                model.triggerFullResRender()
            } else {
                val imageName = model.getFormattedExportName("jpg")
                val renderedImage = model.getProcessedImageFile()
                if (renderedImage != null) {
                    model.saveFileToGalleryAsImage(renderedImage, imageName)
                    showToast("Saving image to device...")
                } else {
                    val bitmap = activity?.getRenderedVideoFrame() ?: activity?.getCurrentPreviewBitmap()
                    bitmap?.let {
                        model.saveImageToGallery(it, imageName)
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
        popup.menu.findItem(R.id.menu_advanced)?.isChecked =
            binding.panelAdvancedVideo.visibility == View.VISIBLE
        
        // Update resolution selection
        val currentRes = model.resolution.value
        when (currentRes) {
            com.dan.timelapse.utils.Settings.RES_720P -> popup.menu.findItem(R.id.menu_res_720p)?.isChecked = true
            com.dan.timelapse.utils.Settings.RES_1080P -> popup.menu.findItem(R.id.menu_res_1080p)?.isChecked = true
            com.dan.timelapse.utils.Settings.RES_4K -> popup.menu.findItem(R.id.menu_res_4k)?.isChecked = true
        }

        // 4K is a downscale-only cap. Hide it when the source cannot supply it.
        val input = model.framesInput.value
        val sourceLongEdge = if (input != null) maxOf(input.width, input.height) else 0
        popup.menu.findItem(R.id.menu_res_4k)?.isEnabled = sourceLongEdge >= 3840 || input == null
        
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_h265 -> {
                    item.isChecked = !item.isChecked
                    model.setEncodingH265(item.isChecked)
                    requestReRender("Re-encoding with ${if (item.isChecked) "H.265" else "H.264"}...")
                    true
                }
                R.id.menu_res_720p -> {
                    if (model.resolution.value != com.dan.timelapse.utils.Settings.RES_720P) {
                        model.setResolution(com.dan.timelapse.utils.Settings.RES_720P)
                        updateHelperText()
                        requestReRender("Re-rendering at 720p...")
                    }
                    true
                }
                R.id.menu_res_1080p -> {
                    if (model.resolution.value != com.dan.timelapse.utils.Settings.RES_1080P) {
                        model.setResolution(com.dan.timelapse.utils.Settings.RES_1080P)
                        updateHelperText()
                        requestReRender("Re-rendering at 1080p...")
                    }
                    true
                }
                R.id.menu_res_4k -> {
                    if (model.resolution.value != com.dan.timelapse.utils.Settings.RES_4K) {
                        model.setResolution(com.dan.timelapse.utils.Settings.RES_4K)
                        updateHelperText()
                        requestReRender("Re-rendering at 4K...")
                    }
                    true
                }
                R.id.menu_advanced -> {
                    item.isChecked = !item.isChecked
                    binding.panelAdvancedVideo.visibility =
                        if (item.isChecked) View.VISIBLE else View.GONE
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    /**
     * The Finish tab shows the settings of the file that is about to be saved.
     * Changing one of them must re-apply it, otherwise Save/Share silently
     * writes the previously rendered file. FPS-only changes are handled by the
     * fast remux path in MainActivity; the rest re-render.
     */
    private fun requestReRender(message: String) {
        if (model.framesInput.value == null) return
        showToast(message)
        model.triggerStudioProcess()
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

    private var syncingH265Switch = false

    private fun setupH265Support() {
        model.checkHardwareSupport(requireContext())
        model.isH265Supported.observe(viewLifecycleOwner) { supported ->
            binding.switchH265.isEnabled = supported
            binding.switchH265Sublabel.visibility = if (supported) View.GONE else View.VISIBLE
            if (!supported) {
                syncingH265Switch = true
                binding.switchH265.isChecked = false
                syncingH265Switch = false
                // Never leave the model claiming HEVC on hardware that cannot encode it.
                if (model.encodingH265.value == true) {
                    model.setEncodingH265(false)
                }
            }
        }

        model.encodingH265.observe(viewLifecycleOwner) { isEnabled ->
            if (binding.switchH265.isChecked != isEnabled) {
                syncingH265Switch = true
                binding.switchH265.isChecked = isEnabled
                syncingH265Switch = false
            }
            updateHelperText()
        }

        binding.switchH265.setOnCheckedChangeListener { _, isChecked ->
            if (syncingH265Switch) return@setOnCheckedChangeListener
            model.setEncodingH265(isChecked)
            requestReRender("Re-encoding with ${if (isChecked) "H.265" else "H.264"}...")
        }
    }

    private fun setupAdvancedPanel() {
        // Hidden by default; the "Advanced settings" menu item toggles it.
        binding.panelAdvancedVideo.visibility = View.GONE

        // Bitrate slider: 1..100% of the resolution/fps-aware baseline bitrate.
        val initialQuality = (model.fileQuality.value ?: 50).coerceIn(1, 100)
        binding.sliderBitrate.value = initialQuality.toFloat()
        binding.textBitrateLabel.text = "Bitrate: $initialQuality%"
        binding.sliderBitrate.addOnChangeListener { _, value, _ ->
            binding.textBitrateLabel.text = "Bitrate: ${value.toInt()}%"
        }
        binding.sliderBitrate.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {}

            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                model.setFileQuality(slider.value.toInt())
                requestReRender("Re-encoding at ${slider.value.toInt()}% bitrate...")
            }
        })

        // FPS slider: 1..60. Changing FPS alone is a fast timestamp remux.
        val initialFps = (model.outputFps.value ?: 30).coerceIn(1, 60)
        binding.sliderFps.valueFrom = 1f
        binding.sliderFps.valueTo = 60f
        binding.sliderFps.value = initialFps.toFloat()
        binding.textFpsLabel.text = "Frame Rate: $initialFps fps"
        binding.sliderFps.addOnChangeListener { _, value, _ ->
            binding.textFpsLabel.text = "Frame Rate: ${value.toInt()} fps"
        }
        binding.sliderFps.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {}

            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                val fps = slider.value.toInt().coerceIn(1, 60)
                model.setOutputFps(fps)
                // Keep the live preview speed in step with the export rate.
                model.setPreviewFps(fps)
                requestReRender("Changing frame rate to $fps fps...")
            }
        })
    }

    private fun updateView(isReady: Boolean) {
        val isMFSR = model.visualEffect.value == Effect.SUPER_RESOLUTION
        val videoReady = isReady && !isMFSR

        // Update button states based on whether render is ready
        binding.btnShareVideo.isEnabled = videoReady
        binding.btnShareImage.isEnabled = isReady
        binding.btnSaveVideoGallery.isEnabled = videoReady
        binding.btnSaveImageGallery.isEnabled = isReady
        binding.btnVideoSettings.isEnabled = !isMFSR

        binding.btnShareVideo.alpha = if (videoReady) 1.0f else if (isMFSR) 0.38f else 0.4f
        binding.btnSaveVideoGallery.alpha = binding.btnShareVideo.alpha
        binding.btnVideoSettings.alpha = if (isMFSR) 0.38f else 1.0f
        binding.btnShareImage.alpha = if (isReady) 1.0f else 0.4f
        binding.btnSaveImageGallery.alpha = if (isReady) 1.0f else 0.4f
        
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
        val input = model.framesInput.value
        val frameIndex = model.resolveSelectedSourceFrameIndex()
        val helperText = if (frameIndex != null && input != null) {
            if (model.processedVideoUri.value != null &&
                input is com.dan.timelapse.framesinput.VideoFramesInput) {
                val totalSeconds = model.renderedPositionMsForSourceFrame(frameIndex) / 1000
                val minutes = totalSeconds / 60
                val seconds = totalSeconds % 60
                "Frame %d/%d (%d:%02d)".format(frameIndex + 1, input.size, minutes, seconds)
            } else {
                "Frame ${frameIndex + 1} of ${input.size}"
            }
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
