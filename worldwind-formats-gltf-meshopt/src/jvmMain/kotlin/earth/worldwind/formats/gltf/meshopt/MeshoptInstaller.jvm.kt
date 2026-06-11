package earth.worldwind.formats.gltf.meshopt

import earth.worldwind.formats.gltf.GltfDecoderRegistry
import earth.worldwind.formats.gltf.MeshoptDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual suspend fun installMeshoptDecoder() {
    if (GltfDecoderRegistry.meshoptDecoder != null) return
    val ok = withContext(Dispatchers.Default) { NativeMeshopt.tryEnsureInitialized() }
    if (ok) GltfDecoderRegistry.meshoptDecoder = JvmMeshoptDecoder
}

actual fun releaseMeshoptDecoder() {
    if (GltfDecoderRegistry.meshoptDecoder === JvmMeshoptDecoder) {
        GltfDecoderRegistry.meshoptDecoder = null
    }
}

private object JvmMeshoptDecoder : MeshoptDecoder {
    override fun decode(
        compressed: ByteArray,
        count: Int,
        byteStride: Int,
        mode: MeshoptDecoder.Mode,
        filter: MeshoptDecoder.Filter?,
    ): ByteArray = NativeMeshopt.decode(
        compressed, count, byteStride,
        mode = mode.ordinal,
        filter = filter?.ordinal ?: -1,
    )
}
