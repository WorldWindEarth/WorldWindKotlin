package earth.worldwind.globe.projection

import earth.worldwind.geom.*
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage

/**
 * Implements an Equirectangular projection, also known as Equidistant Cylindrical, Plate Carree and Rectangular. The
 * projected globe is spherical, not ellipsoidal.
 */
open class EquirectangularProjection : Abstract2DProjection() {
    override val displayName = "Equirectangular"
    override val isContinuous = true

    override fun geographicToCartesian(
        ellipsoid: Ellipsoid, latitude: Angle, longitude: Angle, altitude: Double, offset: Double, result: Vec3
    ): Vec3 {
        result.x = ellipsoid.semiMajorAxis * longitude.inRadians + offset
        result.y = ellipsoid.semiMajorAxis * latitude.inRadians
        result.z = altitude
        return result
    }

    override fun geographicToCartesianGrid(
        ellipsoid: Ellipsoid, sector: Sector, numLat: Int, numLon: Int, height: FloatArray?, verticalExaggeration: Double,
        origin: Vec3?, offset: Double, result: FloatArray, rowOffset: Int, rowStride: Int
    ): FloatArray {
        require(numLat >= 1 && numLon >= 1) {
            logMessage(
                ERROR, "EquirectangularProjection", "geographicToCartesianGrid",
                "Number of latitude or longitude locations is less than one"
            )
        }
        require(height == null || height.size >= numLat * numLon) {
            logMessage(ERROR, "EquirectangularProjection", "geographicToCartesianGrid", "missingArray")
        }

        val eqr = ellipsoid.semiMajorAxis
        val minLat = sector.minLatitude.inRadians
        val maxLat = sector.maxLatitude.inRadians
        val minLon = sector.minLongitude.inRadians
        val maxLon = sector.maxLongitude.inRadians
        val deltaLat = (maxLat - minLat) / if (numLat > 1) numLat - 1 else 1
        val deltaLon = (maxLon - minLon) / if (numLon > 1) numLon - 1 else 1
        var elevIndex = 0
        val xOffset = origin?.x ?: 0.0
        val yOffset = origin?.y ?: 0.0
        val zOffset = origin?.z ?: 0.0

        var rowIndex = rowOffset
        val stride = if (rowStride == 0) numLon * 3 else rowStride
        var lat = minLat
        for (latIndex in 0 until numLat) {
            if (latIndex == numLat - 1) lat = maxLat

            val y = eqr * lat - yOffset

            var lon = minLon
            var colIndex = rowIndex
            for (lonIndex in 0 until numLon) {
                if (lonIndex == numLon - 1) lon = maxLon
                result[colIndex++] = (eqr * lon - xOffset + offset).toFloat()
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
        val minLat = sector.minLatitude.inRadians
        val maxLat = sector.maxLatitude.inRadians
        val minLon = sector.minLongitude.inRadians
        val maxLon = sector.maxLongitude.inRadians
        val deltaLat = (maxLat - minLat) / (if (numLat > 1) numLat - 3 else 1)
        val deltaLon = (maxLon - minLon) / (if (numLon > 1) numLon - 3 else 1)
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
            val y = eqr * lat - yOffset

            var lonIndex = 0
            while (lonIndex < numLon) {
                when {
                    lonIndex < 2 -> lon = minLon
                    lonIndex < numLon - 2 -> lon += deltaLon
                    else -> lon = maxLon
                }
                result[resultIndex++] = (eqr * lon - xOffset + offset).toFloat()
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
        val eqr = ellipsoid.semiMajorAxis
        return result.setRadians(y / eqr, (x - offset) / eqr, z)
    }
}
