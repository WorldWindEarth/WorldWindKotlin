package earth.worldwind.formats.geojson

internal actual fun extractGeoJsonProperties(rawProperties: Any?): LinkedHashMap<String, Any?> {
    if (rawProperties == null) return LinkedHashMap()
    // If the library ever hands us a real Kotlin Map (e.g. via a future serialization
    // backend) the shared map walker is enough; otherwise fall through to the JS path.
    val asMap = extractFromMapLike(rawProperties)
    if (asMap.isNotEmpty()) return asMap
    // data2viz on Kotlin/JS leaves `properties` as a native JS object literal — walk its
    // keys with Object.keys to copy the entries into a LinkedHashMap.
    val result = LinkedHashMap<String, Any?>()
    val keys = js("Object.keys")(rawProperties).unsafeCast<Array<String>>()
    for (k in keys) {
        result[k] = rawProperties.asDynamic()[k]
    }
    return result
}
