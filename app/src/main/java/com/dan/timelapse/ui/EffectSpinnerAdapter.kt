package com.dan.timelapse.ui

import android.content.Context
import android.graphics.drawable.AnimationDrawable
import android.os.AsyncTask
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import com.dan.timelapse.Effect
import com.dan.timelapse.R
import com.dan.timelapse.utils.FilterAnimationGenerator
import java.util.concurrent.ConcurrentHashMap

class EffectSpinnerAdapter(context: Context, private val effects: Array<String>) : ArrayAdapter<String>(context, 0, effects) {

    private val animationGenerator = FilterAnimationGenerator(context)
    private val animationCache = ConcurrentHashMap<Effect, AnimationDrawable>()

    init {
        // Pre-generate animations for all known effects in a background thread
        // so the first dropdown open isn't slow.
        Thread({
            try {
                val allEffects = listOf(
                    Effect.NONE,
                    Effect.AVERAGE,
                    Effect.AVERAGE_WEIGHTED_FOR_LAST,
                    Effect.AVERAGE_WEIGHTED_FOR_LIGHT,
                    Effect.LIGHTEST_PIXELS,
                    Effect.ADD,
                    Effect.HDR,
                    Effect.TRANSITION,
                    Effect.DARKEST_PIXELS,
                    Effect.MOTION_EXTRACTION,
                    Effect.MEDIAN_STACK,
                    Effect.SUPER_RESOLUTION
                )
                for (e in allEffects) {
                    if (!animationCache.containsKey(e)) {
                        animationCache[e] = animationGenerator.createAnimation(e)
                    }
                }
            } catch (t: Throwable) {
                // Fallback to on-demand if pre-gen fails
            }
        }, "EffectAnim-Pregen").start()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        return createView(position, convertView, parent)
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
        return createView(position, convertView, parent)
    }

    private fun createView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.list_item_effect, parent, false)
        val ivEffectAnimation = view.findViewById<ImageView>(R.id.ivEffectAnimation)
        val tvEffectName = view.findViewById<TextView>(R.id.tvEffectName)

        val effectName = effects[position]
        tvEffectName.text = effectName
        val effect = mapEffectNameToEnum(effectName)

        // 1. Fetch the cached base animation template from the generator
        val baseAnimation = animationCache.getOrPut(effect) { animationGenerator.createAnimation(effect) }

        // 2. SURGICAL FIX: Create a lightweight clone of the AnimationDrawable.
        // This grants a unique callback to this specific ImageView while sharing the heavy Bitmap memory.
        val animation = AnimationDrawable().apply {
            isOneShot = baseAnimation.isOneShot
            for (i in 0 until baseAnimation.numberOfFrames) {
                val originalFrame = baseAnimation.getFrame(i)

                // Extract a fresh drawable wrapper that points to the same underlying Bitmap state
                val clonedFrame = originalFrame.constantState?.newDrawable() ?: originalFrame
                addFrame(clonedFrame, baseAnimation.getDuration(i))
            }
        }

        // 3. Halt any zombie animations on recycled views before attaching the new one
        (ivEffectAnimation.drawable as? AnimationDrawable)?.stop()

        ivEffectAnimation.setImageDrawable(animation)

        // 4. Safely start the animation on the UI thread
        ivEffectAnimation.post {
            if (animation.isRunning) animation.stop()
            animation.selectDrawable(0)
            animation.start()
        }

        return view
    }

    private fun mapEffectNameToEnum(effectName: String): Effect {
        return when (effectName) {
            "None" -> Effect.NONE
            "Average" -> Effect.AVERAGE
            "Motion Blur" -> Effect.AVERAGE_WEIGHTED_FOR_LAST
            "Lighten Average" -> Effect.AVERAGE_WEIGHTED_FOR_LIGHT
            "Light Trails" -> Effect.LIGHTEST_PIXELS
            "Sum (Long Exposure)" -> Effect.ADD
            "HDR" -> Effect.HDR
            "Transition" -> Effect.TRANSITION
            "Darken (Noise Clean)" -> Effect.DARKEST_PIXELS
            "Motion Extract" -> Effect.MOTION_EXTRACTION
            "Median (Cleanest)" -> Effect.MEDIAN_STACK
            "Super Resolution" -> Effect.SUPER_RESOLUTION
            else -> Effect.NONE
        }
    }
}
