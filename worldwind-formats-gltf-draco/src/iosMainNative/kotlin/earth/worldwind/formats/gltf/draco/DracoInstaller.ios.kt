@file:OptIn(ExperimentalForeignApi::class)

package earth.worldwind.formats.gltf.draco

import earth.worldwind.formats.gltf.DracoDecoder
import earth.worldwind.formats.gltf.DracoMesh
import earth.worldwind.formats.gltf.draco.cinterop.wwdraco_colors_count
import earth.worldwind.formats.gltf.draco.cinterop.wwdraco_decode
import earth.worldwind.formats.gltf.draco.cinterop.wwdraco_get_colors
import earth.worldwind.formats.gltf.draco.cinterop.wwdraco_get_indices
import earth.worldwind.formats.gltf.draco.cinterop.wwdraco_get_positions
import earth.worldwind.formats.gltf.draco.cinterop.wwdraco_get_texcoords
import earth.worldwind.formats.gltf.draco.cinterop.wwdraco_indices_count
import earth.worldwind.formats.gltf.draco.cinterop.wwdraco_positions_count
import earth.worldwind.formats.gltf.draco.cinterop.wwdraco_release
import earth.worldwind.formats.gltf.draco.cinterop.wwdraco_texcoords_count
import earth.worldwind.formats.gltf.GltfDecoderRegistry
import earth.worldwind.layer.ogc3d.DracoPointCloud
import earth.worldwind.layer.ogc3d.DracoPointCloudDecoder
import earth.worldwind.layer.ogc3d.Ogc3dDecoderRegistry
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.refTo
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toCValues
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.addressOf

/**
 * iOS actual. Calls into the statically-linked `libdraco_bridge.a` (built per-architecture
 * by the `cmakeBuildIos_*` Gradle tasks; linked via cinterop). The C ABI for the bridge
 * is declared at `src/iosMain/cpp/draco_bridge.h`; cinterop generates the Kotlin
 * bindings used below.
 */
actual suspend fun installDracoDecoder() {
    if (GltfDecoderRegistry.dracoDecoder != null && Ogc3dDecoderRegistry.dracoPointCloudDecoder != null) return
    GltfDecoderRegistry.dracoDecoder = IosDracoDecoder
    Ogc3dDecoderRegistry.dracoPointCloudDecoder = IosDracoPointCloudDecoder
}

actual fun releaseDracoDecoder() {
    if (GltfDecoderRegistry.dracoDecoder === IosDracoDecoder) {
        GltfDecoderRegistry.dracoDecoder = null
    }
    if (Ogc3dDecoderRegistry.dracoPointCloudDecoder === IosDracoPointCloudDecoder) {
        Ogc3dDecoderRegistry.dracoPointCloudDecoder = null
    }
}

private object IosDracoPointCloudDecoder : DracoPointCloudDecoder {
    override fun decode(compressedBuffer: ByteArray, attributeIds: Map<String, Int>): DracoPointCloud {
        require(compressedBuffer.isNotEmpty()) { "Draco decode failed (empty input buffer)" }
        val colorsUniqueId = attributeIds["RGBA"] ?: attributeIds["RGB"] ?: -1
        val handle = compressedBuffer.usePinned { pinned ->
            wwdraco_decode(
                pinned.addressOf(0).reinterpret<kotlinx.cinterop.UByteVar>(),
                compressedBuffer.size.convert(),
                colorsUniqueId,
            )
        } ?: error("Draco point-cloud decode failed (native returned null handle)")
        return try {
            val positions = readFloatArray(wwdraco_positions_count(handle).toInt()) { out, cap ->
                wwdraco_get_positions(handle, out, cap.convert()).toInt()
            }
            val colors = readFloatArray(wwdraco_colors_count(handle).toInt()) { out, cap ->
                wwdraco_get_colors(handle, out, cap.convert()).toInt()
            }
            DracoPointCloud(
                positions = positions ?: FloatArray(0),
                colors = colors,
            )
        } finally {
            wwdraco_release(handle)
        }
    }
}

private object IosDracoDecoder : DracoDecoder {
    @Suppress("UNUSED_PARAMETER")
    override fun decode(compressedBuffer: ByteArray, attributeIds: Map<String, Int>): DracoMesh {
        // addressOf(0) throws on empty arrays — surface as "decode failed" without crashing.
        require(compressedBuffer.isNotEmpty()) { "Draco decode failed (empty input buffer)" }
        val handle = compressedBuffer.usePinned { pinned ->
            wwdraco_decode(
                pinned.addressOf(0).reinterpret<kotlinx.cinterop.UByteVar>(),
                compressedBuffer.size.convert(),
                -1,
            )
        } ?: error("Draco decode failed (native returned null handle)")

        return try {
            val indices = readInt32Array(wwdraco_indices_count(handle).toInt()) { out, cap ->
                wwdraco_get_indices(handle, out, cap.convert()).toInt()
            }
            val positions = readFloatArray(wwdraco_positions_count(handle).toInt()) { out, cap ->
                wwdraco_get_positions(handle, out, cap.convert()).toInt()
            }
            val texCoords = readFloatArray(wwdraco_texcoords_count(handle).toInt()) { out, cap ->
                wwdraco_get_texcoords(handle, out, cap.convert()).toInt()
            }
            val colors = readFloatArray(wwdraco_colors_count(handle).toInt()) { out, cap ->
                wwdraco_get_colors(handle, out, cap.convert()).toInt()
            }
            DracoMesh(
                positions = positions ?: FloatArray(0),
                normals = null,
                texCoords = texCoords,
                colors = colors,
                batchIds = null,
                indicesShort = null,
                indicesInt = indices,
            )
        } finally {
            wwdraco_release(handle)
        }
    }
}

private fun readInt32Array(count: Int, copy: (out: kotlinx.cinterop.CPointer<IntVar>, capacity: Int) -> Int): IntArray? {
    if (count <= 0) return null
    val out = IntArray(count)
    out.usePinned { pinned ->
        copy(pinned.addressOf(0).reinterpret<IntVar>(), count)
    }
    return out
}

private fun readFloatArray(count: Int, copy: (out: kotlinx.cinterop.CPointer<FloatVar>, capacity: Int) -> Int): FloatArray? {
    if (count <= 0) return null
    val out = FloatArray(count)
    out.usePinned { pinned ->
        copy(pinned.addressOf(0).reinterpret<FloatVar>(), count)
    }
    return out
}
