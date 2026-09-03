package earth.worldwind.formats.geotiff

import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Sector
import earth.worldwind.geom.coords.TMCoord
import earth.worldwind.util.Logger.INFO
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.log
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tan

/**
 * The coordinate reference system a GeoTIFF's model space lives in — everything between
 * the raster's affine transform and geodetic latitude / longitude.
 *
 * Only the systems the engine can invert without a projection library are represented.
 * Geographic and Web-Mercator rasters are closed-form; transverse-Mercator (which covers
 * every UTM zone, the overwhelmingly common projection for national DEM and orthophoto
 * products) rides on the engine's existing [TMCoord] converter.
 */
internal sealed class GeoTiffCrs {
    /** Model coordinates are angles. [toDegrees] converts the file's angular unit to degrees. */
    class Geographic(val toDegrees: Double) : GeoTiffCrs()

    /** EPSG:3857 and its aliases — spherical Mercator on a 6378137 m sphere. */
    data object WebMercator : GeoTiffCrs()

    /** Transverse Mercator, including every UTM zone. */
    class TransverseMercator(
        val centralMeridian: Double, val originLatitude: Double,
        val falseEasting: Double, val falseNorthing: Double, val scale: Double,
    ) : GeoTiffCrs()
}

/**
 * Maps between a GeoTIFF's raster grid and geodetic coordinates.
 *
 * Raster space is continuous with integers on pixel **corners**: `(0.0, 0.0)` is the
 * north-west corner of the first pixel and `(0.5, 0.5)` its centre. That matches how the
 * engine addresses tile texels (see `AbstractTiledElevationCoverage.fetchTileBlock`), so
 * the resampler can treat both grids the same way.
 */
class GeoTiffGeoReference internal constructor(
    // Affine raster -> model: modelX = a*px + b*py + c, modelY = d*px + e*py + f.
    private val a: Double, private val b: Double, private val c: Double,
    private val d: Double, private val e: Double, private val f: Double,
    internal val crs: GeoTiffCrs,
) {
    private val determinant = a * e - b * d

    /** True when the raster is axis-aligned in model space (the usual case) — lets callers
     *  skip the general path when a straight scale + offset is all that is needed. */
    val isAxisAligned get() = b == 0.0 && d == 0.0

    /** Model-space size of one pixel along the raster's x and y axes. */
    val pixelSizeX get() = kotlin.math.hypot(a, d)
    val pixelSizeY get() = kotlin.math.hypot(b, e)

    /** Convert a continuous raster coordinate to geodetic degrees, writing
     *  `[latitude, longitude]` into [result]. Returns false when the projection rejects
     *  the point (outside its valid domain). */
    fun rasterToGeodetic(px: Double, py: Double, result: DoubleArray): Boolean {
        val x = a * px + b * py + c
        val y = d * px + e * py + f
        return modelToGeodetic(x, y, result)
    }

    /** Convert geodetic degrees to a continuous raster coordinate, writing `[x, y]` into
     *  [result]. Returns false when the point can't be projected into model space. */
    fun geodeticToRaster(latitude: Double, longitude: Double, result: DoubleArray): Boolean {
        if (determinant == 0.0) return false
        if (!geodeticToModel(latitude, longitude, result)) return false
        val dx = result[0] - c
        val dy = result[1] - f
        result[0] = (e * dx - b * dy) / determinant
        result[1] = (a * dy - d * dx) / determinant
        return true
    }

    private fun modelToGeodetic(x: Double, y: Double, result: DoubleArray) = when (val crs = crs) {
        is GeoTiffCrs.Geographic -> {
            result[0] = y * crs.toDegrees
            result[1] = x * crs.toDegrees
            true
        }
        is GeoTiffCrs.WebMercator -> {
            result[0] = (2.0 * atan(exp(y / EARTH_RADIUS)) - PI / 2.0) * 180.0 / PI
            result[1] = x / EARTH_RADIUS * 180.0 / PI
            true
        }
        is GeoTiffCrs.TransverseMercator -> try {
            val tm = TMCoord.fromTM(
                x, y, crs.originLatitude.degrees, crs.centralMeridian.degrees,
                crs.falseEasting, crs.falseNorthing, crs.scale
            )
            result[0] = tm.latitude.inDegrees
            result[1] = tm.longitude.inDegrees
            true
        } catch (ignored: Throwable) {
            false // outside the projection's valid domain — the caller renders a hole
        }
    }

    private fun geodeticToModel(latitude: Double, longitude: Double, result: DoubleArray) = when (val crs = crs) {
        is GeoTiffCrs.Geographic -> {
            result[0] = longitude / crs.toDegrees
            result[1] = latitude / crs.toDegrees
            true
        }
        is GeoTiffCrs.WebMercator -> {
            if (abs(latitude) >= MERCATOR_LIMIT) false else {
                result[0] = longitude * PI / 180.0 * EARTH_RADIUS
                result[1] = EARTH_RADIUS * ln(tan(PI / 4.0 + latitude * PI / 360.0))
                true
            }
        }
        is GeoTiffCrs.TransverseMercator -> try {
            val tm = TMCoord.fromLatLon(
                latitude.degrees, longitude.degrees, null, null,
                crs.originLatitude.degrees, crs.centralMeridian.degrees,
                crs.falseEasting, crs.falseNorthing, crs.scale
            )
            result[0] = tm.easting
            result[1] = tm.northing
            true
        } catch (ignored: Throwable) {
            false
        }
    }

    /**
     * Geographic bounds of a [width] × [height] raster. The outline is walked rather than
     * just the four corners — a projected raster's edges bow, so corner-only bounds would
     * clip the data along the middle of each side.
     */
    fun sector(width: Int, height: Int): Sector {
        val sector = Sector()
        val point = DoubleArray(2)
        val steps = OUTLINE_STEPS
        for (i in 0..steps) {
            val fx = width.toDouble() * i / steps
            val fy = height.toDouble() * i / steps
            for ((px, py) in listOf(
                fx to 0.0, fx to height.toDouble(), 0.0 to fy, width.toDouble() to fy
            )) {
                if (rasterToGeodetic(px, py, point)) sector.union(point[0].degrees, point[1].degrees)
            }
        }
        return sector
    }

    companion object {
        private const val EARTH_RADIUS = 6378137.0
        /** Web Mercator is undefined at the poles; GDAL clips at ~85.05°. */
        private const val MERCATOR_LIMIT = 89.9
        /** Samples per raster edge when computing the bounding sector. */
        private const val OUTLINE_STEPS = 32

        /** Build the georeference for [dir], or `null` when the file carries no usable
         *  transform or sits in a projection the engine can't invert. */
        fun from(dir: TiffDirectory): GeoTiffGeoReference? {
            val keys = parseGeoKeys(dir)
            val crs = resolveCrs(dir, keys) ?: return null
            val transform = dir.modelTransformation
            var a: Double; var b: Double; var c: Double
            var d: Double; var e: Double; var f: Double
            when {
                transform.size >= 16 && (transform[0] != 0.0 || transform[1] != 0.0) -> {
                    a = transform[0]; b = transform[1]; c = transform[3]
                    d = transform[4]; e = transform[5]; f = transform[7]
                }
                dir.modelPixelScale.size >= 2 && dir.modelTiepoint.size >= 6 -> {
                    val scaleX = dir.modelPixelScale[0]
                    val scaleY = dir.modelPixelScale[1]
                    val i = dir.modelTiepoint[0]
                    val j = dir.modelTiepoint[1]
                    val x = dir.modelTiepoint[3]
                    val y = dir.modelTiepoint[4]
                    a = scaleX; b = 0.0; c = x - i * scaleX
                    d = 0.0; e = -scaleY; f = y + j * scaleY
                }
                else -> {
                    log(WARN, "GeoTIFF has no model transformation or tie point — cannot georeference it")
                    return null
                }
            }
            if (a * e - b * d == 0.0) {
                log(WARN, "GeoTIFF model transformation is degenerate")
                return null
            }
            // RasterPixelIsPoint puts the tie point at the centre of pixel (0,0) rather than its
            // north-west corner; shift the origin half a pixel so raster space stays corner-based.
            if (keys[GeoTiffConstants.GT_RASTER_TYPE_GEO_KEY]?.toInt() == RASTER_PIXEL_IS_POINT) {
                c -= 0.5 * (a + b)
                f -= 0.5 * (d + e)
            }
            // Projected files may measure in feet; normalize the linear unit into metres so the
            // projection maths (which is metric) sees what it expects.
            if (crs !is GeoTiffCrs.Geographic) {
                val toMetres = linearUnitToMetres(keys[GeoTiffConstants.PROJ_LINEAR_UNITS_GEO_KEY]?.toInt())
                if (toMetres != 1.0) {
                    a *= toMetres; b *= toMetres; c *= toMetres
                    d *= toMetres; e *= toMetres; f *= toMetres
                }
            }
            return GeoTiffGeoReference(a, b, c, d, e, f, crs)
        }

        /** Flatten the GeoKey directory into `key -> value`. Keys stored in the double / ASCII
         *  parameter arrays resolve to their referenced entry; ASCII keys are skipped (none of
         *  the keys we act on are textual). */
        private fun parseGeoKeys(dir: TiffDirectory): Map<Int, Double> {
            val directory = dir.geoKeyDirectory
            if (directory.size < 4) return emptyMap()
            val count = directory[3]
            val keys = HashMap<Int, Double>(count.coerceIn(0, 256))
            for (i in 0 until count) {
                val at = 4 + i * 4
                if (at + 3 >= directory.size) break
                val keyId = directory[at]
                val location = directory[at + 1]
                val valueOffset = directory[at + 3]
                when (location) {
                    0 -> keys[keyId] = valueOffset.toDouble()
                    GeoTiffConstants.GEO_DOUBLE_PARAMS ->
                        dir.geoDoubleParams.getOrNull(valueOffset)?.let { keys[keyId] = it }
                }
            }
            return keys
        }

        private fun resolveCrs(dir: TiffDirectory, keys: Map<Int, Double>): GeoTiffCrs? {
            val modelType = keys[GeoTiffConstants.GT_MODEL_TYPE_GEO_KEY]?.toInt()
            if (modelType == MODEL_TYPE_PROJECTED) return projectedCrs(keys)
            if (modelType == MODEL_TYPE_GEOCENTRIC) {
                log(WARN, "Geocentric GeoTIFF files are not supported")
                return null
            }
            // Geographic, or a file with no GeoKeys at all: plain WCS/DEM output whose model
            // coordinates are already degrees. Honour the angular unit when one is declared.
            val toDegrees = when (keys[GeoTiffConstants.GEOG_ANGULAR_UNITS_GEO_KEY]?.toInt()) {
                ANGULAR_RADIAN -> 180.0 / PI
                ANGULAR_GRAD -> 0.9
                else -> 1.0
            }
            if (modelType == null && dir.geoKeyDirectory.isEmpty()) {
                log(INFO, "GeoTIFF has no GeoKeys — assuming geographic (EPSG:4326) coordinates")
            }
            return GeoTiffCrs.Geographic(toDegrees)
        }

        private fun projectedCrs(keys: Map<Int, Double>): GeoTiffCrs? {
            when (val epsg = keys[GeoTiffConstants.PROJECTED_CS_TYPE_GEO_KEY]?.toInt() ?: 0) {
                in WEB_MERCATOR_CODES -> return GeoTiffCrs.WebMercator
                in 32601..32660 -> return utm(epsg - 32600, true)
                in 32701..32760 -> return utm(epsg - 32700, false)
                // NAD83 / NAD27 UTM: same projection, a datum shift under a metre at display
                // scales, so they are rendered on the WGS84 ellipsoid rather than refused.
                in 26901..26960 -> return utm(epsg - 26900, true)
                in 26701..26722 -> return utm(epsg - 26700, true)
                USER_DEFINED_CRS -> {
                    val transform = keys[GeoTiffConstants.PROJ_COORD_TRANS_GEO_KEY]?.toInt()
                    if (transform == CT_TRANSVERSE_MERCATOR) return GeoTiffCrs.TransverseMercator(
                        centralMeridian = keys[GeoTiffConstants.PROJ_NAT_ORIGIN_LONG_GEO_KEY]
                            ?: keys[GeoTiffConstants.PROJ_CENTER_LONG_GEO_KEY] ?: 0.0,
                        originLatitude = keys[GeoTiffConstants.PROJ_NAT_ORIGIN_LAT_GEO_KEY]
                            ?: keys[GeoTiffConstants.PROJ_CENTER_LAT_GEO_KEY] ?: 0.0,
                        falseEasting = keys[GeoTiffConstants.PROJ_FALSE_EASTING_GEO_KEY] ?: 0.0,
                        falseNorthing = keys[GeoTiffConstants.PROJ_FALSE_NORTHING_GEO_KEY] ?: 0.0,
                        scale = keys[GeoTiffConstants.PROJ_SCALE_AT_NAT_ORIGIN_GEO_KEY] ?: 1.0,
                    )
                    log(WARN, "GeoTIFF user-defined projection $transform is not supported")
                    return null
                }
                else -> {
                    log(WARN, "GeoTIFF projected CRS EPSG:$epsg is not supported")
                    return null
                }
            }
        }

        private fun utm(zone: Int, isNorth: Boolean) = if (zone !in 1..60) null else
            GeoTiffCrs.TransverseMercator(
                centralMeridian = (zone - 1) * 6.0 - 180.0 + 3.0,
                originLatitude = 0.0,
                falseEasting = 500000.0,
                falseNorthing = if (isNorth) 0.0 else 10000000.0,
                scale = 0.9996,
            )

        private fun linearUnitToMetres(unit: Int?) = when (unit) {
            LINEAR_FOOT -> 0.3048
            LINEAR_FOOT_US_SURVEY -> 1200.0 / 3937.0
            else -> 1.0
        }

        private const val MODEL_TYPE_PROJECTED = 1
        private const val MODEL_TYPE_GEOCENTRIC = 3
        private const val RASTER_PIXEL_IS_POINT = 2
        private const val USER_DEFINED_CRS = 32767
        private const val CT_TRANSVERSE_MERCATOR = 1
        private const val ANGULAR_RADIAN = 9101
        private const val ANGULAR_GRAD = 9105
        private const val LINEAR_FOOT = 9002
        private const val LINEAR_FOOT_US_SURVEY = 9003
        private val WEB_MERCATOR_CODES = listOf(3857, 3785, 900913, 102100, 102113)
    }
}
