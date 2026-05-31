package earth.worldwind.layer.source

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow

/**
 * [BulkFeatureSource] over a static GeoJSON document. Parses [text] on each [fetchAll]
 * call and emits one [CachedFeatureRow] per Feature in document order. Accepts the GeoJSON
 * top-level shapes (FeatureCollection, Feature, bare Geometry, GeometryCollection); see
 * [parseGeoJsonAsFeatureRows] for the flattening contract.
 *
 * Compose with [earth.worldwind.layer.cache.CachedBulkFeatureSource] to persist rows to a
 * GeoPackage feature store across launches:
 * `CachedBulkFeatureSource(GeoJsonBulkFeatureSource(text), store)` — useful for `.geojson`
 * files that should survive across launches without reparsing on every load.
 */
class GeoJsonBulkFeatureSource(
    val text: String,
) : BulkFeatureSource {
    override suspend fun fetchAll(): Flow<CachedFeatureRow> =
        parseGeoJsonAsFeatureRows(text).asFlow()

    companion object {
        /** Cache service-identity tag for GeoJSON-backed feature layers. */
        const val SERVICE_TYPE = "GeoJson"
    }
}
