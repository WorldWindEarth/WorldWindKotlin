package earth.worldwind.layer.mvt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MvtGeometryTest {

    /**
     * Encode a CommandInteger: `(id & 0x7) | (count << 3)`.
     */
    private fun cmd(id: Int, count: Int): Int = (id and 0x7) or (count shl 3)
    /** Encode a parameter with zig-zag (the on-wire form). */
    private fun zz(v: Int): Int = (v shl 1) xor (v shr 31)

    @Test fun decodesPolygonExteriorAsCcwLatLon() {
        // A 1024×1024 axis-aligned square in tile (z=0, x=0, y=0), encoded clockwise in
        // tile space → spec says exterior. Corners: (1024, 1024), (3072, 1024), (3072, 3072),
        // (1024, 3072), close.
        val geometry = intArrayOf(
            cmd(1, 1), zz(1024), zz(1024),                          // MoveTo (1024, 1024)
            cmd(2, 3), zz(2048), zz(0), zz(0), zz(2048), zz(-2048), zz(0), // LineTo right, down, left
            cmd(7, 0),                                              // ClosePath
        )
        val feature = MvtFeature(id = 1, type = MvtGeometryType.POLYGON, tags = intArrayOf(), geometry = geometry)
        val rings = MvtGeometry.decodePolygons(feature, z = 0, x = 0, y = 0, extent = 4096)
        assertEquals(1, rings.size)
        val outer = rings.single().outer
        assertEquals(4, outer.size)
        // The square spans 1024..3072 of 4096 in tile-x ⇒ -90°..+90° lon. y likewise but
        // y is inverted by Mercator so the LAT range varies non-linearly; we don't assert
        // exact lats here, just bounding correctness.
        val lons = outer.map { it.longitude.inDegrees }
        assertTrue(lons.all { it in -90.0..90.0 }, "outer ring longitudes within tile bounds, got $lons")
    }

    @Test fun decodesPolygonWithHole() {
        // Outer CW in tile space (positive area in MVT's spec) + inner CCW (negative area).
        // Outer: small square (10,10)→(110,10)→(110,110)→(10,110).
        // Inner: tiny square (40,40)→(40,60)→(60,60)→(60,40) — same handedness check.
        val geometry = intArrayOf(
            // Outer ring
            cmd(1, 1), zz(10), zz(10),
            cmd(2, 3), zz(100), zz(0), zz(0), zz(100), zz(-100), zz(0),
            cmd(7, 0),
            // Inner ring — needs OPPOSITE winding to be detected as a hole. From the outer's
            // closing point, MoveTo back into the interior at (40,40). Delta from cursor.
            // Cursor after outer's close is at last MoveTo position (10,10)? No — ClosePath
            // doesn't update the cursor; it stays at the last LineTo position (10, 10).
            cmd(1, 1), zz(30), zz(30),                                    // MoveTo (40, 40)
            cmd(2, 3), zz(0), zz(20), zz(20), zz(0), zz(0), zz(-20),      // CCW in tile space
            cmd(7, 0),
        )
        val feature = MvtFeature(id = 2, type = MvtGeometryType.POLYGON, tags = intArrayOf(), geometry = geometry)
        val rings = MvtGeometry.decodePolygons(feature, z = 0, x = 0, y = 0, extent = 4096)
        assertEquals(1, rings.size, "one polygon with one hole")
        assertEquals(1, rings.single().holes.size, "the hole was attached to its enclosing exterior")
    }

    @Test fun decodesLinesWithMultipleMoveTos() {
        // Two separate polylines in one feature: (10,10)→(20,10) and (100,100)→(110,100).
        val geometry = intArrayOf(
            cmd(1, 1), zz(10), zz(10),  cmd(2, 1), zz(10), zz(0),         // line 1
            cmd(1, 1), zz(80), zz(90),  cmd(2, 1), zz(10), zz(0),         // line 2 (delta from end of line 1)
        )
        val feature = MvtFeature(id = 3, type = MvtGeometryType.LINESTRING, tags = intArrayOf(), geometry = geometry)
        val lines = MvtGeometry.decodeLines(feature, z = 0, x = 0, y = 0, extent = 4096)
        assertEquals(2, lines.size)
        assertEquals(2, lines[0].size)
        assertEquals(2, lines[1].size)
    }

    @Test fun decodesMultiPoint() {
        // POINT geometry: a single MoveTo with count > 1 means several independent points.
        // (10,10), (20,30), (15,15)  — using cumulative deltas:
        // MoveTo(3) | ZZ(10), ZZ(10) | ZZ(10), ZZ(20) | ZZ(-5), ZZ(-15)
        val geometry = intArrayOf(
            cmd(1, 3),
            zz(10), zz(10),
            zz(10), zz(20),
            zz(-5), zz(-15),
        )
        val feature = MvtFeature(id = 4, type = MvtGeometryType.POINT, tags = intArrayOf(), geometry = geometry)
        val pts = MvtGeometry.decodePoints(feature, z = 0, x = 0, y = 0, extent = 4096)
        assertEquals(3, pts.size)
    }

    @Test fun unprojectIsInverseOfSlippyForward() {
        // Round-trip a tile-corner: tile (5, 7, 11) NW corner has px=0, py=0; tile_x =
        // 5.0, tile_y = 7.0. Plugging in matches the canonical OpenStreetMap slippy formula.
        val z = 11
        val tileX = 5
        val tileY = 7
        val p = MvtGeometry.unproject(z, tileX, tileY, extent = 4096, px = 0, py = 0)
        // Expected longitude: tileX / 2^z * 360 - 180
        val expectedLon = tileX.toDouble() / (1 shl z) * 360.0 - 180.0
        assertEquals(expectedLon, p.longitude.inDegrees, absoluteTolerance = 1e-9)
    }
}
