package earth.worldwind.layer.cache
import earth.worldwind.layer.source.CachedGeometry

import mil.nga.sf.Geometry
import mil.nga.sf.GeometryCollection
import mil.nga.sf.LineString
import mil.nga.sf.MultiLineString
import mil.nga.sf.MultiPoint
import mil.nga.sf.MultiPolygon
import mil.nga.sf.Point
import mil.nga.sf.Polygon

/** mil.nga.sf → [CachedGeometry] adapter. Returns `null` only for geometry kinds the cache
 *  doesn't model (compound curves, triangulated meshes, etc.); the OGC simple-feature set
 *  (Point / LineString / Polygon and their Multi* + GeometryCollection composites) is
 *  preserved. The Multi* checks must precede [GeometryCollection] since they subclass it. */
internal fun Geometry.toCached(): CachedGeometry? = when (this) {
    is Point -> CachedGeometry.Point(x, y, z)
    is LineString -> toCachedLineString()
    is Polygon -> toCachedPolygon()
    is MultiPoint -> CachedGeometry.MultiPoint(points.orEmpty().map { CachedGeometry.Point(it.x, it.y, it.z) })
    is MultiLineString -> CachedGeometry.MultiLineString(lineStrings.orEmpty().map { it.toCachedLineString() })
    is MultiPolygon -> CachedGeometry.MultiPolygon(polygons.orEmpty().map { it.toCachedPolygon() })
    is GeometryCollection<*> -> CachedGeometry.GeometryCollection(
        geometries.orEmpty().mapNotNull { it?.toCached() }
    )
    else -> null
}

private fun LineString.toCachedLineString(): CachedGeometry.LineString {
    val pts = points.orEmpty()
    val xy = DoubleArray(pts.size * 2)
    val z = if (pts.any { it.z != null }) DoubleArray(pts.size) else null
    for (i in pts.indices) {
        val p = pts[i]
        xy[i * 2] = p.x; xy[i * 2 + 1] = p.y
        if (z != null) z[i] = p.z ?: 0.0
    }
    return CachedGeometry.LineString(xy, z)
}

private fun Polygon.toCachedPolygon(): CachedGeometry.Polygon = CachedGeometry.Polygon(
    rings.orEmpty().map { ring -> ring.toCachedLineString() }
)

/** [CachedGeometry] → mil.nga.sf adapter, used when round-tripping through the GPKG. */
internal fun CachedGeometry.toSf(): Geometry = when (this) {
    is CachedGeometry.Point -> Point(x, y, z)
    is CachedGeometry.LineString -> toSfLineString()
    is CachedGeometry.Polygon -> toSfPolygon()
    is CachedGeometry.MultiPolygon -> MultiPolygon(hasZ, false).also { mp ->
        for (poly in polygons) mp.addPolygon(poly.toSfPolygon())
    }
    is CachedGeometry.MultiPoint -> MultiPoint(hasZ, false).also { mp ->
        for (p in points) mp.addPoint(Point(p.x, p.y, p.z))
    }
    is CachedGeometry.MultiLineString -> MultiLineString(hasZ, false).also { ml ->
        for (line in lines) ml.addLineString(line.toSfLineString())
    }
    is CachedGeometry.GeometryCollection -> GeometryCollection<Geometry>(hasZ, false).also { gc ->
        for (geom in geometries) gc.addGeometry(geom.toSf())
    }
}

private fun CachedGeometry.LineString.toSfLineString(): LineString = LineString(is3D, false).also { ls ->
    for (i in 0 until size) ls.addPoint(Point(xAt(i), yAt(i), zAt(i)))
}

private fun CachedGeometry.Polygon.toSfPolygon(): Polygon = Polygon(hasZ, false).also { poly ->
    for (ring in rings) poly.addRing(ring.toSfLineString())
}

/** Whether any vertex carries a Z (altitude). Drives the mil.nga.sf `hasZ` dimension flag. */
private val CachedGeometry.hasZ: Boolean get() = when (this) {
    is CachedGeometry.Point -> z != null
    is CachedGeometry.LineString -> is3D
    is CachedGeometry.Polygon -> rings.any { it.hasZ }
    is CachedGeometry.MultiPolygon -> polygons.any { it.hasZ }
    is CachedGeometry.MultiPoint -> points.any { it.z != null }
    is CachedGeometry.MultiLineString -> lines.any { it.hasZ }
    is CachedGeometry.GeometryCollection -> geometries.any { it.hasZ }
}
