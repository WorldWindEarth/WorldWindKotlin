package earth.worldwind.ogc

import earth.worldwind.WorldWind
import earth.worldwind.layer.Layer
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.ogc.gpkg.GeoPackage
import earth.worldwind.shape.TiledSurfaceImage
import earth.worldwind.util.LevelSet
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.Logger.makeMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

open class GpkgLayerFactory(
    protected val mainScope: CoroutineScope,
) {
    open fun createLayer(
        pathName: String, creationFailed: ((Exception) -> Unit)? = null, creationSucceeded: ((Layer) -> Unit)? = null
    ): Layer {
        val layer = RenderableLayer().apply { isPickEnabled = false }
        mainScope.launch {
            try {
                val renderableLayer = createGeoPackageLayer(pathName, layer)
                require(!renderableLayer.isEmpty()) {
                    makeMessage(
                        "GpkgLayerFactory", "createFromGeoPackage", "Unsupported GeoPackage contents"
                    )
                }
                // Add the tiled surface image to the layer on the main thread and notify the caller. Request redraw to ensure
                // that the image displays on all WorldWindows the layer may be attached to.
                layer.addAllRenderables(renderableLayer)
                creationSucceeded?.invoke(layer)
                WorldWind.requestRedraw()
            } catch (e: Exception) {
                creationFailed?.invoke(e)
            }
        }
        return layer
    }

    protected open suspend fun createGeoPackageLayer(pathName: String, layer: Layer) = withContext(Dispatchers.IO) {
        RenderableLayer().apply {
            val geoPackage = GeoPackage(pathName)
            for (content in geoPackage.content) {
                try {
                    val config = geoPackage.buildLevelSetConfig(content)
                    val surfaceImage = TiledSurfaceImage(GpkgTileFactory(content), LevelSet(config)).apply {
                        displayName = content.identifier
                    }
                    addRenderable(surfaceImage)
                } catch (e: IllegalArgumentException) {
                    logMessage(WARN, "GpkgLayerFactory", "createFromGeoPackageAsync", e.message!!)
                }
            }
        }
    }
}