package earth.worldwind.formats.gltf.draco

import earth.worldwind.formats.gltf.DracoDecoder
import earth.worldwind.formats.gltf.DracoMesh
import earth.worldwind.formats.gltf.GltfDecoderRegistry
import earth.worldwind.layer.ogc3d.DracoPointCloud
import earth.worldwind.layer.ogc3d.DracoPointCloudDecoder
import earth.worldwind.layer.ogc3d.Ogc3dDecoderRegistry

/**
 * Android actual: loads the locally-built `libdraco_bridge.so` (Gradle's `externalNativeBuild`
 * runs CMake against `src/androidMain/cpp/` at build time, statically linking Google's
 * open-source `libdraco` 1.5.x and emitting one .so per ABI) and installs a JNI-backed
 * decoder into both the glTF mesh registry and the PNTS point-cloud registry (single
 * native bridge handles both — it already dispatches on Draco's encoded-geometry-type).
 */
actual suspend fun installDracoDecoder() {
    if (GltfDecoderRegistry.dracoDecoder != null && Ogc3dDecoderRegistry.dracoPointCloudDecoder != null) return
    if (NativeDraco.tryEnsureInitialized()) {
        GltfDecoderRegistry.dracoDecoder = AndroidDracoDecoder
        Ogc3dDecoderRegistry.dracoPointCloudDecoder = AndroidDracoPointCloudDecoder
    }
}

actual fun releaseDracoDecoder() {
    if (GltfDecoderRegistry.dracoDecoder === AndroidDracoDecoder) {
        GltfDecoderRegistry.dracoDecoder = null
    }
    if (Ogc3dDecoderRegistry.dracoPointCloudDecoder === AndroidDracoPointCloudDecoder) {
        Ogc3dDecoderRegistry.dracoPointCloudDecoder = null
    }
}

private object AndroidDracoPointCloudDecoder : DracoPointCloudDecoder {
    override fun decode(compressedBuffer: ByteArray, attributeIds: Map<String, Int>): DracoPointCloud {
        val colorsUniqueId = attributeIds["RGBA"] ?: attributeIds["RGB"] ?: -1
        val r = NativeDraco.decodePoints(compressedBuffer, colorsUniqueId)
        return DracoPointCloud(
            positions = r.positions,
            colors = if (r.colorsCount > 0) r.colors else null,
            positionsCount = r.positionsCount / 3,
            colorsCount = r.colorsCount / 4,
        )
    }
}

/**
 * Bridges the JNI binding's output to the engine's [DracoMesh]. The native bridge currently
 * decodes positions / texcoords / colors / triangle indices — normals and per-vertex
 * `_BATCHID` aren't extracted, so lit shading falls back to flat-shaded and batch-level
 * picking falls back to tile-level on Draco-compressed primitives.
 */
private object AndroidDracoDecoder : DracoDecoder {
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
