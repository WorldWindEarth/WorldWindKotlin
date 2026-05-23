package earth.worldwind.ogc

import earth.worldwind.layer.FeatureCacheSourceFactory
import earth.worldwind.ogc.gpkg.GeoPackage
import earth.worldwind.ogc.gpkg.GpkgContent
import kotlin.time.Instant

/**
 * Cache façade for a [CachedWfsFeatureLayer]. Mirrors [GpkgTileFactory] /
 * [GpkgElevationSourceFactory] for parallelism with image / coverage caches: it identifies
 * the cache content row and lets callers inspect / clear the cached payload, while the
 * heavy lifting (writes during refresh, reads on open) lives on the [GeoPackage] side.
 */
internal class GpkgFeatureCacheFactory(
    val geoPackage: GeoPackage,
    val content: GpkgContent,
) : FeatureCacheSourceFactory {
    override val contentType = "GPKG"
    override val contentKey: String get() = content.tableName
    override val contentPath: String get() = geoPackage.pathName

    override suspend fun lastModifiedDate(): Instant? =
        content.lastChange?.let { Instant.fromEpochMilliseconds(it.time) }

    override suspend fun contentSize(): Long = geoPackage.readFeaturesDataSize(content.tableName)

    override suspend fun clearContent(deleteMetadata: Boolean) {
        if (deleteMetadata) geoPackage.deleteContent(content.tableName)
        else geoPackage.replaceCachedFeatures(content, emptyList())
    }
}
