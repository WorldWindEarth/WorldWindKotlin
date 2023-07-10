package earth.worldwind.util

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Vec3
import earth.worldwind.render.RenderContext
import kotlin.jvm.JvmStatic
import kotlin.math.*

/**
 * Geographically rectangular tile within a [LevelSet], typically representing terrain or imagery. Provides a base
 * class for tiles used by tiled image layers and elevation models. Applications typically do not interact with this
 * class.
 */
open class Tile protected constructor(
    /**
     * The sector spanned by this tile.
     */
    val sector: Sector,
    /**
     * The level at which this tile lies within a [LevelSet].
     */
    val level: Level,
    /**
     * The tile's row within its level.
     */
    val row: Int,
    /**
     * The tile's column within its level.
     */
    val column: Int
) {
    /**
     * A key that uniquely identifies this tile within a level set. Tile keys are not unique to a specific level set.
     */
    val tileKey = level.levelNumber.toString() + '.' + row + '.' + column
    /**
     * A factor expressing the size of a pixel or elevation cell at the center of this tile, in radians per pixel (or
     * cell).
     * <br>
     * Texel size in meters is computed as `(tileDelta / tileWidth) * cos(lat) * R`, where lat is the
     * centroid latitude and R is the globe's equatorial radius. This is derived by considering that texels are laid out
     * continuously on the arc of constant latitude connecting the tile's east and west edges and passing through its
     * centroid. The radii for the corresponding circle of constant latitude is `cos(lat) * R`, and the arc
     * length is therefore `tileDelta * cos(lat) * R`. The size of a texel along this arc is then found by
     * dividing by the number of texels along that arc, defined by the property Level.tileWidth.
     * <br>
     * This property stores the constant part of the texel size computation, `(tileDelta / tileWidth) *
     * cos(lat)`, leaving the globe-dependant variable `R` to be incorporated by the globe attached to
     * the RenderContext.
     */
    protected val texelSizeFactor = level.tileDelta.longitude.inRadians / level.tileWidth * cos(sector.centroidLatitude.inRadians)
    protected val heightLimits by lazy { FloatArray(2) }
    protected var heightLimitsTimestamp = 0L
    /**
     * The nearest point on the tile to the camera. Altitude value is based on the minimum height for the tile.
     */
    private val nearestPoint = Vec3()
    private val scratchVector = Vec3()

    /**
     * Indicates whether this tile intersects a specified sector.
     *
     * @param sector the sector of interest
     *
     * @return true if the specified sector intersects this tile's sector, otherwise false
     */
    fun intersectsSector(sector: Sector) = this.sector.intersects(sector)

    /**
     * Indicates whether this tile should be subdivided based on the current navigation state and a specified detail
     * factor.
     *
     * @param rc           the current render context
     * @param detailFactor the detail factor to consider
     *
     * @return true if the tile should be subdivided, otherwise false
     */
    open fun mustSubdivide(rc: RenderContext, detailFactor: Double): Boolean {
        val nearestPoint = nearestPoint(rc)
        val distanceToCamera = nearestPoint.distanceTo(rc.cameraPoint)
        // Accelerate the degradation of tile details depending on the viewing angle to tile normal
        val cos = if (rc.camera.position.altitude < rc.globe.equatorialRadius / 10.0) {
            val viewingVector = nearestPoint.subtract(rc.cameraPoint)
            val normalVector =
                rc.globe.geographicToCartesianNormal(sector.centroidLatitude, sector.centroidLongitude, scratchVector)
            val dot = viewingVector.dot(normalVector)
            abs(dot / (viewingVector.magnitude * normalVector.magnitude))
        } else 1.0
        val texelSize = texelSizeFactor * rc.globe.equatorialRadius * cos
        val pixelSize = rc.pixelSizeAtDistance(distanceToCamera)

        // Adjust the subdivision factory when the display density is low.
        return texelSize > pixelSize * detailFactor * rc.densityFactor
    }

    /**
     * Returns the four children formed by subdividing this tile. This tile's sector is subdivided into four quadrants
     * as follows: Southwest; Southeast; Northwest; Northeast. A new tile is then constructed for each quadrant and
     * configured with the next level within this tile's LevelSet and its corresponding row and column within that
     * level. This returns null if this tile's level is the last level within its [LevelSet].
     *
     * @param tileFactory the tile factory to use to create the children
     *
     * @return an array containing the four child tiles, or null if this tile's level is the last level
     */
    open fun subdivide(tileFactory: TileFactory): Array<Tile> {
        val childLevel = level.nextLevel ?: return emptyArray()

        val latMin = sector.minLatitude
        val lonMin = sector.minLongitude
        val latMid = sector.centroidLatitude
        val lonMid = sector.centroidLongitude
        val latMax = sector.maxLatitude
        val lonMax = sector.maxLongitude

        var childRow = 2 * row
        var childCol = 2 * column
        var childSector = Sector(latMin, latMid, lonMin, lonMid)
        val child0 = tileFactory.createTile(childSector, childLevel, childRow, childCol) // Southwest

        childRow = 2 * row
        childCol = 2 * column + 1
        childSector = Sector(latMin, latMid, lonMid, lonMax)
        val child1 = tileFactory.createTile(childSector, childLevel, childRow, childCol) // Southeast

        childRow = 2 * row + 1
        childCol = 2 * column
        childSector = Sector(latMid, latMax, lonMin, lonMid)
        val child2 = tileFactory.createTile(childSector, childLevel, childRow, childCol) // Northwest

        childRow = 2 * row + 1
        childCol = 2 * column + 1
        childSector = Sector(latMid, latMax, lonMid, lonMax)
        val child3 = tileFactory.createTile(childSector, childLevel, childRow, childCol) // Northeast

        return arrayOf(child0, child1, child2, child3)
    }

    /**
     * Returns the four children formed by subdividing this tile, drawing those children from a specified cache. The
     * cache is checked for a child collection prior to subdividing. If one exists in the cache it is returned rather
     * than creating a new collection of children. If a new collection is created in the same manner as [subdivide] and added to the cache.
     *
     * @param tileFactory the tile factory to use to create the children
     * @param cache       a memory cache that may contain pre-existing child tiles.
     * @param cacheSize   the cached size of the four child tiles
     *
     * @return an array containing the four child tiles, or null if this tile's level is the last level
     */
    open fun subdivideToCache(
        tileFactory: TileFactory, cache: LruMemoryCache<String, Array<Tile>>, cacheSize: Int
    ) = cache[tileKey] ?: subdivide(tileFactory).also { cache.put(tileKey, it, cacheSize) }

    /**
     * Calculates the distance from this tile to the camera point which ensures front to back sorting.
     *
     * @param rc the render context which provides the current camera point
     *
     * @return the L1 distance in degrees
     */
    protected open fun drawSortOrder(rc: RenderContext): Double {
        val cameraPosition = rc.camera.position
        // determine the nearest latitude
        val latAbsDifference = abs(cameraPosition.latitude.inDegrees - sector.centroidLatitude.inDegrees)
        // determine the nearest longitude and account for the antimeridian discontinuity
        val lonAbsDifference = abs(cameraPosition.longitude.inDegrees - sector.centroidLongitude.inDegrees)
        val lonAbsDifferenceCorrected = min(lonAbsDifference, 360.0 - lonAbsDifference)

        return latAbsDifference + lonAbsDifferenceCorrected // L1 distance on cylinder
    }

    /**
     * Calculates nearest point of this tile to the camera position associated with the specified render context.
     *
     * @param rc the render context which provides the current camera point
     *
     * @return the nearest point
     */
    protected open fun nearestPoint(rc: RenderContext): Vec3 {
        val cameraPosition = rc.camera.position
        // determine the nearest latitude
        val nearestLat = cameraPosition.latitude.inDegrees.coerceIn(sector.minLatitude.inDegrees, sector.maxLatitude.inDegrees)
        // determine the nearest longitude and account for the antimeridian discontinuity
        val lonDifference = cameraPosition.longitude.inDegrees - sector.centroidLongitude.inDegrees
        val nearestLon = when {
            lonDifference < -180.0 -> sector.maxLongitude.inDegrees
            lonDifference > 180.0 -> sector.minLongitude.inDegrees
            else -> cameraPosition.longitude.inDegrees.coerceIn(sector.minLongitude.inDegrees, sector.maxLongitude.inDegrees)
        }
        val timestamp = rc.elevationModelTimestamp
        if (timestamp != heightLimitsTimestamp) {
            heightLimitsTimestamp = timestamp
            // initialize the heights for elevation model scan
            heightLimits[0] = Float.MAX_VALUE
            heightLimits[1] = -Float.MAX_VALUE
            rc.globe.elevationModel.getHeightLimits(sector, heightLimits)
            // check for valid height limits
            if (heightLimits[0] > heightLimits[1]) heightLimits.fill(0f)
        }
        val minHeight = heightLimits[0] * rc.verticalExaggeration
        return rc.globe.geographicToCartesian(nearestLat.degrees, nearestLon.degrees, minHeight, nearestPoint)
    }

    companion object {
        /**
         * Computes a row number for a tile within a level given the tile's latitude.
         *
         * @param tileDelta the level's tile delta
         * @param latitude  the tile's minimum latitude
         * @param origin    the origin of the grid
         *
         * @return the computed row number
         */
        @JvmStatic
        fun computeRow(tileDelta: Angle, latitude: Angle, origin: Angle): Int {
            var row = floor((latitude.inDegrees - origin.inDegrees) / tileDelta.inDegrees).toInt()
            // if latitude is at the end of the grid, subtract 1 from the computed row to return the last row
            if (latitude.inDegrees - origin.inDegrees == 180.0) row -= 1
            return row
        }

        /**
         * Computes a column number for a tile within a level given the tile's longitude.
         *
         * @param tileDelta the level's tile delta
         * @param longitude the tile's minimum longitude
         * @param origin    the origin of the grid
         *
         * @return The computed column number
         */
        @JvmStatic
        fun computeColumn(tileDelta: Angle, longitude: Angle, origin: Angle): Int {
            var col = floor((longitude.inDegrees - origin.inDegrees) / tileDelta.inDegrees).toInt()
            // if longitude is at the end of the grid, subtract 1 from the computed column to return the last column
            if (longitude.inDegrees - origin.inDegrees == 360.0) col -= 1
            return col
        }

        /**
         * Computes the last row number for a tile within a level given the tile's maximum latitude.
         *
         * @param tileDelta   the level's tile delta
         * @param maxLatitude the tile's maximum latitude
         * @param origin      the origin of the grid
         *
         * @return the computed row number
         */
        @JvmStatic
        fun computeLastRow(tileDelta: Angle, maxLatitude: Angle, origin: Angle): Int {
            var row = ceil((maxLatitude.inDegrees - origin.inDegrees) / tileDelta.inDegrees - 1).toInt()
            // if max latitude is in the first row, set the max row to 0
            if (maxLatitude.inDegrees - origin.inDegrees < tileDelta.inDegrees) row = 0
            return row
        }

        /**
         * Computes the last column number for a tile within a level given the tile's maximum longitude.
         *
         * @param tileDelta    the level's tile delta
         * @param maxLongitude the tile's maximum longitude
         * @param origin       the origin of the grid
         *
         * @return The computed column number
         */
        @JvmStatic
        fun computeLastColumn(tileDelta: Angle, maxLongitude: Angle, origin: Angle): Int {
            var col = ceil((maxLongitude.inDegrees - origin.inDegrees) / tileDelta.inDegrees - 1).toInt()
            // if max longitude is in the first column, set the max column to 0
            if (maxLongitude.inDegrees - origin.inDegrees < tileDelta.inDegrees) col = 0
            return col
        }

        /**
         * Creates all tiles for a specified level within a [LevelSet].
         *
         * @param level       the level to create the tiles for
         * @param tileFactory the tile factory to use for creating tiles.
         * @param result      an pre-allocated Collection in which to store the results
         *
         * @return the result argument populated with the tiles for the specified level
         */
        @JvmStatic
        fun assembleTilesForLevel(level: Level, tileFactory: TileFactory, result: MutableList<Tile>): MutableList<Tile> {
            val sector = level.parent.sector
            val tileOrigin = level.parent.tileOrigin
            val tileDelta = level.tileDelta
            val firstRow = computeRow(tileDelta.latitude, sector.minLatitude, tileOrigin.latitude)
            val lastRow = computeLastRow(tileDelta.latitude, sector.maxLatitude, tileOrigin.latitude)
            val firstCol = computeColumn(tileDelta.longitude, sector.minLongitude, tileOrigin.longitude)
            val lastCol = computeLastColumn(tileDelta.longitude, sector.maxLongitude, tileOrigin.longitude)
            val firstRowLat = tileOrigin.latitude.plusDegrees(firstRow * tileDelta.latitude.inDegrees)
            val firstColLon = tileOrigin.longitude.plusDegrees(firstCol * tileDelta.longitude.inDegrees)
            var minLat = firstRowLat
            for (row in firstRow..lastRow) {
                val maxLat = minLat + tileDelta.latitude
                var minLon = firstColLon
                for (col in firstCol..lastCol) {
                    val maxLon = minLon + tileDelta.longitude
                    val tileSector = Sector(minLat, maxLat, minLon, maxLon)
                    result.add(tileFactory.createTile(tileSector, level, row, col))
                    minLon = maxLon
                }
                minLat = maxLat
            }
            return result
        }
    }
}