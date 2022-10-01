package earth.worldwind.render.buffer

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Range
import earth.worldwind.render.RenderResource
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.kgl.KglBuffer

abstract class AbstractBufferObject(protected val target: Int, val byteCount: Int) : RenderResource {
    val ranges = mutableMapOf<Int, Range>()
    protected var id = KglBuffer.NONE

    override fun release(dc: DrawContext) { deleteBufferObject(dc) }

    open fun bindBuffer(dc: DrawContext): Boolean {
        if (id.isValid()) dc.bindBuffer(target, id)
        return id.isValid()
    }

    protected open fun loadBuffer(dc: DrawContext) {
        val currentBuffer = dc.currentBuffer(target)
        try {
            // Create the OpenGL buffer object.
            if (!id.isValid()) createBufferObject(dc)
            // Make the OpenGL buffer object bound to the specified target.
            dc.bindBuffer(target, id)
            // Load the current NIO buffer as the OpenGL buffer object's data.
            loadBufferObjectData(dc)
        } catch (e: Exception) {
            // The NIO buffer could not be used as buffer data for an OpenGL buffer object. Delete the buffer object
            // to ensure that calls to bindBuffer fail.
            deleteBufferObject(dc)
            logMessage(
                ERROR, "BufferObject", "loadBuffer", "Exception attempting to load buffer data", e
            )
        } finally {
            // Restore the current OpenGL buffer object binding.
            dc.bindBuffer(target, currentBuffer)
        }
    }

    protected open fun createBufferObject(dc: DrawContext) { id = dc.gl.createBuffer() }

    protected open fun deleteBufferObject(dc: DrawContext) {
        if (id.isValid()) {
            dc.gl.deleteBuffer(id)
            id = KglBuffer.NONE
        }
    }

    protected abstract fun loadBufferObjectData(dc: DrawContext)
}