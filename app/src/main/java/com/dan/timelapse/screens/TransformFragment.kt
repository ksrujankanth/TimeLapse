package com.dan.timelapse.screens

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.dan.timelapse.MainActivity
import com.dan.timelapse.MainViewModel
import com.dan.timelapse.R
import com.dan.timelapse.data.CropRatio
import com.dan.timelapse.databinding.TrayTransformBinding

class TransformFragment : Fragment() {
    private val model: MainViewModel by activityViewModels()
    private var _binding: TrayTransformBinding? = null
    private val binding get() = _binding!!

    // Landscape aspect ratios
    private val landscapeRatios = listOf(
        CropRatio.SOURCE,
        CropRatio.RATIO_16_9,
        CropRatio.RATIO_4_3,
        CropRatio.RATIO_1_1,
        CropRatio.RATIO_9_16,
        CropRatio.RATIO_4_5
    )

    // Portrait aspect ratios (swapped for 90/270 rotation)
    private val portraitRatios = listOf(
        CropRatio.SOURCE,
        CropRatio.RATIO_9_16,
        CropRatio.RATIO_4_5,
        CropRatio.RATIO_1_1,
        CropRatio.RATIO_4_3,
        CropRatio.RATIO_16_9
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = TrayTransformBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupSplitButtons()
        observeState()
    }

    override fun onPause() {
        super.onPause()
        model.pausePlayback()
    }

    override fun onResume() {
        super.onResume()
        
        view?.post {
            val activity = requireActivity() as? MainActivity
            
            // ONLY apply the saved math if we are looking at the Original Video
            if (activity?.isPlayingOriginalVideo == true) {
                val savedRotation = model.rotation.value ?: 0
                val savedCropRatio = model.cropRatio.value
                
                activity.applyVideoRotation(savedRotation)
                
                val ratioFloat = savedCropRatio?.let { getRatioFloat(it) }
                activity.applyVideoAspectRatio(ratioFloat)
            }
        }
    }

    private fun getRatioFloat(ratio: CropRatio): Float? {
        return when (ratio) {
            CropRatio.RATIO_1_1 -> 1f / 1f
            CropRatio.RATIO_4_3 -> 4f / 3f
            CropRatio.RATIO_3_4 -> 3f / 4f
            CropRatio.RATIO_16_9 -> 16f / 9f
            CropRatio.RATIO_9_16 -> 9f / 16f
            CropRatio.RATIO_4_5 -> 4f / 5f
            CropRatio.RATIO_5_4 -> 5f / 4f
            CropRatio.RATIO_21_9 -> 21f / 9f
            CropRatio.RATIO_9_21 -> 9f / 21f
            CropRatio.SOURCE -> null
        }
    }

    private fun getCurrentRatios(): List<CropRatio> {
        val outW = model.outputWidth
        val outH = model.outputHeight

        // Fallback if dimensions aren't loaded yet
        if (outW == 0 || outH == 0) return landscapeRatios

        // Check the actual oriented physical dimensions
        val isPortrait = outH > outW
        
        // Return the correct list based on the CURRENT orientation
        // We no longer aggressively filter out ratios, so the user always has a predictable list.
        return if (isPortrait) portraitRatios else landscapeRatios
    }

    private fun setupSplitButtons() {
        android.util.Log.d("TimeLapse", "TransformFragment setupSplitButtons called")
        
        // Rotate Button - cycles 0 -> 90 -> 180 -> 270 -> 0
        binding.btnRotate.setOnClickListener {
            android.util.Log.d("TimeLapse", "Rotate button ONCLICK triggered")
            model.rotate()
        }


        // Ratio Button - dynamically finds the next valid aspect ratio
        binding.btnRatio.setOnClickListener {
            val ratios = getCurrentRatios()
            val currentRatio = model.cropRatio.value ?: CropRatio.SOURCE
            android.util.Log.d("TimeLapse", "Ratio button clicked - currentRatio=$currentRatio, ratios=$ratios")
            
            // Find where we are in the current valid list
            var currentIndex = ratios.indexOf(currentRatio)
            if (currentIndex == -1) currentIndex = 0
            
            // Cycle to the next valid ratio
            val nextIndex = (currentIndex + 1) % ratios.size
            val selectedRatio = ratios[nextIndex]
            
            android.util.Log.d("TimeLapse", "Setting crop ratio to: $selectedRatio")
            model.setCropRatio(selectedRatio)
            
            // Get the ratio float for preview update
            val ratioFloat = if (selectedRatio == CropRatio.SOURCE) null else getRatioFloat(selectedRatio)
            
            // Directly update preview to ensure it works
            (activity as? MainActivity)?.applyVideoAspectRatio(ratioFloat)
            

        }


        // Stabilize Toggle
        binding.btnStabilize.setOnClickListener {
            val currentState = model.isStabilized.value ?: false
            val newState = !currentState
            model.setStabilization(newState)
            
            // Explicit UI updates
            binding.btnStabilize.text = if (newState) "Stabilize: ON" else "Stabilize: OFF"
            binding.btnStabilize.alpha = if (newState) 1.0f else 0.5f
            binding.btnStabilize.setIconResource(
                if (newState) R.drawable.ic_stabilize else R.drawable.ic_stabilize_off
            )
        }


        // Edit Mask Button
        binding.btnStabilizeMenu.setOnClickListener {
            openMaskEditor()
        }
    }

    private fun openMaskEditor() {
        (activity as? MainActivity)?.let { mainActivity ->
            mainActivity.openMaskEditor()
        }
    }

    private fun observeState() {
        // Observe rotation to update button text 
        model.rotation.observe(viewLifecycleOwner) { rotation ->
            binding.btnRotate.text = "Rotate ${rotation}°"
        }


        // Observe ratio to update button text
        model.cropRatio.observe(viewLifecycleOwner) { ratio ->
            val displayText = ratio?.displayName ?: "Crop (original)"
            binding.btnRatio.text = displayText
        }


        // Observe stabilization state
        model.isStabilized.observe(viewLifecycleOwner) { isEnabled ->
            val btn = binding.btnStabilize
            btn.text = if (isEnabled) "Stabilize: ON" else "Stabilize: OFF"
            btn.alpha = if (isEnabled) 1.0f else 0.5f
            btn.setIconResource(
                if (isEnabled) R.drawable.ic_stabilize else R.drawable.ic_stabilize_off
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
