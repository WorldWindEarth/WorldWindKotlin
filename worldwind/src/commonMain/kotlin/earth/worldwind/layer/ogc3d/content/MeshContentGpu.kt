package earth.worldwind.layer.ogc3d.content

import earth.worldwind.formats.gltf.GltfModel
import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Vec3
import earth.worldwind.render.RenderContext
import earth.worldwind.render.Texture
import earth.worldwind.render.buffer.BufferObject
import earth.worldwind.render.image.RgbaTexture
import earth.worldwind.util.ByteArrayPool
import earth.worldwind.util.FloatArrayPool
import earth.worldwind.util.NumericArray
import earth.worldwind.util.kgl.GL_ARRAY_BUFFER
import earth.worldwind.util.kgl.GL_ELEMENT_ARRAY_BUFFER
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.sqrt

/** Off-thread mesh prep: interleave attributes, copy indices, decode textures, compute the
 *  local bounding sphere. Output is consumed by [uploadMeshContent] on the render thread. */
internal suspend fun prepareMeshPrep(
    model: GltfModel,
    contentUri: String,
    target: MeshContent,
): MeshContentPrep {
    val primitives = ArrayList<PrimitivePrep>(model.primitives.size)
    val scratchCorner = Vec3()
    var maxLocalRadiusSq = 0.0

    for ((instanceIdx, instance) in model.primitives.withIndex()) {
        val mesh = model.meshes.getOrNull(instance.meshIndex) ?: continue
        val primitive = mesh.primitives.getOrNull(instance.primitiveIndex) ?: continue
        val positions = primitive.positions
        if (positions.size < 3) continue

        val vertexCount = positions.size / 3
        val normals = primitive.normals?.takeIf { it.size == positions.size }
        val texCoords = primitive.texCoords?.takeIf { it.size == vertexCount * 2 }
        // Per-vertex RGBA (or RGB padded to RGBA). Common in untextured photogrammetry —
        // the colour map is encoded as `COLOR_0` instead of a texture image.
        val colors = primitive.colors?.takeIf { it.size == vertexCount * 4 || it.size == vertexCount * 3 }
        val colorComponents = if (colors != null) if (colors.size == vertexCount * 4) 4 else 3 else 0
        val components = 3 + (if (normals != null) 3 else 0) +
            (if (texCoords != null) 2 else 0) + colorComponents
        val vertexStride = components * 4
        // Pool-borrowed; released right after pack so the float scratch never enters LOS.
        val interleavedFloatCount = vertexCount * components
        val interleaved = FloatArrayPool.acquire(interleavedFloatCount)

        // Fused interleave + bounding-sphere walk; samples every Nth vertex for the radius.
        val sphereStride = ((vertexCount + SPHERE_SAMPLE_TARGET - 1) / SPHERE_SAMPLE_TARGET).coerceAtLeast(1)
        var nextSphereSample = 0
        for (v in 0 until vertexCount) {
            val dstBase = v * components
            val px = positions[v * 3 + 0]
            val py = positions[v * 3 + 1]
            val pz = positions[v * 3 + 2]
            interleaved[dstBase + 0] = px
            interleaved[dstBase + 1] = py
            interleaved[dstBase + 2] = pz
            var off = 3
            if (normals != null) {
                interleaved[dstBase + off + 0] = normals[v * 3 + 0]
                interleaved[dstBase + off + 1] = normals[v * 3 + 1]
                interleaved[dstBase + off + 2] = normals[v * 3 + 2]
                off += 3
            }
            if (texCoords != null) {
                interleaved[dstBase + off + 0] = texCoords[v * 2 + 0]
                interleaved[dstBase + off + 1] = texCoords[v * 2 + 1]
                off += 2
            }
            if (colors != null) {
                for (c in 0 until colorComponents) {
                    interleaved[dstBase + off + c] = colors[v * colorComponents + c]
                }
            }
            if (v == nextSphereSample) {
                scratchCorner.set(px.toDouble(), py.toDouble(), pz.toDouble())
                    .multiplyByMatrix(instance.worldMatrix)
                val r2 = scratchCorner.x * scratchCorner.x +
                    scratchCorner.y * scratchCorner.y +
                    scratchCorner.z * scratchCorner.z
                if (r2 > maxLocalRadiusSq) maxLocalRadiusSq = r2
                nextSphereSample = v + sphereStride
            }
        }

        val material = if (primitive.materialIndex >= 0) model.materials.getOrNull(primitive.materialIndex) else null
        val baseColor = material?.baseColorFactor ?: floatArrayOf(1f, 1f, 1f, 1f)
        // Eager texture decode off-thread; null falls back to baseColor-only rendering.
        val baseColorTexture: Texture? = run {
            val imgIdx = material?.baseColorTextureImageIndex ?: -1
            if (imgIdx < 0 || texCoords == null) return@run null
            val img = model.images.getOrNull(imgIdx) ?: return@run null
            when {
                // KTX2 / KHR_texture_basisu: GltfReader transcoded to raw RGBA and cleared bytes.
                img.decodedRgba != null ->
                    runCatching { RgbaTexture(img.decodedRgba, img.decodedWidth, img.decodedHeight) }.getOrNull()
                // JPEG / PNG: decode the encoded bytes via the platform image loader. Let coroutine
                // cancellation propagate — `runCatching`/getOrNull would swallow it, turning a
                // cancelled fetch into a textureless (permanently white) primitive that still installs.
                img.bytes.isNotEmpty() -> try {
                    decodeTileTexture(img.bytes)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    null
                }
                else -> null
            }?.also { it.generateMipmaps = false }
        }

        // Per-feature picking requires uniform batch id per primitive — GLES2 has no `flat`
        // qualifier so a varying with mixed per-vertex values gets interpolated to garbage.
        // Drop batch ids on mixed-id primitives → tile-level pick fallback for that submesh.
        val batchIds = primitive.batchIds?.takeIf { it.size == vertexCount }?.let { ids ->
            val first = ids[0]
            var uniform = true
            for (i in 1 until ids.size) if (ids[i] != first) { uniform = false; break }
            if (uniform) ids else null
        }
        // Pack vertices + indices + batchIds into a single contiguous ByteArray in native
        // (ARM little-endian) layout. One BufferObject per primitive instead of three →
        // one driver `mmap64` instead of three at upload time. Texture stays separate.
        val vertexBytes = interleavedFloatCount * 4
        val indexBytes = primitive.indicesShort?.let { it.size * 2 }
            ?: primitive.indicesInt?.let { it.size * 4 } ?: 0
        val batchIdBytes = batchIds?.let { it.size * 2 } ?: 0
        // Borrow from ByteArrayPool — released back in [uploadMeshContent] after GL upload.
        // The array may be larger than [combinedByteCount]; only the used prefix is uploaded.
        val combinedByteCount = vertexBytes + indexBytes + batchIdBytes
        val combinedBytes = ByteArrayPool.acquire(combinedByteCount)
        packFloatsLE(combinedBytes, 0, interleaved, interleavedFloatCount)
        FloatArrayPool.release(interleaved)
        primitive.indicesShort?.let { packShortsLE(combinedBytes, vertexBytes, it) }
        primitive.indicesInt?.let { packIntsLE(combinedBytes, vertexBytes, it) }
        batchIds?.let { packShortsLE(combinedBytes, vertexBytes + indexBytes, it) }
        val elementOffset = vertexBytes
        val batchIdOffset = if (batchIds != null) vertexBytes + indexBytes else -1

        primitives.add(
            PrimitivePrep(
                worldMatrix = Matrix4().copy(instance.worldMatrix),
                combinedBytes = combinedBytes,
                combinedByteCount = combinedByteCount,
                indexByteCount = indexBytes,
                elementOffset = elementOffset,
                batchIdOffset = batchIdOffset,
                vertexCount = vertexCount,
                elementCount = primitive.indicesShort?.size ?: primitive.indicesInt?.size ?: 0,
                vertexStride = vertexStride,
                positionOffset = 0,
                normalOffset = if (normals != null) 12 else -1,
                texCoordOffset = if (texCoords != null) 12 + (if (normals != null) 12 else 0) else -1,
                colorOffset = if (colors != null) 12 + (if (normals != null) 12 else 0) + (if (texCoords != null) 8 else 0) else -1,
                colorComponents = colorComponents,
                isInt32Indices = primitive.indicesInt != null,
                baseColor = baseColor,
                baseColorTexture = baseColorTexture,
                alphaBlend = material?.alphaBlend ?: false,
                alphaMask = material?.alphaMask ?: false,
                alphaCutoff = material?.alphaCutoff ?: 0.5f,
                doubleSided = material?.doubleSided ?: false,
                mode = primitive.mode,
                instanceIdx = instanceIdx,
            )
        )
    }

    target.localBoundingSphere.set(
        Vec3(0.0, 0.0, 0.0),
        if (maxLocalRadiusSq > 0.0) sqrt(maxLocalRadiusSq) else 0.0,
    )

    return MeshContentPrep(contentUri = contentUri, primitives = primitives)
}

/** Render-thread upload: install prep's combined buffer + texture into the RR cache.
 *  Idempotent. Tracks every key it puts into the cache; rolls them back on failure so
 *  partial state doesn't sit in the cache untethered from any MeshContent. */
internal fun uploadMeshContent(prep: MeshContentPrep, target: MeshContent, rc: RenderContext) {
    if (target.submeshes != null) return

    val submeshes = ArrayList<MeshSubmesh>(prep.primitives.size)
    var totalBytes = 0
    val rollbackKeys = ArrayList<Any>(prep.primitives.size * 2)

    // WebGL forbids binding one buffer to both targets, so indices need their own buffer there;
    // elsewhere the combined buffer doubles as the EBO. See Kgl.supportsSharedElementArrayBuffer.
    val splitElementBuffer = !rc.supportsSharedElementArrayBuffer

    try {
        for (prim in prep.primitives) {
            // One BufferObject per primitive: vertices + indices + batchIds. Bound to
            // GL_ARRAY_BUFFER for attribs and (shared path) GL_ELEMENT_ARRAY_BUFFER for indices.
            val bufferKey = "${prep.contentUri}/${prim.instanceIdx}/buf"
            val bufferBytes = prim.combinedByteCount
            rc.getBufferObject(bufferKey) { BufferObject(GL_ARRAY_BUFFER, bufferBytes) }
            rollbackKeys.add(bufferKey)
            val combined = prim.combinedBytes
            // Copy the index slice before scheduling the array upload — `combined` returns to the
            // pool when that upload's onUploaded fires. Split path + indexed only.
            val splitIndexBytes = if (splitElementBuffer && prim.indexByteCount > 0) {
                combined.copyOfRange(prim.elementOffset, prim.elementOffset + prim.indexByteCount)
            } else null
            // Release the pool array after the GL write; onUploaded fires even when skipped.
            rc.offerGLBufferUpload(
                bufferKey, 1,
                onUploaded = { ByteArrayPool.release(combined) },
            ) { NumericArray.Bytes(combined, bufferBytes) }
            totalBytes += bufferBytes

            // Split path: dedicated EBO holding just the indices, drawn at offset 0.
            val elementBufferKey: Any? = splitIndexBytes?.let { idx ->
                val key = "${prep.contentUri}/${prim.instanceIdx}/ebo"
                rc.getBufferObject(key) { BufferObject(GL_ELEMENT_ARRAY_BUFFER, idx.size) }
                rollbackKeys.add(key)
                rc.offerGLBufferUpload(key, 1) { NumericArray.Bytes(idx, idx.size) }
                totalBytes += idx.size
                key
            }

            val textureKey: Any? = prim.baseColorTexture?.let { texture ->
                val key = "${prep.contentUri}/${prim.instanceIdx}/tex"
                rc.renderResourceCache.put(key, texture, texture.byteCount)
                rollbackKeys.add(key)
                totalBytes += texture.byteCount
                key
            }

            submeshes.add(
                MeshSubmesh(
                    worldMatrix = prim.worldMatrix,
                    bufferKey = bufferKey,
                    elementBufferKey = elementBufferKey,
                    baseColorTextureKey = textureKey,
                    // Split: dedicated EBO, offset 0. Shared: into the combined buffer's index section.
                    elementOffset = if (elementBufferKey != null) 0 else prim.elementOffset,
                    batchIdOffset = prim.batchIdOffset,
                    vertexCount = prim.vertexCount,
                    elementCount = prim.elementCount,
                    vertexStride = prim.vertexStride,
                    positionOffset = prim.positionOffset,
                    normalOffset = prim.normalOffset,
                    texCoordOffset = prim.texCoordOffset,
                    colorOffset = prim.colorOffset,
                    colorComponents = prim.colorComponents,
                    isInt32Indices = prim.isInt32Indices,
                    baseColor = prim.baseColor,
                    alphaBlend = prim.alphaBlend,
                    alphaMask = prim.alphaMask,
                    alphaCutoff = prim.alphaCutoff,
                    doubleSided = prim.doubleSided,
                    mode = prim.mode,
                ).apply {
                    // Resolve refs once; per-frame enqueueMeshDrawable reads these direct.
                    buffer = rc.renderResourceCache[bufferKey] as? BufferObject
                    elementBuffer = elementBufferKey?.let { rc.renderResourceCache[it] as? BufferObject }
                    baseColorTexture = textureKey?.let { rc.renderResourceCache[it] as? Texture }
                }
            )
        }

        target.gpuByteCount = totalBytes
        target.submeshes = submeshes
    } catch (t: Throwable) {
        for (key in rollbackKeys) rc.renderResourceCache.remove(key)
        throw t
    }
}

/** Little-endian bulk pack helpers. ARM Android is little-endian so the resulting bytes
 *  match what `glVertexAttribPointer(..., GL_FLOAT, ...)` etc. expect to read from the
 *  bound GL buffer — no driver-side byte-swap. */
private fun packFloatsLE(dst: ByteArray, dstOffset: Int, src: FloatArray, srcLength: Int = src.size) {
    var o = dstOffset
    for (i in 0 until srcLength) {
        val bits = src[i].toRawBits()
        dst[o] = bits.toByte()
        dst[o + 1] = (bits ushr 8).toByte()
        dst[o + 2] = (bits ushr 16).toByte()
        dst[o + 3] = (bits ushr 24).toByte()
        o += 4
    }
}

private fun packShortsLE(dst: ByteArray, dstOffset: Int, src: ShortArray) {
    var o = dstOffset
    for (i in src.indices) {
        val v = src[i].toInt()
        dst[o] = v.toByte()
        dst[o + 1] = (v ushr 8).toByte()
        o += 2
    }
}

private fun packIntsLE(dst: ByteArray, dstOffset: Int, src: IntArray) {
    var o = dstOffset
    for (i in src.indices) {
        val v = src[i]
        dst[o] = v.toByte()
        dst[o + 1] = (v ushr 8).toByte()
        dst[o + 2] = (v ushr 16).toByte()
        dst[o + 3] = (v ushr 24).toByte()
        o += 4
    }
}

/** Parse → render-thread handoff. Owns prep arrays + decoded textures until upload. */
internal class MeshContentPrep(
    val contentUri: String,
    val primitives: List<PrimitivePrep>,
)

internal class PrimitivePrep(
    val worldMatrix: Matrix4,
    /** Single contiguous buffer: vertices (interleaved) | indices | batchIds. Native
     *  (little-endian) layout — uploaded as-is into one [BufferObject]. Pool-borrowed
     *  via [earth.worldwind.util.ByteArrayPool]; the array may be longer than
     *  [combinedByteCount] (only that prefix is uploaded), and the upload path releases
     *  it back to the pool after the GL write completes. */
    val combinedBytes: ByteArray,
    /** Actual payload length in [combinedBytes] (it may be longer due to pool oversize). */
    val combinedByteCount: Int,
    /** Length in bytes of the index section within [combinedBytes]; 0 when non-indexed. */
    val indexByteCount: Int,
    /** Byte offset of the index section. Used both as `glDrawElements` offset and as the
     *  end of the vertex region. */
    val elementOffset: Int,
    /** Byte offset of the batchId section, or -1 when absent. */
    val batchIdOffset: Int,
    val vertexCount: Int,
    val elementCount: Int,
    val vertexStride: Int,
    val positionOffset: Int,
    val normalOffset: Int,
    val texCoordOffset: Int,
    /** Byte offset of vertex `COLOR_0` within the interleaved vertex; -1 when absent. */
    val colorOffset: Int,
    /** 3 = RGB, 4 = RGBA. 0 when absent. */
    val colorComponents: Int,
    val isInt32Indices: Boolean,
    val baseColor: FloatArray,
    val baseColorTexture: Texture?,
    val alphaBlend: Boolean,
    val alphaMask: Boolean,
    val alphaCutoff: Float,
    val doubleSided: Boolean,
    val mode: Int,
    val instanceIdx: Int,
)

/** Max vertices sampled for the bounding sphere; stride-walked above. */
private const val SPHERE_SAMPLE_TARGET = 256
