package earth.worldwind.formats.gltf.draco

import earth.worldwind.layer.ogc3d.DracoPointCloud
import earth.worldwind.layer.ogc3d.DracoPointCloudDecoder

/**
 * JVM implementation. Shares the [NativeDraco] bridge with the glTF mesh path — the bridge
 * already dispatches on Draco's encoded-geometry-type and routes point-cloud payloads
 * through `DecodePointCloudFromBuffer` (see `draco_bridge.cpp`).
 *
 * Cesium / Google PNTS encoders use the standard Draco semantic attributes (POSITION,
 * COLOR), so the bridge's `GetNamedAttribute` lookups by semantic enum work without
 * needing the per-attribute unique-id fallback. The [attributeIds] parameter is provided
 * for future generic-attribute support and is currently informational.
 */
internal object JvmDracoPointCloudDecoder : DracoPointCloudDecoder {
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
