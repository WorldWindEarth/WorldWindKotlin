package earth.worldwind.tutorials

import androidx.lifecycle.lifecycleScope
import earth.worldwind.layer.TiledFeatureLayer
import earth.worldwind.layer.cache.attachCache
import earth.worldwind.ogc.wfs.WfsTiledFeatureSource

class WfsRoadsTiledFragment : BasicGlobeFragment() {
    override fun createWorldWindow() = super.createWorldWindow().also {
        WfsAutoRefreshTutorial(it.engine, lifecycleScope,
            cameraLatitude = WfsAutoRefreshTutorial.ROADS_CAMERA_LAT,
            cameraLongitude = WfsAutoRefreshTutorial.ROADS_CAMERA_LON,
            cameraAltitude = WfsAutoRefreshTutorial.ROADS_CAMERA_ALT,
            layerProvider = {
            // Strategy 2: per-tile BBOX GetFeature, each tile cached in the GeoPackage and read
            // cache-first on revisit. Suits ne:ne_10m_roads (a dense global line layer, too large to
            // fetch whole) — exercises the batched line/outline path with many features per tile.
            val layer = TiledFeatureLayer(
                source = WfsTiledFeatureSource(
                    serviceAddress = WfsAutoRefreshTutorial.SERVICE_ADDRESS,
                    typeName = WfsAutoRefreshTutorial.ROADS_TYPE_NAME,
                    maxFeaturesPerTile = WfsAutoRefreshTutorial.ROADS_MAX_FEATURES_PER_TILE,
                ),
                levelOffset = WfsAutoRefreshTutorial.ROADS_LEVEL_OFFSET,
                displayName = WfsAutoRefreshTutorial.ROADS_DISPLAY_NAME,
                customLogicToApplyProperties = WfsAutoRefreshTutorial.roadsStyling,
                // Road LINES: never draw a coarser ancestor — its lines would show through the finer
                // tiles (both resolutions at once). Keep only the finest loaded tiles; cells still
                // loading stay blank for a moment rather than showing doubled roads.
                coarseAncestorFallback = false,
            ).apply {
                isPickEnabled = false
                // Shrink each tile's line VBO (the GPU-memory driver) by simplifying harder: an RDP
                // tolerance keyed to the tile's level drops sub-pixel road detail with no visible change
                // at the rendered zoom — fewer vertices → smaller VBOs (and lighter reads/assembly).
                simplifyTolerancePixels = WfsAutoRefreshTutorial.ROADS_SIMPLIFY_PX
            }
            contentManager.attachCache(layer, "WFS_Roads")
            layer
        }).start()
    }
}
