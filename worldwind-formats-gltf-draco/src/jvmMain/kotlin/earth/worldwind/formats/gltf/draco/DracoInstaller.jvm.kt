package earth.worldwind.formats.gltf.draco

import earth.worldwind.formats.gltf.DracoDecoder
import earth.worldwind.formats.gltf.DracoMesh
import earth.worldwind.formats.gltf.GltfDecoderRegistry
import earth.worldwind.layer.ogc3d.Ogc3dDecoderRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * JVM actual. Bundles the host-built `libdraco_bridge.{so,dylib,dll}` under JAR resources
 * `native/<os>-<arch>/`. On install, extracts the right binary to a temp file, loads it
 * via [System.load], and registers a JNI-backed decoder in both the glTF mesh registry
 * and the PNTS point-cloud registry (single native bridge handles both).
 */
actual suspend fun installDracoDecoder() {
    if (GltfDecoderRegistry.dracoDecoder != null && Ogc3dDecoderRegistry.dracoPointCloudDecoder != null) return
    // Extract + load on Dispatchers.Default — file I/O.
    val ok = withContext(Dispatchers.Default) { NativeDraco.tryEnsureInitialized() }
    if (ok) {
        GltfDecoderRegistry.dracoDecoder = JvmDracoDecoder
        Ogc3dDecoderRegistry.dracoPointCloudDecoder = JvmDracoPointCloudDecoder
    }
}

actual fun releaseDracoDecoder() {
    if (GltfDecoderRegistry.dracoDecoder === JvmDracoDecoder) {
        GltfDecoderRegistry.dracoDecoder = null
    }
    if (Ogc3dDecoderRegistry.dracoPointCloudDecoder === JvmDracoPointCloudDecoder) {
        Ogc3dDecoderRegistry.dracoPointCloudDecoder = null
    }
}

private object JvmDracoDecoder : DracoDecoder {
    @Suppress("UNUSED_PARAMETER")
    override fun decode(compressedBuffer: ByteArray, attributeIds: Map<String, Int>): DracoMesh {
        val r = NativeDraco.decode(compressedBuffer)
        return DracoMesh(
            positions = r.positions.copyOf(r.positionsCount),
            normals = null,
            texCoords = r.texCoords.takeIf { it.isNotEmpty() },
            colors = if (r.colorsCount > 0) r.colors.copyOf(r.colorsCount) else null,
            batchIds = null,
            indicesShort = null,
            indicesInt = r.indices.takeIf { it.isNotEmpty() },
        )
    }
}
