package earth.worldwind.globe.projection

import earth.worldwind.geom.*
import earth.worldwind.globe.Globe

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
    val is2D: Boolean

    /**
     * Converts a geographic position to Cartesian coordinates.
     *
     * @param globe     the globe this projection is applied to
     * @param latitude  the position's latitude
     * @param longitude the position's longitude
     * @param altitude  the position's altitude in meters
     * @param result    a pre-allocated [Vec3] in which to store the computed X, Y and Z Cartesian coordinates
     *
     * @return the result argument, set to the computed Cartesian coordinates
     */
    fun geographicToCartesian(globe: Globe, latitude: Angle, longitude: Angle, altitude: Double, result: Vec3): Vec3

    fun geographicToCartesianNormal(globe: Globe, latitude: Angle, longitude: Angle, result: Vec3): Vec3

    fun geographicToCartesianTransform(
        globe: Globe, latitude: Angle, longitude: Angle, altitude: Double, result: Matrix4
    ): Matrix4

    fun geographicToCartesianGrid(
        globe: Globe, sector: Sector, numLat: Int, numLon: Int, height: FloatArray?,
        verticalExaggeration: Float, origin: Vec3?, result: FloatArray, offset: Int, rowStride: Int
    ): FloatArray

    fun geographicToCartesianBorder(
        globe: Globe, sector: Sector, numLat: Int, numLon: Int, height: Float, origin: Vec3?, result: FloatArray
    ): FloatArray

    /**
     * Converts a Cartesian point to a geographic position.
     *
     * @param globe  Globe model
     * @param x      the Cartesian point's X component
     * @param y      the Cartesian point's Y component
     * @param z      the Cartesian point's Z component
     * @param result a pre-allocated [Position] in which to store the computed geographic position
     *
     * @return the result argument, set to the computed geographic position
     */
    fun cartesianToGeographic(globe: Globe, x: Double, y: Double, z: Double, result: Position): Position

    fun cartesianToLocalTransform(globe: Globe, x: Double, y: Double, z: Double, result: Matrix4): Matrix4

    /**
     * Computes the first intersection of a specified globe and line. The line is interpreted as a ray; intersection
     * points behind the line's origin are ignored.
     *
     * @param globe  the globe this projection is applied to
     * @param line   the line to intersect with the globe
     * @param result a pre-allocated [Vec3] in which to return the computed point
     *
     * @return true if the ray intersects the globe, otherwise false
     */
    fun intersect(globe: Globe, line: Line, result: Vec3): Boolean
}