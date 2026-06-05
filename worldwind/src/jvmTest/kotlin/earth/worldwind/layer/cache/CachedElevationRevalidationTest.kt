package earth.worldwind.layer.cache

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Sector
import earth.worldwind.geom.TileMatrixSet
import earth.worldwind.layer.source.TileBlob
import earth.worldwind.layer.source.TileSource
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The JS/iOS elevation cache shares its revalidation logic in commonMain
 * [CachedElevationSourceFactory]; exercise the conditional-GET path on JVM with fakes.
 */
class CachedElevationRevalidationTest {

    private class FakeBackend : ElevationStoreBackend {
        var bumped = 0
        var written = 0
        override val isReadOnly = false
        override val cacheInfo = CachedSourceInfo(contentKey = "dem", contentPath = "mem")
        override suspend fun readTile(z: Int, x: Int, y: Int): CachedTile? = null
        override suspend fun writeTile(
            z: Int, x: Int, y: Int, bytes: ByteArray, tileScale: Float, tileOffset: Float,
            etag: String?, lastModified: String?,
        ) { written++ }
        override suspend fun bumpValidatedAt(z: Int, x: Int, y: Int) { bumped++ }
    }

    private fun factory(backend: ElevationStoreBackend, source: TileSource) = CachedElevationSourceFactory(
        backend = backend,
        networkSource = source,
        networkDecoder = { _, _ -> null }, // unreached on the 304 path
        outputFormat = "application/bil16",
        isFloat = false,
        tileMatrixSet = TileMatrixSet.fromTilePyramid(
            Sector.fromDegrees(-90.0, -180.0, 180.0, 360.0), 2, 1, 256, 256, Angle.fromDegrees(0.3),
        ),
    )

    @Test
    fun conditionalGet304BumpsWithoutRewrite() = runBlocking {
        var sentEtag: String? = null
        val source = object : TileSource {
            override suspend fun fetchTile(
                z: Int, x: Int, y: Int, previousEtag: String?, previousLastModified: String?,
            ): TileBlob? {
                sentEtag = previousEtag
                return null // 304 Not Modified
            }
        }
        val backend = FakeBackend()
        val changed = factory(backend, source).conditionalRevalidate(0, 0, 0, "v1", null)

        assertEquals("v1", sentEtag, "stored ETag sent as the conditional validator")
        assertFalse(changed, "304 must not rewrite bytes or trigger a redraw")
        assertEquals(1, backend.bumped, "304 bumps the freshness stamp")
        assertEquals(0, backend.written, "304 writes no bytes")
    }
}
