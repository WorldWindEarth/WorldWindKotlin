package earth.worldwind.formats.gltf

/**
 * Pluggable codec for the `EXT_meshopt_compression` glTF extension.
 *
 * Meshopt (Arseny Kapoulkine's meshoptimizer) is a fast-decode mesh compression scheme
 * that compresses bufferViews directly — by contrast Draco operates on whole primitives,
 * and KTX2 on textures. The decoder gets the compressed bufferView bytes plus the per-
 * element layout (count + byteStride + mode + optional filter) and returns the
 * decompressed bufferView in the same byte layout the glTF accessors expect.
 *
 * The engine has no built-in implementation; the codec ships as `worldwind-formats-gltf-meshopt`.
 *
 * Implementations must be thread-safe and stateless. Decode runs on every mesh tile fetch
 * that ships meshopt-compressed primitives (Google Photorealistic 3D Tiles' Draco
 * alternative; Cesium Ion supports it for some assets).
 */
interface MeshoptDecoder {

    /**
     * Decode an EXT_meshopt_compression bufferView.
     *
     * @param compressed bytes of the compressed bufferView the extension declared.
     * @param count number of elements (vertices / indices).
     * @param byteStride bytes per element in the decoded output.
     * @param mode element type — drives which decoder path runs.
     * @param filter optional post-decode transform applied per element (octahedral
     *   normal unpack, quaternion unpack, exponential float decompression). `null` means
     *   no filter.
     * @return the decoded bufferView, `count * byteStride` bytes.
     *
     * @throws IllegalArgumentException for malformed compressed input. The layer
     *   translates this into a per-bufferView failure; affected primitives' attributes
     *   degrade gracefully (positions absent → primitive skipped; secondary attribute
     *   absent → falls back to baseColor / flat shading).
     */
    fun decode(
        compressed: ByteArray,
        count: Int,
        byteStride: Int,
        mode: Mode,
        filter: Filter? = null,
    ): ByteArray

    /** What the compressed bufferView encodes. */
    enum class Mode {
        /** vec3 / vec4 attribute streams (position, normal, etc.). */
        ATTRIBUTES,
        /** Indexed triangle strip / list. */
        TRIANGLES,
        /** Index buffer for non-triangle topology. */
        INDICES,
    }

    /** Per-element transform applied after the base decode. Maps to meshoptimizer's
     *  `meshopt_decodeFilter*` family. */
    enum class Filter {
        /** Octahedral-encoded unit vector → vec3 normal. */
        OCTAHEDRAL,
        /** Quantised quaternion → vec4 rotation. */
        QUATERNION,
        /** Exponential-encoded floats. */
        EXPONENTIAL,
    }
}
