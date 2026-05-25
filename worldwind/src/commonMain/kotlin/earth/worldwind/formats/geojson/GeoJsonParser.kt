package earth.worldwind.formats.geojson

import io.data2viz.geojson.Feature
import io.data2viz.geojson.FeatureCollection
import io.data2viz.geojson.GeoJsonObject
import io.data2viz.geojson.Geometry
import io.data2viz.geojson.GeometryCollection
import io.data2viz.geojson.LineString
import io.data2viz.geojson.MultiLineString
import io.data2viz.geojson.MultiPoint
import io.data2viz.geojson.MultiPolygon
import io.data2viz.geojson.Point
import io.data2viz.geojson.Polygon
import io.data2viz.geojson.Position
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

/**
 * Kotlinx-based GeoJSON parser. We don't call `String.toGeoJsonObject()` from data2viz
 * because the iOS actual in that library is a stub that returns `Point(doubleArrayOf(0,0))`
 * regardless of input — every iOS GeoJSON layer rendered as one point at the equator.
 * Using a single kotlinx parser across all platforms gives us consistent behavior plus
 * removes a third-party iOS gotcha.
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
                .toTypedArray()
            FeatureCollection(features)
        }
        "Feature" -> parseFeature(obj)
        "Point", "LineString", "Polygon", "MultiPoint", "MultiLineString", "MultiPolygon", "GeometryCollection" ->
            parseGeometry(obj)
        else -> null
    }
}

private fun parseFeature(obj: JsonObject?): Feature? {
    if (obj == null) return null
    val geometry = parseGeometry(obj["geometry"] as? JsonObject) ?: return null
    val id = (obj["id"] as? JsonPrimitive)?.let {
        if (it.isString) it.content
        else it.longOrNull ?: it.doubleOrNull
    }
    val properties = (obj["properties"] as? JsonObject)?.let { jsonObjectToMap(it) }
    return Feature(geometry, id, properties)
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
            val polys = coords.map { ringsOf(it as JsonArray) }.toTypedArray()
            MultiPolygon(polys)
        }
        "GeometryCollection" -> {
            val list = (obj["geometries"] as? JsonArray).orEmpty()
                .mapNotNull { parseGeometry(it as? JsonObject) }
                .toTypedArray()
            GeometryCollection(list)
        }
        else -> null
    }
}

private fun positionOf(arr: JsonArray): Position {
    val out = DoubleArray(arr.size)
    for (i in arr.indices) out[i] = (arr[i] as? JsonPrimitive)?.doubleOrNull ?: 0.0
    return out
}

private fun positionsOf(arr: JsonArray): Array<Position> =
    arr.map { positionOf(it as JsonArray) }.toTypedArray()

private fun ringsOf(arr: JsonArray): Array<Array<Position>> =
    arr.map { positionsOf(it as JsonArray) }.toTypedArray()

private fun jsonObjectToMap(obj: JsonObject): LinkedHashMap<String, Any?> {
    val out = LinkedHashMap<String, Any?>(obj.size)
    for ((k, v) in obj) out[k] = jsonValueToAny(v)
    return out
}

private fun jsonValueToAny(value: JsonElement): Any? = when (value) {
    is JsonNull -> null
    is JsonPrimitive -> when {
        value.isString -> value.content
        value.booleanOrNull != null -> value.boolean
        value.intOrNull != null -> value.int
        value.longOrNull != null -> value.long
        value.doubleOrNull != null -> value.double
        else -> value.content
    }
    is JsonArray -> value.map { jsonValueToAny(it) }
    is JsonObject -> jsonObjectToMap(value)
}

private val JsonPrimitive.contentOrNull: String?
    get() = if (this is JsonNull) null else content
