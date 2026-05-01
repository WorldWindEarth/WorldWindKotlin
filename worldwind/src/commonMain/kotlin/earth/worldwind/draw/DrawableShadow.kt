package earth.worldwind.draw

import earth.worldwind.geom.Matrix4
import earth.worldwind.layer.shadow.ShadowCaster
import earth.worldwind.layer.shadow.ShadowState
import earth.worldwind.render.program.SightlineMomentsBlurProgram
import earth.worldwind.render.program.SightlineMomentsProgram
import earth.worldwind.util.Pool
import earth.worldwind.util.kgl.*
import kotlin.jvm.JvmStatic

/**
 * Runs the cascaded shadow map depth pass for the directional sun-shadow pipeline.
 * Enqueued each frame by [earth.worldwind.layer.shadow.ShadowLayer] in the BACKGROUND drawable
 * group, so its [draw] runs before any receiver shape / terrain draw and the cascade moments
 * textures are populated by the time receivers sample them in [DrawContext.shadowCascadeFramebuffer].
 *
 * The depth pass reuses [SightlineMomentsProgram] (Hamburger 4-moment) — its `perpDepth =
 * -ep.z * invRange` formulation is what we want once [ShadowState.CascadeState.lightView]
 * has been translated to put the cascade's near plane at light-eye-z = 0. After the depth
 * pass for each cascade, a separable Gaussian blur ([SightlineMomentsBlurProgram]) widens
 * the variance support so the receiver-side Cholesky reconstruction produces a smooth soft
 * shadow band rather than mesh-aligned stripes.
 *
 * Casters are dispatched via the [ShadowCaster] interface, parallel to
 * [SightlineOccluder] but with this drawable's per-cascade matrices.
 */
open class DrawableShadow protected constructor() : Drawable {
    var momentsProgram: SightlineMomentsProgram? = null
    var momentsBlurProgram: SightlineMomentsBlurProgram? = null

    /** Per-cascade Gaussian-blur tap spacing in shadow-map texels. `[0, 0, 0]` skips the blur. */
    var momentsBlurTexelSpacing: FloatArray = floatArrayOf(0f, 0f, 0f)

    /**
     * Active cascade matrix during caster dispatch. Set by [draw] for each cascade so
     * [loadCasterMatrix] / [loadCasterTranslation] can compose model transforms against
     * the right `lightView`. Null outside the depth pass.
     */
    private var activeCascade: ShadowState.CascadeState? = null

    private val scratchMatrix = Matrix4()
    private var pool: Pool<DrawableShadow>? = null

    companion object {
        val KEY = DrawableShadow::class

        @JvmStatic
        fun obtain(pool: Pool<DrawableShadow>): DrawableShadow {
            val instance = pool.acquire() ?: DrawableShadow()
            instance.pool = pool
            return instance
        }
    }

    override fun recycle() {
        momentsProgram = null
        momentsBlurProgram = null
        activeCascade = null
        pool?.release(this)
        pool = null
    }

    override fun draw(dc: DrawContext) {
        // Per-frame snapshot owned by [Frame] - the layer's scratch state is mutated on the
        // main thread while this draw runs on the GL thread.
        val state = dc.shadowState ?: return
        val moments = momentsProgram ?: return
        if (!moments.useProgram(dc)) return

        // Both PCF and MSM need the cascade depth at full RGBA32F precision; without sized
        // formats (GLES2 / WebGL1) disable the receivers so they don't sample garbage.
        if (!dc.gl.supportsSizedTextureFormats) {
            state.algorithm = null
            return
        }

        try {
            for (i in 0 until state.cascadeCount) {
                val cascade = state.cascades[i]
                if (!cascade.isValid) continue
                drawCascadeDepth(dc, i, cascade)
                if (i < momentsBlurTexelSpacing.size && momentsBlurTexelSpacing[i] > 0f) {
                    blurCascadeMoments(dc, i)
                }
            }
        } finally {
            // Restore default WorldWind state regardless of which cascade failed.
            dc.bindFramebuffer(KglFramebuffer.NONE)
            dc.gl.viewport(dc.viewport.x, dc.viewport.y, dc.viewport.width, dc.viewport.height)
            dc.gl.enable(GL_BLEND)
            dc.gl.disable(GL_POLYGON_OFFSET_FILL)
            dc.gl.polygonOffset(0f, 0f)
            activeCascade = null
        }
    }

    /**
     * Renders terrain and shape casters into the moments framebuffer for one cascade.
     * Same conventions as [DrawableSightline.drawSceneDepth]: clear to the d=1 sentinel,
     * disable blend (raw moment writes overwrite, never blend), polygon-offset the terrain
     * to disambiguate overlapping triangles, then dispatch caster shapes without offset
     * (they only act as occluders, never receive their own shadow during this pass).
     */
    protected open fun drawCascadeDepth(dc: DrawContext, cascadeIndex: Int, cascade: ShadowState.CascadeState) {
        val moments = momentsProgram ?: return
        // Re-bind the moments program. After the previous cascade's [blurCascadeMoments] ran,
        // the blur program is the active GL program. Loading moments uniforms (or running
        // [terrain.drawTriangles]) without re-binding here writes to / draws through the wrong
        // program -- locations from `moments` resolve to bogus slots on the blur program and
        // the GL call is silently discarded, which manifests as garbage moments in cascades 1
        // and 2 (cascade 0 happens to work because [draw] binds moments before the loop).
        if (!moments.useProgram(dc)) return
        moments.loadProjection(cascade.lightProjection)
        moments.loadRange(cascade.range.toFloat())

        val framebuffer = dc.shadowCascadeFramebuffer(cascadeIndex)
        if (!framebuffer.bindFramebuffer(dc)) return

        val colorTexture = framebuffer.getAttachedTexture(GL_COLOR_ATTACHMENT0)
        dc.gl.viewport(0, 0, colorTexture.width, colorTexture.height)
        // Sentinel clear: d=1 in all four moments. Receivers' Cholesky reconstruction of a
        // (1,1,1,1) moment vector returns occlusion=0 (visible) for any receiver depth ≤ 1,
        // i.e. fragments outside the cascade footprint read as fully lit.
        dc.gl.clearColor(1f, 1f, 1f, 1f)
        dc.gl.clear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        // Restore default clear colour so the next main-pass clear doesn't repaint the screen
        // with the moments sentinel.
        dc.gl.clearColor(0f, 0f, 0f, 0f)
        // Direct moment writes; no alpha blending against the sentinel.
        dc.gl.disable(GL_BLEND)

        // Terrain casters. No polygon offset here even though the sightline pipeline uses
        // one: that offset is only meant to disambiguate depth-attachment ordering between
        // overlapping LoD-seam tiles, but at our scale it also suppresses terrain-on-terrain
        // shadows (mountain → valley) because the offset is comparable to the natural
        // mountain-vs-valley `perpDepth` separation. Self-shadow acne is prevented by the
        // receiver's [msmDepthBias] in [ShadowReceiverGlsl], not by polygon offset.
        //
        // Per-cascade tile cull: tiles whose bounding sphere falls entirely outside the
        // active cascade's footprint are skipped. Globe-scale terrain has hundreds of tiles
        // visible in the camera frustum but only a fraction reach into close cascades —
        // big win on cascade 0/1 in particular. Tiles without bounds (radius `<= 0`) are
        // dispatched into every cascade as before.
        //
        // Disable face culling for the terrain depth pass. WorldWind's terrain mesh winds
        // opposite to what GL_CULL_FACE_BACK from the sun's POV expects, so back-face culling
        // rejects the sun-facing slopes — the actual occluders — and only the anti-sun slopes
        // reach the moments FBO; their depth then equals every receiver's own depth and no
        // self-shadow registers. Without culling, both sides rasterise and the depth test
        // keeps the smaller perpDepth regardless of winding.
        dc.gl.disable(GL_CULL_FACE)
        for (idx in 0 until dc.drawableTerrainCount) {
            val terrain = dc.getDrawableTerrain(idx)
            val terrainOrigin = terrain.vertexOrigin
            val terrainRadius = terrain.boundingSphereRadius
            if (terrainRadius > 0.0 && !cascade.intersectsSphere(terrainOrigin, terrainRadius)) continue
            if (!terrain.useVertexPointAttrib(dc, 0 /*vertexPoint*/)) continue
            scratchMatrix.copy(cascade.lightView)
            scratchMatrix.multiplyByTranslation(terrainOrigin.x, terrainOrigin.y, terrainOrigin.z)
            moments.loadModelview(scratchMatrix)
            terrain.drawTriangles(dc)
        }
        dc.gl.enable(GL_CULL_FACE)

        // Shape casters. activeCascade routes loadCasterMatrix / loadCasterTranslation calls
        // through this cascade's lightView for the duration of the dispatch.
        activeCascade = cascade
        drawShapeCasters(dc)
        activeCascade = null
    }

    /**
     * Iterates the non-terrain drawable queue and dispatches every [ShadowCaster] to cast
     * its geometry into the active cascade's moments framebuffer. Shape drawables that opt
     * in by implementing [ShadowCaster] (currently [DrawableShape], [DrawableMesh],
     * [DrawableCollada]) participate. Surface decals, screen sprites, sightline volumes,
     * lambdas, and the depth pass itself are skipped.
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
     * Composes the active cascade's `lightView * modelMatrix` and loads it into the moments
     * depth-pass program. Casters call this once before each draw call.
     */
    fun loadCasterMatrix(modelMatrix: Matrix4) {
        val program = momentsProgram ?: return
        val cascade = activeCascade ?: return
        scratchMatrix.copy(cascade.lightView)
        scratchMatrix.multiplyByMatrix(modelMatrix)
        program.loadModelview(scratchMatrix)
    }

    /**
     * Convenience for casters whose vertex buffer is stored relative to a translated origin
     * in world coordinates (the common case — terrain tiles, shape vertex origins).
     */
    fun loadCasterTranslation(x: Double, y: Double, z: Double) {
        val program = momentsProgram ?: return
        val cascade = activeCascade ?: return
        scratchMatrix.copy(cascade.lightView)
        scratchMatrix.multiplyByTranslation(x, y, z)
        program.loadModelview(scratchMatrix)
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

    /**
     * Separable Gaussian blur on this cascade's moments texture. Pass 1: cascade FBO →
     * shared shadow-blur FBO (horizontal). Pass 2: shadow-blur FBO → cascade FBO (vertical).
     * Receiver shaders sample the cascade FBO directly so no further plumbing is needed.
     * No-op when [momentsBlurProgram] is null.
     */
    protected open fun blurCascadeMoments(dc: DrawContext, cascadeIndex: Int) {
        val blur = momentsBlurProgram ?: return
        if (!blur.useProgram(dc)) return

        val cascadeFb = dc.shadowCascadeFramebuffer(cascadeIndex)
        val cascadeTex = cascadeFb.getAttachedTexture(GL_COLOR_ATTACHMENT0)
        // Match the blur ping-pong's size to this cascade so unit-square UVs read the full
        // texture in both passes — mixed-size sampling would either alias or read uninitialised
        // memory beyond the rendered subregion.
        val tempFb = dc.shadowBlurFramebuffer(cascadeTex.width)
        val tempTex = tempFb.getAttachedTexture(GL_COLOR_ATTACHMENT0)
        val texelStep = 1f / cascadeTex.width.toFloat()
        val tapSpacing = momentsBlurTexelSpacing[cascadeIndex] * texelStep

        if (!dc.unitSquareBuffer.bindBuffer(dc)) return
        dc.gl.vertexAttribPointer(0 /*vertexPoint*/, 2, GL_FLOAT, false, 0, 0)
        dc.gl.disable(GL_DEPTH_TEST)
        dc.gl.disable(GL_BLEND)
        try {
            // Pass 1: horizontal. cascadeFb -> tempFb.
            if (!tempFb.bindFramebuffer(dc)) return
            dc.gl.viewport(0, 0, cascadeTex.width, cascadeTex.height)
            dc.activeTextureUnit(GL_TEXTURE0)
            if (!cascadeTex.bindTexture(dc)) return
            blur.loadBlurDirection(tapSpacing, 0f)
            dc.gl.drawArrays(GL_TRIANGLE_STRIP, 0, 4)

            // Pass 2: vertical. tempFb -> cascadeFb.
            if (!cascadeFb.bindFramebuffer(dc)) return
            dc.gl.viewport(0, 0, cascadeTex.width, cascadeTex.height)
            if (!tempTex.bindTexture(dc)) return
            blur.loadBlurDirection(0f, tapSpacing)
            dc.gl.drawArrays(GL_TRIANGLE_STRIP, 0, 4)
        } finally {
            dc.gl.enable(GL_DEPTH_TEST)
            // Caller's enable(GL_BLEND) at the end of [draw] re-enables blending.
            dc.defaultTexture.bindTexture(dc)
        }
    }
}
