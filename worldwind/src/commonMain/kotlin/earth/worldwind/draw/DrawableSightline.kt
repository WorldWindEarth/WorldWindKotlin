package earth.worldwind.draw

import earth.worldwind.geom.Angle.Companion.NEG90
import earth.worldwind.geom.Angle.Companion.POS180
import earth.worldwind.geom.Angle.Companion.POS90
import earth.worldwind.geom.Matrix4
import earth.worldwind.render.Color
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
    val visibleColor = Color(0f, 0f, 0f, 0f)
    val occludedColor = Color(0f, 0f, 0f, 0f)
    var program: SightlineProgram? = null
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
        program = null
        pool?.release(this)
        pool = null
    }

    override fun draw(dc: DrawContext) {
        val program = program ?: return // program unspecified
        if (!program.useProgram(dc)) return // program failed to build

        // Use the drawable's color.
        program.loadRange(range)
        program.loadColor(visibleColor, occludedColor)

        // TODO accumulate only the visible terrain, which can be used in both passes
        // TODO give terrain a bounding box, test with a frustum set using depthviewProjection
        // TODO construct matrix using separate horizontal and vertical fov
        cubeMapProjection.setToPerspectiveProjection(1, 1, fieldOfView, 1.0, range.toDouble())
        if (omnidirectional) {
            for (i in cubeMapFace.indices) {
                sightlineView.copy(centerTransform)
                sightlineView.multiplyByMatrix(cubeMapFace[i])
                sightlineView.invertOrthonormal()
                if (drawSceneDepth(dc)) drawSceneOcclusion(dc)
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
            if (drawSceneDepth(dc)) drawSceneOcclusion(dc)

            val fills = directionalFillPasses.coerceIn(0, 2)
            for (i in 1..fills) {
                sightlineView.copy(centerTransform)
                sightlineView.multiplyByRotation(1.0, 0.0, 0.0, fieldOfView * -i)
                sightlineView.invertOrthonormal()
                if (drawSceneDepth(dc)) drawSceneOcclusion(dc)
            }
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
            // prevents terrain from self-shadowing in the occlusion pass (terrain is
            // both occluder here and receiver there, so its depth needs a small bias to
            // win the depth comparison against itself).
            dc.gl.colorMask(r = false, g = false, b = false, a = false)
            dc.gl.enable(GL_POLYGON_OFFSET_FILL)
            dc.gl.polygonOffset(4f, 4f)
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