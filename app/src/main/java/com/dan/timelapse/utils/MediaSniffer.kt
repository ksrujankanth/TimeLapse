package com.dan.timelapse.utils

/**
 * Identifies a shared file by its first bytes.
 *
 * Why this exists: a share is only as good as the sender's MIME type. Some cloud providers
 * (Google Drive, and Photos for some item classes) declare `application/octet-stream` or a
 * star/star wildcard and answer `getType()` with the same, so a MIME-only router drops the
 * share even though the bytes are a perfectly good JPEG. Sniffing 16 bytes costs one small
 * read and turns "app appears in the share sheet and does nothing" into a load.
 *
 * Pure Kotlin on purpose: unit-tested on the JVM with header fixtures.
 */
object MediaSniffer {

    enum class Kind { IMAGE, VIDEO }

    data class Detection(val kind: Kind, val extension: String)

    /** Detect from a header buffer. [len] may be shorter than the array. */
    fun detect(bytes: ByteArray, len: Int = bytes.size): Detection? {
        fun at(i: Int): Int = if (i < len) bytes[i].toInt() and 0xFF else -1
        fun tag(offset: Int, text: String): Boolean {
            if (len < offset + text.length) return false
            for (i in text.indices) {
                if (at(offset + i) != text[i].code) return false
            }
            return true
        }

        // JPEG: FF D8 FF
        if (at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF) {
            return Detection(Kind.IMAGE, "jpg")
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (at(0) == 0x89 && tag(1, "PNG")) return Detection(Kind.IMAGE, "png")
        // TIFF: "II*\0" or "MM\0*"
        if ((tag(0, "II") && at(2) == 0x2A && at(3) == 0x00) ||
            (tag(0, "MM") && at(2) == 0x00 && at(3) == 0x2A)
        ) {
            return Detection(Kind.IMAGE, "tif")
        }
        if (tag(0, "GIF87a") || tag(0, "GIF89a")) return Detection(Kind.IMAGE, "gif")
        // WebP: "RIFF" .... "WEBP"
        if (tag(0, "RIFF") && tag(8, "WEBP")) return Detection(Kind.IMAGE, "webp")
        if (tag(0, "BM")) return Detection(Kind.IMAGE, "bmp")

        // ISO base media (MP4/MOV/3GP) and HEIF both carry "ftyp" at offset 4. The brand at
        // offset 8 decides which one it is.
        if (tag(4, "ftyp")) {
            val brand = String(
                ByteArray(4) { i -> if (8 + i < len) bytes[8 + i] else 0 },
                Charsets.US_ASCII
            )
            val imageBrand = brand == "heic" || brand == "heix" || brand == "hevc" ||
                brand == "hevx" || brand == "mif1" || brand == "msf1" || brand == "avif"
            return if (imageBrand) {
                Detection(Kind.IMAGE, "heic")
            } else {
                Detection(Kind.VIDEO, "mp4")
            }
        }
        return null
    }
}
