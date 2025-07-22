package earth.worldwind.util

import earth.worldwind.geom.Angle.Companion.POS90
import earth.worldwind.geom.Angle.Companion.toDegrees
import earth.worldwind.geom.Location
import earth.worldwind.geom.Sector
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log2

/**
 * Configuration values for a multi-resolution, hierarchical collection of tiles organized into levels of increasing
 * resolution.
 */
open class LevelSetConfig {
    /**
     * The sector spanned by the level set.
     */
    val sector = Sector().setFullSphere()
    /**
     * Tile origin for the level set
     */
    val tileOrigin = Sector().setFullSphere()
    /**
     * The geographic width and height of tiles in the first level (the lowest resolution) of the level set.
     */
    var firstLevelDelta = Location(POS90, POS90)
    /**
     * The number of levels in the level set.
     */
    var numLevels = 1
    /**
     * Determines how many levels to skip from retrieving texture during tile pyramid subdivision.
     */
    var levelOffset = 0
    /**
     * The width in pixels of images associated with tiles in the level set, or the number of sample points in the
     * longitudinal direction of elevation tiles associated with the level set.
     */
    var tileWidth = 256
    /**
     * The height in pixels of images associated with tiles in the level set, or the number of sample points in the
     * latitudinal direction of elevation tiles associated with the level set.
     */
    var tileHeight = 256

    /**
     * Returns the number of levels necessary to achieve the specified resolution. The result is correct for this
     * configuration's current firstLevelDelta, tileWidth and tileHeight, and is invalid if any of these values change.
     *
     * @param radiansPerPixel the desired resolution in radians per pixel
     *
     * @return the number of levels
     *
     * @throws IllegalArgumentException If the resolution is not positive
     */
    fun numLevelsForResolution(radiansPerPixel: Double): Int {
        require(radiansPerPixel > 0) {
            logMessage(ERROR, "LevelSetConfig", "numLevelsForResolution", "invalidResolution")
        }
        val degreesPerPixel = toDegrees(radiansPerPixel)
        val firstLevelDegreesPerPixel = firstLevelDelta.latitude.inDegrees / tileHeight
        val level = log2(firstLevelDegreesPerPixel / degreesPerPixel) // fractional level address
        var levelNumber = ceil(level).toInt() // ceiling captures the resolution
        if (levelNumber < 0) levelNumber = 0 // need at least one level, even if it exceeds the desired resolution
        return levelNumber + 1 // convert level number to level count
    }

    /**
     * Returns the number of levels closest to the specified resolution, but does not exceed it. May be used to
     * configure level sets where a not to exceed resolution is mandated. The result is correct for this configuration's
     * current firstLevelDelta, tileWidth and tileHeight, and is invalid if any of these values change.
     *
     * @param radiansPerPixel the desired not to exceed resolution in radians per pixel
     *
     * @return the number of levels
     *
     * @throws IllegalArgumentException If the resolution is not positive
     */
    fun numLevelsForMinResolution(radiansPerPixel: Double): Int {
        require(radiansPerPixel > 0) {
            logMessage(ERROR, "LevelSetConfig", "numLevelsForMinResolution", "invalidResolution")
        }
        val degreesPerPixel = toDegrees(radiansPerPixel)
        val firstLevelDegreesPerPixel = firstLevelDelta.latitude.inDegrees / tileHeight
        val level = log2(firstLevelDegreesPerPixel / degreesPerPixel) // fractional level address
        var levelNumber = floor(level).toInt() // floor prevents exceeding the min scale
        if (levelNumber < 0) levelNumber = 0 // need at least one level, even if it exceeds the desired resolution
        return levelNumber + 1 // convert level number to level count
    }
}