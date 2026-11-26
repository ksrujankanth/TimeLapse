package com.dan.timelapse.widgets

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Container that handles pinch-zoom, pan and double-tap-zoom for its children.
 *
 * Coordinate model (keep this true when editing):
 *
 *  - The container itself is never scaled or translated. Only its children are.
 *  - Every child is scaled about the centre of the padding box (the usable window, i.e. the
 *    view minus the status-bar inset) and then shifted by ([_offsetX], [_offsetY]). For any
 *    child the mapping is therefore
 *    `X = windowCentre + offset + (x - windowCentre) * scale` in container coordinates.
 *  - `_offsetX` is the distance between the window centre and the centre of the content,
 *    positive = content moved RIGHT / DOWN. Swiping right must INCREASE it.
 */
class ZoomableFrameLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    interface OnZoomListener {
        fun onZoom(scale: Float, translationX: Float, translationY: Float)
        fun onZoomReset()
        fun onZoomStart() {}
        fun onZoomEnd() {}
    }

    /**
     * Optional contract for children that draw their media into a rect smaller than their own
     * bounds: a letterboxed bitmap, a centre-cropped video, anything with black bars.
     *
     * The rect is reported in this container's coordinate space. The bounds guard needs it so
     * it can keep the *visible media* on screen instead of the (often much larger) View box.
     * Without it a 4:3 photo inside a 20:9 window can be pushed until the photo itself is
     * completely gone while the View that hosts it still covers the whole screen.
     */
    interface ContentBoundsProvider {
        fun contentBoundsInContainer(dst: RectF)
    }

    companion object {
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 5f
        private const val ANIMATION_DURATION_MS = 220L
    }

    var onZoomListener: OnZoomListener? = null
    var isZoomEnabled = true

    /**
     * Gates pinch / double-tap scaling only. [isZoomEnabled] keeps touch ownership (and with it
     * tap-to-open), while this says whether there is anything worth scaling. With no media
     * loaded a pinch would just magnify the empty-state copy.
     */
    var isZoomGestureEnabled = true
    var onGestureEnd: (() -> Unit)? = null

    /**
     * Fired for a confirmed single tap that was not a pan, a pinch or a double-tap.
     *
     * This container consumes every touch it is enabled for, so click listeners on it or on
     * its children can never fire. Owning the tap here keeps one single source of truth and
     * lets the gesture detectors decide whether the user meant "tap" or "start a gesture".
     */
    var onTap: (() -> Unit)? = null

    private var _scale = 1f
    private var _offsetX = 0f
    private var _offsetY = 0f
    private var currentPointerCount = 0
    private var zoomAnimator: ValueAnimator? = null

    // Scratch buffers kept as fields so the per-event bounds clamp does not allocate.
    private val contentRect = RectF()
    private val clampOut = FloatArray(2)

    // The usable window: the padding box, not the raw View bounds. The container is padded by
    // the status-bar inset (MainActivity does `setPadding(0, insets.top, 0, 0)`), so measuring
    // the raw box would be off by half an inset.
    private val viewportLeft: Float get() = paddingLeft.toFloat()
    private val viewportTop: Float get() = paddingTop.toFloat()
    private val viewportRight: Float get() = (width - paddingRight).toFloat()
    private val viewportBottom: Float get() = (height - paddingBottom).toFloat()
    private val viewportCentreX: Float get() = (viewportLeft + viewportRight) / 2f
    private val viewportCentreY: Float get() = (viewportTop + viewportBottom) / 2f

    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector

    init {
        scaleDetector = ScaleGestureDetector(context, ScaleListener())
        scaleDetector.isQuickScaleEnabled = true
        gestureDetector = GestureDetector(context, GestureListener())
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!isZoomEnabled) return false
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isZoomEnabled) return false

        currentPointerCount = event.pointerCount

        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            // A new gesture must never fight a running snap-back / double-tap animation.
            cancelZoomAnimation()
        }

        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            onGestureEnd?.invoke()
            if (_scale < 1.0f) {
                animateBackToOriginal()
            } else {
                constrainBounds()
            }
        }
        return true
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // Children were just re-measured (a TextureVideoView physically shrinks itself to the
        // video aspect box) and inset padding may have changed, so re-derive the pivots and
        // re-clamp against the new content box.
        constrainBounds(notify = false)
    }

    /**
     * Called by a [ContentBoundsProvider] child when its visible media rect changes without a
     * layout pass (e.g. TouchImageView receiving a new bitmap of the same size).
     */
    fun onContentBoundsChanged() {
        if (width <= 0 || height <= 0) return
        constrainBounds(notify = false)
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        private var isQuickScaling = false
        private var quickScaleFocusX = 0f
        private var quickScaleFocusY = 0f
        private var lastFocusX = 0f
        private var lastFocusY = 0f

        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            if (!isZoomGestureEnabled) return false
            isQuickScaling = currentPointerCount == 1

            // Lock the focal point during a double-tap-and-drag so it doesn't fly off screen
            quickScaleFocusX = detector.focusX
            quickScaleFocusY = detector.focusY

            lastFocusX = detector.focusX
            lastFocusY = detector.focusY

            onZoomListener?.onZoomStart()
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val focusX = if (isQuickScaling) quickScaleFocusX else detector.focusX
            val focusY = if (isQuickScaling) quickScaleFocusY else detector.focusY

            // Finger drift during a real pinch is also a pan. Combined with the focal formula
            // below this is exactly "the content point under the fingers follows the fingers".
            if (!isQuickScaling) {
                _offsetX += (focusX - lastFocusX)
                _offsetY += (focusY - lastFocusY)
            }
            lastFocusX = focusX
            lastFocusY = focusY

            val prevScale = _scale

            // Plain pinch mapping: the finger ratio is applied exactly once (see ZoomMath).
            _scale = ZoomMath.scaleAfterPinch(_scale, detector.scaleFactor, MIN_SCALE, MAX_SCALE)
            val actualScaleChange = _scale / prevScale

            val pivotX = viewportCentreX
            val pivotY = viewportCentreY

            // Keeps the focal point pinned to the same content point while the scale changes.
            _offsetX = ZoomMath.focalOffset(_offsetX, focusX, pivotX, actualScaleChange)
            _offsetY = ZoomMath.focalOffset(_offsetY, focusY, pivotY, actualScaleChange)

            constrainBounds()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            onZoomListener?.onZoomEnd()
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        private var doubleTapStartScale = 1.0f

        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            // GestureDetector only calls this for a tap that stayed inside the touch slop and
            // was not followed by a second tap, so pan / pinch / double-tap-zoom never land
            // here. Keeping it "confirmed" (not onSingleTapUp) is what stops a double-tap
            // zoom from also toggling playback.
            onTap?.invoke()
            return true
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            // distanceX/Y are incremental and already in physical pixels: this container is
            // never scaled (only its children are), so local space == screen space here.
            //
            // Do NOT use (e2.rawX - e1.rawX): e1 is the gesture's DOWN event, so that
            // difference is the whole travel since the finger went down. Re-applying it on
            // every callback accelerated the pan far past the finger and reversed it.
            // GestureDetector also reports the values as "previous minus current", hence the
            // negation.
            if (!scaleDetector.isInProgress) {
                _offsetX = ZoomMath.panOffset(_offsetX, -distanceX)
                _offsetY = ZoomMath.panOffset(_offsetY, -distanceY)
                constrainBounds()
            }
            return true
        }

        override fun onDoubleTapEvent(e: MotionEvent): Boolean {
            if (e.action == MotionEvent.ACTION_DOWN) {
                doubleTapStartScale = _scale
            } else if (e.action == MotionEvent.ACTION_UP && !scaleDetector.isInProgress) {
                if (!isZoomGestureEnabled) return true

                // If the scale changed significantly it was a double-tap-and-drag. Do not wipe it.
                if (abs(_scale - doubleTapStartScale) > 0.05f) {
                    constrainBounds()
                    return true
                }

                if (_scale > 1.0f) {
                    animateBackToOriginal()
                } else {
                    val pivotX = viewportCentreX
                    val pivotY = viewportCentreY

                    val targetScale = (2.0f).coerceIn(MIN_SCALE, MAX_SCALE)
                    val k = targetScale / _scale

                    val targetOffsetX = ZoomMath.focalOffset(_offsetX, e.x, pivotX, k)
                    val targetOffsetY = ZoomMath.focalOffset(_offsetY, e.y, pivotY, k)

                    animateToZoom(targetScale, targetOffsetX, targetOffsetY)
                }
            }
            return true
        }
    }

    /**
     * The rect of the media that is actually visible, in container coordinates and before the
     * zoom transform. Falls back to the whole container when no child reports one.
     */
    private fun obtainContentRect(): RectF {
        for (i in childCount - 1 downTo 0) {
            val child = getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            if (child !is ContentBoundsProvider) continue
            contentRect.setEmpty()
            child.contentBoundsInContainer(contentRect)
            if (contentRect.width() > 0f && contentRect.height() > 0f) return contentRect
        }
        contentRect.set(0f, 0f, width.toFloat(), height.toFloat())
        return contentRect
    }

    /**
     * Clamps [offX]/[offY] for [scale] into [out].
     *
     * Per axis: if the scaled media is smaller than the window it can only be centred
     * (otherwise there would be nothing but background under the finger); if it is bigger it
     * must keep covering the window. This is what makes "the image can never leave the screen"
     * a structural guarantee instead of a best effort.
     */
    private fun clampOffsets(scale: Float, offX: Float, offY: Float, out: FloatArray) {
        if (width <= 0 || height <= 0) {
            out[0] = offX
            out[1] = offY
            return
        }
        val content = obtainContentRect()
        ZoomMath.clampOffset(
            viewportLeft,
            viewportTop,
            viewportRight,
            viewportBottom,
            content.left,
            content.top,
            content.right,
            content.bottom,
            scale,
            offX,
            offY,
            out
        )
    }

    private fun constrainBounds(notify: Boolean = true) {
        if (width <= 0 || height <= 0) return

        _scale = _scale.coerceIn(MIN_SCALE, MAX_SCALE)

        clampOffsets(_scale, _offsetX, _offsetY, clampOut)
        _offsetX = clampOut[0]
        _offsetY = clampOut[1]

        applyZoom()
        if (notify) onZoomListener?.onZoom(_scale, _offsetX, _offsetY)
    }

    // PURE PARENT ARCHITECTURE: apply the matrix exclusively to the children.
    private fun applyZoom() {
        // Pivot every child about the same point the focal math and the clamp use: the centre
        // of the padding box.
        val centerX = viewportCentreX
        val centerY = viewportCentreY
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            // A View pivot is expressed in the CHILD's own coordinate space. Setting it to
            // width/2 (the container's half-width) only happens to coincide with the child
            // centre when the child fills the container. For a letterboxed TextureVideoView
            // it put the pivot far outside the child, so the first pinch flung the video
            // off-screen. Convert the container centre into child-local coordinates instead.
            child.pivotX = ZoomMath.childLocalContainerCenter(centerX, child.left.toFloat())
            child.pivotY = ZoomMath.childLocalContainerCenter(centerY, child.top.toFloat())
            child.scaleX = _scale
            child.scaleY = _scale
            child.translationX = _offsetX
            child.translationY = _offsetY
        }
        invalidate()
    }

    private fun cancelZoomAnimation() {
        zoomAnimator?.cancel()
        zoomAnimator = null
    }

    private fun startAnimation(
        startScale: Float,
        startX: Float,
        startY: Float,
        endScale: Float,
        endX: Float,
        endY: Float,
        onFinished: () -> Unit
    ) {
        cancelZoomAnimation()

        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = ANIMATION_DURATION_MS
        anim.interpolator = DecelerateInterpolator()
        anim.addUpdateListener { animation ->
            val fraction = animation.animatedFraction
            _scale = startScale + (endScale - startScale) * fraction
            _offsetX = startX + (endX - startX) * fraction
            _offsetY = startY + (endY - startY) * fraction
            applyZoom()
        }

        var cancelled = false
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                if (zoomAnimator === anim) zoomAnimator = null
                if (cancelled) return
                _scale = endScale
                _offsetX = endX
                _offsetY = endY
                applyZoom()
                onFinished()
            }
        })

        zoomAnimator = anim
        anim.start()
    }

    private fun animateBackToOriginal() {
        startAnimation(_scale, _offsetX, _offsetY, 1f, 0f, 0f) {
            onZoomListener?.onZoomReset()
        }
    }

    private fun animateToZoom(targetScale: Float, targetOffsetX: Float, targetOffsetY: Float) {
        // Clamp the DESTINATION against the destination scale, otherwise a focal point near
        // the edge of a letterboxed image produces an end state that has to jump on completion.
        val endScale = targetScale.coerceIn(MIN_SCALE, MAX_SCALE)
        clampOffsets(endScale, targetOffsetX, targetOffsetY, clampOut)

        startAnimation(_scale, _offsetX, _offsetY, endScale, clampOut[0], clampOut[1]) {
            constrainBounds()
        }
    }

    fun restoreZoom(scale: Float, tx: Float, ty: Float) {
        if (width == 0 || height == 0) return
        cancelZoomAnimation()

        _scale = scale.coerceIn(MIN_SCALE, MAX_SCALE)
        _offsetX = tx
        _offsetY = ty

        // Restored transforms can predate a content change (different media, rotated screen,
        // different aspect ratio), so never trust them blindly.
        constrainBounds(notify = false)
    }
}
