package earth.worldwind.layer.mercator

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Sector
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Web-Mercator (slippy-map) tile ↔ geographic conversions, shared by the MVT and OSM-Buildings
 * layers and the bulk-retrieval drivers so the projection math lives in exactly one place. Tiles
 * follow the standard 256-px OSM/XYZ scheme: x grows east, y grows south (`y = 0` is north), with
 * `n = 2^zoom` tiles per axis. Latitude is clamped to ±[maxLatitudeDegrees].
 *
 * This is the single source of truth for the Web-Mercator projection: [MercatorSector] (the
 * [earth.worldwind.util.LevelSet]-backed render quadtree's warped extent) delegates its gudermannian
 * math to [mercatorYToLatRadians] / [latRadiansToMercatorY] here, so the scalar hot-path kernel and
 * the object-model tiling can never drift.
 */
object SlippyTiles {
    private const val TILE_SIZE = 256

    /** Web-Mercator latitude limit in radians — `atan(sinh(π))` (≈ 85.0511°), where the projection becomes square. */
    val maxLatitudeRadians = mercatorYToLatRadians(1.0)

    /** Web-Mercator latitude limit in degrees; slippy servers/clients clamp to ±this. */
    val maxLatitudeDegrees = maxLatitudeRadians * 180.0 / PI

    /** Angular resolution (degrees/pixel) of zoom 0 — the full Mercator latitude span over one tile. */
    private val zoom0DegreesPerPixel = 2.0 * maxLatitudeDegrees / TILE_SIZE

    /** Web-Mercator forward projection: normalized Mercator-Y in `[-1, 1]` → latitude in radians (`y = +1` → north limit). */
    fun mercatorYToLatRadians(y: Double): Double = atan(sinh(y * PI))

    /** Web-Mercator inverse projection: latitude in radians → normalized Mercator-Y in `[-1, 1]`. */
    fun latRadiansToMercatorY(latRadians: Double): Double = asinh(tan(latRadians)) / PI

    /** Slippy column for [lonDegrees] at [zoom], clamped to the pyramid width. */
    fun lonToTileX(lonDegrees: Double, zoom: Int): Int {
        val n = 1 shl zoom
        return ((lonDegrees + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
    }

    /** Slippy row (0 = north) for [latDegrees] at [zoom], clamped to Mercator bounds and pyramid height. */
    fun latToTileY(latDegrees: Double, zoom: Int): Int {
        val n = 1 shl zoom
        val latRad = latDegrees.coerceIn(-maxLatitudeDegrees, maxLatitudeDegrees) * PI / 180.0
        return ((1.0 - latRadiansToMercatorY(latRad)) / 2.0 * n).toInt().coerceIn(0, n - 1)
    }

    /** Slippy `(x, y)` containing [lonDegrees]/[latDegrees] at [zoom]. */
    fun lonLatToTile(lonDegrees: Double, latDegrees: Double, zoom: Int): Pair<Int, Int> =
        lonToTileX(lonDegrees, zoom) to latToTileY(latDegrees, zoom)

    /** Longitude (degrees) at fractional slippy column [tileX] (integer tile + in-tile fraction),
     *  where [tilesPerAxis] `= 2^zoom`. Split out so per-vertex unprojection (e.g. MVT geometry)
     *  shares the same projection as [tileToSector] while hoisting `tilesPerAxis` out of its loop. */
    fun tileXToLonDegrees(tileX: Double, tilesPerAxis: Double): Double =
        tileX / tilesPerAxis * 360.0 - 180.0

    /** Latitude (degrees, row 0 = north) at fractional slippy row [tileY], where [tilesPerAxis] `= 2^zoom`. */
    fun tileYToLatDegrees(tileY: Double, tilesPerAxis: Double): Double =
        mercatorYToLatRadians(1.0 - 2.0 * tileY / tilesPerAxis) * 180.0 / PI

    /** Geographic bounds of slippy tile `(zoom, x, y)` (y slippy, 0 = north). */
    fun tileToSector(zoom: Int, x: Int, y: Int): Sector {
        val n = (1 shl zoom).toDouble()
        val west = tileXToLonDegrees(x.toDouble(), n)
        val east = tileXToLonDegrees((x + 1).toDouble(), n)
        val north = tileYToLatDegrees(y.toDouble(), n)
        val south = tileYToLatDegrees((y + 1).toDouble(), n)
        return Sector.fromDegrees(south, west, north - south, east - west)
    }

    /**
     * Slippy zoom whose resolution best matches [resolution], mirroring
     * [earth.worldwind.util.LevelSet.levelForResolution]'s nearest-neighbor rounding so a resolution
     * range selects the same levels here as it does for raster/elevation layers. Never negative;
     * callers clamp the upper bound to the deepest available zoom.
     */
    fun zoomForResolution(resolution: Angle): Int {
        val degreesPerPixel = resolution.inDegrees
        if (degreesPerPixel <= 0.0) return Int.MAX_VALUE // finest possible; caller clamps to maxZoom
        return log2(zoom0DegreesPerPixel / degreesPerPixel).roundToInt().coerceAtLeast(0)
    }
}
