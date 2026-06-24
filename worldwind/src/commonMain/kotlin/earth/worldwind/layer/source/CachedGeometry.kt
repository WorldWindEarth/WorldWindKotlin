package earth.worldwind.layer.source

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Platform-neutral geometry envelope for feature cache rows. Platform implementations convert
 * to/from their native geometry (mil.nga.sf on JVM, JSON on JS). Z is altitude in meters.
 *
 * Pinned [SerialName]s decouple the cache wire format from the class FQN so package moves
 * don't invalidate every cache file (kotlinx-serialization's default discriminator is the FQN).
 */
@Serializable
sealed class CachedGeometry {
    @Serializable
    @SerialName("Point")
    data class Point(val x: Double, val y: Double, val z: Double? = null) : CachedGeometry()

    /**
     * A polyline / polygon ring stored as flat primitive arrays rather than one [Point] object per
     * vertex — dense WFS/OSM geometry is mostly thousands of vertices per ring, and the object-per-
     * vertex graph was the dominant decode-time allocation (the GC-pressure source). [xy] is
     * interleaved `[lon0, lat0, lon1, lat1, …]` in degrees (size `2 * vertexCount`); [z] is the
     * per-vertex altitude in meters parallel to the vertices, or `null` for a 2D ring (clamp to
     * ground). The closing vertex may or may not be present.
     */
    @Serializable
    @SerialName("LineString")
    class LineString(val xy: DoubleArray, val z: DoubleArray? = null) : CachedGeometry() {
        val size: Int get() = xy.size / 2
        fun xAt(i: Int) = xy[i * 2]
        fun yAt(i: Int) = xy[i * 2 + 1]
        fun zAt(i: Int): Double? = z?.get(i)
        /** True when the ring carries altitudes (3D); `false` ⇒ clamp to ground. */
        val is3D: Boolean get() = z != null

        companion object {
            /** Build a flat ring from per-vertex [points] — for low-volume producers and tests; the
             *  hot decode/cache paths fill [xy]/[z] directly from their source coordinates. */
            fun of(points: List<Point>): LineString {
                val xy = DoubleArray(points.size * 2)
                val z = if (points.any { it.z != null }) DoubleArray(points.size) else null
                for (i in points.indices) {
                    val p = points[i]
                    xy[i * 2] = p.x; xy[i * 2 + 1] = p.y
                    if (z != null) z[i] = p.z ?: 0.0
                }
                return LineString(xy, z)
            }
        }
    }

    /** First ring is outer; remaining rings are holes. Closing vertex may or may not be present. */
    @Serializable
    @SerialName("Polygon")
    data class Polygon(val rings: List<LineString>) : CachedGeometry()

    /** A composite of N independent polygons that belong to one feature (GeoJSON
     *  `MultiPolygon`, GPKG `MULTIPOLYGON`). Consumers fan it out into N renderables
     *  that share one properties map. */
    @Serializable
    @SerialName("MultiPolygon")
    data class MultiPolygon(val polygons: List<Polygon>) : CachedGeometry()

    /** N independent points sharing one feature (GeoJSON `MultiPoint`, GPKG `MULTIPOINT`). */
    @Serializable
    @SerialName("MultiPoint")
    data class MultiPoint(val points: List<Point>) : CachedGeometry()

    /** N independent line strings sharing one feature (GeoJSON `MultiLineString`,
     *  GPKG `MULTILINESTRING`). */
    @Serializable
    @SerialName("MultiLineString")
    data class MultiLineString(val lines: List<LineString>) : CachedGeometry()

    /** A heterogeneous collection of geometries belonging to one feature (GeoJSON
     *  `GeometryCollection`, GPKG `GEOMETRYCOLLECTION`). Consumers fan it out recursively. */
    @Serializable
    @SerialName("GeometryCollection")
    data class GeometryCollection(val geometries: List<CachedGeometry>) : CachedGeometry()
}

/** One cached feature row. Null [geometry] is the sentinel that marks a tile as fetched-but-empty. */
@Serializable
data class CachedFeatureRow(val geometry: CachedGeometry?, val properties: String?)

/**
 * Envelope-center `(longitude, latitude)` of this geometry — a deterministic representative point.
 * Used to assign a feature to a single tile (the one containing its center), so a geometry that
 * straddles a tile boundary renders in exactly one tile instead of every tile its envelope touches.
 */
fun CachedGeometry.envelopeCenter(): Pair<Double, Double> {
    var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE
    var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
    fun accept(x: Double, y: Double) {
        if (x < minX) minX = x; if (x > maxX) maxX = x
        if (y < minY) minY = y; if (y > maxY) maxY = y
    }
    fun acceptRing(ring: CachedGeometry.LineString) { for (i in 0 until ring.size) accept(ring.xAt(i), ring.yAt(i)) }
    fun walk(g: CachedGeometry) {
        when (g) {
            is CachedGeometry.Point -> accept(g.x, g.y)
            is CachedGeometry.LineString -> acceptRing(g)
            is CachedGeometry.Polygon -> g.rings.forEach(::acceptRing)
            is CachedGeometry.MultiPolygon -> g.polygons.forEach(::walk)
            is CachedGeometry.MultiPoint -> g.points.forEach { accept(it.x, it.y) }
            is CachedGeometry.MultiLineString -> g.lines.forEach(::acceptRing)
            is CachedGeometry.GeometryCollection -> g.geometries.forEach(::walk)
        }
    }
    walk(this)
    return (minX + maxX) / 2.0 to (minY + maxY) / 2.0
}
