package earth.worldwind.render.buffer

import earth.worldwind.draw.DrawContext
import earth.worldwind.util.kgl.GL_STATIC_DRAW

open class IntBufferObject(target: Int, array: IntArray, size: Int = array.size) : AbstractBufferObject(target, size * 4) {
    protected var array: IntArray? = array

    override fun release(dc: DrawContext) {
        super.release(dc)
        array = null // array can be non-null if the object has not been bound
    }

    override fun bindBuffer(dc: DrawContext): Boolean {
        array?.let{ loadBuffer(dc) }.also { array = null }
        return super.bindBuffer(dc)
    }

    override fun loadBufferObjectData(dc: DrawContext) {
        array?.let { dc.gl.bufferData(target, byteCount, it, GL_STATIC_DRAW) }
    }
}