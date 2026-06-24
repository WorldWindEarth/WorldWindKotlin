package earth.worldwind.formats.geojson

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.GeoJsonObject
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
 * Kotlinx-based GeoJSON parser building MapLibre Spatial-K model objects. We parse with our
 * own [Json] reader rather than the library's `fromJson` so behavior stays identical across
 * every platform (JVM, Android, JS, wasmJs, iOS) and we keep the lenient null-on-failure
 * contract callers rely on.
 *
 * Returns `null` if the text isn't valid GeoJSON.
 */
internal fun parseGeoJsonObject(text: String): GeoJsonObject? = try {
    val element = JSON.parseToJsonElement(text)
    parseGeoJsonElement(element)
} catch (_: Throwable) {
    null
}

private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

private fun parseGeoJsonElement(element: JsonElement): GeoJsonObject? {
    val obj = element as? JsonObject ?: return null
    return when ((obj["type"] as? JsonPrimitive)?.contentOrNull) {
        "FeatureCollection" -> {
            val features = (obj["features"] as? JsonArray).orEmpty()
                .mapNotNull { parseFeature(it as? JsonObject) }
            FeatureCollection(features)
        }
        "Feature" -> parseFeature(obj)
        "Point", "LineString", "Polygon", "MultiPoint", "MultiLineString", "MultiPolygon", "GeometryCollection" ->
            parseGeometry(obj)
        else -> null
    }
}

private fun parseFeature(obj: JsonObject?): Feature<Geometry, JsonObject?>? {
    if (obj == null) return null
    val geometry = parseGeometry(obj["geometry"] as? JsonObject) ?: return null
    val properties = obj["properties"] as? JsonObject
    val id = obj["id"] as? JsonPrimitive
    return Feature(geometry = geometry, properties = properties, id = id)
}

private fun parseGeometry(obj: JsonObject?): Geometry? {
    if (obj == null) return null
    return when ((obj["type"] as? JsonPrimitive)?.contentOrNull) {
        "Point" -> (obj["coordinates"] as? JsonArray)?.let { Point(positionOf(it)) }
        "LineString" -> (obj["coordinates"] as? JsonArray)?.let { LineString(positionsOf(it)) }
        "Polygon" -> (obj["coordinates"] as? JsonArray)?.let { Polygon(ringsOf(it)) }
        "MultiPoint" -> (obj["coordinates"] as? JsonArray)?.let { MultiPoint(positionsOf(it)) }
        "MultiLineString" -> (obj["coordinates"] as? JsonArray)?.let { MultiLineString(ringsOf(it)) }
        "MultiPolygon" -> (obj["coordinates"] as? JsonArray)?.let { coords ->
            MultiPolygon(coords.map { ringsOf(it as JsonArray) })
        }
        "GeometryCollection" -> {
            val list = (obj["geometries"] as? JsonArray).orEmpty()
                .mapNotNull { parseGeometry(it as? JsonObject) }
            GeometryCollection(list)
        }
        else -> null
    }
}

private fun positionOf(arr: JsonArray): Position {
    val lon = (arr.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: 0.0
    val lat = (arr.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: 0.0
    val alt = (arr.getOrNull(2) as? JsonPrimitive)?.doubleOrNull
    return Position(lon, lat, alt)
}

private fun positionsOf(arr: JsonArray): List<Position> = arr.map { positionOf(it as JsonArray) }

private fun ringsOf(arr: JsonArray): List<List<Position>> = arr.map { positionsOf(it as JsonArray) }

private val JsonPrimitive.contentOrNull: String?
    get() = if (this is JsonNull) null else content
