package earth.worldwind.layer.cache

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.math.abs

/**
 * Bug-class regression tests for the cross-platform elevation cache codec. Each test
 * covers a specific failure mode we hit during the v3 cache redesign — getting any of
 * these wrong means terrain misrenders (sub-meter precision loss, scale/offset
 * round-trip drift, decode-as-wrong-format silently returning shorts).
 */
class CachedElevationCodecTest {

    // Tile sizes used for the round-trip tests. Small enough to keep test cost down,
    // large enough to exercise the encoder's row-stride / strip-length paths.
    private val tileWidth = 16
    private val tileHeight = 16

    @Test
    fun shortBuffer_int16_storage_roundtrips_losslessly() {
        // Bug class: "ShortBuffer source + isFloat=false stored as PNG/TIFF-Int16 must
        // round-trip exactly — any pixel mutation here is a regression."
        val input = ShortArray(tileWidth * tileHeight) { i -> (i - 128).toShort() }
        val source = ElevationTileBuffer.Shorts(input)
        val encoded = ElevationStorageCodec.encode(source, isFloat = false, tileWidth, tileHeight)
        assertEquals(1f, encoded.tileScale, "Int16→Int16 must record scale=1")
        assertEquals(0f, encoded.tileOffset, "Int16→Int16 must record offset=0")

        val decoded = ElevationStorageCodec.decode(encoded.bytes, isFloat = false, encoded.tileScale, encoded.tileOffset)
        assertTrue(decoded is ElevationTileBuffer.Shorts, "Int16 storage with (1,0) must decode as Shorts")
        assertEquals(input.size, decoded.values.size)
        for (i in input.indices) {
            assertEquals(input[i], decoded.values[i], "pixel $i drifted in Int16 round-trip")
        }
    }

    @Test
    fun floatBuffer_float32_storage_roundtrips_losslessly() {
        // Bug class: "FloatBuffer source + isFloat=true stored as TIFF-Float32 must
        // preserve every bit — this is the precision-critical path the original isFloat
        // design existed for."
        val input = FloatArray(tileWidth * tileHeight) { i -> i * 0.123456f - 5.0f }
        val source = ElevationTileBuffer.Floats(input)
        val encoded = ElevationStorageCodec.encode(source, isFloat = true, tileWidth, tileHeight)
        assertEquals(1f, encoded.tileScale)
        assertEquals(0f, encoded.tileOffset)

        val decoded = ElevationStorageCodec.decode(encoded.bytes, isFloat = true, 1f, 0f)
        assertTrue(decoded is ElevationTileBuffer.Floats, "Float32 storage must decode as Floats")
        val out = decoded.values
        assertEquals(input.size, out.size)
        for (i in input.indices) {
            // GeoTiff round-trip should be exact for IEEE-754 finite values.
            assertEquals(input[i], out[i], "pixel $i drifted in Float32 round-trip")
        }
    }

    @Test
    fun floatBuffer_int16_storage_packs_via_scale_offset() {
        // Bug class: the precision concern that drove the v2.0 codec redesign — a
        // Float32 source forced into Int16 storage must NEVER round through a
        // WorldWind ShortArray (truncates fractional meters); it must pack with
        // per-tile (scale, offset) so the lossy step is just 16-bit quantization.
        val min = 100.5f
        val max = 8500.25f
        val input = FloatArray(tileWidth * tileHeight) { i ->
            min + (max - min) * (i.toFloat() / (tileWidth * tileHeight - 1).toFloat())
        }
        val source = ElevationTileBuffer.Floats(input)
        val encoded = ElevationStorageCodec.encode(source, isFloat = false, tileWidth, tileHeight)

        // Non-trivial scale + offset must be recorded — confirms the packing path ran.
        assertTrue(encoded.tileScale > 0f, "Float→Int storage must compute a non-zero scale")
        assertEquals(min, encoded.tileOffset, "tileOffset must equal the per-tile min value")
        // Quantization step for the test range: (max - min) / 65535 ≈ 0.128.
        val maxQuantizationStep = (max - min) / 65535f * 2f
        assertTrue(encoded.tileScale <= maxQuantizationStep,
            "scale ${encoded.tileScale} too coarse — packing math is off")

        // Read it back; should be a FloatBuffer with values close to the original (within
        // one quantization step).
        val decoded = ElevationStorageCodec.decode(
            encoded.bytes, isFloat = false, encoded.tileScale, encoded.tileOffset,
        )
        assertTrue(decoded is ElevationTileBuffer.Floats,
            "PNG-Int16 with non-trivial (scale, offset) must decode as Floats so precision is preserved")
        val out = decoded.values
        assertEquals(input.size, out.size)
        for (i in input.indices) {
            val drift = abs(out[i] - input[i])
            assertTrue(drift <= encoded.tileScale,
                "pixel $i drift $drift exceeds quantization step ${encoded.tileScale}")
        }
    }

    @Test
    fun floatBuffer_int16_storage_null_sentinel_handled() {
        // Bug class: Float.MAX_VALUE is the engine's null-data sentinel; the packing
        // path must skip it during min/max computation so the live-value range packs
        // tightly into 16 bits (otherwise one null sentinel poisons the scale).
        val live = 200.0f
        val input = FloatArray(tileWidth * tileHeight) { i ->
            if (i == 0) Float.MAX_VALUE else live + i * 0.01f
        }
        val source = ElevationTileBuffer.Floats(input)
        val encoded = ElevationStorageCodec.encode(source, isFloat = false, tileWidth, tileHeight)
        // Scale should be derived from the live values only; Float.MAX_VALUE would
        // produce a scale ≈ Float.MAX_VALUE / 65535 ≈ 5e34. Anything that large means
        // the null sentinel leaked into the min/max sweep.
        assertTrue(encoded.tileScale < 1f,
            "Null sentinel leaked into min/max — scale ${encoded.tileScale} is way off")
    }

    @Test
    fun shortBuffer_float32_storage_widens_losslessly() {
        // Bug class: Int16 source + isFloat=true storage should just widen (every Int16
        // value is representable as Float32) and round-trip back exactly.
        val input = ShortArray(tileWidth * tileHeight) { i -> ((i * 7) % 1000 - 500).toShort() }
        val source = ElevationTileBuffer.Shorts(input)
        val encoded = ElevationStorageCodec.encode(source, isFloat = true, tileWidth, tileHeight)
        assertEquals(1f, encoded.tileScale)
        assertEquals(0f, encoded.tileOffset)

        val decoded = ElevationStorageCodec.decode(encoded.bytes, isFloat = true, 1f, 0f)
        assertTrue(decoded is ElevationTileBuffer.Floats)
        val out = decoded.values
        assertEquals(input.size, out.size)
        for (i in input.indices) {
            assertEquals(input[i].toFloat(), out[i], "pixel $i drifted in Int16→Float32 widen")
        }
    }

    @Test
    fun cached_tile_equality_is_value_based() {
        // Bug class: CachedTile is a `data class` over a ByteArray — the default
        // equals would compare by identity and break cache-hit assertions. Verify the
        // custom equals/hashCode does value comparison.
        val a = CachedTile(byteArrayOf(1, 2, 3, 4), tileScale = 0.5f, tileOffset = 100f)
        val b = CachedTile(byteArrayOf(1, 2, 3, 4), tileScale = 0.5f, tileOffset = 100f)
        val c = CachedTile(byteArrayOf(1, 2, 3, 5), tileScale = 0.5f, tileOffset = 100f)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotNull(a)
        assertTrue(a != c, "Different bytes must produce unequal CachedTile")
    }
}
