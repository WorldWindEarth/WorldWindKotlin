package earth.worldwind.formats.ogc3d

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Parsed b3dm / i3dm batch table indexed by `batchId`. JSON-array columns are decoded;
 * binary accessor columns require the caller to follow [jsonRoot] / [binary] by hand.
 */
class BatchTable internal constructor(
    val jsonRoot: JsonObject,
    /** Empty for JSON-only batch tables. */
    val binary: ByteArray,
    /** Equal to `BATCH_LENGTH` from the feature table. */
    val batchLength: Int,
) {
    val columns: Set<String> get() = jsonRoot.keys

    /** Value at [batchId] for a JSON-array column. Null for missing / binary-accessor columns. */
    fun propertyValue(batchId: Int, key: String): JsonElement? {
        if (batchId !in 0 until batchLength) return null
        val col = jsonRoot[key] as? JsonArray ?: return null
        return col.getOrNull(batchId)
    }

    /** All JSON-array columns at [batchId]. Allocates per call. */
    fun propertiesFor(batchId: Int): Map<String, JsonElement> {
        if (batchId !in 0 until batchLength) return emptyMap()
        val out = mutableMapOf<String, JsonElement>()
        for ((key, value) in jsonRoot) {
            if (value is JsonArray) value.getOrNull(batchId)?.let { out[key] = it }
        }
        return out
    }
}
