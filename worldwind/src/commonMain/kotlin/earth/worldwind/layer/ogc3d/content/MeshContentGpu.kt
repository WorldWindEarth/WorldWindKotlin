package earth.worldwind.layer.ogc3d.content

import earth.worldwind.formats.gltf.GltfModel
import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Vec3
import earth.worldwind.render.RenderContext
import earth.worldwind.render.Texture
import earth.worldwind.render.buffer.BufferObject
import earth.worldwind.util.NumericArray
import earth.worldwind.util.kgl.GL_ARRAY_BUFFER
import earth.worldwind.util.kgl.GL_ELEMENT_ARRAY_BUFFER
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
        val interleaved = FloatArray(vertexCount * components)

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
            if (imgIdx < 0 || texCoords == null) null
            else {
                val img = model.images.getOrNull(imgIdx)
                if (img == null || img.bytes.isEmpty()) null
                else runCatching { decodeTileTexture(img.bytes) }.getOrNull()
            }
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
        primitives.add(
            PrimitivePrep(
                worldMatrix = Matrix4().copy(instance.worldMatrix),
                interleavedVertices = interleaved,
                indicesShort = primitive.indicesShort,
                indicesInt = primitive.indicesInt,
                batchIds = batchIds,
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

/** Render-thread upload: install prep's buffers + texture into the RR cache. Idempotent.
 *  Tracks every key it puts into the cache; if a later put / NumericArray allocation throws,
 *  rolls them back so partial state doesn't sit in the cache untethered from any MeshContent. */
internal fun uploadMeshContent(prep: MeshContentPrep, target: MeshContent, rc: RenderContext) {
    if (target.submeshes != null) return

    val submeshes = ArrayList<MeshSubmesh>(prep.primitives.size)
    var totalBytes = 0
    val rollbackKeys = ArrayList<Any>(prep.primitives.size * 3)

    try {
        for (prim in prep.primitives) {
            val vboKey = "${prep.contentUri}/${prim.instanceIdx}/vbo"
            val vertexBytes = prim.interleavedVertices.size * 4
            rc.getBufferObject(vboKey) { BufferObject(GL_ARRAY_BUFFER, vertexBytes) }
            rollbackKeys.add(vboKey)
            rc.offerGLBufferUpload(vboKey, 1) { NumericArray.Floats(prim.interleavedVertices) }
            totalBytes += vertexBytes

            val eboKey: Any? = when {
                prim.indicesShort != null -> {
                    val key = "${prep.contentUri}/${prim.instanceIdx}/ebo"
                    val bytes = prim.indicesShort.size * 2
                    rc.getBufferObject(key) { BufferObject(GL_ELEMENT_ARRAY_BUFFER, bytes) }
                    rollbackKeys.add(key)
                    rc.offerGLBufferUpload(key, 1) { NumericArray.Shorts(prim.indicesShort) }
                    totalBytes += bytes
                    key
                }
                prim.indicesInt != null -> {
                    val key = "${prep.contentUri}/${prim.instanceIdx}/ebo"
                    val bytes = prim.indicesInt.size * 4
                    rc.getBufferObject(key) { BufferObject(GL_ELEMENT_ARRAY_BUFFER, bytes) }
                    rollbackKeys.add(key)
                    rc.offerGLBufferUpload(key, 1) { NumericArray.Ints(prim.indicesInt) }
                    totalBytes += bytes
                    key
                }
                else -> null
            }

            val textureKey: Any? = prim.baseColorTexture?.let { texture ->
                val key = "${prep.contentUri}/${prim.instanceIdx}/tex"
                rc.renderResourceCache.put(key, texture, texture.byteCount)
                rollbackKeys.add(key)
                totalBytes += texture.byteCount
                key
            }

            val batchIdKey: Any? = prim.batchIds?.let { batchIds ->
                val key = "${prep.contentUri}/${prim.instanceIdx}/batch"
                val bytes = batchIds.size * 2
                rc.getBufferObject(key) { BufferObject(GL_ARRAY_BUFFER, bytes) }
                rollbackKeys.add(key)
                rc.offerGLBufferUpload(key, 1) { NumericArray.Shorts(batchIds) }
                totalBytes += bytes
                key
            }

            submeshes.add(
                MeshSubmesh(
                    worldMatrix = prim.worldMatrix,
                    vboKey = vboKey,
                    eboKey = eboKey,
                    baseColorTextureKey = textureKey,
                    batchIdKey = batchIdKey,
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
                )
            )
        }

        target.gpuByteCount = totalBytes
        target.submeshes = submeshes
    } catch (t: Throwable) {
        for (key in rollbackKeys) rc.renderResourceCache.remove(key)
        throw t
    }
}

/** Parse → render-thread handoff. Owns prep arrays + decoded textures until upload. */
internal class MeshContentPrep(
    val contentUri: String,
    val primitives: List<PrimitivePrep>,
)

internal class PrimitivePrep(
    val worldMatrix: Matrix4,
    val interleavedVertices: FloatArray,
    val indicesShort: ShortArray?,
    val indicesInt: IntArray?,
    val batchIds: ShortArray?,
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
