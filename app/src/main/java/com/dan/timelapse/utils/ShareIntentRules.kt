package com.dan.timelapse.utils

/**
 * Pure decision rules for Android share-sheet intake (ACTION_SEND / SEND_MULTIPLE / VIEW).
 *
 * Kept separate and string-based so it is unit-tested on the JVM. The share sheet is the one
 * entry point where the app does not control the URI: Google Photos hands over a content URI
 * whose provider streams `openInputStream` perfectly well but often answers no useful
 * `DocumentFile` columns (`_display_name`, `_mime_type`). The old intake path queried those
 * columns first, got null, and died — which is why the app showed up in the share sheet and
 * then loaded nothing. [SharedMediaMaterializer] copies the BYTES instead, exactly what
 * WhatsApp does (and why the same share works there).
 */
object ShareIntentRules {

    enum class Kind { VIDEO, IMAGES, FOLDER }

    /**
     * Merge the places Android puts shared content into one ordered, de-duplicated list.
     * Order matters: EXTRA_STREAM is authoritative, ClipData is the fallback some senders use.
     */
    fun mergeStreams(
        single: String?,
        list: List<String>?,
        clip: List<String>?
    ): List<String> {
        val out = LinkedHashSet<String>()
        single?.takeIf { it.isNotBlank() }?.let(out::add)
        list?.forEach { if (it.isNotBlank()) out.add(it) }
        clip?.forEach { if (it.isNotBlank()) out.add(it) }
        return out.toList()
    }

    /**
     * What did the user share? A directory is a folder; video and image are single-kind;
     * anything else is unsupported and must be rejected with a message, not a crash.
     */
    fun kindFor(mime: String?, isDirectory: Boolean): Kind? {
        if (isDirectory) return Kind.FOLDER
        val m = mime?.trim()?.lowercase() ?: return null
        if (m == "vnd.android.document/directory") return Kind.FOLDER
        if (m.startsWith("video/")) return Kind.VIDEO
        if (m.startsWith("image/")) return Kind.IMAGES
        return null
    }

    /**
     * The MIME to act on. A sender that declares a star-star wildcard, an application
     * wildcard, or nothing at all is common (Google Photos among them), so fall back to the
     * first URI whose provider answers `getType()`.
     */
    fun pickMime(declared: String?, resolved: List<String?>): String? {
        val d = declared?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val star = "*"
        val isStarStar = d == star + "/" + star
        val isWildcard = d == null || isStarStar || d == "application" + "/" + star ||
            d == "application/octet-stream" || d.endsWith("/" + star)
        if (!isWildcard) return d
        val fromProvider = resolved.firstOrNull { !it.isNullOrBlank() }?.trim()?.lowercase()
        if (fromProvider != null) return fromProvider
        // Nothing resolvable (Google Photos cloud items often answer null): a declared
        // image/video wildcard is still actionable, a star-star one is not.
        return if (d == null || isStarStar || d == "application/octet-stream") null else d
    }

    /**
     * Cache-file extension for a shared stream. Decoding sniffs magic bytes, so this only has
     * to be close enough for a filename — except that ImagesFramesInput picks its 16-bit
     * decoder by extension, which the materializer fixes by sniffing the real bytes.
     */
    fun extensionFor(mime: String?): String {
        val m = mime?.trim()?.lowercase() ?: return "bin"
        return when {
            m.endsWith("jpeg") || m.endsWith("jpg") -> "jpg"
            m.endsWith("png") -> "png"
            m.endsWith("webp") -> "webp"
            m.endsWith("heic") || m.endsWith("heif") -> "heic"
            m.endsWith("gif") -> "gif"
            m.endsWith("bmp") -> "bmp"
            m.endsWith("tiff") || m.endsWith("tif") -> "tif"
            m.startsWith("video/") -> "mp4"
            else -> "bin"
        }
    }
}
