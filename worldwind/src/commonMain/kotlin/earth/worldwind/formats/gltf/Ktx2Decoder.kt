package earth.worldwind.formats.gltf

/**
 * Pluggable codec for KTX2 / Basis-Universal texture compression (`KHR_texture_basisu`
 * glTF extension).
 *
 * The engine has no built-in KTX2 decoder — Basis Universal's transcoder is ~30K LOC of
 * C++ (ETC1S / UASTC block decoding + endpoint dequantisation + selector palettes) and a
 * real decoder ships as a native library per platform. Apps that need to render
 * KTX2-compressed 3D Tiles (Google Photorealistic 3D Tiles, many Cesium Ion photogrammetry
 * assets) wire in a platform-specific implementation by setting
 * [earth.worldwind.layer.ogc3d.GltfDecoderRegistry.ktx2Decoder] once at startup.
 *
 * Implementations are expected to be:
 *  - **Thread-safe**: GltfReader.parse is called from background coroutines, potentially
 *    in parallel for different tiles.
 *  - **Stateless** between calls: each [decode] runs against fresh input and produces
 *    fresh output.
 *  - **Fast**: decode runs on every textured tile fetch; native implementations should
 *    reuse internal scratch buffers.
 *
 * Typical app-side wiring:
 * ```
 * GltfDecoderRegistry.ktx2Decoder = MyJvmKtx2Decoder()  // JNI-backed
 * ```
 *
 * When no decoder is registered, [GltfReader] logs once + emits no image bytes for the
 * affected texture; the layer renders the primitive untextured (vertex colour or material
 * base-colour factor only).
 */
interface Ktx2Decoder {

    /**
     * Decode a single KTX2 / Basis-Universal compressed texture into an RGBA8888 pixel
     * buffer plus its dimensions.
     *
     * @param ktx2Bytes the bytes of the bufferView (or external image file) referenced by
     *   the `KHR_texture_basisu` extension's `source` index. Already sliced — byte 0 is
     *   the start of the KTX2 container.
     * @return decoded image. The decoder transcodes to RGBA8888 (4 bytes per pixel, row-
     *   major from top-left) regardless of the encoded format; downstream texture upload
     *   then converts to whatever GL internal format the target platform prefers.
     *
     * @throws IllegalArgumentException for malformed KTX2 input. The layer translates this
     *   into a per-texture failure (the affected primitive renders untextured; other
     *   primitives in the same tile are unaffected).
     */
    fun decode(ktx2Bytes: ByteArray): Ktx2Image
}

/**
 * Result of a successful [Ktx2Decoder.decode] call. RGBA8888 pixel buffer plus width and
 * height — the rest of the texture pipeline (mipmap generation, GL upload) treats KTX2-
 * sourced and non-KTX2-sourced images identically.
 */
class Ktx2Image(
    val rgba: ByteArray,
    val width: Int,
    val height: Int,
)
