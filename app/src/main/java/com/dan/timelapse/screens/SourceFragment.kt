package com.dan.timelapse.screens

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.dan.timelapse.MainActivity
import com.dan.timelapse.MainViewModel
import com.dan.timelapse.R
import com.dan.timelapse.databinding.TraySourceBinding
import com.dan.timelapse.framesinput.FramesInput
import com.dan.timelapse.framesinput.ImagesFramesInput
import com.google.android.material.slider.RangeSlider
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SourceFragment : Fragment() {

    private val model: MainViewModel by activityViewModels()
    private lateinit var binding: TraySourceBinding
    private var scrubJob: Job? = null
    private var isDraggingSlider = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = TraySourceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupSplitButtons()
        observeState()

        binding.rangeSliderDuration.setLabelFormatter { value ->
            value.toInt().toString()
        }

        model.framesInput.observe(viewLifecycleOwner) { input ->
            if (input == null) {
                binding.layoutInitial.visibility = View.VISIBLE
                binding.layoutLoaded.visibility = View.GONE
            } else {
                binding.layoutInitial.visibility = View.GONE
                binding.layoutLoaded.visibility = View.VISIBLE
                setupLoadedState(input)
            }
        }
        
        // Update button text based on last source type
        model.lastSourceType.observe(viewLifecycleOwner) { type ->
            updateHeroButtonText(type)
        }
    }

    private fun setupSplitButtons() {
        val activity = requireActivity() as MainActivity

        // Hero Button: 80% Action - triggers last used picker
        binding.btnLoadHero.setOnClickListener {
            when (model.lastSourceType.value) {
                MainViewModel.SourceType.VIDEO -> activity.openPicker()
                MainViewModel.SourceType.IMAGES -> activity.openImagePicker()
                MainViewModel.SourceType.FOLDER -> activity.openFolderPicker()
                else -> activity.openPicker() // Default to video
            }
        }

        // Hero Button: 20% Menu - opens PopupMenu
        binding.btnLoadMenu.setOnClickListener { view ->
            showLoadOptionsMenu(view)
        }

// Change Media Hero Button: 80% Action - opens picker to select new media
        binding.btnChangeMedia.setOnClickListener {
            when (model.lastSourceType.value) {
                MainViewModel.SourceType.VIDEO -> (requireActivity() as MainActivity).openPicker()
                MainViewModel.SourceType.IMAGES -> (requireActivity() as MainActivity).openImagePicker()
                MainViewModel.SourceType.FOLDER -> (requireActivity() as MainActivity).openFolderPicker()
else -> (requireActivity() as MainActivity).openPicker()
            }
        }

        // Change Media Menu: 20%
        binding.btnChangeMediaMenu.setOnClickListener { view ->
            showChangeMediaMenu(view)
        }

        // Trim slider - OnChangeListener for real-time preview seeking
        binding.rangeSliderDuration.addOnChangeListener { slider, _, fromUser ->
            if (fromUser && slider.values.size >= 2) {
                var startFrame = slider.values[0].toInt()
                var endFrame = slider.values[1].toInt()
                
                // CONSTRAINT 1: Prevent thumbs from touching - enforce minimum 1 frame separation
                if (endFrame <= startFrame) {
                    // If thumbs touch/cross, adjust endFrame to be at least startFrame + 1
                    endFrame = startFrame + 1
                    // Clamp to max value
                    endFrame = endFrame.coerceAtMost(slider.valueTo.toInt())
                    // Update slider values
                    slider.values = listOf(startFrame.toFloat(), endFrame.toFloat())
                }
                
val selectedCount = endFrame - startFrame + 1
                val framesInput = model.framesInput.value
                val isImageSequence = framesInput is ImagesFramesInput
                val videoFps = framesInput?.fps ?: 30
                val totalFrames = framesInput?.size ?: 300
                val safeMaxFrame = slider.valueTo
                
                // Color-coded status: Orange if partial, Green if full
                val isPartial = (startFrame > 1) || (endFrame < safeMaxFrame.toInt())
                
                if (isImageSequence) {
                    if (isPartial) {
                        binding.textSelectionStatus.text = getString(R.string.status_preview_chunk_images, selectedCount)
                        binding.textSelectionStatus.setTextColor(resources.getColor(R.color.colorWarning, null))
                    } else {
                        binding.textSelectionStatus.text = getString(R.string.status_full_images, totalFrames)
                        binding.textSelectionStatus.setTextColor(resources.getColor(R.color.colorSuccess, null))
                    }
                } else {
                    val startSec = ((startFrame - 1) / videoFps.coerceAtLeast(1)).toInt()
                    val endSec = ((endFrame - 1) / videoFps.coerceAtLeast(1)).toInt()
                    val startTimeStr = formatTime(startSec)
                    val endTimeStr = formatTime(endSec)
                    
                    if (isPartial) {
                        val chunkDurationSec = endSec - startSec
                        val chunkTimeStr = formatTime(chunkDurationSec)
                        binding.textSelectionStatus.text = getString(R.string.status_preview_chunk_video, startTimeStr, endTimeStr, chunkTimeStr)
                        binding.textSelectionStatus.setTextColor(resources.getColor(R.color.colorWarning, null))
                    } else {
                        val fullSec = ((safeMaxFrame - 1) / videoFps.coerceAtLeast(1)).toInt()
                        val fullTimeStr = formatTime(fullSec)
                        binding.textSelectionStatus.text = getString(R.string.status_full_video, fullTimeStr)
                        binding.textSelectionStatus.setTextColor(resources.getColor(R.color.colorSuccess, null))
                    }
                }

                // 1. SAVE THE SELECTION TO THE VIEWMODEL SO THE RENDERER USES IT
                val input = model.framesInput.value
                model.setTrimRange(startFrame.toFloat(), endFrame.toFloat())

                // 2. IMMEDIATE SEEK: jump video to active thumb position when user drags slider
                val fps = model.framesInput.value?.fps?.coerceAtLeast(1) ?: 30
                val activeThumbIndex = slider.activeThumbIndex
                val activeFrame = slider.values[activeThumbIndex].toInt()
                val targetMs = ((activeFrame - 1) * 1000L) / fps.toLong()
                if (input is com.dan.timelapse.framesinput.VideoFramesInput) {
                    (activity as? MainActivity)?.seekVideoTo(targetMs.toInt())
                }
            }
        }
        
        // OnSliderTouchListener - Claim touch events from parent tray and update model only when drag ends
        binding.rangeSliderDuration.addOnSliderTouchListener(object : com.google.android.material.slider.RangeSlider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.RangeSlider) {
                isDraggingSlider = true
                // Force the parent tray to let us slide horizontally without interfering
                slider.parent?.requestDisallowInterceptTouchEvent(true)
            }

            override fun onStopTrackingTouch(slider: com.google.android.material.slider.RangeSlider) {
                isDraggingSlider = false
                slider.parent?.requestDisallowInterceptTouchEvent(false)
                
                // Update the model exactly once when the user lifts their finger
                if (slider.values.size >= 2) {
                    model.setTrimRange(slider.values[0], slider.values[1])
                }
            }
        })
        
        // Fill screen toggle
        binding.switchZoomToFill.setOnCheckedChangeListener { _, isChecked ->
            model.setZoomToFill(isChecked)
        }
    }

    private fun showLoadOptionsMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.menu_source_options, popup.menu)
        
        popup.setOnMenuItemClickListener { item ->
            val activity = requireActivity() as MainActivity
            when (item.itemId) {
                R.id.menu_load_last -> {
                    activity.onOpenLastSelection()
                    true
                }
                R.id.menu_pick_video -> {
                    model.setLastSourceType(MainViewModel.SourceType.VIDEO)
                    activity.openPicker()
                    true
                }
                R.id.menu_pick_images -> {
                    model.setLastSourceType(MainViewModel.SourceType.IMAGES)
                    activity.openImagePicker()
                    true
                }
                R.id.menu_pick_folder -> {
                    model.setLastSourceType(MainViewModel.SourceType.FOLDER)
                    activity.openFolderPicker()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showChangeMediaMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.menu_source_change, popup.menu)
        
        popup.setOnMenuItemClickListener { item ->
            val activity = requireActivity() as MainActivity
            when (item.itemId) {
                R.id.menu_change_video -> {
                    model.setLastSourceType(MainViewModel.SourceType.VIDEO)
                    activity.openPicker()
                    true
                }
                R.id.menu_change_images -> {
                    model.setLastSourceType(MainViewModel.SourceType.IMAGES)
                    activity.openImagePicker()
                    true
                }
                R.id.menu_change_folder -> {
                    model.setLastSourceType(MainViewModel.SourceType.FOLDER)
                    activity.openFolderPicker()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun updateHeroButtonText(type: MainViewModel.SourceType?) {
        val text = when (type) {
            MainViewModel.SourceType.VIDEO -> getString(R.string.load_video)
            MainViewModel.SourceType.IMAGES -> getString(R.string.load_images)
            MainViewModel.SourceType.FOLDER -> getString(R.string.load_folder)
            else -> getString(R.string.load_media)
        }
        binding.btnLoadHero.text = text
    }

    private fun observeState() {
        // Observe source type changes to update button text
        model.lastSourceType.observe(viewLifecycleOwner) { type ->
            updateHeroButtonText(type)
        }
        
        // Observe trim range and input changes for slider setup
        model.trimRange.observe(viewLifecycleOwner) { range ->
            if (!isDraggingSlider && range != null) {
                val frameCount = (model.framesInput.value?.size ?: 257).toFloat()
                val safeMaxFrame = maxOf(2f, frameCount)
                binding.rangeSliderDuration.valueFrom = 1f
                binding.rangeSliderDuration.valueTo = safeMaxFrame
                binding.rangeSliderDuration.values = listOf(
                    range.first.coerceIn(1f, safeMaxFrame),
                    range.second.coerceIn(1f, safeMaxFrame)
                )
                updateSelectionStatus(range.first.toInt(), range.second.toInt(), safeMaxFrame.toInt())
            }
             // Don't call setupTrimControls here - it causes the loop
        }
        model.framesInput.observe(viewLifecycleOwner) {
            setupTrimControls()
        }
    }
    
    private fun updateSelectionStatus(startFrame: Int, endFrame: Int, safeMaxFrame: Int) {
        val input = model.framesInput.value ?: return
        val isImageSequence = input is ImagesFramesInput
        val fps = input.fps.coerceAtLeast(1)
        val safeMax = safeMaxFrame.coerceAtLeast(1)
        
        val isPartial = (startFrame > 1) || (endFrame < safeMax)
        val colorRes = if (isPartial) R.color.colorWarning else R.color.colorSuccess

        if (isImageSequence) {
            val selectedCount = endFrame - startFrame + 1
            binding.textSelectionStatus.text = "Duration: $selectedCount frames"
            binding.textSelectionStatus.setTextColor(resources.getColor(colorRes, null))
        } else {
            val durationSec = ((endFrame - startFrame) / fps).toInt()
            binding.textSelectionStatus.text = "Duration: ${formatTime(durationSec)}"
            binding.textSelectionStatus.setTextColor(resources.getColor(colorRes, null))
        }
    }
    
    private fun setupTrimControls() {
        val framesInput = model.framesInput.value ?: return
        val frameCount = framesInput.size.toFloat()
        val isImageSequence = framesInput is ImagesFramesInput
        
        // THE FIX: Ensure valueTo is always strictly greater than valueFrom (minimum 2)
        val safeMaxFrame = maxOf(2f, frameCount)
        
        // Enable continuous floating-point values (disable step validation)
        binding.rangeSliderDuration.stepSize = 0f
        
        // Check if images have varying resolutions (for Fill toggle visibility)
        val hasVaryingResolutions = if (isImageSequence) {
            (framesInput as ImagesFramesInput).hasVaryingResolutions
        } else false
        
        // Show status text when media is loaded
        binding.textSelectionStatus.visibility = View.VISIBLE
        
        // Restore saved trim selection from ViewModel, fallback to 300-frame default
        val fps = framesInput.fps.coerceAtLeast(1).toFloat()
        val (startValue, endValue) = if (isImageSequence) {
            // Images: Read from trimRange, fallback to 300 default
            val savedStart = model.trimRange.value?.first ?: 1f
            val savedEnd = model.trimRange.value?.second ?: minOf(300f, safeMaxFrame)
            Pair(savedStart, savedEnd)
        } else {
            // Video: Check if we have a saved end time
            val savedEndMs = model.trimEndMs.value ?: 0L
            
            if (savedEndMs > 0L) {
                // Restore the user's previous selection
                val savedStartMs = model.trimStartMs.value ?: 0L
                val savedStartFrame = (savedStartMs * fps / 1000f) + 1f
                val savedEndFrame = (savedEndMs * fps / 1000f) + 1f
                Pair(savedStartFrame, savedEndFrame.coerceAtMost(safeMaxFrame))
            } else {
                // First load only: Apply the 300-frame safe default
                Pair(1f, minOf(300f, safeMaxFrame))
            }
        }

        // Toggle UI based on media type AND varying resolutions
        // Show Fill toggle ONLY for images with varying resolutions (not for video)
        val showFillToggle = isImageSequence && hasVaryingResolutions
        binding.switchZoomToFill.visibility = if (showFillToggle) View.VISIBLE else View.GONE
        binding.textFillScreen.visibility = if (showFillToggle) View.VISIBLE else View.GONE

        if (isImageSequence) {
            // Images: Show RangeSlider with 300-frame default
            binding.rangeSliderDuration.visibility = View.VISIBLE
            
            if (frameCount > 1) {
                binding.rangeSliderDuration.isEnabled = true
                binding.rangeSliderDuration.valueFrom = 1f
                binding.rangeSliderDuration.valueTo = safeMaxFrame
                
                val start = Math.round(startValue.coerceIn(1f, safeMaxFrame)).toFloat()
                val end = Math.round(endValue.coerceIn(1f, safeMaxFrame)).toFloat()
                
                binding.rangeSliderDuration.minSeparation = 0f
                
                val finalEnd = if (end <= start) (start + 1f).coerceAtMost(safeMaxFrame) else end
                binding.rangeSliderDuration.values = listOf(start, finalEnd)
                
                // Color-coded status: Orange if partial (start > 1 or end < max), Green if full
                val isPartial = (start > 1f) || (finalEnd < safeMaxFrame)
                val selectedCount = finalEnd.toInt() - start.toInt() + 1
                
                if (isPartial) {
                    binding.textSelectionStatus.text = getString(R.string.status_preview_chunk_images, selectedCount)
                    binding.textSelectionStatus.setTextColor(resources.getColor(R.color.colorWarning, null))
                } else {
                    binding.textSelectionStatus.text = getString(R.string.status_full_images, frameCount.toInt())
                    binding.textSelectionStatus.setTextColor(resources.getColor(R.color.colorSuccess, null))
                }
            } else {
                binding.rangeSliderDuration.isEnabled = false
                binding.rangeSliderDuration.values = listOf(1f, 2f)
                binding.textSelectionStatus.visibility = View.GONE
            }
        } else {
            // Video: Show Trim RangeSlider with 300-frame / 10-second default
            binding.rangeSliderDuration.visibility = View.VISIBLE
            
            if (frameCount > 1) {
                // Enable slider and set its range
                binding.rangeSliderDuration.isEnabled = true
                binding.rangeSliderDuration.valueFrom = 1f
                binding.rangeSliderDuration.valueTo = safeMaxFrame
                
                // Set slider values (clamped to valid range)
                val start = Math.round(startValue.coerceIn(1f, safeMaxFrame)).toFloat()
                val end = Math.round(endValue.coerceIn(1f, safeMaxFrame)).toFloat()
                
                // CONSTRAINT 1: Prevent crash - cannot use minSeparation with stepSize
                // Set to 0 to disable, validation handled in code below
                binding.rangeSliderDuration.minSeparation = 0f
                
                // Ensure start <= end, adjusting end if they are too close or invalid
                val finalEnd = if (end <= start) (start + 1f).coerceAtMost(safeMaxFrame) else end
                
                binding.rangeSliderDuration.values = listOf(start, finalEnd)
                
                // Color-coded status: Orange if partial (start > 1 or end < max), Green if full
                val isPartial = (start > 1f) || (finalEnd < safeMaxFrame)
                val fps = framesInput.fps.coerceAtLeast(1)
                
                // Format time strings for status display
                val startSec = ((start - 1) / fps).toInt()
                val endSec = ((finalEnd - 1) / fps).toInt()
                val startTimeStr = formatTime(startSec)
                val endTimeStr = formatTime(endSec)
                
                if (isPartial) {
                    val chunkDurationSec = endSec - startSec
                    val chunkTimeStr = formatTime(chunkDurationSec)
                    binding.textSelectionStatus.text = getString(R.string.status_preview_chunk_video, startTimeStr, endTimeStr, chunkTimeStr)
                    binding.textSelectionStatus.setTextColor(resources.getColor(R.color.colorWarning, null))
                } else {
                    // Calculate full video duration
                    val fullSec = ((safeMaxFrame - 1) / fps).toInt()
                    val fullTimeStr = formatTime(fullSec)
                    binding.textSelectionStatus.text = getString(R.string.status_full_video, fullTimeStr)
                    binding.textSelectionStatus.setTextColor(resources.getColor(R.color.colorSuccess, null))
                }
            } else {
                // Disable slider if only one frame/item
                binding.rangeSliderDuration.isEnabled = false
                binding.rangeSliderDuration.values = listOf(1f, 2f)
                binding.textSelectionStatus.visibility = View.GONE
            }
        }

        // Set current zoom to fill state
        binding.switchZoomToFill.isChecked = model.isZoomToFill.value == true
    }
    
    private fun formatTime(seconds: Int): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return if (mins > 0) {
            String.format("%d:%02d", mins, secs)
        } else {
            String.format("0:%02d", secs)
        }
    }

    private fun setupLoadedState(@Suppress("UNUSED_PARAMETER") framesInput: FramesInput) {
        // Initial setup for controls based on input type
        setupTrimControls()
    }
}
