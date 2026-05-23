package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.Angle
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.ogc.WfsLayerFactory
import earth.worldwind.render.Color
import earth.worldwind.shape.Polygon
import earth.worldwind.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class WfsLayerTutorial(engine: WorldWind, private val scope: CoroutineScope) : AbstractTutorial(engine) {

    private var wfsLayer: RenderableLayer? = null
    private var job: Job? = null

    /** Natural Earth tags each country with a `continent` attribute — colour each polygon
     *  by that so the grouping is visible at a glance. */
    private val continentColors = mapOf(
        "Africa" to Color.fromHexString("#F4A261"),
        "Asia" to Color.fromHexString("#E76F51"),
        "Europe" to Color.fromHexString("#2A9D8F"),
        "North America" to Color.fromHexString("#264653"),
        "South America" to Color.fromHexString("#E9C46A"),
        "Oceania" to Color.fromHexString("#8338EC"),
        "Antarctica" to Color.fromHexString("#CAD2C5"),
        "Seven seas (open ocean)" to Color.fromHexString("#A8DADC"),
    )

    override fun start() {
        super.start()
        job = scope.launch {
            try {
                // Demonstrates two factory features:
                //   * pageSize triggers WFS 2.0 STARTINDEX pagination — countries (~255)
                //     come back in 100-feature chunks instead of one big request.
                //   * customLogicToApplyProperties fires once per parsed feature, letting
                //     us recolour each polygon based on the `continent` attribute the
                //     server returns alongside its geometry.
                WfsLayerFactory.createLayer(
                    serviceAddress = "https://ahocevar.com/geoserver/wfs",
                    typeName = "ne:ne_10m_admin_0_countries",
                    displayName = "Countries by Continent (WFS)",
                    pageSize = 100,
                    customLogicToApplyProperties = { properties ->
                        val continent = properties["continent"] as? String ?: return@createLayer
                        val color = continentColors[continent] ?: return@createLayer
                        if (this is Polygon) attributes.interiorColor = color
                    },
                ).also {
                    if (isActive) {
                        wfsLayer = it
                        engine.layers.addLayer(it)
                        WorldWind.requestRedraw()
                    }
                }
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

}
