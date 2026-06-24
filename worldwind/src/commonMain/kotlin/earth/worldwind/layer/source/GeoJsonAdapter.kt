package earth.worldwind.layer.source

import earth.worldwind.formats.geojson.parseGeoJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
    is LineString -> coordinates.toCachedRing()
    is Polygon -> CachedGeometry.Polygon(coordinates.map { ring -> ring.toCachedRing() })
    is MultiPoint -> CachedGeometry.MultiPoint(coordinates.map { it.toCachedPoint() })
        .takeIf { it.points.isNotEmpty() }
    is MultiLineString -> CachedGeometry.MultiLineString(coordinates.map { line -> line.toCachedRing() })
        .takeIf { it.lines.isNotEmpty() }
    is MultiPolygon -> CachedGeometry.MultiPolygon(
        coordinates.map { polyRings -> CachedGeometry.Polygon(polyRings.map { ring -> ring.toCachedRing() }) }
    ).takeIf { it.polygons.isNotEmpty() }
    is GeometryCollection<*> -> CachedGeometry.GeometryCollection(geometries.mapNotNull { it.toCachedGeometry() })
        .takeIf { it.geometries.isNotEmpty() }
}

internal fun Position.toCachedPoint(): CachedGeometry.Point = CachedGeometry.Point(
    x = longitude,
    y = latitude,
    z = altitude,
)

/** Flatten GeoJSON positions straight into a [CachedGeometry.LineString]'s primitive arrays — no
 *  intermediate per-vertex object (the decode-time allocation we're cutting). */
private fun List<Position>.toCachedRing(): CachedGeometry.LineString {
    val xy = DoubleArray(size * 2)
    var z: DoubleArray? = null
    for (i in indices) {
        val p = this[i]
        xy[i * 2] = p.longitude; xy[i * 2 + 1] = p.latitude
        val alt = p.altitude
        if (alt != null) {
            val za = z ?: DoubleArray(size).also { z = it }
            za[i] = alt
        }
    }
    return CachedGeometry.LineString(xy, z)
}

/** Serialize the feature's `properties` as a JSON string for [CachedFeatureRow.properties].
 *  Returns `null` when the feature has no properties — `BulkFeatureLayer` treats it as an
 *  empty map. */
internal fun featurePropertiesJson(feature: Feature<*, *>): String? {
    val props = feature.properties as? JsonObject
    // Carry the GeoJSON feature id as an "id" property so the feature cache can dedupe by it
    // (GpkgFeatureStore.extractFeatureUid → upsert-by-uid). Without a stable key, a whole unclipped
    // WFS feature spanning many tiles is delete+reinserted on every overlapping tile's cache write.
    val id = feature.id?.content?.takeIf { it.isNotBlank() }
    if (id == null || props?.containsKey("id") == true) return props?.toString()
    return buildJsonObject {
        props?.forEach { (k, v) -> put(k, v) }
        put("id", id)
    }.toString()
}
