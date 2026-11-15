package com.dan.timelapse.widgets

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.graphics.toRect
import kotlin.math.max
import kotlin.math.min

interface TouchImageViewListener {
    fun onViewRectChanged(rect: RectF)
    fun onTouchEvent(event: MotionEvent): Boolean
}

interface OnSurfaceReadyListener {
    fun onSurfaceReady()
}

open class TouchImageView @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val ACTION_NONE = 0
        const val ACTION_MOVE = 10
        const val ACTION_DOUBLE_TAP = 11
        const val ACTION_SCALE = 20

        const val MAX_PIXEL_ZOOM = 10 // bitmap pixels / view pixel
    }

    private var _listener: TouchImageViewListener? = null
    private var _surfaceReadyListener: OnSurfaceReadyListener? = null
    private var _bitmap: Bitmap? = null
    private var action = ACTION_NONE
    private var actionScale = 1.0f
    private val actionScaleCenter = PointF()
    private val actionMove = PointF()
    private val _viewRect = RectF()
    private val bgPaint = Paint()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        isDither = true
    }
    private val widgetRect = Rect()
    private val tempMatrix = Matrix()
    private val tempInverse = Matrix()
    private val tempPointF = PointF()
    private val tempRectF = RectF()
    private val tempFloatArray = FloatArray(9)
    private val tempPointF2 = PointF()
    private var currentRotation: Int = 0
    private var currentAspectRatio: Float? = null

    init {
        bgPaint.color = Color.WHITE
        bgPaint.style = Paint.Style.FILL
    }

    var viewRect: RectF
        get() = _viewRect
        set(rect) {
            _viewRect.set(rect)
            invalidate()
        }

    val viewToBitmapScale: Float
        get() {
            val bitmap = this._bitmap ?: return 1f
            return bitmap.width / _viewRect.width()
        }

    private val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.OnScaleGestureListener {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            action = max(action, ACTION_SCALE)
            actionScale = detector.scaleFactor
            actionScaleCenter.set(detector.focusX, detector.focusY)
            return true
        }

        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            action = max(action, ACTION_DOUBLE_TAP)
            return true
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            action = max(action, ACTION_MOVE)
            actionMove.set(distanceX, distanceY)
            return true
        }

        override fun onDown(e: MotionEvent): Boolean {
            return true
        }
    })

    fun setListener(lister: TouchImageViewListener?) {
        _listener = lister
    }

    fun setSurfaceReadyListener(lister: OnSurfaceReadyListener?) {
        _surfaceReadyListener = lister
    }

    // This allows MainActivity to call the standard method name
    fun setImageBitmap(bitmap: Bitmap?) {
        setBitmap(bitmap) // Calls your existing complex logic
    }

    open fun setBitmap(bitmap: Bitmap?, _reset: Boolean = true) {
        var reset = _reset
        if (!reset) {
            val oldBitmap = this._bitmap
            if (null == bitmap || null == oldBitmap) {
                reset = true
            } else if (bitmap.width != oldBitmap.width || bitmap.height != oldBitmap.height) {
                reset = true
            }
        }

        this._bitmap = bitmap
        if (reset) resetPosition()
        invalidate()
    }

    fun getBitmap(): Bitmap? = _bitmap

    private fun resetPosition() {
        _bitmap?.let { bitmap ->
            _viewRect.set(bestFitRect(width, height, bitmap.width, bitmap.height))
        }
    }

    private fun bestFitRect(outputWidth: Int, outputHeight: Int, inputWidth: Int, inputHeight: Int): RectF {
        if (outputWidth == 0 || outputHeight == 0 || inputWidth == 0 || inputHeight == 0) {
            return RectF(0f, 0f, 0f, 0f)
        }
        
        val effectiveInputWidth: Int
        val effectiveInputHeight: Int
        if (currentRotation == 90 || currentRotation == 270) {
            effectiveInputWidth = inputHeight
            effectiveInputHeight = inputWidth
        } else {
            effectiveInputWidth = inputWidth
            effectiveInputHeight = inputHeight
        }

        // Apply Crop Aspect Ratio calculation to determine logical box
        val cropW: Float
        val cropH: Float
        if (currentAspectRatio != null) {
             val srcRatio = effectiveInputWidth.toFloat() / effectiveInputHeight.toFloat()
             if (srcRatio > currentAspectRatio!!) {
                 cropH = effectiveInputHeight.toFloat()
                 cropW = cropH * currentAspectRatio!!
             } else {
                 cropW = effectiveInputWidth.toFloat()
                 cropH = cropW / currentAspectRatio!!
             }
        } else {
             cropW = effectiveInputWidth.toFloat()
             cropH = effectiveInputHeight.toFloat()
        }
        
        val availableHeight = outputHeight.toFloat()
        var width = outputWidth.toFloat()
        var height = width * cropH / cropW

        if (height > availableHeight) {
            height = availableHeight
            width = height * cropW / cropH
        }

        val left = (outputWidth - width) / 2
        val top = (outputHeight - height) / 2
        return RectF(left, top, left + width - 1, top + height - 1)
    }

    private fun scaleRect(rect: RectF, scale: Float, center: PointF, minSize: PointF, maxSize: PointF) {
        val fixedScaleCenter = PointF(
                min(max(center.x, rect.left), rect.right),
                min(max(center.y, rect.top), rect.bottom)
        )

        val newSize = PointF(
                rect.width() * scale,
                rect.height() * scale
        )

        if (newSize.x > maxSize.x || newSize.y > maxSize.y) {
            newSize.set(maxSize)
        }

        if (newSize.x < minSize.x || newSize.y < minSize.y) {
            newSize.set(minSize)
        }

        val topLeftPercent = PointF(
                (fixedScaleCenter.x - rect.left) / rect.width(),
                (fixedScaleCenter.y - rect.top) / rect.height()
        )

        val newSizeDelta = PointF(
                newSize.x - rect.width(),
                newSize.y - rect.height()
        )

        val scaleDeltaTopLeft = PointF(
                topLeftPercent.x * newSizeDelta.x,
                topLeftPercent.y * newSizeDelta.y
        )

        rect.left -= scaleDeltaTopLeft.x
        rect.top -= scaleDeltaTopLeft.y
        rect.right += newSizeDelta.x - scaleDeltaTopLeft.x
        rect.bottom += newSizeDelta.y - scaleDeltaTopLeft.y
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        widgetRect.right = w
        widgetRect.bottom = h
        resetPosition()
        _surfaceReadyListener?.onSurfaceReady()
    }

    private fun updateViewRect(viewRect: RectF, fitRect: RectF) {
        // Safety: if view dimensions are 0, skip the math to prevent infinite loops
        if (width == 0 || height == 0) {
            return
        }

        if (viewRect.width() < width && viewRect.height() < height) {
            viewRect.set(fitRect)
        } else {
            if (viewRect.width() < width) {
                viewRect.offsetTo((width - viewRect.width()) / 2, viewRect.top)
            } else if (viewRect.left > 0) {
                viewRect.offsetTo(0f, viewRect.top)
            } else if (viewRect.right < width) {
                viewRect.offsetTo(width - viewRect.width(), viewRect.top)
            }

            if (viewRect.height() < height) {
                viewRect.offsetTo(viewRect.left, (height - viewRect.height()) / 2)
            } else if (viewRect.top > 0) {
                viewRect.offsetTo(viewRect.left, 0f)
            } else if (viewRect.bottom < height) {
                viewRect.offsetTo(viewRect.left, height - viewRect.height())
            }
        }

        val dirty = viewRect.toRect() != this._viewRect.toRect()
        this._viewRect.set(viewRect)

        if (dirty) {
            invalidate()
            _listener?.onViewRectChanged(this._viewRect)
        }
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (null == event) return true

        // Let the standard gesture detectors run
        scaleGestureDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        val action = event.action and MotionEvent.ACTION_MASK

        // Let external listeners handle the touch if needed
        val lister = _listener
        if (null != lister && lister.onTouchEvent(event)) return true

        // Break the infinite state loop: only emit when the finger is lifted
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            _listener?.onViewRectChanged(_viewRect)
        }

        // Always consume touch events when we have a bitmap.
        // This prevents gestures from reaching MainActivity's GestureDetector
        // which would trigger togglePlayback and cause position jumps.
        return _bitmap != null
    }

    fun getDisplayMatrix(): Matrix {
        tempMatrix.reset()
        val bitmap = _bitmap ?: return tempMatrix

        val scale = min(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)

        tempMatrix.postScale(scale, scale)
        tempMatrix.postTranslate(_viewRect.left, _viewRect.top)

        return tempMatrix
    }

    fun getNormalizedPoint(x: Float, y: Float): PointF {
        getDisplayMatrix().invert(tempInverse)
        tempFloatArray[0] = x
        tempFloatArray[1] = y
        tempInverse.mapPoints(tempFloatArray)
        tempPointF.set(tempFloatArray[0], tempFloatArray[1])
        return tempPointF
    }

    fun getAdjustedRadius(baseRadius: Float): Float {
        getDisplayMatrix().getValues(tempFloatArray)
        val currentScale = tempFloatArray[Matrix.MSCALE_X]
        return baseRadius / currentScale
    }

    /**
     * Set rotation in degrees (0, 90, 180, 270).
     * This will recalculate the best fit rectangle accounting for the rotation.
     */
    fun setRotation(degrees: Int) {
        val normalizedDegrees = degrees % 360
        if (currentRotation != normalizedDegrees) {
            currentRotation = normalizedDegrees
            resetPosition()
            invalidate()
        }
    }

    fun getCustomRotation(): Int = currentRotation

    fun setRotationDegrees(degrees: Int) {
        if (currentRotation != degrees) {
            currentRotation = degrees
            resetPosition()
            invalidate()
            requestLayout()
        }
    }

    fun setAspectRatio(ratio: Float?) {
        if (currentAspectRatio != ratio) {
            currentAspectRatio = ratio
            resetPosition()
            invalidate()
            requestLayout()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = _bitmap ?: return

        canvas.save()
        
        canvas.clipRect(_viewRect)

        val srcRect = Rect(0, 0, bmp.width, bmp.height)
        
        if (currentAspectRatio != null) {
            val isRotated = currentRotation == 90 || currentRotation == 270
            val effectiveBmpW = if (isRotated) bmp.height else bmp.width
            val effectiveBmpH = if (isRotated) bmp.width else bmp.height
            
            val bmpRatio = effectiveBmpW.toFloat() / effectiveBmpH.toFloat()
            val targetRatio = currentAspectRatio!!

            var cropW = effectiveBmpW
            var cropH = effectiveBmpH

            if (bmpRatio > targetRatio) {
                cropW = (effectiveBmpH * targetRatio).toInt()
            } else {
                cropH = (effectiveBmpW / targetRatio).toInt()
            }

            val actualCropW = if (isRotated) cropH else cropW
            val actualCropH = if (isRotated) cropW else cropH

            val left = (bmp.width - actualCropW) / 2
            val top = (bmp.height - actualCropH) / 2
            
            srcRect.set(left, top, left + actualCropW, top + actualCropH)
        }

        if (currentRotation != 0) {
            val cx = _viewRect.centerX()
            val cy = _viewRect.centerY()
            canvas.rotate(currentRotation.toFloat(), cx, cy)
            
            val drawRect = if (currentRotation == 90 || currentRotation == 270) {
                RectF(
                    cx - _viewRect.height() / 2f,
                    cy - _viewRect.width() / 2f,
                    cx + _viewRect.height() / 2f,
                    cy + _viewRect.width() / 2f
                )
            } else {
                _viewRect
            }
            canvas.drawBitmap(bmp, srcRect, drawRect, bitmapPaint)
        } else {
            canvas.drawBitmap(bmp, srcRect, _viewRect, bitmapPaint)
        }
        
        canvas.restore()
    }
}
