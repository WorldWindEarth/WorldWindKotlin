package earth.worldwind.globe.projection

import earth.worldwind.geom.*
import earth.worldwind.globe.Globe
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import kotlin.math.*

/*
 * GeographicProjection implementing coordinate transformations based on the WGS 84 reference system (aka WGS 1984,
 * EPSG:4326).
 *
 * The WGS 84 projection defines a Cartesian coordinate system whose origin is at the globe's center. It's Y axis points
 * to the North Pole, the Z axis points to the intersection of the prime meridian and the equator, and the X axis
 * completes a right-handed coordinate system, is in the equatorial plane and 90 degrees East of the Z axis.
 */
open class Wgs84Projection: GeographicProjection {
    private val scratchPos = Position()

    override val displayName = "WGS84"
    override val is2D = false

    override fun geographicToCartesian(globe: Globe, latitude: Angle, longitude: Angle, altitude: Double, result: Vec3): Vec3 {
        val cosLat = cos(latitude.inRadians)
        val sinLat = sin(latitude.inRadians)
        val cosLon = cos(longitude.inRadians)
        val sinLon = sin(longitude.inRadians)
        val ec2 = globe.eccentricitySquared
        val rpm = globe.equatorialRadius / sqrt(1.0 - ec2 * sinLat * sinLat)
        return result.set(
            (altitude + rpm) * cosLat * sinLon,
            (altitude + rpm * (1.0 - ec2)) * sinLat,
            (altitude + rpm) * cosLat * cosLon
        )
    }

    override fun geographicToCartesianNormal(globe: Globe, latitude: Angle, longitude: Angle, result: Vec3): Vec3 {
        val cosLat = cos(latitude.inRadians)
        val sinLat = sin(latitude.inRadians)
        val cosLon = cos(longitude.inRadians)
        val sinLon = sin(longitude.inRadians)
        val eqr2 = globe.equatorialRadius * globe.equatorialRadius
        val pol2 = globe.polarRadius * globe.polarRadius
        return result.set(
            cosLat * sinLon / eqr2,
            (1 - globe.eccentricitySquared) * sinLat / pol2,
            cosLat * cosLon / eqr2
        ).normalize()
    }

    override fun geographicToCartesianTransform(
        globe: Globe, latitude: Angle, longitude: Angle, altitude: Double, result: Matrix4
    ): Matrix4 {
        val cosLat = cos(latitude.inRadians)
        val sinLat = sin(latitude.inRadians)
        val cosLon = cos(longitude.inRadians)
        val sinLon = sin(longitude.inRadians)
        val ec2 = globe.eccentricitySquared
        val rpm = globe.equatorialRadius / sqrt(1.0 - ec2 * sinLat * sinLat)
        val eqr2 = globe.equatorialRadius * globe.equatorialRadius
        val pol2 = globe.polarRadius * globe.polarRadius

        // Convert the geographic position to Cartesian coordinates. This is equivalent to calling geographicToCartesian
        // but is much more efficient as an inline computation, as the results of cosLat/sinLat/etc. can be computed
        // once and reused.
        val px = (rpm + altitude) * cosLat * sinLon
        val py = (rpm * (1.0 - ec2) + altitude) * sinLat
        val pz = (rpm + altitude) * cosLat * cosLon

        // Compute the surface normal at the geographic position. This is equivalent to calling
        // geographicToCartesianNormal but is much more efficient as an inline computation.
        var ux = cosLat * sinLon / eqr2
        var uy = (1 - globe.eccentricitySquared) * sinLat / pol2
        var uz = cosLat * cosLon / eqr2
        var len = sqrt(ux * ux + uy * uy + uz * uz)
        ux /= len
        uy /= len
        uz /= len

        // Compute the north pointing tangent at the geographic position. This computation could be encoded in its own
        // method, but is much more efficient as an inline computation. The north-pointing tangent is derived by
        // rotating the vector (0, 1, 0) about the Y-axis by longitude degrees, then rotating it about the X-axis by
        // -latitude degrees. The latitude angle must be inverted because latitude is a clockwise rotation about the
        // X-axis, and standard rotation matrices assume counter-clockwise rotation. The combined rotation can be
        // represented by a combining two rotation matrices Rlat, and Rlon, then transforming the vector (0, 1, 0) by
        // the combined transform: NorthTangent = (Rlon * Rlat) * (0, 1, 0)
        //
        // Additionally, this computation can be simplified by making two observations:
        // - The vector's X and Z coordinates are always 0, and its Y coordinate is always 1.
        // - Inverting the latitude rotation angle is equivalent to inverting sinLat. We know this by the
        //   trigonometric identities cos(-x) = cos(x), and sin(-x) = -sin(x).
        var nx = -sinLat * sinLon
        var ny = cosLat
        var nz = -sinLat * cosLon
        len = sqrt(nx * nx + ny * ny + nz * nz)
        nx /= len
        ny /= len
        nz /= len

        // Compute the east pointing tangent as the cross product of the north and up axes. This is much more efficient
        // as an inline computation.
        val ex = ny * uz - nz * uy
        val ey = nz * ux - nx * uz
        val ez = nx * uy - ny * ux

        // Ensure the normal, north and east vectors represent an orthonormal basis by ensuring that the north vector is
        // perpendicular to normal and east vectors. This should already be the case, but rounding errors can be
        // introduced when working with Earth sized coordinates.
        nx = uy * ez - uz * ey
        ny = uz * ex - ux * ez
        nz = ux * ey - uy * ex

        // Set the result to an orthonormal basis with the East, North, and Up vectors forming the X, Y and Z axes,
        // respectively, and the Cartesian point indicating the coordinate system's origin.
        return result.set(
            ex, nx, ux, px,
            ey, ny, uy, py,
            ez, nz, uz, pz,
            0.0, 0.0, 0.0, 1.0
        )
    }

    override fun geographicToCartesianGrid(
        globe: Globe, sector: Sector, numLat: Int, numLon: Int, height: FloatArray?, verticalExaggeration: Float,
        origin: Vec3?, result: FloatArray, offset: Int, rowStride: Int
    ): FloatArray {
        require(numLat >= 1 && numLon >= 1) {
            logMessage(
                ERROR, "Wgs84Projection", "geographicToCartesianGrid",
                "Number of latitude or longitude locations is less than one"
            )
        }
        require(height == null || height.size >= numLat * numLon) {
            logMessage(ERROR, "Wgs84Projection", "geographicToCartesianGrid", "missingArray")
        }
        val minLat = sector.minLatitude.inRadians
        val maxLat = sector.maxLatitude.inRadians
        val minLon = sector.minLongitude.inRadians
        val maxLon = sector.maxLongitude.inRadians
        val deltaLat = (maxLat - minLat) / if (numLat > 1) numLat - 1 else 1
        val deltaLon = (maxLon - minLon) / if (numLon > 1) numLon - 1 else 1
        val eqr = globe.equatorialRadius
        val ec2 = globe.eccentricitySquared
        val cosLon = DoubleArray(numLon)
        val sinLon = DoubleArray(numLon)
        var elevIndex = 0
        val xOffset = origin?.x ?: 0.0
        val yOffset = origin?.y ?: 0.0
        val zOffset = origin?.z ?: 0.0

        // Compute and save values that are a function of each unique longitude value in the specified sector. This
        // eliminates the need to re-compute these values for each column of constant longitude.
        var lon = minLon
        for (lonIndex in 0 until numLon) {
            if (lonIndex == numLon - 1) lon = maxLon // explicitly set the last lon to the max longitude to ensure alignment
            cosLon[lonIndex] = cos(lon)
            sinLon[lonIndex] = sin(lon)
            lon += deltaLon
        }

        // Iterate over the latitude and longitude coordinates in the specified sector, computing the Cartesian
        // point corresponding to each latitude and longitude.
        var rowIndex = offset
        val stride = if (rowStride == 0) numLon * 3 else rowStride
        var lat = minLat
        for (latIndex in 0 until numLat) {
            if (latIndex == numLat - 1) lat = maxLat // explicitly set the last lat to the max latitude to ensure alignment

            // Latitude is constant for each row. Values that are a function of latitude can be computed once per row.
            val cosLat = cos(lat)
            val sinLat = sin(lat)
            val rpm = eqr / sqrt(1.0 - ec2 * sinLat * sinLat)
            var colIndex = rowIndex
            for (lonIndex in 0 until numLon) {
                val hgt = if (height != null) (height[elevIndex++] * verticalExaggeration).toDouble() else 0.0
                result[colIndex++] = ((hgt + rpm) * cosLat * sinLon[lonIndex] - xOffset).toFloat()
                result[colIndex++] = ((hgt + rpm * (1.0 - ec2)) * sinLat - yOffset).toFloat()
                result[colIndex++] = ((hgt + rpm) * cosLat * cosLon[lonIndex] - zOffset).toFloat()
            }
            rowIndex += stride
            lat += deltaLat
        }
        return result
    }

    override fun geographicToCartesianBorder(
        globe: Globe, sector: Sector, numLat: Int, numLon: Int, height: Float, origin: Vec3?, result: FloatArray
    ): FloatArray {
        require(numLat >= 1 && numLon >= 1) {
            logMessage(
                ERROR, "Wgs84Projection", "geographicToCartesianBorder",
                "Number of latitude or longitude locations is less than one"
            )
        }
        val minLat = sector.minLatitude.inRadians
        val maxLat = sector.maxLatitude.inRadians
        val minLon = sector.minLongitude.inRadians
        val maxLon = sector.maxLongitude.inRadians
        val deltaLat = (maxLat - minLat) / if (numLat > 1) numLat - 3 else 1
        val deltaLon = (maxLon - minLon) / if (numLon > 1) numLon - 3 else 1
        var lat = minLat
        var lon = minLon
        val eqr = globe.equatorialRadius
        val ec2 = globe.eccentricitySquared
        val xOffset = origin?.x ?: 0.0
        val yOffset = origin?.y ?: 0.0
        val zOffset = origin?.z ?: 0.0
        var resultIndex = 0

        // Iterate over the edges of the specified sector, computing the Cartesian point at designated latitude and
        // longitude around the border.
        for (latIndex in 0 until numLat) {
            when {
                latIndex < 2 -> lat = minLat // explicitly set the first lat to the min latitude to ensure alignment
                latIndex < numLat - 2 -> lat += deltaLat
                else -> lat = maxLat // explicitly set the last lat to the max latitude to ensure alignment
            }

            // Latitude is constant for each row. Values that are a function of latitude can be computed once per row.
            val cosLat = cos(lat)
            val sinLat = sin(lat)
            val rpm = eqr / sqrt(1.0 - ec2 * sinLat * sinLat)
            var lonIndex = 0
            while (lonIndex < numLon) {
                when {
                    lonIndex < 2 -> lon = minLon // explicitly set the first lon to the min longitude to ensure alignment
                    lonIndex < numLon - 2 -> lon += deltaLon
                    else -> lon = maxLon // explicitly set the last lon to the max longitude to ensure alignment
                }
                val cosLon = cos(lon)
                val sinLon = sin(lon)
                result[resultIndex++] = ((height + rpm) * cosLat * sinLon - xOffset).toFloat()
                result[resultIndex++] = ((height + rpm * (1.0 - ec2)) * sinLat - yOffset).toFloat()
                result[resultIndex++] = ((height + rpm) * cosLat * cosLon - zOffset).toFloat()
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

    override fun cartesianToGeographic(globe: Globe, x: Double, y: Double, z: Double, result: Position): Position {
        // According to H. Vermeille,
        // "An analytical method to transform geocentric into geodetic coordinates"
        // http://www.springerlink.com/content/3t6837t27t351227/fulltext.pdf
        // Journal of Geodesy, accepted 10/2010, not yet published
        val zpx = z * z + x * x
        val zpxSqrt = sqrt(zpx)
        val a = globe.equatorialRadius
        val ra2 = 1 / (a * a)
        val e2 = globe.eccentricitySquared
        val e4 = e2 * e2

        // Step 1
        val p = zpx * ra2
        val q = y * y * (1 - e2) * ra2
        val r = (p + q - e4) / 6
        val h: Double
        val phi: Double
        val evoluteBorderTest = 8 * r * r * r + e4 * p * q
        if (evoluteBorderTest > 0 || q != 0.0) {
            val u: Double
            if (evoluteBorderTest > 0) {
                // Step 2: general case
                val rad1 = sqrt(evoluteBorderTest)
                val rad2 = sqrt(e4 * p * q)

                // 10*e2 is my arbitrary decision of what Vermeille means by "near... the cusps of the evolute".
                val rad = ((rad1 + rad2) * (rad1 + rad2)).pow(1/3.0)
                u = if (evoluteBorderTest > 10 * e2) r + 0.5 * rad + 2 * r * r / rad
                else r + 0.5 * rad + 0.5 * ((rad1 - rad2) * (rad1 - rad2)).pow(1/3.0)
            } else {
                // Step 3: near evolute
                val rad1 = sqrt(-evoluteBorderTest)
                val rad2 = sqrt(-8 * r * r * r)
                val rad3 = sqrt(e4 * p * q)
                val aTan = 2 * atan2(rad3, rad1 + rad2) / 3
                u = -4 * r * sin(aTan) * cos(PI / 6 + aTan)
            }
            val v = sqrt(u * u + e4 * q)
            val w = e2 * (u + v - q) / (2 * v)
            val k = (u + v) / (sqrt(w * w + u + v) + w)
            val d = k * zpxSqrt / (k + e2)
            val dpySqrt = sqrt(d * d + y * y)
            h = (k + e2 - 1) * dpySqrt / k
            phi = 2 * atan2(y, dpySqrt + d)
        } else {
            // Step 4: singular disk
            val rad1 = sqrt(1 - e2)
            val rad2 = sqrt(e2 - p)
            val e = sqrt(e2)
            h = -a * rad1 * rad2 / e
            phi = rad2 / (e * rad2 + rad1 * sqrt(p))
        }

        // Compute lambda
        val s2 = sqrt(2.0)
        val lambda = when {
            (s2 - 1) * x < zpxSqrt + z -> 2 * atan2(x, zpxSqrt + z) // case 1 - -135deg < lambda < 135deg
            zpxSqrt + x < (s2 + 1) * z -> -PI * 0.5 + 2 * atan2(z, zpxSqrt - x) // case 2 - -225deg < lambda < 45deg
            else -> PI * 0.5 - 2 * atan2(z, zpxSqrt + x) // case 3: - -45deg < lambda < 225deg
        }
        return result.setRadians(phi, lambda, h)
    }

    override fun cartesianToLocalTransform(globe: Globe, x: Double, y: Double, z: Double, result: Matrix4): Matrix4 {
        val pos = cartesianToGeographic(globe, x, y, z, scratchPos)
        val cosLat = cos(pos.latitude.inRadians)
        val sinLat = sin(pos.latitude.inRadians)
        val cosLon = cos(pos.longitude.inRadians)
        val sinLon = sin(pos.longitude.inRadians)
        val eqr2 = globe.equatorialRadius * globe.equatorialRadius
        val pol2 = globe.polarRadius * globe.polarRadius

        // Compute the surface normal at the geographic position. This is equivalent to calling
        // geographicToCartesianNormal but is much more efficient as an inline computation.
        var ux = cosLat * sinLon / eqr2
        var uy = (1 - globe.eccentricitySquared) * sinLat / pol2
        var uz = cosLat * cosLon / eqr2
        var len = sqrt(ux * ux + uy * uy + uz * uz)
        ux /= len
        uy /= len
        uz /= len

        // Compute the north pointing tangent at the geographic position. This computation could be encoded in its own
        // method, but is much more efficient as an inline computation. The north-pointing tangent is derived by
        // rotating the vector (0, 1, 0) about the Y-axis by longitude degrees, then rotating it about the X-axis by
        // -latitude degrees. The latitude angle must be inverted because latitude is a clockwise rotation about the
        // X-axis, and standard rotation matrices assume counter-clockwise rotation. The combined rotation can be
        // represented by a combining two rotation matrices Rlat, and Rlon, then transforming the vector (0, 1, 0) by
        // the combined transform: NorthTangent = (Rlon * Rlat) * (0, 1, 0)
        //
        // Additionally, this computation can be simplified by making two observations:
        // - The vector's X and Z coordinates are always 0, and its Y coordinate is always 1.
        // - Inverting the latitude rotation angle is equivalent to inverting sinLat. We know this by the
        //   trigonometric identities cos(-x) = cos(x), and sin(-x) = -sin(x).
        var nx = -sinLat * sinLon
        var ny = cosLat
        var nz = -sinLat * cosLon
        len = sqrt(nx * nx + ny * ny + nz * nz)
        nx /= len
        ny /= len
        nz /= len

        // Compute the east pointing tangent as the cross product of the north and up axes. This is much more efficient
        // as an inline computation.
        val ex = ny * uz - nz * uy
        val ey = nz * ux - nx * uz
        val ez = nx * uy - ny * ux

        // Ensure the normal, north and east vectors represent an orthonormal basis by ensuring that the north vector is
        // perpendicular to normal and east vectors. This should already be the case, but rounding errors can be
        // introduced when working with Earth sized coordinates.
        nx = uy * ez - uz * ey
        ny = uz * ex - ux * ez
        nz = ux * ey - uy * ex

        // Set the result to an orthonormal basis with the East, North, and Up vectors forming the X, Y and Z axes,
        // respectively, and the Cartesian point indicating the coordinate system's origin.
        return result.set(
            ex, nx, ux, x,
            ey, ny, uy, y,
            ez, nz, uz, z,
            0.0, 0.0, 0.0, 1.0
        )
    }

    override fun intersect(globe: Globe, line: Line, result: Vec3): Boolean {
        // Taken from "Mathematics for 3D Game Programming and Computer Graphics, Third Edition", Section 6.2.3.
        // Note that the parameter n from in equations 6.70 and 6.71 is omitted here. For an ellipsoidal globe this
        // parameter is always 1, so its square and its product with any other value simplifies to the identity.
        val vx = line.direction.x
        val vy = line.direction.y
        val vz = line.direction.z
        val sx = line.origin.x
        val sy = line.origin.y
        val sz = line.origin.z
        val eqr = globe.equatorialRadius
        val eqr2 = eqr * eqr // nominal radius squared
        val m = eqr / globe.polarRadius // ratio of the x semi-axis length to the y semi-axis length
        val m2 = m * m
        val a = vx * vx + m2 * vy * vy + vz * vz
        val b = 2 * (sx * vx + m2 * sy * vy + sz * vz)
        val c = sx * sx + m2 * sy * sy + sz * sz - eqr2
        val d = b * b - 4 * a * c // discriminant
        if (d < 0) return false
        var t = (-b - sqrt(d)) / (2 * a)
        // check if the nearest intersection point is in front of the origin of the ray
        if (t > 0) {
            result.set(sx + vx * t, sy + vy * t, sz + vz * t)
            return true
        }
        t = (-b + sqrt(d)) / (2 * a)
        // check if the second intersection point is in front of the origin of the ray
        if (t > 0) {
            result.set(sx + vx * t, sy + vy * t, sz + vz * t)
            return true
        }

        // the intersection points were behind the origin of the provided line
        return false
    }
}