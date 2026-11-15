package com.dan.timelapse.framesinput

import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.dan.timelapse.MainViewModel
import com.dan.timelapse.utils.UriFile
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlinx.coroutines.yield

class ImagesFramesInput(
    private val uris: List<UriFile>,
    private val model: MainViewModel,
    private val skipProbing: Boolean = false,
    private val onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }
) : FramesInput() {

    companion object {
        suspend fun fromFolder(
            folderUriFile: UriFile, 
            model: MainViewModel, 
            onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }
        ): ImagesFramesInput {
            val context = folderUriFile.context
            
            onProgress(0, 0, "Listing files...")
            val documentFile = DocumentFile.fromTreeUri(context, folderUriFile.uri)
                ?: throw Exception("Selected item is not a valid folder")

            val allFiles = documentFile.listFiles()
            yield() // Allow cancellation

            onProgress(0, allFiles.size, "Filtering images...")
            // Extract, filter for images. Prefer EXIF DateTimeOriginal, fall back to file lastModified.
            // This ensures images captured on a real camera are sorted by capture time even if
            // lastModified has been rewritten by a file transfer or backup tool.
            val imageFiles = mutableListOf<Pair<DocumentFile, Long>>()
            
            allFiles.forEachIndexed { index, df ->
                if (index % 20 == 0) {
                    onProgress(index, allFiles.size, "Filtering ($index/${allFiles.size})...")
                    yield()
                }
                if (df.isFile && df.type?.startsWith("image/") == true) {
                    val sortKey = try {
                        context.contentResolver.openInputStream(df.uri)?.use { stream ->
                            val exif = androidx.exifinterface.media.ExifInterface(stream)
                            val dt = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL)
                                ?: exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME)
                            if (!dt.isNullOrEmpty()) {
                                try {
                                    val f = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
                                    f.parse(dt)?.time ?: df.lastModified()
                                } catch (_: Exception) {
                                    df.lastModified()
                                }
                            } else {
                                df.lastModified()
                            }
                        } ?: df.lastModified()
                    } catch (_: Exception) {
                        df.lastModified()
                    }
                    imageFiles.add(Pair(df, sortKey))
                }
            }
            
            val sortedFiles = imageFiles.sortedBy { it.second }.map { it.first }

if (sortedFiles.isEmpty()) throw Exception("No images found in folder")
            yield()
            
            val total = sortedFiles.size
            val uriFiles = mutableListOf<UriFile>()
            
            for (i in sortedFiles.indices) {
                if (i % 10 == 0) {
                    onProgress(i, total, "Preparing URIs ($i/$total)...")
                    yield()
                }
                UriFile.fromSingleUri(context, sortedFiles[i].uri)?.let { uriFiles.add(it) }
            }
            
             if (uriFiles.isEmpty()) {
                throw Exception("No valid images found in folder")
            }
            
            return ImagesFramesInput(uriFiles, model)
         }
     }

    override val fps: Int get() = 30 // Default for image sequences
    override val name: String get() = if (uris.isNotEmpty()) uris[0].name else "ImageSequence"
    override val videoUri: Uri get() = if (uris.isNotEmpty()) uris[0].uri else Uri.EMPTY
    override val size: Int get() = uris.size
    
    // Baseline dimensions - will be set during init
    private var baselineWidth: Int = 1920
    private var baselineHeight: Int = 1080
    
    // Instruction 3: Fast resolution probing for varying resolutions
    var hasVaryingResolutions: Boolean = false
        private set

    // Initialize: Find the first valid frame to establish baseline resolution AND check for varying resolutions
    init {
        if (!skipProbing) {
            probeResolutions()
        }
    }

    /**
     * Probes the first batch of images to determine baseline resolution and detect variations.
     * We limit this to the first 100 images to prevent hanging on huge folders.
     */
    private fun probeResolutions() {
        try {
            val probeLimit = minOf(uris.size, 100)
            
            // First pass: use fast probing with inJustDecodeBounds
            for (i in 0 until probeLimit) {
                if (i % 10 == 0) {
                    onProgress(i, probeLimit, "Probing resolution ($i/$probeLimit)...")
                }
                try {
                    val uriFile = uris[i]
                    val inputStream = uriFile.context.contentResolver.openInputStream(uriFile.uri)
                    val options = android.graphics.BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    android.graphics.BitmapFactory.decodeStream(inputStream, null, options)
                    inputStream?.close()
                    
                    if (i == 0) {
                        baselineWidth = options.outWidth
                        baselineHeight = options.outHeight
                    } else if (options.outWidth != baselineWidth || options.outHeight != baselineHeight) {
                        hasVaryingResolutions = true
                        android.util.Log.d("ImagesFramesInput", "Varying resolution detected within first $probeLimit frames")
                        break // Early exit - we found varying resolutions
                    }
                } catch (e: Exception) {
                    // Continue probing other images
                }
            }
            
            // Second pass: verify baseline by loading actual first frame
            val verifyLimit = minOf(uris.size, 10)
            for (i in 0 until verifyLimit) {
                if (i % 5 == 0) {
                    onProgress(probeLimit + i, probeLimit + verifyLimit, "Verifying baseline ($i/$verifyLimit)...")
                }
                val probeFrame = getFrameRaw(i)
                if (probeFrame != null && !probeFrame.empty() && probeFrame.cols() > 0 && probeFrame.rows() > 0) {
                    baselineWidth = probeFrame.cols()
                    baselineHeight = probeFrame.rows()
                    probeFrame.release()
                    break
                }
                probeFrame?.release()
            }
        } catch (e: Exception) {
            android.util.Log.e("ImagesFramesInput", "Init failed, using defaults", e)
        }

        // Ensure we never have 0 dimensions
        if (baselineWidth <= 0) baselineWidth = 1920
        if (baselineHeight <= 0) baselineHeight = 1080
    }

    override var width: Int = baselineWidth
    override var height: Int = baselineHeight

    override fun firstFrame(): Mat = getFrame(0) ?: Mat()

    // Private helper that loads image without resizing (used for dimension probing)
    private fun getFrameRaw(index: Int): Mat? {
        if (index < 0 || index >= uris.size) return null
        
        var matRGBA: Mat? = null
        var matRGB: Mat? = null
        var bitmap: android.graphics.Bitmap? = null
        var bitmapRaw: android.graphics.Bitmap? = null
        
        return try {
            val uriFile = uris[index]
            val inputStream = uriFile.context.contentResolver.openInputStream(uriFile.uri)
            
            val options = android.graphics.BitmapFactory.Options().apply {
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            }
            bitmapRaw = android.graphics.BitmapFactory.decodeStream(inputStream, null, options)
            inputStream?.close()
            
            if (bitmapRaw == null) return null
            
            // FIX 2: Handle EXIF orientation to prevent sideways image sequences
            val exifStream = uriFile.context.contentResolver.openInputStream(uriFile.uri)
            val exif = exifStream?.let { androidx.exifinterface.media.ExifInterface(it) }
            exifStream?.close()
            
            val orientation = exif?.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)
            val rotationDegrees = when (orientation) {
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            
            bitmap = if (rotationDegrees != 0f) {
                val matrix = android.graphics.Matrix().apply { postRotate(rotationDegrees) }
                val rotated = android.graphics.Bitmap.createBitmap(bitmapRaw, 0, 0, bitmapRaw.width, bitmapRaw.height, matrix, true)
                bitmapRaw.recycle()
                rotated
            } else {
                bitmapRaw
            }

            matRGBA = Mat()
            org.opencv.android.Utils.bitmapToMat(bitmap, matRGBA)
            bitmap.recycle()
            bitmap = null

            matRGB = Mat()
            com.dan.timelapse.images.ImageTools.dropAlphaChannel(matRGBA, matRGB)
            
            val result = matRGB
            matRGB = null 
            result
        } catch (e: Exception) {
            null
        } finally {
            matRGBA?.release()
            matRGB?.release()
            bitmap?.recycle()
            bitmapRaw?.recycle()
        }
    }

    override fun getFrame(index: Int): Mat? {
        val rawFrame = getFrameRaw(index) ?: return null
        
        // 1. Drop corrupted frames safely to prevent downstream math errors
        if (rawFrame.empty() || rawFrame.cols() <= 0 || rawFrame.rows() <= 0) {
            rawFrame.release()
            return null
        }

        // 2. Check if Fill mode is enabled
        val isFillMode = model.isZoomToFill.value ?: false
        
        // 3. If aspect ratios match and Fill is off, simple resize is fine
        val rawAspect = rawFrame.cols().toFloat() / rawFrame.rows().toFloat()
        val targetAspect = baselineWidth.toFloat() / baselineHeight.toFloat()
        
        // If raw matches baseline and Fill mode matches aspect ratio preference, just resize
        if (rawFrame.cols() == baselineWidth && rawFrame.rows() == baselineHeight) {
            return rawFrame
        }
        
        // 4. Implement Fill (Center Crop) or Fit (Letterbox/Pad)
        var resultMat: Mat? = null
        var tempMat: Mat? = null
        
        return try {
            if (isFillMode) {
                // FILL MODE: Scale so smallest dimension matches, crop excess
                // Calculate scale to fit width or height (whichever is larger)
                val scale = maxOf(
                    baselineWidth.toFloat() / rawFrame.cols(),
                    baselineHeight.toFloat() / rawFrame.rows()
                )
                val scaledW = (rawFrame.cols() * scale).toInt()
                val scaledH = (rawFrame.rows() * scale).toInt()
                
                tempMat = Mat()
                org.opencv.imgproc.Imgproc.resize(rawFrame, tempMat, 
                    org.opencv.core.Size(scaledW.toDouble(), scaledH.toDouble()))
                
                // Crop center to target dimensions
                val x = ((scaledW - baselineWidth) / 2).coerceAtLeast(0)
                val y = ((scaledH - baselineHeight) / 2).coerceAtLeast(0)
                val rect = org.opencv.core.Rect(x, y, 
                    baselineWidth.coerceAtMost(scaledW - x), 
                    baselineHeight.coerceAtMost(scaledH - y))
                resultMat = tempMat.submat(rect)
                tempMat.release()
                tempMat = null
            } else {
                // FIT MODE: Scale so largest dimension fits, pad with black
                // Calculate scale to fit width or height (whichever is smaller)
                val scale = minOf(
                    baselineWidth.toFloat() / rawFrame.cols(),
                    baselineHeight.toFloat() / rawFrame.rows()
                )
                val scaledW = (rawFrame.cols() * scale).toInt()
                val scaledH = (rawFrame.rows() * scale).toInt()
                
                tempMat = Mat()
                org.opencv.imgproc.Imgproc.resize(rawFrame, tempMat,
                    org.opencv.core.Size(scaledW.toDouble(), scaledH.toDouble()))
                
                // Create target sized mat with black padding
                resultMat = Mat(baselineHeight, baselineWidth, rawFrame.type(), 
                    org.opencv.core.Scalar(0.0, 0.0, 0.0))
                
                // Copy scaled image to center
                val x = ((baselineWidth - scaledW) / 2).coerceAtLeast(0)
                val y = ((baselineHeight - scaledH) / 2).coerceAtLeast(0)
                val roi = resultMat.submat(y, y + scaledH, x, x + scaledW)
                tempMat.copyTo(roi)
                roi.release()
                tempMat.release()
                tempMat = null
            }
            
            resultMat
        } catch (e: Exception) {
            android.util.Log.e("ImagesFramesInput", "Fill/Fit failed for frame $index", e)
            tempMat?.release()
            resultMat?.release()
            null
        } finally {
            rawFrame.release()
        }
    }

    override fun forEachFrame(start: Int, progress: (Int, Int) -> Unit, callback: (Int, Int, Mat) -> Boolean) {
        var counter = start
        val totalFramesToProcess = (size - start).coerceAtLeast(0)
        while (counter < size) {
            val frame = getFrame(counter) ?: break
            
            // FIX: Wrap in try/finally to guarantee memory cleanup even if callback throws
            try {
                progress(counter - start, totalFramesToProcess)
                val continueProcessing = callback(counter, size, frame)
                if (!continueProcessing) break
            } finally {
                frame.release() // Mandatory memory cleanup
            }
            
            counter++
        }
    }

    override fun release() {
        // No persistent mats to release
    }
}
