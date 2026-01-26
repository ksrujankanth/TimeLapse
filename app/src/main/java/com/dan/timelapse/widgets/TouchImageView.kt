package com.dan.timelapse

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
    private var _bitmap: Bitmap? = null
    private var action = ACTION_NONE
    private var actionScale = 1.0f
    private val actionScaleCenter = PointF()
    private val actionMove = PointF()
    private val _viewRect = RectF()
    private val bgPaint = Paint()
    private val widgetRect = Rect()

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
        // Add the 'override' modifier here
        override fun onScale(detector: ScaleGestureDetector): Boolean { // Removed nullable '?' from ScaleGestureDetector
            // It's good practice to check for null if the API doesn't guarantee non-null
            // However, in this specific listener, 'detector' is generally expected to be non-null.
            // If you encounter NullPointerExceptions, you might need to add a null check.
            action = max(action, ACTION_SCALE)
            actionScale = detector.scaleFactor // 'detector' is now non-null
            actionScaleCenter.set(detector.focusX, detector.focusY) // 'detector' is now non-null
            return true
        }

        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { // Removed nullable '?'
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) { // Removed nullable '?'
            // Consider adding a null check for 'detector' if needed, though often non-null here.
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean { // Changed e: MotionEvent? to e: MotionEvent
            action = max(action, ACTION_DOUBLE_TAP)
            // Since 'e' is now non-null, you don't need a null check for 'e' itself here.
            return true
        }

        override fun onScroll(
            e1: MotionEvent?, // This was already correct (nullable)
            e2: MotionEvent,  // Changed e2: MotionEvent? to e2: MotionEvent
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            action = max(action, ACTION_MOVE)
            actionMove.set(distanceX, distanceY)
            // Since 'e2' is now non-null, you don't need a null check for 'e2' itself here.
            return true
        }

        // IMPORTANT: It's good practice to also override onDown and return true
        // if you are handling gestures like onScroll or onFling,
        // as onDown is used to determine if the listener is interested in subsequent events.
        override fun onDown(e: MotionEvent): Boolean {
            return true // Or true if you want to consume the event
        }
    })

    fun setListener(lister: TouchImageViewListener?) {
        _listener = lister
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
        var width = outputWidth.toFloat()
        var height = width * inputHeight / inputWidth

        if (height > outputHeight) {
            height = outputHeight.toFloat()
            width = height * inputWidth / inputHeight
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
    }

    private fun updateViewRect(viewRect: RectF, fitRect: RectF) {

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
        val bitmap = this._bitmap ?: return true

        val lister = _listener
        if (null != lister && lister.onTouchEvent(event)) return true

        action = ACTION_NONE
        scaleGestureDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        val viewRect = RectF(_viewRect)
        val fitRect = bestFitRect(width, height, bitmap.width, bitmap.height)
        val minSize = PointF(fitRect.width(), fitRect.height())
        val maxSize = PointF(bitmap.width.toFloat() * MAX_PIXEL_ZOOM, bitmap.height.toFloat() * MAX_PIXEL_ZOOM)

        when(action) {
            ACTION_DOUBLE_TAP -> {
                if (viewRect.width() > width || viewRect.height() > height) {
                    viewRect.set(fitRect)
                } else {
                    var scale = bitmap.width / viewRect.width()
                    if (scale < 2f) scale = 2f
                    scaleRect(
                            viewRect,
                            scale,
                            PointF(event.x, event.y),
                            minSize,
                            maxSize
                    )
                }
            }

            ACTION_MOVE -> {
                viewRect.offset(-actionMove.x, -actionMove.y)
            }

            ACTION_SCALE -> {
                scaleRect(viewRect, actionScale, actionScaleCenter, minSize, maxSize)
            }

            else -> return true
        }

        updateViewRect(viewRect, fitRect)

        return true
    }

    // In TouchImageView.kt
    override fun onDraw(canvas: Canvas) { // Remove nullable '?' from Canvas
        super.onDraw(canvas)
        // if (null == canvas) return // This check is no longer needed as canvas is non-null

        canvas.drawRect(widgetRect, bgPaint)

        val bitmap = this._bitmap
        if (null == bitmap) {
            @Suppress("DEPRECATION") // Keep this for now to avoid another warning
            val noImage = resources.getDrawable(android.R.drawable.ic_menu_report_image)
            noImage.bounds = bestFitRect(width, height, 100, 100).toRect()
            noImage.setTint(Color.GRAY)
            noImage.draw(canvas)
        } else {
            val fullRect = Rect(_viewRect.toRect())
            fullRect.right += 1
            fullRect.bottom += 1
            canvas.drawBitmap(bitmap, null, fullRect, null)
        }
    }

}