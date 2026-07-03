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
 * `n = 2^zoom` tiles per axis. Latitude is clamped to ±[MercatorSector.MAX_LATITUDE_DEG].
 */
object SlippyTiles {
    private const val TILE_SIZE = 256

    /** Angular resolution (degrees/pixel) of zoom 0 — the full Mercator latitude span over one tile. */
    private val zoom0DegreesPerPixel = 2.0 * MercatorSector.MAX_LATITUDE_DEG / TILE_SIZE

    /** Slippy column for [lonDegrees] at [zoom], clamped to the pyramid width. */
    fun lonToTileX(lonDegrees: Double, zoom: Int): Int {
        val n = 1 shl zoom
        return ((lonDegrees + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
    }

    /** Slippy row (0 = north) for [latDegrees] at [zoom], clamped to Mercator bounds and pyramid height. */
    fun latToTileY(latDegrees: Double, zoom: Int): Int {
        val n = 1 shl zoom
        val latRad = latDegrees.coerceIn(-MercatorSector.MAX_LATITUDE_DEG, MercatorSector.MAX_LATITUDE_DEG) * PI / 180.0
        return ((1.0 - asinh(tan(latRad)) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
    }

    /** Slippy `(x, y)` containing [lonDegrees]/[latDegrees] at [zoom]. */
    fun lonLatToTile(lonDegrees: Double, latDegrees: Double, zoom: Int): Pair<Int, Int> =
        lonToTileX(lonDegrees, zoom) to latToTileY(latDegrees, zoom)

    /** Geographic bounds of slippy tile `(zoom, x, y)` (y slippy, 0 = north). */
    fun tileToSector(zoom: Int, x: Int, y: Int): Sector {
        val n = 1 shl zoom
        val west = x.toDouble() / n * 360.0 - 180.0
        val east = (x + 1).toDouble() / n * 360.0 - 180.0
        val north = atan(sinh(PI * (1 - 2 * y.toDouble() / n))) * 180.0 / PI
        val south = atan(sinh(PI * (1 - 2 * (y + 1).toDouble() / n))) * 180.0 / PI
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
