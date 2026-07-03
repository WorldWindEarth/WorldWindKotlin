package earth.worldwind.formats.ogc3d

import earth.worldwind.formats.BinaryDataView
import earth.worldwind.layer.ogc3d.Ogc3dDecoderRegistry
import earth.worldwind.util.ByteArrayPool
import earth.worldwind.util.Logger
import earth.worldwind.util.Logger.logMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Decoded pnts (Point Cloud) payload. All position arrays are flattened tile-local
 * `[x0, y0, z0, x1, y1, z1, ...]` Float32. Colors are flattened RGBA Uint8 (4 bytes per
 * point) regardless of the wire format (RGB / RGBA / CONSTANT_RGBA all normalise to RGBA).
 *
 * Phase 3 scope:
 *  - POSITION (vec3 float32) + POSITION_QUANTIZED (vec3 uint16 with QUANTIZED_VOLUME_OFFSET
 *    + QUANTIZED_VOLUME_SCALE)
 *  - RGB (vec3 uint8) + RGBA (vec4 uint8) + CONSTANT_RGBA (uniform color applied to all
 *    points)
 *  - RTC_CENTER (vec3, float32 in the JSON form per spec)
 *
 * Deferred to follow-up: NORMAL / NORMAL_OCT16P, RGB565, BATCH_ID, batch-table styling.
 */
class PntsPayload internal constructor(
    val pointsLength: Int,
    /** Tile-local Float32 positions, `pointsLength * 3` entries. */
    val positions: FloatArray,
    /** Per-point RGBA color, `pointsLength * 4` bytes. */
    val colors: ByteArray,
    /** RTC_CENTER offset applied to each vertex before the tile-to-world transform. Null
     *  when the asset is already in tile-local coordinates. */
    val rtcCenter: DoubleArray?,
)

/**
 * Parses a Point Cloud (pnts) payload. Header is the standard 28-byte
 * [Ogc3dHeader]; the format-specific body is the feature-table-described point cloud.
 */
object PntsLoader {
    fun parse(bytes: ByteArray): PntsPayload {
        val header = Ogc3dHeader.parse(bytes)
        require(header.magic == "pnts") { "PntsLoader called on a non-pnts payload (magic '${header.magic}')" }
        val ft = FeatureTable.parse(
            bytes = bytes,
            jsonOffset = header.featureTableJsonOffset,
            jsonLength = header.featureTableJsonByteLength,
            binOffset = header.featureTableBinaryOffset,
            binLength = header.featureTableBinaryByteLength,
        )
        val pointsLength = ft.scalarOrNull("POINTS_LENGTH")?.toInt()
            ?: error("pnts POINTS_LENGTH missing from feature table")
        require(pointsLength > 0) { "pnts POINTS_LENGTH must be positive (got $pointsLength)" }

        val rtcCenter = ft.vec3OrNull("RTC_CENTER")

        // Draco-compressed POSITION + RGB take priority over the raw accessors — when the
        // extension is present, the regular POSITION/RGB byteOffsets are placeholders and
        // the bytes referenced by them are NOT valid raw float32 / uint8.
        decodeDraco(ft, pointsLength)?.let { (positions, colors) ->
            return PntsPayload(
                pointsLength = pointsLength,
                positions = positions,
                colors = colors,
                rtcCenter = rtcCenter,
            )
        }

        // Positions: explicit float32 takes priority; quantized form needs the volume scale.
        val positions = readPositions(ft, pointsLength)
            ?: error("pnts has neither POSITION nor POSITION_QUANTIZED accessor")

        val colors = readColors(ft, pointsLength)

        return PntsPayload(
            pointsLength = pointsLength,
            positions = positions,
            colors = colors,
            rtcCenter = rtcCenter,
        )
    }

    /** Decode the `3DTILES_draco_point_compression` extension via the registered decoder.
     *  Returns null if the extension is absent. Throws if it's present but malformed or
     *  no decoder is registered — the caller surfaces this as a tile-level failure. */
    private fun decodeDraco(ft: FeatureTable, count: Int): Pair<FloatArray, ByteArray>? {
        val extensions = ft.jsonElement("extensions") as? JsonObject ?: return null
        val draco = extensions["3DTILES_draco_point_compression"] as? JsonObject ?: return null
        val byteOffset = (draco["byteOffset"] as? JsonPrimitive)?.content?.toIntOrNull()
            ?: error("3DTILES_draco_point_compression missing byteOffset")
        val byteLength = (draco["byteLength"] as? JsonPrimitive)?.content?.toIntOrNull()
            ?: error("3DTILES_draco_point_compression missing byteLength")
        require(byteOffset >= 0 && byteOffset + byteLength <= ft.binary.size) {
            "3DTILES_draco_point_compression overflows feature-table binary: " +
                "byteOffset=$byteOffset byteLength=$byteLength binary=${ft.binary.size}"
        }
        val propertiesJson = draco["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val attributeIds = propertiesJson.mapNotNull { (k, v) ->
            (v as? JsonPrimitive)?.content?.toIntOrNull()?.let { k to it }
        }.toMap()

        val decoder = Ogc3dDecoderRegistry.dracoPointCloudDecoder ?: run {
            logMessage(
                Logger.WARN, "PntsLoader", "decodeDraco",
                "3DTILES_draco_point_compression payload but no decoder registered — " +
                    "wire installDracoDecoder() before opening Draco-compressed PNTS tilesets",
            )
            error("no 3DTILES_draco_point_compression decoder registered")
        }
        // Zero-copy when the extension covers the whole binary (Cesium/Google encoders).
        val compressed = if (byteOffset == 0 && byteLength == ft.binary.size) ft.binary
        else ft.binary.copyOfRange(byteOffset, byteOffset + byteLength)
        val decoded = decoder.decode(compressed, attributeIds)
        require(decoded.positionsCount == count) {
            "draco decoded ${decoded.positionsCount} points but POINTS_LENGTH=$count"
        }
        // PNTS positions are dataset-native Z-up per 3D Tiles 1.0 §3.4.7 — Draco-encoded
        // and raw POSITION accessors share the same frame, no axis swap needed.
        val positions = decoded.positions
        val hasAlpha = "RGBA" in attributeIds
        val colors = decoded.colors?.let { src ->
            // Detect scale on RGB only — alpha is C++-side padded to 1.0f.
            val validFloats = decoded.colorsCount * 4
            var anyGtOne = false
            var i = 0
            while (i + 2 < validFloats) {
                if (src[i] > 1.5f || src[i + 1] > 1.5f || src[i + 2] > 1.5f) {
                    anyGtOne = true; break
                }
                i += 4
            }
            val toUint8Scale = if (anyGtOne) 1f else 255f
            packRgba(count, src, toUint8Scale, hasAlpha)
        } ?: ByteArray(count * 4).also { out ->
            for (i in 0 until count * 4) out[i] = 0xFF.toByte()
        }
        return positions to colors
    }

    /** Hand-unrolled RGBA conversion — one pass per point, no modulo, inlined clamp.
     *  Output is pool-acquired (may be oversized); caller releases via [ByteArrayPool]. */
    private fun packRgba(count: Int, src: FloatArray, scale: Float, hasAlpha: Boolean): ByteArray {
        val out = ByteArrayPool.acquire(count * 4)
        var j = 0
        for (i in 0 until count) {
            out[j] = clampByte(src[j] * scale); val j1 = j + 1
            out[j1] = clampByte(src[j1] * scale); val j2 = j + 2
            out[j2] = clampByte(src[j2] * scale); val j3 = j + 3
            out[j3] = if (hasAlpha) clampByte(src[j3] * scale) else 0xFF.toByte()
            j = j + 4
        }
        return out
    }

    private fun clampByte(v: Float): Byte {
        val i = v.toInt()
        return (if (i < 0) 0 else if (i > 255) 255 else i).toByte()
    }

    private fun readPositions(ft: FeatureTable, count: Int): FloatArray? {
        ft.readVec3Float("POSITION", count)?.let { return it }
        // POSITION_QUANTIZED needs offset + scale to map uint16 [0, 65535] back to a real
        // vec3. Per spec, value v -> v / 65535 * scale + offset.
        val byteOffset = ft.accessorByteOffset("POSITION_QUANTIZED") ?: return null
        val offset = ft.vec3OrNull("QUANTIZED_VOLUME_OFFSET")
            ?: error("pnts POSITION_QUANTIZED requires QUANTIZED_VOLUME_OFFSET")
        val scale = ft.vec3OrNull("QUANTIZED_VOLUME_SCALE")
            ?: error("pnts POSITION_QUANTIZED requires QUANTIZED_VOLUME_SCALE")
        val view = BinaryDataView(ft.binary)
        val out = FloatArray(count * 3)
        val inv65535 = 1.0f / 65535.0f
        for (i in 0 until count) {
            val base = byteOffset + i * 6
            for (c in 0 until 3) {
                val q = view.getUint16(base + c * 2, littleEndian = true)
                out[i * 3 + c] = (q * inv65535 * scale[c].toFloat()) + offset[c].toFloat()
            }
        }
        return out
    }

    private fun readColors(ft: FeatureTable, count: Int): ByteArray {
        // RGBA: vec4 uint8.
        ft.readBytes("RGBA", count, components = 4)?.let { return it }
        // RGB: vec3 uint8 — promote to RGBA with alpha=255.
        ft.readBytes("RGB", count, components = 3)?.let { rgb ->
            val out = ByteArray(count * 4)
            for (i in 0 until count) {
                out[i * 4 + 0] = rgb[i * 3 + 0]
                out[i * 4 + 1] = rgb[i * 3 + 1]
                out[i * 4 + 2] = rgb[i * 3 + 2]
                out[i * 4 + 3] = 0xFF.toByte()
            }
            return out
        }
        // CONSTANT_RGBA: scalar [r, g, b, a] in the JSON, applied to every point.
        readConstantRgba(ft)?.let { rgba ->
            val out = ByteArray(count * 4)
            for (i in 0 until count) {
                out[i * 4 + 0] = rgba[0]
                out[i * 4 + 1] = rgba[1]
                out[i * 4 + 2] = rgba[2]
                out[i * 4 + 3] = rgba[3]
            }
            return out
        }
        // No color information — default to opaque white.
        val out = ByteArray(count * 4)
        for (i in 0 until count) {
            out[i * 4 + 0] = 0xFF.toByte()
            out[i * 4 + 1] = 0xFF.toByte()
            out[i * 4 + 2] = 0xFF.toByte()
            out[i * 4 + 3] = 0xFF.toByte()
        }
        return out
    }

    private fun readConstantRgba(ft: FeatureTable): ByteArray? {
        val arr = ft.jsonElement("CONSTANT_RGBA") as? JsonArray ?: return null
        if (arr.size < 4) return null
        return ByteArray(4) { i ->
            ((arr[i] as? JsonPrimitive)?.content?.toIntOrNull() ?: 255).toByte()
        }
    }
}
