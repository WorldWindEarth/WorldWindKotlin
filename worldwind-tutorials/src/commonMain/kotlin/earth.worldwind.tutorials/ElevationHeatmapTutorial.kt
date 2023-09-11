package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.*
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.layer.heatmap.ElevationHeatmapLayer

class ElevationHeatmapTutorial(private val engine: WorldWind) : AbstractTutorial() {
    var elevationHeatmapLayer = ElevationHeatmapLayer();

    override fun start() {
        super.start()
        engine.layers.addLayer(elevationHeatmapLayer)
        engine.cameraFromLookAt(
            LookAt(
                position = Position.fromDegrees(50.4501 , 30.5234 , 0.0), // Kyiv
                altitudeMode = AltitudeMode.ABSOLUTE, range = 1e4,
                heading = Angle.ZERO, tilt = 0.0.degrees, roll = Angle.ZERO
            )
        )
    }

    override fun stop() {
        super.stop()
        engine.layers.removeLayer(elevationHeatmapLayer)
    }
}