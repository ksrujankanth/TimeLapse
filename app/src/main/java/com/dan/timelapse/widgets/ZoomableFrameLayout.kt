package com.dan.timelapse.widgets

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs

class ZoomableFrameLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    interface OnZoomListener {
        fun onZoom(scale: Float, translationX: Float, translationY: Float)
        fun onZoomReset()
        fun onZoomStart() {}
        fun onZoomEnd() {}
    }

    var onZoomListener: OnZoomListener? = null
    var isZoomEnabled = true
    var onGestureEnd: (() -> Unit)? = null

    private var _scale = 1f
    private var _offsetX = 0f
    private var _offsetY = 0f
    private var isRestoring = false
    private var currentPointerCount = 0

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

        // 1. PURE SCREEN PIXELS: Because the parent view never scales itself,
        // the native events provide flawless 1:1 physical screen coordinates.
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        val action = event.actionMasked
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            onGestureEnd?.invoke()
            if (_scale < 1.0f) {
                animateBackToOriginal()
            } else {
                constrainBounds(isGestureEnding = true)
            }
        }
        return true
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        applyZoom() // Ensure new/updated children inherit the current zoom state
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        private var isQuickScaling = false
        private var quickScaleFocusX = 0f
        private var quickScaleFocusY = 0f
        private var lastFocusX = 0f
        private var lastFocusY = 0f

        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
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

            // 2. DRIFT PANNING: Track physical finger drift during 2-finger pinches
            if (!isQuickScaling) {
                _offsetX += (focusX - lastFocusX)
                _offsetY += (focusY - lastFocusY)
            }
            lastFocusX = focusX
            lastFocusY = focusY

            val prevScale = _scale
            // G1-02/G1-05: 1.5x sensitivity boost for snappy pinch
            val boostedFactor = 1.0f + (detector.scaleFactor - 1.0f) * 1.5f
            _scale *= boostedFactor
            _scale = _scale.coerceIn(0.5f, 5.0f)
            val actualScaleChange = _scale / prevScale

            val pivotX = width / 2f
            val pivotY = height / 2f

            // 3. THE HOLY GRAIL FOCAL MATH: Perfectly binds the translation to the scale change multiplier
            _offsetX = (focusX - pivotX) * (1f - actualScaleChange) + _offsetX * actualScaleChange
            _offsetY = (focusY - pivotY) * (1f - actualScaleChange) + _offsetY * actualScaleChange

            constrainBounds(isGestureEnding = false)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            onZoomListener?.onZoomEnd()
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        private var doubleTapStartScale = 1.0f

        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (_scale > 1.0f && !scaleDetector.isInProgress && e1 != null) {
                // G1-06: Use raw screen coordinates to avoid local-coordinate feedback loop
                _offsetX -= (e2.rawX - e1.rawX)
                _offsetY -= (e2.rawY - e1.rawY)
                constrainBounds(isGestureEnding = false)
            }
            return true
        }

        override fun onDoubleTapEvent(e: MotionEvent): Boolean {
            if (e.action == MotionEvent.ACTION_DOWN) {
                doubleTapStartScale = _scale
            } else if (e.action == MotionEvent.ACTION_UP && !scaleDetector.isInProgress) {

                // 4. THE SNAP-BACK FIX: If scale changed significantly, it was a drag. Do not wipe it.
                if (abs(_scale - doubleTapStartScale) > 0.05f) {
                    constrainBounds(isGestureEnding = true)
                    return true
                }

                if (_scale > 1.0f) {
                    animateBackToOriginal()
                } else {
                    val pivotX = width / 2f
                    val pivotY = height / 2f

                    val targetScale = 2.0f
                    val k = targetScale / _scale

                    val targetOffsetX = (e.x - pivotX) * (1f - k) + _offsetX * k
                    val targetOffsetY = (e.y - pivotY) * (1f - k) + _offsetY * k

                    animateToZoom(targetScale, targetOffsetX, targetOffsetY)
                }
            }
            return true
        }
    }

    private fun constrainBounds(isGestureEnding: Boolean) {
        if (_scale <= 1.0f && isGestureEnding) {
            _offsetX = 0f
            _offsetY = 0f
        } else if (_scale > 1.0f) {
            val maxDx = (width * _scale - width) / 2f
            val maxDy = (height * _scale - height) / 2f
            _offsetX = _offsetX.coerceIn(-maxDx, maxDx)
            _offsetY = _offsetY.coerceIn(-maxDy, maxDy)
        }
        applyZoom()
        onZoomListener?.onZoom(_scale, _offsetX, _offsetY)
    }

    // 5. PURE PARENT ARCHITECTURE: Apply the matrix exclusively to the children
    private fun applyZoom() {
        val pivotX = width / 2f
        val pivotY = height / 2f
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.pivotX = pivotX
            child.pivotY = pivotY
            child.scaleX = _scale
            child.scaleY = _scale
            child.translationX = _offsetX
            child.translationY = _offsetY
        }
        invalidate()
    }

    private fun animateBackToOriginal() {
        val startScale = _scale
        val startX = _offsetX
        val startY = _offsetY

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                val fraction = animation.animatedFraction
                _scale = startScale + (1f - startScale) * fraction
                _offsetX = startX + (0f - startX) * fraction
                _offsetY = startY + (0f - startY) * fraction
                applyZoom()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    _scale = 1f
                    _offsetX = 0f
                    _offsetY = 0f
                    applyZoom()
                    onZoomListener?.onZoomReset()
                }
            })
        }
        animator.start()
    }

    private fun animateToZoom(targetScale: Float, targetOffsetX: Float, targetOffsetY: Float) {
        val startScale = _scale
        val startX = _offsetX
        val startY = _offsetY

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                val fraction = animation.animatedFraction
                _scale = startScale + (targetScale - startScale) * fraction
                _offsetX = startX + (targetOffsetX - startX) * fraction
                _offsetY = startY + (targetOffsetY - startY) * fraction
                applyZoom()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    constrainBounds(isGestureEnding = true)
                }
            })
        }
        animator.start()
    }

    fun restoreZoom(scale: Float, tx: Float, ty: Float) {
        if (isRestoring || width == 0 || height == 0) return
        isRestoring = true

        _scale = scale
        _offsetX = tx
        _offsetY = ty

        applyZoom()
        isRestoring = false
    }
}
