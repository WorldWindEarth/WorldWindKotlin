package earth.worldwind.draw

import earth.worldwind.geom.Angle.Companion.POS90
import earth.worldwind.geom.Matrix4
import earth.worldwind.render.Color
import earth.worldwind.render.program.SightlineMomentsBlurProgram
import earth.worldwind.render.program.SightlineMomentsProgram
import earth.worldwind.render.program.SightlineProgram
import earth.worldwind.render.program.SightlineProgramCube
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
    val visibleColor = Color(0f, 0f, 0f, 0f)
    val occludedColor = Color(0f, 0f, 0f, 0f)
    var program: SightlineProgram? = null
    /**
     * Cube-map MSM receiver shader for the omnidirectional path. When set together with
     * [omnidirectional] = `true` and [momentsProgram], the omni render switches from per-face
     * 2D moments + 5 receiver passes to a single cube-map depth pass + single receiver pass
     * with `samplerCube`. Hardware seamless cube filtering eliminates the bottom-side seam
     * mismatch that the 2D path showed as a square contour. Directional path ignores this.
     */
    var programCube: SightlineProgramCube? = null
    /**
     * Depth-pass shader for the Variance Shadow Mapping path. Writes `(d, d^2)` into the
     * moments framebuffer's colour attachment so the receiver in [program] can apply
     * Chebyshev's inequality to a filtered tap. Must be set alongside [program]; without it,
     * the depth pass is skipped and no sightline output reaches the screen.
     */
    var momentsProgram: SightlineMomentsProgram? = null
    /**
     * Separable Gaussian blur applied to the moments texture between depth and occlusion
     * passes. Without it, Chebyshev's variance support is one bilinear footprint - too
     * narrow to bridge depth-pass triangles and the receiver still sees mesh-aligned
     * stripes. With it, variance is computed over a wider kernel and the silhouette
     * dissolves into a smooth gradient. May be `null` to skip the blur (fastest, but with
     * the stripe artefacts).
     */
    var momentsBlurProgram: SightlineMomentsBlurProgram? = null
    /**
     * Spacing between adjacent taps in the moments-blur kernel, measured in moments-FBO
     * texels. The 5-tap binomial kernel covers `[-2, +2]` taps along each axis, so the
     * effective blur radius is `2 * momentsBlurTexelSpacing` texels in each direction
     * (per pass). Larger spacing widens the soft band; smaller spacing sharpens the
     * shadow but risks reintroducing mesh-aligned stripes if it falls below the typical
     * depth-pass triangle width.
     */
    var momentsBlurTexelSpacing = 2f
    private var pool: Pool<DrawableSightline>? = null
    private val sightlineView = Matrix4()
    private val matrix = Matrix4()
    private val cubeMapProjection = Matrix4()
    /**
     * Per-face eye-to-world rotation for the omnidirectional sightline. Built to match the
     * OpenGL canonical cube-map face convention so the depth-pass output texels align with
     * the s,t coordinates that `samplerCube` reads back: e.g. POS_X uses `up = -Y, right = -Z`,
     * which makes a fragment at world direction `(1, 0, 1)` rasterise to clip `(-1, 0)` =
     * texel `(0, 0.5)` - the same texel `samplerCube` looks up for that direction. Without
     * this match the cube-map sample returns moments stored for a different direction and the
     * receiver computes nonsense.
     *
     * Order is `[POS_X, NEG_X, POS_Y, NEG_Y, NEG_Z]`; POS_Z is omitted (terrain is never
     * visible looking up). Each matrix is the rotation that, concatenated with `centerTransform`
     * and inverted, gives the per-face view matrix.
     */
    private val cubeMapFace = arrayOf(
        // POS_X: eye_x=-Z, eye_y=-Y, eye_z=-X (eye_-z=+X). Looking at +X.
        Matrix4().set(
            0.0,  0.0, -1.0, 0.0,
            0.0, -1.0,  0.0, 0.0,
           -1.0,  0.0,  0.0, 0.0,
            0.0,  0.0,  0.0, 1.0,
        ),
        // NEG_X: eye_x=+Z, eye_y=-Y, eye_z=+X. Looking at -X.
        Matrix4().set(
            0.0,  0.0,  1.0, 0.0,
            0.0, -1.0,  0.0, 0.0,
            1.0,  0.0,  0.0, 0.0,
            0.0,  0.0,  0.0, 1.0,
        ),
        // POS_Y: eye_x=+X, eye_y=+Z, eye_z=-Y. Looking at +Y.
        Matrix4().set(
            1.0,  0.0,  0.0, 0.0,
            0.0,  0.0, -1.0, 0.0,
            0.0,  1.0,  0.0, 0.0,
            0.0,  0.0,  0.0, 1.0,
        ),
        // NEG_Y: eye_x=+X, eye_y=-Z, eye_z=+Y. Looking at -Y.
        Matrix4().set(
            1.0,  0.0,  0.0, 0.0,
            0.0,  0.0,  1.0, 0.0,
            0.0, -1.0,  0.0, 0.0,
            0.0,  0.0,  0.0, 1.0,
        ),
        // NEG_Z: eye_x=-X, eye_y=-Y, eye_z=+Z. Looking at -Z (down).
        Matrix4().set(
           -1.0,  0.0,  0.0, 0.0,
            0.0, -1.0,  0.0, 0.0,
            0.0,  0.0,  1.0, 0.0,
            0.0,  0.0,  0.0, 1.0,
        ),
    )

    /**
     * GL cube-map face attachment target parallel to [cubeMapFace]. Used to attach the
     * matching face of the moments cube map as the FBO colour attachment per face render.
     * NEG_Z lives at constant `0x851A` (`POSITIVE_X + 5`), not `+ 4`, because POS_Z occupies
     * the slot at `+ 4` and we skip it.
     */
    private val cubeMapFaceTarget = intArrayOf(
        GL_TEXTURE_CUBE_MAP_POSITIVE_X,
        GL_TEXTURE_CUBE_MAP_NEGATIVE_X,
        GL_TEXTURE_CUBE_MAP_POSITIVE_Y,
        GL_TEXTURE_CUBE_MAP_NEGATIVE_Y,
        GL_TEXTURE_CUBE_MAP_NEGATIVE_Z,
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
        momentsBlurTexelSpacing = 2f
        program = null
        programCube = null
        momentsProgram = null
        momentsBlurProgram = null
        pool?.release(this)
        pool = null
    }

    override fun draw(dc: DrawContext) {
        // TODO accumulate only the visible terrain, which can be used in both passes
        // TODO give terrain a bounding box, test with a frustum set using depthviewProjection
        // TODO construct matrix using separate horizontal and vertical fov
        cubeMapProjection.setToPerspectiveProjection(1, 1, fieldOfView, 1.0, range.toDouble())
        if (omnidirectional) drawOmniCubeMap(dc) else drawDirectional(dc)
    }

    /**
     * Omnidirectional render via a cube-map moments texture. Renders 5 face depth passes
     * (each attaches the matching cube-map face as the FBO colour attachment, so all faces
     * share the same FBO and depth attachment), then a single receiver pass that samples
     * the cube map with `samplerCube`. Hardware seamless cube filtering smooths face seams,
     * and the single receiver pass has no clipMask or per-face ownership logic - the
     * square-contour artefact of the 2D path is absent by construction.
     */
    private fun drawOmniCubeMap(dc: DrawContext) {
        val cube = programCube ?: return
        if (!cube.useProgram(dc)) return
        cube.loadRange(range)
        cube.loadColor(visibleColor, occludedColor)
        if (drawSceneDepthCube(dc)) drawSceneOcclusionCube(dc, cube)
    }

    /**
     * Directional render: forward face plus optional close-range fill passes. All passes
     * share [fieldOfView] so their side planes coincide and the ground footprint reads as
     * a single triangle. Each fill rotates the view a further `fieldOfView` down so its top
     * edge meets the previous face's bottom:
     *   fill 1 covers pitch `[-3·fov/2 .. -fov/2]`
     *   fill 2 covers pitch `[-5·fov/2 .. -3·fov/2]` (very close ground)
     * [directionalFillPasses] selects how many fills to render - DirectionalSightline
     * gates this on the sightline's altitude.
     */
    private fun drawDirectional(dc: DrawContext) {
        val program = program ?: return
        if (!program.useProgram(dc)) return
        program.loadRange(range)
        program.loadColor(visibleColor, occludedColor)

        sightlineView.copy(centerTransform) // should contain rotation for sightline direction
        sightlineView.invertOrthonormal()
        renderFace(dc)

        val fills = directionalFillPasses.coerceIn(0, 2)
        for (i in 1..fills) {
            sightlineView.copy(centerTransform)
            sightlineView.multiplyByRotation(1.0, 0.0, 0.0, fieldOfView * -i)
            sightlineView.invertOrthonormal()
            renderFace(dc)
        }
    }

    private fun renderFace(dc: DrawContext) {
        if (drawSceneDepth(dc)) {
            blurMoments(dc)
            drawSceneOcclusion(dc)
        }
    }

    protected open fun drawSceneDepth(dc: DrawContext): Boolean {
        val moments = momentsProgram ?: return false
        if (!moments.useProgram(dc)) return false
        moments.loadRange(range)
        // The cube map projection is constant per-frame across all faces. The view matrix
        // (sightlineView * tile-translate) is what changes per face / per tile.
        moments.loadProjection(cubeMapProjection)
        try {
            val framebuffer = dc.momentsFramebuffer
            if (!framebuffer.bindFramebuffer(dc)) return false // framebuffer failed to bind

            // Clear colour to the d=1 sentinel for all four moments: (1, 1, 1, 1). At a
            // sentinel pixel the moments describe a degenerate distribution at the far
            // plane; the receiver's Hamburger reconstruction then maps any receiver depth
            // d <= 1 to "in front of the distribution" => occlusion = 0 (visible). Depth
            // attachment cleared to 1 (default far) for terrain triangle ordering.
            val colorTexture = framebuffer.getAttachedTexture(GL_COLOR_ATTACHMENT0)
            dc.gl.viewport(0, 0, colorTexture.width, colorTexture.height)
            dc.gl.clearColor(1f, 1f, 1f, 1f)
            dc.gl.clear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
            // Restore the GL clear-colour state immediately so the next frame's main-
            // framebuffer clear (which doesn't set clearColor itself) doesn't paint the
            // whole scene with the moments sentinel.
            dc.gl.clearColor(0f, 0f, 0f, 0f)

            // Disable blending: MSM stores raw moments d^k whose alpha channel (d^4) varies
            // and is < 1 over most of the depth range. With blending enabled, fragments would
            // alpha-blend with the cleared sentinel instead of overwriting it, producing
            // near-sentinel results. (VSM was immune because it wrote a constant alpha = 1.0.)
            dc.gl.disable(GL_BLEND)
            // Polygon offset disambiguates depth-attachment ordering for any overlapping
            // terrain triangles within the same face. It does NOT affect the colour output
            // (moments are gl_Position.w * invRange, unaffected by polygon offset) - terrain
            // self-shadow is prevented separately by the receiver's `fragmentDepth <= M1`
            // early-out, which fires whenever receiver and stored M1 represent the same surface.
            dc.gl.enable(GL_POLYGON_OFFSET_FILL)
            dc.gl.polygonOffset(4f, 4f)
            for (idx in 0 until dc.drawableTerrainCount) {
                // Get the drawable terrain associated with the draw context.
                val terrain = dc.getDrawableTerrain(idx)
                val terrainOrigin = terrain.vertexOrigin

                // Use the terrain's vertex point attribute.
                if (!terrain.useVertexPointAttrib(dc, 0 /*vertexPoint*/)) continue // vertex buffer failed to bind

                // Per-tile modelview = sightlineView * translate(terrainOrigin). Projection
                // was loaded once at the top of the depth pass.
                matrix.copy(sightlineView)
                matrix.multiplyByTranslation(terrainOrigin.x, terrainOrigin.y, terrainOrigin.z)
                moments.loadModelview(matrix)

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
            // the moments texture so they cast shadows alongside terrain. Surface decals and
            // screen-space sprites (placemarks, leader lines) are intentionally excluded.
            // Dispatch is via SightlineOccluder; loadOccluderMatrix / loadOccluderTranslation /
            // drawShapeStateOccluder route through `momentsProgram` since that's the active
            // depth shader.
            drawShapesDepth(dc)
        } finally {
            // Restore the default World Wind OpenGL state.
            dc.bindFramebuffer(KglFramebuffer.NONE)
            dc.gl.viewport(dc.viewport.x, dc.viewport.y, dc.viewport.width, dc.viewport.height)
            dc.gl.enable(GL_BLEND)
            dc.gl.disable(GL_POLYGON_OFFSET_FILL)
            dc.gl.polygonOffset(0f, 0f)
        }
        return true
    }

    /**
     * Runs a separable Gaussian blur over the moments texture. Pass 1 reads from the
     * moments FBO and writes a horizontally-blurred result into the blur ping-pong FBO.
     * Pass 2 reads back from the ping-pong FBO and writes the vertically-blurred (i.e.
     * fully separable-Gaussian-blurred) result into the original moments FBO so the
     * occlusion pass samples the blurred version with no further plumbing. No-op if
     * [momentsBlurProgram] is null.
     */
    protected open fun blurMoments(dc: DrawContext) {
        val blur = momentsBlurProgram ?: return
        if (!blur.useProgram(dc)) return

        val momentsFb = dc.momentsFramebuffer
        val tempFb = dc.momentsBlurFramebuffer
        val momentsTex = momentsFb.getAttachedTexture(GL_COLOR_ATTACHMENT0)
        val tempTex = tempFb.getAttachedTexture(GL_COLOR_ATTACHMENT0)
        val texelStep = 1f / momentsTex.width.toFloat()

        // Bind the unit-square buffer for the fullscreen quad. Same buffer used by other
        // fullscreen-quad shaders in the codebase.
        if (!dc.unitSquareBuffer.bindBuffer(dc)) return
        dc.gl.vertexAttribPointer(0 /*vertexPoint*/, 2, GL_FLOAT, false, 0, 0)
        // Blur passes are full-screen replaces, not blends. Disable depth test (no
        // geometry to sort) and blend (the moments d^k channels include alpha = d^4 < 1
        // over most of the depth range, which would alpha-blend with the cleared sentinel
        // instead of overwriting it - same write-failure mode as the depth pass).
        dc.gl.disable(GL_DEPTH_TEST)
        dc.gl.disable(GL_BLEND)
        try {
            // Pass 1: horizontal. moments -> tempFb.
            if (!tempFb.bindFramebuffer(dc)) return
            dc.gl.viewport(0, 0, momentsTex.width, momentsTex.height)
            dc.activeTextureUnit(GL_TEXTURE0)
            if (!momentsTex.bindTexture(dc)) return
            blur.loadBlurDirection(momentsBlurTexelSpacing * texelStep, 0f)
            dc.gl.drawArrays(GL_TRIANGLE_STRIP, 0, 4)

            // Pass 2: vertical. tempFb -> moments.
            if (!momentsFb.bindFramebuffer(dc)) return
            dc.gl.viewport(0, 0, momentsTex.width, momentsTex.height)
            if (!tempTex.bindTexture(dc)) return
            blur.loadBlurDirection(0f, momentsBlurTexelSpacing * texelStep)
            dc.gl.drawArrays(GL_TRIANGLE_STRIP, 0, 4)
        } finally {
            // Restore the system framebuffer + camera viewport so the subsequent
            // occlusion pass writes to the main framebuffer, not the moments FBO that
            // pass 2 left bound.
            dc.bindFramebuffer(KglFramebuffer.NONE)
            dc.gl.viewport(dc.viewport.x, dc.viewport.y, dc.viewport.width, dc.viewport.height)
            dc.gl.enable(GL_DEPTH_TEST)
            dc.gl.enable(GL_BLEND)
            dc.defaultTexture.bindTexture(dc)
        }
    }

    /**
     * Iterates the non-terrain drawable queue and dispatches every [SightlineOccluder] to
     * cast its geometry into the moments framebuffer. The built-in [DrawableShape] /
     * [DrawableMesh] / [DrawableCollada] all implement the interface; custom drawables opt
     * in the same way. Surface decals, screen-space sprites, line drawables and lambdas
     * don't implement [SightlineOccluder] and are therefore skipped.
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
     * and loads the result into the moments depth-pass program's mvpMatrix uniform.
     * [SightlineOccluder] implementations call this once before each draw call. VSM uses
     * [momentsProgram] as the depth-pass shader, so the matrix is loaded into that program
     * (not into [program], which is the receiver shader).
     */
    fun loadOccluderMatrix(modelMatrix: Matrix4) {
        val program = momentsProgram ?: return
        matrix.copy(sightlineView)
        matrix.multiplyByMatrix(modelMatrix)
        program.loadModelview(matrix)
    }

    /**
     * Convenience for occluders whose vertices are stored relative to a translated origin in
     * world coordinates (the common case — terrain tiles, shape vertex origins, etc.). Composes
     * `cubeMapProjection × sightlineView × translate(x, y, z)` and loads the result into the
     * moments depth-pass program's mvpMatrix uniform.
     */
    fun loadOccluderTranslation(x: Double, y: Double, z: Double) {
        val program = momentsProgram ?: return
        matrix.copy(sightlineView)
        matrix.multiplyByTranslation(x, y, z)
        program.loadModelview(matrix)
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

    /**
     * Cube-map depth pass for the omnidirectional path. Each iteration of the loop attaches
     * the matching cube-map face to the FBO's colour attachment (`GL_COLOR_ATTACHMENT0`),
     * clears it to the d=1 sentinel, and rasterises the visible terrain (and 3D occluder
     * shapes) using a per-face view matrix. The depth attachment is shared across all five
     * face renders - it's cleared per face so each render has a fresh depth buffer for
     * triangle ordering. The omitted POS_Z face is left in its allocated state and masked
     * out at the receiver, so we don't waste a clear pass on it.
     */
    protected open fun drawSceneDepthCube(dc: DrawContext): Boolean {
        val moments = momentsProgram ?: return false
        if (!moments.useProgram(dc)) return false
        moments.loadRange(range)
        moments.loadProjection(cubeMapProjection)

        val framebuffer = dc.momentsCubeMapFramebuffer
        val cubeTexture = dc.momentsCubeMapTexture
        val size = cubeTexture.width
        if (!framebuffer.bindFramebuffer(dc)) return false

        try {
            // d=1 sentinel for the moments clear.
            dc.gl.clearColor(1f, 1f, 1f, 1f)
            dc.gl.viewport(0, 0, size, size)
            // Disable blend (MSM moments need direct write; alpha=d^4 < 1 would alpha-blend
            // with the cleared sentinel and bias the result).
            dc.gl.disable(GL_BLEND)

            for (i in cubeMapFace.indices) {
                // Re-attach this cube face as the colour attachment for this pass.
                framebuffer.attachTexture(dc, cubeTexture, GL_COLOR_ATTACHMENT0, cubeMapFaceTarget[i])
                dc.gl.clear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)

                // Per-face view matrix in centerTransform-local frame.
                sightlineView.copy(centerTransform)
                sightlineView.multiplyByMatrix(cubeMapFace[i])
                sightlineView.invertOrthonormal()

                // Polygon offset disambiguates depth ordering between overlapping terrain
                // triangles within this face's view.
                dc.gl.enable(GL_POLYGON_OFFSET_FILL)
                dc.gl.polygonOffset(4f, 4f)
                for (idx in 0 until dc.drawableTerrainCount) {
                    val terrain = dc.getDrawableTerrain(idx)
                    val terrainOrigin = terrain.vertexOrigin
                    if (!terrain.useVertexPointAttrib(dc, 0 /*vertexPoint*/)) continue
                    matrix.copy(sightlineView)
                    matrix.multiplyByTranslation(terrainOrigin.x, terrainOrigin.y, terrainOrigin.z)
                    moments.loadModelview(matrix)
                    terrain.drawTriangles(dc)
                }

                // Shapes act only as occluders, never as receivers - no self-shadow bias.
                dc.gl.disable(GL_POLYGON_OFFSET_FILL)
                drawShapesDepth(dc)
            }
        } finally {
            dc.bindFramebuffer(KglFramebuffer.NONE)
            dc.gl.viewport(dc.viewport.x, dc.viewport.y, dc.viewport.width, dc.viewport.height)
            dc.gl.clearColor(0f, 0f, 0f, 0f)
            dc.gl.enable(GL_BLEND)
            dc.gl.disable(GL_POLYGON_OFFSET_FILL)
            dc.gl.polygonOffset(0f, 0f)
        }
        return true
    }

    /**
     * Cube-map occlusion (receiver) pass. Single full-terrain pass that samples the moments
     * cube map with the fragment's centerTransform-local direction (`sightlineLocalPos`).
     * Hardware seamless cube filtering interpolates across face boundaries with no per-face
     * clip frustum or ownership logic, so seams are continuous and there is no double-blend.
     */
    protected open fun drawSceneOcclusionCube(dc: DrawContext, program: SightlineProgramCube) {
        if (!program.useProgram(dc)) return
        // sightlineLocalMatrix = inv(centerTransform) * translate(terrainOrigin); compute
        // inv(centerTransform) once into [sightlineView], then per-tile compose translate.
        sightlineView.copy(centerTransform)
        sightlineView.invertOrthonormal()

        dc.activeTextureUnit(GL_TEXTURE0)
        val cubeTexture = dc.momentsCubeMapTexture
        if (!cubeTexture.bindTexture(dc)) return
        try {
            for (idx in 0 until dc.drawableTerrainCount) {
                val terrain = dc.getDrawableTerrain(idx)
                val terrainOrigin = terrain.vertexOrigin
                if (!terrain.useVertexPointAttrib(dc, 0 /*vertexPoint*/)) continue

                matrix.copy(dc.modelviewProjection)
                matrix.multiplyByTranslation(terrainOrigin.x, terrainOrigin.y, terrainOrigin.z)
                program.loadModelviewProjection(matrix)

                matrix.copy(sightlineView)
                matrix.multiplyByTranslation(terrainOrigin.x, terrainOrigin.y, terrainOrigin.z)
                program.loadSightlineLocalMatrix(matrix)

                terrain.drawTriangles(dc)
            }
        } finally {
            // Unbind the cube map at the active unit to avoid feedback when the next frame's
            // depth pass attaches the cube map's faces to the FBO. [DrawContext.bindTexture]
            // only tracks the GL_TEXTURE_2D binding, so unbind the cube target manually.
            dc.gl.bindTexture(GL_TEXTURE_CUBE_MAP, KglTexture.NONE)
        }
    }

    protected open fun drawSceneOcclusion(dc: DrawContext) {
        val program = program ?: return
        // Switch back to the receiver shader; the depth pass left momentsProgram bound.
        // The receiver's range/colour uniforms were set once at the top of [draw]; since
        // uniforms live on the program object, they're still here when we re-bind. Only
        // per-tile MVP and SLP need reloading inside the loop below.
        if (!program.useProgram(dc)) return
        try {
            // Bind the moments colour attachment as the depth sampler. The shader still
            // calls the uniform `depthSampler` for legacy reasons but reads (M1, M2) from
            // the .rg channels and applies Chebyshev's inequality.
            dc.activeTextureUnit(GL_TEXTURE0)
            val momentsTexture = dc.momentsFramebuffer.getAttachedTexture(GL_COLOR_ATTACHMENT0)
            if (!momentsTexture.bindTexture(dc)) return // framebuffer texture failed to bind
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
            // Unbind moments texture to avoid feedback loop next frame.
            dc.defaultTexture.bindTexture(dc)
        }
    }
}