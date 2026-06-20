package earth.worldwind.layer.ogc3d.draw

import earth.worldwind.draw.DrawContext
import earth.worldwind.draw.Drawable
import earth.worldwind.draw.DrawableShadow
import earth.worldwind.draw.DrawableSightline
import earth.worldwind.draw.SightlineOccluder
import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Vec3
import earth.worldwind.layer.shadow.ShadowCaster
import earth.worldwind.layer.shadow.ShadowMode
import earth.worldwind.layer.shadow.applyShadowReceiverUniforms
import earth.worldwind.layer.sightline.applySightlineReceiverUniforms
import earth.worldwind.layer.ogc3d.content.MeshContent
import earth.worldwind.layer.ogc3d.content.MeshSubmesh
import earth.worldwind.layer.ogc3d.program.Ogc3dTilesProgram
import earth.worldwind.render.Color
import earth.worldwind.render.Texture
import earth.worldwind.render.buffer.BufferObject
import earth.worldwind.util.Pool
import earth.worldwind.util.kgl.GL_CULL_FACE
import earth.worldwind.util.kgl.GL_FLOAT
import earth.worldwind.util.kgl.GL_TEXTURE0
import earth.worldwind.util.kgl.GL_TRIANGLES
import earth.worldwind.util.kgl.GL_TRIANGLE_FAN
import earth.worldwind.util.kgl.GL_TRIANGLE_STRIP
import earth.worldwind.util.kgl.GL_UNSIGNED_INT
import earth.worldwind.util.kgl.GL_UNSIGNED_SHORT
import kotlin.jvm.JvmStatic

/**
 * Color-pass drawable for one mesh tile. Pool-recycled. [ShadowCaster] + [SightlineOccluder]
 * gate on `shadowMode.castsShadows`. The sightline visibility tint is applied per-fragment by
 * `Ogc3dTilesProgram`'s embedded `SightlineReceiverGlsl` splice; the SURFACE-grouped sightline
 * overlay only tints terrain.
 */
open class DrawableTileMesh protected constructor() : Drawable, ShadowCaster, SightlineOccluder {

    var content: MeshContent? = null

    /** Set from `Tile3d.isFallback` at enqueue. Fallback tiles render only where stencil
     *  != [stencilId] so they fill gaps without overlaying finer descendants. */
    var isFallback: Boolean = false

    /** Outermost-fallback subtree id (0 = no fallback ancestor → stencil bypassed;
     *  1..255 = inside a fallback subtree). Non-fallback tiles write; fallback tiles
     *  draw where stencil != this id. */
    var stencilId: Int = 0

    val tileToWorld = Matrix4()

    var shadowMode: ShadowMode = ShadowMode.ENABLED

    var program: Ogc3dTilesProgram? = null

    /** Resolved from RR cache at enqueue; null entries → submesh renders without texture.
     *  Pooled per instance — [ensureSubmeshArrays] grows in place to avoid per-frame
     *  `arrayOfNulls<>` allocations across ~hundreds of drawables. */
    var submeshTextures: Array<Texture?> = EMPTY_TEXTURE_ARRAY
        private set

    /** Resolved at enqueue; null entries → submesh draw skipped. */
    var submeshVertexBuffers: Array<BufferObject?> = EMPTY_BUFFER_ARRAY
        private set

    /** Null entries for non-indexed primitives or after eviction. */
    var submeshElementBuffers: Array<BufferObject?> = EMPTY_BUFFER_ARRAY
        private set

    /** Null entries when the primitive has no `_BATCHID` — submesh falls back to tile-level pick. */
    var submeshBatchIdBuffers: Array<BufferObject?> = EMPTY_BUFFER_ARRAY
        private set

    /** Pick color for tile-level pick mode. Batch-level pick uses [pickIdBase] instead. */
    val pickColor = Color()

    /** First pick id reserved for this tile's batches. Zero = tile-level pick. */
    var pickIdBase: Int = 0

    private val worldBoundingCenter = Vec3()
    private var worldBoundingRadius = 0.0

    override val shadowCasterCenter: Vec3? get() = if (worldBoundingRadius > 0.0) worldBoundingCenter else null
    override val shadowCasterRadius: Double get() = worldBoundingRadius

    private var pool: Pool<DrawableTileMesh>? = null

    private val mvpMatrix = Matrix4()
    private val submeshModelMatrix = Matrix4()

    companion object {
        val KEY = DrawableTileMesh::class
        private val EMPTY_TEXTURE_ARRAY: Array<Texture?> = arrayOfNulls(0)
        private val EMPTY_BUFFER_ARRAY: Array<BufferObject?> = arrayOfNulls(0)

        @JvmStatic
        fun obtain(pool: Pool<DrawableTileMesh>): DrawableTileMesh {
            val instance = pool.acquire() ?: DrawableTileMesh()
            instance.pool = pool
            return instance
        }
    }

    /** Resize the four submesh arrays to hold at least [n] entries and null-clear the
     *  active range. Grows by power-of-two so consecutive enqueues of the same drawable
     *  for similar tile shapes avoid re-allocation entirely. */
    fun ensureSubmeshArrays(n: Int) {
        if (submeshTextures.size < n) {
            val cap = n.coerceAtLeast(8)
            submeshTextures = arrayOfNulls(cap)
            submeshVertexBuffers = arrayOfNulls(cap)
            submeshElementBuffers = arrayOfNulls(cap)
            submeshBatchIdBuffers = arrayOfNulls(cap)
            return
        }
        for (i in 0 until n) {
            submeshTextures[i] = null
            submeshVertexBuffers[i] = null
            submeshElementBuffers[i] = null
            submeshBatchIdBuffers[i] = null
        }
    }

    /** Populated by the layer before enqueue so the depth-pass can cull cheaply. */
    fun setWorldBounds(center: Vec3, radius: Double) {
        worldBoundingCenter.copy(center)
        worldBoundingRadius = radius
    }

    override fun recycle() {
        content = null
        program = null
        // Drop references but keep the array buffers — next obtain() reuses them via
        // ensureSubmeshArrays. Without this, drawables in the pool pin textures/buffers
        // alive and block RR-cache eviction.
        submeshTextures.fill(null)
        submeshVertexBuffers.fill(null)
        submeshElementBuffers.fill(null)
        submeshBatchIdBuffers.fill(null)
        tileToWorld.setToIdentity()
        worldBoundingCenter.set(0.0, 0.0, 0.0)
        worldBoundingRadius = 0.0
        shadowMode = ShadowMode.ENABLED
        pickColor.set(0f, 0f, 0f, 1f)
        pickIdBase = 0
        isFallback = false
        stencilId = 0
        pool?.release(this)
        pool = null
    }

    override fun draw(dc: DrawContext) {
        val content = this.content ?: return
        val submeshes = content.submeshes ?: return
        if (submeshes.isEmpty()) return

        val program = this.program ?: return
        if (!program.useProgram(dc)) return

        dc.applyShadowReceiverUniforms(program, shadowMode.receivesShadows)
        dc.applySightlineReceiverUniforms(program)
        program.loadLightDirection(dc.lightDirection)

        program.enablePickMode(dc.isPickMode)
        val batchPickActive = dc.isPickMode && pickIdBase > 0
        program.enableBatchPick(batchPickActive)
        if (dc.isPickMode) {
            if (batchPickActive) program.loadPickIdBase(pickIdBase)
            else program.loadPickColor(pickColor)
        }

        // Cesium skip-LoD stencil masking: every tile in a fallback subtree shares one
        // 7-bit [stencilId]. Fine tiles write their id; coarse fallback tiles draw only
        // where the stencil hasn't been claimed. id 0 = no fallback ancestor → bypass.
        val useStencil = useTileStencil(stencilId)
        if (useStencil) applyTileStencilState(dc, isFallback, stencilId)

        try {
            dc.activeTextureUnit(GL_TEXTURE0)
            val textures = submeshTextures
            val vertexBuffers = submeshVertexBuffers
            val elementBuffers = submeshElementBuffers
            val batchIdBuffers = submeshBatchIdBuffers
            for ((idx, submesh) in submeshes.withIndex()) {
                drawSubmesh(
                    dc, program, submesh,
                    vertexBuffer = vertexBuffers.getOrNull(idx),
                    elementBuffer = elementBuffers.getOrNull(idx),
                    texture = textures.getOrNull(idx),
                    batchIdBuffer = batchIdBuffers.getOrNull(idx),
                )
            }
        } finally {
            dc.gl.disableVertexAttribArray(1)
            dc.gl.disableVertexAttribArray(2)
            dc.gl.disableVertexAttribArray(3)
            dc.gl.disableVertexAttribArray(4)
            if (useStencil) clearTileStencilState(dc)
        }
    }

    private fun drawSubmesh(
        dc: DrawContext,
        program: Ogc3dTilesProgram,
        submesh: MeshSubmesh,
        vertexBuffer: BufferObject?,
        elementBuffer: BufferObject?,
        texture: Texture?,
        batchIdBuffer: BufferObject?,
    ) {
        if (vertexBuffer?.bindBuffer(dc) != true) return
        elementBuffer?.bindBuffer(dc)

        submeshModelMatrix.copy(tileToWorld).multiplyByMatrix(submesh.worldMatrix)
        program.loadModelMatrix(submeshModelMatrix)

        mvpMatrix.copy(dc.modelviewProjection).multiplyByMatrix(submeshModelMatrix)
        program.loadModelviewProjection(mvpMatrix)

        program.loadColor(Color(submesh.baseColor[0], submesh.baseColor[1], submesh.baseColor[2], submesh.baseColor[3]))
        program.loadOpacity(1.0f)
        program.enableLighting(submesh.normalOffset >= 0)
        // Vertex colour is the per-vertex `COLOR_0` attribute (RGB or RGBA). Untextured
        // photogrammetry tilesets often ship colour this way instead of as a texture map.
        program.enableVertexColor(submesh.colorOffset >= 0)
        val hasTexture = texture != null && submesh.texCoordOffset >= 0 && texture.bindTexture(dc)
        program.enableTexture(hasTexture)
        program.enableAlphaMask(submesh.alphaMask, submesh.alphaCutoff)

        val cullFaceDisabled = submesh.doubleSided
        if (cullFaceDisabled) dc.gl.disable(GL_CULL_FACE)

        // 0: pos (vec3), 1: normal (vec3?), 2: uv (vec2?), 3: vertex colour (unused),
        // 4: BATCH_ID (uint16?) — GL_UNSIGNED_SHORT, normalized=false promotes to int-valued float.
        dc.gl.enableVertexAttribArray(0)
        dc.gl.vertexAttribPointer(0, 3, GL_FLOAT, false, submesh.vertexStride, submesh.positionOffset)
        if (submesh.normalOffset >= 0) {
            dc.gl.enableVertexAttribArray(1)
            dc.gl.vertexAttribPointer(1, 3, GL_FLOAT, false, submesh.vertexStride, submesh.normalOffset)
        } else {
            dc.gl.disableVertexAttribArray(1)
        }
        if (submesh.texCoordOffset >= 0) {
            dc.gl.enableVertexAttribArray(2)
            dc.gl.vertexAttribPointer(2, 2, GL_FLOAT, false, submesh.vertexStride, submesh.texCoordOffset)
        } else {
            dc.gl.disableVertexAttribArray(2)
        }
        if (submesh.colorOffset >= 0) {
            dc.gl.enableVertexAttribArray(3)
            dc.gl.vertexAttribPointer(3, submesh.colorComponents, GL_FLOAT, false, submesh.vertexStride, submesh.colorOffset)
        } else {
            dc.gl.disableVertexAttribArray(3)
        }
        if (batchIdBuffer?.bindBuffer(dc) == true) {
            dc.gl.enableVertexAttribArray(4)
            dc.gl.vertexAttribPointer(4, 1, GL_UNSIGNED_SHORT, false, 0, 0)
        } else {
            dc.gl.disableVertexAttribArray(4)
        }

        if (elementBuffer != null) {
            val type = if (submesh.isInt32Indices) GL_UNSIGNED_INT else GL_UNSIGNED_SHORT
            dc.gl.drawElements(submesh.mode, submesh.elementCount, type, 0)
        } else {
            dc.gl.drawArrays(submesh.mode, 0, submesh.vertexCount)
        }

        if (cullFaceDisabled) dc.gl.enable(GL_CULL_FACE)
    }

    /** Cascade depth pass. Triangles only — lines/points alias on the shadow map. */
    override fun drawShadowDepth(dc: DrawContext, shadow: DrawableShadow) {
        if (!shadowMode.castsShadows) return
        val content = this.content ?: return
        val submeshes = content.submeshes ?: return
        val vertexBuffers = submeshVertexBuffers
        val elementBuffers = submeshElementBuffers
        for ((idx, submesh) in submeshes.withIndex()) {
            val mode = submesh.mode
            if (mode != GL_TRIANGLES && mode != GL_TRIANGLE_STRIP && mode != GL_TRIANGLE_FAN) continue
            val vbo = vertexBuffers.getOrNull(idx) ?: continue
            if (!vbo.bindBuffer(dc)) continue
            val ebo = elementBuffers.getOrNull(idx)
            ebo?.bindBuffer(dc)
            submeshModelMatrix.copy(tileToWorld).multiplyByMatrix(submesh.worldMatrix)
            shadow.loadCasterMatrix(submeshModelMatrix)
            dc.gl.enableVertexAttribArray(0)
            dc.gl.vertexAttribPointer(0, 3, GL_FLOAT, false, submesh.vertexStride, submesh.positionOffset)
            if (ebo != null) {
                val type = if (submesh.isInt32Indices) GL_UNSIGNED_INT else GL_UNSIGNED_SHORT
                dc.gl.drawElements(mode, submesh.elementCount, type, 0)
            } else {
                dc.gl.drawArrays(mode, 0, submesh.vertexCount)
            }
        }
    }

    /** Sightline depth pass. Triangles only — lines/points alias on the moments cube. */
    override fun drawSightlineDepth(dc: DrawContext, sightline: DrawableSightline) {
        if (!shadowMode.castsShadows) return
        val content = this.content ?: return
        val submeshes = content.submeshes ?: return
        val vertexBuffers = submeshVertexBuffers
        val elementBuffers = submeshElementBuffers
        for ((idx, submesh) in submeshes.withIndex()) {
            val mode = submesh.mode
            if (mode != GL_TRIANGLES && mode != GL_TRIANGLE_STRIP && mode != GL_TRIANGLE_FAN) continue
            val vbo = vertexBuffers.getOrNull(idx) ?: continue
            if (!vbo.bindBuffer(dc)) continue
            val ebo = elementBuffers.getOrNull(idx)
            ebo?.bindBuffer(dc)
            submeshModelMatrix.copy(tileToWorld).multiplyByMatrix(submesh.worldMatrix)
            sightline.loadOccluderMatrix(submeshModelMatrix)
            dc.gl.enableVertexAttribArray(0)
            dc.gl.vertexAttribPointer(0, 3, GL_FLOAT, false, submesh.vertexStride, submesh.positionOffset)
            if (ebo != null) {
                val type = if (submesh.isInt32Indices) GL_UNSIGNED_INT else GL_UNSIGNED_SHORT
                dc.gl.drawElements(mode, submesh.elementCount, type, 0)
            } else {
                dc.gl.drawArrays(mode, 0, submesh.vertexCount)
            }
        }
    }

}

