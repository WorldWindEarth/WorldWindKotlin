package earth.worldwind.globe.projection

import earth.worldwind.geom.*

/**
 * Represents transformations between geographic coordinates and Cartesian coordinates. GeographicProjection specifies
 * the Cartesian coordinate system used by Globe and WorldWindow.
 */
interface GeographicProjection {
    /**
     * This projection's display name.
     */
    val displayName: String
    /**
     * Indicates whether this projection is a 2D projection.
     */
    val is2D: Boolean get() = false
    /**
     * Indicates whether this projection is continuous with itself horizontally.
     */
    val isContinuous: Boolean get() = false
    /**
     * Indicates the geographic limits of this projection.
     * May be null to indicate the full range of latitude and longitude.
     */
    val projectionLimits: Sector? get() = null // Sector().setFullSphere()

    /**
     * Converts a geographic position to Cartesian coordinates.
     *
     * @param ellipsoid the ellipsoid this projection is applied to
     * @param latitude  the position's latitude
     * @param longitude the position's longitude
     * @param altitude  the position's altitude in meters
     * @param offset    the horizontal offset in 2D projection
     * @param result    a pre-allocated [Vec3] in which to store the computed X, Y and Z Cartesian coordinates
     *
     * @return the result argument, set to the computed Cartesian coordinates
     */
    fun geographicToCartesian(ellipsoid: Ellipsoid, latitude: Angle, longitude: Angle, altitude: Double, offset: Double, result: Vec3): Vec3

    fun geographicToCartesianNormal(ellipsoid: Ellipsoid, latitude: Angle, longitude: Angle, result: Vec3): Vec3

    fun geographicToCartesianTransform(
        ellipsoid: Ellipsoid, latitude: Angle, longitude: Angle, altitude: Double, result: Matrix4
    ): Matrix4

    fun geographicToCartesianGrid(
        ellipsoid: Ellipsoid, sector: Sector, numLat: Int, numLon: Int, height: FloatArray?,
        verticalExaggeration: Double, origin: Vec3?, offset: Double, result: FloatArray, rowOffset: Int, rowStride: Int
    ): FloatArray

    fun geographicToCartesianBorder(
        ellipsoid: Ellipsoid, sector: Sector, numLat: Int, numLon: Int, height: Float, origin: Vec3?, offset: Double, result: FloatArray
    ): FloatArray

    /**
     * Converts a Cartesian point to a geographic position.
     *
     * @param ellipsoid the ellipsoid this projection is applied to
     * @param x         the Cartesian point's X component
     * @param y         the Cartesian point's Y component
     * @param z         the Cartesian point's Z component
     * @param offset    the horizontal offset in 2D projection
     * @param result    a pre-allocated [Position] in which to store the computed geographic position
     *
     * @return the result argument, set to the computed geographic position
     */
    fun cartesianToGeographic(ellipsoid: Ellipsoid, x: Double, y: Double, z: Double, offset: Double, result: Position): Position

    fun cartesianToLocalTransform(ellipsoid: Ellipsoid, x: Double, y: Double, z: Double, result: Matrix4): Matrix4

    /**
     * Computes the first intersection of a specified globe and line. The line is interpreted as a ray; intersection
     * points behind the line's origin are ignored.
     *
     * @param ellipsoid the ellipsoid this projection is applied to
     * @param line      the line to intersect with the globe
     * @param result    a pre-allocated [Vec3] in which to return the computed point
     *
     * @return true if the ray intersects the globe, otherwise false
     */
    fun intersect(ellipsoid: Ellipsoid, line: Line, result: Vec3): Boolean
}