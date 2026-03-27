package earth.worldwind.draw

import earth.worldwind.geom.Matrix3
import earth.worldwind.geom.Vec2
import earth.worldwind.geom.Vec3
import earth.worldwind.render.Color
import earth.worldwind.render.Texture
import earth.worldwind.render.buffer.BufferObject
import earth.worldwind.render.program.AbstractShaderProgram

open class DrawQuadState internal constructor() {
    companion object {
        const val MAX_DRAW_ELEMENTS = 5
    }

    var programDrawToTexture: AbstractShaderProgram? = null
    var programDrawTextureToTerrain: AbstractShaderProgram? = null
    var vertexBuffer: BufferObject? = null
    var elementBuffer: BufferObject? = null
    val vertexOrigin = Vec3()

    var a = Vec2()
    var b = Vec2()
    var c = Vec2()
    var d = Vec2()
    var vertexStride = 0
    var enableCullFace = true
    var enableDepthTest = true
    var enableLighting = false
    var depthOffset = 0.0
    var isLine = false
    val color = Color()
    var opacity = 1.0f
    var lineWidth = 1f
    var texture: Texture? = null
    var textureLod = 0
    val texCoordMatrix = Matrix3()
    val texCoordAttrib = VertexAttrib()
    internal var primCount = 0
    internal val prims = Array(MAX_DRAW_ELEMENTS) { DrawElements() }

    open fun reset() {
        programDrawToTexture = null
        programDrawTextureToTerrain = null
        vertexBuffer = null
        elementBuffer = null
        vertexOrigin.set(0.0, 0.0, 0.0)
        vertexStride = 0
        enableCullFace = true
        enableDepthTest = true
        enableLighting = false
        depthOffset = 0.0
        isLine = false
        color.set(1f, 1f, 1f, 1f)
        opacity = 1.0f
        lineWidth = 1f
        texture = null
        textureLod = 0
        texCoordMatrix.setToIdentity()
        texCoordAttrib.size = 0
        texCoordAttrib.offset = 0
        primCount = 0
        a.set(0.0, 0.0)
        b.set(0.0, 0.0)
        c.set(0.0, 0.0)
        d.set(0.0, 0.0)

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
        prim.depthOffset = depthOffset
        prim.texture = texture
        prim.textureLod = textureLod
        prim.texCoordMatrix.copy(texCoordMatrix)
        prim.texCoordAttrib.copy(texCoordAttrib)
        prim.a.copy(a)
        prim.b.copy(b)
        prim.c.copy(c)
        prim.d.copy(d)
    }

    internal open class DrawElements {
        var mode = 0
        var count = 0
        var type = 0
        var offset = 0
        val color = Color()
        var opacity = 1.0f
        var lineWidth = 0f
        var depthOffset = 0.0
        var texture: Texture? = null
        var textureLod = 0
        val texCoordMatrix = Matrix3()
        val texCoordAttrib = VertexAttrib()

        var a = Vec2()
        var b = Vec2()
        var c = Vec2()
        var d = Vec2()
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