package earth.worldwind.formats.gltf

import earth.worldwind.geom.Matrix4

/**
 * In-memory model of a parsed glTF 2.0 asset, produced by [GltfReader]. Carries enough of
 * the spec to drive textured + lit mesh rendering for 3D Tiles content; not a full glTF
 * compliance representation.
 *
 * The shape is intentionally flat and copy-free where possible — every primitive owns plain
 * FloatArray / ShortArray / IntArray buffers ready for GPU upload. Materials reference
 * images by index into [images]; per-image bytes carry the MIME type so a platform-side
 * decoder can hand the bytes off to PNG/JPEG decode without inspecting the buffer.
 *
 * Out of scope: full PBR (metallic/roughness textures, normal maps, occlusion, emissive),
 * skeletal animation, morph targets, KHR_draco_mesh_compression. Future PRs can add them
 * without changing the shape of consumers — extra optional fields on the primitives /
 * materials.
 */
class GltfModel internal constructor(
    val meshes: List<GltfMesh>,
    val materials: List<GltfMaterial>,
    val images: List<GltfImage>,
    /** Default scene's root nodes already flattened: each entry is one drawable primitive
     *  with its world-relative model matrix composed up the node tree. */
    val primitives: List<GltfPrimitiveInstance>,
)

/**
 * One mesh primitive (one draw call's worth of geometry). Attribute arrays are tightly
 * packed: positions are vec3, normals vec3, texCoords vec2, colors vec3 or vec4. Indices
 * are either uint16 (most cases) or uint32 (large b3dms); exactly one will be non-null.
 */
class GltfPrimitive internal constructor(
    val positions: FloatArray,
    val normals: FloatArray?,
    val texCoords: FloatArray?,
    val colors: FloatArray?,
    /** Per-vertex `_BATCHID` from b3dm — one uint16 per vertex (the glTF spec allows
     *  UNSIGNED_BYTE / UNSIGNED_SHORT / FLOAT; we normalise to a short, half the size of
     *  the original FLOAT path). Bound to the shader as `GL_UNSIGNED_SHORT` with
     *  `normalized = false`, so the vertex shader sees the actual integer batchId as a
     *  float. Null when the source has no `_BATCHID` attribute. Tiles with more than
     *  65535 batches would overflow — those datasets need a wider format and are out of
     *  scope for now. */
    val batchIds: ShortArray?,
    val indicesShort: ShortArray?,
    val indicesInt: IntArray?,
    /** Index into [GltfModel.materials], or -1 for the default white material. */
    val materialIndex: Int,
    /** glTF primitive mode (4 = TRIANGLES, 1 = LINES, 0 = POINTS, etc.). Default 4. */
    val mode: Int,
)

/**
 * One mesh primitive instance — a [primitiveIndex] under [GltfModel.meshes][meshIndex]
 * pre-composed with the node's world transform. Consumers iterate this list and don't
 * need to walk the original node tree.
 */
class GltfPrimitiveInstance internal constructor(
    val meshIndex: Int,
    val primitiveIndex: Int,
    val worldMatrix: Matrix4,
)

/**
 * A glTF mesh: a list of primitives that share the mesh's name but may use different
 * materials and topologies. Most photogrammetry b3dms ship one mesh with N primitives —
 * one per material.
 */
class GltfMesh internal constructor(
    val primitives: List<GltfPrimitive>,
)

/**
 * glTF material — PBR base color factor + optional base color texture. Phase 2 ignores
 * metallic/roughness/normal/emissive — they're recorded by the reader as defaults for
 * future expansion without consumer churn.
 */
class GltfMaterial internal constructor(
    /** PBR base color (linear sRGB premultiplied with factor; values are in [0, 1]). */
    val baseColorFactor: FloatArray,
    /** Index into [GltfModel.images] for the base color texture, or -1 when none. */
    val baseColorTextureImageIndex: Int,
    /** Texcoord set used by the base color texture. Almost always 0; tracked for completeness. */
    val baseColorTextureTexCoordSet: Int,
    /** True when the material uses alpha blending (alphaMode = "BLEND"). */
    val alphaBlend: Boolean,
    /** True when the material uses alpha masking (alphaMode = "MASK"). */
    val alphaMask: Boolean,
    /** Alpha cutoff used when [alphaMask] is true. Default 0.5 per spec. */
    val alphaCutoff: Float,
    /** True when the material disables back-face culling (doubleSided in glTF). */
    val doubleSided: Boolean,
)

/**
 * Image bytes + MIME type as embedded in (or referenced by) the glTF asset. The reader
 * resolves bufferView-embedded images at parse time so consumers get raw decode-ready
 * bytes either way.
 */
class GltfImage internal constructor(
    /** Raw image bytes (typically JPEG or PNG). Empty when [decodedRgba] is populated
     *  (KTX2 path — the codec already transcoded to RGBA, so the raw KTX2 container is
     *  no longer needed downstream). */
    val bytes: ByteArray,
    /** MIME type as declared in the JSON (`image/jpeg`, `image/png`, `image/ktx2`).
     *  May be empty when the JSON used a `uri` with no explicit MIME — caller may need
     *  to sniff. */
    val mimeType: String,
    /** Pre-decoded RGBA8888 pixel buffer when this image came in via
     *  `KHR_texture_basisu` and a [Ktx2Decoder] was registered. Row-major from top-left,
     *  4 bytes per pixel. Null when the image is still in raw [bytes] form. */
    val decodedRgba: ByteArray? = null,
    /** Image width in pixels when [decodedRgba] is non-null. 0 otherwise. */
    val decodedWidth: Int = 0,
    /** Image height in pixels when [decodedRgba] is non-null. 0 otherwise. */
    val decodedHeight: Int = 0,
)
