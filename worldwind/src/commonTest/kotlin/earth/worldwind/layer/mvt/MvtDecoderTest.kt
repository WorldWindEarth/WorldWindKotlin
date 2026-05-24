package earth.worldwind.layer.mvt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MvtDecoderTest {

    /**
     * Hand-crafted minimal valid MVT: one layer "test" (version 2, extent 4096) with one
     * POINT feature at tile-local (5, 7) and one property `key=value`.
     *
     * Hand-assembled because pulling in a protobuf encoder just for tests would defeat the
     * "no codegen runtime" goal of the implementation. Each line below documents the
     * field-tag → wire-type → length/value sequence; the comment is the source of truth.
     */
    private val fixture: ByteArray by lazy {
        // Build inner-most messages first, then wrap.
        // ----- Value: { string_value = "value" } → field 1, wt 2, len 5
        val value = byteArrayOf(0x0A, 5) + "value".encodeToByteArray()

        // ----- Feature {
        //   id = 1                    → field 1, wt 0 → tag 0x08, varint 1
        //   tags = [0, 0] (packed)    → field 2, wt 2 → tag 0x12, len 2, bytes [0, 0]
        //   type = POINT (1)          → field 3, wt 0 → tag 0x18, varint 1
        //   geometry = [9, 10, 14]    → field 4, wt 2 → tag 0x22, len 3, bytes [9, 10, 14]
        //       (MoveTo(1) | ZZ(5)=10 | ZZ(7)=14 → cursor lands on (5, 7))
        // }
        val feature = byteArrayOf(
            0x08, 1,
            0x12, 2, 0, 0,
            0x18, 1,
            0x22, 3, 9, 10, 14,
        )

        // ----- Layer {
        //   version = 2               → field 15, wt 0 → tag 0x78, varint 2
        //   name = "test"             → field 1,  wt 2 → tag 0x0A, len 4, "test"
        //   features = [feature]      → field 2,  wt 2 → tag 0x12, len N, payload
        //   keys = ["key"]            → field 3,  wt 2 → tag 0x1A, len 3, "key"
        //   values = [value]          → field 4,  wt 2 → tag 0x22, len M, payload
        //   extent = 4096             → field 5,  wt 0 → tag 0x28, varint 4096 (2 bytes)
        // }
        val layer =
            byteArrayOf(0x78.toByte(), 2) +                                   // version
            byteArrayOf(0x0A, 4) + "test".encodeToByteArray() +               // name
            byteArrayOf(0x12, feature.size.toByte()) + feature +              // features
            byteArrayOf(0x1A, 3) + "key".encodeToByteArray() +                // keys
            byteArrayOf(0x22, value.size.toByte()) + value +                  // values
            byteArrayOf(0x28, 0x80.toByte(), 0x20)                            // extent = 4096

        // ----- Tile { layers = [layer] } → field 3, wt 2 → tag 0x1A, len, payload
        byteArrayOf(0x1A, layer.size.toByte()) + layer
    }

    @Test fun decodesOneLayer() {
        val tile = MvtDecoder.decode(fixture)
        assertEquals(1, tile.layers.size)
        val layer = tile.layers.single()
        assertEquals("test", layer.name)
        assertEquals(2, layer.version)
        assertEquals(4096, layer.extent)
        assertEquals(listOf("key"), layer.keys)
        assertEquals(listOf<Any?>("value"), layer.values)
    }

    @Test fun decodesFeatureFields() {
        val tile = MvtDecoder.decode(fixture)
        val feature = tile.layers.single().features.single()
        assertEquals(1L, feature.id)
        assertEquals(MvtGeometryType.POINT, feature.type)
        assertEquals(listOf(0, 0), feature.tags.toList())
        assertEquals(listOf(9, 10, 14), feature.geometry.toList())
    }

    @Test fun togglesPropertiesFromTagDictionary() {
        val tile = MvtDecoder.decode(fixture)
        val layer = tile.layers.single()
        val feature = layer.features.single()
        val props = feature.toProperties(layer)
        assertEquals(mapOf<String, Any?>("key" to "value"), props)
    }

    @Test fun decodesGeometryToPoint() {
        // Feature's geometry was MoveTo(1) with ZZ(5)=10, ZZ(7)=14 → tile-local (5, 7).
        val tile = MvtDecoder.decode(fixture)
        val feature = tile.layers.single().features.single()
        val pts = MvtGeometry.decodePoints(feature, z = 0, x = 0, y = 0, extent = 4096)
        assertEquals(1, pts.size)
        val p = pts.single()
        // Tile (0,0,0) covers the whole world. (5, 7) of 4096 is tiny — lon ≈ -180 + (5/4096)*360
        val expectedLon = -180.0 + (5.0 / 4096.0) * 360.0
        assertEquals(expectedLon, p.longitude.inDegrees, absoluteTolerance = 1e-9)
        // y=7 of 4096 in tile 0 is very near the top of the Mercator world (lat ≈ MAX_MERCATOR_LAT).
        assertTrue(p.latitude.inDegrees > 84.0)
    }

    @Test fun unprojectMatchesSlippyMath() {
        // At zoom 2, the tile (1, 1) covers the NW quadrant of the southern half plus the
        // northwestern quarter of the lower hemisphere. Tile-NW corner (px=0, py=0):
        //   lon = -90, lat = +66.51326044311186 (north edge of tile)
        // We test the centre to dodge corner edge cases.
        val centre = MvtGeometry.unproject(z = 2, x = 1, y = 1, extent = 4096, px = 2048, py = 2048)
        // Expected: at tile (1.5, 1.5) of 4 tiles → mercator (0.375, 0.375)
        // lon = 0.375*360 - 180 = -45
        assertEquals(-45.0, centre.longitude.inDegrees, absoluteTolerance = 1e-9)
        // lat = atan(sinh(π * (1 - 2*0.375))) * 180/π  → roughly +40.98
        assertTrue(centre.latitude.inDegrees in 40.0..42.0)
    }
}
