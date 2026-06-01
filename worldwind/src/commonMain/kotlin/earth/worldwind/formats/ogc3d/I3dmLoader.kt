package earth.worldwind.formats.ogc3d

import earth.worldwind.formats.BinaryDataView
import earth.worldwind.formats.gltf.GlbReader
import earth.worldwind.formats.gltf.GltfModel
import earth.worldwind.formats.gltf.GltfReader
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Decoded i3dm payload: a shared glTF mesh + a flat list of per-instance transforms.
 * Renderer emits one instanced draw call (or [instancesLength] non-instanced draws on
 * platforms without `glDrawElementsInstanced`).
 */
class I3dmPayload internal constructor(
    val gltf: GltfModel,
    val instancesLength: Int,
    /** Per-instance translation, flattened (x0, y0, z0, x1, y1, z1, ...). Always present. */
    val positions: FloatArray,
    /** Per-instance scale, flattened (sx0, sy0, sz0, ...). null when the asset uses uniform
     *  scale-1 (the renderer treats null as identity to save an attribute upload). */
    val scales: FloatArray?,
    /** RTC_CENTER (instance position offset). Null when absent. */
    val rtcCenter: DoubleArray?,
)

/**
 * Parses an Instanced 3D Model payload. The i3dm header is 32 bytes (the standard 28-byte
 * 3D Tiles header plus a uint32 `gltfFormat` field at offset 28). Only the embedded-GLB
 * form (`gltfFormat == 1`) is supported — the legacy URL form is rejected.
 *
 * ```
 * Bytes 0..3   : magic = "i3dm"
 * Bytes 4..7   : version = 1
 * Bytes 8..11  : byteLength
 * Bytes 12..15 : featureTableJSONByteLength
 * Bytes 16..19 : featureTableBinaryByteLength
 * Bytes 20..23 : batchTableJSONByteLength
 * Bytes 24..27 : batchTableBinaryByteLength
 * Bytes 28..31 : gltfFormat (0 = url, 1 = embedded GLB)
 * Then feature/batch tables, then the GLB.
 * ```
 */
object I3dmLoader {
    private const val HEADER_SIZE = 32

    fun parse(bytes: ByteArray): I3dmPayload {
        require(bytes.size >= HEADER_SIZE) { "i3dm payload truncated (size ${bytes.size})" }
        val view = BinaryDataView(bytes)
        val magic = bytes.decodeToString(0, 4)
        require(magic == "i3dm") { "i3dm magic mismatch: got '$magic'" }
        val version = view.getInt32(4, littleEndian = true)
        require(version == 1) { "i3dm version mismatch: expected 1, got $version" }
        val byteLength = view.getInt32(8, littleEndian = true)
        val ftJsonLen = view.getInt32(12, littleEndian = true)
        val ftBinLen = view.getInt32(16, littleEndian = true)
        val btJsonLen = view.getInt32(20, littleEndian = true)
        val btBinLen = view.getInt32(24, littleEndian = true)
        val gltfFormat = view.getInt32(28, littleEndian = true)
        require(gltfFormat == 1) {
            "i3dm gltfFormat=$gltfFormat unsupported (only embedded GLB, gltfFormat=1, is wired)"
        }

        val ftJsonOffset = HEADER_SIZE
        val ftBinOffset = ftJsonOffset + ftJsonLen
        val btJsonOffset = ftBinOffset + ftBinLen
        val btBinOffset = btJsonOffset + btJsonLen
        val glbOffset = btBinOffset + btBinLen
        val glbLength = byteLength - glbOffset
        require(glbLength > 0) {
            "i3dm header sub-lengths overflow byteLength: header sum=$glbOffset, byteLength=$byteLength"
        }

        val ft = FeatureTable.parse(
            bytes = bytes,
            jsonOffset = ftJsonOffset,
            jsonLength = ftJsonLen,
            binOffset = ftBinOffset,
            binLength = ftBinLen,
        )
        val instancesLength = ft.scalarOrNull("INSTANCES_LENGTH")?.toInt()
            ?: error("i3dm INSTANCES_LENGTH missing from feature table")
        val rtcCenter = ft.vec3OrNull("RTC_CENTER")

        val positions = readVec3Accessor(ft, "POSITION", instancesLength)
            ?: error("i3dm POSITION accessor missing from feature table")
        val scales: FloatArray? = readVec3Accessor(ft, "SCALE_NON_UNIFORM", instancesLength)
            ?: readScalarAccessor(ft, "SCALE", instancesLength)?.let { uniformScales ->
                FloatArray(instancesLength * 3) { i -> uniformScales[i / 3] }
            }

        val glb = GlbReader.parse(bytes, glbOffset, glbLength)
        val gltf = GltfReader.parse(glb.jsonText, glb.binChunk)
        return I3dmPayload(gltf, instancesLength, positions, scales, rtcCenter)
    }

    private fun readVec3Accessor(ft: FeatureTable, key: String, count: Int): FloatArray? {
        val el = ft.jsonElement(key) as? JsonObject ?: return null
        val byteOffset = (el["byteOffset"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
        val out = FloatArray(count * 3)
        val view = BinaryDataView(ft.binary)
        for (i in 0 until count) {
            for (c in 0 until 3) {
                out[i * 3 + c] = view.getFloat32(byteOffset + (i * 3 + c) * 4, littleEndian = true)
            }
        }
        return out
    }

    private fun readScalarAccessor(ft: FeatureTable, key: String, count: Int): FloatArray? {
        val el = ft.jsonElement(key) as? JsonObject ?: return null
        val byteOffset = (el["byteOffset"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
        val view = BinaryDataView(ft.binary)
        return FloatArray(count) { i -> view.getFloat32(byteOffset + i * 4, littleEndian = true) }
    }
}
