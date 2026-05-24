package earth.worldwind.ogc

import earth.worldwind.layer.FeatureCacheSourceFactory
import earth.worldwind.layer.cache.CacheEvictionPolicy
import earth.worldwind.layer.cache.CachedFeatureRow
import earth.worldwind.layer.cache.CachedGeometry
import earth.worldwind.ogc.gpkg.GeoPackage
import earth.worldwind.ogc.gpkg.GpkgContent
import earth.worldwind.ogc.gpkg.GpkgFeatureRow
import mil.nga.sf.Geometry
import mil.nga.sf.LineString
import mil.nga.sf.Point
import mil.nga.sf.Polygon
import kotlin.time.Instant

/**
 * GPKG-backed [FeatureCacheSourceFactory] shared by every [earth.worldwind.layer.CacheableFeatureLayer]
 * on JVM/Android — WFS, OSM buildings, MVT, shapefile, etc. Read/write logic lives on [GeoPackage].
 */
class GpkgFeatureCacheFactory(
    val geoPackage: GeoPackage,
    val content: GpkgContent,
) : FeatureCacheSourceFactory {
    override val contentType = "GPKG"
    override val contentKey: String get() = content.tableName
    override val contentPath: String get() = geoPackage.pathName

    override suspend fun lastModifiedDate(): Instant? =
        content.lastChange?.let { Instant.fromEpochMilliseconds(it.time) }

    override suspend fun contentSize(): Long = geoPackage.readFeaturesDataSize(content.tableName)

    override var evictionPolicy: CacheEvictionPolicy = CacheEvictionPolicy.UNBOUNDED

    override suspend fun evict() {
        if (evictionPolicy.isUnbounded || geoPackage.isReadOnly) return
        geoPackage.evictFeatures(content, evictionPolicy)
    }

    override suspend fun clearContent(deleteMetadata: Boolean) {
        if (deleteMetadata) geoPackage.deleteContent(content.tableName)
        else geoPackage.replaceCachedFeatures(content, emptyList())
    }

    override suspend fun readFeatureTile(z: Int, x: Int, y: Int): List<CachedFeatureRow> =
        geoPackage.readFeatureTile(content, z, x, y).map { it.toCached() }

    override suspend fun writeFeatureTile(z: Int, x: Int, y: Int, rows: List<CachedFeatureRow>) {
        val payload = if (rows.isEmpty()) {
            listOf(GpkgFeatureRow(geometry = null, properties = null))
        } else rows.map { it.toGpkg() }
        geoPackage.writeFeatureTile(content, z, x, y, payload)
    }

    override suspend fun readAllFeatures(): List<CachedFeatureRow> =
        geoPackage.readCachedFeatures(content).map { (geom, props) ->
            CachedFeatureRow(geom.toCached(), props)
        }

    override suspend fun replaceAllFeatures(rows: List<CachedFeatureRow>) {
        val payload = rows.mapNotNull { row ->
            val geom = row.geometry?.toSf() ?: return@mapNotNull null
            geom to row.properties
        }
        geoPackage.replaceCachedFeatures(content, payload)
    }

    private fun GpkgFeatureRow.toCached() = CachedFeatureRow(geometry?.toCached(), properties)
    private fun CachedFeatureRow.toGpkg() = GpkgFeatureRow(geometry?.toSf(), properties)

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
