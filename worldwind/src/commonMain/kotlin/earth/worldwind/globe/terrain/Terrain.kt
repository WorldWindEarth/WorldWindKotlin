package earth.worldwind.globe.terrain

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Line
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Vec3

/**
 * Surface of a planet or celestial object.
 * <br>
 * Models the geometric surface defined by an ellipsoidal globe and its
 * associated elevations. Terrain uses the Cartesian coordinate system specified by a GeographicProjection and
 * is capable of representing both a 3D ellipsoid and a 2D map projection, though not simultaneously.
 * <br>
 * <h3>Caching Terrain Queries</h3>
 *
 * Terrain implementations typically model a subset of the globe's surface at varying
 * resolution. In this case results from the methods `intersect` and `surfacePoint` cannot be cached. Either
 * method may fail to compute a result when the terrain surface has no geometry in the region queried, and even if
 * computation is successful the result is based on an unknown resolution. However, if the terrain implementation is
 * known to model a pre-determined resolution and region of interest results from the methods `intersect` and
 * `surfacePoint` may be cached. Terrains which track [version] additionally let callers detect via [containsTile]
 * when a previously computed surface point remains valid despite other terrain changes.
 */
interface Terrain {
    /**
     * Monotonic version of the rendered terrain composition. It changes whenever the set of rendered tiles or any
     * tile's geometry changes and is unique across terrain instances. [UNKNOWN_VERSION] indicates the composition
     * is not tracked, disabling terrain-dependent point caching.
     */
    val version get() = UNKNOWN_VERSION

    /**
     * Indicates the geographic rectangular region that contains this terrain. The returned sector may contain
     * geographic areas where the terrain is nonexistent.
     */
    val sector: Sector

    /**
     * Computes the first intersection of this terrain with a specified line in Cartesian coordinates. The line is
     * interpreted as a ray; intersection points behind the line's origin are ignored. If the line does not intersect
     * the geometric surface modeled by this terrain, this returns false and does not modify the result argument.
     *
     * @param line   the line to intersect with this terrain
     * @param result a pre-allocated [Vec3] in which to return the intersection point
     *
     * @return true if the ray intersects this terrain, otherwise false
     */
    fun intersect(line: Line, result: Vec3): Boolean

    /**
     * Computes the Cartesian coordinates of a geographic location on the terrain surface. If the latitude and longitude
     * are outside the geometric surface modeled by this terrain, this returns false and does not modify the result
     * argument.
     *
     * @param latitude  the location's latitude
     * @param longitude the location's longitude
     * @param result    a pre-allocated [Vec3] in which to store the computed X, Y and Z Cartesian coordinates
     *
     * @return true if the geographic location is on the terrain surface, otherwise false
     */
    fun surfacePoint(latitude: Angle, longitude: Angle, result: Vec3): Boolean

    /**
     * Computes the surface point like [surfacePoint] and returns the terrain tile which produced it, or null if no
     * rendered tile contains the location. Implementations which track [version] must override this method.
     *
     * @param latitude  the location's latitude
     * @param longitude the location's longitude
     * @param result    a pre-allocated [Vec3] in which to store the computed X, Y and Z Cartesian coordinates
     *
     * @return the terrain tile containing the location, or null if the location is off the terrain surface
     */
    fun surfacePointTile(latitude: Angle, longitude: Angle, result: Vec3): TerrainTile? {
        surfacePoint(latitude, longitude, result)
        return null
    }

    /**
     * Determines whether the tile with the specified key is rendered by this terrain with unchanged geometry.
     *
     * @param tileKey            the tile's key
     * @param pointBufferVersion the tile's point geometry version at the time its result was computed
     *
     * @return true if the tile is rendered by this terrain and its geometry is unchanged, otherwise false
     */
    fun containsTile(tileKey: String, pointBufferVersion: Int) = false

    /**
     * Computes minimal and maximal height from available tiles with tile level offset not more than specified depth
     *
     * @param levelNumberDepth Level number offset from the most detailed available tile
     * @param result Float array containing min and max heights
     */
    fun heightLimits(levelNumberDepth: Int, result: FloatArray)

    companion object {
        /**
         * Terrain composition version indicating the composition is not tracked.
         */
        const val UNKNOWN_VERSION = -1L
    }
}