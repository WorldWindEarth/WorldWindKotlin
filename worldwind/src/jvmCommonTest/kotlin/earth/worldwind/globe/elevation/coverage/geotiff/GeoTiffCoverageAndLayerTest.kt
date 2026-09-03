package earth.worldwind.globe.elevation.coverage.geotiff

import earth.worldwind.formats.geotiff.ByteArrayTiffDataSource
import earth.worldwind.formats.geotiff.GeoTiffDataset
import earth.worldwind.formats.geotiff.TiffTestFixtures
import earth.worldwind.layer.geotiff.GeoTiffImageLayer
import earth.worldwind.render.image.ImageTile
import earth.worldwind.util.LevelSet
import kotlinx.coroutines.runBlocking
import java.nio.ShortBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end wiring tests: a synthetic GeoTIFF through the pyramid the engine actually
 * renders — tile matrix and elevation posts on the coverage side, level set and per-tile
 * image sources on the layer side.
 */
class GeoTiffCoverageAndLayerTest {
    private val pixelSize = 0.001
    private val size = 512

    private fun bytes() = TiffTestFixtures.tiledTiff(
        width = size, height = size, tileWidth = 16, tileHeight = 16,
        pixelScale = doubleArrayOf(pixelSize, pixelSize, 0.0),
        tiePoint = doubleArrayOf(0.0, 0.0, 0.0, 10.0, 50.0, 0.0),
        overviewFactors = intArrayOf(2, 4),
    ) { x, y, _ -> (x + y * 100).toDouble() }

    @Test
    fun coverageSpansTheRasterAndServesItsPosts() {
        val coverage = assertNotNull(
            GeoTiffElevationCoverage.create(ByteArrayTiffDataSource(bytes()), "test DEM")
        )
        assertEquals("test DEM", coverage.displayName)
        assertEquals(10.0, coverage.sector.minLongitude.inDegrees, 1e-9)
        assertEquals(50.0 - size * pixelSize, coverage.sector.minLatitude.inDegrees, 1e-9)

        // The pyramid covers the raster's own extent, so its level 0 sector is the data's.
        val matrix = coverage.tileMatrixSet.entries[0]
        assertEquals(coverage.sector, matrix.sector)
        assertTrue(
            coverage.tileMatrixSet.maxResolution.inDegrees <= pixelSize,
            "pyramid descends to the raster's native resolution"
        )

        // Pull one tile the way the coverage does and check the posts land where they belong.
        val factory = GeoTiffElevationSourceFactory(coverage.dataset)
        val source = factory.createElevationSource(matrix, 0, 0)
        val buffer = runBlocking { source.asElevationDataFactory().fetchElevationData() }
        val posts = assertNotNull(buffer as? ShortBuffer)
        assertEquals(matrix.tileWidth * matrix.tileHeight, posts.remaining())
        // Level 0 holds the whole raster in 256 posts: the north-west post samples the
        // raster's north-west corner, whose ramp value is 0.
        assertEquals(0.toShort(), posts.get(0))
        assertTrue(posts.get(matrix.tileWidth * matrix.tileHeight - 1) > 0, "south-east post carries data")
        coverage.close()
    }

    @Test
    fun layerBuildsAPyramidOverTheImagery() {
        val layer = assertNotNull(GeoTiffImageLayer.create(ByteArrayTiffDataSource(bytes()), "test imagery"))
        assertEquals("test imagery", layer.displayName)
        assertEquals("GeoTIFF", layer.type)
        val surfaceImage = assertNotNull(layer.tiledSurfaceImage)
        val levelSet: LevelSet = surfaceImage.levelSet
        assertEquals(layer.sector, levelSet.sector)
        // Reaching 0.001° texels from a 90° first level takes ten halvings.
        assertTrue(levelSet.numLevels >= 9, "pyramid reaches native resolution")
        assertTrue(
            levelSet.lastLevel.tileDelta.latitude.inDegrees / levelSet.tileHeight <= pixelSize,
            "finest level resolves individual source pixels"
        )

        // Every tile the engine asks for carries an image source keyed to its own address.
        val level = levelSet.lastLevel
        val tile = surfaceImage.tileFactory.createTile(layer.sector, level, 3, 7) as ImageTile
        val other = surfaceImage.tileFactory.createTile(layer.sector, level, 3, 8) as ImageTile
        assertNotNull(tile.imageSource)
        assertEquals(
            tile.imageSource, surfaceImage.tileFactory.createTile(layer.sector, level, 3, 7).let {
                (it as ImageTile).imageSource
            },
            "the same tile address maps to the same texture-cache key"
        )
        assertTrue(tile.imageSource != other.imageSource, "different tiles are different textures")
        layer.close()
    }

    @Test
    fun refusesRastersItCannotGeoreference() {
        // No tie point and no model transformation: nothing to place the pixels with.
        val bytes = TiffTestFixtures.tiledTiff(
            width = 32, height = 32, tileWidth = 16, tileHeight = 16,
            pixelScale = DoubleArray(0), tiePoint = DoubleArray(0),
        ) { x, y, _ -> (x + y).toDouble() }
        assertTrue(GeoTiffDataset.open(ByteArrayTiffDataSource(bytes)) == null)
    }
}
