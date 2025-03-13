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

open class DrawableSightline protected constructor(): Drawable {
    val centerTransform = Matrix4()
    var range = 0f
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

        // Configure the cube map projection matrix to capture one face of the cube map as far as the sightline's range.
        cubeMapProjection.setToPerspectiveProjection(1, 1, POS90, 1.0, range.toDouble())

        // TODO accumulate only the visible terrain, which can be used in both passes
        // TODO give terrain a bounding box, test with a frustum set using depthviewProjection
        for (i in cubeMapFace.indices) {
            sightlineView.copy(centerTransform)
            sightlineView.multiplyByMatrix(cubeMapFace[i])
            sightlineView.invertOrthonormal()
            if (drawSceneDepth(dc)) drawSceneOcclusion(dc)
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

            // Draw only depth values offset slightly away from the viewer.
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