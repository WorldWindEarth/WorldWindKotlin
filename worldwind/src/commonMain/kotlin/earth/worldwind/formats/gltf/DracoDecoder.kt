package earth.worldwind.formats.gltf

/**
 * Pluggable codec for Draco mesh compression (`KHR_draco_mesh_compression` glTF extension).
 *
 * The engine has no built-in Draco decoder — Draco's algorithm is ~50K LOC of C++ (edge-
 * breaker connectivity + ANS entropy coding + quantized attribute prediction) and a real
 * decoder ships as a native library per platform. Apps that need to render Draco-
 * compressed 3D Tiles (Google Photorealistic 3D Tiles, many Cesium Ion photogrammetry
 * assets) wire in a platform-specific implementation by setting
 * [GltfDecoderRegistry.dracoDecoder] once at startup.
 *
 * Implementations are expected to be:
 *  - **Thread-safe**: GltfReader.parse is called from background coroutines, potentially
 *    in parallel for different tiles.
 *  - **Stateless** between calls: each [decode] runs against fresh input and produces
 *    fresh output; no setup/teardown across decode calls.
 *  - **Fast**: decode runs on every tile fetch on the streaming hot path; native
 *    implementations should reuse internal buffers.
 *
 * Typical app-side wiring:
 * ```
 * GltfDecoderRegistry.dracoDecoder = MyJvmDracoDecoder() // JNI-backed
 * ```
 *
 * When no decoder is registered, [GltfReader] logs once per tile + skips the
 * Draco-encoded primitive (its vertices are unavailable; nothing visible from that
 * primitive will render).
 */
interface DracoDecoder {

    /**
     * Decode a single Draco-compressed primitive into vertex / index arrays.
     *
     * @param compressedBuffer the bytes of the bufferView referenced by
     *   `KHR_draco_mesh_compression.bufferView`. Already sliced — the implementation
     *   should treat byte 0 as the start of the Draco stream.
     * @param attributeIds map from glTF semantic name (`POSITION`, `NORMAL`,
     *   `TEXCOORD_0`, `COLOR_0`) to the Draco-side attribute id supplied by the
     *   `KHR_draco_mesh_compression.attributes` JSON object. The decoder pulls each
     *   attribute by id from the decoded Draco mesh and emits it under the matching
     *   semantic.
     *
     * @return the decoded mesh. Implementations populate only the attributes requested
     *   in [attributeIds]; null-valued fields are honored as "absent" by downstream
     *   GltfReader code, which falls back to the regular accessors where applicable.
     *
     * @throws IllegalArgumentException for malformed Draco buffers. The layer translates
     *   this into a tile-level failure (the affected tile transitions to FAILED).
     */
    fun decode(compressedBuffer: ByteArray, attributeIds: Map<String, Int>): DracoMesh
}

/**
 * Result of a successful [DracoDecoder.decode] call. Plain copy-out arrays in the same
 * shape [GltfReader] would have built from a non-compressed primitive — the rest of the
 * pipeline (interleave + GPU upload + texture binding) treats Draco and non-Draco
 * primitives identically.
 */
class DracoMesh(
    val positions: FloatArray,
    val normals: FloatArray? = null,
    val texCoords: FloatArray? = null,
    val colors: FloatArray? = null,
    /** Per-vertex b3dm `_BATCHID` if the Draco-compressed primitive carried it. Stored as
     *  uint16 (matching the non-Draco path), with [Short.toInt] giving the unsigned value
     *  after masking with 0xFFFF. Null when the source primitive had no `_BATCHID`. */
    val batchIds: ShortArray? = null,
    val indicesShort: ShortArray? = null,
    val indicesInt: IntArray? = null,
)
