package earth.worldwind.layer.ogc3d.content.spz

import earth.worldwind.formats.gltf.GlbReader
import earth.worldwind.layer.ogc3d.content.GaussianLoader
import earth.worldwind.layer.ogc3d.content.GaussianPayload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Loader for glb-wrapped Gaussian splats — glTF 2.0 binary declaring
 * `KHR_gaussian_splatting` + `KHR_gaussian_splatting_compression_spz_2`, with the SPZ
 * stream as a bufferView in the BIN chunk. The de-facto production wire format (Cesium,
 * gs_view, Niantic Lightship VPS). Also accepts raw `.spz` (`NGSP`) so a single
 * registration on [earth.worldwind.layer.ogc3d.Ogc3dTilesLayer.gaussianLoader] handles both.
 *
 * Unwrap: parse GLB → slice SPZ bufferView → outer inflate (the whole header+body is
 * gzipped as one chunk, unlike raw `.spz` which only gzips the body) → run uncompressed
 * SPZ decode → bake the primitive's glTF node transform (typically Y-up → Z-up + a
 * translation) into per-splat positions + orientation quaternions.
 */
class GltfGaussianLoader(
    private val rawSpzLoader: SpzGaussianLoader = SpzGaussianLoader(options = SpzDecodeOptions.ANISOTROPIC),
) : GaussianLoader {

    override fun supports(bytes: ByteArray): Boolean {
        if (rawSpzLoader.supports(bytes)) return true
        return looksLikeGaussianGlb(bytes)
    }

    override fun parse(bytes: ByteArray): GaussianPayload {
        if (rawSpzLoader.supports(bytes)) return rawSpzLoader.parse(bytes)
        val glb = GlbReader.parse(bytes)
        val binChunk = glb.binChunk
            ?: error("KHR_gaussian_splatting glTF needs a BIN chunk; got JSON-only payload")
        val (gzippedSpz, nodeMatrix) = extract(glb.jsonText, binChunk)
        val inflater = SpzGaussianLoader.requireInflater()
        val raw = rawSpzLoader.parseUncompressedStream(inflater.inflate(gzippedSpz))
        return applyNodeMatrix(raw, nodeMatrix)
    }

    /** Glb magic + the SPZ-2 extension name appears in the first 8 KB. Matches the
     *  SPZ-specific extension (not the generic `KHR_gaussian_splatting`) so glbs that merely
     *  list the parent extension in extensionsUsed but ship a different codec aren't routed
     *  through this loader. Avoids a full GLB parse on the dispatcher's hot path. */
    private fun looksLikeGaussianGlb(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        if (bytes[0] != 'g'.code.toByte() || bytes[1] != 'l'.code.toByte() ||
            bytes[2] != 'T'.code.toByte() || bytes[3] != 'F'.code.toByte()) return false
        val probe = minOf(bytes.size, 8192)
        outer@ for (i in 12..probe - NEEDLE.size) {
            for (j in NEEDLE.indices) {
                if (bytes[i + j] != NEEDLE[j]) continue@outer
            }
            return true
        }
        return false
    }

    /** Slice the bufferView referenced by the first mesh primitive's spz_2 extension out
     *  of the BIN chunk + read the first scene node's optional 4x4 transform. */
    private fun extract(jsonText: String, binChunk: ByteArray): Pair<ByteArray, FloatArray?> {
        val root = Json.parseToJsonElement(jsonText).jsonObject
        val bufferViews = root["bufferViews"]?.jsonArray
            ?: error("glTF gaussian splat: missing bufferViews")
        val firstMesh = root["meshes"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: error("glTF gaussian splat: missing/empty meshes")
        val firstPrim = firstMesh["primitives"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: error("glTF gaussian splat: first mesh has no primitive")
        val gsExt = firstPrim["extensions"]?.jsonObject?.get(EXT_GAUSSIAN)?.jsonObject
            ?: error("glTF gaussian splat: primitive missing $EXT_GAUSSIAN")
        val spzExt = gsExt["extensions"]?.jsonObject?.get(EXT_SPZ2)?.jsonObject
            ?: error("glTF gaussian splat: primitive missing $EXT_SPZ2")
        val bvIdx = spzExt["bufferView"]?.jsonPrimitive?.intOrNull
            ?: error("glTF gaussian splat: spz extension missing bufferView")
        val bv = bufferViews.getOrNull(bvIdx)?.jsonObject
            ?: error("glTF gaussian splat: bufferView $bvIdx out of range")
        val byteOffset = bv["byteOffset"]?.jsonPrimitive?.intOrNull ?: 0
        val byteLength = bv["byteLength"]?.jsonPrimitive?.intOrNull
            ?: error("glTF gaussian splat: bufferView missing byteLength")
        require(byteOffset >= 0 && byteOffset + byteLength <= binChunk.size) {
            "glTF gaussian splat: bufferView [$byteOffset, +$byteLength) out of BIN " +
                "bounds (${binChunk.size})"
        }
        // Single-bufferView tiles can hand the BIN chunk through directly (saves a copy).
        val spz = if (byteOffset == 0 && byteLength == binChunk.size) binChunk
        else binChunk.copyOfRange(byteOffset, byteOffset + byteLength)
        return spz to extractFirstNodeMatrix(root)
    }

    /** 4x4 column-major matrix of the node referencing the first mesh (which is where
     *  [extract] pulls the SPZ from), or null when absent (treat as identity). A leading
     *  camera/light/empty node — common in glTF exporters — would otherwise hijack the
     *  transform from `nodes[0]`. */
    private fun extractFirstNodeMatrix(root: JsonObject): FloatArray? {
        val nodes = root["nodes"]?.jsonArray ?: return null
        val node = nodes.firstOrNull { entry ->
            (entry as? JsonObject)?.get("mesh")?.jsonPrimitive?.intOrNull == 0
        } as? JsonObject ?: return null
        val matrix = node["matrix"]?.jsonArray ?: return null
        if (matrix.size != 16) return null
        return FloatArray(16) { matrix[it].jsonPrimitive.doubleOrNull?.toFloat() ?: 0f }
    }

    /** Bake the node transform into the splats so the engine sees content already in
     *  tile-local frame. Mutates [GaussianPayload.centers] + [GaussianPayload.rotations] in
     *  place — each splat's read/write is self-contained, no aliasing. Assumes the 3x3 is
     *  orthonormal (authoring tools emit identity / basis-swap; non-uniform scale belongs
     *  on the tile transform). */
    private fun applyNodeMatrix(raw: GaussianPayload, matrix: FloatArray?): GaussianPayload {
        if (matrix == null || isIdentity4x4(matrix)) return raw

        // glTF 4x4 is column-major: m[col * 4 + row].
        val m00 = matrix[0]; val m10 = matrix[1]; val m20 = matrix[2]
        val m01 = matrix[4]; val m11 = matrix[5]; val m21 = matrix[6]
        val m02 = matrix[8]; val m12 = matrix[9]; val m22 = matrix[10]
        val tx = matrix[12]; val ty = matrix[13]; val tz = matrix[14]

        val q = rotationToQuaternion(m00, m01, m02, m10, m11, m12, m20, m21, m22)
        val qx = q[0]; val qy = q[1]; val qz = q[2]; val qw = q[3]

        val centers = raw.centers
        val rotations = raw.rotations
        val count = raw.splatCount
        // SpzDecodeOptions.skipRotations leaves rotations empty; the splats are round and
        // the Hamilton product would index past the empty array.
        val applyRotation = rotations.isNotEmpty()
        for (i in 0 until count) {
            val px = centers[i * 3]
            val py = centers[i * 3 + 1]
            val pz = centers[i * 3 + 2]
            centers[i * 3]     = m00 * px + m01 * py + m02 * pz + tx
            centers[i * 3 + 1] = m10 * px + m11 * py + m12 * pz + ty
            centers[i * 3 + 2] = m20 * px + m21 * py + m22 * pz + tz

            if (applyRotation) {
                val sx = rotations[i * 4]
                val sy = rotations[i * 4 + 1]
                val sz = rotations[i * 4 + 2]
                val sw = rotations[i * 4 + 3]
                // Hamilton product q_node · q_splat in (x, y, z, w) layout.
                rotations[i * 4]     = qw * sx + qx * sw + qy * sz - qz * sy
                rotations[i * 4 + 1] = qw * sy - qx * sz + qy * sw + qz * sx
                rotations[i * 4 + 2] = qw * sz + qx * sy - qy * sx + qz * sw
                rotations[i * 4 + 3] = qw * sw - qx * sx - qy * sy - qz * sz
            }
        }
        return raw
    }

    private fun isIdentity4x4(m: FloatArray): Boolean {
        for (col in 0..3) for (row in 0..3) {
            val expect = if (row == col) 1f else 0f
            if (abs(m[col * 4 + row] - expect) > 1e-6f) return false
        }
        return true
    }

    /** Shoemake's branch on the largest diagonal element. Returns quaternion as
     *  (x, y, z, w) to match the SPZ layout. */
    private fun rotationToQuaternion(
        m00: Float, m01: Float, m02: Float,
        m10: Float, m11: Float, m12: Float,
        m20: Float, m21: Float, m22: Float,
    ): FloatArray {
        val trace = m00 + m11 + m22
        if (trace > 0f) {
            val s = sqrt(trace + 1f) * 2f
            return floatArrayOf((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, 0.25f * s)
        }
        if (m00 > m11 && m00 > m22) {
            val s = sqrt(1f + m00 - m11 - m22) * 2f
            return floatArrayOf(0.25f * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s)
        }
        if (m11 > m22) {
            val s = sqrt(1f + m11 - m00 - m22) * 2f
            return floatArrayOf((m01 + m10) / s, 0.25f * s, (m12 + m21) / s, (m02 - m20) / s)
        }
        val s = sqrt(1f + m22 - m00 - m11) * 2f
        return floatArrayOf((m02 + m20) / s, (m12 + m21) / s, 0.25f * s, (m10 - m01) / s)
    }

    private companion object {
        private const val EXT_GAUSSIAN = "KHR_gaussian_splatting"
        private const val EXT_SPZ2 = "KHR_gaussian_splatting_compression_spz_2"
        private val NEEDLE = EXT_SPZ2.encodeToByteArray()
    }
}
