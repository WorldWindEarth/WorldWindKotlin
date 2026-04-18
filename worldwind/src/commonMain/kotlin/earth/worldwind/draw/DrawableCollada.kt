package earth.worldwind.draw

import earth.worldwind.geom.Matrix3
import earth.worldwind.geom.Matrix4
import earth.worldwind.render.Color
import earth.worldwind.render.Texture
import earth.worldwind.render.buffer.BufferObject
import earth.worldwind.render.program.BasicTextureProgram
import earth.worldwind.util.kgl.*

class DrawableCollada : Drawable {
    class EntityDrawState {
        var vertexByteOffset = 0
        var uvByteOffset = 0
        var normalByteOffset = 0
        var indexByteOffset = 0
        var indexCount = 0
        var indexType = GL_UNSIGNED_SHORT
        var indexed = false
        var vertexCount = 0
        var hasNormals = false
        var hasUvs = false
        var texture: Texture? = null
        val color = Color(1f, 1f, 1f, 1f)
        var opacity = 1f
        val nodeWorldMatrix = Matrix4()
        val nodeNormalMatrix = Matrix4()
        var useLocalTransforms = true
    }

    var program: BasicTextureProgram? = null
    var vertexBuffer: BufferObject? = null
    var indexBuffer: BufferObject? = null
    val transformationMatrix = Matrix4()
    val normalTransformMatrix = Matrix4()
    val texCoordMatrix = Matrix3(1.0, 0.0, 0.0, 0.0, -1.0, 1.0, 0.0, 0.0, 1.0) // unit Y-flip: v' = 1 - v
    var doubleSided = false
    val pickColor = Color()
    var layerOpacity = 1f
    val entities = mutableListOf<EntityDrawState>()

    private val mvpMatrix = Matrix4()
    private val normalMatrix = Matrix4()

    companion object {
        val KEY = DrawableCollada::class
    }

    override fun recycle() {
        program = null
        vertexBuffer = null
        indexBuffer = null
        entities.clear()
    }

    override fun draw(dc: DrawContext) {
        val prog = program ?: return
        if (!prog.useProgram(dc)) return
        if (vertexBuffer?.bindBuffer(dc) != true) return

        if (indexBuffer != null) indexBuffer?.bindBuffer(dc)

        prog.loadModulateColor(dc.isPickMode)
        prog.loadTextureEnabled(false)
        dc.gl.enableVertexAttribArray(0) // vertexPoint

        if (doubleSided) dc.gl.disable(GL_CULL_FACE)

        for (entity in entities) drawEntity(dc, prog, entity)

        if (doubleSided) dc.gl.enable(GL_CULL_FACE)
        prog.loadTextureEnabled(false)
        prog.loadApplyLighting(false)
        dc.gl.disableVertexAttribArray(1) // normalVector
        dc.gl.disableVertexAttribArray(2) // vertexTexCoord
    }

    private fun drawEntity(dc: DrawContext, prog: BasicTextureProgram, entity: EntityDrawState) {
        val opacity = entity.opacity * layerOpacity
        if (dc.isPickMode) {
            prog.loadColor(pickColor)
            prog.loadOpacity(if (opacity > 0) 1f else 0f)
        } else {
            prog.loadColor(entity.color)
            prog.loadOpacity(opacity)
        }
        dc.gl.depthMask(opacity >= 1f || dc.isPickMode)

        // Texture
        var textureEnabled = false
        if (entity.hasUvs && entity.texture != null && !dc.isPickMode) {
            if (entity.texture!!.bindTexture(dc)) {
                textureEnabled = true
                prog.loadTextureEnabled(true)
                prog.loadTextureMatrix(texCoordMatrix)
                dc.gl.enableVertexAttribArray(2)
                dc.gl.vertexAttribPointer(2, 2, GL_FLOAT, false, 0, entity.uvByteOffset)
            }
        }
        if (!textureEnabled) {
            prog.loadTextureEnabled(false)
            dc.gl.disableVertexAttribArray(2)
        }

        // Lighting/normals
        var lightingEnabled = false
        if (entity.hasNormals && !dc.isPickMode && !doubleSided) {
            lightingEnabled = true
            prog.loadApplyLighting(true)
            dc.gl.enableVertexAttribArray(1)
            dc.gl.vertexAttribPointer(1, 3, GL_FLOAT, false, 0, entity.normalByteOffset)
            normalMatrix.copy(dc.modelviewNormalTransform)
            normalMatrix.multiplyByMatrix(normalTransformMatrix)
            if (entity.useLocalTransforms) normalMatrix.multiplyByMatrix(entity.nodeNormalMatrix)
            prog.loadModelviewInverse(normalMatrix)
        } else {
            prog.loadApplyLighting(false)
            dc.gl.disableVertexAttribArray(1)
        }

        // MVP
        mvpMatrix.copy(dc.modelviewProjection)
        mvpMatrix.multiplyByMatrix(transformationMatrix)
        if (entity.useLocalTransforms) mvpMatrix.multiplyByMatrix(entity.nodeWorldMatrix)
        prog.loadModelviewProjection(mvpMatrix)

        // Vertex positions
        dc.gl.vertexAttribPointer(0, 3, GL_FLOAT, false, 0, entity.vertexByteOffset)

        if (entity.indexed) {
            dc.gl.drawElements(GL_TRIANGLES, entity.indexCount, entity.indexType, entity.indexByteOffset)
        } else {
            dc.gl.drawArrays(GL_TRIANGLES, 0, entity.vertexCount)
        }

        if (lightingEnabled) {
            prog.loadApplyLighting(false)
            dc.gl.disableVertexAttribArray(1)
        }
        if (textureEnabled) {
            prog.loadTextureEnabled(false)
            dc.gl.disableVertexAttribArray(2)
        }
    }
}
