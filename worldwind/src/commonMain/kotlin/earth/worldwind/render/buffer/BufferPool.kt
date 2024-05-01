package earth.worldwind.render.buffer

import earth.worldwind.util.kgl.KglBuffer
import earth.worldwind.draw.DrawContext

open class BufferPool(
    protected val target: Int,
    protected val usage: Int,
    protected val blockSize: Int = DEFAULT_BLOCK_SIZE,
    protected val blockCount: Int = DEFAULT_BLOCK_COUNT
) {
    protected val buffers = Array(blockCount) { KglBuffer.NONE }
    protected var index = 0
    protected var offset = 0

    fun reset() {
        index = 0
        offset = 0
    }

    fun contextLost() {
        reset()
        buffers.fill(KglBuffer.NONE)
    }

    open fun bindBuffer(dc: DrawContext, sourceData: FloatArray): Int {
        if (blockSize < sourceData.size * Float.SIZE_BYTES) return -1
        if (!buffers[index].isValid() && !appendBuffer(dc)) return -1

        if (!enoughSpace(sourceData)) {
            index = (index + 1) % blockCount;
            if (!appendBuffer(dc)) return -1
        }

        dc.gl.bindBuffer(target, buffers[index])

        return subAllocAndPopulate(dc, sourceData)
    }

    private fun enoughSpace(sourceData: FloatArray) = offset + sourceData.size * Float.SIZE_BYTES <= blockSize

    private fun appendBuffer(dc: DrawContext): Boolean {
        if (!buffers[index].isValid() && !allocNewBuffer(dc)) return false
        offset = 0
        return true
    }

    private fun allocNewBuffer(dc: DrawContext): Boolean {
        val id = dc.gl.createBuffer()
        if (!id.isValid()) return false
        dc.gl.bindBuffer(target, id)
        dc.gl.bufferData(target, blockSize, null as FloatArray?, usage) // Only reserve memory
        dc.gl.bindBuffer(target, KglBuffer.NONE)
        buffers[index] = id
        return id.isValid()
    }

    private fun subAllocAndPopulate(dc: DrawContext, sourceData: FloatArray): Int {
        val bufferSize = sourceData.size * Float.SIZE_BYTES
        if (offset + bufferSize > blockSize) return -1
        dc.gl.bufferSubData(target, offset, bufferSize, sourceData) // Update part of the buffer
        val result = offset
        offset += bufferSize
        return result
    }

    companion object {
        const val DEFAULT_BLOCK_SIZE = 64 * 1024 // 64 KB
        const val DEFAULT_BLOCK_COUNT = 64
    }
}
