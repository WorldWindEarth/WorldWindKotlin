package earth.worldwind.layer.source

import earth.worldwind.formats.geojson.parseGeoJsonObject
import io.data2viz.geojson.Feature
import io.data2viz.geojson.FeatureCollection
import io.data2viz.geojson.Geometry
import io.data2viz.geojson.GeometryCollection
import io.data2viz.geojson.LineString
import io.data2viz.geojson.MultiLineString
import io.data2viz.geojson.MultiPoint
import io.data2viz.geojson.MultiPolygon
import io.data2viz.geojson.Point
import io.data2viz.geojson.Polygon
import io.data2viz.geojson.Position
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Shared GeoJSON → [CachedFeatureRow] conversion used by every [BulkFeatureSource] whose
 * wire format is GeoJSON ([GeoJsonBulkFeatureSource] for static documents,
 * [earth.worldwind.ogc.wfs.WfsBulkFeatureSource] for the GeoJSON-output WFS path).
 *
 * The full OGC simple-feature set is preserved: Multi* shapes map to their
 * [CachedGeometry] composites and `GeometryCollection` recurses; the consumer fans them out
 * to N renderables sharing one properties map.
 */

/** Parse a GeoJSON document and convert every feature into a [CachedFeatureRow]. Accepts
 *  any GeoJSON top-level shape (FeatureCollection, Feature, bare Geometry); bare geometries
 *  are wrapped as a property-less feature. Returns an empty list on parse failure. */
internal fun parseGeoJsonAsFeatureRows(text: String): List<CachedFeatureRow> {
    val geoJsonObject = parseGeoJsonObject(text) ?: return emptyList()
    val features = when (geoJsonObject) {
        is FeatureCollection -> geoJsonObject.features.toList()
        is Feature -> listOf(geoJsonObject)
        is Geometry -> listOf(Feature(geoJsonObject))
        else -> emptyList()
    }
    return features.mapNotNull { feature ->
        feature.geometry.toCachedGeometry()?.let { geom ->
            CachedFeatureRow(geom, featurePropertiesJson(feature))
        }
    }
}

internal fun Geometry.toCachedGeometry(): CachedGeometry? = when (this) {
    is Point -> coordinates.toCachedPoint()
    is LineString -> CachedGeometry.LineString(coordinates.map { it.toCachedPoint() })
    is Polygon -> CachedGeometry.Polygon(
        coordinates.map { ring -> CachedGeometry.LineString(ring.map { it.toCachedPoint() }) }
    )
    is MultiPoint -> CachedGeometry.MultiPoint(coordinates.map { it.toCachedPoint() })
        .takeIf { it.points.isNotEmpty() }
    is MultiLineString -> CachedGeometry.MultiLineString(
        coordinates.map { line -> CachedGeometry.LineString(line.map { it.toCachedPoint() }) }
    ).takeIf { it.lines.isNotEmpty() }
    is MultiPolygon -> CachedGeometry.MultiPolygon(
        coordinates.map { polyRings ->
            CachedGeometry.Polygon(polyRings.map { ring -> CachedGeometry.LineString(ring.map { it.toCachedPoint() }) })
        }
    ).takeIf { it.polygons.isNotEmpty() }
    is GeometryCollection -> CachedGeometry.GeometryCollection(geometries.mapNotNull { it.toCachedGeometry() })
        .takeIf { it.geometries.isNotEmpty() }
    else -> null
}

internal fun Position.toCachedPoint(): CachedGeometry.Point = CachedGeometry.Point(
    x = getOrElse(0) { 0.0 },
    y = getOrElse(1) { 0.0 },
    z = if (size > 2) get(2) else null,
)

/** Serialize the feature's `properties` map as a JSON string for [CachedFeatureRow.properties].
 *  Returns `null` when the feature has no properties — [BulkFeatureLayer] treats it as an
 *  empty map. */
internal fun featurePropertiesJson(feature: Feature): String? {
    val props = feature.properties as? Map<*, *> ?: return null
    return JsonObject(buildMap {
        for ((k, v) in props) put(k.toString(), v.toJsonElement())
    }).toString()
}

private fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is String -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is JsonElement -> this
    else -> JsonPrimitive(toString())
}
