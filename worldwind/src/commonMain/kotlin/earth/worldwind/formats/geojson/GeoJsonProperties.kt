package earth.worldwind.formats.geojson

/**
 * Normalize the raw `feature.properties` value handed back by [io.data2viz.geojson] into
 * a [LinkedHashMap]. On the JVM the underlying Jackson deserialization already produces a
 * LinkedHashMap, so a direct cast works. On Kotlin/JS the library leaves the field as a
 * native JS object literal — the cast fails and the map comes back empty — so the JS
 * actual walks the object's keys instead. On Kotlin/Native the property is a Map but not
 * necessarily a LinkedHashMap, so the shared [extractFromMapLike] copies entries across.
 */
internal expect fun extractGeoJsonProperties(rawProperties: Any?): LinkedHashMap<String, Any?>

internal fun extractFromMapLike(rawProperties: Any?): LinkedHashMap<String, Any?> {
    if (rawProperties !is Map<*, *>) return LinkedHashMap()
    val result = LinkedHashMap<String, Any?>(rawProperties.size)
    rawProperties.forEach { (k, v) -> result[k.toString()] = v }
    return result
}
