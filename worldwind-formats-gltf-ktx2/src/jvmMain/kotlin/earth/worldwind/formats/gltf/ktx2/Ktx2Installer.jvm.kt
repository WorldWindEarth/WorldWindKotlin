package earth.worldwind.formats.gltf.ktx2

import earth.worldwind.formats.gltf.GltfDecoderRegistry
import earth.worldwind.formats.gltf.Ktx2Decoder
import earth.worldwind.formats.gltf.Ktx2Image
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual suspend fun installKtx2Decoder() {
    if (GltfDecoderRegistry.ktx2Decoder != null) return
    val ok = withContext(Dispatchers.Default) { NativeKtx2.tryEnsureInitialized() }
    if (ok) GltfDecoderRegistry.ktx2Decoder = JvmKtx2Decoder
}

actual fun releaseKtx2Decoder() {
    if (GltfDecoderRegistry.ktx2Decoder === JvmKtx2Decoder) {
        GltfDecoderRegistry.ktx2Decoder = null
    }
}

private object JvmKtx2Decoder : Ktx2Decoder {
    override fun decode(ktx2Bytes: ByteArray): Ktx2Image {
        val r = NativeKtx2.decode(ktx2Bytes)
        return Ktx2Image(rgba = r.rgba, width = r.width, height = r.height)
    }
}
