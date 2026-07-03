package earth.worldwind.formats.ogc3d

import earth.worldwind.formats.BinaryDataView
import earth.worldwind.formats.gltf.GlbReader
import earth.worldwind.formats.gltf.GltfModel
import earth.worldwind.formats.gltf.GltfReader

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
    fun parse(bytes: ByteArray): I3dmPayload {
        // i3dm = the standard 28-byte 3D Tiles header + a trailing gltfFormat uint32 at offset 28.
        val header = Ogc3dHeader.parse(bytes, extraHeaderBytes = 4)
        require(header.magic == "i3dm") { "i3dm magic mismatch: got '${header.magic}'" }
        val gltfFormat = BinaryDataView(bytes).getInt32(Ogc3dHeader.HEADER_SIZE, littleEndian = true)
        require(gltfFormat == 1) {
            "i3dm gltfFormat=$gltfFormat unsupported (only embedded GLB, gltfFormat=1, is wired)"
        }

        val glbOffset = header.payloadOffset
        val glbLength = header.byteLength - glbOffset
        require(glbLength > 0) {
            "i3dm header sub-lengths overflow byteLength: header sum=$glbOffset, byteLength=${header.byteLength}"
        }

        val ft = FeatureTable.parse(
            bytes = bytes,
            jsonOffset = header.featureTableJsonOffset,
            jsonLength = header.featureTableJsonByteLength,
            binOffset = header.featureTableBinaryOffset,
            binLength = header.featureTableBinaryByteLength,
        )
        val instancesLength = ft.scalarOrNull("INSTANCES_LENGTH")?.toInt()
            ?: error("i3dm INSTANCES_LENGTH missing from feature table")
        val rtcCenter = ft.vec3OrNull("RTC_CENTER")

        val positions = ft.readVec3Float("POSITION", instancesLength)
            ?: error("i3dm POSITION accessor missing from feature table")
        val scales: FloatArray? = ft.readVec3Float("SCALE_NON_UNIFORM", instancesLength)
            ?: ft.readScalarFloat("SCALE", instancesLength)?.let { uniformScales ->
                FloatArray(instancesLength * 3) { i -> uniformScales[i / 3] }
            }

        val glb = GlbReader.parse(bytes, glbOffset, glbLength)
        val gltf = GltfReader.parse(glb.jsonText, glb.binChunk)
        return I3dmPayload(gltf, instancesLength, positions, scales, rtcCenter)
    }
}
