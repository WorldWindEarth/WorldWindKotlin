package earth.worldwind.globe.projection

import earth.worldwind.geom.*
import earth.worldwind.geom.Sector.Companion.fromDegrees
import earth.worldwind.util.Logger
import kotlin.math.*

open class MercatorProjection : Abstract2DProjection() {
    override val displayName = "Mercator"
    override val isContinuous = true
    override val projectionLimits = fromDegrees(-78.0, -180.0, 156.0, 360.0)

    override fun geographicToCartesian(
        ellipsoid: Ellipsoid, latitude: Angle, longitude: Angle, altitude: Double, offset: Double, result: Vec3
    ): Vec3 {
        val lat = latitude.coerceIn(projectionLimits.minLatitude, projectionLimits.maxLatitude)
        val lon = longitude.coerceIn(projectionLimits.minLongitude, projectionLimits.maxLongitude)

        // See "Map Projections: A Working Manual", page 44 for the source of the below formulas.
        val ecc = sqrt(ellipsoid.eccentricitySquared)
        val sinPhi = sin(lat.inRadians)
        val s = (1 + sinPhi) / (1 - sinPhi) * ((1 - ecc * sinPhi) / (1 + ecc * sinPhi)).pow(ecc)
        result.x = ellipsoid.semiMajorAxis * lon.inRadians + offset
        result.y = 0.5 * ellipsoid.semiMajorAxis * ln(s)
        result.z = altitude
        return result
    }

    override fun geographicToCartesianGrid(
        ellipsoid: Ellipsoid, sector: Sector, numLat: Int, numLon: Int, height: FloatArray?, verticalExaggeration: Double,
        origin: Vec3?, offset: Double, result: FloatArray, rowOffset: Int, rowStride: Int
    ): FloatArray {
        require(numLat >= 1 && numLon >= 1) {
            Logger.logMessage(
                Logger.ERROR, "MercatorProjection", "geographicToCartesianGrid",
                "Number of latitude or longitude locations is less than one"
            )
        }
        require(height == null || height.size >= numLat * numLon) {
            Logger.logMessage(Logger.ERROR, "MercatorProjection", "geographicToCartesianGrid", "missingArray")
        }

        val eqr = ellipsoid.semiMajorAxis
        val ecc = sqrt(ellipsoid.eccentricitySquared)
        val minLat = sector.minLatitude.inRadians
        val maxLat = sector.maxLatitude.inRadians
        val minLon = sector.minLongitude.inRadians
        val maxLon = sector.maxLongitude.inRadians
        val deltaLat = (maxLat - minLat) / if (numLat > 1) numLat - 1 else 1
        val deltaLon = (maxLon - minLon) / if (numLon > 1) numLon - 1 else 1
        val minLatLimit = projectionLimits.minLatitude.inRadians
        val maxLatLimit = projectionLimits.maxLatitude.inRadians
        val minLonLimit = projectionLimits.minLongitude.inRadians
        val maxLonLimit = projectionLimits.maxLongitude.inRadians
        var elevIndex = 0
        val xOffset = origin?.x ?: 0.0
        val yOffset = origin?.y ?: 0.0
        val zOffset = origin?.z ?: 0.0

        // Iterate over the latitude and longitude coordinates in the specified sector, computing the Cartesian point
        // corresponding to each latitude and longitude.
        var rowIndex = rowOffset
        val stride = if (rowStride == 0) numLon * 3 else rowStride
        var lat = minLat
        for (latIndex in 0 until numLat) {
            if (latIndex == numLat - 1) lat = maxLat // explicitly set the last lat to the max latitude to ensure alignment

            // Latitude is constant for each row. Values that are a function of latitude can be computed once per row.
            val sinLat = sin(lat.coerceIn(minLatLimit, maxLatLimit))
            val s = (1 + sinLat) / (1 - sinLat) * ((1 - ecc * sinLat) / (1 + ecc * sinLat)).pow(ecc)
            val y = eqr * ln(s) * 0.5 - yOffset

            var lon = minLon
            var colIndex = rowIndex
            for (lonIndex in 0 until numLon) {
                if (lonIndex == numLon - 1) lon = maxLon // explicitly set the last lon to the max longitude to ensure alignment
                result[colIndex++] = (eqr * lon.coerceIn(minLonLimit, maxLonLimit) - xOffset + offset).toFloat()
                result[colIndex++] = y.toFloat()
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
        val ecc = sqrt(ellipsoid.eccentricitySquared)
        val minLat = sector.minLatitude.inRadians
        val maxLat = sector.maxLatitude.inRadians
        val minLon = sector.minLongitude.inRadians
        val maxLon = sector.maxLongitude.inRadians
        val deltaLat = (maxLat - minLat) / (if (numLat > 1) numLat - 3 else 1)
        val deltaLon = (maxLon - minLon) / (if (numLon > 1) numLon - 3 else 1)
        val minLatLimit = projectionLimits.minLatitude.inRadians
        val maxLatLimit = projectionLimits.maxLatitude.inRadians
        val minLonLimit = projectionLimits.minLongitude.inRadians
        val maxLonLimit = projectionLimits.maxLongitude.inRadians
        val xOffset = origin?.x ?: 0.0
        val yOffset = origin?.y ?: 0.0
        val zOffset = origin?.z ?: 0.0

        // Iterate over the latitude and longitude coordinates in the specified sector, computing the Cartesian point
        // corresponding to each latitude and longitude.
        var resultIndex = 0
        var lat = minLat
        var lon = minLon
        for (latIndex in 0 until numLat) {
            when {
                latIndex < 2 -> lat = minLat // explicitly set the first lat to the min latitude to ensure alignment
                latIndex < numLat - 2 -> lat += deltaLat
                else -> lat = maxLat // explicitly set the last lat to the max latitude to ensure alignment
            }
            // Latitude is constant for each row. Values that are a function of latitude can be computed once per row.
            val sinLat = sin(lat.coerceIn(minLatLimit, maxLatLimit))
            val s = ((1 + sinLat) / (1 - sinLat)) * ((1 - ecc * sinLat) / (1 + ecc * sinLat)).pow(ecc)
            val y = eqr * ln(s) * 0.5 - yOffset

            var lonIndex = 0
            while (lonIndex < numLon) {
                when {
                    lonIndex < 2 -> lon = minLon
                    lonIndex < numLon - 2 -> lon += deltaLon
                    else -> lon = maxLon
                }
                result[resultIndex++] = (eqr * lon.coerceIn(minLonLimit, maxLonLimit) - xOffset + offset).toFloat()
                result[resultIndex++] = y.toFloat()
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
        // See "Map Projections: A Working Manual", pages 44-45 for the source of the below formulas.
        val eqr = ellipsoid.semiMajorAxis
        val ecc = sqrt(ellipsoid.eccentricitySquared)
        val t = exp(-y / ellipsoid.semiMajorAxis) // eq (7-10)

        // Inverse projection requires converging series
        var phi = PI / 2 - 2 * atan(t) // eq (7-11) [first trial]
        var phiLast = 0.0
        while (abs(phi - phiLast) > CONVERGENCE_FACTOR) {
            phiLast = phi
            phi = PI / 2.0 - 2.0 * atan(t * ((1 - ecc * sin(phi)) / (1 + ecc * sin(phi))).pow(ecc / 2.0)) // eq (7-9)
        }

        val lambda = (x - offset) / eqr // eq (7-12)

        return result.setRadians(phi, lambda, z)
    }

    companion object {
        private const val CONVERGENCE_FACTOR = 0.000001
    }
}