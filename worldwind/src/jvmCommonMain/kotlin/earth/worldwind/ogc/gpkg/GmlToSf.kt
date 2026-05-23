package earth.worldwind.ogc.gpkg

import earth.worldwind.geom.Position
import earth.worldwind.ogc.wfs.WfsGmlReader
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mil.nga.sf.Geometry
import mil.nga.sf.LineString
import mil.nga.sf.LinearRing
import mil.nga.sf.Point
import mil.nga.sf.Polygon

/**
 * Convert WfsGmlReader's pre-parsed [WfsGmlReader.FeatureRecord]s into the
 * `(geometry, propertiesJson)` pairs the WFS cache writer feeds into the GPKG features
 * table. Multi* GML geometries already fanned out one-record-per-inner-geometry inside
 * the reader, so each record maps to exactly one row.
 *
 * Properties travel as a JSON object string (string values only — GML doesn't strongly
 * type leaves the way GeoJSON does) so the cache's `properties` column stays uniform
 * across both decode paths and the GeoJsonLayerFactory-style replay can re-parse them
 * back into a `LinkedHashMap`.
 */
internal fun gmlFeatureRecordsToSf(
    records: List<WfsGmlReader.FeatureRecord>,
): List<Pair<Geometry, String?>> = records.mapNotNull { record ->
    val geom = gmlGeometryToSf(record.geometry) ?: return@mapNotNull null
    val properties = if (record.properties.isEmpty()) null else propertiesToJsonString(record.properties)
    geom to properties
}

private fun gmlGeometryToSf(geometry: WfsGmlReader.GmlGeometry): Geometry? = when (geometry) {
    is WfsGmlReader.GmlGeometry.PointGeom -> geometry.position.toSfPoint()
    is WfsGmlReader.GmlGeometry.LineGeom -> {
        val pts = geometry.positions.map { it.toSfPoint() }
        if (pts.size >= 2) LineString(pts) else null
    }
    is WfsGmlReader.GmlGeometry.PolygonGeom -> {
        val ext = geometry.exterior.map { it.toSfPoint() }
        if (ext.size < 4) null else Polygon(buildList<LineString> {
            add(LinearRing(ext))
            geometry.interiors.forEach { ring ->
                val pts = ring.map { it.toSfPoint() }
                if (pts.size >= 4) add(LinearRing(pts))
            }
        })
    }
}

private fun Position.toSfPoint(): Point = Point(longitude.inDegrees, latitude.inDegrees, altitude)

private fun propertiesToJsonString(props: Map<String, String>): String =
    JsonObject(props.mapValues<String, String, JsonElement> { (_, v) -> JsonPrimitive(v) }).toString()
