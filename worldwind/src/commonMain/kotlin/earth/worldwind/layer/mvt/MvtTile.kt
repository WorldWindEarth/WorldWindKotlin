package earth.worldwind.layer.mvt

/**
 * Decoded Mapbox Vector Tile — the flat in-memory shape of `Tile { repeated Layer }`. Each
 * [MvtLayer] holds its own dictionary of property keys/values plus a list of features whose
 * geometry stream is still in tile-local extent coordinates. Ring grouping, geometry-type-
 * specific handling, and Mercator unprojection happen in [MvtGeometry], not here.
 */
data class MvtTile(val layers: List<MvtLayer>)

/**
 * One named layer inside an MVT tile (e.g. `water`, `transportation`, `building`).
 *
 * Properties are interned: [keys] / [values] are layer-wide dictionaries, [MvtFeature.tags]
 * is the [k, v, k, v, ...] index pair stream. Use [MvtFeature.toProperties] to inflate.
 *
 * [extent] is the tile-local coordinate range. Standard is 4096; spec allows powers of two
 * up to 16384. Decoded geometry is in [0, extent] with Y increasing downward.
 */
data class MvtLayer(
    val name: String,
    val version: Int,
    val extent: Int,
    val keys: List<String>,
    val values: List<Any?>,
    val features: List<MvtFeature>,
)

enum class MvtGeometryType { UNKNOWN, POINT, LINESTRING, POLYGON }

/**
 * One feature. [geometry] is the raw MVT command stream (interleaved CommandIntegers and
 * ParameterIntegers, all as packed uint32s) — pass to [MvtGeometry] to materialise into
 * lat/lon points/lines/rings. [tags] holds [keyIdx, valueIdx, ...] pairs that index into the
 * parent [MvtLayer]'s [MvtLayer.keys] / [MvtLayer.values].
 */
data class MvtFeature(
    val id: Long,
    val type: MvtGeometryType,
    val tags: IntArray,
    val geometry: IntArray,
) {
    /** Inflate [tags] against the parent layer's key/value dictionaries. */
    fun toProperties(layer: MvtLayer): Map<String, Any?> = inflateMvtProperties(tags, layer.keys, layer.values)
}

/** Shared tag→property inflation, letting the pick path defer it (hold raw [tags] + dicts, inflate on demand) instead of retaining a built map per feature. */
internal fun inflateMvtProperties(tags: IntArray, keys: List<String>, values: List<Any?>): Map<String, Any?> {
    if (tags.isEmpty()) return emptyMap()
    val out = LinkedHashMap<String, Any?>(tags.size / 2)
    var i = 0
    while (i + 1 < tags.size) {
        val k = tags[i]
        val v = tags[i + 1]
        if (k in keys.indices && v in values.indices) out[keys[k]] = values[v]
        i += 2
    }
    return out
}

/** Like [inflateMvtProperties] but fills [dst] (cleared first), so a caller can reuse one map per
 *  tile instead of allocating per feature. */
internal fun inflateMvtPropertiesInto(tags: IntArray, keys: List<String>, values: List<Any?>, dst: MutableMap<String, Any?>) {
    dst.clear()
    var i = 0
    while (i + 1 < tags.size) {
        val k = tags[i]
        val v = tags[i + 1]
        if (k in keys.indices && v in values.indices) dst[keys[k]] = values[v]
        i += 2
    }
}
