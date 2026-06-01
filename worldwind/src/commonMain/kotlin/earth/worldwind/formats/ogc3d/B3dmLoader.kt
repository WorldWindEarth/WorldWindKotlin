package earth.worldwind.formats.ogc3d

import earth.worldwind.formats.gltf.GlbReader
import earth.worldwind.formats.gltf.GltfModel
import earth.worldwind.formats.gltf.GltfReader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Decoded b3dm payload: the embedded glTF + feature table (for RTC_CENTER + optional
 * `gltfUpAxis` override) + the batch table. The renderer applies `rtcCenter` as an extra
 * translation when present — per spec, b3dm authors may offset every vertex by a tile-
 * relative center to keep numerical precision tight at city scale.
 */
class B3dmPayload internal constructor(
    val gltf: GltfModel,
    /** RTC_CENTER in the b3dm feature table, or null when the asset is already in tile-local
     *  coordinates. Length 3 (x, y, z). */
    val rtcCenter: DoubleArray?,
    /** Batch length (number of features). 0 when the payload is a single un-batched mesh. */
    val batchLength: Int,
    /** Parsed batch table mapping `batchId → properties` for per-feature picking. Null when
     *  the payload has no batch table or `batchLength == 0`. */
    val batchTable: BatchTable?,
    /** `gltfUpAxis` override declared in the feature-table JSON: takes precedence over the
     *  tileset-level `asset.gltfUpAxis`. Null when absent — the layer falls through to the
     *  tileset declaration, then to its default. */
    val gltfUpAxisOverride: String?,
)

/**
 * Parses a Batched 3D Model payload. The container wraps a glTF GLB binary; this loader
 * is essentially [Ogc3dHeader] + [FeatureTable] + [BatchTable] + [GlbReader] + [GltfReader]
 * glued together with the RTC_CENTER, batch length, and per-feature properties extracted
 * into [B3dmPayload].
 */
object B3dmLoader {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(bytes: ByteArray): B3dmPayload {
        val header = Ogc3dHeader.parse(bytes)
        require(header.magic == "b3dm") { "B3dmLoader called on a non-b3dm payload (magic '${header.magic}')" }
        val featureTable = FeatureTable.parse(
            bytes = bytes,
            jsonOffset = header.featureTableJsonOffset,
            jsonLength = header.featureTableJsonByteLength,
            binOffset = header.featureTableBinaryOffset,
            binLength = header.featureTableBinaryByteLength,
        )
        val rtcCenter = featureTable.vec3OrNull("RTC_CENTER")
        val batchLength = featureTable.scalarOrNull("BATCH_LENGTH")?.toInt() ?: 0
        // Per-tile axis override (informal `EXT_b3dm_gltf_up_axis` convention some publishers
        // adopted from Cesium). Wins over the tileset's `asset.gltfUpAxis`.
        val gltfUpAxisOverride = featureTable.stringOrNull("gltfUpAxis")

        val batchTable = if (batchLength > 0 && header.batchTableJsonByteLength > 0) {
            val jsonText = bytes.decodeToString(
                header.batchTableJsonOffset,
                header.batchTableJsonOffset + header.batchTableJsonByteLength,
            ).trimEnd(' ', '\u0000')
            val root = json.parseToJsonElement(jsonText) as? JsonObject ?: JsonObject(emptyMap())
            val bin = if (header.batchTableBinaryByteLength > 0) {
                bytes.sliceArray(
                    header.batchTableBinaryOffset until
                        header.batchTableBinaryOffset + header.batchTableBinaryByteLength,
                )
            } else ByteArray(0)
            BatchTable(jsonRoot = root, binary = bin, batchLength = batchLength)
        } else null

        val glbOffset = header.payloadOffset
        val glbLength = header.byteLength - glbOffset
        require(glbLength > 0) {
            "b3dm header sub-lengths overflow byteLength: header end=$glbOffset, byteLength=${header.byteLength}"
        }
        val glb = GlbReader.parse(bytes, glbOffset, glbLength)
        val gltf = GltfReader.parse(glb.jsonText, glb.binChunk)
        return B3dmPayload(
            gltf = gltf,
            rtcCenter = rtcCenter,
            batchLength = batchLength,
            batchTable = batchTable,
            gltfUpAxisOverride = gltfUpAxisOverride,
        )
    }
}
