package earth.worldwind.draw

import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Vec3
import earth.worldwind.layer.shadow.ShadowCaster
import earth.worldwind.layer.shadow.applyShadowReceiverUniforms
import earth.worldwind.render.buffer.BufferObject
import earth.worldwind.render.program.BasicTextureProgram
import earth.worldwind.util.Pool
import earth.worldwind.util.kgl.GL_CULL_FACE
import earth.worldwind.util.kgl.GL_DEPTH_TEST
import earth.worldwind.util.kgl.GL_FLOAT
import earth.worldwind.util.kgl.GL_TEXTURE0
import kotlin.jvm.JvmStatic

open class DrawableMesh protected constructor(): Drawable, SightlineOccluder, ShadowCaster {
    val drawState = DrawShapeState()
    var normalsBuffer: BufferObject? = null
    var texCoordsBuffer: BufferObject? = null
    private var pool: Pool<DrawableMesh>? = null
    private val mvpMatrix = Matrix4()
    private val modelMatrix = Matrix4()
    private val eyeLightDirection = Vec3()

    override val shadowCasterCenter get() = if (drawState.boundingRadius > 0.0) drawState.boundingCenter else null
    override val shadowCasterRadius get() = drawState.boundingRadius

    companion object {
        val KEY = DrawableMesh::class

        @JvmStatic
        fun obtain(pool: Pool<DrawableMesh>): DrawableMesh {
            val instance = pool.acquire() ?: DrawableMesh()
            instance.pool = pool
            return instance
        }
    }

    override fun recycle() {
        drawState.reset()
        normalsBuffer = null
        texCoordsBuffer = null
        pool?.release(this)
        pool = null
    }

    override fun draw(dc: DrawContext) {
        if (drawState.isOccluderOnly) return // queued only for the sightline depth pass
        val program = drawState.program as? BasicTextureProgram ?: return // program unspecified
        if (!program.useProgram(dc)) return // program failed to build
        if (drawState.vertexBuffer?.bindBuffer(dc) != true) return  // vertex buffer unspecified or failed to bind
        if (drawState.elementBuffer?.bindBuffer(dc) != true) return  // element buffer unspecified or failed to bind

        // Use the draw context's pick mode.
        program.loadModulateColor(dc.isPickMode)

        // Disable triangle back face culling if requested.
        if (!drawState.enableCullFace) dc.gl.disable(GL_CULL_FACE)

        // Disable depth testing if requested.
        if (!drawState.enableDepthTest) dc.gl.disable(GL_DEPTH_TEST)

        // Disable depth writing if requested.
        if (!drawState.enableDepthWrite) dc.gl.depthMask(false)

        // Make multi-texture unit 0 active.
        dc.activeTextureUnit(GL_TEXTURE0)
        program.loadTextureUnit(GL_TEXTURE0)

        // Bind cascade shadow textures and load receiver uniforms once per draw. Picks bypass.
        dc.applyShadowReceiverUniforms(program)

        // Model -> world transform: vertex origin is added to tile-local positions to recover
        // ECEF Cartesian for the shadow receiver. Same transform implicit in [mvpMatrix].
        modelMatrix.setToTranslation(drawState.vertexOrigin.x, drawState.vertexOrigin.y, drawState.vertexOrigin.z)
        program.loadModelMatrix(modelMatrix)

        // Use the shape's vertex point attribute.
        dc.gl.vertexAttribPointer(0 /*vertexPoint*/, 3, GL_FLOAT, false, 0, 0)

        // Apply lighting.
        if (!dc.isPickMode && drawState.enableLighting && normalsBuffer?.bindBuffer(dc) == true) {
            program.loadApplyLighting(true)
            dc.gl.enableVertexAttribArray(1 /*normalVector*/)
            dc.gl.vertexAttribPointer(1 /*normalVector*/, 3, GL_FLOAT, false, 0, 0)
            program.loadModelviewInverse(dc.modelviewNormalTransform)
            // Transform the world-space light direction into eye space using the same orthonormal
            // rotation as the normals (modelviewNormalTransform). The fragment shader dots this
            // against the eye-space normal directly.
            eyeLightDirection.copy(dc.lightDirection).multiplyByMatrix(dc.modelviewNormalTransform).normalize()
            program.loadLightDirection(eyeLightDirection)
        }

        // Draw the specified primitives.
        for (idx in 0 until drawState.primCount) {
            val prim = drawState.prims[idx]
            // Use the draw context's modelview projection matrix, transformed to shape local coordinates.
            if (prim.depthOffset != 0.0) {
                mvpMatrix.copy(dc.projection).offsetProjectionDepth(prim.depthOffset)
                mvpMatrix.multiplyByMatrix(dc.modelview)
            } else {
                mvpMatrix.copy(dc.modelviewProjection)
            }
            mvpMatrix.multiplyByTranslation(drawState.vertexOrigin.x, drawState.vertexOrigin.y, drawState.vertexOrigin.z)
            program.loadModelviewProjection(mvpMatrix)
            program.loadColor(prim.color)
            program.loadOpacity(prim.opacity)
            if (prim.texture?.bindTexture(dc) == true && texCoordsBuffer?.bindBuffer(dc) == true) {
                dc.gl.enableVertexAttribArray(2)
                dc.gl.vertexAttribPointer(2 /*vertexTexCoord*/, 2, GL_FLOAT, false, 0, 0)
                program.loadTextureEnabled(true)
                program.loadTextureMatrix(prim.texCoordMatrix)
            } else {
                dc.gl.disableVertexAttribArray(2 /*vertexTexCoord*/)
                program.loadTextureEnabled(false)
                // prevent "RENDER WARNING: there is no texture bound to unit 0"
                dc.defaultTexture.bindTexture(dc)
            }
            dc.gl.lineWidth(prim.lineWidth)
            dc.gl.drawElements(prim.mode, prim.count, prim.type, prim.offset)
        }

        // Restore the default WorldWind OpenGL state.
        if (!drawState.enableCullFace) dc.gl.enable(GL_CULL_FACE)
        if (!drawState.enableDepthTest) dc.gl.enable(GL_DEPTH_TEST)
        if (!drawState.enableDepthWrite) dc.gl.depthMask(true)
        dc.gl.lineWidth(1f)
        if (!dc.isPickMode && drawState.enableLighting) {
            program.loadApplyLighting(false)
            dc.gl.disableVertexAttribArray(1 /*normalVector*/)
        }
        dc.gl.disableVertexAttribArray(2 /*vertexTexCoord*/)
    }

    override fun drawSightlineDepth(dc: DrawContext, sightline: DrawableSightline) {
        // Mesh positions are tightly packed (separate normal / uv buffers), so stride is 0.
        sightline.drawShapeStateOccluder(dc, drawState, 0)
    }

    override fun drawShadowDepth(dc: DrawContext, shadow: DrawableShadow) {
        // Same dispatch as the sightline path; positions tightly packed, stride 0.
        shadow.drawShapeStateOccluder(dc, drawState, 0)
    }
}