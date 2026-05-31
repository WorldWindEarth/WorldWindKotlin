package earth.worldwind.layer.cache

import earth.worldwind.formats.geotiff.GeoTiffReader
import earth.worldwind.formats.geotiff.GeoTiffWriter
import kotlin.math.round

/**
 * Cross-platform encode/decode for one elevation tile in the cache.
 *
 * **Storage format** (JS / iOS — the platforms that use this codec):
 *   - `isFloat = true` → TIFF-Float32. Lossless for Float32 sources; widens Int16
 *     sources to Float32. Coverage-level `(scale, offset) = (1, 0)` (unused).
 *   - `isFloat = false` → TIFF-Uint16 with per-tile `(scale, offset)` that pack a
 *     FloatBuffer's actual range into the 16-bit codes. Int16 sources go through
 *     unchanged at `(1, 0)`. Float→Int *never* rounds through a WorldWind `ShortArray`
 *     intermediate — the lossy step is the 16-bit quantization (`(max-min)/65535`
 *     per code), not "round to nearest meter".
 *
 * The JVM/GeoPackage path uses PNG-Int16 + the gpkg `gpkg_2d_gridded_tile_ancillary`
 * table; it has its own implementation in `GpkgCachedElevationDataFactory`. Same
 * encoding contract (storage `isFloat` + per-tile scale/offset), different wire
 * format.
 */
object ElevationStorageCodec {

    /** Bytes ready for blob storage plus the per-tile scale/offset the read path needs
     *  to recover the original sample values. Persist [tileScale] + [tileOffset]
     *  alongside [bytes] in whatever ancillary store the platform uses (IDB object
     *  store / iOS sidecar). */
    data class Encoded(val bytes: ByteArray, val tileScale: Float, val tileOffset: Float)

    /**
     * Encode a tile buffer for storage. Dispatches on [buffer] type and [isFloat]:
     *
     * | source         | isFloat | output                                 |
     * |----------------|---------|----------------------------------------|
     * | ShortBuffer    | false   | TIFF-Uint16 codes (scale=1, offset=0)  |
     * | ShortBuffer    | true    | TIFF-Float32 (widened, lossless)       |
     * | FloatBuffer    | true    | TIFF-Float32 (lossless)                |
     * | FloatBuffer    | false   | TIFF-Uint16 with (scale, offset) packed from the FloatBuffer's value range — preserves up to ~`range/65535` per pixel; no ShortArray intermediate |
     */
    fun encode(
        buffer: ElevationTileBuffer, isFloat: Boolean, tileWidth: Int, tileHeight: Int,
    ): Encoded = when (buffer) {
        is ElevationTileBuffer.Shorts -> encodeShorts(buffer.values, isFloat, tileWidth, tileHeight)
        is ElevationTileBuffer.Floats -> encodeFloats(buffer.values, isFloat, tileWidth, tileHeight)
    }

    /**
     * Decode a stored tile. Returns either a `ShortBuffer`-equivalent (for unscaled
     * Int16 storage) or a `FloatBuffer`-equivalent (for Float32 storage or Int16
     * storage with non-trivial scale/offset where applying them would lose precision).
     *
     * Callers convert to the `ShortArray` render representation at the boundary
     * (typically via the platform's elevation decoder).
     */
    fun decode(
        bytes: ByteArray, isFloat: Boolean, tileScale: Float, tileOffset: Float,
    ): ElevationTileBuffer {
        val reader = GeoTiffReader(bytes)
        return if (isFloat) {
            ElevationTileBuffer.Floats(reader.createElevationFloatArray())
        } else if (tileScale != 1f || tileOffset != 0f) {
            val raw = reader.createElevationFloatArray()
            for (i in raw.indices) raw[i] = raw[i] * tileScale + tileOffset
            ElevationTileBuffer.Floats(raw)
        } else {
            ElevationTileBuffer.Shorts(reader.createElevationShortArray())
        }
    }

    private fun encodeShorts(
        values: ShortArray, isFloat: Boolean, tileWidth: Int, tileHeight: Int,
    ): Encoded = if (isFloat) {
        val widened = FloatArray(values.size) { values[it].toFloat() }
        Encoded(
            bytes = GeoTiffWriter.writeFloatGrayscaleTiff(widened, tileWidth, tileHeight),
            tileScale = 1f, tileOffset = 0f,
        )
    } else {
        val codes = IntArray(values.size) { values[it].toInt() and 0xFFFF }
        Encoded(
            bytes = GeoTiffWriter.writeUint16GrayscaleTiff(codes, tileWidth, tileHeight),
            tileScale = 1f, tileOffset = 0f,
        )
    }

    private fun encodeFloats(
        values: FloatArray, isFloat: Boolean, tileWidth: Int, tileHeight: Int,
    ): Encoded = if (isFloat) {
        Encoded(
            bytes = GeoTiffWriter.writeFloatGrayscaleTiff(values, tileWidth, tileHeight),
            tileScale = 1f, tileOffset = 0f,
        )
    } else {
        // First pass: find non-null min/max in the FloatBuffer.
        var min = Float.POSITIVE_INFINITY
        var max = Float.NEGATIVE_INFINITY
        var hasValid = false
        for (f in values) {
            if (f.isNoData()) continue
            hasValid = true
            if (f < min) min = f
            if (f > max) max = f
        }
        val effectiveMin = if (hasValid) min else 0f
        val effectiveMax = if (hasValid && max > min) max else effectiveMin
        val range = effectiveMax - effectiveMin
        val tileScale = if (range > 0f) range / 65535f else 1f
        val tileOffset = effectiveMin
        // Pack each float directly to a 16-bit code, no ShortArray intermediate.
        // Null sentinel (Float.MAX_VALUE) maps to code 0 — lossy but consistent.
        val codes = IntArray(values.size) { i ->
            val f = values[i]
            if (f.isNoData()) {
                0
            } else {
                val raw = ((f - tileOffset) / tileScale).toDouble()
                round(raw.coerceIn(0.0, 65535.0)).toInt()
            }
        }
        Encoded(
            bytes = GeoTiffWriter.writeUint16GrayscaleTiff(codes, tileWidth, tileHeight),
            tileScale = tileScale, tileOffset = tileOffset,
        )
    }

    /**
     * No-data test for an elevation sample. `Float.MAX_VALUE`
     * ([earth.worldwind.globe.elevation.coverage.ElevationCoverage.MISSING_DATA]) is the
     * sentinel, but it's detected by magnitude rather than `== Float.MAX_VALUE`: on Kotlin/JS
     * a value round-tripped through a `Float32Array` doesn't reliably equal the
     * `Float.MAX_VALUE` constant (float-vs-double rounding), so an exact compare lets the
     * sentinel leak into the min/max sweep and blows up the packing scale. No real elevation
     * comes anywhere near 1e30, so the threshold is unambiguous.
     */
    private fun Float.isNoData(): Boolean = isNaN() || this >= 1e30f
}

/**
 * Platform-neutral tile-buffer carrier. Concrete sample type stays explicit so the
 * codec doesn't need a platform `Buffer` abstraction (the existing `java.nio.Buffer`
 * shape on JVM and `Float32Array` shape on JS / iOS aren't `expect/actual`-friendly).
 */
sealed class ElevationTileBuffer {
    class Shorts(val values: ShortArray) : ElevationTileBuffer()
    class Floats(val values: FloatArray) : ElevationTileBuffer()
}
