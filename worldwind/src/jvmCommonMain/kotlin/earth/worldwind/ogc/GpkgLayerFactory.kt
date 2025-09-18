package earth.worldwind.ogc

import earth.worldwind.layer.RenderableLayer
import earth.worldwind.layer.mercator.MercatorTiledSurfaceImage
import earth.worldwind.ogc.gpkg.GeoPackage
import earth.worldwind.ogc.gpkg.GeoPackage.Companion.FEATURES
import earth.worldwind.ogc.gpkg.GeoPackage.Companion.TILES
import earth.worldwind.shape.TiledSurfaceImage
import earth.worldwind.util.LevelSet
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.Logger.makeMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object GpkgLayerFactory {

    suspend fun createLayer(pathName: String, layerNames: List<String>? = null): RenderableLayer {
        val layer = createGeoPackageLayer(pathName, layerNames)
        require(!layer.isEmpty()) {
            makeMessage("GpkgLayerFactory", "createLayer", "Unsupported GeoPackage contents")
        }
        return layer
    }

    private suspend fun createGeoPackageLayer(pathName: String, layerNames: List<String>?) = withContext(Dispatchers.IO) {
        RenderableLayer().apply {
            isPickEnabled = false // Disable picking for the tiled image layer
            val geoPackage = GeoPackage(pathName)
            for (content in geoPackage.getContent(TILES, layerNames)) {
                try {
                    val tileFactory = GpkgTileFactory(geoPackage, content)
                    val levelSet = LevelSet(geoPackage.buildLevelSetConfig(content))
                    val surfaceImage = if (content.srs?.id == GeoPackage.EPSG_3857) {
                        MercatorTiledSurfaceImage(tileFactory, levelSet)
                    } else {
                        TiledSurfaceImage(tileFactory, levelSet)
                    }
                    surfaceImage.displayName = content.identifier
                    addRenderable(surfaceImage)
                } catch (e: IllegalArgumentException) {
                    logMessage(WARN, "GpkgLayerFactory", "createGeoPackageLayer", e.message!!)
                }
            }
            for (content in geoPackage.getContent(FEATURES, layerNames)) {
                try {
                    addAllRenderables(geoPackage.getRenderables(content))
                } catch (e: IllegalArgumentException) {
                    logMessage(WARN, "GpkgLayerFactory", "createGeoPackageLayer", e.message!!)
                }
            }
        }
    }
}