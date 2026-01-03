package earth.worldwind.util

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Location
import earth.worldwind.geom.Sector
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import kotlin.math.log2
import kotlin.math.roundToInt

/**
 * Multi-resolution, hierarchical collection of tiles organized into levels of increasing resolution. Applications
 * typically do not interact with this class.
 */
open class LevelSet {
    /**
     * The sector spanned by this level set.
     */
    val sector: Sector
    /**
     * Tile origin for this level set
     */
    val tileOrigin: Sector
    /**
     * The geographic width and height of tiles in the first level (the lowest resolution) of this level set.
     */
    val firstLevelDelta: Location
    /**
     * The width in pixels of images associated with tiles in this level set, or the number of sample points in the
     * longitudinal direction of elevation tiles associated with this level set.
     */
    val tileWidth: Int
    /**
     * The height in pixels of images associated with tiles in this level set, or the number of sample points in the
     * latitudinal direction of elevation tiles associated with this level set.
     */
    val tileHeight: Int
    /**
     * Determines how many levels to skip from retrieving tile data during tile pyramid subdivision.
     */
    val levelOffset: Int
    /**
     * The hierarchical levels, sorted from lowest to highest resolution.
     */
    protected val levels: Array<Level>
    /**
     * Returns the number of levels in this level set.
     */
    val numLevels get() = levels.size
    /**
     * Returns the first level (the lowest resolution) of this level set.
     */
    val firstLevel get() = levels.first()
    /**
     * Returns the last level (the highest resolution) of this level set.
     */
    val lastLevel get() = levels.last()

    /**
     * Constructs a level set with specified parameters.
     *
     * @param sector          the sector spanned by this level set
     * @param tileOrigin      the origin for this level set
     * @param firstLevelDelta the geographic width and height of tiles in the first level (the lowest resolution)
     * of the level set
     * @param numLevels       the number of levels in the level set
     * @param tileWidth       the height in pixels of images associated with tiles in this level set, or the number of
     * sample points in the longitudinal direction of elevation tiles associate with this leve set
     * @param tileHeight      the height in pixels of images associated with tiles in this level set, or the number of
     * sample points in the latitudinal direction of elevation tiles associate with this level set
     * @param levelOffset     determines how many levels to skip from retrieving texture during tile pyramid subdivision
     * @param firstLevelNumber determines the lowest resolution level number. .
     *
     * @throws IllegalArgumentException If any dimension is zero
     */
    constructor(
        sector: Sector, tileOrigin: Sector, firstLevelDelta: Location, numLevels: Int, tileWidth: Int, tileHeight: Int,
        levelOffset: Int = 0, firstLevelNumber: Int = 0
    ) {
        require(firstLevelDelta.latitude.inDegrees > 0.0 && firstLevelDelta.longitude.inDegrees > 0.0) {
            logMessage(ERROR, "LevelSet", "constructor", "invalidTileDelta")
        }
        require(numLevels >= 1) {
            logMessage(ERROR, "LevelSet", "constructor", "invalidNumLevels")
        }
        require(tileWidth >= 1 && tileHeight >= 1) {
            logMessage(ERROR, "LevelSet", "constructor", "invalidWidthOrHeight")
        }
        this.sector = sector
        this.tileOrigin = tileOrigin
        this.firstLevelDelta = firstLevelDelta
        this.tileWidth = tileWidth
        this.tileHeight = tileHeight
        this.levelOffset = levelOffset
        this.levels = Array(numLevels) {
            val divisor = 1 shl it
            Level(this, it + firstLevelNumber, Location(firstLevelDelta.latitude / divisor, firstLevelDelta.longitude / divisor))
        }
    }

    /**
     * Constructs a level set with parameters from a specified level set.
     *
     * @param levelSet source level set
     */
    constructor(levelSet: LevelSet): this(
        Sector(levelSet.sector),
        Sector(levelSet.tileOrigin),
        Location(levelSet.firstLevelDelta),
        levelSet.numLevels,
        levelSet.tileWidth,
        levelSet.tileHeight,
        levelSet.levelOffset,
        levelSet.firstLevel.levelNumber,
    )

    /**
     * Constructs a level set with parameters from a specified configuration. The configuration's sector must be
     * non-null, its first level delta must be positive, its number of levels must be 1 or more, and its tile width and
     * tile height must be 1 or greater.
     *
     * @param config the configuration for this level set
     */
    constructor(config: LevelSetConfig): this(
        config.sector,
        config.tileOrigin,
        config.firstLevelDelta,
        config.numLevels,
        config.tileWidth,
        config.tileHeight,
        config.levelOffset,
        config.firstLevelNumber,
    )

    /**
     * Returns the [Level] for a specified level number.
     *
     * @param levelNumber the number of the desired level
     *
     * @return the requested level, or null if the level does not exist
     */
    fun level(levelNumber: Int) = levels.getOrNull(levelNumber - firstLevel.levelNumber)

    /**
     * Returns the level that most closely approximates the specified resolution.
     *
     * @param resolution the desired resolution in angular value of latitude per pixel.
     *
     * @return the level for the specified resolution, or null if this level set is empty
     *
     * @throws IllegalArgumentException If the resolution is not positive
     * @throws IllegalStateException If this level set is empty
     */
    fun levelForResolution(resolution: Angle): Level {
        require(resolution.inDegrees > 0.0) {
            logMessage(ERROR, "LevelSetConfig", "levelForResolution", "invalidResolution")
        }
        if (levels.isEmpty()) error("This level set is empty")
        val firstLevelDegreesPerPixel = firstLevelDelta.latitude.inDegrees / tileHeight
        val level = log2(firstLevelDegreesPerPixel / resolution.inDegrees) // fractional level address
        val levelNumber = level.roundToInt() // nearest neighbor level
        return when {
            levelNumber < 0 -> levels[0] // unable to match the resolution; return the first level
            levelNumber < levels.size -> levels[levelNumber] // nearest neighbor level is in this level set
            else -> levels[levels.size - 1] // unable to match the resolution; return the last level
        }
    }

    /**
     * Determine the min relevant level for the sector of this level set
     *
     * @param maxLevel Maximum available level
     * @return Minimum relevant level number
     */
    fun minRelevantLevel(maxLevel: Level = lastLevel) = levels[sector.minLevelNumber(maxLevel.levelNumber)]

    /**
     * Calculates approximate count of tiles in specified sector within specified resolution range
     *
     * @param sector co calculate tiles amount
     * @param minLevel minimal required level
     * @param maxLevel maximal required level
     * @return tiles count
     */
    fun tileCount(sector: Sector, minLevel: Level, maxLevel: Level): Long {
        var tileCount = 0L
        var level = minLevel
        do {
            tileCount += level.tilesInSector(sector)
            level = level.nextLevel ?: break
        } while (level.levelNumber <= maxLevel.levelNumber)
        return tileCount
    }
}