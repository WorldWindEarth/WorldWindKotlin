package earth.worldwind.formats.geojson

/** Typed view of an arbitrary JS object as a string-keyed map. The actual JS object never
 *  changes at runtime; only the Kotlin static type does, so we can index it without
 *  falling through to fully untyped `asDynamic()`. */
private external interface JsStringIndexedObject {
    operator fun get(key: String): Any?
}

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
    val indexed = rawProperties.unsafeCast<JsStringIndexedObject>()
    for (k in keys) {
        result[k] = indexed[k]
    }
    return result
}
