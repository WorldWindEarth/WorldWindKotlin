package earth.worldwind.draw

import earth.worldwind.geom.Matrix3
import earth.worldwind.geom.Vec3
import earth.worldwind.render.Color
import earth.worldwind.render.Texture
import earth.worldwind.render.buffer.AbstractBufferObject
import earth.worldwind.render.program.TriangleShaderProgram
import earth.worldwind.render.VertexState

open class DrawShapeState internal constructor() {
    companion object {
        const val MAX_DRAW_ELEMENTS = 4
    }

    var program: TriangleShaderProgram? = null
    var elementBuffer: AbstractBufferObject? = null
    val vertexOrigin = Vec3()
    var vertexStride = 0
    var enableCullFace = true
    var enableDepthTest = true
    var enableDepthWrite = true
    var depthOffset = 0.0
    var isLine = false
    var isStatic = false
    val vertexState = VertexState()
    var pickIdOffset = 0
    protected val color = Color()
    protected var opacity = 1.0f
    protected var lineWidth = 1f
    protected var texture: Texture? = null
    protected val texCoordMatrix = Matrix3()
    internal var primCount = 0
    internal val prims = Array(MAX_DRAW_ELEMENTS) { DrawElements() }

    open fun reset() {
        program = null
        vertexState.reset()
        elementBuffer = null
        vertexOrigin.set(0.0, 0.0, 0.0)
        vertexStride = 0
        enableCullFace = true
        enableDepthTest = true
        isLine = false
        isStatic = false
        depthOffset = 0.0
        color.set(1f, 1f, 1f, 1f)
        pickIdOffset = 0
        opacity = 1.0f
        lineWidth = 1f
        texture = null
        texCoordMatrix.setToIdentity()
        primCount = 0
        for (idx in 0 until MAX_DRAW_ELEMENTS) prims[idx].texture = null
    }

    fun color(color: Color) = apply { this.color.copy(color) }

    fun opacity(opacity: Float) = apply { this.opacity = opacity }

    fun lineWidth(width: Float) = apply { lineWidth = width }

    fun texture(texture: Texture?) = apply { this.texture = texture }

    fun texCoordMatrix(matrix: Matrix3) = apply { texCoordMatrix.copy(matrix) }

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
    }
}