package com.dan.timelapse.framesinput

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import com.dan.timelapse.utils.UriFile
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc.COLOR_RGBA2RGB
import org.opencv.imgproc.Imgproc.cvtColor
import java.io.FileNotFoundException
import kotlin.math.max

class ImagesFramesInput(private val _uriFiles: List<UriFile>) : FramesInput() {

    companion object {
        private fun fromUriFiles(uriFiles: List<UriFile>): ImagesFramesInput {
            val files = uriFiles.filter { it.isPhoto && !it.name.startsWith('.') }
                .sortedBy { it.name }

            if (files.isEmpty()) throw FileNotFoundException()

            return ImagesFramesInput(files)
        }

        fun fromFolder(folderUri: UriFile): ImagesFramesInput {
            return fromUriFiles(folderUri.listFiles())
        }

        fun fromFiles(context: Context, uris: List<Uri>): ImagesFramesInput {
            val files = uris.mapNotNull { UriFile.fromSingleUri(context, it) }
            return fromUriFiles(files)
        }

        private fun getOrientation(uriFile: UriFile): Int {
            return try {
                uriFile.context.contentResolver.openInputStream(uriFile.uri)?.use { inputStream ->
                    val exif = ExifInterface(inputStream)
                    exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                } ?: ExifInterface.ORIENTATION_NORMAL
            } catch (e: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }
        }

        private fun applyRotation(frame: Mat, orientation: Int) {
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> {
                    val rotated = Mat()
                    Core.rotate(frame, rotated, Core.ROTATE_90_CLOCKWISE)
                    rotated.copyTo(frame)
                    rotated.release()
                }
                ExifInterface.ORIENTATION_ROTATE_180 -> {
                    val rotated = Mat()
                    Core.rotate(frame, rotated, Core.ROTATE_180)
                    rotated.copyTo(frame)
                    rotated.release()
                }
                ExifInterface.ORIENTATION_ROTATE_270 -> {
                    val rotated = Mat()
                    Core.rotate(frame, rotated, Core.ROTATE_90_COUNTERCLOCKWISE)
                    rotated.copyTo(frame)
                    rotated.release()
                }
            }
        }

        private fun loadImage(uriFile: UriFile, frame: Mat) {
            val inputStream = uriFile.context.contentResolver.openInputStream(uriFile.uri) ?: throw FileNotFoundException()
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            if (null == bitmap) throw FileNotFoundException()
            
            val orientation = getOrientation(uriFile)

            val image = Mat()
            Utils.bitmapToMat(bitmap, image)
            bitmap.recycle()
            cvtColor(image, frame, COLOR_RGBA2RGB)
            image.release()

            applyRotation(frame, orientation)
        }
    }

    private val _width: Int
    private val _height: Int
    private val _name: String

    override val fps: Int
        get() = 30

    override val name: String
        get() = _name

    override val width: Int
        get() = _width

    override val height: Int
        get() = _height

    override val size: Int
        get() = _uriFiles.size

    override val videoUri: Uri?
        get() = null

    init {
        _name = fixName(_uriFiles.first().name)
        val firstImage = Mat()
        loadImage(_uriFiles.first(), firstImage)
        _width = firstImage.width()
        _height = firstImage.height()
        firstImage.release()
    }

    override fun forEachFrame(startFrom: Int, seekCallback: ((Int) -> Unit)?, callback: (Int, Int, Mat) -> Boolean) {
        val frame = Mat()
        for (i in startFrom until _uriFiles.size) {
            val uriFile = _uriFiles[i]
            try {
                loadImage(uriFile, frame)
                if (!callback(i, _uriFiles.size, frame)) break
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        frame.release()
    }

    /**
     * Efficiently load a thumbnail for the UI slider
     */
    fun loadThumbnail(index: Int, targetWidth: Int): Bitmap? {
        if (index < 0 || index >= _uriFiles.size) return null
        val uriFile = _uriFiles[index]
        
        return try {
            val context = uriFile.context
            
            // First decode with inJustDecodeBounds=true to check dimensions
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uriFile.uri)?.use { 
                BitmapFactory.decodeStream(it, null, options)
            }

            // Calculate inSampleSize
            options.inSampleSize = calculateInSampleSize(options, targetWidth)
            options.inJustDecodeBounds = false
            
            val bitmap = context.contentResolver.openInputStream(uriFile.uri)?.use { 
                BitmapFactory.decodeStream(it, null, options)
            } ?: return null

            // Apply orientation if needed
            val orientation = getOrientation(uriFile)
            if (orientation == ExifInterface.ORIENTATION_NORMAL) return bitmap

            val matrix = android.graphics.Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            }
            
            val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotatedBitmap != bitmap) {
                bitmap.recycle()
            }
            rotatedBitmap
        } catch (e: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (width > reqWidth) {
            val halfWidth = width / 2
            while (halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
