package earth.worldwind.formats.gltf

import kotlin.concurrent.Volatile

/**
 * Process-level registry of pluggable codecs the glTF parser consults when it encounters
 * compressed payloads. Apps wire concrete implementations once at startup (typically in
 * their entry-point's first lines); [GltfReader] reads the registry on the streaming hot
 * path without per-call plumbing.
 *
 * Process-level (vs. per-layer): every codec here is a stateless byte-to-array transform.
 * Having two consumers in the same app use different Draco / KTX2 decoders has no
 * legitimate use case, and a global registry avoids constructor-arg churn.
 *
 * **Thread-safety**: fields are `@Volatile` for visibility but writes should happen during
 * startup, before any glTF parse begins. Hot-swapping mid-run is undefined for assets
 * already mid-decode.
 *
 * Codec implementations ship as separate sibling modules:
 *  - `worldwind-formats-gltf-draco` — `DracoDecoder` backed by libdraco
 *  - `worldwind-formats-gltf-ktx2` — `Ktx2Decoder` backed by libktx (Basis Universal)
 *  - `worldwind-formats-gltf-meshopt` — `MeshoptDecoder` backed by meshoptimizer
 */
object GltfDecoderRegistry {

    /**
     * Decoder for the `KHR_draco_mesh_compression` glTF extension. Null = unsupported;
     * Draco-compressed primitives log once + skip.
     *
     * Typical wiring (via the worldwind-formats-gltf-draco module):
     * ```
     * scope.launch { installDracoDecoder() }
     * ```
     */
    @Volatile var dracoDecoder: DracoDecoder? = null

    /**
     * Decoder for the `KHR_texture_basisu` glTF extension (KTX2 / Basis Universal compressed
     * textures). Null = unsupported; KTX2 texture references log once + the texture is
     * omitted, leaving the affected primitive untextured.
     */
    @Volatile var ktx2Decoder: Ktx2Decoder? = null

    /**
     * Decoder for the `EXT_meshopt_compression` glTF extension (meshoptimizer-compressed
     * bufferViews). Null = unsupported; meshopt-compressed bufferViews log once + are
     * skipped, leaving affected primitives without those attributes (typically renders the
     * primitive as missing or with default values for the affected components).
     */
    @Volatile var meshoptDecoder: MeshoptDecoder? = null
}
