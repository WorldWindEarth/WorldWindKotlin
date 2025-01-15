package earth.worldwind.draw

import earth.worldwind.geom.Range
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Vec3
import earth.worldwind.globe.Globe
import earth.worldwind.render.buffer.BufferObject
import earth.worldwind.util.Pool
import earth.worldwind.util.kgl.GL_FLOAT
import earth.worldwind.util.kgl.GL_LINES
import earth.worldwind.util.kgl.GL_TRIANGLE_STRIP
import earth.worldwind.util.kgl.GL_UNSIGNED_SHORT
import kotlin.jvm.JvmStatic

open class BasicDrawableTerrain protected constructor(): DrawableTerrain {
    override var offset = Globe.Offset.Center
    override val sector = Sector()
    override val vertexOrigin = Vec3()
    val lineElementRange = Range()
    val triStripElementRange = Range()
    var vertexPoints: BufferObject? = null
    var vertexHeights: BufferObject? = null
    var vertexTexCoords: BufferObject? = null
    var elements: BufferObject? = null
    private var pool: Pool<BasicDrawableTerrain>? = null

    companion object {
        @JvmStatic
        fun obtain(pool: Pool<BasicDrawableTerrain>): BasicDrawableTerrain {
            val instance = pool.acquire() ?: BasicDrawableTerrain()
            instance.pool = pool
            return instance
        }
    }

    override fun recycle() {
        vertexPoints = null
        vertexHeights = null
        vertexTexCoords = null
        elements = null
        pool?.release(this)
        pool = null
    }

    override fun useVertexPointAttrib(dc: DrawContext, attribLocation: Int): Boolean {
        val bufferBound = vertexPoints?.bindBuffer(dc) ?: false
        if (bufferBound) dc.gl.vertexAttribPointer(attribLocation, 3, GL_FLOAT, false, 0, 0)
        return bufferBound
    }

    override fun useVertexHeightsAttrib(dc: DrawContext, attribLocation: Int): Boolean {
        val bufferBound = vertexHeights?.bindBuffer(dc) ?: false
        if (bufferBound) dc.gl.vertexAttribPointer(attribLocation, 1, GL_FLOAT, false, 0, 0)
        return bufferBound
    }

    override fun useVertexTexCoordAttrib(dc: DrawContext, attribLocation: Int): Boolean {
        val bufferBound = vertexTexCoords?.bindBuffer(dc) ?: false
        if (bufferBound) dc.gl.vertexAttribPointer(attribLocation, 2, GL_FLOAT, false, 0, 0)
        return bufferBound
    }

    override fun drawLines(dc: DrawContext): Boolean {
        val bufferBound = elements?.bindBuffer(dc) ?: false
        if (bufferBound) dc.gl.drawElements(
            GL_LINES, lineElementRange.length,
            GL_UNSIGNED_SHORT, lineElementRange.lower * 2
        )
        return bufferBound
    }

    override fun drawTriangles(dc: DrawContext): Boolean {
        val bufferBound = elements?.bindBuffer(dc) ?: false
        if (bufferBound) dc.gl.drawElements(
            GL_TRIANGLE_STRIP, triStripElementRange.length,
            GL_UNSIGNED_SHORT, triStripElementRange.lower * 2
        )
        return bufferBound
    }

    override fun draw(dc: DrawContext) {
        drawTriangles(dc)
    }
}