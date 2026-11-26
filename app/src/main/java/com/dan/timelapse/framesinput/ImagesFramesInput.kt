package com.dan.timelapse.framesinput

import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.dan.timelapse.MainViewModel
import com.dan.timelapse.utils.UriFile
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.Locale
import kotlinx.coroutines.yield

class ImagesFramesInput(
    private val uris: List<UriFile>,
    private val model: MainViewModel,
    private val skipProbing: Boolean = false,
    private val onProgress: (Int, Int, String) -> Unit = { _, _, _ -> },
    /** Tree URI of the source folder, when this sequence came from a folder picker. */
    private val treeUri: Uri? = null
) : FramesInput() {

    companion object {
        private const val TAG = "ImagesFramesInput"

        /** Above this many entries, skip the per-file EXIF open (see fromFolder). */
        private const val EXIF_PROBE_LIMIT = 300

        /** Containers OpenCV decodes with bit depth intact (everything else uses BitmapFactory). */
        private val WIDE_DEPTH_EXTENSIONS = setOf("tif", "tiff", "png")

        /**
         * How many consecutive undecodable frames end a sequence walk.
         *
         * One bad frame must not cancel a render (a single transient CvException in the
         * decoder aborted a whole 17-frame render before frame 0), but a source that has
         * genuinely stopped decoding should not be walked to its end either.
         */
        private const val MAX_CONSECUTIVE_DECODE_FAILURES = 8

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

            // Reading EXIF means opening every file over SAF. On a 1500-file folder that
            // took minutes (observed: >3 min and still filtering on a Pixel 4a), which
            // makes large folders unusable. Above the limit we sort by lastModified,
            // which for camera output is the capture time anyway.
            val probeExif = allFiles.size <= EXIF_PROBE_LIMIT
            if (!probeExif) {
                Log.i(TAG, "Folder has ${allFiles.size} entries — sorting by lastModified " +
                        "instead of opening every file for EXIF")
            }

            allFiles.forEachIndexed { index, df ->
                if (index % 20 == 0) {
                    onProgress(index, allFiles.size, "Filtering ($index/${allFiles.size})...")
                    yield()
                }
                val lowerName = df.name?.lowercase(Locale.US) ?: ""
                val isImage = df.type?.startsWith("image/") == true ||
                    lowerName.endsWith(".tif") || lowerName.endsWith(".tiff") ||
                    lowerName.endsWith(".png") || lowerName.endsWith(".jpg") ||
                    lowerName.endsWith(".jpeg")
                if (df.isFile && isImage) {
                    val sortKey = if (!probeExif) {
                        df.lastModified()
                    } else try {
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
            
            val sortedFiles = imageFiles.sortedWith(
                compareBy<Pair<DocumentFile, Long>> { it.second }
                    // lastModified is the copy time for a transferred folder, and equal
                    // keys fall back to provider order (not name order). Name order is
                    // capture order for camera output, so use it as the tie-break.
                    .thenComparator { a, b ->
                        com.dan.timelapse.utils.NaturalOrder.compare(
                            a.first.name ?: "",
                            b.first.name ?: ""
                        )
                    }
            ).map { it.first }

if (sortedFiles.isEmpty()) throw Exception("No images found in folder")
            yield()
            
            val total = sortedFiles.size
            val uriFiles = mutableListOf<UriFile>()
            
            for (i in sortedFiles.indices) {
                if (i % 200 == 0) {
                    onProgress(i, total, "Preparing URIs ($i/$total)...")
                    yield()
                }
                // Build the UriFile from the DocumentFile we already have. The old path
                // ran a content query per file, which on a 1500-file folder added
                // hundreds of round trips before the folder could open.
                val df = sortedFiles[i]
                val documentId = try {
                    DocumentsContract.getDocumentId(df.uri)
                } catch (e: Exception) {
                    continue
                }
                uriFiles.add(
                    UriFile(
                        context = context,
                        uri = df.uri,
                        name = df.name ?: "image_$i",
                        mime = df.type ?: "image/jpeg",
                        documentId = documentId
                    )
                )
            }
            
             if (uriFiles.isEmpty()) {
                throw Exception("No valid images found in folder")
            }
            
            // Keep the tree URI: it is the only URI with a persistable grant, so it is
            // what the session must store for a folder to be restorable.
            return ImagesFramesInput(uriFiles, model, treeUri = folderUriFile.uri)
         }
     }

    override val fps: Int get() = 30 // Default for image sequences
    override val name: String get() = if (uris.isNotEmpty()) uris[0].name else "ImageSequence"
    override val videoUri: Uri get() = if (uris.isNotEmpty()) uris[0].uri else Uri.EMPTY
    override val persistableUri: Uri? get() = treeUri ?: videoUri

    /** True when this sequence came from a folder (tree URI) picker. */
    val isFolder: Boolean get() = treeUri != null

    /**
     * Every child URI of a MULTI-SELECT sequence.
     *
     * persistableUri can only describe one URI (the tree, or the first image), so a
     * 50-photo selection used to restore as a single frame. A folder session returns
     * empty: its tree URI is the only persistable grant and re-listing the tree on
     * restore is the correct behaviour.
     */
    val childUris: List<Uri>
        get() = if (treeUri != null) emptyList() else uris.map { it.uri }

    /**
     * 16-bit fusion source for MFSR only.
     *
     * Returns CV_16U RGB when the file is genuinely 16-bit and already matches the
     * sequence baseline geometry. Mixed sizes fall back to the 8-bit [getFrame] path:
     * the engine's anchor/gate geometry must not differ between the two passes.
     */
    @Synchronized
    override fun getFrame16(index: Int): Mat? {
        if (index < 0 || index >= uris.size) return null
        val mat = decodeWideDepthFrame(uris[index], keepDepth = true) ?: return null
        if (mat.depth() != CvType.CV_16U || mat.channels() != 3 ||
            mat.cols() != baselineWidth || mat.rows() != baselineHeight) {
            mat.release()
            return null
        }
        return mat
    }

    /** True when at least one frame was decoded from a >8-bit container (TIFF/PNG). */
    var usedHighBitDepth: Boolean = false
        private set

    override val isHighBitDepth: Boolean get() = usedHighBitDepth

    /**
     * Decode a 16-bit / float TIFF or PNG frame to 8-bit BGR.
     *
     * Returns null for anything else, so the normal BitmapFactory path keeps handling
     * JPEG (and keeps its inSampleSize decoding). DNG is NOT supported: decoding camera
     * RAW needs LibRaw, which is not part of OpenCV's Android build.
     */
    @Synchronized
    private fun decodeWideDepthFrame(uriFile: UriFile, keepDepth: Boolean = false): Mat? {
        val lower = uriFile.name.lowercase(Locale.US)
        val extension = lower.substringAfterLast('.', "")
        if (extension !in WIDE_DEPTH_EXTENSIONS) return null

        var tmp: File? = null
        var raw: Mat? = null
        var rgb: Mat? = null
        return try {
            tmp = File.createTempFile("highdepth_", ".$extension", uriFile.context.cacheDir)
            uriFile.context.contentResolver.openInputStream(uriFile.uri)?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            } ?: return null

            raw = Imgcodecs.imread(tmp.absolutePath, Imgcodecs.IMREAD_UNCHANGED)
            if (raw == null || raw.empty()) {
                // imgcodecs could not read the container: fall through to BitmapFactory.
                // `raw` is released exactly once, by the finally block below.
                Log.w(TAG, "OpenCV returned an empty Mat for ${uriFile.name}")
                return null
            }

            val sourceDepth = raw.depth()
            when (sourceDepth) {
                CvType.CV_16U -> {
                    // keepDepth: hand the 16-bit samples to MFSR's fusion pass intact.
                    if (!keepDepth) raw.convertTo(raw, CvType.CV_8U, 255.0 / 65535.0)
                    usedHighBitDepth = true
                }
                CvType.CV_32F -> {
                    if (!keepDepth) raw.convertTo(raw, CvType.CV_8U, 255.0)
                    usedHighBitDepth = true
                }
                else -> Unit
            }

            // Imgcodecs.imread returns BGR. The engine (and BitmapFactory) work in RGB
            // and finalize() converts RGB->BGR before saving, so convert here or red
            // and blue come out swapped for every TIFF/PNG source.
            //
            // ONE conversion straight out of `raw`, into an explicitly allocated
            // destination: the old code built an intermediate 3-channel copy first,
            // which is ~144 MB of pointless peak memory per frame at 6000x4000 16-bit.
            //
            // (The "Bad number of channels ... 'dcn' is <garbage>" failures that used
            // to surface here were NOT caused by this call. Imgproc.cvtColor(src, dst,
            // code) was bound to a native entry point that expects an extra `dcn`
            // argument, so OpenCV read a stale register as the channel count. See the
            // arity note in opencv/.../imgproc/Imgproc.java.)
            val code = when (raw.channels()) {
                4 -> Imgproc.COLOR_BGRA2RGB
                3 -> Imgproc.COLOR_BGR2RGB
                1 -> Imgproc.COLOR_GRAY2RGB
                else -> {
                    // 2 channels (gray+alpha) has no single-step RGB conversion; let
                    // BitmapFactory try rather than failing with a channel mismatch.
                    Log.w(TAG, "Unsupported channel count ${raw.channels()} for ${uriFile.name}")
                    return null
                }
            }
            val dest = Mat(raw.rows(), raw.cols(), CvType.makeType(raw.depth(), 3))
            rgb = dest
            Imgproc.cvtColor(raw, dest, code)
            if (dest.empty()) {
                Log.w(TAG, "cvtColor produced an empty frame for ${uriFile.name}")
                return null
            }
            Log.i(TAG, "Decoded ${uriFile.name} via OpenCV (sourceDepth=$sourceDepth, " +
                    "16-bit=$usedHighBitDepth, ${dest.cols()}x${dest.rows()})")
            val result = dest
            rgb = null // ownership passes to the caller
            result
        } catch (e: Exception) {
            Log.w(TAG, "OpenCV decode failed for ${uriFile.name}", e)
            null
        } finally {
            rgb?.release()
            raw?.release()
            tmp?.delete()
        }
    }
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

    /**
     * Private helper that loads image without resizing (used for dimension probing).
     *
     * `@Synchronized`: this instance is driven by two threads at once — the preview
     * playback coroutine and the render pass — and both reach OpenCV's `imread` /
     * `cvtColor` / `dropAlphaChannelNative` through here. Neither holds a shared Mat,
     * but the decode itself is not re-entrant against a Mat header that the GC
     * finalizer can free underneath it, so the decode is serialised.
     */
    @Synchronized
    private fun getFrameRaw(index: Int, inSampleSize: Int = 1): Mat? {
        if (index < 0 || index >= uris.size) return null

        // 16-bit TIFF/PNG ("RAW-like") frames: BitmapFactory either downconverts or
        // cannot decode them at all, so they bypass it entirely. OpenCV's Android
        // build has libtiff and libpng compiled in (verified in libopencv_java4.so).
        decodeWideDepthFrame(uris[index])?.let { return it }

        var matRGBA: Mat? = null
        var matRGB: Mat? = null
        var bitmap: android.graphics.Bitmap? = null
        var bitmapRaw: android.graphics.Bitmap? = null
        
        return try {
            val uriFile = uris[index]
            val inputStream = uriFile.context.contentResolver.openInputStream(uriFile.uri)
            
            val options = android.graphics.BitmapFactory.Options().apply {
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                this.inSampleSize = inSampleSize.coerceAtLeast(1)
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

    @Synchronized
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

    /**
     * Analysis pass: decode at 1/2, 1/4, ... scale directly in the JPEG decoder.
     * Decoding a 12 MP JPEG at quarter scale costs a fraction of a full decode,
     * which is what makes the two-pass render affordable for image sequences.
     */
    override fun forEachAnalysisFrame(start: Int, targetWidth: Int, step: Int, callback: (Int, Mat) -> Boolean) {
        // Mixed-resolution folders: the render pass normalises every frame to the
        // baseline box (fill/fit), but this fast path used to analyse raw frames at
        // their own sizes. Deltas then lived in different pixel spaces, and OpenCV
        // asserts when the frame size changes under the tracker. Correctness wins.
        if (hasVaryingResolutions) {
            super.forEachAnalysisFrame(start, targetWidth, step, callback)
            return
        }
        val stride = step.coerceAtLeast(1)
        var sample = 1
        val base = baselineWidth.coerceAtLeast(1)
        while (base / (sample * 2) >= targetWidth) sample *= 2

        var counter = start
        while (counter < size) {
            val frame = getFrameRaw(counter, sample)
            if (frame == null) {
                // Deliberately break instead of skipping: buildRenderPlan() stores one
                // warp per RENDERED frame and pass 2 keys it by ordinal, so dropping a
                // frame here would shift every later warp onto the wrong frame. Stopping
                // early only loses the tail, which pass 2 falls back to identity for.
                Log.w(TAG, "Analysis stopped at frame $counter of $size (decode failed)")
                break
            }
            try {
                if (!callback(counter, frame)) break
            } finally {
                frame.release()
            }
            // Same stride as the render pass: an undecodable frame that pass 2
            // never visits must not stop the analysis here.
            counter += stride
        }
    }

    override fun forEachFrame(start: Int, step: Int, progress: (Int, Int) -> Unit, callback: (Int, Int, Mat) -> Boolean) {
        val stride = step.coerceAtLeast(1)
        var counter = start
        val totalFramesToProcess = (size - start).coerceAtLeast(0)
        var skipped = 0
        var consecutiveFailures = 0
        while (counter < size) {
            val frame = getFrame(counter)
            if (frame == null) {
                // ONE unreadable frame must not cancel the render. This used to be
                // `?: break`, so a single transient CvException in the decoder aborted
                // the whole sequence before frame 0 and the user got "Processing
                // Failed" with no output at all. Skip it, say so, and keep going.
                skipped++
                consecutiveFailures++
                Log.w(TAG, "Skipping undecodable frame $counter of $size " +
                        "(skipped=$skipped, consecutive=$consecutiveFailures)")
                if (consecutiveFailures >= MAX_CONSECUTIVE_DECODE_FAILURES) {
                    Log.e(TAG, "Stopping sequence walk: $consecutiveFailures consecutive " +
                            "frames failed to decode (delivered ${counter - start - skipped})")
                    break
                }
                // Keep the stride alignment: a failure must not shift every later
                // frame's sampling phase.
                counter += stride
                continue
            }
            consecutiveFailures = 0

            // FIX: Wrap in try/finally to guarantee memory cleanup even if callback throws
            try {
                progress(counter - start, totalFramesToProcess)
                val continueProcessing = callback(counter, size, frame)
                if (!continueProcessing) break
            } finally {
                frame.release() // Mandatory memory cleanup
            }
            
            // Input speed: an undecoded frame frame cannot fail, so this is the
            // only place a real render-time saving exists for photo sequences.
            counter += stride
        }
        if (skipped > 0) {
            Log.w(TAG, "forEachFrame finished with $skipped undecodable frame(s) skipped " +
                    "out of ${size - start}")
        }
    }

    override fun release() {
        // No persistent mats to release
    }
}
