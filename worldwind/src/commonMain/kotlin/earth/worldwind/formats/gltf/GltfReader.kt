package earth.worldwind.formats.gltf

import earth.worldwind.formats.BinaryDataView
import earth.worldwind.geom.Matrix4
import earth.worldwind.layer.ogc3d.Ogc3dDecoderRegistry
import earth.worldwind.util.Logger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Parses a glTF 2.0 JSON document + optional BIN buffer into a [GltfModel] suitable for
 * driving textured + lit mesh draws. Built for the b3dm / i3dm / cmpt content paths plus
 * the 3D Tiles 1.1 `3DTILES_content_gltf` extension; reads enough of the spec for
 * photogrammetry-style assets and ignores features the renderer doesn't yet consume
 * (skinning, morph targets, animations, KHR_draco compression, KTX2 textures).
 *
 * Distinct from the older [GltfLoader] which is wired to the tutorial's single-asset
 * `GltfScene` API and supports only data-URI buffers; this reader supports the BIN-chunk
 * pathway used by every b3dm in the wild.
 */
object GltfReader {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Parse a glTF asset.
     *
     * @param jsonText JSON document (chunk 0 of a GLB, or raw .gltf file contents).
     * @param binChunk binary chunk (chunk 1 of a GLB), or null for JSON-only assets. When
     *   present, every accessor's bufferView references buffer 0 = this chunk.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun parse(jsonText: String, binChunk: ByteArray?): GltfModel {
        val doc = json.decodeFromString<G2Doc>(jsonText)
        // Decode every buffer. Buffer 0 in a GLB has no URI and resolves to the BIN chunk;
        // other buffers must carry a `data:` URI (the 3D Tiles content path doesn't fetch
        // external buffer files — that's an external-asset feature we deliberately skip).
        val buffers: List<ByteArray> = doc.buffers.mapIndexed { idx, b ->
            when {
                idx == 0 && (b.uri.isEmpty() || b.uri == "data:") && binChunk != null -> binChunk
                b.uri.startsWith("data:") -> {
                    val commaIdx = b.uri.indexOf(',')
                    if (commaIdx >= 0) Base64.decode(b.uri, commaIdx + 1) else ByteArray(0)
                }
                else -> ByteArray(0)
            }
        }

        // Identify which image indices source a KTX2 / Basis-Universal payload via the
        // `KHR_texture_basisu` texture extension. These need transcoding through the
        // registered [Ktx2Decoder]; the regular PNG/JPG image-decode path can't read KTX2.
        val basisuImageIndices: Set<Int> = doc.textures
            .mapNotNull { it.extensions?.basisu?.source.takeIf { idx -> idx != null && idx >= 0 } }
            .toSet()
        val ktx2Decoder = Ogc3dDecoderRegistry.ktx2Decoder
        var ktx2SkipLogged = false

        // Decode embedded images. glTF images can reference either a bufferView (preferred
        // for GLB) or a data: URI; both produce raw decode-ready bytes.
        val images: List<GltfImage> = doc.images.mapIndexed { idx, img ->
            val bytes = when {
                img.bufferView >= 0 -> {
                    val bv = doc.bufferViews.getOrNull(img.bufferView)
                    val buf = bv?.let { buffers.getOrNull(it.buffer) }
                    if (bv != null && buf != null) {
                        buf.sliceArray(bv.byteOffset until bv.byteOffset + bv.byteLength)
                    } else ByteArray(0)
                }
                img.uri.startsWith("data:") -> {
                    val commaIdx = img.uri.indexOf(',')
                    if (commaIdx >= 0) Base64.decode(img.uri, commaIdx + 1) else ByteArray(0)
                }
                else -> ByteArray(0)
            }
            if (idx in basisuImageIndices && bytes.isNotEmpty()) {
                if (ktx2Decoder == null) {
                    if (!ktx2SkipLogged) {
                        ktx2SkipLogged = true
                        Logger.log(
                            Logger.WARN,
                            "GltfReader.parse: texture uses KHR_texture_basisu but no " +
                                "Ogc3dDecoderRegistry.ktx2Decoder is registered; the texture " +
                                "will be omitted (affected primitive renders untextured)",
                        )
                    }
                    // No decoder — emit an empty GltfImage so material/texture indices stay
                    // stable. MeshContentGpu treats empty `bytes` as "no texture".
                    GltfImage(bytes = ByteArray(0), mimeType = img.mimeType)
                } else {
                    val decoded = runCatching { ktx2Decoder.decode(bytes) }.getOrElse { t ->
                        Logger.log(
                            Logger.WARN,
                            "GltfReader.parse: KTX2 decode failed for image#$idx; emitting " +
                                "empty image. ${t.message}",
                        )
                        null
                    }
                    if (decoded != null) {
                        GltfImage(
                            bytes = ByteArray(0),
                            mimeType = "image/rgba8",
                            decodedRgba = decoded.rgba,
                            decodedWidth = decoded.width,
                            decodedHeight = decoded.height,
                        )
                    } else {
                        GltfImage(bytes = ByteArray(0), mimeType = img.mimeType)
                    }
                }
            } else {
                GltfImage(bytes = bytes, mimeType = img.mimeType)
            }
        }

        // Decode materials.
        val materials: List<GltfMaterial> = doc.materials.map { mat ->
            val pbr = mat.pbrMetallicRoughness
            val baseColorTextureIdx = pbr.baseColorTexture.index
            val baseColorImageIdx = if (baseColorTextureIdx >= 0) {
                val tex = doc.textures.getOrNull(baseColorTextureIdx)
                // KHR_texture_basisu: when the extension is present, its source supersedes
                // the texture's regular `source` (which may be -1 when no PNG/JPG fallback
                // is supplied, or non-negative for assets that ship both for compatibility).
                tex?.extensions?.basisu?.source?.takeIf { it >= 0 }
                    ?: tex?.source ?: -1
            } else -1
            val baseColor = pbr.baseColorFactor.let { f ->
                FloatArray(4) { i -> if (i < f.size) f[i] else 1f }
            }
            GltfMaterial(
                baseColorFactor = baseColor,
                baseColorTextureImageIndex = baseColorImageIdx,
                baseColorTextureTexCoordSet = pbr.baseColorTexture.texCoord,
                alphaBlend = mat.alphaMode.equals("BLEND", ignoreCase = true),
                alphaMask = mat.alphaMode.equals("MASK", ignoreCase = true),
                alphaCutoff = mat.alphaCutoff,
                doubleSided = mat.doubleSided,
            )
        }

        // Decode mesh primitives' attribute + index buffers into plain arrays. Each primitive
        // is either:
        //  (a) a normal primitive whose attributes/indices come from glTF accessors, OR
        //  (b) a KHR_draco_mesh_compression primitive whose attributes/indices are decoded
        //      from a single compressed bufferView by the registered DracoDecoder. Falls back
        //      to (a) when the extension is absent OR the decoder is null AND the primitive
        //      keeps the regular accessors as a Draco fallback (typical for glTF assets that
        //      ship both for compatibility; Cesium tilesets usually omit the fallback).
        val dracoDecoder = Ogc3dDecoderRegistry.dracoDecoder
        var dracoSkipLogged = false
        val meshes: List<GltfMesh> = doc.meshes.map { mesh ->
            val prims = mesh.primitives.mapNotNull { prim ->
                val draco = prim.extensions?.dracoMeshCompression
                if (draco != null) {
                    if (dracoDecoder == null) {
                        if (!dracoSkipLogged) {
                            dracoSkipLogged = true
                            Logger.log(
                                Logger.WARN,
                                "GltfReader.parse: primitive uses KHR_draco_mesh_compression but " +
                                    "no Ogc3dDecoderRegistry.dracoDecoder is registered; the primitive will be skipped"
                            )
                        }
                        // Try the regular accessors as a Draco fallback. Per spec the primitive
                        // may publish both; if it doesn't, position will be missing and we'll
                        // skip below.
                        if (prim.attributes.position < 0) return@mapNotNull null
                    } else {
                        val decoded = runCatching {
                            decodeDracoPrimitive(dracoDecoder, prim, draco, doc, buffers)
                        }.getOrElse { t ->
                            Logger.log(
                                Logger.WARN,
                                "GltfReader.parse: Draco decode failed for one primitive; skipping. ${t.message}"
                            )
                            return@mapNotNull null
                        }
                        return@mapNotNull GltfPrimitive(
                            positions = decoded.positions,
                            normals = decoded.normals,
                            texCoords = decoded.texCoords,
                            colors = decoded.colors,
                            batchIds = decoded.batchIds,
                            indicesShort = decoded.indicesShort,
                            indicesInt = decoded.indicesInt,
                            materialIndex = prim.material,
                            mode = prim.mode,
                        )
                    }
                }

                val posAccessor = doc.accessors.getOrNull(prim.attributes.position) ?: return@mapNotNull null
                val positions = readFloatVec3(doc, buffers, posAccessor) ?: return@mapNotNull null
                val normals = prim.attributes.normal.takeIf { it >= 0 }?.let { idx ->
                    doc.accessors.getOrNull(idx)?.let { readFloatVec3(doc, buffers, it) }
                }
                val texCoords = prim.attributes.texCoord0.takeIf { it >= 0 }?.let { idx ->
                    doc.accessors.getOrNull(idx)?.let { readFloatVec2(doc, buffers, it) }
                }
                val colors = prim.attributes.color0.takeIf { it >= 0 }?.let { idx ->
                    doc.accessors.getOrNull(idx)?.let { readColor(doc, buffers, it) }
                }
                val batchIds = prim.attributes.batchId.takeIf { it >= 0 }?.let { idx ->
                    doc.accessors.getOrNull(idx)?.let { readScalarShort(doc, buffers, it) }
                }
                val (idxShort, idxInt) = if (prim.indices >= 0) {
                    doc.accessors.getOrNull(prim.indices)?.let { readIndices(doc, buffers, it) }
                        ?: (null to null)
                } else (null to null)
                GltfPrimitive(
                    positions = positions,
                    normals = normals,
                    texCoords = texCoords,
                    colors = colors,
                    batchIds = batchIds,
                    indicesShort = idxShort,
                    indicesInt = idxInt,
                    materialIndex = prim.material,
                    mode = prim.mode,
                )
            }
            GltfMesh(prims)
        }

        // Walk the default scene's node tree, composing transforms, collecting per-primitive
        // instances. Most b3dms have a single node per mesh so this is cheap; recursive
        // scenes still resolve to a flat instance list the renderer iterates without
        // re-walking.
        val sceneIdx = if (doc.scene >= 0) doc.scene else 0
        val rootScene = doc.scenes.getOrNull(sceneIdx)
        val instances = mutableListOf<GltfPrimitiveInstance>()
        rootScene?.nodes?.forEach { nodeIdx ->
            traverseNode(doc, nodeIdx, parentMatrix = null, meshes = meshes, instances = instances)
        }

        return GltfModel(
            meshes = meshes,
            materials = materials,
            images = images,
            primitives = instances,
        )
    }

    private fun traverseNode(
        doc: G2Doc,
        nodeIdx: Int,
        parentMatrix: Matrix4?,
        meshes: List<GltfMesh>,
        instances: MutableList<GltfPrimitiveInstance>,
    ) {
        val node = doc.nodes.getOrNull(nodeIdx) ?: return
        val localMatrix = nodeMatrix(node)
        val worldMatrix = Matrix4()
        if (parentMatrix != null) worldMatrix.setToMultiply(parentMatrix, localMatrix) else worldMatrix.copy(localMatrix)
        if (node.mesh >= 0) {
            val mesh = meshes.getOrNull(node.mesh)
            if (mesh != null) {
                for (primIdx in mesh.primitives.indices) {
                    instances.add(GltfPrimitiveInstance(node.mesh, primIdx, Matrix4().copy(worldMatrix)))
                }
            }
        }
        for (childIdx in node.children) traverseNode(doc, childIdx, worldMatrix, meshes, instances)
    }

    private fun nodeMatrix(node: G2DocNode): Matrix4 {
        val m = Matrix4()
        if (node.matrix.size >= 16) {
            // glTF stores matrices column-major; copy into Matrix4's row-major backing.
            for (i in 0..15) m.m[i % 4 * 4 + i / 4] = node.matrix[i]
        } else {
            val sx = if (node.scale.size >= 3) node.scale[0] else 1.0
            val sy = if (node.scale.size >= 3) node.scale[1] else 1.0
            val sz = if (node.scale.size >= 3) node.scale[2] else 1.0
            val tx = if (node.translation.size >= 3) node.translation[0] else 0.0
            val ty = if (node.translation.size >= 3) node.translation[1] else 0.0
            val tz = if (node.translation.size >= 3) node.translation[2] else 0.0
            val qx = if (node.rotation.size >= 4) node.rotation[0] else 0.0
            val qy = if (node.rotation.size >= 4) node.rotation[1] else 0.0
            val qz = if (node.rotation.size >= 4) node.rotation[2] else 0.0
            val qw = if (node.rotation.size >= 4) node.rotation[3] else 1.0
            // R * S with scaled rotation columns, then T in column 3. Same as GltfLoader.
            m.m[0] = (1 - 2 * (qy * qy + qz * qz)) * sx
            m.m[4] = (2 * (qx * qy + qz * qw)) * sx
            m.m[8] = (2 * (qx * qz - qy * qw)) * sx
            m.m[12] = 0.0
            m.m[1] = (2 * (qx * qy - qz * qw)) * sy
            m.m[5] = (1 - 2 * (qx * qx + qz * qz)) * sy
            m.m[9] = (2 * (qy * qz + qx * qw)) * sy
            m.m[13] = 0.0
            m.m[2] = (2 * (qx * qz + qy * qw)) * sz
            m.m[6] = (2 * (qy * qz - qx * qw)) * sz
            m.m[10] = (1 - 2 * (qx * qx + qy * qy)) * sz
            m.m[14] = 0.0
            m.m[3] = tx; m.m[7] = ty; m.m[11] = tz; m.m[15] = 1.0
        }
        return m
    }

    /**
     * Slice the Draco-compressed bufferView out of [buffers] and hand it to [decoder]
     * along with the attribute-id map. Returns the decoded mesh; caller adapts into a
     * [GltfPrimitive].
     */
    private fun decodeDracoPrimitive(
        decoder: DracoDecoder,
        prim: G2DocPrimitive,
        draco: G2DocDracoMeshCompression,
        doc: G2Doc,
        buffers: List<ByteArray>,
    ): DracoMesh {
        val bv = doc.bufferViews.getOrNull(draco.bufferView)
            ?: error("KHR_draco_mesh_compression bufferView ${draco.bufferView} out of range")
        val buf = buffers.getOrNull(bv.buffer)
            ?: error("KHR_draco_mesh_compression bufferView buffer ${bv.buffer} out of range")
        val compressed = buf.sliceArray(bv.byteOffset until bv.byteOffset + bv.byteLength)
        return decoder.decode(compressed, draco.attributes)
    }

    private fun readFloatVec3(doc: G2Doc, buffers: List<ByteArray>, accessor: G2DocAccessor): FloatArray? =
        readFloatComponents(doc, buffers, accessor, components = 3)

    private fun readFloatVec2(doc: G2Doc, buffers: List<ByteArray>, accessor: G2DocAccessor): FloatArray? =
        readFloatComponents(doc, buffers, accessor, components = 2)

    /** Decode a vec2/vec3 accessor honoring `KHR_mesh_quantization`. Google Photorealistic
     *  3D Tiles' coarsest LODs ship POSITION as normalized SHORT; assuming float32 walks the
     *  buffer at the wrong stride and reinterprets the packed integers as garbage IEEE bits,
     *  scattering vertices to random world positions. Per the glTF + KHR_mesh_quantization
     *  spec: signed normalized = max(v / MAX_POSITIVE, -1), unsigned normalized = v / MAX,
     *  unnormalized = raw integer cast to float. */
    private fun readFloatComponents(
        doc: G2Doc, buffers: List<ByteArray>, accessor: G2DocAccessor, components: Int,
    ): FloatArray? {
        val bv = doc.bufferViews.getOrNull(accessor.bufferView) ?: return null
        val buf = buffers.getOrNull(bv.buffer) ?: return null
        val baseOffset = bv.byteOffset + accessor.byteOffset
        return when (accessor.componentType) {
            5126 -> readFloatsLE(buf, baseOffset, accessor.count, components, bv.byteStride)
            5120, 5121, 5122, 5123 -> readIntsAsFloats(
                buf, baseOffset, accessor.count, components, bv.byteStride,
                accessor.componentType, accessor.normalized,
            )
            else -> null
        }
    }

    /** Bulk little-endian float decode. Inlined per-byte unpack avoids the per-call
     *  [BinaryDataView.getFloat32] overhead that dominated the 3D Tiles parse path (~10% of
     *  all JNI trampoline samples in the Adreno profile). Falls back to a strided loop when
     *  the bufferView interleaves attributes; otherwise reads one contiguous block. */
    private fun readFloatsLE(buf: ByteArray, baseOffset: Int, count: Int, components: Int, byteStride: Int): FloatArray {
        val result = FloatArray(count * components)
        val packedStride = components * 4
        if (byteStride <= 0 || byteStride == packedStride) {
            var src = baseOffset
            var dst = 0
            val end = count * components
            while (dst < end) {
                val bits = (buf[src].toInt() and 0xFF) or
                    ((buf[src + 1].toInt() and 0xFF) shl 8) or
                    ((buf[src + 2].toInt() and 0xFF) shl 16) or
                    ((buf[src + 3].toInt() and 0xFF) shl 24)
                result[dst] = Float.fromBits(bits)
                src += 4
                dst++
            }
        } else {
            for (i in 0 until count) {
                val elementStart = baseOffset + i * byteStride
                for (c in 0 until components) {
                    val o = elementStart + c * 4
                    val bits = (buf[o].toInt() and 0xFF) or
                        ((buf[o + 1].toInt() and 0xFF) shl 8) or
                        ((buf[o + 2].toInt() and 0xFF) shl 16) or
                        ((buf[o + 3].toInt() and 0xFF) shl 24)
                    result[i * components + c] = Float.fromBits(bits)
                }
            }
        }
        return result
    }

    private fun readIntsAsFloats(
        buf: ByteArray, baseOffset: Int, count: Int, components: Int, byteStride: Int,
        componentType: Int, normalized: Boolean,
    ): FloatArray {
        val result = FloatArray(count * components)
        val componentSize = when (componentType) { 5120, 5121 -> 1; 5122, 5123 -> 2; else -> 1 }
        val packedStride = components * componentSize
        val stride = if (byteStride > 0) byteStride else packedStride
        for (i in 0 until count) {
            val elementStart = baseOffset + i * stride
            for (c in 0 until components) {
                val o = elementStart + c * componentSize
                val raw = when (componentType) {
                    5120 -> buf[o].toInt() // BYTE (already sign-extended)
                    5121 -> buf[o].toInt() and 0xFF // UNSIGNED_BYTE
                    5122 -> ((buf[o].toInt() and 0xFF) or (buf[o + 1].toInt() shl 8)).toShort().toInt() // SHORT
                    5123 -> (buf[o].toInt() and 0xFF) or ((buf[o + 1].toInt() and 0xFF) shl 8) // UNSIGNED_SHORT
                    else -> 0
                }
                val v = if (normalized) when (componentType) {
                    5120 -> kotlin.math.max(raw / 127f, -1f)
                    5121 -> raw / 255f
                    5122 -> kotlin.math.max(raw / 32767f, -1f)
                    5123 -> raw / 65535f
                    else -> raw.toFloat()
                } else raw.toFloat()
                result[i * components + c] = v
            }
        }
        return result
    }

    private fun readColor(doc: G2Doc, buffers: List<ByteArray>, accessor: G2DocAccessor): FloatArray? {
        // COLOR_0 may be vec3 or vec4, float32 or uint8/uint16 normalized. Convert everything
        // to float32 vec4 for uniform fragment-shader handling.
        val bv = doc.bufferViews.getOrNull(accessor.bufferView) ?: return null
        val buf = buffers.getOrNull(bv.buffer) ?: return null
        val components = when (accessor.type) { "VEC3" -> 3; "VEC4" -> 4; else -> return null }
        val view = BinaryDataView(buf)
        val componentSize = when (accessor.componentType) {
            5126 -> 4 // FLOAT
            5121 -> 1 // UNSIGNED_BYTE
            5123 -> 2 // UNSIGNED_SHORT
            else -> return null
        }
        val stride = if (bv.byteStride > 0) bv.byteStride else components * componentSize
        val out = FloatArray(accessor.count * 4)
        for (i in 0 until accessor.count) {
            val elementStart = bv.byteOffset + accessor.byteOffset + i * stride
            for (c in 0 until 4) {
                out[i * 4 + c] = if (c < components) when (accessor.componentType) {
                    5126 -> view.getFloat32(elementStart + c * 4, littleEndian = true)
                    5121 -> view.getUint8(elementStart + c) / 255f
                    5123 -> view.getUint16(elementStart + c * 2, littleEndian = true) / 65535f
                    else -> 1f
                } else 1f
            }
        }
        return out
    }

    /**
     * Read a SCALAR accessor (b3dm `_BATCHID`) as a packed [ShortArray] — bit-identical
     * uint16 storage that the GPU re-interprets back to an unsigned value via
     * `vertexAttribPointer(GL_UNSIGNED_SHORT, normalized = false)`. The spec permits
     * UNSIGNED_BYTE / UNSIGNED_SHORT / FLOAT component types; the first two widen / copy
     * directly, FLOAT is truncated to integer (batch IDs are integer-semantic regardless
     * of how they were encoded).
     */
    private fun readScalarShort(doc: G2Doc, buffers: List<ByteArray>, accessor: G2DocAccessor): ShortArray? {
        val bv = doc.bufferViews.getOrNull(accessor.bufferView) ?: return null
        val buf = buffers.getOrNull(bv.buffer) ?: return null
        val base = bv.byteOffset + accessor.byteOffset
        val view = BinaryDataView(buf)
        return when (accessor.componentType) {
            5121 -> ShortArray(accessor.count) { view.getUint8(base + it).toShort() } // UNSIGNED_BYTE
            5123 -> ShortArray(accessor.count) { view.getUint16(base + it * 2, littleEndian = true).toShort() } // UNSIGNED_SHORT
            5126 -> ShortArray(accessor.count) { view.getFloat32(base + it * 4, littleEndian = true).toInt().toShort() } // FLOAT
            else -> null
        }
    }

    private fun readIndices(doc: G2Doc, buffers: List<ByteArray>, accessor: G2DocAccessor): Pair<ShortArray?, IntArray?> {
        val bv = doc.bufferViews.getOrNull(accessor.bufferView) ?: return null to null
        val buf = buffers.getOrNull(bv.buffer) ?: return null to null
        val base = bv.byteOffset + accessor.byteOffset
        val view = BinaryDataView(buf)
        return when (accessor.componentType) {
            5121 -> ShortArray(accessor.count) { view.getUint8(base + it).toShort() } to null
            5123 -> ShortArray(accessor.count) { view.getUint16(base + it * 2, littleEndian = true).toShort() } to null
            5125 -> null to IntArray(accessor.count) {
                view.getInt32(base + it * 4, littleEndian = true)
            }
            else -> null to null
        }
    }
}

// --- wire-format model (private) --------------------------------------------------------

@Serializable
internal data class G2Doc(
    val scene: Int = -1,
    val scenes: List<G2DocScene> = emptyList(),
    val nodes: List<G2DocNode> = emptyList(),
    val meshes: List<G2DocMesh> = emptyList(),
    val accessors: List<G2DocAccessor> = emptyList(),
    val bufferViews: List<G2G2DocBufferView> = emptyList(),
    val buffers: List<G2DocBuffer> = emptyList(),
    val materials: List<G2DocMaterial> = emptyList(),
    val textures: List<G2DocTexture> = emptyList(),
    val images: List<G2DocImage> = emptyList(),
    val samplers: List<G2DocSampler> = emptyList(),
)

@Serializable
internal data class G2DocScene(val nodes: List<Int> = emptyList())

@Serializable
internal data class G2DocNode(
    val mesh: Int = -1,
    val children: List<Int> = emptyList(),
    val matrix: List<Double> = emptyList(),
    val translation: List<Double> = emptyList(),
    val rotation: List<Double> = emptyList(),
    val scale: List<Double> = emptyList(),
)

@Serializable
internal data class G2DocMesh(val primitives: List<G2DocPrimitive> = emptyList())

@Serializable
internal data class G2DocPrimitive(
    val attributes: G2DocAttributes = G2DocAttributes(),
    val indices: Int = -1,
    val material: Int = -1,
    val mode: Int = 4,
    val extensions: G2DocPrimitiveExtensions? = null,
)

@Serializable
internal data class G2DocPrimitiveExtensions(
    @SerialName("KHR_draco_mesh_compression")
    val dracoMeshCompression: G2DocDracoMeshCompression? = null,
)

@Serializable
internal data class G2DocDracoMeshCompression(
    /** Index of the bufferView containing the Draco-compressed stream. */
    val bufferView: Int = -1,
    /** Maps glTF attribute semantic names (POSITION / NORMAL / TEXCOORD_0 / COLOR_0) to
     *  Draco-side attribute IDs the decoder uses to identify each attribute in the
     *  compressed stream. */
    val attributes: Map<String, Int> = emptyMap(),
)

@Serializable
internal data class G2DocAttributes(
    @SerialName("POSITION") val position: Int = -1,
    @SerialName("NORMAL") val normal: Int = -1,
    @SerialName("TEXCOORD_0") val texCoord0: Int = -1,
    @SerialName("COLOR_0") val color0: Int = -1,
    /** b3dm-specific per-vertex feature id; same accessor type as COLOR (SCALAR / float
     *  or UNSIGNED_SHORT). Consumed by per-feature picking — see batchIds on
     *  [GltfPrimitive]. */
    @SerialName("_BATCHID") val batchId: Int = -1,
)

@Serializable
internal data class G2DocAccessor(
    val bufferView: Int = -1,
    val byteOffset: Int = 0,
    val componentType: Int = 5126,
    val count: Int = 0,
    val type: String = "SCALAR",
    val normalized: Boolean = false,
)

@Serializable
internal data class G2G2DocBufferView(
    val buffer: Int = 0,
    val byteOffset: Int = 0,
    val byteLength: Int = 0,
    val byteStride: Int = 0,
)

@Serializable
internal data class G2DocBuffer(val uri: String = "", val byteLength: Int = 0)

@Serializable
internal data class G2DocMaterial(
    val pbrMetallicRoughness: G2DocPbr = G2DocPbr(),
    val alphaMode: String = "OPAQUE",
    val alphaCutoff: Float = 0.5f,
    val doubleSided: Boolean = false,
)

@Serializable
internal data class G2DocPbr(
    val baseColorFactor: List<Float> = listOf(1f, 1f, 1f, 1f),
    val baseColorTexture: G2G2DocTextureInfo = G2G2DocTextureInfo(),
    val metallicFactor: Float = 1f,
    val roughnessFactor: Float = 1f,
)

@Serializable
internal data class G2G2DocTextureInfo(
    val index: Int = -1,
    val texCoord: Int = 0,
)

@Serializable
internal data class G2DocTexture(
    val source: Int = -1,
    val sampler: Int = -1,
    val extensions: G2DocTextureExtensions? = null,
)

@Serializable
internal data class G2DocTextureExtensions(
    /** `KHR_texture_basisu`: the texture sources a KTX2 / Basis-Universal image. The
     *  index points into the glTF `images` array; that image's bytes are a KTX2 container
     *  and need transcoding via the registered [earth.worldwind.formats.gltf.Ktx2Decoder]. */
    @SerialName("KHR_texture_basisu") val basisu: G2DocBasisuTexture? = null,
)

@Serializable
internal data class G2DocBasisuTexture(
    val source: Int = -1,
)

@Serializable
internal data class G2DocImage(
    val uri: String = "",
    val bufferView: Int = -1,
    val mimeType: String = "",
)

@Serializable
internal data class G2DocSampler(
    val magFilter: Int = 9729,
    val minFilter: Int = 9729,
    val wrapS: Int = 10497,
    val wrapT: Int = 10497,
)
