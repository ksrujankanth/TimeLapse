package com.dan.timelapse.utils

/**
 * Natural (numeric-aware) filename ordering: `IMG_2.jpg` sorts before `IMG_10.jpg`.
 *
 * Why this exists: `ImagesFramesInput.fromFolder` sorts a folder by EXIF capture time
 * for <=300 entries and by `lastModified` above that. A folder copied from a PC or a
 * backup has `lastModified` = copy time, often identical for every file, and
 * `sortedBy` is stable — so the final order was whatever `DocumentFile.listFiles()`
 * returned, and `readdir` order is NOT name order on ext4/sdcardfs. A 1500-frame
 * timelapse could therefore play in an arbitrary order. Name order is the correct
 * tie-break: camera filenames increase with capture order.
 *
 * Pure Kotlin (no Android types) so it is unit-tested on the JVM.
 */
object NaturalOrder : Comparator<String> {

    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                // Compare the whole digit run numerically. Leading zeros are ignored,
                // then the longer run wins (10 > 9) and equal lengths compare lexically.
                var si = i
                var sj = j
                while (si < a.length && a[si].isDigit()) si++
                while (sj < b.length && b[sj].isDigit()) sj++
                val na = a.substring(i, si).trimStart('0')
                val nb = b.substring(j, sj).trimStart('0')
                val cmp = if (na.length != nb.length) na.length - nb.length else na.compareTo(nb)
                if (cmp != 0) return cmp
                i = si
                j = sj
            } else {
                val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
