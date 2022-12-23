package earth.worldwind.globe

import earth.worldwind.geom.*
import earth.worldwind.geom.Angle.Companion.ZERO
import earth.worldwind.globe.elevation.ElevationModel
import earth.worldwind.globe.projection.GeographicProjection
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
    var ellipsoid: Ellipsoid,
    /**
     * Indicates the geographic projection used by this globe. The projection specifies this globe's Cartesian
     * coordinate system.
     */
    var projection: GeographicProjection
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
     * Indicates the eccentricity squared parameter of the globe's ellipsoid. This is equivalent to `2*f -
     * f*f`, where `f` is the ellipsoid's flattening parameter.
     */
    val eccentricitySquared get() = ellipsoid.eccentricitySquared
    /**
     * Indicates whether this is a 2D globe.
     */
    val is2D get() = projection.is2D
    private val scratchHeights = FloatArray(1)
    private val scratchSector = Sector()

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
        projection.geographicToCartesian(this, latitude, longitude, altitude, result)

    fun geographicToCartesianNormal(latitude: Angle, longitude: Angle, result: Vec3) =
        projection.geographicToCartesianNormal(this, latitude, longitude, result)

    fun geographicToCartesianTransform(latitude: Angle, longitude: Angle, altitude: Double, result: Matrix4) =
        projection.geographicToCartesianTransform(this, latitude, longitude, altitude, result)

    fun geographicToCartesianGrid(
        sector: Sector, numLat: Int, numLon: Int, height: FloatArray?, verticalExaggeration: Float,
        origin: Vec3?, result: FloatArray, offset: Int, rowStride: Int
    ) = projection.geographicToCartesianGrid(
        this, sector, numLat, numLon, height, verticalExaggeration,
        origin, result, offset, rowStride
    )

    fun geographicToCartesianBorder(
        sector: Sector, numLat: Int, numLon: Int, height: Float, origin: Vec3, result: FloatArray
    ) = projection.geographicToCartesianBorder(this, sector, numLat, numLon, height, origin, result)

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
        projection.cartesianToGeographic(this, x, y, z, result)

    fun cartesianToLocalTransform(x: Double, y: Double, z: Double, result: Matrix4) =
        projection.cartesianToLocalTransform(this, x, y, z, result)

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
    fun intersect(line: Line, result: Vec3) = projection.intersect(this, line, result)

    /**
     * Determine terrain altitude in specified geographic point from elevation model
     *
     * @param latitude  location latitude
     * @param longitude location longitude
     *
     * @return Elevation in meters in specified location
     */
    fun getElevation(latitude: Angle, longitude: Angle): Double {
        scratchSector.set(latitude, longitude, ZERO, ZERO)
        elevationModel.getHeightGrid(scratchSector, 1, 1, scratchHeights)
        return scratchHeights[0].toDouble()
    }
}