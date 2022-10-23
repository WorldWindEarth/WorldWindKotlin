package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.LookAt
import earth.worldwind.geom.Position
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.Color
import earth.worldwind.shape.OmnidirectionalSightline
import earth.worldwind.shape.Placemark

class SightlineTutorial(private val engine: WorldWind) : AbstractTutorial() {

    private val layer = RenderableLayer("Sightline").apply {
        // Specify the sightline position, which is the origin of the line of sight calculation
        val position = Position.fromDegrees(46.230, -122.190, 2500.0)
        // Create the sightline, specifying the range of the sightline (meters)
        addRenderable(
            OmnidirectionalSightline(position, 10000.0).apply {
                // Create attributes for the visible terrain
                attributes.apply { interiorColor = Color(0f, 1f, 0f, 0.5f) }
                // Create attributes for the occluded terrain
                occludeAttributes.apply { interiorColor = Color(0.1f, 0.1f, 0.1f, 0.8f) }
            }
        )
        // Create a Placemark to visualize the position of the sightline
        addRenderable(
            Placemark(position).apply {
                attributes.apply {
                    imageSource = earth.worldwind.render.image.ImageSource.fromResource(MR.images.aircraft_fixwing)
                    imageScale = 2.0
                    isDrawLeader = true
                }
            }
        )
    }

    override fun start() {
        super.start()
        engine.layers.addLayer(layer)
        engine.cameraFromLookAt(
            LookAt().set(
                latitude = 46.230.degrees, longitude = (-122.190).degrees, altitude = 500.0,
                altitudeMode = AltitudeMode.ABSOLUTE, range = 1.5e4,
                heading = 45.0.degrees, tilt = 70.0.degrees, roll = 0.0.degrees
            )
        )
    }

    override fun stop() {
        super.stop()
        engine.layers.removeLayer(layer)
    }

}