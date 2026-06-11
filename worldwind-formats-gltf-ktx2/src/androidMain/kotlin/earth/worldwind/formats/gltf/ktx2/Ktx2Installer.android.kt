package earth.worldwind.formats.gltf.ktx2

import earth.worldwind.formats.gltf.GltfDecoderRegistry
import earth.worldwind.formats.gltf.Ktx2Decoder
import earth.worldwind.formats.gltf.Ktx2Image

actual suspend fun installKtx2Decoder() {
    if (GltfDecoderRegistry.ktx2Decoder != null) return
    if (NativeKtx2.tryEnsureInitialized()) {
        GltfDecoderRegistry.ktx2Decoder = AndroidKtx2Decoder
    }
}

actual fun releaseKtx2Decoder() {
    if (GltfDecoderRegistry.ktx2Decoder === AndroidKtx2Decoder) {
        GltfDecoderRegistry.ktx2Decoder = null
    }
}

private object AndroidKtx2Decoder : Ktx2Decoder {
    override fun decode(ktx2Bytes: ByteArray): Ktx2Image {
        val r = NativeKtx2.decode(ktx2Bytes)
        return Ktx2Image(rgba = r.rgba, width = r.width, height = r.height)
    }
}
