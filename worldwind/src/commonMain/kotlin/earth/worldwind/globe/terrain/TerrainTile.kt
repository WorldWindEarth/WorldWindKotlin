package earth.worldwind.globe.terrain

import earth.worldwind.geom.Sector
import earth.worldwind.geom.Vec3
import earth.worldwind.render.RenderContext
import earth.worldwind.render.buffer.FloatBufferObject
import earth.worldwind.util.Level
import earth.worldwind.util.Tile
import earth.worldwind.util.kgl.GL_ARRAY_BUFFER
import kotlinx.datetime.Instant

/**
 * Represents a portion of a globe's terrain. Applications typically do not interact directly with this class.
 */
open class TerrainTile(sector: Sector, level: Level, row: Int, column: Int): Tile(sector, level, row, column) {
    /**
     * Minimum elevation value used by the BasicTessellator to determine the terrain mesh edge extension depth (skirt).
     * This value is scaled by the vertical exaggeration when the terrain is generated.
     */
    val heights by lazy { FloatArray( level.tileWidth * level.tileHeight) }
    val points by lazy { FloatArray((level.tileWidth + 2) * (level.tileHeight + 2) * 3) }
    val origin = Vec3()
    internal val minTerrainElevation = -Short.MAX_VALUE.toFloat()
    internal var heightTimestamp = Instant.DISTANT_PAST
    internal var verticalExaggeration = 0.0f
    var sortOrder = 0.0
        protected set
    private lateinit var pointBufferKey: String

    open fun prepare(rc: RenderContext) {
        val globe = rc.globe
        val tileWidth = level.tileWidth
        val tileHeight = level.tileHeight
        val timestamp = globe.elevationModel.timestamp
        if (timestamp !== heightTimestamp) {
            val heights = heights
            heights.fill(0f)
            globe.elevationModel.getHeightGrid(sector, tileWidth, tileHeight, heights)
        }
        val ve = rc.verticalExaggeration.toFloat()
        if (ve != verticalExaggeration || timestamp !== heightTimestamp) {
            val origin = origin
            val heights = heights
            val points = points
            val borderHeight = minTerrainElevation * ve
            val rowStride = (tileWidth + 2) * 3
            globe.geographicToCartesian(sector.centroidLatitude, sector.centroidLongitude, 0.0, origin)
            globe.geographicToCartesianGrid(
                sector, tileWidth, tileHeight, heights, ve, origin, points, rowStride + 3, rowStride
            )
            globe.geographicToCartesianBorder(
                sector, tileWidth + 2, tileHeight + 2, borderHeight, origin, points
            )
            updatePointBufferKey()
        }
        heightTimestamp = timestamp
        verticalExaggeration = ve
        sortOrder = drawSortOrder(rc)
    }

    fun getPointBuffer(rc: RenderContext) = rc.getBufferObject(pointBufferKey) {
        FloatBufferObject(GL_ARRAY_BUFFER, points)
    }

    protected fun updatePointBufferKey() { pointBufferKey = "TerrainTile.points.$tileKey.${pointBufferSequence++}" }

    companion object {
        private var pointBufferSequence = 0L // must be static to avoid cache collisions when a tile instances is destroyed and re-created
    }
}