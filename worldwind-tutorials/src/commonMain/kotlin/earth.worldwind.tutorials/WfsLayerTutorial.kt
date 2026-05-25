package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.Angle
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.layer.BulkFeatureLayer
import earth.worldwind.render.Color
import earth.worldwind.render.Renderable
import earth.worldwind.shape.Label
import earth.worldwind.shape.Placemark
import earth.worldwind.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * @param layerLoader builds the [BulkFeatureLayer] over a (possibly cache-wrapped) source.
 *   Defaults to network-only — JVM/Android override it with a cache-wrapped source built
 *   via `GpkgContentManager.openFeatureStore` + `CachedBulkFeatureSource`.
 */
class WfsLayerTutorial(
    engine: WorldWind,
    private val scope: CoroutineScope,
    private val layerLoader: suspend () -> RenderableLayer = ::defaultNetworkOnlyLoader,
) : AbstractTutorial(engine) {

    private var wfsLayer: RenderableLayer? = null
    private var job: Job? = null

    override fun start() {
        super.start()
        job = scope.launch {
            try {
                val layer = layerLoader()
                if (!isActive) return@launch
                // Tutorial layer is informational; disable picking so the placemarks and
                // labels don't intercept drag gestures from the navigation controller.
                layer.isPickEnabled = false
                wfsLayer = layer
                engine.layers.addLayer(layer)
                if (layer is BulkFeatureLayer) layer.load()
                WorldWind.requestRedraw()
                Logger.log(Logger.INFO, "WFS layer creation succeeded")
            } catch (e: Throwable) {
                Logger.log(Logger.ERROR, "WFS layer creation failed", e)
            }
        }
        engine.camera.apply {
            position.altitude = engine.distanceToViewGlobeExtents * 1.1
            heading = Angle.ZERO
            tilt = Angle.ZERO
            roll = Angle.ZERO
        }
    }

    override fun stop() {
        super.stop()
        job?.cancel()
        wfsLayer?.let { engine.layers.removeLayer(it) }.also { wfsLayer = null }
    }

    companion object {
        const val SERVICE_ADDRESS = "https://demo.mapserver.org/cgi-bin/wfs"
        const val TYPE_NAME = "ms:cities"
        const val DISPLAY_NAME = "Major Cities (WFS)"
        const val MAX_FEATURES = 500
        const val PAGE_SIZE = 100

        val populationTiers = listOf(
            5_000_000.0 to Color.fromHexString("#E63946"),
            2_000_000.0 to Color.fromHexString("#F4A261"),
            1_000_000.0 to Color.fromHexString("#E9C46A"),
            0.0         to Color.fromHexString("#2A9D8F"),
        )

        /** Per-feature styling — tints each placemark / label by its POPULATION attribute. */
        val populationStyling: Renderable.(LinkedHashMap<String, Any?>) -> Unit = { properties ->
            val population = (properties["POPULATION"] as? Number)?.toDouble()
            if (population != null) {
                val color = populationTiers.first { (threshold, _) -> population >= threshold }.second
                when (this) {
                    is Placemark -> attributes.imageColor = color
                    is Label -> attributes.textColor = color
                }
            }
        }

        /** Default loader: empty placeholder. WFS network + cache wiring lives on
         *  JVM/Android (the `WfsBulkFeatureSource` class only exists in `jvmCommonMain`)
         *  — those tutorials supply a non-default `layerLoader` that builds a real
         *  [BulkFeatureLayer]. iOS/JS use this default until they get their own source. */
        @Suppress("RedundantSuspendModifier")
        suspend fun defaultNetworkOnlyLoader(): RenderableLayer = RenderableLayer(DISPLAY_NAME)
    }
}
