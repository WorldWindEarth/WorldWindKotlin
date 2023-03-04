package earth.worldwind.layer.atmosphere

import earth.worldwind.draw.DrawContext
import earth.worldwind.draw.Drawable
import earth.worldwind.geom.Vec3
import earth.worldwind.render.buffer.FloatBufferObject
import earth.worldwind.render.buffer.ShortBufferObject
import earth.worldwind.util.Pool
import earth.worldwind.util.kgl.*
import kotlin.jvm.JvmStatic

class DrawableSkyAtmosphere : Drawable {
    var vertexPoints: FloatBufferObject? = null
    var triStripElements: ShortBufferObject? = null
    val lightDirection = Vec3()
    var globeRadius = 0.0
    var atmosphereAltitude = 0.0
    var program: SkyProgram? = null
    private var pool: Pool<DrawableSkyAtmosphere>? = null

    companion object {
        @JvmStatic
        fun obtain(pool: Pool<DrawableSkyAtmosphere>): DrawableSkyAtmosphere {
            val instance = pool.acquire() ?: DrawableSkyAtmosphere() // get an instance from the pool
            instance.pool = pool
            return instance
        }
    }

    override fun recycle() {
        program = null
        vertexPoints = null
        triStripElements = null
        pool?.release(this)
        pool = null
    }

    override fun draw(dc: DrawContext) {
        val program = program ?: return  // program unspecified
        if (!program.useProgram(dc)) return  // program failed to build
        if (vertexPoints?.bindBuffer(dc) != true) return  // vertex buffer unspecified or failed to bind
        val triStripElements = triStripElements ?: return // element buffer unspecified
        if (!triStripElements.bindBuffer(dc)) return  // element buffer failed to bind

        // Use the render context's globe radius and atmosphere altitude.
        program.loadAtmosphereParams(globeRadius, atmosphereAltitude)

        // Use the draw context's eye point.
        program.loadEyePoint(dc.eyePoint)

        // Use this layer's light direction.
        program.loadLightDirection(lightDirection)

        // Use the vertex origin for the sky ellipsoid.
        program.loadVertexOrigin(0.0, 0.0, 0.0)

        // Use the draw context's modelview projection matrix.
        program.loadModelviewProjection(dc.modelviewProjection)

        // Use the sky's vertex point attribute.
        dc.gl.vertexAttribPointer(0 /*vertexPoint*/, 3, GL_FLOAT, false, 0, 0)

        // Draw the inside of the sky without writing to the depth buffer.
        dc.gl.depthMask(false)
        dc.gl.frontFace(GL_CW)
        dc.gl.drawElements(GL_TRIANGLE_STRIP, triStripElements.byteCount / 2, GL_UNSIGNED_SHORT, 0)

        // Restore the default WorldWind OpenGL state.
        dc.gl.depthMask(true)
        dc.gl.frontFace(GL_CCW)
    }
}