package com.dan.timelapse.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

/**
 * Copies shared content into the app cache BEFORE anything else touches it.
 *
 * A share intent hands over a ONE-SHOT read grant. For Google Photos' cloud-backed
 * ("online-only") items the provider streams the original on demand and exposes no useful
 * DocumentFile columns, so the old `UriFile.fromSingleUri(...)!!` returned null and the load
 * either NPE'd or reported "Error loading media". Reading through `openInputStream` is what
 * WhatsApp does, and it is why sharing the same 10 photos to WhatsApp works.
 *
 * Materialising once also means the probe, the preview loop and every MFSR pass read local
 * files, so a grant that expires mid-render cannot break the render.
 */
class SharedMediaMaterializer(private val context: Context) {

    companion object {
        private const val TAG = "SharedMedia"
        private const val SHARE_DIR_PREFIX = "share_"
    }

    /** Fresh cache directory for one share; payloads from older shares are deleted. */
    fun newSessionDir(): File {
        val cache = context.cacheDir
        cache.listFiles { f -> f.isDirectory && f.name.startsWith(SHARE_DIR_PREFIX) }
            ?.forEach { runCatching { it.deleteRecursively() } }
        val dir = File(cache, "$SHARE_DIR_PREFIX${System.currentTimeMillis()}")
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IOException("Cannot create share cache dir: ${dir.absolutePath}")
        }
        return dir
    }

    /**
     * Copy one shared image into [dir] and describe it without asking the provider anything
     * beyond its MIME type. Throws if the stream cannot be read, so the caller can skip that
     * item and still load the rest.
     */
    fun materializeImage(uri: Uri, dir: File, index: Int): UriFile {
        val declaredMime = resolveMime(uri, "image/jpeg")
        val base = "img_%03d".format(index)
        var out = File(dir, "$base.${ShareIntentRules.extensionFor(declaredMime)}")
        copy(uri, out)

        // The declared type can be generic (image/*, application/octet-stream), but
        // ImagesFramesInput picks its 16-bit decoder from the FILE EXTENSION, so a shared
        // PNG/TIFF would otherwise lose its 16-bit route. Rename according to the bytes.
        val detected = detectFile(out)
        if (detected != null && detected.extension != out.extension) {
            val renamed = File(dir, "$base.${detected.extension}")
            if (out.renameTo(renamed)) out = renamed
        }
        val mime = when (detected?.extension) {
            "png" -> "image/png"
            "tif" -> "image/tiff"
            "jpg" -> "image/jpeg"
            "heic" -> "image/heic"
            else -> declaredMime
        }
        Log.i(TAG, "Shared image $uri -> ${out.name} (${out.length()} bytes, $mime)")
        return UriFile(context, Uri.fromFile(out), out.name, mime, base)
    }

    /**
     * Identify a shared stream by reading its first 16 bytes.
     *
     * Used when the sender declares no usable MIME (some cloud providers answer
     * `application/octet-stream` for everything). 16 bytes is a small read even for an
     * online-only item, and it decides whether to run the image or the video loader.
     */
    fun probeKind(uri: Uri): MediaSniffer.Kind? {
        val head = ByteArray(16)
        val read = try {
            context.contentResolver.openInputStream(uri)?.use { it.read(head) } ?: return null
        } catch (e: Exception) {
            Log.w(TAG, "Cannot probe shared stream $uri", e)
            return null
        }
        if (read <= 0) return null
        return MediaSniffer.detect(head, read)?.kind
    }

    /**
     * A UriFile for a stream we deliberately do NOT pre-copy: VideoFramesInput already bridges
     * the source into cache in its constructor. Never depends on document columns either.
     */
    fun uriFileFor(uri: Uri, index: Int, fallbackExt: String): UriFile {
        UriFile.fromSingleUri(context, uri)?.let { return it }
        val mime = resolveMime(uri, "video/mp4")
        val ext = ShareIntentRules.extensionFor(mime).takeIf { it != "bin" } ?: fallbackExt
        val name = queryDisplayName(uri) ?: "shared_$index.$ext"
        return UriFile(context, uri, name, mime, "shared_$index")
    }

    private fun resolveMime(uri: Uri, fallback: String): String =
        try {
            context.contentResolver.getType(uri)?.takeIf { it.isNotBlank() } ?: fallback
        } catch (e: Exception) {
            fallback
        }

    private fun copy(uri: Uri, out: File) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw FileNotFoundException("Cannot open shared content: $uri")
        input.use { source ->
            FileOutputStream(out).use { sink -> source.copyTo(sink, 64 * 1024) }
        }
        if (out.length() == 0L) throw IOException("Shared content is empty: $uri")
    }

    private fun sniffExtension(file: File): String? = detectFile(file)?.extension

    private fun detectFile(file: File): MediaSniffer.Detection? {
        val magic = ByteArray(16)
        val read = try {
            FileInputStream(file).use { it.read(magic) }
        } catch (e: Exception) {
            -1
        }
        if (read <= 0) return null
        return MediaSniffer.detect(magic, read)
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            Log.w(TAG, "DISPLAY_NAME query failed for $uri", e)
            null
        }
    }
}
