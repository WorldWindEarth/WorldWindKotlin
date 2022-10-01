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
    companion object {
        private var pointBufferSequence = 0L // must be static to avoid cache collisions when a tile instances is destroyed and re-created
    }

    /**
     * Minimum elevation value used by the BasicTessellator to determine the terrain mesh edge extension depth (skirt).
     * This value is scaled by the vertical exaggeration when the terrain is generated.
     */
    var heights: FloatArray? = null
    var points: FloatArray? = null
        set(value) {
            field = value
            pointBufferKey = "TerrainTile.points." + tileKey + "." + pointBufferSequence++
        }
    var origin = Vec3()
    internal val minTerrainElevation = (-Short.MAX_VALUE).toFloat()
    internal var heightTimestamp = Instant.DISTANT_PAST
    internal var verticalExaggeration = 0.0
    private var pointBufferKey: String? = null

    fun getPointBuffer(rc: RenderContext): FloatBufferObject? {
        val points = points ?: return null
        val pointBufferKey = pointBufferKey ?: return null
        // TODO consider a pool of terrain tiles
        // TODO consider a pool of terrain tile vertex buffers
        return rc.getBufferObject(pointBufferKey) { FloatBufferObject(GL_ARRAY_BUFFER, points) }
    }
}