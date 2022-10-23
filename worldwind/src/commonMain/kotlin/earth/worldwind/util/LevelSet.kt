package earth.worldwind.util

import earth.worldwind.geom.Angle.Companion.ZERO
import earth.worldwind.geom.Angle.Companion.toDegrees
import earth.worldwind.geom.Location
import earth.worldwind.geom.Sector
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import kotlin.math.ln
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
    val tileOrigin: Location
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
     * The hierarchical levels, sorted from lowest to highest resolution.
     */
    protected val levels: Array<Level>
    /**
     * Returns the number of levels in this level set.
     */
    val numLevels get() = levels.size
    /**
     * Returns the first level (lowest resolution) of this level set.
     */
    val firstLevel get() = if (levels.isNotEmpty()) levels[0] else null
    /**
     * Returns the last level (highest resolution) of this level set.
     */
    val lastLevel get() = if (levels.isNotEmpty()) levels[levels.size - 1] else null

    /**
     * Constructs an empty level set with no levels. The methods `level`, `levelForResolution`,
     * `firstLevel` and `lastLevel` always return null.
     */
    constructor() {
        sector = Sector()
        tileOrigin = Location()
        firstLevelDelta = Location()
        tileWidth = 0
        tileHeight = 0
        levels = emptyArray()
    }

    /**
     * Constructs a level set with specified parameters.
     *
     * @param sector          the sector spanned by this level set
     * @param tileOrigin      the origin for this level set
     * @param firstLevelDelta the geographic width and height of tiles in the first level (lowest resolution)
     * of the level set
     * @param numLevels       the number of levels in the level set
     * @param tileWidth       the height in pixels of images associated with tiles in this level set, or the number of
     * sample points in the longitudinal direction of elevation tiles associate with this leve set
     * @param tileHeight      the height in pixels of images associated with tiles in this level set, or the number of
     * sample points in the latitudinal direction of elevation tiles associate with this level set
     *
     * @throws IllegalArgumentException If any dimension is zero
     */
    constructor(
        sector: Sector, tileOrigin: Location, firstLevelDelta: Location, numLevels: Int, tileWidth: Int, tileHeight: Int
    ) {
        require(firstLevelDelta.latitude > ZERO && firstLevelDelta.longitude > ZERO) {
            logMessage(ERROR, "LevelSet", "constructor", "invalidTileDelta")
        }
        require(numLevels >= 0) {
            logMessage(ERROR, "LevelSet", "constructor", "invalidNumLevels")
        }
        require(tileWidth >= 1 && tileHeight >= 1) {
            logMessage(ERROR, "LevelSet", "constructor", "invalidWidthOrHeight")
        }
        this.sector = Sector(sector)
        this.tileOrigin = Location(tileOrigin)
        this.firstLevelDelta = Location(firstLevelDelta)
        this.tileWidth = tileWidth
        this.tileHeight = tileHeight
        this.levels = Array(numLevels) {
            val divisor = (1 shl it).toDouble()
            Level(this, it, Location(firstLevelDelta.latitude / divisor, firstLevelDelta.longitude / divisor))
        }
    }

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
        config.tileHeight
    )

    /**
     * Returns the [Level] for a specified level number.
     *
     * @param levelNumber the number of the desired level
     *
     * @return the requested level, or null if the level does not exist
     */
    fun level(levelNumber: Int) = if (levelNumber in levels.indices) levels[levelNumber] else null

    /**
     * Returns the level that most closely approximates the specified resolution.
     *
     * @param radiansPerPixel the desired resolution in radians per pixel
     *
     * @return the level for the specified resolution, or null if this level set is empty
     *
     * @throws IllegalArgumentException If the resolution is not positive
     */
    fun levelForResolution(radiansPerPixel: Double): Level? {
        require(radiansPerPixel > 0) {
            logMessage(ERROR, "LevelSetConfig", "levelForResolution", "invalidResolution")
        }
        if (levels.isEmpty()) return null // this level set is empty
        val degreesPerPixel = toDegrees(radiansPerPixel)
        val firstLevelDegreesPerPixel = firstLevelDelta.latitude.inDegrees / tileHeight
        val level = ln(firstLevelDegreesPerPixel / degreesPerPixel) / ln(2.0) // fractional level address
        val levelNumber = level.roundToInt() // nearest neighbor level
        return when {
            levelNumber < 0 -> levels[0] // unable to match the resolution; return the first level
            levelNumber < levels.size -> levels[levelNumber] // nearest neighbor level is in this level set
            else -> levels[levels.size - 1] // unable to match the resolution; return the last level
        }
    }
}