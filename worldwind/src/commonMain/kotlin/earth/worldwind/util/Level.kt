package earth.worldwind.util

import earth.worldwind.geom.Location
import earth.worldwind.geom.Sector
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Represents a level of a specific resolution in a [LevelSet].
 */
open class Level internal constructor(
    /**
     * The LevelSet that this level is a member of.
     */
    val parent: LevelSet,
    /**
     * The level's ordinal in its parent level set.
     */
    val levelNumber: Int,
    /**
     * The geographic width and height of tiles within this level.
     */
    val tileDelta: Location
) {
    /**
     * The width in pixels of the image represented by all tiles in this level set, or the number of sample points in
     * the longitudinal direction of this level set.
     */
    val levelWidth = (parent.tileWidth * parent.sector.deltaLongitude.inDegrees / tileDelta.longitude.inDegrees).roundToInt()
    /**
     * The height in pixels of the image represented by all tiles in this level set, or the number of sample points in
     * the latitudinal direction of this level set.
     */
    val levelHeight = (parent.tileHeight * parent.sector.deltaLatitude.inDegrees / tileDelta.latitude.inDegrees).roundToInt()
    /**
     * The parent LevelSet's tileWidth.
     */
    val tileWidth = parent.tileWidth
    /**
     * The parent LevelSet's tileHeight.
     */
    val tileHeight = parent.tileHeight
    /**
     * The size of pixels or elevation cells within this level, in radians per pixel or per cell.
     */
    val texelSize = tileDelta.longitude.inRadians / parent.tileWidth
    /**
     * Indicates whether this level is the lowest resolution level (level 0) within the parent level set.
     */
    val isFirstLevel get() = levelNumber == 0
    /**
     * Indicates whether this level is the highest resolution level within the parent level set.
     */
    val isLastLevel get() = levelNumber == parent.numLevels - 1
    /**
     * Returns the level whose ordinal occurs immediately before this level's ordinal in the parent level set, or null
     * if this is the fist level.
     */
    val previousLevel get() = parent.level(levelNumber - 1)
    /**
     * Returns the level whose ordinal occurs immediately after this level's ordinal in the parent level set, or null if
     * this is the last level.
     */
    val nextLevel get() = parent.level(levelNumber + 1)

    /**
     * Calculates amount of tiles, which fit specified sector
     *
     * @param sector the desired sector to check tile count
     * @return Number of tiles which fit specified sector at this level
     */
    fun tilesInSector(sector: Sector): Int {
        val tilesPerLat = ceil(sector.deltaLatitude.inDegrees / tileDelta.latitude.inDegrees).toInt()
        val tilesPerLon = ceil(sector.deltaLongitude.inDegrees / tileDelta.longitude.inDegrees).toInt()
        return tilesPerLat * tilesPerLon
    }
}