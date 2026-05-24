package earth.worldwind.ogc

import earth.worldwind.layer.FeatureCacheSourceFactory
import earth.worldwind.ogc.gpkg.GeoPackage
import earth.worldwind.ogc.gpkg.GpkgContent
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

    override suspend fun clearContent(deleteMetadata: Boolean) {
        if (deleteMetadata) geoPackage.deleteContent(content.tableName)
        else geoPackage.replaceCachedFeatures(content, emptyList())
    }
}
