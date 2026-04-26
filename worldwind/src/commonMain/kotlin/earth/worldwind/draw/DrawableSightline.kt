package earth.worldwind.draw

import earth.worldwind.geom.Angle.Companion.NEG90
import earth.worldwind.geom.Angle.Companion.POS180
import earth.worldwind.geom.Angle.Companion.POS90
import earth.worldwind.geom.Matrix4
import earth.worldwind.render.Color
import earth.worldwind.render.Framebuffer
import earth.worldwind.render.program.SightlineCompositeProgram
import earth.worldwind.render.program.SightlineProgram
import earth.worldwind.util.Pool
import earth.worldwind.util.kgl.*
import kotlin.jvm.JvmStatic

open class DrawableSightline protected constructor() : Drawable {
    var omnidirectional = false
    var fieldOfView = POS90
    val centerTransform = Matrix4()
    var range = 0f
    /**
     * Number of close-range fill passes for the directional sightline (0, 1, or 2; clamped at
     * draw time). Each fill rotates [fieldOfView] further down so the side planes coincide
     * with the forward pass and the projected ground footprint reads as one triangle. Ignored
     * when [omnidirectional] is `true`.
     */
    var directionalFillPasses = 0
    /**
     * Radius of the bilateral filter used to dissolve the staircase silhouette that the
     * triangulated terrain mesh imprints on the visibility map. In screen pixels of the
     * sightline framebuffer (which matches the camera viewport). 0 disables the filter
     * - the visibility texture is composited as-is, restoring the pre-filter look.
     */
    var bilateralRadius = 6f
    /**
     * Standard deviation of the depth-difference Gaussian used by the bilateral filter,
     * in normalised post-projection depth units (`[0, 1]`). Smaller values make the
     * filter more edge-preserving (kernel taps falling on nearer/further surfaces are
     * weighted down more aggressively). Tuned conservatively so close-range geometry
     * edges aren't visibly bled across.
     */
    var bilateralDepthSigma = 0.0005f
    val visibleColor = Color(0f, 0f, 0f, 0f)
    val occludedColor = Color(0f, 0f, 0f, 0f)
    var program: SightlineProgram? = null
    /**
     * Bilateral-blur compositing program used to smooth the silhouette of the visibility
     * texture before alpha-blending it onto the main framebuffer. May be `null` to skip
     * compositing entirely - in that case the visibility passes still run but their
     * output is left in the offscreen framebuffer and never reaches the screen.
     */
    var compositeProgram: SightlineCompositeProgram? = null
    private var pool: Pool<DrawableSightline>? = null
    private val sightlineView = Matrix4()
    private val matrix = Matrix4()
    private val cubeMapProjection = Matrix4()
    private val cubeMapFace = arrayOf(
        Matrix4().setToRotation(0.0, 0.0, 1.0, NEG90).multiplyByRotation(1.0, 0.0, 0.0, POS90),  // positive X
        Matrix4().setToRotation(0.0, 0.0, 1.0, POS90).multiplyByRotation(1.0, 0.0, 0.0, POS90),  // negative X
        Matrix4().setToRotation(1.0, 0.0, 0.0, POS90),  // positive Y
        Matrix4().setToRotation(0.0, 0.0, 1.0, POS180).multiplyByRotation(1.0, 0.0, 0.0, POS90),  // negative Y
        /*Matrix4().setToRotation(1.0, 0.0, 0.0, POS180),*/ // positive Z, intentionally omitted as terrain is never visible when looking up
        Matrix4() // negative Z
    )

    companion object {
        val KEY = DrawableSightline::class

        @JvmStatic
        fun obtain(pool: Pool<DrawableSightline>): DrawableSightline {
            val instance = pool.acquire() ?: DrawableSightline()
            instance.pool = pool
            return instance
        }
    }

    override fun recycle() {
        visibleColor.set(0f, 0f, 0f, 0f)
        occludedColor.set(0f, 0f, 0f, 0f)
        directionalFillPasses = 0
        bilateralRadius = 6f
        bilateralDepthSigma = 0.0005f
        program = null
        compositeProgram = null
        pool?.release(this)
        pool = null
    }

    override fun draw(dc: DrawContext) {
        val program = program ?: return // program unspecified
        if (!program.useProgram(dc)) return // program failed to build

        // Use the drawable's color.
        program.loadRange(range)
        program.loadColor(visibleColor, occludedColor)

        // Redirect every occlusion pass to an offscreen visibility framebuffer so we can
        // bilateral-filter the silhouette before compositing it onto the main framebuffer.
        // The terrain mesh's triangle edges produce axis-aligned staircase silhouettes in
        // the raw visibility map; the post-process kernel dissolves them while preserving
        // genuine geometry edges via depth-aware weighting.
        val viewportWidth = dc.viewport.width
        val viewportHeight = dc.viewport.height
        val visibilityFb = dc.sightlineFramebuffer(viewportWidth, viewportHeight)
        if (!visibilityFb.bindFramebuffer(dc)) return // framebuffer failed to bind
        dc.gl.viewport(0, 0, viewportWidth, viewportHeight)
        dc.gl.clearColor(0f, 0f, 0f, 0f)
        dc.gl.clear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)

        // TODO accumulate only the visible terrain, which can be used in both passes
        // TODO give terrain a bounding box, test with a frustum set using depthviewProjection
        // TODO construct matrix using separate horizontal and vertical fov
        cubeMapProjection.setToPerspectiveProjection(1, 1, fieldOfView, 1.0, range.toDouble())
        if (omnidirectional) {
            for (i in cubeMapFace.indices) {
                sightlineView.copy(centerTransform)
                sightlineView.multiplyByMatrix(cubeMapFace[i])
                sightlineView.invertOrthonormal()
                runFacePass(dc, visibilityFb, viewportWidth, viewportHeight)
            }
        } else {
            // Forward face plus optional close-range fill passes. All passes share
            // [fieldOfView] so their side planes coincide and the ground footprint reads as
            // a single triangle. Each fill rotates the view a further `fieldOfView` down so
            // its top edge meets the previous face's bottom:
            //   fill 1 covers pitch [-3·fov/2 .. -fov/2]
            //   fill 2 covers pitch [-5·fov/2 .. -3·fov/2] (very close ground)
            // [directionalFillPasses] selects how many fills to render — DirectionalSightline
            // gates this on the sightline's altitude.
            sightlineView.copy(centerTransform) // should contain rotation for sightline direction
            sightlineView.invertOrthonormal()
            runFacePass(dc, visibilityFb, viewportWidth, viewportHeight)

            val fills = directionalFillPasses.coerceIn(0, 2)
            for (i in 1..fills) {
                sightlineView.copy(centerTransform)
                sightlineView.multiplyByRotation(1.0, 0.0, 0.0, fieldOfView * -i)
                sightlineView.invertOrthonormal()
                runFacePass(dc, visibilityFb, viewportWidth, viewportHeight)
            }
        }

        // Bind the system framebuffer back and run the bilateral compositor over the
        // accumulated visibility texture. Skip if no compositor was provided - the
        // visibility map stays in the offscreen FBO and the user sees no sightline at all,
        // which is the right failure mode (better than uncomposited debug output).
        dc.bindFramebuffer(KglFramebuffer.NONE)
        dc.gl.viewport(dc.viewport.x, dc.viewport.y, viewportWidth, viewportHeight)
        drawComposite(dc, visibilityFb, viewportWidth, viewportHeight)
    }

    /**
     * Runs one face of the sightline (depth + occlusion). [drawSceneDepth] toggles the
     * scratch framebuffer for its own use, so this helper rebinds the visibility FBO
     * afterwards and clears its depth attachment. Clearing depth between faces prevents
     * each face's terrain from failing the depth test against the previous face's
     * (identical-depth) terrain when the depth function is `LESS`.
     */
    private fun runFacePass(dc: DrawContext, visibilityFb: Framebuffer, w: Int, h: Int) {
        if (!drawSceneDepth(dc)) return
        visibilityFb.bindFramebuffer(dc)
        dc.gl.viewport(0, 0, w, h)
        dc.gl.clear(GL_DEPTH_BUFFER_BIT)
        drawSceneOcclusion(dc)
    }

    /**
     * Renders a full-screen quad over the main framebuffer, sampling the visibility
     * texture and applying the bilateral filter. Disables depth test for the quad and
     * relies on whatever blend state the renderer already configured (premultiplied
     * `ONE, ONE_MINUS_SRC_ALPHA`).
     */
    private fun drawComposite(dc: DrawContext, visibilityFb: Framebuffer, w: Int, h: Int) {
        val composite = compositeProgram ?: return
        if (!composite.useProgram(dc)) return

        composite.loadViewportSize(w, h)
        composite.loadBilateralRadius(bilateralRadius)
        composite.loadDepthSigma(bilateralDepthSigma)

        // Bind the visibility colour to TEX0 and depth to TEX1; the shader has already
        // wired its samplers to those units in initProgram.
        dc.activeTextureUnit(GL_TEXTURE0)
        if (!visibilityFb.getAttachedTexture(GL_COLOR_ATTACHMENT0).bindTexture(dc)) return
        dc.activeTextureUnit(GL_TEXTURE1)
        if (!visibilityFb.getAttachedTexture(GL_DEPTH_ATTACHMENT).bindTexture(dc)) return

        // Bind the unit-square vertex buffer and configure the single position attribute.
        if (!dc.unitSquareBuffer.bindBuffer(dc)) return
        dc.gl.vertexAttribPointer(0 /*vertexPoint*/, 2, GL_FLOAT, false, 0, 0)

        // The composite pass writes a full-screen quad; depth test would just discard it.
        dc.gl.disable(GL_DEPTH_TEST)
        try {
            dc.gl.drawArrays(GL_TRIANGLE_STRIP, 0, 4)
        } finally {
            dc.gl.enable(GL_DEPTH_TEST)
            // Unbind FBO textures from sampler units to avoid feedback loops on the next
            // frame's sightline pass.
            dc.activeTextureUnit(GL_TEXTURE1)
            dc.defaultTexture.bindTexture(dc)
            dc.activeTextureUnit(GL_TEXTURE0)
            dc.defaultTexture.bindTexture(dc)
        }
    }

    protected open fun drawSceneDepth(dc: DrawContext): Boolean {
        val program = program ?: return false
        try {
            val framebuffer = dc.scratchFramebuffer
            if (!framebuffer.bindFramebuffer(dc)) return false // framebuffer failed to bind

            // Clear the framebuffer.
            val depthTexture = framebuffer.getAttachedTexture(GL_DEPTH_ATTACHMENT)
            dc.gl.viewport(0, 0, depthTexture.width, depthTexture.height)
            dc.gl.clear(GL_DEPTH_BUFFER_BIT)

            // Draw only depth values offset slightly away from the viewer. The offset
            // prevents terrain from self-shadowing in the occlusion pass (terrain is both
            // occluder here and receiver there, so its depth needs a small bias to win the
            // comparison against itself). The values are kept as low as possible: at long
            // sightline range, 16-bit depth precision is coarse enough that one unit of
            // offset corresponds to several physical metres, so a too-large bias makes
            // terrain just past a ridge fall inside the offset zone and report as
            // visible. (1, 1) is enough to cover floating-point round-off without
            // hiding the back side of opposing slopes.
            dc.gl.colorMask(r = false, g = false, b = false, a = false)
            dc.gl.enable(GL_POLYGON_OFFSET_FILL)
            dc.gl.polygonOffset(1f, 1f)
            for (idx in 0 until dc.drawableTerrainCount) {
                // Get the drawable terrain associated with the draw context.
                val terrain = dc.getDrawableTerrain(idx)
                val terrainOrigin = terrain.vertexOrigin

                // Use the terrain's vertex point attribute.
                if (!terrain.useVertexPointAttrib(dc, 0 /*vertexPoint*/)) continue // vertex buffer failed to bind

                // Draw the terrain onto one face of the cube map, from the sightline's point of view.
                matrix.setToMultiply(cubeMapProjection, sightlineView)
                matrix.multiplyByTranslation(terrainOrigin.x, terrainOrigin.y, terrainOrigin.z)
                program.loadModelviewProjection(matrix)

                // Draw the terrain as triangles.
                terrain.drawTriangles(dc)
            }

            // Disable the polygon offset before rasterizing 3D shapes. Shapes only act as
            // occluders, never as receivers (the occlusion pass renders only terrain), so
            // they don't need a self-shadow bias - and at long range the bias is the
            // dominant term in normalised-depth precision, which makes the slope-pushed
            // shape depth land beyond nearby terrain and silently swallows the shadow.
            dc.gl.disable(GL_POLYGON_OFFSET_FILL)

            // Rasterize world-space 3D drawables (filled shapes, meshes, COLLADA models) into
            // the depth texture so they cast shadows alongside terrain. Surface decals and
            // screen-space sprites (placemarks, leader lines) are intentionally excluded.
            drawShapesDepth(dc)
        } finally {
            // Restore the default World Wind OpenGL state.
            dc.bindFramebuffer(KglFramebuffer.NONE)
            dc.gl.viewport(dc.viewport.x, dc.viewport.y, dc.viewport.width, dc.viewport.height)
            dc.gl.colorMask(r = true, g = true, b = true, a = true)
            dc.gl.disable(GL_POLYGON_OFFSET_FILL)
            dc.gl.polygonOffset(0f, 0f)
        }
        return true
    }

    /**
     * Iterates the non-terrain drawable queue and dispatches every [SightlineOccluder] to
     * cast its geometry into the sightline's depth texture. The built-in
     * [DrawableShape] / [DrawableMesh] / [DrawableCollada] all implement the interface;
     * custom drawables opt in the same way. Surface decals, screen-space sprites, line
     * drawables and lambdas don't implement [SightlineOccluder] and are therefore skipped.
     */
    protected open fun drawShapesDepth(dc: DrawContext) {
        val queue = dc.drawableQueue ?: return
        for (i in 0 until queue.count) {
            val drawable = queue.getDrawable(i)
            if (drawable is SightlineOccluder) drawable.drawSightlineDepth(dc, this)
        }
    }

    /**
     * Composes the active sightline matrices (`cubeMapProjection × sightlineView × modelMatrix`)
     * and loads the result into the sightline depth-pass program's mvpMatrix uniform.
     * [SightlineOccluder] implementations call this once before each draw call.
     */
    fun loadOccluderMatrix(modelMatrix: Matrix4) {
        val program = program ?: return
        matrix.setToMultiply(cubeMapProjection, sightlineView)
        matrix.multiplyByMatrix(modelMatrix)
        program.loadModelviewProjection(matrix)
    }

    /**
     * Convenience for occluders whose vertices are stored relative to a translated origin in
     * world coordinates (the common case — terrain tiles, shape vertex origins, etc.). Composes
     * `cubeMapProjection × sightlineView × translate(x, y, z)` and loads the result into the
     * sightline depth-pass program's mvpMatrix uniform.
     */
    fun loadOccluderTranslation(x: Double, y: Double, z: Double) {
        val program = program ?: return
        matrix.setToMultiply(cubeMapProjection, sightlineView)
        matrix.multiplyByTranslation(x, y, z)
        program.loadModelviewProjection(matrix)
    }

    /**
     * Renders the filled-triangle primitives of a [DrawShapeState] into the sightline's depth
     * texture. Shared between [DrawableShape] (interleaved attributes, passes its own
     * [DrawShapeState.vertexStride]) and [DrawableMesh] (positions packed tightly in their
     * own buffer, stride 0). Line-mode primitives within an otherwise filled state are
     * skipped, and the state's [DrawShapeState.enableCullFace] preference is respected.
     */
    fun drawShapeStateOccluder(dc: DrawContext, state: DrawShapeState, vertexStride: Int) {
        if (state.vertexBuffer?.bindBuffer(dc) != true) return
        if (state.elementBuffer?.bindBuffer(dc) != true) return
        loadOccluderTranslation(state.vertexOrigin.x, state.vertexOrigin.y, state.vertexOrigin.z)
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

    protected open fun drawSceneOcclusion(dc: DrawContext) {
        val program = program ?: return
        try {
            // Make multi-texture unit 0 active.
            dc.activeTextureUnit(GL_TEXTURE0)
            val depthTexture = dc.scratchFramebuffer.getAttachedTexture(GL_DEPTH_ATTACHMENT)
            if (!depthTexture.bindTexture(dc)) return // framebuffer texture failed to bind
            for (idx in 0 until dc.drawableTerrainCount) {
                // Get the drawable terrain associated with the draw context.
                val terrain = dc.getDrawableTerrain(idx)
                val terrainOrigin = terrain.vertexOrigin

                // Use the terrain's vertex point attribute.
                if (!terrain.useVertexPointAttrib(dc, 0 /*vertexPoint*/)) continue  // vertex buffer failed to bind

                // Use the draw context's modelview projection matrix, transformed to terrain local coordinates.
                matrix.copy(dc.modelviewProjection)
                matrix.multiplyByTranslation(terrainOrigin.x, terrainOrigin.y, terrainOrigin.z)
                program.loadModelviewProjection(matrix)

                // Map the terrain into one face of the cube map, from the sightline's point of view.
                matrix.copy(sightlineView)
                matrix.multiplyByTranslation(terrainOrigin.x, terrainOrigin.y, terrainOrigin.z)
                program.loadSightlineProjection(cubeMapProjection, matrix)

                // Draw the terrain as triangles.
                terrain.drawTriangles(dc)
            }
        } finally {
            // Unbind depth attachment texture to avoid feedback loop
            dc.defaultTexture.bindTexture(dc)
        }
    }
}