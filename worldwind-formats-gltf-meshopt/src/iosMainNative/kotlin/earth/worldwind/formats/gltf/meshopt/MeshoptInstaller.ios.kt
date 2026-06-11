@file:OptIn(ExperimentalForeignApi::class)

package earth.worldwind.formats.gltf.meshopt

import earth.worldwind.formats.gltf.GltfDecoderRegistry
import earth.worldwind.formats.gltf.MeshoptDecoder
import earth.worldwind.formats.gltf.meshopt.cinterop.wwmeshopt_decode
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned

actual suspend fun installMeshoptDecoder() {
    if (GltfDecoderRegistry.meshoptDecoder != null) return
    GltfDecoderRegistry.meshoptDecoder = IosMeshoptDecoder
}

actual fun releaseMeshoptDecoder() {
    if (GltfDecoderRegistry.meshoptDecoder === IosMeshoptDecoder) {
        GltfDecoderRegistry.meshoptDecoder = null
    }
}

private object IosMeshoptDecoder : MeshoptDecoder {
    override fun decode(
        compressed: ByteArray,
        count: Int,
        byteStride: Int,
        mode: MeshoptDecoder.Mode,
        filter: MeshoptDecoder.Filter?,
    ): ByteArray {
        val out = ByteArray(count * byteStride)
        // addressOf(0) on a zero-length pinned ByteArray throws — short-circuit empty
        // inputs / outputs (legal: degenerate primitives) before reaching usePinned.
        if (out.isEmpty() || compressed.isEmpty()) return out
        val status = out.usePinned { outPinned ->
            compressed.usePinned { compressedPinned ->
                wwmeshopt_decode(
                    outPinned.addressOf(0).reinterpret<UByteVar>(),
                    out.size.convert(),
                    compressedPinned.addressOf(0).reinterpret<UByteVar>(),
                    compressed.size.convert(),
                    count,
                    byteStride,
                    mode.ordinal,
                    filter?.ordinal ?: -1,
                )
            }
        }
        require(status == 0) {
            "Meshopt decode failed (status=$status, mode=$mode, count=$count, stride=$byteStride)"
        }
        return out
    }
}
