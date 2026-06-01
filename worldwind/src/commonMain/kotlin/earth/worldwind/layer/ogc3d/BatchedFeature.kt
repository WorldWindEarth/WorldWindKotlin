package earth.worldwind.layer.ogc3d

import earth.worldwind.formats.ogc3d.BatchTable
import earth.worldwind.layer.ogc3d.tileset.Tile3d
import kotlinx.serialization.json.JsonElement

/**
 * One picked feature from a batch-table tile. `PickedObject.userObject` when batch picking
 * is active; the surrounding PickedObject still carries the world-space hit.
 */
class BatchedFeature internal constructor(
    val tile: Tile3d,
    /** `0 until batchTable.batchLength`. */
    val batchId: Int,
    val batchTable: BatchTable,
) {
    fun propertyValue(key: String): JsonElement? = batchTable.propertyValue(batchId, key)

    /** Allocates per call. */
    val properties: Map<String, JsonElement> get() = batchTable.propertiesFor(batchId)

    override fun toString() = "BatchedFeature(tile=${tile.contentUri}, batchId=$batchId)"
}
