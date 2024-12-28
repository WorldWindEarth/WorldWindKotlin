package earth.worldwind.globe

import earth.worldwind.geom.*
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.globe.elevation.ElevationModel
import earth.worldwind.globe.geoid.EGM96Geoid
import earth.worldwind.globe.geoid.Geoid
import earth.worldwind.globe.projection.GeographicProjection
import earth.worldwind.globe.projection.Wgs84Projection
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Planet or celestial object approximated by a reference ellipsoid and elevation models. Globe expresses its
 * ellipsoidal parameters and elevation values in meters.
 */
open class Globe(
    /**
     * The globe's reference ellipsoid defining the globe's equatorial radius and polar radius.
     */
    var ellipsoid: Ellipsoid = Ellipsoid.WGS84,
    /**
     * Indicates the geographic projection used by this globe. The projection specifies this globe's Cartesian
     * coordinate system.
     */
    var projection: GeographicProjection = Wgs84Projection(),
    /**
     * Represents Gravitational Model of the globe
     */
    var geoid: Geoid = EGM96Geoid()
) {
    /**
     * Represents the elevations for an area, often but not necessarily the whole globe.
     */
    var elevationModel = ElevationModel()
    /**
     * Indicates the radius in meters of the globe's ellipsoid at the equator.
     */
    val equatorialRadius get() = ellipsoid.semiMajorAxis
    /**
     * Indicates the radius in meters of the globe's ellipsoid at the poles.
     */
    val polarRadius get() = ellipsoid.semiMinorAxis
    /**
     * Indicates whether this is a 2D globe.
     */
    val is2D get() = projection.is2D
    /**
     * Indicates whether this projection is continuous with itself horizontally.
     */
    val isContinuous get() = projection.isContinuous
    /**
     * Indicates the geographic limits of this projection.
     */
    val projectionLimits get() = projection.projectionLimits
    /**
     * Current globe state.
     */
    val state get() = State(ellipsoid, projection.displayName)
    /**
     * The globe offset in 2D continuous projection. Center is the default for 3D.
     */
    var offset = Offset.Center
        set (value) {
            field = value
            // Calculate horizontal projection offset in meters
            offsetValue = when (value) {
                Offset.Center -> 0.0
                Offset.Right -> 2.0 * PI * ellipsoid.semiMajorAxis
                Offset.Left -> -2.0 * PI * ellipsoid.semiMajorAxis
            }
        }
    protected var offsetValue = 0.0

    /**
     * An offset to apply to this globe when translating between Geographic positions and Cartesian points.
     * Used during scrolling to position points appropriately.
     */
    enum class Offset { Center, Right, Left }

    /**
     * Used to compare states during rendering to determine whether globe-state dependent cached values must be updated.
     */
    data class State(private val ellipsoid: Ellipsoid, private val projectionName: String)

    /**
     * Indicates the radius in meters of the globe's ellipsoid at a specified location.
     *
     * @param latitude  the location's latitude
     * @param longitude the location's longitude
     *
     * @return the radius in meters of the globe's ellipsoid at the specified location
     */
    @Suppress("UNUSED_PARAMETER")
    fun getRadiusAt(latitude: Angle, longitude: Angle): Double {
        // The radius for an ellipsoidal globe is a function of its latitude. The following solution was derived by
        // observing that the length of the ellipsoidal point at the specified latitude and longitude indicates the
        // radius at that location. The formula for the length of the ellipsoidal point was then converted into the
        // simplified form below.
        val sinLat = sin(latitude.inRadians)
        val ec2 = ellipsoid.eccentricitySquared
        val rpm = ellipsoid.semiMajorAxis / sqrt(1 - ec2 * sinLat * sinLat)
        return rpm * sqrt(1 + (ec2 * ec2 - 2 * ec2) * sinLat * sinLat)
    }

    /**
     * Converts a geographic position to Cartesian coordinates. This globe's projection specifies the Cartesian
     * coordinate system.
     *
     * @param latitude  the position's latitude
     * @param longitude the position's longitude
     * @param altitude  the position's altitude in meters
     * @param result    a pre-allocated [Vec3] in which to store the computed X, Y and Z Cartesian coordinates
     *
     * @return the result argument, set to the computed Cartesian coordinates
     */
    fun geographicToCartesian(latitude: Angle, longitude: Angle, altitude: Double, result: Vec3) =
        projection.geographicToCartesian(ellipsoid, latitude, longitude, altitude, offsetValue, result)

    fun geographicToCartesianNormal(latitude: Angle, longitude: Angle, result: Vec3) =
        projection.geographicToCartesianNormal(ellipsoid, latitude, longitude, result)

    fun geographicToCartesianTransform(latitude: Angle, longitude: Angle, altitude: Double, result: Matrix4) =
        projection.geographicToCartesianTransform(ellipsoid, latitude, longitude, altitude, result)

    fun geographicToCartesianGrid(
        sector: Sector, numLat: Int, numLon: Int, height: FloatArray?, verticalExaggeration: Float,
        origin: Vec3?, result: FloatArray, rowOffset: Int = 0, rowStride: Int = 0
    ) = projection.geographicToCartesianGrid(
        ellipsoid, sector, numLat, numLon, height, verticalExaggeration,
        origin, offsetValue, result, rowOffset, rowStride
    )

    fun geographicToCartesianBorder(
        sector: Sector, numLat: Int, numLon: Int, height: Float, origin: Vec3, result: FloatArray
    ) = projection.geographicToCartesianBorder(ellipsoid, sector, numLat, numLon, height, origin, offsetValue, result)

    /**
     * Converts a Cartesian point to a geographic position. This globe's projection specifies the Cartesian coordinate
     * system.
     *
     * @param x      the Cartesian point's X component
     * @param y      the Cartesian point's Y component
     * @param z      the Cartesian point's Z component
     * @param result a pre-allocated [Position] in which to store the computed geographic position
     *
     * @return the result argument, set to the computed geographic position
     */
    fun cartesianToGeographic(x: Double, y: Double, z: Double, result: Position) =
        projection.cartesianToGeographic(ellipsoid, x, y, z, offsetValue, result).also {
            if (is2D) result.longitude = result.longitude.normalize180()
        }

    fun cartesianToLocalTransform(x: Double, y: Double, z: Double, result: Matrix4) =
        projection.cartesianToLocalTransform(ellipsoid, x, y, z, result)

    /**
     * Indicates the distance to the globe's horizon from a specified height above the globe's ellipsoid. The result of
     * this method is undefined if the height is negative.
     *
     * @param height the viewer's height above the globe's ellipsoid in meters
     *
     * @return the horizon distance in meters
     */
    fun horizonDistance(height: Double) = if (height > 0.0) sqrt(height * (2 * ellipsoid.semiMajorAxis + height)) else 0.0

    /**
     * Computes the first intersection of this globe with a specified line. The line is interpreted as a ray;
     * intersection points behind the line's origin are ignored.
     *
     * @param line   the line to intersect with this globe
     * @param result a pre-allocated [Vec3] in which to return the computed point
     *
     * @return true if the ray intersects the globe, otherwise false
     */
    fun intersect(line: Line, result: Vec3) = projection.intersect(ellipsoid, line, result)

    /**
     * Determine terrain altitude in specified geographic point from elevation model
     *
     * @param latitude  location latitude
     * @param longitude location longitude
     * @param retrieve  retrieve the most detailed elevation data instead of using first available cached value
     *
     * @return Elevation in meters in specified location
     */
    fun getElevation(latitude: Angle, longitude: Angle, retrieve: Boolean = false) =
        (elevationModel.getElevation(latitude, longitude, retrieve) + geoid.getOffset(latitude, longitude)).toDouble()

    /**
     * Gets elevation values for the specified sector with required width and height resolution, including Geoid offset
     *
     * @param gridSector specified sector to determine elevation values
     * @param gridWidth value matrix width
     * @param gridHeight value matrix height
     * @param result pre-allocated array for the result. Must be width * height size.
     */
    fun getElevationGrid(gridSector: Sector, gridWidth: Int, gridHeight: Int, result: FloatArray) {
        elevationModel.getElevationGrid(gridSector, gridWidth, gridHeight, result)
        // Apply Gravitational Model offset
        val minLat = gridSector.minLatitude.inDegrees
        val minLon = gridSector.minLongitude.inDegrees
        val deltaLat = gridSector.deltaLatitude.inDegrees / (gridHeight - 1)
        val deltaLon = gridSector.deltaLongitude.inDegrees / (gridWidth - 1)
        var h = 0
        for (i in 0 until gridHeight) {
            val lat = minLat + i * deltaLat
            for (j in 0 until gridWidth) {
                val lon = minLon + j * deltaLon
                result[h++] += geoid.getOffset(lat.degrees, lon.degrees)
            }
        }
    }

    /**
     * Gets elevation limits at specified sector, including Geoid offset
     *
     * @param sector specified sector to determine elevation limits
     * @return pre-allocated array for the result. Must be size of 2.
     */
    fun getElevationLimits(sector: Sector, result: FloatArray) {
        elevationModel.getElevationLimits(sector, result)
        // Apply Gravitational Model offset
        val offset = geoid.getOffset(sector.centroidLatitude, sector.centroidLongitude)
        for (i in result.indices) result[i] += offset
    }

    /**
     * Get absolute position with terrain elevation at specified coordinates
     *
     * @param latitude Specified latitude
     * @param longitude Specified longitude
     *
     * @return Absolute position with terrain elevation
     */
    fun getAbsolutePosition(latitude: Angle, longitude: Angle) =
        Position(latitude, longitude, getElevation(latitude, longitude, retrieve = true))

    /**
     * Get absolute position for specified position and specified altitude mode
     *
     * @param position Specified position
     * @param altitudeMode Specified altitude mode
     *
     * @return Absolute position for specified altitude mode
     */
    fun getAbsolutePosition(position: Position, altitudeMode: AltitudeMode) = when (altitudeMode) {
        AltitudeMode.CLAMP_TO_GROUND -> getAbsolutePosition(position.latitude, position.longitude)
        AltitudeMode.RELATIVE_TO_GROUND -> getAbsolutePosition(position.latitude, position.longitude).apply {
            altitude += position.altitude
        }
        else -> Position(position)
    }

}