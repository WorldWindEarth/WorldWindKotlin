package earth.worldwind.draw

import earth.worldwind.geom.Matrix4
import earth.worldwind.render.Color
import earth.worldwind.render.program.BasicShaderProgram
import earth.worldwind.util.Pool
import kotlin.jvm.JvmStatic

open class DrawableSurfaceColor protected constructor(): Drawable {
    val color = Color()
    var opacity = 1.0f
    var program: BasicShaderProgram? = null
    private var pool: Pool<DrawableSurfaceColor>? = null
    private val mvpMatrix = Matrix4()

    companion object {
        val KEY = DrawableSurfaceColor::class

        @JvmStatic
        fun obtain(pool: Pool<DrawableSurfaceColor>): DrawableSurfaceColor {
            val instance = pool.acquire() ?: DrawableSurfaceColor()
            instance.pool = pool
            return instance
        }
    }

    override fun recycle() {
        program = null
        pool?.release(this)
        pool = null
    }

    override fun draw(dc: DrawContext) {
        val program = program ?: return // program unspecified
        if (!program.useProgram(dc)) return // program failed to build

        // Configure the program to draw the specified color.
        program.enableTexture(false)
        program.loadColor(color)
        program.loadOpacity(opacity)
        for (idx in 0 until dc.drawableTerrainCount) {
            // Get the drawable terrain associated with the draw context.
            val terrain = dc.getDrawableTerrain(idx)

            // Use the terrain's vertex point attribute.
            if (!terrain.useVertexPointAttrib(dc, 0 /*vertexPoint*/)) continue  // vertex buffer failed to bind

            // Use the draw context's modelview projection matrix, transformed to terrain local coordinates.
            val terrainOrigin = terrain.vertexOrigin
            mvpMatrix.copy(dc.modelviewProjection)
            mvpMatrix.multiplyByTranslation(terrainOrigin.x, terrainOrigin.y, terrainOrigin.z)
            program.loadModelviewProjection(mvpMatrix)

            // Draw the terrain as triangles.
            terrain.drawTriangles(dc)
        }
    }
}