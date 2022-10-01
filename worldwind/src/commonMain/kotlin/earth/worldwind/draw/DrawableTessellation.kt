package earth.worldwind.draw

import earth.worldwind.geom.Matrix4
import earth.worldwind.render.Color
import earth.worldwind.render.program.BasicShaderProgram
import earth.worldwind.util.Pool
import kotlin.jvm.JvmStatic

open class DrawableTessellation protected constructor(): Drawable {
    val color = Color()
    var program: BasicShaderProgram? = null
    private var pool: Pool<DrawableTessellation>? = null
    private val mvpMatrix = Matrix4()
    private val offsetMvpMatrix = Matrix4()

    companion object {
        @JvmStatic
        fun obtain(pool: Pool<DrawableTessellation>): DrawableTessellation {
            val instance = pool.acquire() ?: DrawableTessellation()
            instance.pool = pool
            return instance
        }
    }

    fun set(program: BasicShaderProgram?, color: Color?) = apply {
        if (color != null) this.color.copy(color) else this.color.set(1f, 1f, 1f, 1f)
        this.program = program
    }

    override fun recycle() {
        program = null
        pool?.release(this)
        pool = null
    }

    override fun draw(dc: DrawContext) {
        val program = program ?: return // program unspecified
        if (!program.useProgram(dc)) return // program failed to build

        // Use the draw context's pick mode.
        program.enablePickMode(dc.isPickMode)

        // Configure the program to draw the specified color.
        program.enableTexture(false)
        program.loadColor(color)

        // Suppress writes to the OpenGL depth buffer.
        dc.gl.depthMask(false)

        // Compute the portion of the modelview projection matrix that remains constant for each tile.
        offsetMvpMatrix.copy(dc.projection)
        offsetMvpMatrix.offsetProjectionDepth(-1.0e-3) // offset this layer's depth values toward the eye
        offsetMvpMatrix.multiplyByMatrix(dc.modelview)
        for (idx in 0 until dc.drawableTerrainCount) {
            // Get the drawable terrain associated with the draw context.
            val terrain = dc.getDrawableTerrain(idx)

            // Use the terrain's vertex point attribute.
            if (!terrain.useVertexPointAttrib(dc, 0 /*vertexPoint*/)) continue  // vertex buffer failed to bind

            // Use the draw context's modelview projection matrix, transformed to terrain local coordinates.
            val terrainOrigin = terrain.vertexOrigin
            mvpMatrix.copy(offsetMvpMatrix)
            mvpMatrix.multiplyByTranslation(terrainOrigin.x, terrainOrigin.y, terrainOrigin.z)
            program.loadModelviewProjection(mvpMatrix)

            // Draw the terrain as lines.
            terrain.drawLines(dc)
        }

        // Restore default WorldWind OpenGL state.
        dc.gl.depthMask(true)
    }
}