package earth.worldwind.layer.source

import earth.worldwind.formats.geojson.parseGeoJsonObject
import kotlinx.serialization.json.JsonObject
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.GeometryCollection
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.MultiLineString
import org.maplibre.spatialk.geojson.MultiPoint
import org.maplibre.spatialk.geojson.MultiPolygon
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position

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
    val features: List<Feature<*, *>> = when (geoJsonObject) {
        is FeatureCollection<*, *> -> geoJsonObject.features
        is Feature<*, *> -> listOf(geoJsonObject)
        is Geometry -> listOf(Feature<Geometry, JsonObject?>(geometry = geoJsonObject, properties = null))
    }
    return features.mapNotNull { feature ->
        feature.geometry?.toCachedGeometry()?.let { geom ->
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
    is GeometryCollection<*> -> CachedGeometry.GeometryCollection(geometries.mapNotNull { it.toCachedGeometry() })
        .takeIf { it.geometries.isNotEmpty() }
}

internal fun Position.toCachedPoint(): CachedGeometry.Point = CachedGeometry.Point(
    x = longitude,
    y = latitude,
    z = altitude,
)

/** Serialize the feature's `properties` as a JSON string for [CachedFeatureRow.properties].
 *  Returns `null` when the feature has no properties — [BulkFeatureLayer] treats it as an
 *  empty map. */
internal fun featurePropertiesJson(feature: Feature<*, *>): String? =
    (feature.properties as? JsonObject)?.toString()
