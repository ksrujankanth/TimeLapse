package com.dan.timelapse.screens

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.dan.timelapse.Effect
import com.dan.timelapse.MainActivity
import com.dan.timelapse.MainViewModel
import com.dan.timelapse.R
import com.dan.timelapse.databinding.TrayStudioBinding
import com.dan.timelapse.ui.EffectPickerDialog

class StudioFragment : Fragment() {
    private val model: MainViewModel by activityViewModels()
    private var _binding: TrayStudioBinding? = null
    private val binding get() = _binding!!

    // Spinner position -> Effect. Effect.UI_ORDER is the single source of truth for the
    // picker order, so the labels, the previews and the render cannot drift apart.
    private val effectOrdinals: IntArray = Effect.UI_ORDER.map { it.ordinal }.toIntArray()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = TrayStudioBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupSliderMaxBasedOnRAM()
        setupEffects()
        setupGenerateButton()
    }

    private fun setupSliderMaxBasedOnRAM() {
        val activityManager = requireActivity().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val totalMemoryGB = memoryInfo.totalMem / (1024.0 * 1024.0 * 1024.0)

        // High-end device: 6GB+ RAM = 30 max, Lower-end: 20 max
        val maxSliderValue = if (totalMemoryGB >= 6.0) 30 else 20
        binding.sliderSampling.valueTo = maxSliderValue.toFloat()

        // Ensure current value doesn't exceed new max
        if (binding.sliderSampling.value > maxSliderValue) {
            binding.sliderSampling.value = maxSliderValue.toFloat()
        }
    }

    private fun setupEffects() {
        // The picker is a full-screen gallery, not a dropdown: twelve animated previews
        // cannot be shown at a legible size inside a popup anchored to this field.
        binding.spinnerEffect.setOnClickListener { showEffectPicker() }
        binding.spinnerEffectLayout.setEndIconOnClickListener { showEffectPicker() }

        // Get current effect ordinal and find matching spinner position
        val currentEffectOrdinal = model.visualEffect.value?.ordinal ?: 0
        val spinnerPosition = effectOrdinals.indexOf(currentEffectOrdinal).takeIf { it >= 0 } ?: 0
        bindEffectSelection(spinnerPosition)

        // Observe endless state changes
        model.isEndless.observe(viewLifecycleOwner) { isEndless ->
            binding.switchEndless.isChecked = isEndless
            updateSliderState(isEndless)
        }

        // Endless toggle - only enable/disable based on effect
        binding.switchEndless.setOnCheckedChangeListener { _, isChecked ->
            // Check if current effect supports endless
            val currentEffectOrdinalValue = model.visualEffect.value?.ordinal ?: 0
            val currentEffect = Effect.entries.getOrElse(currentEffectOrdinalValue) { Effect.NONE }

            if (!currentEffect.supportsEndless) {
                binding.switchEndless.isChecked = false
                return@setOnCheckedChangeListener
            }
            model.setEndless(isChecked)
        }

        // Observe visual effect changes to update UI (e.g., when loading from saved state)
        model.visualEffect.observe(viewLifecycleOwner) { effect ->
            val effectOrdinal = effect.ordinal
            val position = effectOrdinals.indexOf(effectOrdinal).takeIf { it >= 0 } ?: 0
            updateEndlessToggleState(position, effectOrdinals)
            
            val isMFSR = effect == Effect.SUPER_RESOLUTION
            
            // IMPORTANT: This must run AFTER updateEndlessToggleState because that method
            // also touches sliderSampling.isEnabled (for NONE effect handling).
            // For SUPER_RESOLUTION we always force disable regardless of other states.
            if (isMFSR) {
                binding.sliderSampling.isEnabled = false
                binding.sliderSampling.alpha = 0.38f
                binding.textSamplingLabel.text = "Intensity (Locked for Super Res)"
                // Also disable endless toggle — SUPER_RESOLUTION doesn't support it
                binding.switchEndless.isEnabled = false
                binding.switchEndless.alpha = 0.38f
                binding.switchEndless.isChecked = false
            } else {
                val blendingValue = model.frameBlending.value
                binding.textSamplingLabel.text = "Intensity (${blendingValue ?: 10} frames)"
            }

            // The speed summary must not promise a stride the renderer will skip.
            updateSpeedSummary()
        }

        // Sampling slider - set initial value with safe clamping
        val initialValue = (model.frameBlending.value ?: 10).toFloat()
        binding.sliderSampling.value = initialValue.coerceIn(
            binding.sliderSampling.valueFrom, 
            binding.sliderSampling.valueTo
        )
        updateSamplingLabel(binding.sliderSampling.value.toInt())
        
        // Force the UI to immediately respect the saved Endless toggle state on load
        updateSliderState(model.isEndless.value ?: false)

        binding.sliderSampling.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                // When user interacts with slider, turn off endless mode
                model.setEndless(false)
                
                // Hard floor at 2 frames - single-frame stacking is meaningless
                val safeValue = value.toInt().coerceAtLeast(2)
                model.setFrameBlending(safeValue)
                updateSamplingLabel(safeValue)
            }
        }

        // Lock the blending slider - observe framesInput to dynamically cap slider max
        model.framesInput.observe(viewLifecycleOwner) { input ->
            val frameCount = input?.size?.toFloat() ?: 30f
            val minSliderVal = binding.sliderSampling.valueFrom // Usually 2.0f
            
            // 1. Guard against media with too few frames (e.g. 1 image)
            if (frameCount < minSliderVal) {
                binding.sliderSampling.isEnabled = false
                // Must be strictly > valueFrom (Material Slider requirement)
                binding.sliderSampling.valueTo = minSliderVal + 1f
            } else {
                binding.sliderSampling.isEnabled = true
                val maxAllowed = minOf(30f, frameCount)
                binding.sliderSampling.valueTo = maxOf(minSliderVal + 1f, maxAllowed)
            }
            
            // 2. Safely snap the current thumb value into the new legal bounds
            val currentValue = binding.sliderSampling.value
            binding.sliderSampling.value = currentValue.coerceIn(
                binding.sliderSampling.valueFrom, 
                binding.sliderSampling.valueTo
            )
        }
        
        // Observe trimRange to dynamically cap slider max based on selected chunk
        model.trimRange.observe(viewLifecycleOwner) { range ->
            if (range != null) {
                val totalSelectedFrames = (range.second - range.first + 1).toInt()
                val minSliderVal = binding.sliderSampling.valueFrom
                // Must be strictly > valueFrom (Material Slider requirement)
                val safeMax = minOf(30f, maxOf(minSliderVal + 1f, totalSelectedFrames.toFloat()))
                
                if (binding.sliderSampling.valueTo != safeMax) {
                    binding.sliderSampling.valueTo = safeMax
                }
                
                if (binding.sliderSampling.value > safeMax) {
                    binding.sliderSampling.value = safeMax
                    model.setFrameBlending(safeMax.toInt())
                }
            }
        }
        
        // Observe frameBlending changes from ViewModel with safe clamping
        model.frameBlending.observe(viewLifecycleOwner) { blendingValue ->
            // When ViewModel changes, NEVER apply blindly. Clamp it.
            val targetValue = blendingValue?.toFloat() ?: binding.sliderSampling.valueFrom
            val safeValue = targetValue.coerceIn(
                binding.sliderSampling.valueFrom, 
                binding.sliderSampling.valueTo
            )
            
            if (binding.sliderSampling.value != safeValue) {
                binding.sliderSampling.value = safeValue
            }
            
            // Update the label to reflect the blending value
            binding.textSamplingLabel.text = "Intensity (${blendingValue ?: 10} frames)"
        }
    }

    private fun showEffectPicker() {
        val currentEffectOrdinal = model.visualEffect.value?.ordinal ?: 0
        val selected = effectOrdinals.indexOf(currentEffectOrdinal).takeIf { it >= 0 } ?: 0
        EffectPickerDialog.show(requireContext(), selected) { position ->
            model.setVisualEffect(effectOrdinals.getOrElse(position) { Effect.NONE.ordinal })
            bindEffectSelection(position)
        }
    }

    /** Updates every control that depends on the chosen picker position. */
    private fun bindEffectSelection(position: Int) {
        val effects = resources.getStringArray(R.array.effect_names)
        val descriptions = resources.getStringArray(R.array.effect_descriptions)
        binding.spinnerEffect.setText(effects.getOrElse(position) { effects[0] }, false)
        binding.textEffectDescription.text = descriptions.getOrElse(position) { descriptions[0] }

        updateEndlessToggleState(position, effectOrdinals)
        updateSliderState(model.isEndless.value ?: false)

        // If endless was enabled but the new effect doesn't support it, switch it off.
        val effect = Effect.entries.getOrElse(effectOrdinals.getOrElse(position) { 0 }) { Effect.NONE }
        if (!effect.supportsEndless && model.isEndless.value == true) {
            model.setEndless(false)
        }
    }

    private fun updateEndlessToggleState(spinnerPosition: Int, effectOrdinals: IntArray) {
        val effectOrdinal = effectOrdinals.getOrNull(spinnerPosition) ?: 0
        val effect = Effect.entries.getOrElse(effectOrdinal) { Effect.NONE }
        val supportsEndless = effect.supportsEndless
        val isNoneEffect = effect == Effect.NONE
        val isMFSR = effect == Effect.SUPER_RESOLUTION

        // THE FIX: Check current endless state to prevent race conditions during state restoration
        val isEndless = model.isEndless.value == true

        binding.switchEndless.isEnabled = supportsEndless

        // Visual feedback - grey out when not supported
        if (!supportsEndless) {
            binding.switchEndless.alpha = 0.38f
            binding.switchEndless.isChecked = false
        } else {
            binding.switchEndless.alpha = 1.0f
        }

        // THE FIX: Slider state must be disabled if Endless is ON, regardless of observer execution order
        val shouldDisableSlider = isNoneEffect || isMFSR || isEndless
        binding.sliderSampling.isEnabled = !shouldDisableSlider
        binding.sliderSampling.alpha = if (shouldDisableSlider) 0.38f else 1.0f
        
        // Show/hide None effect helper text
        binding.textNoneEffectHelper.visibility = if (isNoneEffect) View.VISIBLE else View.GONE
        binding.textHelperStacking.visibility = if (isNoneEffect) View.GONE else View.VISIBLE
    }

    private fun updateSliderState(isEndless: Boolean) {
        // Don't touch slider for NONE or SUPER_RESOLUTION — they have their own disabled state
        val currentEffect = model.visualEffect.value ?: Effect.NONE
        if (currentEffect != Effect.NONE && currentEffect != Effect.SUPER_RESOLUTION) {
            binding.sliderSampling.isEnabled = !isEndless
            binding.sliderSampling.alpha = if (isEndless) 0.38f else 1.0f
        }
    }

    private fun updateSamplingLabel(value: Int) {
        binding.textSamplingLabel.text = "Intensity $value frames"
    }

    private fun setupGenerateButton() {
        binding.btnGenerate.setOnClickListener {
            // Phase 1: Force sync: read the current selection from the UI and push to VM
            val uiEffectsArray = resources.getStringArray(R.array.effect_names)
            val currentText = binding.spinnerEffect.text?.toString() ?: ""
            val indexFromUI = uiEffectsArray.indexOf(currentText).coerceAtLeast(0)
            val ordinal = effectOrdinals.getOrNull(indexFromUI) ?: Effect.NONE.ordinal
            model.setVisualEffect(ordinal)

            // Phase 2: Trigger render now that VM state is synchronized
            model.triggerStudioProcess()
        }

        binding.btnGenerateMenu.setOnClickListener { view ->
            showGenerateOptionsSheet()
        }
        
        // Observe speed multiplier to update the summary text
        model.speedMultiplier.observe(viewLifecycleOwner) { updateSpeedSummary() }
    }

    /**
     * Single source for the "speed" copy in this tray. Super Resolution ignores
     * input speed entirely, so saying "60x, skipping 59 frames" there is a lie.
     */
    private fun updateSpeedSummary() {
        val speed = model.speedMultiplier.value ?: 1
        val baseDesc = "Controls how many frames are merged together to create the final look."
        val applicable = model.isInputSpeedApplicable()
        when {
            !applicable -> {
                binding.tvSpeedSummary.visibility = View.VISIBLE
                binding.tvSpeedSummary.text = "Input speed not used by Super Resolution"
                binding.textHelperStacking.text = baseDesc
            }
            speed <= 1 -> {
                binding.tvSpeedSummary.visibility = View.GONE
                binding.textHelperStacking.text = baseDesc
            }
            else -> {
                val framesSkipped = speed - 1
                binding.tvSpeedSummary.visibility = View.VISIBLE
                binding.tvSpeedSummary.text = "${speed}x Speed (Skipping $framesSkipped frames between captures)"
                binding.textHelperStacking.text = "$baseDesc • (Speed: ${speed}x, skipping ${framesSkipped} frames)"
            }
        }
    }

    private fun showGenerateOptionsSheet() {
        val bottomSheetDialog = com.google.android.material.bottomsheet.BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.dialog_speed_slider, null)
        bottomSheetDialog.setContentView(view)

        val sliderInput = view.findViewById<com.google.android.material.slider.Slider>(R.id.sliderInputSpeed)
        val tvInputLabel = view.findViewById<android.widget.TextView>(R.id.tvInputSpeedLabel)
        val tvInputHint = view.findViewById<android.widget.TextView>(R.id.tvInputSpeedHint)
        val sliderOutput = view.findViewById<com.google.android.material.slider.Slider>(R.id.sliderOutputSpeed)
        val tvOutputLabel = view.findViewById<android.widget.TextView>(R.id.tvOutputSpeedLabel)

        // Configure Input Slider
        sliderInput.valueFrom = 0f
        sliderInput.valueTo = (model.speedOptions.size - 1).toFloat()
        val currentSpeed = model.speedMultiplier.value ?: 1
        sliderInput.value = model.speedOptions.indexOf(currentSpeed).coerceAtLeast(0).toFloat()

        // Super Resolution consumes every frame for sub-pixel coverage, so the
        // renderer deliberately ignores this value. A live slider that changes
        // nothing is worse than a disabled one that says why.
        if (model.isInputSpeedApplicable()) {
            tvInputLabel.text = "Input speed: ${currentSpeed}x"
        } else {
            sliderInput.isEnabled = false
            sliderInput.alpha = 0.38f
            tvInputLabel.text = "Input speed: not used by Super Resolution"
            tvInputHint.text = "Super Resolution needs every frame"
        }

        sliderInput.addOnChangeListener { _, value, _ ->
            val speed = model.speedOptions[value.toInt()]
            tvInputLabel.text = "Input speed: ${speed}x"
            model.setSpeedMultiplierFromIndex(value.toInt())
        }
        
        sliderInput.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {}
            
            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                // Scrubbing input speed is exploratory: re-render the preview, but
                // do not auto-save one file per slider stop.
                model.triggerStudioProcess(suppressAutoSave = true)
            }
        })

        // Configure Output Slider
        sliderOutput.valueFrom = 0f
        sliderOutput.valueTo = (model.previewFpsOptions.size - 1).toFloat()
        val currentFps = model.previewFps.value ?: 30
        sliderOutput.value = model.previewFpsOptions.indexOf(currentFps).coerceAtLeast(0).toFloat()
        tvOutputLabel.text = "Preview: $currentFps FPS"

        sliderOutput.addOnChangeListener { _, value, _ ->
            val fps = model.previewFpsOptions[value.toInt()]
            tvOutputLabel.text = "Preview: $fps FPS"
        }
        
        sliderOutput.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {}
            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                val fps = model.previewFpsOptions[slider.value.toInt()]
                model.setPreviewFps(fps)
                model.setOutputFps(fps)
                val mainActivity = activity as? com.dan.timelapse.MainActivity
                mainActivity?.let {
                    val input = model.framesInput.value
                    if (input is com.dan.timelapse.framesinput.ImagesFramesInput && it.isPlayingOriginalVideo) {
                        it.startPreviewLoop(Int.MAX_VALUE)
                    }
                }
            }
        })
        
        // Auto-save switch
        val switchAutoSave = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchAutoSave)
        switchAutoSave.isChecked = model.autoSaveAfterRender.value ?: false
        switchAutoSave.setOnCheckedChangeListener { _, isChecked ->
            model.setAutoSaveAfterRender(isChecked)
        }

        // Preserve audio switch
        val switchPreserveAudio = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchPreserveAudio)
        switchPreserveAudio.isChecked = model.preserveAudio.value ?: false
        switchPreserveAudio.setOnCheckedChangeListener { _, isChecked ->
            model.setPreserveAudio(isChecked)
        }

        bottomSheetDialog.show()
    }

    private fun showGenerateMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.menu_studio_generate, popup.menu)

        popup.menu.findItem(R.id.menu_auto_save)?.isChecked = model.autoSaveAfterRender.value == true

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_auto_save -> {
                    item.isChecked = !item.isChecked
                    model.setAutoSaveAfterRender(item.isChecked)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
