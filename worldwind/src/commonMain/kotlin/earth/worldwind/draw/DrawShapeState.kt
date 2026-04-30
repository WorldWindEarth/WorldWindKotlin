package earth.worldwind.draw

import earth.worldwind.geom.Matrix3
import earth.worldwind.geom.Vec3
import earth.worldwind.render.Color
import earth.worldwind.render.Texture
import earth.worldwind.render.buffer.BufferObject
import earth.worldwind.render.program.AbstractShaderProgram

open class DrawShapeState internal constructor() {
    companion object {
        const val MAX_DRAW_ELEMENTS = 5
    }

    var program: AbstractShaderProgram? = null
    var vertexBuffer: BufferObject? = null
    var elementBuffer: BufferObject? = null
    val vertexOrigin = Vec3()
    var vertexStride = 0

    /**
     * World-space bounding sphere of this shape, used by the shadow cascade dispatcher to skip
     * cascades whose AABB doesn't intersect the shape. [boundingRadius] left at `0.0` opts out
     * of culling (the shape is rasterised into every cascade). Populated by shapes whose
     * `currentBoundindData.boundingBox` is meaningful.
     */
    val boundingCenter = Vec3()
    var boundingRadius: Double = 0.0
    var enableCullFace = true
    var enableDepthTest = true
    var enableDepthWrite = true
    var enableLighting = false
    var depthOffset = 0.0
    var isLine = false
    // True when the drawable is queued only for SightlineOccluder; main draw() short-circuits.
    var isOccluderOnly = false
    val color = Color()
    var opacity = 1.0f
    var lineWidth = 1f
    var texture: Texture? = null
    var textureLod = 0
    val texCoordMatrix = Matrix3()
    val texCoordAttrib = VertexAttrib()
    internal var primCount = 0
    internal val prims = Array(MAX_DRAW_ELEMENTS) { DrawElements() }

    open fun copy(other: DrawShapeState) {
        program = other.program
        vertexBuffer = other.vertexBuffer
        elementBuffer = other.elementBuffer
        vertexOrigin.copy(other.vertexOrigin)
        boundingCenter.copy(other.boundingCenter)
        boundingRadius = other.boundingRadius
        vertexStride = other.vertexStride
        enableCullFace = other.enableCullFace
        enableDepthTest = other.enableDepthTest
        enableDepthWrite = other.enableDepthWrite
        enableLighting = other.enableLighting
        isLine = other.isLine
        isOccluderOnly = other.isOccluderOnly
        depthOffset = other.depthOffset
        color.copy(other.color)
        opacity = other.opacity
        lineWidth = other.lineWidth
        texture = other.texture
        textureLod = other.textureLod
        texCoordMatrix.copy(other.texCoordMatrix)
        texCoordAttrib.copy(other.texCoordAttrib)
        primCount = other.primCount
        for (i in 0 until other.primCount) {
            val src = other.prims[i]; val dst = prims[i]
            dst.mode = src.mode; dst.count = src.count; dst.type = src.type; dst.offset = src.offset
            dst.color.copy(src.color); dst.opacity = src.opacity; dst.lineWidth = src.lineWidth
            dst.depthOffset = src.depthOffset; dst.texture = src.texture; dst.textureLod = src.textureLod
            dst.texCoordMatrix.copy(src.texCoordMatrix); dst.texCoordAttrib.copy(src.texCoordAttrib)
        }
    }

    open fun reset() {
        program = null
        vertexBuffer = null
        elementBuffer = null
        vertexOrigin.set(0.0, 0.0, 0.0)
        boundingCenter.set(0.0, 0.0, 0.0)
        boundingRadius = 0.0
        vertexStride = 0
        enableCullFace = true
        enableDepthTest = true
        enableLighting = false
        isLine = false
        isOccluderOnly = false
        depthOffset = 0.0
        color.set(1f, 1f, 1f, 1f)
        opacity = 1.0f
        lineWidth = 1f
        texture = null
        textureLod = 0
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
        prim.depthOffset = depthOffset
        prim.texture = texture
        prim.textureLod = textureLod
        if (texture != null) prim.texCoordMatrix.copy(texCoordMatrix)
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
        var depthOffset = 0.0
        var texture: Texture? = null
        var textureLod = 0
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