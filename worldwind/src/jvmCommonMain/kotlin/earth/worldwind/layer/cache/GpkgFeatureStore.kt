package earth.worldwind.layer.cache
import earth.worldwind.layer.source.CachedFeatureRow
import earth.worldwind.layer.source.CachedGeometry

import earth.worldwind.ogc.gpkg.GeoPackage
import earth.worldwind.ogc.gpkg.GpkgContent
import earth.worldwind.ogc.gpkg.GpkgFeatureRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import mil.nga.sf.Geometry
import mil.nga.sf.LineString
import mil.nga.sf.Point
import mil.nga.sf.Polygon

/**
 * GeoPackage-backed [FeatureStore]. One instance binds to one `data_type='features'` table
 * (identified by [content]). Supports both bulk rows (`z`/`x`/`y` IS NULL — WFS, Shapefile)
 * and tile-addressed rows (OsmBuildings) in the same table.
 *
 * Tile-addressed rows use a sentinel `(z, x, y, NULL, NULL)` to mean "tile cached with zero
 * features". On read, that row turns into an empty flow (distinct from `null`, which means
 * the tile isn't cached at all).
 */
class GpkgFeatureStore(
    private val geoPackage: GeoPackage,
    private val content: GpkgContent,
    override val evictionPolicy: CacheEvictionPolicy = CacheEvictionPolicy.UNBOUNDED,
) : FeatureStore, CachedSourceInfoProvider {

    override val cacheInfo: CachedSourceInfo
        get() = CachedSourceInfo(contentKey = content.tableName, contentPath = geoPackage.pathName)

    override suspend fun readAll(): Flow<CachedFeatureRow> = flow {
        for ((geom, props) in geoPackage.readCachedFeatures(content)) {
            val cached = geom.toCached() ?: continue
            emit(CachedFeatureRow(cached, props))
        }
    }

    override suspend fun replaceAll(rows: Flow<CachedFeatureRow>) {
        val payload = mutableListOf<Pair<Geometry, String?>>()
        rows.collect { row ->
            val geom = row.geometry?.toSf() ?: return@collect
            payload += geom to row.properties
        }
        geoPackage.replaceCachedFeatures(content, payload)
    }

    override suspend fun readTile(z: Int, x: Int, y: Int): Flow<CachedFeatureRow>? {
        val rows = geoPackage.readFeatureTile(content, z, x, y)
        if (rows.isEmpty()) return null                          // tile not cached
        if (rows.size == 1 && rows[0].geometry == null) return emptyFlow()  // negative cache
        return rows.asSequence()
            .mapNotNull { row -> row.geometry?.toCached()?.let { CachedFeatureRow(it, row.properties) } }
            .asFlow()
    }

    override suspend fun writeTile(z: Int, x: Int, y: Int, rows: Flow<CachedFeatureRow>) {
        val materialised = rows.toList()
        val payload = if (materialised.isEmpty()) {
            // Negative-cache sentinel — "fetched, server returned nothing for this tile".
            listOf(GpkgFeatureRow(geometry = null, properties = null))
        } else materialised.mapNotNull { row ->
            row.geometry?.toSf()?.let { GpkgFeatureRow(it, row.properties) }
        }
        geoPackage.writeFeatureTile(content, z, x, y, payload)
    }

    override suspend fun deleteTile(z: Int, x: Int, y: Int) {
        geoPackage.writeFeatureTile(content, z, x, y, emptyList())
    }

    override suspend fun evict() {
        if (evictionPolicy.isUnbounded || geoPackage.isReadOnly) return
        geoPackage.evictFeatures(content, evictionPolicy)
    }

    override suspend fun sizeBytes(): Long = geoPackage.readFeaturesDataSize(content.tableName)

    // --- mil.nga.sf ↔ CachedGeometry plumbing — same as GpkgFeatureCacheFactory. -----------

    private fun Geometry.toCached(): CachedGeometry? = when (this) {
        is Point -> CachedGeometry.Point(x, y, z)
        is LineString -> CachedGeometry.LineString(points.map { CachedGeometry.Point(it.x, it.y, it.z) })
        is Polygon -> CachedGeometry.Polygon(rings.orEmpty().map { ring ->
            CachedGeometry.LineString(ring.points.map { CachedGeometry.Point(it.x, it.y, it.z) })
        })
        else -> null
    }

    private fun CachedGeometry.toSf(): Geometry = when (this) {
        is CachedGeometry.Point -> Point(x, y, z)
        is CachedGeometry.LineString -> LineString(z3D, false).also { ls ->
            for (p in points) ls.addPoint(Point(p.x, p.y, p.z))
        }
        is CachedGeometry.Polygon -> Polygon(z3D, false).also { poly ->
            for (ring in rings) poly.addRing(ring.toSf() as LineString)
        }
    }

    private val CachedGeometry.LineString.z3D: Boolean get() = points.any { it.z != null }
    private val CachedGeometry.Polygon.z3D: Boolean get() = rings.any { it.z3D }
}
