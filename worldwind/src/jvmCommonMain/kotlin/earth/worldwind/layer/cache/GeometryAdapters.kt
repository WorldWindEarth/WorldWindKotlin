package earth.worldwind.layer.cache
import earth.worldwind.layer.source.CachedGeometry

import mil.nga.sf.Geometry
import mil.nga.sf.LineString
import mil.nga.sf.Point
import mil.nga.sf.Polygon

/** mil.nga.sf → [CachedGeometry] adapter. Returns `null` for geometry kinds the cache
 *  doesn't model (collections, multi-*, compound curves, triangulated meshes). */
internal fun Geometry.toCached(): CachedGeometry? = when (this) {
    is Point -> CachedGeometry.Point(x, y, z)
    is LineString -> CachedGeometry.LineString(points.map { CachedGeometry.Point(it.x, it.y, it.z) })
    is Polygon -> CachedGeometry.Polygon(rings.orEmpty().map { ring ->
        CachedGeometry.LineString(ring.points.map { CachedGeometry.Point(it.x, it.y, it.z) })
    })
    else -> null
}

/** [CachedGeometry] → mil.nga.sf adapter, used when round-tripping through the GPKG. */
internal fun CachedGeometry.toSf(): Geometry = when (this) {
    is CachedGeometry.Point -> Point(x, y, z)
    is CachedGeometry.LineString -> LineString(z3D, false).also { ls ->
        for (p in points) ls.addPoint(Point(p.x, p.y, p.z))
    }
    is CachedGeometry.Polygon -> Polygon(z3D, false).also { poly ->
        for (ring in rings) poly.addRing(ring.toSf() as LineString)
    }
}

private val CachedGeometry.LineString.z3D: Boolean get() = points.any { it.z != null }
private val CachedGeometry.Polygon.z3D: Boolean get() = rings.any { it.z3D }
