package earth.worldwind.draw

import earth.worldwind.render.buffer.BufferObject
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.NumericArray
import kotlin.math.max

open class UploadQueue internal constructor() {
    protected var size = 0
    protected var entries = arrayOfNulls<Entry>(size)
    val count get() = size

    companion object {
        protected const val MIN_CAPACITY_INCREMENT = 12
    }

    fun queueBufferUpload(buffer: BufferObject, array: NumericArray, version: Long) {
        val capacity = entries.size
        if (capacity == size) {
            val increment = max(capacity shr 1, MIN_CAPACITY_INCREMENT)
            val newEntries = arrayOfNulls<Entry>(capacity + increment)
            entries.copyInto(newEntries)
            entries = newEntries
        }
        val entry = entries[size] ?: Entry().also { entries[size] = it }
        entry.array = array
        entry.buffer = buffer
        entry.version = version
        size++
    }

    fun processUploads(dc: DrawContext) {
        var position = 0
        while (position < size) {
            val next = entries[position++] ?: break
            try {
                next.array?.let { array ->
                    next.buffer?.let { buffer ->
                        if (buffer.version < next.version) {
                            buffer.loadBuffer(dc, array)
                            buffer.version = next.version
                        }
                    }
                }
            } catch (e: Exception) {
                logMessage(
                    ERROR, "UploadQueue", "processUploads",
                    "Exception while uploading '$next'", e
                )
            }
        }
    }

    fun clearUploads() {
        for (idx in 0 until size) entries[idx]?.recycle()
        size = 0
    }

    protected open class Entry {
        var array: NumericArray? = null
        var buffer: BufferObject? = null
        var version = 0L

        fun recycle() {
            array = null
            buffer = null
            version = 0L
        }
    }
}
