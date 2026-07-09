package earth.worldwind.layer.cache
import earth.worldwind.layer.source.CachedFeatureRow
import earth.worldwind.layer.source.CachedGeometry

import earth.worldwind.formats.gpkg.GeoPackage
import earth.worldwind.formats.gpkg.GpkgContent
import earth.worldwind.geom.Sector
import earth.worldwind.formats.gpkg.GpkgFeatureRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import mil.nga.sf.Geometry

/**
 * GeoPackage-backed [FeatureStore]. One instance binds to one `data_type='features'` table
 * (identified by [content]).
 *
 * The features table is a flat, standard GeoPackage features layer (geometry + properties JSON +
 * an optional `feature_uid`), spatially indexed by RTree. Tile *coverage* — which `(z, x, y)`
 * regions were fetched, their freshness, and the empty/negative-cache flag — lives in the separate
 * `ww_feature_coverage` side table, so this layer reads cleanly in any external GIS. Tile reads
 * resolve through coverage, then serve the tile's bbox of features.
 */
class GpkgFeatureStore(
    private val geoPackage: GeoPackage,
    private val content: GpkgContent,
    override val cachePolicy: CachePolicy = CachePolicy.UNBOUNDED,
) : FeatureStore, CachedSourceInfoProvider {

    override val cacheInfo: CachedSourceInfo
        get() = CachedSourceInfo(contentKey = content.tableName, contentPath = geoPackage.pathName)

    override suspend fun readAll(): Flow<CachedFeatureRow> = flow {
        for ((geom, props) in geoPackage.readCachedFeatures(content)) {
            val cached = geom.toCached() ?: continue
            emit(CachedFeatureRow(cached, props))
        }
    }.flowOn(dbDispatcher)

    override suspend fun readBySector(sector: Sector): Flow<CachedFeatureRow> = flow {
        val rows = geoPackage.readFeaturesInBbox(
            content,
            sector.minLongitude.inDegrees, sector.minLatitude.inDegrees,
            sector.maxLongitude.inDegrees, sector.maxLatitude.inDegrees,
        )
        for ((geom, props) in rows) {
            val cached = geom.toCached() ?: continue
            emit(CachedFeatureRow(cached, props))
        }
    }.flowOn(dbDispatcher)

    override suspend fun replaceAll(rows: Flow<CachedFeatureRow>) {
        val payload = mutableListOf<Pair<Geometry, String?>>()
        rows.collect { row ->
            val geom = row.geometry?.toSf() ?: return@collect
            payload += geom to row.properties
        }
        withContext(dbDispatcher) { geoPackage.replaceCachedFeatures(content, payload) }
    }

    override suspend fun readTile(z: Int, x: Int, y: Int, sector: Sector?): Flow<CachedFeatureRow>? =
        withContext(dbDispatcher) {
            // Coverage — not the features themselves — records whether the tile was fetched: absent row
            // = miss (null), is_empty = negative cache. A present, non-empty tile serves its bbox of features.
            val coverage = geoPackage.readFeatureCoverage(content, z, x, y) ?: return@withContext null
            if (coverage.isEmpty) return@withContext emptyFlow()
            // readFeatureTile materializes the rows on dbDispatcher; the toCached conversion stays lazy
            // (runs in the collector's context) so only the SQLite work is serialized here.
            geoPackage.readFeatureTile(content, z, x, y, sector).asSequence()
                .mapNotNull { (geom, props) -> geom.toCached()?.let { CachedFeatureRow(it, props) } }
                .asFlow()
        }

    override suspend fun readTileLastModified(z: Int, x: Int, y: Int): Long? =
        withContext(dbDispatcher) { geoPackage.readFeatureCoverage(content, z, x, y)?.validatedAt }

    override suspend fun writeTile(z: Int, x: Int, y: Int, rows: Flow<CachedFeatureRow>, sector: Sector?) {
        // Empty flow → empty payload → writeFeatureTile clears the region + records is_empty coverage.
        val payload = rows.toList().mapNotNull { row ->
            row.geometry?.toSf()?.let { GpkgFeatureRow(it, row.properties, extractFeatureUid(row.properties)) }
        }
        withContext(dbDispatcher) {
            geoPackage.writeFeatureTile(content, z, x, y, payload, sector)
            // Per-put eviction trigger; row count keeps multi-row inserts at the right rate.
            geoPackage.notifyFeatureInsert(content, cachePolicy, payload.size)
        }
    }

    override suspend fun deleteTile(z: Int, x: Int, y: Int) {
        withContext(dbDispatcher) { geoPackage.writeFeatureTile(content, z, x, y, emptyList()) }
    }

    /** The stable natural key for upsert/dedup — the OSM-style `id` field in the [properties] JSON
     *  (e.g. `"way/123"`), or null when the source provides none (then writes bbox-replace). */
    private fun extractFeatureUid(properties: String?): String? {
        if (properties == null) return null
        val obj = runCatching { Json.parseToJsonElement(properties) }.getOrNull() as? JsonObject ?: return null
        return (obj["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    override suspend fun evict() {
        if (cachePolicy.isUnbounded || geoPackage.isReadOnly) return
        withContext(dbDispatcher) { geoPackage.evictFeatures(content, cachePolicy) }
    }

    // Goes through this store's own [content] instance so the in-memory copy stays coherent with the row.
    override suspend fun setBoundingSector(sector: Sector) = geoPackage.setBoundingSector(content, sector)

    override suspend fun sizeBytes(): Long =
        withContext(dbDispatcher) { geoPackage.readFeaturesDataSize(content.tableName) }

    private companion object {
        /**
         * One thread for ALL GeoPackage feature I/O. SQLite is single-writer, and running reads +
         * writes concurrently from the fetch pool ([Dispatchers.Default]) produced a WAL-checkpoint +
         * connection-pool + mil.nga.geopackage-monitor lock convoy that blocked the dispatcher for
         * ~35 s in profiling, starving tile decode. Serialising removes the contention outright and
         * keeps blocking SQLite off the CPU dispatcher (so decode/tessellation keep their threads).
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        private val dbDispatcher = Dispatchers.IO.limitedParallelism(1)
    }
}
