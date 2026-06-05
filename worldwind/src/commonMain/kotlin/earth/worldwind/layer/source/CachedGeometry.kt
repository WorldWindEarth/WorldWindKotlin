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

    @Serializable
    @SerialName("LineString")
    data class LineString(val points: List<Point>) : CachedGeometry()

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
    fun accept(p: CachedGeometry.Point) {
        if (p.x < minX) minX = p.x; if (p.x > maxX) maxX = p.x
        if (p.y < minY) minY = p.y; if (p.y > maxY) maxY = p.y
    }
    fun walk(g: CachedGeometry) {
        when (g) {
            is CachedGeometry.Point -> accept(g)
            is CachedGeometry.LineString -> g.points.forEach(::accept)
            is CachedGeometry.Polygon -> g.rings.forEach { ring -> ring.points.forEach(::accept) }
            is CachedGeometry.MultiPolygon -> g.polygons.forEach(::walk)
            is CachedGeometry.MultiPoint -> g.points.forEach(::accept)
            is CachedGeometry.MultiLineString -> g.lines.forEach { line -> line.points.forEach(::accept) }
            is CachedGeometry.GeometryCollection -> g.geometries.forEach(::walk)
        }
    }
    walk(this)
    return (minX + maxX) / 2.0 to (minY + maxY) / 2.0
}
