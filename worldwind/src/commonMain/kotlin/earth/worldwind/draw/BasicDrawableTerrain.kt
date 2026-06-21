package earth.worldwind.draw

import earth.worldwind.geom.Range
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Vec3
import earth.worldwind.globe.Globe
import earth.worldwind.render.buffer.BufferObject
import earth.worldwind.util.Pool
import earth.worldwind.util.kgl.GL_ALWAYS
import earth.worldwind.util.kgl.GL_EQUAL
import earth.worldwind.util.kgl.GL_FLOAT
import earth.worldwind.util.kgl.GL_KEEP
import earth.worldwind.util.kgl.GL_LINES
import earth.worldwind.util.kgl.GL_STENCIL_TEST
import earth.worldwind.util.kgl.GL_TRIANGLE_STRIP
import earth.worldwind.util.kgl.GL_UNSIGNED_SHORT
import kotlin.jvm.JvmStatic

open class BasicDrawableTerrain protected constructor(): DrawableTerrain {
    override var offset = Globe.Offset.Center
    override val sector = Sector()
    override val vertexOrigin = Vec3()
    override var boundingSphereRadius: Double = 0.0
    val lineElementRange = Range()
    val triStripElementRange = Range()
    var vertexPoints: BufferObject? = null
    var vertexHeights: BufferObject? = null
    var vertexTexCoords: BufferObject? = null
    var elements: BufferObject? = null
    /** Lazy-cached intersection vs [DrawContext.groundCoverageRegions]. Computed on the
     *  first [drawTriangles] call per frame, reused by subsequent painters; reset by
     *  [recycle]. */
    private var intersectsGroundCoverage: Boolean = false
    private var intersectsGroundCoverageComputed: Boolean = false
    private var pool: Pool<BasicDrawableTerrain>? = null

    companion object {
        val KEY = BasicDrawableTerrain::class

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
        // Reset of the lazy memo (paired with [intersectsGroundCoverageComputed]) — not defensive;
        // without it the next tile inherits the prior tile's coverage answer.
        intersectsGroundCoverage = false
        intersectsGroundCoverageComputed = false
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
        if (!bufferBound) return false
        // Stencil-skip mesh-covered fragments — pick mode included, else the depth readback
        // returns terrain altitude under content rendered through-terrain via the stencil.
        val mask = dc.hasGroundCoverageMask && checkGroundCoverageIntersect(dc)
        if (mask) {
            dc.gl.enable(GL_STENCIL_TEST)
            dc.gl.stencilFunc(GL_EQUAL, 0, GROUND_COVERED_BIT)
            dc.gl.stencilOp(GL_KEEP, GL_KEEP, GL_KEEP)
            dc.gl.stencilMask(0x00)
        }
        try {
            dc.gl.drawElements(
                GL_TRIANGLE_STRIP, triStripElementRange.length,
                GL_UNSIGNED_SHORT, triStripElementRange.lower * 2
            )
        } finally {
            if (mask) {
                dc.gl.disable(GL_STENCIL_TEST)
                dc.gl.stencilFunc(GL_ALWAYS, 0, 0xFF)
                dc.gl.stencilMask(0xFF)
            }
        }
        return true
    }

    override fun draw(dc: DrawContext) {
        drawTriangles(dc)
    }

    /** Lazy intersect vs [DrawContext.groundCoverageRegions]; cached for the frame. */
    private fun checkGroundCoverageIntersect(dc: DrawContext): Boolean {
        if (intersectsGroundCoverageComputed) return intersectsGroundCoverage
        val regions = dc.groundCoverageRegions
        var hit = false
        for (i in regions.indices) {
            if (regions[i].intersects(sector)) {
                hit = true
                break
            }
        }
        intersectsGroundCoverage = hit
        intersectsGroundCoverageComputed = true
        return hit
    }
}