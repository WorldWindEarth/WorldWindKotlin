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
    private lateinit var pointBufferKey: String

    fun updatePointBufferKey() { pointBufferKey = "TerrainTile.points.$tileKey.${pointBufferSequence++}" }

    fun getPointBuffer(rc: RenderContext) = rc.getBufferObject(pointBufferKey) {
        FloatBufferObject(GL_ARRAY_BUFFER, points)
    }

    companion object {
        private var pointBufferSequence = 0L // must be static to avoid cache collisions when a tile instances is destroyed and re-created
    }
}