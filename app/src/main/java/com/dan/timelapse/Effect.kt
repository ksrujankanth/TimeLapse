package com.dan.timelapse

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
    ENDLESS_MEDIAN_STACK,
    DIFFERENCE,
    ENDLESS_DIFFERENCE,
    SUPER_RESOLUTION;

    val supportsEndless: Boolean
        get() = when(this) {
            AVERAGE, AVERAGE_WEIGHTED_FOR_LAST, AVERAGE_WEIGHTED_FOR_LIGHT, LIGHTEST_PIXELS, ADD, DARKEST_PIXELS, MOTION_EXTRACTION, MEDIAN_STACK, DIFFERENCE -> true
            else -> false
        }

    fun toEndless(): Effect {
        return when(this) {
            AVERAGE -> ENDLESS_AVERAGE
            AVERAGE_WEIGHTED_FOR_LAST -> ENDLESS_AVERAGE_WEIGHTED_FOR_LAST
            AVERAGE_WEIGHTED_FOR_LIGHT -> ENDLESS_AVERAGE_WEIGHTED_FOR_LIGHT
            LIGHTEST_PIXELS -> ENDLESS_LIGHTEST_PIXELS
            ADD -> ENDLESS_ADD
            DARKEST_PIXELS -> ENDLESS_DARKEST_PIXELS
            MOTION_EXTRACTION -> ENDLESS_MOTION_EXTRACTION
            MEDIAN_STACK -> ENDLESS_MEDIAN_STACK
            DIFFERENCE -> ENDLESS_DIFFERENCE
            SUPER_RESOLUTION -> SUPER_RESOLUTION
            else -> this
        }
    }

    /** Maps an ENDLESS_ variant back to the effect it decorates. */
    val baseEffect: Effect
        get() = when(this) {
            ENDLESS_AVERAGE -> AVERAGE
            ENDLESS_AVERAGE_WEIGHTED_FOR_LAST -> AVERAGE_WEIGHTED_FOR_LAST
            ENDLESS_AVERAGE_WEIGHTED_FOR_LIGHT -> AVERAGE_WEIGHTED_FOR_LIGHT
            ENDLESS_LIGHTEST_PIXELS -> LIGHTEST_PIXELS
            ENDLESS_ADD -> ADD
            ENDLESS_DARKEST_PIXELS -> DARKEST_PIXELS
            ENDLESS_MOTION_EXTRACTION -> MOTION_EXTRACTION
            ENDLESS_MEDIAN_STACK -> MEDIAN_STACK
            ENDLESS_DIFFERENCE -> DIFFERENCE
            else -> this
        }

    /**
     * True when the render wraps this effect in a sliding-window stack filter
     * that emits its first output only once the window is full (see the
     * consumer `when` in `VideoProcessor.startRendering`).
     *
     * The endless variants accumulate instead of buffering a window, so they
     * emit from the first input and are NOT included.
     */
    val usesStackWindow: Boolean
        get() = when (this) {
            AVERAGE,
            AVERAGE_WEIGHTED_FOR_LAST,
            AVERAGE_WEIGHTED_FOR_LIGHT,
            LIGHTEST_PIXELS,
            ADD,
            HDR,
            DARKEST_PIXELS,
            MOTION_EXTRACTION,
            MEDIAN_STACK -> true
            // TRANSITION is NOT windowed: TransitionFramesFilter emits `size`
            // interpolated frames for every input pair, from the second input on.
            else -> false
        }

    val friendlyName: String
        get() = when(this) {
            NONE -> "None"
            AVERAGE, ENDLESS_AVERAGE -> "Average"
            AVERAGE_WEIGHTED_FOR_LAST, ENDLESS_AVERAGE_WEIGHTED_FOR_LAST -> "Motion Blur"
            AVERAGE_WEIGHTED_FOR_LIGHT, ENDLESS_AVERAGE_WEIGHTED_FOR_LIGHT -> "Lighten Average"
            LIGHTEST_PIXELS, ENDLESS_LIGHTEST_PIXELS -> "Light Trails"
            ADD, ENDLESS_ADD -> "Sum"
            HDR -> "HDR"
            TRANSITION -> "Transition"
            DARKEST_PIXELS, ENDLESS_DARKEST_PIXELS -> "Darken"
            MOTION_EXTRACTION, ENDLESS_MOTION_EXTRACTION -> "Motion Extract"
            MEDIAN_STACK, ENDLESS_MEDIAN_STACK -> "Median"
            DIFFERENCE, ENDLESS_DIFFERENCE -> "Difference"
            SUPER_RESOLUTION -> "Super Resolution"
        }

    companion object {
        /**
         * Order of the effect picker. MUST stay in the same order as
         * `R.array.effect_names` / `R.array.effect_descriptions`; the spinner position is
         * the index into this list. Keeping it here removes the three hand-maintained
         * copies that used to live in StudioFragment and EffectSpinnerAdapter.
         */
        val UI_ORDER: List<Effect> = listOf(
            NONE,
            AVERAGE,
            AVERAGE_WEIGHTED_FOR_LAST,
            AVERAGE_WEIGHTED_FOR_LIGHT,
            LIGHTEST_PIXELS,
            ADD,
            HDR,
            TRANSITION,
            DARKEST_PIXELS,
            MOTION_EXTRACTION,
            MEDIAN_STACK,
            SUPER_RESOLUTION
        )
    }
}
