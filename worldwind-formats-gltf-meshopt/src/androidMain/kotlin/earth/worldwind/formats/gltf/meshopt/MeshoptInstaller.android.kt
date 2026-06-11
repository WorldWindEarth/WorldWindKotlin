package earth.worldwind.formats.gltf.meshopt

import earth.worldwind.formats.gltf.GltfDecoderRegistry
import earth.worldwind.formats.gltf.MeshoptDecoder

actual suspend fun installMeshoptDecoder() {
    if (GltfDecoderRegistry.meshoptDecoder != null) return
    if (NativeMeshopt.tryEnsureInitialized()) {
        GltfDecoderRegistry.meshoptDecoder = AndroidMeshoptDecoder
    }
}

actual fun releaseMeshoptDecoder() {
    if (GltfDecoderRegistry.meshoptDecoder === AndroidMeshoptDecoder) {
        GltfDecoderRegistry.meshoptDecoder = null
    }
}

private object AndroidMeshoptDecoder : MeshoptDecoder {
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
