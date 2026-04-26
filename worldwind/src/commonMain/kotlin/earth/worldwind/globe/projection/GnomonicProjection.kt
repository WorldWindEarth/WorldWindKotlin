package earth.worldwind.globe.projection

import earth.worldwind.geom.*
import earth.worldwind.geom.Sector.Companion.fromDegrees
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import kotlin.math.*

/**
 * Represents a polar Gnomonic geographic projection.
 *
 * @param isNorth `true` for the north aspect, `false` for the south aspect.
 */
open class GnomonicProjection(
    /**
     * Indicates whether this projection is the north or south aspect.
     */
    var isNorth: Boolean = true
) : Abstract2DProjection() {
    override val displayName get() = if (isNorth) "North Gnomonic" else "South Gnomonic"
    override val projectionLimits get() =
        if (isNorth) fromDegrees(30.0, -180.0, 60.0, 360.0) else fromDegrees(-90.0, -180.0, 60.0, 360.0)

    override fun geographicToCartesian(
        ellipsoid: Ellipsoid, latitude: Angle, longitude: Angle, altitude: Double, offset: Double, result: Vec3
    ): Vec3 {
        // Formulae taken from "Map Projections -- A Working Manual", Snyder, USGS paper 1395, pg. 167.
        val latRad = latitude.inRadians
        val lonRad = longitude.inRadians
        if ((isNorth && latRad == PI / 2) || (!isNorth && latRad == -PI / 2)) {
            result.x = 0.0
            result.y = 0.0
            result.z = altitude
        } else {
            val poleFactor = if (isNorth) 1 else -1
            val a = ellipsoid.semiMajorAxis / tan(latRad) // R cot(phi)
            result.x = a * sin(lonRad) * poleFactor // eqs. 22-6, 22-10
            result.y = a * -cos(lonRad) // eqs. 22-7, 22-11
            result.z = altitude
        }
        return result
    }

    override fun geographicToCartesianGrid(
        ellipsoid: Ellipsoid, sector: Sector, numLat: Int, numLon: Int, height: FloatArray?, verticalExaggeration: Double,
        origin: Vec3?, offset: Double, result: FloatArray, rowOffset: Int, rowStride: Int
    ): FloatArray {
        require(numLat >= 1 && numLon >= 1) {
            logMessage(
                ERROR, "GnomonicProjection", "geographicToCartesianGrid",
                "Number of latitude or longitude locations is less than one"
            )
        }
        require(height == null || height.size >= numLat * numLon) {
            logMessage(ERROR, "GnomonicProjection", "geographicToCartesianGrid", "missingArray")
        }

        val eqr = ellipsoid.semiMajorAxis
        val poleFactor = if (isNorth) 1 else -1
        val minLat = sector.minLatitude.inRadians
        val maxLat = sector.maxLatitude.inRadians
        val minLon = sector.minLongitude.inRadians
        val maxLon = sector.maxLongitude.inRadians
        val deltaLat = (maxLat - minLat) / if (numLat > 1) numLat - 1 else 1
        val deltaLon = (maxLon - minLon) / if (numLon > 1) numLon - 1 else 1
        val minLatLimit = projectionLimits.minLatitude.inRadians
        val maxLatLimit = projectionLimits.maxLatitude.inRadians
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
            var a = eqr / tan(clampedLat)
            if ((isNorth && clampedLat == PI / 2) || (!isNorth && clampedLat == -PI / 2)) a = 0.0

            var lon = minLon
            var colIndex = rowIndex
            for (lonIndex in 0 until numLon) {
                if (lonIndex == numLon - 1) lon = maxLon
                result[colIndex++] = (a * sin(lon) * poleFactor - xOffset).toFloat()
                result[colIndex++] = (a * -cos(lon) - yOffset).toFloat()
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
        val eqr = ellipsoid.semiMajorAxis
        val poleFactor = if (isNorth) 1 else -1
        val minLat = sector.minLatitude.inRadians
        val maxLat = sector.maxLatitude.inRadians
        val minLon = sector.minLongitude.inRadians
        val maxLon = sector.maxLongitude.inRadians
        val deltaLat = (maxLat - minLat) / (if (numLat > 1) numLat - 3 else 1)
        val deltaLon = (maxLon - minLon) / (if (numLon > 1) numLon - 3 else 1)
        val minLatLimit = projectionLimits.minLatitude.inRadians
        val maxLatLimit = projectionLimits.maxLatitude.inRadians
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
            var a = eqr / tan(clampedLat)
            if ((isNorth && clampedLat == PI / 2) || (!isNorth && clampedLat == -PI / 2)) a = 0.0

            var lonIndex = 0
            while (lonIndex < numLon) {
                when {
                    lonIndex < 2 -> lon = minLon
                    lonIndex < numLon - 2 -> lon += deltaLon
                    else -> lon = maxLon
                }
                result[resultIndex++] = (a * sin(lon) * poleFactor - xOffset).toFloat()
                result[resultIndex++] = (a * -cos(lon) - yOffset).toFloat()
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
        // Formulae taken from "Map Projections -- A Working Manual", Snyder, USGS paper 1395, pg. 167.
        val rho = sqrt(x * x + y * y)
        if (rho < 1.0e-4) {
            result.latitude = if (isNorth) Angle.POS90 else Angle.NEG90
            result.longitude = Angle.ZERO
            result.altitude = z
        } else {
            val c = atan2(rho, ellipsoid.semiMajorAxis).coerceAtMost(PI)
            val latSign = if (isNorth) 1 else -1
            val ySign = if (isNorth) -1 else 1
            result.setRadians(asin(cos(c) * latSign), atan2(x, y * ySign), z)
        }
        return result
    }
}
