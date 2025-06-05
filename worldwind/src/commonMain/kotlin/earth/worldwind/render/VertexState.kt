package earth.worldwind.render

import earth.worldwind.draw.DrawContext
import earth.worldwind.render.buffer.BufferObject
import earth.worldwind.util.kgl.GL_FLOAT

open class VertexState {
    internal class VertexAttrib constructor(
        val index: Int = 0,
        val vertexBuffer: BufferObject,
        val size: Int = 0,
        val type: Int = GL_FLOAT,
        val normalized: Boolean = false,
        val stride: Int = 0,
        val offset: Int = 0
    )

    private var attributes = mutableListOf<VertexAttrib>()

    fun reset() {
        attributes.clear()
    }

    fun addAttribute(
        index: Int,
        vertexBuffer: BufferObject,
        size: Int,
        type: Int,
        normalized: Boolean,
        stride: Int,
        offset: Int
    ) {
        attributes.add(VertexAttrib(index, vertexBuffer, size, type, normalized, stride, offset))
    }

    fun bind(dc: DrawContext): Boolean {
        var bindSuccessful = true
        for (vertexAttrib in attributes) {
            bindSuccessful = vertexAttrib.vertexBuffer.bindBuffer(dc)
            if (bindSuccessful) {
                dc.gl.enableVertexAttribArray(vertexAttrib.index)
                dc.gl.vertexAttribPointer(
                    vertexAttrib.index,
                    vertexAttrib.size,
                    vertexAttrib.type,
                    vertexAttrib.normalized,
                    vertexAttrib.stride,
                    vertexAttrib.offset
                )
            } else {
                break
            }
        }
        return bindSuccessful
    }

    fun unbind(dc: DrawContext) {
        for (vertexAttrib in attributes) {
            if (vertexAttrib.index == 0) continue // skip 0 for now as it's always enabled and disabling it here will cause lots of headache
            dc.gl.disableVertexAttribArray(vertexAttrib.index)
        }
    }
}