package earth.worldwind.layer.source

import earth.worldwind.geom.Sector
import earth.worldwind.layer.BulkFeatureLayer
import earth.worldwind.layer.cache.CachePolicy
import earth.worldwind.layer.cache.CachedBulkFeatureSource
import earth.worldwind.layer.cache.FeatureStore
import earth.worldwind.shape.Label
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlin.test.Test
import kotlin.test.assertEquals
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.layer.source.CachedFeatureRow

/**
 * End-to-end test for the GeoJSON cache cycle:
 *   1. Wrap a `GeoJsonBulkFeatureSource` in [CachedBulkFeatureSource] over an in-memory
 *      [FeatureStore] (simulating the attachCache stage).
 *   2. Load — rows are parsed from text AND persisted to the store.
 *   3. Construct a cache-only `CachedBulkFeatureSource` over the SAME store (simulating
 *      cross-launch reopen via `cm.openLayer<BulkFeatureLayer>(contentKey)`).
 *   4. Load again — renderables come back, identical in shape and count, without ever
 *      re-parsing the GeoJSON text.
 *
 * Together with the registry-existence assertion in `CachedLayerRegistryTest`, this pins
 * the GeoJSON-cache-opt-in contract added on top of step 4 of the unification.
 */
class GeoJsonCacheRoundTripTest {

    @Test
    fun first_load_writes_to_store_and_emits_renderables() = runTest {
        val store = InMemoryFeatureStore()
        val layer = BulkFeatureLayer(
            source = CachedBulkFeatureSource(GeoJsonBulkFeatureSource(SAMPLE), store),
        )
        layer.load()
        assertEquals(3, layer.toList().size, "three features → three renderables")
        assertEquals(3, store.bulkRows.size, "rows persisted to cache on first load")
    }

    @Test
    fun cache_only_reopen_replays_renderables_without_source_text() = runTest {
        // Phase 1 — attach + load, populating the store.
        val store = InMemoryFeatureStore()
        BulkFeatureLayer(
            source = CachedBulkFeatureSource(GeoJsonBulkFeatureSource(SAMPLE), store),
        ).also { it.load() }

        // Phase 2 — cross-launch reopen: cache-only source over the same store, no
        // GeoJSON text anywhere.
        val reopened = BulkFeatureLayer(
            source = CachedBulkFeatureSource(inner = null, store = store),
        )
        reopened.load()

        // Same renderable count + types as the first load.
        val rendered = reopened.toList()
        assertEquals(3, rendered.size)
        val labelTexts = rendered.filterIsInstance<Label>().map { it.text }
        assertEquals(listOf("a", "b", "c"), labelTexts, "feature names survive the cache round-trip in document order")
    }

    @Test
    fun empty_cache_with_null_inner_yields_empty_layer() = runTest {
        // Models cache eviction: store wiped, inner = null. Layer.load() must be a no-op
        // (empty), not a crash.
        val layer = BulkFeatureLayer(
            source = CachedBulkFeatureSource(inner = null, store = InMemoryFeatureStore()),
        )
        layer.load()
        assertEquals(0, layer.toList().size)
    }

    companion object {
        private val SAMPLE = """{"type":"FeatureCollection","features":[
            |{"type":"Feature","geometry":{"type":"Point","coordinates":[1.0,1.0]},"properties":{"name":"a"}},
            |{"type":"Feature","geometry":{"type":"Point","coordinates":[2.0,2.0]},"properties":{"name":"b"}},
            |{"type":"Feature","geometry":{"type":"Point","coordinates":[3.0,3.0]},"properties":{"name":"c"}}
        |]}""".trimMargin()
    }
}

/** Minimal in-memory [FeatureStore] for round-trip tests. Tile methods are unsupported —
 *  only bulk readAll / replaceAll / sizeBytes are exercised by [CachedBulkFeatureSource]
 *  in this test set. */
private class InMemoryFeatureStore : FeatureStore {
    override val cachePolicy = CachePolicy.UNBOUNDED
    val bulkRows = mutableListOf<CachedFeatureRow>()

    override suspend fun readAll(): Flow<CachedFeatureRow> =
        bulkRows.toList().asFlow()

    override suspend fun replaceAll(rows: Flow<CachedFeatureRow>) {
        bulkRows.clear()
        rows.toList().also { bulkRows.addAll(it) }
    }

    override suspend fun readTile(z: Int, x: Int, y: Int, sector: Sector?) = throw UnsupportedOperationException()
    override suspend fun writeTile(z: Int, x: Int, y: Int, rows: Flow<CachedFeatureRow>, sector: Sector?) =
        throw UnsupportedOperationException()
    override suspend fun deleteTile(z: Int, x: Int, y: Int) = throw UnsupportedOperationException()

    override suspend fun sizeBytes(): Long =
        bulkRows.sumOf { (it.properties?.length ?: 0).toLong() + 32L }
}

private fun BulkFeatureLayer.toList() = (this as RenderableLayer).toList()

private fun runTest(body: suspend () -> Unit) {
    kotlinx.coroutines.test.runTest { body() }
}
