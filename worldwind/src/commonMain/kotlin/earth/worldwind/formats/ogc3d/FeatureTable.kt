package earth.worldwind.formats.ogc3d

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Parsed feature + batch tables from a 3D Tiles container. Each table has a JSON section
 * describing per-feature scalars (or accessor references) and a binary section holding the
 * raw values referenced by the JSON.
 *
 * Phase 2 only consumes a few fields:
 *  - b3dm: `RTC_CENTER` (translation applied to every vertex), `BATCH_LENGTH`.
 *  - i3dm: `INSTANCES_LENGTH`, `POSITION` / `POSITION_QUANTIZED`, `NORMAL_UP` / `NORMAL_RIGHT`,
 *    `SCALE` / `SCALE_NON_UNIFORM`.
 *  - pnts: covered in Phase 3.
 *
 * Lookup methods return the raw [JsonElement] (so callers can branch on scalar vs accessor)
 * and the binary section as bytes ready for indexed reads.
 */
class FeatureTable internal constructor(
    private val jsonRoot: JsonObject,
    /** Raw binary section; sliced from the input. May be empty when no binary refs are used. */
    val binary: ByteArray,
) {
    /** Lookup a top-level key. Returns null when absent. */
    fun jsonElement(key: String): JsonElement? = jsonRoot[key]

    /** Compact JSON dump for diagnostics — used in parse-failure messages. */
    fun jsonDebugString(): String = jsonRoot.toString()

    /** Lookup a scalar number. Returns null when the key is missing or is not a primitive. */
    fun scalarOrNull(key: String): Double? {
        val el = jsonRoot[key] as? JsonPrimitive ?: return null
        return el.content.toDoubleOrNull()
    }

    /** Lookup a string value. Returns null when the key is missing, not a primitive, or not
     *  a JSON string (e.g. the `gltfUpAxis` override that some b3dm publishers emit in the
     *  feature table to override the tileset-level declaration). */
    fun stringOrNull(key: String): String? {
        val el = jsonRoot[key] as? JsonPrimitive ?: return null
        if (!el.isString) return null
        return el.content
    }

    /** Decode the feature-table value at [key] as a 3-tuple of doubles (e.g. RTC_CENTER). */
    fun vec3OrNull(key: String): DoubleArray? {
        val arr = jsonRoot[key] as? JsonArray ?: return null
        if (arr.size < 3) return null
        return DoubleArray(3) { i ->
            (arr[i] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * @param bytes the surrounding byte buffer
         * @param jsonOffset offset of the JSON section
         * @param jsonLength length of the JSON section
         * @param binOffset offset of the binary section
         * @param binLength length of the binary section
         */
        fun parse(
            bytes: ByteArray,
            jsonOffset: Int,
            jsonLength: Int,
            binOffset: Int,
            binLength: Int,
        ): FeatureTable {
            val jsonText = if (jsonLength > 0) {
                bytes.decodeToString(jsonOffset, jsonOffset + jsonLength).trimEnd(' ', ' ')
            } else "{}"
            val root = json.parseToJsonElement(jsonText) as? JsonObject ?: JsonObject(emptyMap())
            val bin = if (binLength > 0) bytes.sliceArray(binOffset until binOffset + binLength) else ByteArray(0)
            return FeatureTable(root, bin)
        }
    }
}
