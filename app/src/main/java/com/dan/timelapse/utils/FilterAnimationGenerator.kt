package com.dan.timelapse.utils


import android.content.Context
import android.graphics.*
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.BitmapDrawable
import com.dan.timelapse.Effect


/**
 * Triple-Pipe "Logic Cursor" Animation Generator
 * 
 * Scientific Test Strip design showing how each filter handles:
 * - WHITE (Lead): Tests "Lightest" and "Average Weighted for Last" logic
 * - BLACK (Center): Tests "Darkest" and "Difference" logic  
 * - RED (Tail): Tests "Weighted for Light" (color persistence) and "HDR"
 */
class FilterAnimationGenerator(private val context: Context) {

// Cinematic dimensions for wide, smooth animation
    private val width = 600
    private val height = 100
    private val framesCount = 30  // Was 60 - halved to reduce memory + generation time
    private val frameDuration = 160 // Doubled from 80ms to keep same total loop duration
    
    // Triple-Pipe dimensions
    private val pipeWidth = 12f // Thicker for visibility
    private val verticalPadding = 0.20f // 20% space above and below
    private val cursorWidth = pipeWidth * 3 // White + Black + Red
    
    // Windows for cumulative effects (30% window per spec)
    private val avgWindow = (framesCount * 0.30).toInt()
    private val lightWindow = (framesCount * 0.40).toInt()


    fun createAnimation(effect: Effect): AnimationDrawable {
        val animation = AnimationDrawable()
        animation.isOneShot = false

        for (i in 0 until framesCount) {
            // RGB_565 halves memory vs ARGB_8888 with no visible quality loss for these diagrams
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            
            // SYNC GUARANTEE: All frames use the same xPos for straight vertical alignment
            val xPos = (i.toFloat() / framesCount) * (width - cursorWidth - 20) + 10

            when (effect) {
                Effect.NONE -> {
                    // Clean canvas, cursor moves without trail
                    canvas.drawColor(Color.BLACK)
                    drawTriplePipe(canvas, xPos, paint)
                }

                Effect.LIGHTEST_PIXELS, Effect.ENDLESS_LIGHTEST_PIXELS -> {
                    // White and Red leave solid trails (lightest pixels persist)
                    canvas.drawColor(Color.BLACK)
                    drawLightestTrail(canvas, xPos, paint)
                    drawTriplePipe(canvas, xPos, paint)
                }

                Effect.DARKEST_PIXELS, Effect.ENDLESS_DARKEST_PIXELS -> {
                    // DARKEST: Pre-fill with GRAY, Black segment carves black path
                    drawDarkestTrail(canvas, xPos, paint)
                    drawTriplePipe(canvas, xPos, paint)
                }

                Effect.AVERAGE, Effect.ENDLESS_AVERAGE -> {
                    // Standard average - all colors merge
                    canvas.drawColor(Color.BLACK)
                    drawAverageTrail(canvas, xPos, avgWindow, paint)
                    drawTriplePipe(canvas, xPos, paint)
                }

                Effect.AVERAGE_WEIGHTED_FOR_LAST, Effect.ENDLESS_AVERAGE_WEIGHTED_FOR_LAST -> {
                    // White lead is bright, Red tail fades almost instantly
                    canvas.drawColor(Color.BLACK)
                    drawWeightedForLastTrail(canvas, xPos, avgWindow / 2, paint)
                    drawTriplePipe(canvas, xPos, paint)
                }

                Effect.AVERAGE_WEIGHTED_FOR_LIGHT, Effect.ENDLESS_AVERAGE_WEIGHTED_FOR_LIGHT -> {
                    // White lead leaves trail, Red tail lingers longer than Black
                    canvas.drawColor(Color.BLACK)
                    drawWeightedForLightTrail(canvas, xPos, lightWindow, paint)
                    drawTriplePipe(canvas, xPos, paint)
                }

                Effect.ADD, Effect.ENDLESS_ADD -> {
                    // Accumulation with intensity
                    canvas.drawColor(Color.BLACK)
                    drawAddTrail(canvas, xPos, avgWindow, paint)
                    drawTriplePipe(canvas, xPos, paint)
                }

                Effect.MOTION_EXTRACTION, Effect.ENDLESS_MOTION_EXTRACTION -> {
                    // Only edges/movement visible
                    canvas.drawColor(Color.BLACK)
                    drawMotionExtract(canvas, xPos, paint)
                }

                Effect.HDR -> {
                    // Red tail becomes neon-bright, reveals shadow detail
                    canvas.drawColor(Color.BLACK)
                    drawHDREffect(canvas, xPos, paint)
                    drawTriplePipe(canvas, xPos, paint, isHDR = true)
                }

                Effect.DIFFERENCE, Effect.ENDLESS_DIFFERENCE -> {
                    // Difference: Only leading/trailing edges of White/Red shimmer
                    canvas.drawColor(Color.BLACK)
                    drawDifferenceEffect(canvas, xPos, paint)
                    drawTriplePipe(canvas, xPos, paint)
                }

                Effect.MEDIAN_STACK, Effect.ENDLESS_MEDIAN_STACK -> {
                    // Median - cleaner averaging
                    canvas.drawColor(Color.BLACK)
                    drawMedianEffect(canvas, xPos, paint)
                    drawTriplePipe(canvas, xPos, paint, alpha = 200)
                }

                Effect.TRANSITION -> {
                    // Cross-fade transition
                    canvas.drawColor(Color.BLACK)
                    drawTransitionEffect(canvas, xPos, i, paint)
                    drawTriplePipe(canvas, xPos, paint)
                }

                Effect.SUPER_RESOLUTION -> {
                    // Super Resolution: show multiple slightly offset pipes converging to sharp lines
                    // Represents sub-pixel alignment of multiple frames into a crisper result
                    canvas.drawColor(Color.BLACK)
                    drawSuperResolutionEffect(canvas, xPos, i, paint)
                }

                else -> {
                    canvas.drawColor(Color.BLACK)
                    drawTriplePipe(canvas, xPos, paint, alpha = 200)
                }
            }

            animation.addFrame(BitmapDrawable(context.resources, bitmap), frameDuration)
        }
        return animation
    }

    private fun drawTriplePipe(
        canvas: Canvas, 
        x: Float, 
        paint: Paint, 
        alpha: Int = 255,
        isHDR: Boolean = false
    ) {
        val top = height * 0.20f
        val bottom = height * 0.80f
        val pw = 12f // Pipe Width
        
        // RED (Tail)
        paint.shader = null
        paint.color = if (isHDR) Color.parseColor("#FF4444") else Color.RED
        paint.alpha = alpha
        canvas.drawRect(x, top, x + pw, bottom, paint)
        
        // BLACK (Middle)
        paint.color = Color.BLACK
        paint.alpha = alpha
        canvas.drawRect(x + pw, top, x + (pw * 2), bottom, paint)
        // Add thin gray outline to make black visible on black
        paint.style = Paint.Style.STROKE
        paint.color = Color.GRAY
        paint.strokeWidth = 1f
        canvas.drawRect(x + pw, top, x + (pw * 2), bottom, paint)
        paint.style = Paint.Style.FILL
        
        // WHITE (Lead)
        paint.color = Color.WHITE
        paint.alpha = alpha
        canvas.drawRect(x + (pw * 2), top, x + (pw * 3), bottom, paint)
    }


    /**
     * Lightest Pixels: White (lead) and Red (tail) leave solid trails
     * Black leaves nothing (already black on black)
     */
    private fun drawLightestTrail(canvas: Canvas, xPos: Float, paint: Paint) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        
        val top = height * verticalPadding
        val bottom = height * (1 - verticalPadding)
        
        // White lead pipe leaves full trail
        paint.color = Color.WHITE
        paint.alpha = 255
        val whiteStart = xPos + (pipeWidth * 2)
        canvas.drawRect(0f, top, whiteStart + pipeWidth, bottom, paint)
        
        // Red tail leaves dimmed trail
        paint.color = Color.RED
        paint.alpha = 180
        canvas.drawRect(0f, top, xPos + pipeWidth, bottom, paint)
        
        // Black middle leaves NO trail (darkest on black background)
    }


    /**
     * Darkest Pixels: Pre-fill with GRAY background
     * Black segment "carves" a black path through the gray
     */
    private fun drawDarkestTrail(canvas: Canvas, xPos: Float, paint: Paint) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        
        val top = height * verticalPadding
        val bottom = height * (1 - verticalPadding)
        
        // Background: GRAY so black trail is visible
        canvas.drawColor(Color.GRAY)
        
        // Black segment carves black path (darkest wins)
        paint.color = Color.BLACK
        val blackEnd = xPos + (pipeWidth * 2)
        canvas.drawRect(0f, top, blackEnd, bottom, paint)
    }


    /**
     * Standard Average: All three colors merge into pinkish-grey
     */
    private fun drawAverageTrail(canvas: Canvas, xPos: Float, window: Int, paint: Paint) {
        val tailWidth = (window.toFloat() / framesCount) * width
        val startX = (xPos - tailWidth).coerceAtLeast(0f)
        
        val top = height * verticalPadding
        val bottom = height * (1 - verticalPadding)
        
        // Pinkish-grey gradient representing merged colors
        val colors = intArrayOf(
            Color.TRANSPARENT,
            Color.parseColor("#CC8888"), // Pinkish
            Color.WHITE
        )

        val gradient = LinearGradient(
            startX, 0f, xPos, 0f,
            colors,
            null,
            Shader.TileMode.CLAMP
        )
        
        paint.shader = gradient
        paint.alpha = 255
        paint.style = Paint.Style.FILL
        canvas.drawRect(startX, top, xPos, bottom, paint)
        paint.shader = null
    }


    /**
     * Weighted for Last: White lead is bright, Red tail fades almost instantly
     * Short, sharp trail prioritizing current frame
     */
    private fun drawWeightedForLastTrail(canvas: Canvas, xPos: Float, window: Int, paint: Paint) {
        val tailWidth = (window.toFloat() / framesCount) * width
        val startX = (xPos - tailWidth).coerceAtLeast(0f)
        
        val top = height * verticalPadding
        val bottom = height * (1 - verticalPadding)
        
        // Fast fade - transparent to white quickly
        val colors = intArrayOf(
            Color.TRANSPARENT,
            Color.TRANSPARENT,
            Color.WHITE
        )

        val gradient = LinearGradient(
            startX, 0f, xPos, 0f,
            colors,
            floatArrayOf(0f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
        
        paint.shader = gradient
        paint.alpha = 255
        paint.style = Paint.Style.FILL
        canvas.drawRect(startX, top, xPos, bottom, paint)
        paint.shader = null
    }


    /**
     * Weighted for Light: White and Red pipes leave long trails; Black leaves NONE
     * This demonstrates "weighted for light" - highlights persist, shadows ignored
     */
    private fun drawWeightedForLightTrail(canvas: Canvas, xPos: Float, window: Int, paint: Paint) {
        val tailWidth = (window.toFloat() / framesCount) * width
        val startX = (xPos - tailWidth).coerceAtLeast(0f)
        
        val top = height * verticalPadding
        val bottom = height * (1 - verticalPadding)
        
        paint.shader = null
        paint.style = Paint.Style.FILL
        
        // White lead pipe leaves LONG solid trail (highlights persist)
        paint.color = Color.WHITE
        paint.alpha = 200
        val whiteStart = xPos + (pipeWidth * 2)
        canvas.drawRect(startX, top, whiteStart + pipeWidth, bottom, paint)
        
        // Red tail pipe leaves LONG dimmed trail (color lingers)
        paint.color = Color.RED
        paint.alpha = 150
        canvas.drawRect(startX, top, xPos + pipeWidth, bottom, paint)
        
        // Black middle pipe leaves NO TRAIL (shadows are ignored in "weighted for light")
    }


    /**
     * Add: Accumulation effect with glowing intensity
     */
    private fun drawAddTrail(canvas: Canvas, xPos: Float, window: Int, paint: Paint) {
        val tailWidth = (window.toFloat() / framesCount) * width
        val startX = (xPos - tailWidth).coerceAtLeast(0f)
        
        val top = height * verticalPadding
        val bottom = height * (1 - verticalPadding)
        
        // Build-up gradient from dark red to white
        val colors = intArrayOf(
            Color.parseColor("#440000"), // Dark red
            Color.parseColor("#CC4444"), // Medium red
            Color.parseColor("#FFAAAA"), // Light red
            Color.WHITE
        )

        val gradient = LinearGradient(
            startX, 0f, xPos, 0f,
            colors,
            floatArrayOf(0f, 0.5f, 0.8f, 1f),
            Shader.TileMode.CLAMP
        )
        
        paint.shader = gradient
        paint.alpha = 255
        paint.style = Paint.Style.FILL
        canvas.drawRect(startX, top, xPos, bottom, paint)
        paint.shader = null
    }


    /**
     * Motion Extraction: Draw only the shimmering outlines of White/Red pipes
     * Black pipe is invisible (no motion extracted from shadows)
     */
    private fun drawMotionExtract(canvas: Canvas, xPos: Float, paint: Paint) {
        val top = height * verticalPadding
        val bottom = height * (1 - verticalPadding)
        
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        
        // White pipe (Lead) - shimmering outline
        paint.color = Color.WHITE
        paint.alpha = 220
        // White pipe is at xPos + (pipeWidth * 2) to xPos + (pipeWidth * 3)
        val whiteLeft = xPos + (pipeWidth * 2)
        val whiteRight = xPos + (pipeWidth * 3)
        canvas.drawRect(whiteLeft - 1, top - 1, whiteRight + 1, bottom + 1, paint)
        
        // Inner shimmer of White pipe
        paint.alpha = 120
        canvas.drawRect(whiteLeft + 1, top + 1, whiteRight - 1, bottom - 1, paint)
        
        // Red pipe (Tail) - shimmering outline
        paint.color = Color.RED
        paint.alpha = 180
        // Red pipe is at xPos to xPos + pipeWidth
        canvas.drawRect(xPos - 1, top - 1, xPos + pipeWidth + 1, bottom + 1, paint)
        
        // Inner shimmer of Red pipe
        paint.alpha = 100
        canvas.drawRect(xPos + 1, top + 1, xPos + pipeWidth - 1, bottom - 1, paint)
        
        // Black pipe (Middle) - NO OUTLINE (shadows don't shimmer in motion extract)
        
        paint.style = Paint.Style.FILL
    }


    /**
     * HDR: Reveals "hidden" shadow detail behind cursor
     * Red tail becomes neon-bright
     */
    private fun drawHDREffect(canvas: Canvas, xPos: Float, paint: Paint) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        
        val top = height * verticalPadding
        val bottom = height * (1 - verticalPadding)
        
        // Background "hidden detail" pattern
        val revealWidth = xPos + cursorWidth
        
        // Subtle vertical stripes representing shadow detail
        for (j in 0 until 6) {
            val stripeX = revealWidth * (j / 6f)
            val nextStripeX = revealWidth * ((j + 1) / 6f)
            val brightness = 20 + (j * 8)
            val colorHex = brightness.toString(16).padStart(2, '0')
            paint.color = Color.parseColor("#${colorHex}2222")
            canvas.drawRect(stripeX, top, nextStripeX, bottom, paint)
        }
    }


    /**
     * Difference: Only leading/trailing edges of White/Red shimmer
     * Background is black.
     */
    private fun drawDifferenceEffect(canvas: Canvas, xPos: Float, paint: Paint) {
        val top = height * verticalPadding
        val bottom = height * (1 - verticalPadding)

        // Black background
        canvas.drawColor(Color.BLACK)

        // Leading edge of WHITE pipe shimmers (White is Leading pipe)
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.alpha = 200
        // White pipe starts at x + (pipeWidth * 2)
        canvas.drawRect(xPos + (pipeWidth * 2) - 2, top - 2, xPos + (pipeWidth * 2) + 2, bottom + 2, paint)

        // Trailing edge of RED pipe shimmers (Red is Trailing pipe)
        paint.color = Color.RED
        paint.alpha = 150
        canvas.drawRect(xPos - 2, top - 2, xPos + pipeWidth + 2, bottom + 2, paint)

        paint.style = Paint.Style.FILL
    }


    /**
     * Median Stack: Clean averaging effect
     */
    private fun drawMedianEffect(canvas: Canvas, xPos: Float, paint: Paint) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        
        val top = height * verticalPadding
        val bottom = height * (1 - verticalPadding)
        
        // Smooth composite trail
        paint.color = Color.parseColor("#999999")
        paint.alpha = 120
        val fadeStart = (xPos * 0.6f).coerceAtLeast(0f)
        canvas.drawRect(fadeStart, top, xPos, bottom, paint)
    }


    /**
     * Transition: Cross-fade progress indicator
     */
    private fun drawTransitionEffect(canvas: Canvas, xPos: Float, frameIndex: Int, paint: Paint) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        
        val top = height * verticalPadding
        val bottom = height * (1 - verticalPadding)
        
        // Progress indicator showing transition phase
        val progress = frameIndex.toFloat() / framesCount
        
        // Wipe transition from left to right
        val colors = intArrayOf(Color.WHITE, Color.TRANSPARENT)
        val gradient = LinearGradient(
            0f, 0f, width.toFloat(), 0f,
            colors,
            floatArrayOf(progress, (progress + 0.15f).coerceAtMost(1f)),
            Shader.TileMode.CLAMP
        )
        
        paint.shader = gradient
        paint.alpha = 100
        canvas.drawRect(0f, top, width.toFloat(), bottom, paint)
        paint.shader = null
    }

    /**
     * Super Resolution: Show multiple slightly offset "ghost" pipes converging
     * into a sharper, crisper result — representing sub-pixel alignment of
     * multiple frames into a higher-detail output.
     */
    private fun drawSuperResolutionEffect(canvas: Canvas, xPos: Float, frameIndex: Int, paint: Paint) {
        paint.shader = null
        paint.style = Paint.Style.FILL

        val top = height * verticalPadding
        val bottom = height * (1 - verticalPadding)
        val pw = pipeWidth

        // Phase 0 (first 60%): show multiple ghost pipes offset by sub-pixel amounts
        // Phase 1 (last 40%): ghosts converge into a single sharp pipe
        val phase = (frameIndex.toFloat() / framesCount)
        val convergence = if (phase < 0.6f) 0f else (phase - 0.6f) / 0.4f

        // Draw 5 ghost copies of the triple-pipe, offset by sub-pixel amounts
        for (ghost in 0 until 5) {
            val ghostOffset = (ghost - 2) * 1.5f * (1f - convergence)
            val ghostAlpha = (60 + convergence * 60).toInt().coerceAtMost(180)

            val gx = xPos + ghostOffset

            paint.color = Color.RED
            paint.alpha = ghostAlpha
            canvas.drawRect(gx, top, gx + pw, bottom, paint)

            paint.color = Color.WHITE
            paint.alpha = ghostAlpha
            canvas.drawRect(gx + pw * 2, top, gx + pw * 3, bottom, paint)
        }

        val sharpAlpha = (convergence * 255).toInt().coerceAtLeast(80)
        drawTriplePipe(canvas, xPos, paint, alpha = sharpAlpha)

        if (convergence > 0.5f) {
            paint.color = Color.parseColor("#00FFAA")
            paint.alpha = (convergence * 200).toInt()
            paint.strokeWidth = 1f
            paint.style = Paint.Style.STROKE
            canvas.drawLine(xPos + pw * 1.5f, 0f, xPos + pw * 1.5f, height.toFloat(), paint)
            paint.style = Paint.Style.FILL
        }
    }
}
