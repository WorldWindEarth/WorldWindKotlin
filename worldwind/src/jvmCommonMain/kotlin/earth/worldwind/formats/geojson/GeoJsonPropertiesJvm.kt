package earth.worldwind.formats.geojson

internal actual fun extractGeoJsonProperties(rawProperties: Any?): LinkedHashMap<String, Any?> =
    extractFromMapLike(rawProperties)
