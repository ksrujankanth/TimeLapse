package com.dan.timelapse.utils

import org.opencv.core.Mat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

inline fun <T> Mat.use(block: (Mat) -> T): T {
    try {
        return block(this)
    } finally {
        this.release()
    }
}

fun getJvmUsedMemoryMB(): Long {
    val runtime = Runtime.getRuntime()
    return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
}

fun getJvmMaxMemoryMB(): Long {
    return Runtime.getRuntime().maxMemory() / (1024 * 1024)
}

fun getJvmAvailableMemoryMB(): Long {
    val runtime = Runtime.getRuntime()
    return (runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())) / (1024 * 1024)
}

fun getTimestamp(): String {
    return SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
}
