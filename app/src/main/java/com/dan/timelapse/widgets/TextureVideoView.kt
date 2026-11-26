package com.dan.timelapse.widgets

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView
import android.view.View

class TextureVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextureView(context, attrs, defStyleAttr), TextureView.SurfaceTextureListener,
    ZoomableFrameLayout.ContentBoundsProvider {

    private var mediaPlayer: MediaPlayer? = null
    private var mSurface: Surface? = null
    private var videoUri: Uri? = null
    private var isPrepared = false
    private var onPreparedListener: MediaPlayer.OnPreparedListener? = null
    private var onCompletionListener: MediaPlayer.OnCompletionListener? = null
    private var onErrorListener: MediaPlayer.OnErrorListener? = null

    private var videoWidth = 0
    private var videoHeight = 0
    private var currentRotation = 0
    private var currentAspectRatio: Float? = null
    
    // Listener for size changes to re-apply matrix
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (isPrepared) {
            applyMatrix()
        }
        (parent as? ZoomableFrameLayout)?.onContentBoundsChanged()
    }

    /**
     * This view letterboxes by physically shrinking itself to the visible video box
     * (see [onMeasure]), so its own bounds ARE the media box. Report them in the
     * container's coordinate space for the zoom container's bounds guard.
     */
    override fun contentBoundsInContainer(dst: RectF) {
        dst.set(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
        if (dst.width() <= 0f || dst.height() <= 0f) {
            val container = parent as? View
            dst.set(0f, 0f, (container?.width ?: 0).toFloat(), (container?.height ?: 0).toFloat())
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Get the available size from the parent FrameLayout
        val parentWidth = MeasureSpec.getSize(widthMeasureSpec)
        val parentHeight = MeasureSpec.getSize(heightMeasureSpec)

        if (videoWidth > 0 && videoHeight > 0) {
            // 1. Calculate the visually oriented dimensions
            val isRotated = currentRotation == 90 || currentRotation == 270
            val orientedW = if (isRotated) videoHeight else videoWidth
            val orientedH = if (isRotated) videoWidth else videoHeight

            // 2. What ratio do we WANT to show?
            val targetRatio = currentAspectRatio ?: (orientedW.toFloat() / orientedH.toFloat())
            
            // 3. What ratio is the physical screen space?
            val screenRatio = parentWidth.toFloat() / parentHeight.toFloat()

            var finalWidth = parentWidth
            var finalHeight = parentHeight

            // 4. Shrink the TextureView physically to create the "crop box"
            // This forces the black bars effect outside the video view!
            if (targetRatio > screenRatio) {
                // Video is wider than screen -> Fit to width, shrink height
                finalHeight = (parentWidth / targetRatio).toInt()
            } else {
                // Video is taller than screen -> Fit to height, shrink width
                finalWidth = (parentHeight * targetRatio).toInt()
            }

            setMeasuredDimension(finalWidth, finalHeight)
        } else {
            // Default behavior before video loads
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    init {
        surfaceTextureListener = this
        
        // Ensure this view centers itself in its parent FrameLayout
        // so that when it shrinks to crop, the black bars are perfectly even.
        post {
            val params = layoutParams
            if (params is android.widget.FrameLayout.LayoutParams) {
                params.gravity = android.view.Gravity.CENTER
                layoutParams = params
            }
        }
    }

    fun setVideoURI(uri: Uri?) {
        videoUri = uri
        if (isAvailable) {
            openVideo()
        }
    }

    private fun openVideo() {
        val uri = videoUri ?: return
        mSurface?.release()
        mSurface = Surface(surfaceTexture)

        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, uri)
                setSurface(mSurface!!)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                setOnPreparedListener { mp ->
                    isPrepared = true
                    this@TextureVideoView.videoWidth = mp.videoWidth
                    this@TextureVideoView.videoHeight = mp.videoHeight
                    applyMatrix()
                    
                    // Force the view to calculate its crop box immediately!
                    requestLayout()
                    
                    onPreparedListener?.onPrepared(mp)
                }
                setOnCompletionListener { mp ->
                    onCompletionListener?.onCompletion(mp)
                }
                setOnErrorListener { mp, what, extra ->
                    onErrorListener?.onError(mp, what, extra) ?: false
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Apply rotation using Matrix transformation for proper video rotation
     */
    fun setVideoRotation(degrees: Int) {
        currentRotation = degrees
        requestLayout() 
        if (isPrepared) applyMatrix()
        post { applyMatrix() }
    }
    
    /**
     * Set aspect ratio for center-crop mode (e.g., 1.0f for 1:1, 1.77f for 16:9)
     * Pass null to disable cropping (show full video)
     */
    fun setAspectRatio(ratio: Float?) {
        currentAspectRatio = ratio
        requestLayout()
        if (isPrepared) applyMatrix()
        post { applyMatrix() }
    }

    private fun applyMatrix() {
        if (videoWidth == 0 || videoHeight == 0 || width == 0 || height == 0) return


        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val vidW = videoWidth.toFloat()
        val vidH = videoHeight.toFloat()
        
        val matrix = Matrix()
        
        // 1. Move pivot to center of the view
        matrix.postTranslate(-viewW / 2f, -viewH / 2f)
        
        // 2. UN-STRETCH: TextureView aggressively stretches video to fill the view bounds.
        // We MUST scale it back to its true physical proportions before rotating.
        matrix.postScale(vidW / viewW, vidH / viewH)
        
        // 3. Apply user rotation
        matrix.postRotate(currentRotation.toFloat())
        
        // 4. Calculate bounding box of the rotated video
        val isRotated = currentRotation == 90 || currentRotation == 270
        val orientedW = if (isRotated) vidH else vidW
        val orientedH = if (isRotated) vidW else vidH
        
        // 5. Calculate target aspect ratio crop box
        val targetRatio = currentAspectRatio ?: (orientedW / orientedH)
        val viewRatio = viewW / viewH
        
        val boxW: Float
        val boxH: Float
        
        // FIX: The previous math was flipped, causing it to overflow the screen.
        // This guarantees the video's bounding box always fits perfectly INSIDE the screen bounds.
        if (targetRatio > viewRatio) {
            // Target is wider than the view -> fit to width, scale height
            boxW = viewW
            boxH = viewW / targetRatio
        } else {
            // Target is taller than the view -> fit to height, scale width
            boxH = viewH
            boxW = viewH * targetRatio
        }
        
        // 6. Scale to FILL the crop box exactly
        val scaleX = boxW / orientedW
        val scaleY = boxH / orientedH
        val scale = maxOf(scaleX, scaleY)
        
        matrix.postScale(scale, scale)
        
        // 7. Move back to the exact center
        matrix.postTranslate(viewW / 2f, viewH / 2f)
        
        setTransform(matrix)
    }
    
    /**
     * Calculate crop rectangle to achieve target aspect ratio.
     * Maintains maximum possible area while matching target ratio.
     */
    private fun calculateCropRect(sourceRect: RectF, targetRatio: Float): RectF {
        val srcWidth = sourceRect.width()
        val srcHeight = sourceRect.height()
        val srcRatio = srcWidth / srcHeight
        
        return if (srcRatio > targetRatio) {
            // Source is wider than target - crop width
            val newWidth = srcHeight * targetRatio
            val xOffset = (srcWidth - newWidth) / 2
            RectF(
                sourceRect.left + xOffset,
                sourceRect.top,
                sourceRect.right - xOffset,
                sourceRect.bottom
            )
        } else {
            // Source is taller than target - crop height
            val newHeight = srcWidth / targetRatio
            val yOffset = (srcHeight - newHeight) / 2
            RectF(
                sourceRect.left,
                sourceRect.top + yOffset,
                sourceRect.right,
                sourceRect.bottom - yOffset
            )
        }
    }

    fun start() {
        try {
            if (isPrepared) {
                mediaPlayer?.start()
            }
        } catch (e: IllegalStateException) {
            android.util.Log.w("TextureVideoView", "Ignored start() in invalid state")
            handleDeadSystemException(e)
        } catch (e: RuntimeException) {
            android.util.Log.e("TextureVideoView", "DeadSystemException caught! MediaPlayer is a zombie.", e)
            handleDeadSystemException(e)
        }
    }

    fun pause() {
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
            }
        } catch (e: IllegalStateException) {
            android.util.Log.w("TextureVideoView", "Ignored pause() in invalid state")
        } catch (e: RuntimeException) {
            android.util.Log.e("TextureVideoView", "DeadSystemException caught on pause!", e)
            handleDeadSystemException(e)
        }
    }

    fun stop() {
        try {
            mediaPlayer?.stop()
        } catch (e: IllegalStateException) {
            android.util.Log.w("TextureVideoView", "Ignored stop() in invalid state")
        } catch (e: RuntimeException) {
            android.util.Log.e("TextureVideoView", "DeadSystemException caught on stop!", e)
            handleDeadSystemException(e)
        }
    }

    fun stopPlayback() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            isPrepared = false
        } catch (e: Exception) {
            android.util.Log.w("TextureVideoView", "Ignored stopPlayback() error", e)
        }
    }

    fun seekTo(msec: Int) {
        try {
            mediaPlayer?.seekTo(msec)
        } catch (e: IllegalStateException) {
            android.util.Log.w("TextureVideoView", "Ignored seekTo() in invalid state")
        } catch (e: RuntimeException) {
            android.util.Log.e("TextureVideoView", "DeadSystemException caught on seekTo!", e)
            handleDeadSystemException(e)
        }
    }

    private fun handleDeadSystemException(e: RuntimeException) {
        // The media server died. Release the zombie player.
        try {
            mediaPlayer?.release()
        } catch (ignored: Exception) {}
        mediaPlayer = null
        isPrepared = false
        android.util.Log.i("TextureVideoView", "Zombie player destroyed. Re-initializing...")
        
        // Attempt to re-initialize if surface is available
        if (isAvailable) {
            post { openVideo() }
        }
    }

    /**
     * Detach the hardware surface from MediaPlayer to prevent deadlock.
     * Call this BEFORE stopping/pausing when transitioning to image view.
     */
    fun detachSurface() {
        try {
            mediaPlayer?.setSurface(null)
        } catch (e: Exception) {
            android.util.Log.w("TextureVideoView", "Failed to detach surface", e)
        }
    }

    /**
     * Safe teardown that forces MediaPlayer operations onto the Main Looper.
     * Prevents cross-thread deadlocks when releasing the player.
     * If already on the Main thread, executes synchronously to guarantee
     * the player is released before the caller continues.
     */
    fun safeTeardown() {
        val teardown = Runnable {
            try {
                mediaPlayer?.setSurface(null)
                if (mediaPlayer?.isPlaying == true) {
                    mediaPlayer?.stop()
                }
                mediaPlayer?.release()
                mediaPlayer = null
                isPrepared = false
            } catch (e: Exception) {
                android.util.Log.e("TextureVideoView", "Safe teardown ignored exception", e)
            }
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            teardown.run()
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post(teardown)
        }
    }

    val isPlaying: Boolean
        get() = try {
            mediaPlayer?.isPlaying ?: false
        } catch (e: IllegalStateException) {
            false
        } catch (e: RuntimeException) {
            android.util.Log.e("TextureVideoView", "DeadSystemException on isPlaying", e)
            false
        }

    val duration: Int
        get() = mediaPlayer?.duration ?: 0

    val currentPosition: Int
        get() = mediaPlayer?.currentPosition ?: 0

    fun setOnPreparedListener(listener: MediaPlayer.OnPreparedListener?) {
        onPreparedListener = listener
    }

    fun setOnCompletionListener(listener: MediaPlayer.OnCompletionListener?) {
        onCompletionListener = listener
    }

    fun setOnErrorListener(listener: MediaPlayer.OnErrorListener?) {
        onErrorListener = listener
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        openVideo()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        applyMatrix()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.setSurface(null)
            mediaPlayer?.release()
            mediaPlayer = null
            mSurface?.release()
            mSurface = null
            isPrepared = false
        } catch (e: Exception) { }
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
}
