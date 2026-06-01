package earth.worldwind.layer.cache

import earth.worldwind.geom.Sector
import kotlin.time.Instant

/**
 * Metadata about one content entry in the cache — enough for the caller to decide whether
 * to instantiate a layer from it. Produced by `ContentManager.listEntries()`; the caller
 * turns one of these into a working layer via the reified `openLayer<T>(key)` /
 * `openElevationCoverage<T>(key)` helpers (or by manually opening the store + source pair).
 */
data class CacheEntry(
    val contentKey: String,
    val dataType: DataType,
    val service: WebServiceInfo?,
    val boundingSector: Sector?,
    val lastModified: Instant?,
    val displayName: String = contentKey,
    val isFloat: Boolean = false,
) {
    enum class DataType {
        /** Image tile pyramid (PNG/JPEG/WebP). */
        TILES,
        /** Vector-tile pyramid (Mapbox protobuf, OGC `im_vector_tiles_mapbox`). */
        VECTOR_TILES,
        /** Gridded coverage (elevation). */
        COVERAGE,
        /** Vector features (WFS, Shapefile, OsmBuildings — distinguished by [WebServiceInfo.type]). */
        FEATURES,
        /** OGC 3D Tiles content cache (community extension; b3dm / i3dm / cmpt / pnts / glTF). */
        OGC_3D_TILES,
    }
}

/**
 * Persisted web-service metadata for a cached layer. Stored in the GeoPackage
 * `gpkg_web_service` extension table so a layer can be reconstructed without contacting
 * the originating server — capabilities XML / JSON in [metadata] survives across launches.
 *
 * [type] is the discriminator the layer factory dispatches on — "WMS", "WMTS", "WFS",
 * "WCS", "MVT", "WebMercator", "Shapefile", "OsmBuildings". The set of recognised values
 * lives in the source-construction layer (e.g. `ContentManager.openLayer` / `openElevationCoverage`).
 */
data class WebServiceInfo(
    val type: String,
    val address: String,
    val layerName: String? = null,
    val outputFormat: String? = null,
    val metadata: String? = null,
    val isTransparent: Boolean = false,
)
