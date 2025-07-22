package earth.worldwind.draw

import earth.worldwind.geom.Matrix3
import earth.worldwind.geom.Vec3
import earth.worldwind.render.Color
import earth.worldwind.render.Texture
import earth.worldwind.render.buffer.BufferObject
import earth.worldwind.render.program.TriangleShaderProgram

open class DrawShapeState internal constructor() {
    companion object {
        const val MAX_DRAW_ELEMENTS = 4
    }

    var program: TriangleShaderProgram? = null
    var vertexBuffer: BufferObject? = null
    var elementBuffer: BufferObject? = null
    val vertexOrigin = Vec3()
    var vertexStride = 0
    var enableCullFace = true
    var enableDepthTest = true
    var enableDepthWrite = true
    var depthOffset = 0.0
    var isLine = false
    val color = Color()
    var opacity = 1.0f
    var lineWidth = 1f
    var texture: Texture? = null
    val texCoordMatrix = Matrix3()
    val texCoordAttrib = VertexAttrib()
    internal var primCount = 0
    internal val prims = Array(MAX_DRAW_ELEMENTS) { DrawElements() }

    open fun reset() {
        program = null
        vertexBuffer = null
        elementBuffer = null
        vertexOrigin.set(0.0, 0.0, 0.0)
        vertexStride = 0
        enableCullFace = true
        enableDepthTest = true
        isLine = false
        depthOffset = 0.0
        color.set(1f, 1f, 1f, 1f)
        opacity = 1.0f
        lineWidth = 1f
        texture = null
        texCoordMatrix.setToIdentity()
        texCoordAttrib.size = 0
        texCoordAttrib.offset = 0
        primCount = 0
        for (idx in 0 until MAX_DRAW_ELEMENTS) prims[idx].texture = null
    }

    open fun drawElements(mode: Int, count: Int, type: Int, offset: Int) {
        val prim = prims[primCount++]
        prim.mode = mode
        prim.count = count
        prim.type = type
        prim.offset = offset
        prim.color.copy(color)
        prim.opacity = opacity
        prim.lineWidth = lineWidth
        prim.texture = texture
        prim.texCoordMatrix.copy(texCoordMatrix)
        prim.texCoordAttrib.copy(texCoordAttrib)
    }

    internal open class DrawElements {
        var mode = 0
        var count = 0
        var type = 0
        var offset = 0
        val color = Color()
        var opacity = 1.0f
        var lineWidth = 0f
        var texture: Texture? = null
        val texCoordMatrix = Matrix3()
        val texCoordAttrib = VertexAttrib()
    }

    open class VertexAttrib {
        var size = 0
        var offset = 0

        fun copy(attrib: VertexAttrib) {
            size = attrib.size
            offset = attrib.offset
        }
    }
}