package com.dan.timelapse.widgets

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import org.opencv.core.Mat

enum class MaskMode { ZOOM, DRAW, ERASE }

class MaskView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var currentMode = MaskMode.DRAW
    
    // Callback for when user draws a stroke
    var onStrokeDrawn: (() -> Unit)? = null
    
    // External bitmap for mask rendering (used by MaskEditFragment)
    private var externalMaskBitmap: Bitmap? = null
    
    // THE FIX: Cache the display bitmap to avoid recreating it every frame
    private var cachedDisplayBitmap: Bitmap? = null
    private var lastBitmapSize = 0
    
    // THE FIX: Cache the pixels array to avoid 60fps allocation churn
    private var cachedPixels: IntArray? = null
    
    // Drawing state
    private var lastX = -1f
    private var lastY = -1f
    private val paint = Paint()
    
    // Bezier curve smoothing
    private var prevX = -1f
    private var prevY = -1f
    private val TOUCH_TOLERANCE = 4f
    
    // Brush settings
    private var brushRadiusDp = 20f
    private var brushRadiusPx = 0f
    
    // Current zoom level from the container
    private var currentZoom = 1f
    
    // Pan/translation from the container
    private var currentTranslationX = 0f
    private var currentTranslationY = 0f
    
    // Path for smooth drawing
    private val path = Path()
    
    // Calculated display bounds - where the bitmap is actually drawn in the view
    private var displayRect = RectF()

    /**
     * Set the current zoom level from the container
     */
    fun setZoomLevel(zoom: Float) {
        this.currentZoom = zoom
    }
    
    /**
     * Set the current pan offset from the container
     */
    fun setTranslation(translationX: Float, translationY: Float) {
        this.currentTranslationX = translationX
        this.currentTranslationY = translationY
    }

    fun setMode(mode: MaskMode) {
        this.currentMode = mode
        lastX = -1f
        lastY = -1f
        prevX = -1f
        prevY = -1f
        path.reset()
    }
    
    fun getMode(): MaskMode = currentMode

    fun setBrushRadius(radiusDp: Float) {
        this.brushRadiusDp = radiusDp
        this.brushRadiusPx = radiusDp * context.resources.displayMetrics.density
    }
    
    private var imageWidth = 0
    private var imageHeight = 0
    
    fun setImageDimensions(width: Int, height: Int) {
        this.imageWidth = width
        this.imageHeight = height
    }
    
    fun setExternalMaskBitmap(bitmap: Bitmap?) {
        externalMaskBitmap = bitmap
        // Invalidate cache when bitmap changes
        cachedDisplayBitmap?.recycle()
        cachedDisplayBitmap = null
        invalidate()
    }
    
    fun getExternalMaskBitmap(): Bitmap? = externalMaskBitmap

    fun clearMask() {
        externalMaskBitmap?.eraseColor(Color.BLACK)
        cachedDisplayBitmap?.recycle()
        cachedDisplayBitmap = null
        invalidate()
    }

    fun getExportedMat(): Mat {
        if (externalMaskBitmap != null) {
            val mask = Mat.zeros(externalMaskBitmap!!.height, externalMaskBitmap!!.width, org.opencv.core.CvType.CV_8UC1)
            org.opencv.android.Utils.bitmapToMat(externalMaskBitmap, mask)
            org.opencv.imgproc.Imgproc.cvtColor(mask, mask, org.opencv.imgproc.Imgproc.COLOR_RGBA2GRAY)
            return mask
        }
        return Mat()
    }

    fun setExportedMat(mat: Mat) {
        if (externalMaskBitmap == null || 
            externalMaskBitmap!!.width != mat.cols() || 
            externalMaskBitmap!!.height != mat.rows()) {
            externalMaskBitmap?.recycle()
            externalMaskBitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        }
        org.opencv.android.Utils.matToBitmap(mat, externalMaskBitmap)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        brushRadiusPx = brushRadiusDp * context.resources.displayMetrics.density
    }
    
    /**
     * Calculate where the bitmap is displayed in the view (for center-crop/fit)
     */
    private fun calculateDisplayRect(): RectF {
        if (externalMaskBitmap == null || width == 0 || height == 0) {
            return RectF(0f, 0f, width.toFloat(), height.toFloat())
        }
        
        val bmpW = externalMaskBitmap!!.width.toFloat()
        val bmpH = externalMaskBitmap!!.height.toFloat()
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        
        // Calculate scale to fit/fill the view (same logic as TextureVideoView)
        val bmpRatio = bmpW / bmpH
        val viewRatio = viewW / viewH
        
        val scale: Float
        val displayLeft: Float
        val displayTop: Float
        
        if (bmpRatio > viewRatio) {
            // Bitmap is wider - fit to width, crop height
            scale = viewW / bmpW
            val scaledHeight = bmpH * scale
            displayLeft = 0f
            displayTop = (viewH - scaledHeight) / 2f
        } else {
            // Bitmap is taller - fit to height, crop width
            scale = viewH / bmpH
            val scaledWidth = bmpW * scale
            displayLeft = (viewW - scaledWidth) / 2f
            displayTop = 0f
        }
        
        return RectF(displayLeft, displayTop, displayLeft + bmpW * scale, displayTop + bmpH * scale)
    }
    
    /**
     * Transform view coordinates to bitmap coordinates
     */
    private fun viewToBitmapCoords(viewX: Float, viewY: Float): PointF? {
        if (externalMaskBitmap == null) return null
        
        val rect = calculateDisplayRect()
        
        // Check if touch is within the display bounds
        if (viewX < rect.left || viewX > rect.right || viewY < rect.top || viewY > rect.bottom) {
            return null // Touch is outside the bitmap area
        }
        
        // Transform to bitmap coordinates
        val bmpX = (viewX - rect.left) * (externalMaskBitmap!!.width.toFloat() / rect.width())
        val bmpY = (viewY - rect.top) * (externalMaskBitmap!!.height.toFloat() / rect.height())
        
        return PointF(bmpX, bmpY)
    }

    /**
     * Get the brush radius for bitmap coordinates
     * The brush stays at a fixed physical size on screen regardless of zoom
     */
    private fun getBrushRadiusForBitmap(): Float {
        if (imageWidth == 0 || imageHeight == 0 || width == 0 || height == 0) {
            return brushRadiusPx * 2
        }
        
        // Calculate scale from view to bitmap
        val scaleX = imageWidth.toFloat() / width
        val scaleY = imageHeight.toFloat() / height
        val avgScale = (scaleX + scaleY) / 2f
        
        // Divide by zoom so brush stays same physical size when zoomed in
        // This makes the brush feel like a real pen - same size on screen regardless of zoom
        val effectiveZoom = if (currentZoom > 1f) currentZoom else 1f
        
        return (brushRadiusPx * avgScale) / effectiveZoom
    }

    init {
        paint.isAntiAlias = true
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = brushRadiusPx * 2
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (currentMode == MaskMode.ZOOM) {
            return false
        }

        // FIX: Ignore multi-touch to prevent stray drawing on pinch
        if (event.pointerCount > 1) return false
        
        if (externalMaskBitmap == null) return false

        val x = event.x
        val y = event.y

        val color = if (currentMode == MaskMode.DRAW) 255 else 0

        // Transform touch coordinates to bitmap coordinates
        val bitmapCoords = viewToBitmapCoords(x, y)
        if (bitmapCoords == null) {
            return true
        }
        
        val bx = bitmapCoords.x
        val by = bitmapCoords.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                lastX = bx
                lastY = by
                prevX = bx
                prevY = by
                drawCircleOnBitmap(bx, by, color)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = Math.abs(bx - lastX)
                val dy = Math.abs(by - lastY)
                
                if (dx >= TOUCH_TOLERANCE || dy >= TOUCH_TOLERANCE) {
                    // Smooth Bezier curve through midpoint
                    drawBezierOnBitmap(prevX, prevY, lastX, lastY, bx, by, color)
                    prevX = lastX
                    prevY = lastY
                    lastX = bx
                    lastY = by
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_UP -> {
                // Draw final segment
                if (lastX >= 0 && prevX >= 0) {
                    drawLineOnBitmap(prevX, prevY, lastX, lastY, color)
                }
                lastX = -1f
                lastY = -1f
                prevX = -1f
                prevY = -1f
                return true
            }
        }
        
        return false
    }
    
    private fun drawCircleOnBitmap(x: Float, y: Float, color: Int) {
        externalMaskBitmap?.let { bmp ->
            val canvas = Canvas(bmp)
            paint.color = Color.rgb(color, color, color)
            
            // Use zoom-independent brush size
            val radius = getBrushRadiusForBitmap()
            
            paint.strokeWidth = radius * 2
            paint.style = Paint.Style.FILL
            canvas.drawCircle(x, y, radius, paint)
            paint.style = Paint.Style.STROKE
            
            invalidate()
            onStrokeDrawn?.invoke()
        }
    }
    
    private fun drawBezierOnBitmap(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, color: Int) {
        externalMaskBitmap?.let { bmp ->
            val canvas = Canvas(bmp)
            paint.color = Color.rgb(color, color, color)
            
            val radius = getBrushRadiusForBitmap()
            paint.strokeWidth = radius * 2
            paint.style = Paint.Style.STROKE
            
            val path = Path()
            path.moveTo(x0, y0)
            // Quadratic Bezier through midpoint for smooth curves
            path.quadTo(x1, y1, (x1 + x2) / 2, (y1 + y2) / 2)
            canvas.drawPath(path, paint)
            
            // Draw circles at endpoints for smooth joints
            paint.style = Paint.Style.FILL
            canvas.drawCircle(x0, y0, radius, paint)
            canvas.drawCircle(x1, y1, radius, paint)
            canvas.drawCircle(x2, y2, radius, paint)
            
            invalidate()
            onStrokeDrawn?.invoke()
        }
    }
    
    private fun drawLineOnBitmap(fromX: Float, fromY: Float, toX: Float, toY: Float, color: Int) {
        externalMaskBitmap?.let { bmp ->
            val canvas = Canvas(bmp)
            paint.color = Color.rgb(color, color, color)
            
            // Use zoom-independent brush size
            val radius = getBrushRadiusForBitmap()
            
            paint.strokeWidth = radius * 2
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(fromX, fromY, toX, toY, paint)
            
            // Draw circles at start and end for smooth joints
            paint.style = Paint.Style.FILL
            canvas.drawCircle(fromX, fromY, radius, paint)
            canvas.drawCircle(toX, toY, radius, paint)
            paint.style = Paint.Style.STROKE
            
            invalidate()
            onStrokeDrawn?.invoke()
        }
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        externalMaskBitmap?.let { bmp ->
            // THE FIX: Use cached bitmap instead of recreating every frame
            val bmpSize = bmp.width * bmp.height
            if (cachedDisplayBitmap == null || lastBitmapSize != bmpSize) {
                cachedDisplayBitmap?.recycle()
                cachedDisplayBitmap = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                lastBitmapSize = bmpSize
            }
            
            val cachedBmp = cachedDisplayBitmap!!
            
            // THE FIX: Reuse cached pixels array instead of allocating on every frame
            if (cachedPixels == null || cachedPixels!!.size != bmpSize) {
                cachedPixels = IntArray(bmpSize)
            }
            val pixels = cachedPixels!!
            bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            
            for (i in pixels.indices) {
                val pixel = pixels[i]
                val gray = Color.red(pixel)
                // More opaque grey overlay (0.7f = 70% opacity) - visible on any background
                pixels[i] = Color.argb((gray * 0.7f).toInt(), gray, gray, gray)
            }
            
            cachedBmp.setPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            
            // Draw with proper scaling to fit the view (same as calculateDisplayRect)
            val rect = calculateDisplayRect()
            val srcRect = Rect(0, 0, bmp.width, bmp.height)
            val destRect = RectF(rect)
            canvas.drawBitmap(cachedBmp, srcRect, destRect, null)
        }
    }
}
