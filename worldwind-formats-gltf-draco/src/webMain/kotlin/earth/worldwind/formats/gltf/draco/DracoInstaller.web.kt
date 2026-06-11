package earth.worldwind.formats.gltf.draco

import earth.worldwind.formats.gltf.DracoDecoder
import earth.worldwind.formats.gltf.DracoMesh
import earth.worldwind.formats.gltf.GltfDecoderRegistry
import earth.worldwind.layer.ogc3d.DracoPointCloud
import earth.worldwind.layer.ogc3d.DracoPointCloudDecoder
import earth.worldwind.layer.ogc3d.Ogc3dDecoderRegistry

/**
 * Web actual. Loads Google's `draco3d` npm package (which wraps `draco_decoder.wasm`) on
 * first install, then registers decoders for both the glTF mesh and PNTS point-cloud paths
 * (the underlying JS pipeline dispatches on Draco's encoded-geometry-type).
 */
actual suspend fun installDracoDecoder() {
    if (GltfDecoderRegistry.dracoDecoder != null && Ogc3dDecoderRegistry.dracoPointCloudDecoder != null) return
    NativeDraco.ensureInitialized()
    GltfDecoderRegistry.dracoDecoder = WebDracoDecoder
    Ogc3dDecoderRegistry.dracoPointCloudDecoder = WebDracoPointCloudDecoder
}

actual fun releaseDracoDecoder() {
    if (GltfDecoderRegistry.dracoDecoder === WebDracoDecoder) {
        GltfDecoderRegistry.dracoDecoder = null
    }
    if (Ogc3dDecoderRegistry.dracoPointCloudDecoder === WebDracoPointCloudDecoder) {
        Ogc3dDecoderRegistry.dracoPointCloudDecoder = null
    }
}

private object WebDracoPointCloudDecoder : DracoPointCloudDecoder {
    override fun decode(compressedBuffer: ByteArray, attributeIds: Map<String, Int>): DracoPointCloud {
        val colorsUniqueId = attributeIds["RGBA"] ?: attributeIds["RGB"] ?: -1
        val r = NativeDraco.decode(compressedBuffer, colorsUniqueId)
            ?: error("Draco point-cloud decode failed (draco_decoder.wasm returned no result)")
        return DracoPointCloud(
            positions = r.positions,
            colors = r.colors.takeIf { it.isNotEmpty() },
        )
    }
}

private object WebDracoDecoder : DracoDecoder {
    @Suppress("UNUSED_PARAMETER")
    override fun decode(compressedBuffer: ByteArray, attributeIds: Map<String, Int>): DracoMesh {
        val r = NativeDraco.decode(compressedBuffer)
            ?: error("Draco decode failed (draco_decoder.wasm returned no result)")
        return DracoMesh(
            positions = r.positions,
            normals = null,
            texCoords = r.texCoords.takeIf { it.isNotEmpty() },
            colors = r.colors.takeIf { it.isNotEmpty() },
            batchIds = null,
            indicesShort = null,
            indicesInt = r.indices.takeIf { it.isNotEmpty() },
        )
    }
}
