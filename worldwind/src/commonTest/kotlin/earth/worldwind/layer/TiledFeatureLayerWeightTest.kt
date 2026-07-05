package earth.worldwind.layer

import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.layer.mvt.MvtBatchedLineTile
import earth.worldwind.layer.mvt.MvtBatchedPolygonTile
import earth.worldwind.layer.source.CachedFeatureRow
import earth.worldwind.layer.source.TiledFeatureSource
import earth.worldwind.render.Renderable
import earth.worldwind.shape.Placemark
import earth.worldwind.shape.ShapeAttributes
import kotlinx.coroutines.flow.Flow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Validates the GPU-byte accounting that backs [TiledFeatureLayer]'s byte-weighted eviction and the
 * per-frame upload throttle — the numbers that keep the resident tile set from OOMing the GPU driver.
 */
class TiledFeatureLayerWeightTest {

    private val sector = Sector.fromDegrees(0.0, 0.0, 1.0, 1.0)
    private val attrs = ShapeAttributes()

    private fun polygonTile(outer: DoubleArray) = MvtBatchedPolygonTile(
        listOf(MvtBatchedPolygonTile.BatchFeature(outer = outer, holes = emptyList(), attributes = attrs)),
        sector,
    )

    private fun lineTile(coords: DoubleArray) = MvtBatchedLineTile(
        listOf(MvtBatchedLineTile.BatchLineFeature(coords = coords, attributes = attrs)),
        sector,
    )

    @Test fun polygonByteEstimatePreAssemblyFromVertexCount() {
        // Square: 4 vertices (8 doubles). Pre-assembly estimate = verts * (STRIDE 3 + 3 idx) * 4 bytes.
        val tile = polygonTile(doubleArrayOf(0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0))
        assertEquals(4 * (3 + 3) * 4, tile.gpuByteEstimate)
    }

    @Test fun emptyPolygonTileWeighsNothing() {
        assertEquals(0, polygonTile(DoubleArray(0)).gpuByteEstimate)
    }

    @Test fun lineByteEstimateSwitchesToExactAfterAssembly() {
        val tile = lineTile(doubleArrayOf(0.0, 0.0, 1.0, 0.0, 1.0, 1.0))
        val preAssembly = tile.gpuByteEstimate
        assertTrue(preAssembly > 0, "pre-assembly estimate should be positive for a 3-point line")
        tile.assemble(null) // line tiles assemble without a globe (degree-space geometry)
        val postAssembly = tile.gpuByteEstimate
        assertTrue(postAssembly > 0, "assembled line tile should own VBO/EBO bytes")
        // Post-assembly the estimate is the exact array footprint — a multiple of 4 (Float/Int bytes).
        assertEquals(0, postAssembly % 4)
    }

    @Test fun contentWeightSumsBatchedTilesPlusNominalForOthers() {
        val layer = WeighLayer()
        try {
            val poly = polygonTile(doubleArrayOf(0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0))
            val line = lineTile(doubleArrayOf(0.0, 0.0, 1.0, 1.0))

            // Batched tiles contribute exactly their measured GPU footprint.
            assertEquals(poly.gpuByteEstimate, layer.weigh(listOf(poly)))
            assertEquals(poly.gpuByteEstimate + line.gpuByteEstimate, layer.weigh(listOf(poly, line)))

            // A non-batched renderable (Placemark) adds a positive nominal charge on top.
            val withPlacemark = layer.weigh(listOf(poly, Placemark(Position.fromDegrees(0.0, 0.0, 0.0))))
            assertTrue(withPlacemark > poly.gpuByteEstimate, "non-batched renderable should be charged too")
        } finally {
            layer.close()
        }
    }

    /** Exposes the protected [TiledFeatureLayer.contentWeight] for assertion. */
    private class WeighLayer : TiledFeatureLayer(source = NoopSource) {
        fun weigh(content: List<Renderable>) = contentWeight(content)
    }

    private object NoopSource : TiledFeatureSource {
        override suspend fun fetchTile(z: Int, x: Int, y: Int, sector: Sector): Flow<CachedFeatureRow>? = null
    }
}
