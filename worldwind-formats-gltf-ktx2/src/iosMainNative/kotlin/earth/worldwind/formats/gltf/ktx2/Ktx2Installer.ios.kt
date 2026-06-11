@file:OptIn(ExperimentalForeignApi::class)

package earth.worldwind.formats.gltf.ktx2

import earth.worldwind.formats.gltf.GltfDecoderRegistry
import earth.worldwind.formats.gltf.Ktx2Decoder
import earth.worldwind.formats.gltf.Ktx2Image
import earth.worldwind.formats.gltf.ktx2.cinterop.wwktx_copy_rgba
import earth.worldwind.formats.gltf.ktx2.cinterop.wwktx_decode
import earth.worldwind.formats.gltf.ktx2.cinterop.wwktx_height
import earth.worldwind.formats.gltf.ktx2.cinterop.wwktx_release
import earth.worldwind.formats.gltf.ktx2.cinterop.wwktx_width
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned

actual suspend fun installKtx2Decoder() {
    if (GltfDecoderRegistry.ktx2Decoder != null) return
    GltfDecoderRegistry.ktx2Decoder = IosKtx2Decoder
}

actual fun releaseKtx2Decoder() {
    if (GltfDecoderRegistry.ktx2Decoder === IosKtx2Decoder) {
        GltfDecoderRegistry.ktx2Decoder = null
    }
}

private object IosKtx2Decoder : Ktx2Decoder {
    override fun decode(ktx2Bytes: ByteArray): Ktx2Image {
        require(ktx2Bytes.isNotEmpty()) { "KTX2 decode failed (empty input buffer)" }
        val handle = ktx2Bytes.usePinned { pinned ->
            wwktx_decode(pinned.addressOf(0).reinterpret<UByteVar>(), ktx2Bytes.size.convert())
        } ?: error("KTX2 decode failed (native returned null handle)")
        return try {
            val w = wwktx_width(handle)
            val h = wwktx_height(handle)
            val rgba = ByteArray(w * h * 4)
            // Degenerate 0×0 texture — skip the pinned copy.
            if (rgba.isNotEmpty()) {
                rgba.usePinned { pinned ->
                    wwktx_copy_rgba(handle, pinned.addressOf(0).reinterpret<UByteVar>(), rgba.size.convert())
                }
            }
            Ktx2Image(rgba = rgba, width = w, height = h)
        } finally {
            wwktx_release(handle)
        }
    }
}
