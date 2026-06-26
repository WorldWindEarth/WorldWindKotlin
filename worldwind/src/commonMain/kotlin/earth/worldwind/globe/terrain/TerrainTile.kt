package earth.worldwind.globe.terrain

import earth.worldwind.geom.Sector
import earth.worldwind.geom.Vec3
import earth.worldwind.globe.Globe
import earth.worldwind.render.RenderContext
import earth.worldwind.render.buffer.BufferObject
import earth.worldwind.util.Level
import earth.worldwind.util.NumericArray
import earth.worldwind.util.Tile
import earth.worldwind.util.kgl.GL_ARRAY_BUFFER

/**
 * Represents a portion of a globe's terrain. Applications typically do not interact directly with this class.
 */
open class TerrainTile(sector: Sector, level: Level, row: Int, column: Int): Tile(sector, level, row, column) {
    val origin = Vec3()
    val points by lazy { FloatArray((level.tileWidth + 2) * (level.tileHeight + 2) * 3) }
    protected val heights by lazy { FloatArray( (level.tileWidth + 2) * (level.tileHeight + 2)) }
    protected val heightGrid by lazy { FloatArray( level.tileWidth * level.tileHeight) }
    /**
     * Minimum elevation value used by the BasicTessellator to determine the terrain mesh edge extension depth (skirt).
     * This value is scaled by the vertical exaggeration when the terrain is generated.
     */
    protected val minTerrainElevation = -Short.MAX_VALUE.toFloat()
    protected var heightTimestamp = 0L
    protected var globeVE = 0.0
    protected var globeState: Globe.State? = null
    protected var globeOffset: Globe.Offset? = null
    var sortOrder = 0.0
        protected set
    // Stable per-tile keys so the cached BufferObject is reused; re-uploads go via version bump.
    private val pointBufferKey = "TerrainTile.points.$tileKey"
    private val heightBufferKey = "TerrainTile.heights.$tileKey"
    private var pointBufferVersion = 0
    private var heightBufferVersion = 0

    public override val heightLimits get() = super.heightLimits

    open fun prepare(rc: RenderContext) {
        val globe = rc.globe
        val tileWidth = level.tileWidth
        val tileHeight = level.tileHeight
        val timestamp = rc.elevationModelTimestamp
        if (timestamp != heightTimestamp) {
            heightGrid.fill(0f)
            globe.getElevationGrid(sector, tileWidth, tileHeight, heightGrid)
            // Calculate height vertex buffer from height grid
            for (r in 0 until level.tileHeight) for (c in 0 until level.tileWidth) {
                heights[(r + 1) * (level.tileWidth + 2) + c + 1] = heightGrid[r * level.tileWidth + c]
            }
            if (rc.globe.is2D) {
                heightGrid.fill(0f) // Do not show terrain in 2D, but keep height values in vertex for heatmap
                calcHeightLimits(globe) // Force calculate height limits for heatmap
            }
            updateHeightBufferKey()
        }
        val ve = rc.globe.verticalExaggeration
        val state = rc.globeState
        val offset = rc.globe.offset
        if (timestamp != heightTimestamp || ve != globeVE || state != globeState || offset != globeOffset) {
            val rowStride = (tileWidth + 2) * 3
            globe.geographicToCartesian(sector.centroidLatitude, sector.centroidLongitude, 0.0, origin)
            globe.geographicToCartesianGrid(
                sector, tileWidth, tileHeight, heightGrid, origin, points, rowStride + 3, rowStride
            )
            globe.geographicToCartesianBorder(
                sector, tileWidth + 2, tileHeight + 2, minTerrainElevation, origin, points
            )
            updatePointBufferKey()
        }
        heightTimestamp = timestamp
        globeVE = ve
        globeState = state
        globeOffset = offset
        sortOrder = drawSortOrder(rc)
    }

    fun getHeightBuffer(rc: RenderContext) : BufferObject {
        val buffer = rc.getBufferObject(heightBufferKey) { BufferObject(GL_ARRAY_BUFFER, 0) }
        rc.offerGLBufferUpload(heightBufferKey, heightBufferVersion) { NumericArray.Floats(heights) }
        return buffer
    }

    fun getPointBuffer(rc: RenderContext) : BufferObject {
        val buffer = rc.getBufferObject(pointBufferKey) { BufferObject(GL_ARRAY_BUFFER, 0) }
        rc.offerGLBufferUpload(pointBufferKey, pointBufferVersion) { NumericArray.Floats(points) }
        return buffer
    }

    protected open fun updateHeightBufferKey() {
        heightBufferVersion = (++bufferSequence).toInt()
    }

    protected open fun updatePointBufferKey() {
        pointBufferVersion = (++bufferSequence).toInt()
    }

    companion object {
        // Global monotonic counter — a re-created tile's first upload still beats its predecessor's cached version.
        private var bufferSequence = 0L
    }
}