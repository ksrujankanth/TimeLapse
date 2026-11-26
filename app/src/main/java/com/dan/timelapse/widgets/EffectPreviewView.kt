package com.dan.timelapse.widgets

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import com.dan.timelapse.Effect
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Animated "what does this filter actually do" tile shown next to every effect name.
 *
 * The old implementation pre-rendered 30 bitmaps per effect and played them as an
 * [android.graphics.drawable.AnimationDrawable] at 160 ms/frame (6.25 fps), so every row
 * advanced in visible 20 px jumps and every row moved in lockstep. It also drew a
 * fictional three-colour "triple pipe" cursor, so most rows were indistinguishable, and
 * several rows contradicted the filter they were illustrating (HDR showed stripes,
 * Motion Extract showed outlines on black, Transition showed a wipe, Median showed a
 * translucent bar). This view draws live at display refresh rate and renders the real
 * signature of each filter.
 *
 * Invariants when editing the drawings - each must match the FramesFilter it represents:
 *  - AVERAGE               sum of N frames / N     -> uniform, diluted smear
 *  - AVERAGE_WEIGHTED_LAST last frame gets +size/2 -> same smear, head-heavy
 *  - AVERAGE_WEIGHTED_LIGHT p-norm 6 (~max), windowed -> saturated constant streak
 *  - LIGHTEST_PIXELS       max from frame 0        -> the same, but permanent
 *  - ADD                   8U saturating add       -> white streak + washed-out sky
 *  - HDR                   MergeMertens on a bracket -> 3 exposures + merge wipe
 *  - TRANSITION            addWeighted(prev, w, next, 1-w) -> cross-dissolve
 *  - DARKEST_PIXELS        min filter              -> subject erased, frame darker
 *  - MOTION_EXTRACTION     0.5*(cur-prev)+127.5    -> mid-grey field + signed edges
 *  - MEDIAN_STACK          median                  -> subject erased, brightness kept
 *  - SUPER_RESOLUTION      sub-pixel stack         -> ghosts converge, grid resolves
 *
 * The moving subject is a bright object crossing a dark landscape on purpose: a
 * bright-on-dark object is the only thing whose average / max / min / sum differ enough
 * to be told apart at 120 x 50 dp.
 */
class EffectPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private companion object {
        // Logical drawing space. 240 x 100 = exactly the 2.4:1 aspect of a tile, so
        // nothing is letterboxed away.
        const val LW = 240f
        const val LH = 112f

        /**
         * Loop length. Deliberately NOT driven by a ValueAnimator: that clock is scaled by
         * the "Animator duration scale" developer option, so on a device set to 0.5x the
         * previews ran twice as fast as designed. See [frameCallback].
         */
        const val LOOP_MS = 5200L

        const val GROUND_Y = 83f
        const val SUN_R = 6.5f

        /** Fraction of the loop a 12-frame stack covers, i.e. how long the trail is. */
        const val STACK_SPAN = 0.30f
        const val STACK_FRAMES = 12
        const val TRAIL_SAMPLES = 48
    }

    private val cSkyTop = 0xFF0C1622.toInt()
    private val cSkyMid = 0xFF17272E.toInt()
    private val cSkyBottom = 0xFF2B3F3C.toInt()
    private val cGround = 0xFF1A2A25.toInt()
    private val cHorizon = 0xFF567061.toInt()
    private val cMountain = 0xFF566D61.toInt()
    private val cRidge = 0xFF93AC9C.toInt()
    private val cMountainFar = 0xFF46595A.toInt()
    private val cSun = 0xFFFFE8B8.toInt()

    /** Warmer than the sun itself: a cream streak over a teal sky turns khaki. */
    private val cTrail = 0xFFFFD4A1.toInt()
    private val cMidGrey = 0xFF7E858A.toInt()

    /** Pastel sparkle tints, cycled so the star field is not monochrome. */
    private val sparkleTints = intArrayOf(
        0xFFFFFFFF.toInt(),
        0xFFCDE9FF.toInt(),
        0xFFFFE3C4.toInt()
    )

    private val twoPi = (2.0 * PI).toFloat()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val shapeA = Path()
    private val shapeB = Path()
    private val clipPath = Path()
    private val density = resources.displayMetrics.density
    private val tileBounds = RectF()

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF3F7F4.toInt()
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val skyGradient: LinearGradient by lazy {
        LinearGradient(
            0f, 0f, 0f, GROUND_Y,
            intArrayOf(cSkyTop, cSkyMid, cSkyBottom),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    /**
     * Shaders are expensive native objects and every tile redraws at 60 Hz, so the
     * gradients are cached by rounded geometry instead of being rebuilt in onDraw.
     * (Twelve tiles used to allocate ~750-900 LinearGradients per second.)
     */
    private val gradientCache = HashMap<Long, LinearGradient>()

    /** Scratch path for the Difference tile (the shared [path] is used by it too). */
    private val auxPath = Path()

    private inline fun cachedGradient(key: Long, build: () -> LinearGradient): LinearGradient =
        gradientCache.getOrPut(key) { build() }

    private fun verticalKey(tag: Int, y0: Float, y1: Float): Long =
        (tag.toLong() shl 48) or (y0.toLong() shl 24) or y1.toLong()

    private fun horizontalKey(tag: Int, x0: Float, x1: Float): Long =
        (tag.toLong() shl 48) or (x0.toLong() shl 24) or x1.toLong()

    private var effect: Effect = Effect.AVERAGE
    private var phaseOffset = 0f
    private var phase = 0.62f                // a developed frame, shown until the first tick
    private var title: String = ""
    private var titleSize = 18f
    private var selected = false
    private var accentColor = 0xFF81D4FA.toInt()

    /** Radius of the tile's own clip, kept in sync with the card that hosts it. */
    var cornerRadius: Float = 7f * density
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private var framePosted = false
    private var originNanos = 0L

    /**
     * Frame clock. `System.nanoTime` through Choreographer, not a ValueAnimator, because
     * ValueAnimator's timeline is multiplied by the "Animator duration scale" developer
     * setting: at 0.5x these previews ran twice as fast and at 2x they crawled. This is
     * wall-clock, so the speed is the same on every device.
     */
    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        framePosted = false
        // isShown is false while the window is not visible (app backgrounded with the
        // sheet open, or the tile is hidden). Without this the loop kept re-posting a
        // vsync callback and invalidating an invisible surface for as long as the
        // process lived.
        if (!isAttachedToWindow || !isShown) return@FrameCallback
        if (originNanos == 0L) originNanos = frameTimeNanos
        val elapsedMs = (frameTimeNanos - originNanos) / 1_000_000.0
        phase = ((elapsedMs / LOOP_MS + phaseOffset) % 1.0).toFloat()
        invalidate()
        scheduleFrame()
    }

    /** x, y and animation phase of each sparkle in the Super Resolution row. */
    private val sparkles = arrayOf(
        floatArrayOf(28f, 20f, 0.00f),
        floatArrayOf(62f, 34f, 0.37f),
        floatArrayOf(98f, 13f, 0.72f),
        floatArrayOf(130f, 29f, 0.15f),
        floatArrayOf(162f, 19f, 0.55f),
        floatArrayOf(198f, 36f, 0.83f),
        floatArrayOf(226f, 15f, 0.28f),
        floatArrayOf(44f, 52f, 0.62f),
        floatArrayOf(84f, 62f, 0.91f),
        floatArrayOf(120f, 49f, 0.44f),
        floatArrayOf(152f, 63f, 0.08f),
        floatArrayOf(190f, 57f, 0.70f),
        floatArrayOf(214f, 68f, 0.35f),
        floatArrayOf(70f, 78f, 0.50f),
        floatArrayOf(172f, 80f, 0.77f)
    )

    /**
     * @param newEffect effect to illustrate. ENDLESS_ variants are mapped back to the
     *   effect they decorate, so callers never have to normalise.
     * @param offset starting phase in [0,1). Give each row a different offset, otherwise
     *   every row animates in lockstep and the dropdown looks like twelve loading bars.
     */
    fun setEffect(newEffect: Effect, offset: Float = 0f) {
        val normalised = newEffect.baseEffect
        if (normalised == effect && offset == phaseOffset) return
        effect = normalised
        phaseOffset = offset
        restart()
        invalidate()
    }

    /** Effect name drawn inside the tile, over the foreground band. */
    fun setTitle(text: String) {
        if (text == title) return
        title = text
        // Fit once here instead of measuring every frame. The tick's width is reserved
        // either way, so switching selection never nudges the text sideways.
        var size = 18f
        titlePaint.textSize = size
        val reserved = size * 0.96f
        val maxWidth = LW - 20f - reserved
        while (size > 10f && titlePaint.measureText(text) > maxWidth) {
            size -= 0.5f
            titlePaint.textSize = size
        }
        titleSize = size
        invalidate()
    }

    /**
     * Marks this tile as the chosen one. The tick is drawn inline with the name inside the
     * picture: a badge floating in the corner reads as unrelated chrome on top of the art.
     */
    fun setSelected(value: Boolean, accent: Int) {
        if (selected == value && accentColor == accent) return
        selected = value
        accentColor = accent
        invalidate()
    }

    /**
     * Starts the loop. Deliberately NOT gated on [ValueAnimator.areAnimatorsEnabled].
     *
     * The system "remove animations" developer setting (and the accessibility preference that
     * drives it) exists to suppress *transition* motion. These tiles are the content: a frozen
     * still is indistinguishable from a broken picker — on the Moto X Play, whose three
     * animation scales are 0, the whole effect sheet read as "just a picture gallery".
     * The loop clock is wall-clock ([frameCallback]), so it is already independent of the
     * animator scale for *speed*; this removes the last dependency on it.
     */
    private fun restart() {
        originNanos = 0L
        if (!isAttachedToWindow) return
        scheduleFrame()
    }

    private fun scheduleFrame() {
        if (framePosted || !isAttachedToWindow) return
        framePosted = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        restart()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // When the height is not fixed we are the thumbnail: keep the exact 2.4:1 of the
        // drawing space so nothing is letterboxed away.
        val wMode = MeasureSpec.getMode(widthMeasureSpec)
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        if (wMode == MeasureSpec.EXACTLY && hMode != MeasureSpec.EXACTLY) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = (w * LH / LW).toInt()
            setMeasuredDimension(
                w,
                if (hMode == MeasureSpec.AT_MOST) min(h, MeasureSpec.getSize(heightMeasureSpec)) else h
            )
            return
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        framePosted = false
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE && isAttachedToWindow) {
            // Restart the clock so a long background pause does not jump the phase.
            originNanos = 0L
            scheduleFrame()
        } else {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            framePosted = false
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // isShown is also false when a PARENT is GONE/INVISIBLE, and that path delivers
        // onVisibilityChanged, not onWindowVisibilityChanged. The frame callback bails
        // without rescheduling in that state, so without this restart the tile stopped
        // animating permanently after an ancestor hid and re-showed it.
        if (visibility == VISIBLE && isAttachedToWindow && isShown) {
            originNanos = 0L
            scheduleFrame()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return

        // Uniform scale only: a stretched tile would turn every circle into an ellipse.
        val scale = min(vw / LW, vh / LH)
        val ox = (vw - LW * scale) * 0.5f
        val oy = (vh - LH * scale) * 0.5f

        tileBounds.set(ox, oy, ox + LW * scale, oy + LH * scale)
        clipPath.rewind()
        clipPath.addRoundRect(tileBounds, cornerRadius, cornerRadius, Path.Direction.CW)

        canvas.save()
        canvas.clipPath(clipPath)
        canvas.translate(ox, oy)
        canvas.scale(scale, scale)
        drawEffect(canvas, phase)
        drawTitle(canvas)
        canvas.restore()
    }

    /**
     * The name lives inside the picture, on the foreground band. Outside the tile it was
     * ambiguous whether a caption belonged to the image above or below it, and it forced
     * the tile to be smaller than the space it was given.
     */
    private fun drawTitle(c: Canvas) {
        if (title.isEmpty()) return

        // Scrim, so white text stays legible over the sunset rows as well.
        paint.style = Paint.Style.FILL
        paint.shader = cachedGradient(verticalKey(1, GROUND_Y - 10f, LH)) {
            LinearGradient(
                0f, GROUND_Y - 10f, 0f, LH,
                intArrayOf(0x00000000, 0x9E0A1216.toInt()),
                null,
                Shader.TileMode.CLAMP
            )
        }
        paint.alpha = 255
        c.drawRect(0f, GROUND_Y - 10f, LW, LH, paint)
        paint.shader = null

        titlePaint.textSize = titleSize
        val textWidth = titlePaint.measureText(title)
        val checkWidth = titleSize * 0.64f
        val gap = titleSize * 0.32f
        val reserved = checkWidth + gap
        val left = LW * 0.5f - (reserved + textWidth) * 0.5f
        val baseline = LH - 7.5f

        if (selected) drawCheck(c, left, baseline, checkWidth)

        titlePaint.textAlign = Paint.Align.LEFT
        c.drawText(title, left + reserved, baseline, titlePaint)
    }

    private fun drawCheck(c: Canvas, left: Float, baseline: Float, size: Float) {
        val top = baseline - titleSize * 0.78f
        paint.style = Paint.Style.STROKE
        paint.shader = null
        paint.strokeWidth = 2.1f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = accentColor
        paint.alpha = 255
        path.rewind()
        path.moveTo(left, top + size * 0.52f)
        path.lineTo(left + size * 0.36f, top + size * 0.86f)
        path.lineTo(left + size, top + size * 0.06f)
        c.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        paint.strokeCap = Paint.Cap.BUTT
        paint.strokeJoin = Paint.Join.MITER
    }

    // ---------------------------------------------------------------- dispatch

    private fun drawEffect(c: Canvas, t: Float) {
        when (effect) {
            Effect.NONE -> {
                drawBackdrop(c)
                drawSun(c, t, 1f)
                drawForeground(c)
            }

            Effect.AVERAGE -> {
                drawBackdrop(c)
                val from = (t - STACK_SPAN).coerceAtLeast(0f)
                paint.style = Paint.Style.FILL
                paint.shader = null
                paint.color = cTrail
                // 1/N of the peak: that dilution is what separates Average from the
                // max-based streaks. Boosted slightly so it survives a small tile.
                paint.alpha = 135
                c.drawPath(trailPath(from, t, TRAIL_SAMPLES), paint)
                paint.alpha = 255
                drawForeground(c)
            }

            Effect.AVERAGE_WEIGHTED_FOR_LAST -> {
                drawBackdrop(c)
                // A longer tail than Average on purpose: the extra weight on the newest
                // frame is what the user is meant to see, and a short stub hid it.
                val from = (t - STACK_SPAN * 1.55f).coerceAtLeast(0f)
                val x0 = headX(from)
                val x1 = headX(t).coerceAtLeast(x0 + 1f)
                paint.style = Paint.Style.FILL
                paint.color = Color.WHITE
                paint.alpha = 255
                paint.shader = cachedGradient(horizontalKey(3, x0, x1)) {
                    LinearGradient(
                        x0, 0f, x1, 0f,
                        intArrayOf(0x00FFD4A1, 0x5CFFD4A1, 0xF5FFD4A1.toInt()),
                        floatArrayOf(0f, 0.62f, 1f),
                        Shader.TileMode.CLAMP
                    )
                }
                c.drawPath(trailPath(from, t, TRAIL_SAMPLES), paint)
                paint.shader = null
                drawForeground(c)
            }

            Effect.AVERAGE_WEIGHTED_FOR_LIGHT -> {
                drawBackdrop(c)
                val from = (t - STACK_SPAN).coerceAtLeast(0f)
                paint.style = Paint.Style.FILL
                paint.shader = null
                paint.color = cTrail
                // A p-norm of 6 still averages, so the streak is warm and slightly
                // translucent instead of the hard white a true max produces.
                paint.alpha = 215
                c.drawPath(trailPath(from, t, TRAIL_SAMPLES), paint)
                paint.alpha = 255
                drawForeground(c)
            }

            Effect.LIGHTEST_PIXELS -> {
                drawBackdrop(c)
                // Endless max: every frame from the start of the clip stays on screen, and
                // no frame is diluted, so the streak is white with a bloom around it.
                // trailPath() returns a SHARED Path and rewinds it, so the glow and the
                // core must each be drawn immediately: keeping a reference to the first
                // call and drawing it after the second made both passes use the fat
                // geometry (the thin white core was never drawn).
                paint.style = Paint.Style.FILL
                paint.shader = null
                paint.color = Color.WHITE
                paint.alpha = 45
                c.drawPath(trailPath(0f, t, 96, SUN_R + 1.8f), paint)
                paint.alpha = 255
                c.drawPath(trailPath(0f, t, 96, SUN_R), paint)
                drawForeground(c)
            }

            Effect.ADD -> drawAdd(c, t)

            Effect.HDR -> drawHdr(c)

            Effect.TRANSITION -> {
                drawBackdrop(c)
                // Close together on purpose: two overlapping exposures dissolving into each
                // other read as a morph; two separated dots just read as two dots.
                val w = 0.5f - 0.5f * cos(twoPi * t)
                drawSun(c, 0.49f, 1f - w)
                drawSun(c, 0.535f, w)
                drawForeground(c)
            }

            Effect.DARKEST_PIXELS -> {
                // min() of a stack is darker than the mean, and any bright transient
                // (hot pixel, headlight, meteor) is gone once the window is full.
                drawBackdrop(c)
                // Two sweeps per loop. The fade ends before the position resets, so the
                // jump back to the left edge is never visible.
                val local = (t * 2f) % 1f
                val keep = 1f - (local / 0.45f).coerceIn(0f, 1f)
                if (keep > 0.01f) {
                    paint.style = Paint.Style.FILL
                    paint.shader = null
                    paint.color = cTrail
                    paint.alpha = (keep * 125f).toInt()
                    c.drawPath(trailPath(0f, local, 96), paint)
                    paint.alpha = 255
                    drawSun(c, local, keep * 0.9f)
                }
                drawForeground(c)
                // The frame itself is darker than the mean of the stack.
                paint.color = Color.BLACK
                paint.alpha = 76
                c.drawRect(0f, 0f, LW, LH, paint)
                paint.alpha = 255
            }

            Effect.MOTION_EXTRACTION -> drawMotionExtract(c, t)

            Effect.MEDIAN_STACK -> {
                drawBackdrop(c)
                // The subject only ever occupies a pixel for 1 of N frames, so the median
                // rejects it while keeping every static pixel at full brightness.
                val local = (t * 2f) % 1f
                val keep = 1f - (local / 0.45f).coerceIn(0f, 1f)
                if (keep > 0.01f) drawSun(c, local, keep)
                drawForeground(c)
            }

            Effect.SUPER_RESOLUTION -> drawSuperResolution(c, t)

            Effect.DIFFERENCE -> drawDifference(c, t)

            else -> {
                drawBackdrop(c)
                drawSun(c, t, 1f)
                drawForeground(c)
            }
        }
    }

    // ---------------------------------------------------------------- scene

    /**
     * Sky only. The subject is drawn next, then [drawForeground] puts the hills on top, so
     * the sun sets *behind* the ridge instead of floating in front of it.
     */
    private fun drawDifference(c: Canvas, t: Float) {
        drawBackdrop(c)
        // What the effect does: subtract one exposure from another. Show exactly that —
        // two overlapping discs, only the crescent where they disagree is kept.
        val radius = 34f
        val cx = LW * 0.40f + 6f * sin(t * 2.0 * PI).toFloat()
        val cy = GROUND_Y * 0.48f
        path.reset()
        path.addCircle(cx, cy, radius, Path.Direction.CW)
        auxPath.reset()
        auxPath.addCircle(cx + 20f, cy - 5f, radius, Path.Direction.CW)
        paint.shader = null
        paint.color = Color.WHITE
        // Faint outline of the exposure being subtracted, so the subtraction is legible.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        paint.alpha = 70
        c.drawPath(auxPath, paint)
        // The result: the crescent where the two exposures disagree.
        paint.style = Paint.Style.FILL
        paint.alpha = 215
        if (path.op(auxPath, Path.Op.DIFFERENCE)) c.drawPath(path, paint)
        paint.strokeWidth = 1f
        paint.alpha = 255
        drawForeground(c)
    }

    private fun drawBackdrop(c: Canvas) {
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        paint.shader = skyGradient
        c.drawRect(0f, 0f, LW, LH, paint)
        paint.shader = null

        // Warm haze on the horizon. A pastel wash keeps eleven dark tiles from reading as
        // the same grey-blue picture with different white marks on it.
        paint.shader = cachedGradient(verticalKey(2, 46f, GROUND_Y)) {
            LinearGradient(
                0f, 46f, 0f, GROUND_Y,
                intArrayOf(0x00FFB067, 0x2EFFB067),
                null,
                Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 46f, LW, GROUND_Y, paint)
        paint.shader = null
    }

    private fun drawForeground(c: Canvas) {
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        paint.shader = null

        paint.color = cGround
        c.drawRect(0f, GROUND_Y, LW, LH, paint)
        paint.color = cHorizon
        c.drawRect(0f, GROUND_Y - 0.9f, LW, GROUND_Y + 0.9f, paint)

        // Static content is the control: it must survive every stack untouched, so the
        // viewer can see that a trailing filter is not just smearing the whole frame.
        path.rewind()
        path.moveTo(18f, GROUND_Y)
        path.lineTo(54f, 38f)
        path.lineTo(90f, GROUND_Y)
        path.close()
        paint.color = cMountain
        c.drawPath(path, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.6f
        paint.color = cRidge
        path.rewind()
        path.moveTo(54f, 38f)
        path.lineTo(90f, GROUND_Y)
        c.drawPath(path, paint)
        paint.style = Paint.Style.FILL

        path.rewind()
        path.moveTo(148f, GROUND_Y)
        path.lineTo(176f, 58f)
        path.lineTo(204f, GROUND_Y)
        path.close()
        paint.color = cMountainFar
        c.drawPath(path, paint)
    }

    private fun headX(t: Float): Float = 16f + 208f * t

    private fun headY(t: Float): Float = 58f - 10f * sin(PI * t).toFloat()

    private fun drawSun(c: Canvas, t: Float, alpha: Float) {
        if (alpha <= 0.004f) return
        // Flat disk, no specular core: a bright core makes a fading copy collapse into a
        // single grey dot instead of reading as a half-faded exposure.
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = cSun
        paint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
        c.drawCircle(headX(t), headY(t), SUN_R, paint)
        paint.alpha = 255
    }

    /**
     * Union of the subject's footprint over [fromT]..[toT]. A single filled path gives a
     * clean band with round ends and, unlike stacking translucent circles, no overdraw
     * brightening where the samples overlap.
     */
    private fun trailPath(fromT: Float, toT: Float, samples: Int, radius: Float = SUN_R): Path {
        path.rewind()
        val span = (toT - fromT).coerceAtLeast(0f)
        if (span < 0.0005f) {
            path.addCircle(headX(toT), headY(toT), radius, Path.Direction.CW)
            return path
        }
        for (k in 0 until samples) {
            val t = fromT + span * k / (samples - 1f)
            if (t < 0f) continue
            path.addCircle(headX(t), headY(t), radius, Path.Direction.CW)
        }
        return path
    }

    // ---------------------------------------------------------------- per effect

    /** 8U saturating add: the streak clips to white and the sky washes out with it. */
    private fun drawAdd(c: Canvas, t: Float) {
        drawBackdrop(c)

        val from = (t - STACK_SPAN).coerceAtLeast(0f)
        val trail = trailPath(from, t, TRAIL_SAMPLES)
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = cTrail
        paint.blendMode = BlendMode.PLUS
        paint.alpha = 255
        c.drawPath(trail, paint)
        paint.blendMode = BlendMode.SRC_OVER
        paint.alpha = 255

        drawForeground(c)

        // Every frame is added, so even pixels that never saw the subject climb toward
        // white: that wash is what makes this look like a long exposure rather than a trail.
        paint.color = Color.WHITE
        paint.alpha = 40
        c.drawRect(0f, 0f, LW, LH, paint)
        paint.alpha = 255
    }

    /**
     * MergeMertens burns a bracketed set into one frame: an under-exposed frame holds the
     * highlights, an over-exposed frame holds the shadows, and the merge keeps both.
     *
     * This row deliberately uses its own sunset scene instead of the shared landscape: a
     * bracket is only legible when the scene has a bright sky. On the dark landscape the
     * -2 EV and 0 EV panels came out identical.
     */
    private fun drawHdr(c: Canvas) {
        c.drawColor(Color.BLACK)

        val panelW = 64f
        val gap = 16f
        val total = panelW * 3 + gap * 2
        var x = (LW - total) * 0.5f
        for (i in 0..2) {
            c.save()
            // Full-bleed height: with the old 12 dp letterbox a partially faded merge showed
            // a black frame around the panels and looked like a rendering glitch.
            c.clipRect(x, 0f, x + panelW, LH)
            drawSunset(c, x, panelW, openShadows = false)
            paint.shader = null
            paint.style = Paint.Style.FILL
            when (i) {
                0 -> { paint.color = Color.BLACK; paint.alpha = 120 } // -2 EV
                1 -> { paint.color = Color.BLACK; paint.alpha = 0 }   //  0 EV
                else -> { paint.color = Color.WHITE; paint.alpha = 150 } // +2 EV
            }
            c.drawRect(x, 0f, x + panelW, LH, paint)
            paint.alpha = 255
            c.restore()
            x += panelW + gap
        }

        // Hold the bracket for the whole loop. The three exposures ARE the HDR icon; a
        // half-faded merge on top of narrow panels only produced a muddy frame.
    }

    /**
     * @param openShadows the merged exposure: the foreground terrain is lifted out of
     *   black while the sunset keeps its gradient, i.e. both ends of the range survive.
     * @param withSun draw the low sun. The SR row reuses this backdrop without it.
     */
    private fun drawSunset(c: Canvas, x0: Float, w: Float, openShadows: Boolean, withSun: Boolean = true) {
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.alpha = 255

        paint.shader = cachedGradient(horizontalKey(4, x0, x0 + w)) {
            LinearGradient(
                x0, 0f, x0, GROUND_Y,
                intArrayOf(0xFF241B33.toInt(), 0xFFA8482C.toInt(), 0xFFFFC078.toInt()),
                floatArrayOf(0f, 0.6f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        c.drawRect(x0, 0f, x0 + w, GROUND_Y, paint)
        paint.shader = null

        if (withSun) {
            // Low sun, so the brightest patch sits right at the horizon where a bracket
            // actually has something to protect.
            paint.color = 0xFFFFF0C0.toInt()
            c.drawCircle(x0 + w * 0.62f, 72f, 8f, paint)
        }

        paint.color = if (openShadows) 0xFF3A2A36.toInt() else 0xFF140F1A.toInt()
        c.drawRect(x0, GROUND_Y, x0 + w, LH, paint)

        path.rewind()
        path.moveTo(x0 + 2f, GROUND_Y)
        path.lineTo(x0 + w * 0.34f, 49f)
        path.lineTo(x0 + w * 0.66f, GROUND_Y)
        path.close()
        paint.color = if (openShadows) 0xFF4B3542.toInt() else 0xFF1E1622.toInt()
        c.drawPath(path, paint)

        if (openShadows) {
            // Recovered shadow detail, only visible in the merged frame.
            paint.color = 0xFF5B4350.toInt()
            c.drawCircle(x0 + w * 0.82f, 99f, 6f, paint)
            c.drawCircle(x0 + w * 0.90f, 105f, 4f, paint)
        }
    }

    /**
     * The real output of `0.5 * (current - previous) + 127.5`: a flat mid-grey field where
     * nothing moves, a bright sliver on the leading edge and a dark one behind.
     *
     * The subject is the same sun as every other row, but nothing here is a full-height
     * bar: a bar reads as "scanning", not as "the edges of the thing that moved".
     */
    private fun drawMotionExtract(c: Canvas, t: Float) {
        c.drawColor(cMidGrey)

        // Static content differs by zero, so it is *exactly* mid grey. Only a few levels
        // of sensor noise survive, which is what the user actually sees on a still scene.
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = 0xFF898989.toInt()
        path.rewind()
        path.moveTo(148f, GROUND_Y)
        path.lineTo(176f, 58f)
        path.lineTo(204f, GROUND_Y)
        path.close()
        c.drawPath(path, paint)

        val nowX = headX(t)
        val nowY = headY(t)
        val prevX = headX(t - 0.02f)
        val prevY = headY(t - 0.02f)

        // A faint ring marks the subject itself: in a difference image the interior is
        // flat grey, so without it the row just looks like a floating sliver.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f
        paint.color = 0xFFA6A6A6.toInt()
        c.drawCircle(nowX, nowY, SUN_R, paint)
        paint.style = Paint.Style.FILL

        shapeA.rewind()
        shapeB.rewind()
        shapeA.addCircle(nowX, nowY, SUN_R, Path.Direction.CW)
        shapeB.addCircle(prevX, prevY, SUN_R, Path.Direction.CW)

        paint.color = 0xFFF4F4F4.toInt()
        path.rewind()
        if (path.op(shapeA, shapeB, Path.Op.DIFFERENCE)) c.drawPath(path, paint)

        paint.color = 0xFF0F0F13.toInt()
        path.rewind()
        if (path.op(shapeB, shapeA, Path.Op.DIFFERENCE)) c.drawPath(path, paint)
    }

    /**
     * Super resolution is not "an edge converging" - nobody recognises that. It is
     * *detail where there was none*: the same colourful sunset as the HDR row, no sun, and
     * a sky full of sparkles plus a razor ridge that a single frame does not resolve.
     */
    private fun drawSuperResolution(c: Canvas, t: Float) {
        drawSunset(c, 0f, LW, openShadows = true, withSun = false)

        // Razor-sharp ridge: the acutance a stack buys over one frame.
        paint.style = Paint.Style.STROKE
        paint.shader = null
        paint.strokeWidth = 1.3f
        paint.color = 0xFFFFD9A0.toInt()
        paint.alpha = 190
        path.rewind()
        path.moveTo(2f, GROUND_Y)
        path.lineTo(LW * 0.34f, 49f)
        path.lineTo(LW * 0.66f, GROUND_Y)
        c.drawPath(path, paint)

        // Fine striations in the foreground: texture that only exists in the merged frame.
        // Kept sparse and short - a dense comb of lines read as a barcode.
        paint.strokeWidth = 0.9f
        paint.color = 0xFF6B4E60.toInt()
        paint.alpha = 150
        c.drawLine(26f, 99f, 34f, 99f, paint)
        c.drawLine(48f, 104f, 60f, 104f, paint)
        c.drawLine(186f, 97f, 198f, 97f, paint)
        c.drawLine(206f, 105f, 216f, 105f, paint)

        // Twinkling sparkles, each on its own phase so the field never pulses in unison.
        for (i in sparkles.indices) {
            val s = sparkles[i]
            val k = 0.5f + 0.5f * sin(twoPi * (s[2] + t))
            drawSparkle(c, s[0], s[1], 1.1f + 3.4f * k, 0.12f + 0.88f * k, sparkleTints[i % 3])
        }

        paint.alpha = 255
        paint.strokeWidth = 1f
        paint.style = Paint.Style.FILL
    }

    private fun drawSparkle(c: Canvas, x: Float, y: Float, r: Float, k: Float, tint: Int) {
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = tint

        paint.alpha = (k * 46f).toInt().coerceIn(0, 255)
        c.drawCircle(x, y, r * 1.7f, paint)

        paint.alpha = (k * 235f).toInt().coerceIn(0, 255)
        c.drawRect(x - r, y - 0.55f, x + r, y + 0.55f, paint)
        c.drawRect(x - 0.55f, y - r, x + 0.55f, y + r, paint)
        c.drawCircle(x, y, 1.05f, paint)
    }
}
