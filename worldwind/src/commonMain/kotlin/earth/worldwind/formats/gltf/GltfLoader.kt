package earth.worldwind.formats.gltf

import dev.icerock.moko.resources.AssetResource
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Position
import earth.worldwind.render.Color
import earth.worldwind.render.RenderResourceCache
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.atan2
import kotlin.math.sqrt

@Serializable
private data class DocScene(val nodes: List<Int> = emptyList())

@Serializable
private data class DocNode(
    val mesh: Int = -1,
    val children: List<Int> = emptyList(),
    val matrix: List<Double> = emptyList(),
    val translation: List<Double> = emptyList(),
    val rotation: List<Double> = emptyList(),
    val scale: List<Double> = emptyList()
)

@Serializable
private data class DocMesh(val primitives: List<DocPrimitive> = emptyList())

@Serializable
private data class DocPrimitive(
    val attributes: DocAttributes = DocAttributes(),
    val indices: Int = -1,
    val material: Int = -1,
    val mode: Int = 4
)

@Serializable
private data class DocAttributes(
    @SerialName("POSITION") val position: Int = -1,
    @SerialName("NORMAL") val normal: Int = -1
)

@Serializable
private data class DocAccessor(
    val bufferView: Int = -1,
    val byteOffset: Int = 0,
    val componentType: Int = 5126,
    val count: Int = 0,
    val type: String = "SCALAR"
)

@Serializable
private data class DocBufferView(
    val buffer: Int = 0,
    val byteOffset: Int = 0,
    val byteLength: Int = 0,
    val byteStride: Int = 0
)

@Serializable
private data class DocBuffer(val uri: String = "", val byteLength: Int = 0)

@Serializable
private data class DocMaterial(
    val pbrMetallicRoughness: DocPBR = DocPBR()
)

@Serializable
private data class DocPBR(
    val baseColorFactor: List<Float> = listOf(1f, 1f, 1f, 1f)
)

@Serializable
private data class GltfDoc(
    val scene: Int = 0,
    val scenes: List<DocScene> = emptyList(),
    val nodes: List<DocNode> = emptyList(),
    val meshes: List<DocMesh> = emptyList(),
    val accessors: List<DocAccessor> = emptyList(),
    val bufferViews: List<DocBufferView> = emptyList(),
    val buffers: List<DocBuffer> = emptyList(),
    val materials: List<DocMaterial> = emptyList()
)

class GltfLoader(val position: Position) {
    private var asset: AssetResource? = null

    constructor(position: Position, asset: AssetResource) : this(position) {
        this.asset = asset
    }

    suspend fun parse(rrc: RenderResourceCache): GltfScene {
        val a = requireNotNull(asset) { "GltfLoader.parse(rrc) requires AssetResource constructor" }
        val data = suspendCancellableCoroutine { cont -> rrc.retrieveTextAsset(a) { cont.resume(it) } }
        return parse(data)
    }

    fun parse(data: String): GltfScene {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val doc = json.decodeFromString<GltfDoc>(data)
        val rawBuffers = doc.buffers.map { decodeBuffer(it) }
        val entities = mutableListOf<GltfEntity>()
        val rootScene = doc.scenes.getOrNull(doc.scene) ?: DocScene()
        for (nodeIdx in rootScene.nodes) traverseNode(doc, rawBuffers, nodeIdx, null, entities)
        return GltfScene(position, entities)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeBuffer(buf: DocBuffer): ByteArray {
        val uri = buf.uri
        if (uri.startsWith("data:")) {
            val commaIdx = uri.indexOf(',')
            if (commaIdx >= 0) return Base64.decode(uri, commaIdx + 1)
        }
        return ByteArray(0)
    }

    private fun traverseNode(
        doc: GltfDoc, rawBuffers: List<ByteArray>, nodeIdx: Int, parentMatrix: Matrix4?, entities: MutableList<GltfEntity>
    ) {
        val node = doc.nodes.getOrNull(nodeIdx) ?: return
        val localMatrix = buildNodeMatrix(node)
        val worldMatrix = Matrix4()
        if (parentMatrix != null) worldMatrix.setToMultiply(parentMatrix, localMatrix) else worldMatrix.copy(localMatrix)
        val normalMatrix = buildNormalMatrix(worldMatrix)

        if (node.mesh >= 0) {
            val mesh = doc.meshes.getOrNull(node.mesh)
            if (mesh != null) {
                for (prim in mesh.primitives) {
                    buildEntity(doc, rawBuffers, prim, worldMatrix, normalMatrix)?.let { entities.add(it) }
                }
            }
        }
        for (childIdx in node.children) traverseNode(doc, rawBuffers, childIdx, worldMatrix, entities)
    }

    private fun buildNodeMatrix(node: DocNode): Matrix4 {
        val m = Matrix4()
        if (node.matrix.size >= 16) {
            // GLTF column-major → Matrix4 row-major: transpose
            for (i in 0..15) m.m[i % 4 * 4 + i / 4] = node.matrix[i]
        } else {
            // TRS: local = T * R * S (column-vector convention)
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
            // Build R*S by scaling rotation columns, then set translation column
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

    private fun buildNormalMatrix(worldMat: Matrix4): Matrix4 {
        val rx = atan2(worldMat.m[6], worldMat.m[10])
        val cosY = sqrt(worldMat.m[6] * worldMat.m[6] + worldMat.m[10] * worldMat.m[10])
        val ry = atan2(-worldMat.m[2], cosY)
        val rz = atan2(worldMat.m[1], worldMat.m[0])
        val nm = Matrix4()
        nm.setToIdentity()
        nm.multiplyByRotation(-1.0, 0.0, 0.0, Angle.fromRadians(rx))
        nm.multiplyByRotation(0.0, -1.0, 0.0, Angle.fromRadians(ry))
        nm.multiplyByRotation(0.0, 0.0, -1.0, Angle.fromRadians(rz))
        return nm
    }

    private fun buildEntity(
        doc: GltfDoc, rawBuffers: List<ByteArray>, prim: DocPrimitive, worldMatrix: Matrix4, normalMatrix: Matrix4
    ): GltfEntity? {
        val posAccessor = doc.accessors.getOrNull(prim.attributes.position) ?: return null
        val positions = readFloatAccessor(doc, rawBuffers, posAccessor) ?: return null
        val normals = if (prim.attributes.normal >= 0)
            doc.accessors.getOrNull(prim.attributes.normal)?.let { readFloatAccessor(doc, rawBuffers, it) }
        else null

        val color = if (prim.material >= 0) {
            val bf = doc.materials.getOrNull(prim.material)?.pbrMetallicRoughness?.baseColorFactor
            if (bf != null && bf.size >= 4) Color(bf[0], bf[1], bf[2], bf[3]) else Color(1f, 1f, 1f, 1f)
        } else Color(1f, 1f, 1f, 1f)

        val (indicesShort, indicesInt) = if (prim.indices >= 0) {
            doc.accessors.getOrNull(prim.indices)?.let { readIndexAccessor(doc, rawBuffers, it) } ?: Pair(null, null)
        } else Pair(null, null)

        return GltfEntity(positions, normals, indicesShort, indicesInt, color, Matrix4().copy(worldMatrix), Matrix4().copy(normalMatrix))
    }

    private fun readFloatAccessor(doc: GltfDoc, rawBuffers: List<ByteArray>, accessor: DocAccessor): FloatArray? {
        val bv = doc.bufferViews.getOrNull(accessor.bufferView) ?: return null
        val buf = rawBuffers.getOrNull(bv.buffer) ?: return null
        val components = when (accessor.type) { "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; "MAT4" -> 16; else -> 1 }
        val stride = if (bv.byteStride > 0) bv.byteStride else components * 4
        val result = FloatArray(accessor.count * components)
        for (i in 0 until accessor.count) {
            val elementStart = bv.byteOffset + accessor.byteOffset + i * stride
            for (c in 0 until components) result[i * components + c] = buf.readFloat(elementStart + c * 4)
        }
        return result
    }

    private fun readIndexAccessor(doc: GltfDoc, rawBuffers: List<ByteArray>, accessor: DocAccessor): Pair<ShortArray?, IntArray?> {
        val bv = doc.bufferViews.getOrNull(accessor.bufferView) ?: return Pair(null, null)
        val buf = rawBuffers.getOrNull(bv.buffer) ?: return Pair(null, null)
        val base = bv.byteOffset + accessor.byteOffset
        return when (accessor.componentType) {
            5123 -> Pair(ShortArray(accessor.count) { buf.readUShort(base + it * 2).toShort() }, null)
            5125 -> Pair(null, IntArray(accessor.count) { buf.readUInt(base + it * 4) })
            else -> Pair(null, null)
        }
    }

    private fun ByteArray.readFloat(offset: Int): Float {
        val bits = (this[offset].toInt() and 0xFF) or
                ((this[offset + 1].toInt() and 0xFF) shl 8) or
                ((this[offset + 2].toInt() and 0xFF) shl 16) or
                ((this[offset + 3].toInt() and 0xFF) shl 24)
        return Float.fromBits(bits)
    }

    private fun ByteArray.readUShort(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.readUInt(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or
                ((this[offset + 1].toInt() and 0xFF) shl 8) or
                ((this[offset + 2].toInt() and 0xFF) shl 16) or
                ((this[offset + 3].toInt() and 0xFF) shl 24)
}
