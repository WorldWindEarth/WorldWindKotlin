package earth.worldwind.util

import earth.worldwind.geom.Angle.Companion.ZERO
import earth.worldwind.geom.Location
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
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
    val levelWidth: Int
    /**
     * The height in pixels of the image represented by all tiles in this level set, or the number of sample points in
     * the latitudinal direction of this level set.
     */
    val levelHeight: Int
    /**
     * The parent LevelSet's tileWidth.
     */
    val tileWidth: Int
    /**
     * The parent LevelSet's tileHeight.
     */
    val tileHeight: Int
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
     * Constructs a Level within a LevelSet. Applications typically do not interact with this class.
     */
    init {
        require(tileDelta.latitude > ZERO && tileDelta.longitude > ZERO) {
            logMessage(ERROR, "Level", "constructor", "The tile delta is zero")
        }
        levelWidth = (parent.tileWidth * parent.sector.deltaLongitude.inDegrees / tileDelta.longitude.inDegrees).roundToInt()
        levelHeight = (parent.tileHeight * parent.sector.deltaLatitude.inDegrees / tileDelta.latitude.inDegrees).roundToInt()
        tileWidth = parent.tileWidth
        tileHeight = parent.tileHeight
    }
}