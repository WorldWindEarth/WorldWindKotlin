package earth.worldwind.ogc.gpkg

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import mil.nga.sf.Geometry
import mil.nga.sf.LineString
import mil.nga.sf.LinearRing
import mil.nga.sf.MultiLineString
import mil.nga.sf.MultiPoint
import mil.nga.sf.MultiPolygon
import mil.nga.sf.Point
import mil.nga.sf.Polygon

/**
 * Convert a GeoJSON geometry (already parsed into a kotlinx-serialization `JsonObject`)
 * into the corresponding [mil.nga.sf.Geometry] tree so it can be encoded as a GPKG
 * geometry blob via [mil.nga.geopackage.geom.GeoPackageGeometryData].
 *
 * Supports Point, LineString, Polygon, MultiPoint, MultiLineString, MultiPolygon. GeoJSON
 * geometries always store coordinates as `[lon, lat (, alt)]`, which is what
 * [mil.nga.sf.Point] expects (x = longitude, y = latitude).
 */
internal fun geoJsonGeometryToSf(geometry: JsonObject): Geometry? {
    val type = (geometry["type"] as? JsonPrimitive)?.content ?: return null
    val coords = geometry["coordinates"] as? JsonArray ?: return null
    return when (type) {
        "Point" -> coords.toPoint()
        "LineString" -> coords.toLineString()
        "Polygon" -> coords.toPolygon()
        "MultiPoint" -> MultiPoint(coords.mapNotNull { (it as? JsonArray)?.toPoint() })
        "MultiLineString" -> MultiLineString(coords.mapNotNull { (it as? JsonArray)?.toLineString() })
        "MultiPolygon" -> MultiPolygon(coords.mapNotNull { (it as? JsonArray)?.toPolygon() })
        else -> null
    }
}

private fun JsonArray.toPoint(): Point? {
    val x = (this.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return null
    val y = (this.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return null
    val z = (this.getOrNull(2) as? JsonPrimitive)?.doubleOrNull
    return if (z != null) Point(x, y, z) else Point(x, y)
}

private fun JsonArray.toLineString(): LineString? {
    val points = mapNotNull { (it as? JsonArray)?.toPoint() }
    return if (points.size >= 2) LineString(points) else null
}

private fun JsonArray.toPolygon(): Polygon? {
    val rings = mapNotNull { ringElement ->
        val ring = ringElement as? JsonArray ?: return@mapNotNull null
        val pts = ring.mapNotNull { (it as? JsonArray)?.toPoint() }
        if (pts.size >= 4) LinearRing(pts) else null
    }
    return if (rings.isNotEmpty()) Polygon(rings) else null
}
