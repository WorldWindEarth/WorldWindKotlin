package earth.worldwind.draw

import earth.worldwind.geom.Matrix4
import earth.worldwind.layer.shadow.ShadowCaster
import earth.worldwind.layer.shadow.ShadowState
import earth.worldwind.render.Texture
import earth.worldwind.render.program.AlphaDepthProgram
import earth.worldwind.render.program.DirectionalDepthProgram
import earth.worldwind.util.Logger.INFO
import earth.worldwind.util.Logger.log
import earth.worldwind.util.Pool
import earth.worldwind.util.kgl.*
import kotlin.jvm.JvmStatic

/**
 * Runs the cascaded shadow map depth pass for the directional sun-shadow pipeline.
 * Enqueued each frame by [earth.worldwind.layer.shadow.ShadowLayer] in the BACKGROUND drawable
 * group, so its [draw] runs before any receiver shape / terrain draw and the cascade depth
 * textures are populated by the time receivers sample them
 * ([DrawContext.shadowCascadeFramebuffer]).
 *
 * The pass is **depth-only**: [DirectionalDepthProgram] rasterises casters through each
 * cascade's orthographic light projection with colour writes masked off, and the cascade
 * framebuffer's `DEPTH_COMPONENT` texture receives true hardware depth. That makes
 * `glPolygonOffset` a real slope-scaled caster bias — steeply-lit triangles get
 * proportionally more offset, so receivers only need a small constant bias and contact
 * shadows survive at street scale.
 *
 * Casters are dispatched via the [ShadowCaster] interface, parallel to
 * [SightlineOccluder] but with this drawable's per-cascade matrices.
 */
open class DrawableShadow protected constructor() : Drawable {
    var depthProgram: DirectionalDepthProgram? = null
    var alphaDepthProgram: AlphaDepthProgram? = null
    /** `true` while [beginAlphaMaskedCaster] has the alpha-tested program bound. */
    private var alphaCasterActive = false

    /**
     * Active cascade matrix during caster dispatch. Set by [draw] for each cascade so
     * [loadCasterMatrix] / [loadCasterTranslation] can compose model transforms against
     * the right `lightView`. Null outside the depth pass.
     */
    private var activeCascade: ShadowState.CascadeState? = null

    private val scratchMatrix = Matrix4()
    private var terrainCoverageSkip = BooleanArray(0)
    private var pool: Pool<DrawableShadow>? = null

    companion object {
        val KEY = DrawableShadow::class

        /**
         * Slope-scaled polygon offset for the caster pass (factor × max depth slope +
         * units × implementation quantum). Covers the rasterization-texel slope only — the
         * receiver's per-tap receiver-plane bias handles the filter kernel's spatial reach,
         * so the factor stays small and chimney-scale contact shadows survive. The 24-bit
         * cascade depth buffer keeps the constant term sub-centimetre even for
         * kilometre-deep cascades.
         */
        const val POLYGON_OFFSET_FACTOR = 1.5f
        const val POLYGON_OFFSET_UNITS = 4f

        @JvmStatic
        fun obtain(pool: Pool<DrawableShadow>): DrawableShadow {
            val instance = pool.acquire() ?: DrawableShadow()
            instance.pool = pool
            return instance
        }
    }

    override fun recycle() {
        depthProgram = null
        alphaDepthProgram = null
        activeCascade = null
        pool?.release(this)
        pool = null
    }

    override fun draw(dc: DrawContext) {
        // Frame-owned snapshot - the layer's scratch state mutates on the render thread.
        val state = dc.shadowState ?: return
        val program = depthProgram ?: return

        // Depth-texture cascades need sized formats; without them receivers stay fully lit.
        if (!dc.gl.supportsSizedTextureFormats) {
            state.isReady = false
            return
        }
        if (!program.useProgram(dc)) {
            state.isReady = false
            return
        }

        // Unbind cascade textures from units 1..4 - still bound from the previous frame's
        // receivers, they'd form a WebGL feedback loop with the cascade FBO.
        for (i in 0 until state.cascadeCount) {
            dc.activeTextureUnit(GL_TEXTURE1 + i)
            dc.bindTexture(KglTexture.NONE)
        }
        dc.activeTextureUnit(GL_TEXTURE0)
        dc.lastShadowTextureBindStamp = -1L

        // Restore the caller's framebuffer binding on exit.
        val previousFramebuffer = dc.currentFramebuffer
        try {
            // Depth-only pass: no colour writes, slope-scaled polygon offset as caster bias.
            // Depth test/write asserted explicitly - a leaked depthMask(false) would silently
            // empty the maps (glClear obeys depthMask too).
            dc.gl.enable(GL_DEPTH_TEST)
            dc.gl.depthFunc(GL_LEQUAL)
            dc.gl.depthMask(true)
            dc.gl.colorMask(false, false, false, false)
            dc.gl.disable(GL_BLEND)
            dc.gl.enable(GL_POLYGON_OFFSET_FILL)
            dc.gl.polygonOffset(POLYGON_OFFSET_FACTOR, POLYGON_OFFSET_UNITS)
            computeTerrainCoverageSkips(dc)
            for (i in 0 until state.cascadeCount) {
                val cascade = state.cascades[i]
                if (!cascade.isValid) continue
                drawCascadeDepth(dc, i, cascade)
            }
        } finally {
            // Restore default WorldWind state regardless of which cascade failed.
            dc.bindFramebuffer(previousFramebuffer)
            dc.gl.viewport(dc.viewport.x, dc.viewport.y, dc.viewport.width, dc.viewport.height)
            dc.gl.colorMask(true, true, true, true)
            dc.gl.enable(GL_BLEND)
            dc.gl.disable(GL_POLYGON_OFFSET_FILL)
            dc.gl.polygonOffset(0f, 0f)
            activeCascade = null
        }
    }

    /**
     * Renders terrain and shape casters into the depth framebuffer for one cascade. Depth
     * clears to 1.0 — the "no occluder" sentinel every receiver comparison passes against.
     */
    protected open fun drawCascadeDepth(dc: DrawContext, cascadeIndex: Int, cascade: ShadowState.CascadeState) {
        val program = depthProgram ?: return

        val framebuffer = dc.shadowCascadeFramebuffer(cascadeIndex)
        if (!framebuffer.bindFramebuffer(dc)) return

        val depthTexture = framebuffer.getAttachedTexture(GL_DEPTH_ATTACHMENT)
        dc.gl.viewport(0, 0, depthTexture.width, depthTexture.height)
        dc.gl.clear(GL_DEPTH_BUFFER_BIT)

        // Terrain casters, sphere-culled per cascade. Face culling off: terrain winding
        // inverts from the sun's POV and would reject the sun-facing slopes - the occluders.
        if (dc.shadowState?.isTerrainCastingEnabled != false) {
            dc.gl.disable(GL_CULL_FACE)
            for (idx in 0 until dc.drawableTerrainCount) {
                val terrain = dc.getDrawableTerrain(idx)
                val terrainOrigin = terrain.vertexOrigin
                val terrainRadius = terrain.boundingSphereRadius
                if (terrainRadius > 0.0 && !cascade.intersectsSphere(terrainOrigin, terrainRadius)) continue
                // Terrain under 3D-Tile mesh coverage must not cast: the globe's elevation
                // surface is an independent height source that commonly sits ABOVE the
                // photogrammetry mesh, blanketing whole tile regions in false shadow.
                if (terrainCoverageSkip[idx]) continue
                if (!terrain.useVertexPointAttrib(dc, 0 /*vertexPoint*/)) continue
                scratchMatrix.copy(cascade.lightProjectionView)
                scratchMatrix.multiplyByTranslation(terrainOrigin.x, terrainOrigin.y, terrainOrigin.z)
                program.loadModelviewProjection(scratchMatrix)
                terrain.drawTriangles(dc)
            }
            dc.gl.enable(GL_CULL_FACE)
        }

        // Shape casters; activeCascade routes their matrix loads through this cascade.
        activeCascade = cascade
        drawShapeCasters(dc)
        activeCascade = null

        // Debug: coarse-sample the map; all-1.0 = no caster wrote this cascade.
        if ((dc.shadowState?.debugShadowMode ?: 0) != 0) {
            val size = depthTexture.width
            val patch = ByteArray(16 * 16 * 4)
            var minDepth = Float.MAX_VALUE
            var writtenSamples = 0
            for (gy in 0 until 4) for (gx in 0 until 4) {
                dc.gl.readPixels(size * (gx * 2 + 1) / 8 - 8, size * (gy * 2 + 1) / 8 - 8, 16, 16, GL_DEPTH_COMPONENT, GL_FLOAT, patch)
                for (i in 0 until 256) {
                    val bits = (patch[i * 4].toInt() and 0xFF) or
                        ((patch[i * 4 + 1].toInt() and 0xFF) shl 8) or
                        ((patch[i * 4 + 2].toInt() and 0xFF) shl 16) or
                        ((patch[i * 4 + 3].toInt() and 0xFF) shl 24)
                    val v = Float.fromBits(bits)
                    if (v < minDepth) minDepth = v
                    if (v < 1f) writtenSamples++
                }
            }
            log(INFO, "DrawableShadow cascade$cascadeIndex depth min=$minDepth written=$writtenSamples/4096")
        }
    }

    /**
     * Precomputes, once per frame, which terrain tiles to skip as casters: those
     * INTERSECTING any 3D-Tile mesh coverage region. Intersection is deliberate — coverage
     * arrives as multiple sectors, and a tile straddling two of them is contained by
     * neither, so a containment test lets it cast and darken the mesh below. The cost is
     * that terrain bordering a tileset stops casting onto it.
     */
    private fun computeTerrainCoverageSkips(dc: DrawContext) {
        val count = dc.drawableTerrainCount
        if (terrainCoverageSkip.size < count) terrainCoverageSkip = BooleanArray(count)
        val regions = dc.groundCoverageRegions
        for (idx in 0 until count) {
            var skip = false
            if (dc.hasGroundCoverageMask) {
                val sector = dc.getDrawableTerrain(idx).sector
                for (i in regions.indices) if (regions[i].intersects(sector)) { skip = true; break }
            }
            terrainCoverageSkip[idx] = skip
        }
    }

    /**
     * Iterates the non-terrain drawable queue and dispatches every [ShadowCaster] to cast
     * its geometry into the active cascade's depth framebuffer. Shape drawables that opt
     * in by implementing [ShadowCaster] (currently [DrawableShape], [DrawableMesh],
     * [DrawableCollada], the 3D-Tile mesh / points drawables) participate. Surface decals,
     * screen sprites, sightline volumes, lambdas, and the depth pass itself are skipped.
     *
     * Casters whose [ShadowCaster.shadowCasterCenter] is non-null are sphere-tested against
     * the active cascade's light-eye AABB (see
     * [ShadowState.CascadeState.intersectsSphere]) so a near-camera shape doesn't waste time
     * rasterising into the far cascade. Casters that don't expose bounds (radius `<= 0`) are
     * dispatched into every cascade — the safe default.
     */
    protected open fun drawShapeCasters(dc: DrawContext) {
        val queue = dc.drawableQueue ?: return
        val cascade = activeCascade ?: return
        for (i in 0 until queue.count) {
            val drawable = queue.getDrawable(i)
            if (drawable !is ShadowCaster) continue
            val center = drawable.shadowCasterCenter
            if (center != null && !cascade.intersectsSphere(center, drawable.shadowCasterRadius)) continue
            drawable.drawShadowDepth(dc, this)
        }
    }

    /**
     * Composes the active cascade's `lightView * modelMatrix` and loads it into the depth-pass
     * program. Casters call this once before each draw call.
     */
    fun loadCasterMatrix(modelMatrix: Matrix4) {
        val cascade = activeCascade ?: return
        scratchMatrix.copy(cascade.lightProjectionView)
        scratchMatrix.multiplyByMatrix(modelMatrix)
        if (alphaCasterActive) alphaDepthProgram?.loadModelviewProjection(scratchMatrix)
        else depthProgram?.loadModelviewProjection(scratchMatrix)
    }

    /**
     * Switches the depth pass to the alpha-tested caster program for a cutout-textured
     * sub-draw: binds [texture] on unit 0 and loads [cutoff]. The caller binds its own
     * texcoord attribute (location 1) and calls [endAlphaMaskedCaster] afterwards.
     * Returns false - leaving the opaque program active - when the variant is unavailable
     * or the texture fails to bind (the sub-draw then casts a solid silhouette).
     */
    fun beginAlphaMaskedCaster(dc: DrawContext, texture: Texture, cutoff: Float): Boolean {
        val program = alphaDepthProgram ?: return false
        if (!program.useProgram(dc)) return false
        alphaCasterActive = true
        if (!texture.bindTexture(dc)) {
            endAlphaMaskedCaster(dc)
            return false
        }
        program.loadAlphaCutoff(cutoff)
        return true
    }

    /** Restores the opaque depth program after an alpha-masked sub-draw. */
    fun endAlphaMaskedCaster(dc: DrawContext) {
        alphaCasterActive = false
        depthProgram?.useProgram(dc)
    }

    /**
     * Convenience for casters whose vertex buffer is stored relative to a translated origin
     * in world coordinates (the common case — terrain tiles, shape vertex origins).
     */
    fun loadCasterTranslation(x: Double, y: Double, z: Double) {
        val program = depthProgram ?: return
        val cascade = activeCascade ?: return
        scratchMatrix.copy(cascade.lightProjectionView)
        scratchMatrix.multiplyByTranslation(x, y, z)
        program.loadModelviewProjection(scratchMatrix)
    }

    /**
     * Renders a [DrawShapeState]'s filled-triangle primitives into the active cascade.
     * Mirrors [DrawableSightline.drawShapeStateOccluder] – the depth pass shader and matrix
     * conventions are identical.
     */
    fun drawShapeStateOccluder(dc: DrawContext, state: DrawShapeState, vertexStride: Int) {
        if (state.vertexBuffer?.bindBuffer(dc) != true) return
        if (state.elementBuffer?.bindBuffer(dc) != true) return
        loadCasterTranslation(state.vertexOrigin.x, state.vertexOrigin.y, state.vertexOrigin.z)
        dc.gl.vertexAttribPointer(0 /*vertexPoint*/, 3, GL_FLOAT, false, vertexStride, 0)
        val cullFaceDisabled = !state.enableCullFace
        if (cullFaceDisabled) dc.gl.disable(GL_CULL_FACE)
        for (idx in 0 until state.primCount) {
            val prim = state.prims[idx]
            if (prim.mode == GL_TRIANGLES || prim.mode == GL_TRIANGLE_STRIP || prim.mode == GL_TRIANGLE_FAN) {
                dc.gl.drawElements(prim.mode, prim.count, prim.type, prim.offset)
            }
        }
        if (cullFaceDisabled) dc.gl.enable(GL_CULL_FACE)
    }
}
