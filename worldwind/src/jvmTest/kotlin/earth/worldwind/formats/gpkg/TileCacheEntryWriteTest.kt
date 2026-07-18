package earth.worldwind.formats.gpkg

import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Location
import earth.worldwind.geom.Sector
import earth.worldwind.geom.TileMatrixSet
import earth.worldwind.util.LevelSet
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Round-trips the single-transaction cache write ([GeoPackage.writeTileCacheEntry]) against the
 * per-statement read APIs, for both the raster (blob + freshness) and elevation (blob + gridded
 * ancillary + freshness) layouts.
 */
class TileCacheEntryWriteTest {
    private val file = File.createTempFile("tile-cache-entry", ".gpkg").also { it.delete() }

    @AfterTest fun cleanup() { file.delete() }

    private fun fullSphereLevelSet() = LevelSet(
        Sector().setFullSphere(), Sector().setFullSphere(),
        Location(90.0.degrees, 90.0.degrees), numLevels = 3, tileWidth = 256, tileHeight = 256,
    )

    @Test
    fun rasterEntryRoundTripsAndKeepsRowIdOnRewrite() = runBlocking {
        val gpkg = GeoPackage(file.absolutePath, isReadOnly = false)
        try {
            val content = gpkg.setupTilesContent("cache_tiles", fullSphereLevelSet())
            val bytes = byteArrayOf(1, 2, 3, 4)
            val id = gpkg.writeTileCacheEntry(
                content, 1, 2, 3, bytes,
                etag = "W/\"abc\"", httpLastModified = "Mon, 01 Jan 2024 00:00:00 GMT", validatedAt = 12345L,
            )
            val row = assertNotNull(gpkg.readTileUserData(content, 1, 2, 3))
            assertEquals(id, row.id)
            assertContentEquals(bytes, row.tileData)
            val reval = assertNotNull(gpkg.readTileRevalidation(content, id))
            assertEquals("W/\"abc\"", reval.etag)
            assertEquals(12345L, reval.validatedAt)

            // Rewrite reuses the row (stable tpudt_id) and refreshes blob + freshness
            val rewritten = byteArrayOf(9, 8)
            val id2 = gpkg.writeTileCacheEntry(content, 1, 2, 3, rewritten, validatedAt = 99999L)
            assertEquals(id, id2)
            assertContentEquals(rewritten, assertNotNull(gpkg.readTileUserData(content, 1, 2, 3)).tileData)
            val reval2 = assertNotNull(gpkg.readTileRevalidation(content, id))
            assertEquals(99999L, reval2.validatedAt)
            assertNull(reval2.etag)
        } finally {
            gpkg.shutdown()
        }
    }

    @Test
    fun elevationEntryWritesGriddedAncillaryRow() = runBlocking {
        val gpkg = GeoPackage(file.absolutePath, isReadOnly = false)
        try {
            val tms = TileMatrixSet.fromTilePyramid(
                Sector().setFullSphere(), 4, 2, 256, 256, 0.1.degrees,
            )
            val content = gpkg.setupGriddedCoverageContent("cache_elevation", tms)
            val id = gpkg.writeTileCacheEntry(
                content, 0, 1, 1, byteArrayOf(5, 6, 7), validatedAt = 42L,
                griddedScale = 0.5f, griddedOffset = 10.0f,
            )
            val row = assertNotNull(gpkg.readTileUserData(content, 0, 1, 1))
            assertEquals(id, row.id)
            val gridded = assertNotNull(gpkg.readGriddedTile(content, row))
            assertEquals(0.5, gridded.scale, 1e-6)
            assertEquals(10.0, gridded.offset, 1e-6)
            assertEquals(42L, assertNotNull(gpkg.readTileRevalidation(content, id)).validatedAt)

            // Rewrite replaces the gridded attributes in place
            gpkg.writeTileCacheEntry(
                content, 0, 1, 1, byteArrayOf(1), validatedAt = 43L,
                griddedScale = 2.0f, griddedOffset = -5.0f,
            )
            val gridded2 = assertNotNull(gpkg.readGriddedTile(content, assertNotNull(gpkg.readTileUserData(content, 0, 1, 1))))
            assertEquals(2.0, gridded2.scale, 1e-6)
            assertEquals(-5.0, gridded2.offset, 1e-6)
        } finally {
            gpkg.shutdown()
        }
    }
}
