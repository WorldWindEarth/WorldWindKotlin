package earth.worldwind.formats.i3s

import earth.worldwind.formats.BinaryDataView
import earth.worldwind.formats.gltf.GltfImage
import earth.worldwind.formats.gltf.GltfMaterial
import earth.worldwind.formats.gltf.GltfMesh
import earth.worldwind.formats.gltf.GltfModel
import earth.worldwind.formats.gltf.GltfPrimitive
import earth.worldwind.formats.gltf.GltfPrimitiveInstance
import earth.worldwind.geom.Matrix4

/**
 * Transcodes an I3S node's default (uncompressed) geometry buffer to a [GltfModel] so the existing
 * 3D-Tiles mesh pipeline consumes it unchanged (the JIT "I3S→glTF" strategy CesiumJS uses).
 *
 * The buffer is structure-of-arrays: integer header (vertexCount, featureCount), then each vertex
 * attribute as one contiguous block in schema order, then per-feature blocks. Topology is
 * `PerAttributeArray` (non-indexed → drawn via `drawArrays`); positions are node-local, placed by the
 * tile's `tileToWorld`, so [GltfPrimitiveInstance.worldMatrix] is identity.
 *
 * Scope: Esri default schema ([I3sGeometrySchema.DEFAULT]). TODO: Draco geometry, KTX2/DDS/Basis
 * textures (JPEG/PNG pass through as [GltfImage]); verify attribute order/types on more datasets.
 */
object I3sGeometryDecoder {
    /** A texture resource fetched from the node's `textures/…` entry, paired with its MIME type. */
    class I3sTexture(val bytes: ByteArray, val mimeType: String)

    /** Decode a node's `geometries/{n}.bin` into a one-primitive model. [texture] is the node's
     *  base-color texture (a separate archive entry); [doubleSided] flips back-face culling. */
    fun decode(
        geometryBytes: ByteArray,
        texture: I3sTexture? = null,
        schema: I3sGeometrySchema = I3sGeometrySchema.DEFAULT,
        doubleSided: Boolean = false,
        /** Flat degree→metre scale for position X/Y (geographic layers store Δlon/Δlat in degrees).
         *  Superseded by [vertexMapper] when set; 1.0 = already metres. */
        positionScaleX: Double = 1.0,
        positionScaleY: Double = 1.0,
        /** Emit normals. False → unlit (shader lights only when normals present); pass false for
         *  photogrammetry, whose texture has baked lighting (else double-darkened). */
        emitNormals: Boolean = true,
        /** Exact per-vertex placement via the real projection → adjacent tiles' shared edges coincide
         *  (no periodic tangent-plane seams). Overrides [positionScaleX]/[Y] when set. */
        vertexMapper: I3sVertexMapper? = null,
    ): GltfModel {
        val view = BinaryDataView(geometryBytes)
        var off = 0

        // Guard header reads before the loop so a truncated buffer fails clearly, not mid-read.
        val headerBytes = schema.headerFields.sumOf { it.second.byteSize }
        require(geometryBytes.size >= headerBytes) {
            "I3S geometry truncated: header needs $headerBytes bytes, have ${geometryBytes.size}"
        }
        var vertexCount = 0
        var featureCount = 0
        for ((name, type) in schema.headerFields) {
            val value = readScalar(view, off, type)
            when (name) {
                "vertexCount" -> vertexCount = value.toInt()
                "featureCount" -> featureCount = value.toInt()
            }
            off += type.byteSize
        }
        require(vertexCount >= 0) { "I3S geometry: negative vertexCount=$vertexCount" }

        // Fail early with a clear message rather than an opaque out-of-bounds mid-read.
        val vertexBytes = schema.vertexOrdering.sumOf { vertexCount.toLong() * it.componentsPerVertex * it.type.byteSize }
        require(off + vertexBytes <= geometryBytes.size) {
            "I3S geometry truncated: header+vertex blocks need ${off + vertexBytes} bytes, have ${geometryBytes.size}"
        }

        var positions = FloatArray(0)
        var normals: FloatArray? = null
        var texCoords: FloatArray? = null
        var colors: FloatArray? = null

        for (attr in schema.vertexOrdering) {
            val components = vertexCount * attr.componentsPerVertex
            when (attr.name.lowercase()) {
                "position" -> {
                    val mapper = vertexMapper
                    if (mapper != null) {
                        // Exact: map each (Δlon°, Δlat°, Δheight) through the real projection.
                        positions = FloatArray(components)
                        var v = 0; var p = off
                        while (v < vertexCount) {
                            mapper.map(
                                view.getFloat32(p, littleEndian = true),
                                view.getFloat32(p + 4, littleEndian = true),
                                view.getFloat32(p + 8, littleEndian = true),
                                positions, v * 3,
                            )
                            p += 12; v++
                        }
                    } else {
                        // Fallback: flat tangent-plane scaling (degrees → local metres).
                        positions = readFloat32(view, off, components)
                        if (positionScaleX != 1.0 || positionScaleY != 1.0) {
                            val sx = positionScaleX.toFloat(); val sy = positionScaleY.toFloat()
                            var i = 0
                            while (i < positions.size) {
                                positions[i] *= sx; positions[i + 1] *= sy; i += 3
                            }
                        }
                    }
                }
                "normal" -> if (emitNormals) normals = readFloat32(view, off, components)
                "uv0", "uv", "texcoord" -> texCoords = readFloat32(view, off, components)
                "color" -> colors = readColorRgba(view, off, vertexCount, attr)
                else -> {} // unknown attribute: skip its block
            }
            off += components * attr.type.byteSize
        }
        // Feature attributes (id / faceRange…) are per-feature and used for picking — skipped for now.

        val hasTexture = texture != null && texCoords != null
        val material = GltfMaterial(
            baseColorFactor = floatArrayOf(1f, 1f, 1f, 1f),
            baseColorTextureImageIndex = if (hasTexture) 0 else -1,
            baseColorTextureTexCoordSet = 0,
            alphaBlend = false,
            alphaMask = false,
            alphaCutoff = 0.5f,
            doubleSided = doubleSided,
        )
        val primitive = GltfPrimitive(
            positions = positions,
            normals = normals,
            texCoords = texCoords,
            colors = colors,
            batchIds = null,
            indicesShort = null, // PerAttributeArray topology → non-indexed, drawn via drawArrays
            indicesInt = null,
            materialIndex = 0,
            mode = 4, // TRIANGLES
        )
        return GltfModel(
            meshes = listOf(GltfMesh(listOf(primitive))),
            materials = listOf(material),
            images = if (texture != null) listOf(GltfImage(texture.bytes, texture.mimeType)) else emptyList(),
            primitives = listOf(GltfPrimitiveInstance(meshIndex = 0, primitiveIndex = 0, worldMatrix = Matrix4())),
        )
    }

    private fun readScalar(view: BinaryDataView, offset: Int, type: I3sValueType): Long = when (type) {
        I3sValueType.UINT8 -> view.getUint8(offset).toLong()
        I3sValueType.UINT16 -> view.getUint16(offset, littleEndian = true).toLong()
        I3sValueType.UINT32 -> view.getUint32(offset, littleEndian = true)
        I3sValueType.UINT64 -> view.getInt64(offset, littleEndian = true)
        I3sValueType.FLOAT32 -> view.getFloat32(offset, littleEndian = true).toLong()
    }

    private fun readFloat32(view: BinaryDataView, offset: Int, count: Int): FloatArray {
        val out = FloatArray(count)
        var p = offset
        for (i in 0 until count) { out[i] = view.getFloat32(p, littleEndian = true); p += 4 }
        return out
    }

    /** Read a color block into RGBA float [0,1], expanding RGB→RGBA (alpha 1) when needed. */
    private fun readColorRgba(view: BinaryDataView, offset: Int, vertexCount: Int, attr: I3sAttribute): FloatArray {
        val out = FloatArray(vertexCount * 4)
        val comps = attr.componentsPerVertex
        var p = offset
        for (v in 0 until vertexCount) {
            val d = v * 4
            if (attr.type == I3sValueType.FLOAT32) {
                out[d] = view.getFloat32(p, true); out[d + 1] = view.getFloat32(p + 4, true); out[d + 2] = view.getFloat32(p + 8, true)
                out[d + 3] = if (comps >= 4) view.getFloat32(p + 12, true) else 1f
            } else { // UINT8 RGBA (the common case)
                out[d] = view.getUint8(p) / 255f; out[d + 1] = view.getUint8(p + 1) / 255f; out[d + 2] = view.getUint8(p + 2) / 255f
                out[d + 3] = if (comps >= 4) view.getUint8(p + 3) / 255f else 1f
            }
            p += comps * attr.type.byteSize
        }
        return out
    }
}

/**
 * Maps a raw I3S vertex (Δlon°, Δlat°, Δheight m relative to the node origin) to a local Cartesian
 * offset from the tile origin, written into `out[offset..offset+2]`. Backed by the real projection so
 * tiles share a consistent curved surface (no tangent-plane seams).
 */
fun interface I3sVertexMapper {
    fun map(deltaLonDeg: Float, deltaLatDeg: Float, deltaHeightM: Float, out: FloatArray, offset: Int)
}

/** Primitive value types used by I3S geometry/feature attributes, with their byte width. */
enum class I3sValueType(val byteSize: Int) {
    UINT8(1), UINT16(2), UINT32(4), UINT64(8), FLOAT32(4);
}

/** One attribute block in an I3S geometry buffer. */
class I3sAttribute(val name: String, val type: I3sValueType, val componentsPerVertex: Int)

/**
 * Binary layout of an I3S default geometry buffer: integer [headerFields], then each
 * [vertexOrdering] attribute as one contiguous `vertexCount`-long block, then each [featureOrdering]
 * attribute as one `featureCount`-long block. [DEFAULT] is the common Esri schema.
 */
class I3sGeometrySchema(
    val headerFields: List<Pair<String, I3sValueType>>,
    val vertexOrdering: List<I3sAttribute>,
    val featureOrdering: List<I3sAttribute>,
) {
    companion object {
        val DEFAULT = I3sGeometrySchema(
            headerFields = listOf("vertexCount" to I3sValueType.UINT32, "featureCount" to I3sValueType.UINT32),
            vertexOrdering = listOf(
                I3sAttribute("position", I3sValueType.FLOAT32, 3),
                I3sAttribute("normal", I3sValueType.FLOAT32, 3),
                I3sAttribute("uv0", I3sValueType.FLOAT32, 2),
                I3sAttribute("color", I3sValueType.UINT8, 4),
            ),
            featureOrdering = listOf(
                I3sAttribute("id", I3sValueType.UINT64, 1),
                I3sAttribute("faceRange", I3sValueType.UINT32, 2),
            ),
        )
    }
}
