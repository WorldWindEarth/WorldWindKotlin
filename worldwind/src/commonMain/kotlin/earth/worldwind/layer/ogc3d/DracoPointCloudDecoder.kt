package earth.worldwind.layer.ogc3d

/**
 * Pluggable codec for the `3DTILES_draco_point_compression` PNTS extension. Wired in by
 * the `worldwind-formats-gltf-draco` module's `installDracoDecoder()`, which shares its
 * native bridge with the glTF mesh path. Implementations must be thread-safe.
 */
interface DracoPointCloudDecoder {
    /**
     * @param compressedBuffer Draco stream from `extensions.3DTILES_draco_point_compression`.
     * @param attributeIds PNTS semantic → Draco unique-id from the extension's `properties` map.
     * @throws IllegalArgumentException on malformed input (caller surfaces as a tile failure).
     */
    fun decode(compressedBuffer: ByteArray, attributeIds: Map<String, Int>): DracoPointCloud
}

/** Decoded point cloud. [positions] / [colors] may be pool-backed and longer than the valid
 *  span — readers must respect [positionsCount] / [colorsCount] (in points). */
class DracoPointCloud(
    val positions: FloatArray,
    val colors: FloatArray? = null,
    val positionsCount: Int = positions.size / 3,
    val colorsCount: Int = (colors?.size ?: 0) / 4,
)
