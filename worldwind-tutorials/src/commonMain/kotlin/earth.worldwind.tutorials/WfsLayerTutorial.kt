package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.Angle
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.ogc.WfsLayerFactory
import earth.worldwind.render.Color
import earth.worldwind.shape.Label
import earth.worldwind.shape.Placemark
import earth.worldwind.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class WfsLayerTutorial(engine: WorldWind, private val scope: CoroutineScope) : AbstractTutorial(engine) {

    private var wfsLayer: RenderableLayer? = null
    private var job: Job? = null

    /** Population thresholds (in people) and the placemark colour for each tier. The
     *  MapServer demo's `ms:cities` layer ships the world's biggest cities first, with
     *  `POPULATION` as a numeric attribute we can read in [customLogicToApplyProperties]. */
    private val populationTiers = listOf(
        5_000_000.0 to Color.fromHexString("#E63946"), // megacity (5M+) — red
        2_000_000.0 to Color.fromHexString("#F4A261"), // major (2–5M)   — orange
        1_000_000.0 to Color.fromHexString("#E9C46A"), // large (1–2M)   — yellow
        0.0         to Color.fromHexString("#2A9D8F"), // rest (<1M)     — teal
    )

    override fun start() {
        super.start()
        job = scope.launch {
            try {
                // Demonstrates two WfsLayerFactory hooks:
                //   * pageSize triggers WFS 2.0 STARTINDEX pagination — the requested 500
                //     cities come back in five 100-feature pages instead of one big
                //     request.
                //   * customLogicToApplyProperties fires once per parsed feature, letting
                //     us tint each placemark by its POPULATION attribute.
                // The MapServer demo (demo.mapserver.org/cgi-bin/wfs) is used instead of
                // a GeoServer demo because its CORS preflight is correctly configured —
                // some popular GeoServer demos (e.g. ahocevar.com) 403 the OPTIONS
                // request so the browser blocks the actual GET.
                WfsLayerFactory.createLayer(
                    serviceAddress = "https://demo.mapserver.org/cgi-bin/wfs",
                    typeName = "ms:cities",
                    displayName = "Major Cities (WFS)",
                    maxFeatures = 500,
                    pageSize = 100,
                    customLogicToApplyProperties = { properties ->
                        val population = (properties["POPULATION"] as? Number)?.toDouble() ?: return@createLayer
                        val color = populationTiers.first { (threshold, _) -> population >= threshold }.second
                        // GeoJsonLayerFactory emits a Label when a feature has `name` but
                        // no `icon`, and a Placemark otherwise — colour whichever we got.
                        when (this) {
                            is Placemark -> attributes.imageColor = color
                            is Label -> attributes.textColor = color
                        }
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
