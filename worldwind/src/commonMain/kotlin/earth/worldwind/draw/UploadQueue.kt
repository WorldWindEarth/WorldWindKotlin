package earth.worldwind.draw

import earth.worldwind.render.buffer.BufferObject
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.NumericArray
import kotlin.jvm.JvmOverloads
import kotlin.math.max

open class UploadQueue internal constructor() {
    protected var size = 0
    protected var entries = arrayOfNulls<Entry>(size)
    val count get() = size

    companion object {
        protected const val MIN_CAPACITY_INCREMENT = 12
    }

    @JvmOverloads
    fun queueBufferUpload(buffer: BufferObject, array: NumericArray, version: Int, onUploaded: (() -> Unit)? = null) =
        queueEntry(buffer, array, version, isAssertion = false, onUploaded)

    /**
     * Queues a frame's assertion that [buffer] holds exactly [array]'s content before the frame
     * draws. Unlike the monotonic [queueBufferUpload], an assertion re-uploads whenever the
     * buffer's current content is not [array] — including when another window's frame replaced
     * it with newer content — so windows sharing one render resource cache never draw their
     * counts against another frame's content. Compared by array identity: producers must hand
     * the same [NumericArray] instance while content is unchanged and a fresh instance per
     * regeneration (see [earth.worldwind.render.RenderContext.assertGLBufferContent]).
     */
    fun queueContentAssertion(buffer: BufferObject, array: NumericArray) =
        queueEntry(buffer, array, 0, isAssertion = true, null)

    private fun queueEntry(
        buffer: BufferObject, array: NumericArray, version: Int, isAssertion: Boolean, onUploaded: (() -> Unit)?
    ) {
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
        entry.isAssertion = isAssertion
        entry.onUploaded = onUploaded
        size++
    }

    fun processUploads(dc: DrawContext) {
        // Entries persist until [clearUploads] (the frame's recycle): a re-draw of the same frame
        // re-runs its content assertions against whatever other windows uploaded in between.
        // Idempotent — versioned entries are guarded by version, assertions by content identity,
        // and [Entry.onUploaded] is cleared after firing so a re-draw doesn't re-fire it.
        for (position in 0 until size) {
            val next = entries[position] ?: break
            try {
                next.array?.let { array ->
                    next.buffer?.let { buffer ->
                        if (next.isAssertion) {
                            if (buffer.contentArray !== array) buffer.loadBuffer(dc, array)
                        } else if (buffer.version < next.version) {
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
            next.onUploaded?.invoke()
            next.onUploaded = null
        }
    }

    fun clearUploads() {
        for (idx in 0 until size) entries[idx]?.recycle()
        size = 0
    }

    protected open class Entry {
        var array: NumericArray? = null
        var buffer: BufferObject? = null
        var version = 0
        var isAssertion = false
        var onUploaded: (() -> Unit)? = null

        fun recycle() {
            array = null
            buffer = null
            version = 0
            isAssertion = false
            onUploaded = null
        }
    }
}
