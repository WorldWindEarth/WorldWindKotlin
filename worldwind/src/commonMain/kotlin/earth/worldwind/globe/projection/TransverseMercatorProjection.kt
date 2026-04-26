package earth.worldwind.globe.projection

import earth.worldwind.geom.*
import earth.worldwind.geom.Angle.Companion.ZERO
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Angle.Companion.fromDegrees
import earth.worldwind.geom.Angle.Companion.radians
import earth.worldwind.geom.coords.TMCoord
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage

/**
 * Provides a Transverse Mercator ellipsoidal projection. The projection's central meridian may be specified and
 * defaults to the Prime Meridian (0 longitude). By default, the projection computes values for 30 degrees either side
 * of the central meridian; large widths may cause the projection to fail.
 *
 * The projection limits reflect the central meridian and the width, clamped to a minimum of -180 degrees and a maximum
 * of +180 degrees, so a band whose central meridian is +/-180 cannot be displayed.
 */
open class TransverseMercatorProjection(
    centralMeridian: Angle = ZERO, centralLatitude: Angle = ZERO, width: Angle = DEFAULT_WIDTH
) : Abstract2DProjection() {
    override val displayName = "Transverse Mercator"

    var centralMeridian: Angle = centralMeridian
    var centralLatitude: Angle = centralLatitude
    var width: Angle = width
    override val projectionLimits get() = makeProjectionLimits(centralMeridian, width)
    protected open val scale = 1.0

    override fun geographicToCartesian(
        ellipsoid: Ellipsoid, latitude: Angle, longitude: Angle, altitude: Double, offset: Double, result: Vec3
    ): Vec3 {
        val lat = latitude.coerceIn(MIN_LAT, MAX_LAT)
        val minLon = centralMeridian - width
        val maxLon = centralMeridian + width
        val lon = longitude.coerceIn(minLon, maxLon)
        val tm = TMCoord.fromLatLon(
            lat, lon, ellipsoid.semiMajorAxis, 1.0 / ellipsoid.inverseFlattening,
            centralLatitude, centralMeridian, 0.0, 0.0, scale
        )
        result.x = tm.easting
        result.y = tm.northing
        result.z = altitude
        return result
    }

    override fun geographicToCartesianGrid(
        ellipsoid: Ellipsoid, sector: Sector, numLat: Int, numLon: Int, height: FloatArray?, verticalExaggeration: Double,
        origin: Vec3?, offset: Double, result: FloatArray, rowOffset: Int, rowStride: Int
    ): FloatArray {
        require(numLat >= 1 && numLon >= 1) {
            logMessage(
                ERROR, "TransverseMercatorProjection", "geographicToCartesianGrid",
                "Number of latitude or longitude locations is less than one"
            )
        }
        require(height == null || height.size >= numLat * numLon) {
            logMessage(ERROR, "TransverseMercatorProjection", "geographicToCartesianGrid", "missingArray")
        }

        val a = ellipsoid.semiMajorAxis
        val f = 1.0 / ellipsoid.inverseFlattening
        val minLat = sector.minLatitude.inRadians
        val maxLat = sector.maxLatitude.inRadians
        val minLon = sector.minLongitude.inRadians
        val maxLon = sector.maxLongitude.inRadians
        val deltaLat = (maxLat - minLat) / if (numLat > 1) numLat - 1 else 1
        val deltaLon = (maxLon - minLon) / if (numLon > 1) numLon - 1 else 1
        val minLatLimit = MIN_LAT.inRadians
        val maxLatLimit = MAX_LAT.inRadians
        val minLonLimit = (centralMeridian - width).inRadians
        val maxLonLimit = (centralMeridian + width).inRadians
        var elevIndex = 0
        val xOffset = origin?.x ?: 0.0
        val yOffset = origin?.y ?: 0.0
        val zOffset = origin?.z ?: 0.0

        var rowIndex = rowOffset
        val stride = if (rowStride == 0) numLon * 3 else rowStride
        var lat = minLat
        for (latIndex in 0 until numLat) {
            if (latIndex == numLat - 1) lat = maxLat
            val clampedLat = lat.coerceIn(minLatLimit, maxLatLimit)

            var lon = minLon
            var colIndex = rowIndex
            for (lonIndex in 0 until numLon) {
                if (lonIndex == numLon - 1) lon = maxLon
                val clampedLon = lon.coerceIn(minLonLimit, maxLonLimit)
                val tm = TMCoord.fromLatLon(
                    clampedLat.radians, clampedLon.radians, a, f,
                    centralLatitude, centralMeridian, 0.0, 0.0, scale
                )
                result[colIndex++] = (tm.easting - xOffset).toFloat()
                result[colIndex++] = (tm.northing - yOffset).toFloat()
                result[colIndex++] = if (height != null) (height[elevIndex++] * verticalExaggeration - zOffset).toFloat() else 0f
                lon += deltaLon
            }
            rowIndex += stride
            lat += deltaLat
        }
        return result
    }

    override fun geographicToCartesianBorder(
        ellipsoid: Ellipsoid, sector: Sector, numLat: Int, numLon: Int, height: Float,
        origin: Vec3?, offset: Double, result: FloatArray
    ): FloatArray {
        val a = ellipsoid.semiMajorAxis
        val f = 1.0 / ellipsoid.inverseFlattening
        val minLat = sector.minLatitude.inRadians
        val maxLat = sector.maxLatitude.inRadians
        val minLon = sector.minLongitude.inRadians
        val maxLon = sector.maxLongitude.inRadians
        val deltaLat = (maxLat - minLat) / (if (numLat > 1) numLat - 3 else 1)
        val deltaLon = (maxLon - minLon) / (if (numLon > 1) numLon - 3 else 1)
        val minLatLimit = MIN_LAT.inRadians
        val maxLatLimit = MAX_LAT.inRadians
        val minLonLimit = (centralMeridian - width).inRadians
        val maxLonLimit = (centralMeridian + width).inRadians
        val xOffset = origin?.x ?: 0.0
        val yOffset = origin?.y ?: 0.0
        val zOffset = origin?.z ?: 0.0

        var resultIndex = 0
        var lat = minLat
        var lon = minLon
        for (latIndex in 0 until numLat) {
            when {
                latIndex < 2 -> lat = minLat
                latIndex < numLat - 2 -> lat += deltaLat
                else -> lat = maxLat
            }
            val clampedLat = lat.coerceIn(minLatLimit, maxLatLimit)

            var lonIndex = 0
            while (lonIndex < numLon) {
                when {
                    lonIndex < 2 -> lon = minLon
                    lonIndex < numLon - 2 -> lon += deltaLon
                    else -> lon = maxLon
                }
                val clampedLon = lon.coerceIn(minLonLimit, maxLonLimit)
                val tm = TMCoord.fromLatLon(
                    clampedLat.radians, clampedLon.radians, a, f,
                    centralLatitude, centralMeridian, 0.0, 0.0, scale
                )
                result[resultIndex++] = (tm.easting - xOffset).toFloat()
                result[resultIndex++] = (tm.northing - yOffset).toFloat()
                result[resultIndex++] = height - zOffset.toFloat()
                if (lonIndex == 0 && latIndex != 0 && latIndex != numLat - 1) {
                    val skip = numLon - 2
                    lonIndex += skip
                    resultIndex += skip * 3
                }
                lonIndex++
            }
        }
        return result
    }

    override fun cartesianToGeographic(
        ellipsoid: Ellipsoid, x: Double, y: Double, z: Double, offset: Double, result: Position
    ): Position {
        val tm = TMCoord.fromTM(x, y, centralLatitude, centralMeridian, 0.0, 0.0, scale)
        return result.set(tm.latitude, tm.longitude, z)
    }

    companion object {
        val DEFAULT_WIDTH: Angle = 30.0.degrees
        // The Transverse Mercator series breaks down beyond this band; latitudes outside it are clamped before projection.
        private val MIN_LAT: Angle = (-82.0).degrees
        private val MAX_LAT: Angle = 86.0.degrees

        private fun makeProjectionLimits(centralMeridian: Angle, width: Angle): Sector {
            val minLon = (centralMeridian.inDegrees - width.inDegrees).coerceAtLeast(-180.0)
            val maxLon = (centralMeridian.inDegrees + width.inDegrees).coerceAtMost(180.0)
            return Sector(fromDegrees(-90.0), fromDegrees(90.0), fromDegrees(minLon), fromDegrees(maxLon))
        }
    }
}
