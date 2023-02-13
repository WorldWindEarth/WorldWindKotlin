package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.*
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.Color
import earth.worldwind.render.image.ImageSource
import earth.worldwind.shape.Placemark
import earth.worldwind.shape.PlacemarkAttributes

class PlacemarksTutorial(private val engine: WorldWind) : AbstractTutorial() {

    private val layer = RenderableLayer("Placemarks").apply {
        // Create a simple placemark at downtown Ventura, CA. This placemark is a 20x20 cyan square centered on the
        // geographic position. This placemark demonstrates the creation with a convenient factory method.
        addRenderable(
            Placemark.createWithColorAndSize(
                Position.fromDegrees(34.281, -119.293, 0.0), Color(0f, 1f, 1f, 1f), 20
            ).apply {
                altitudeMode = AltitudeMode.CLAMP_TO_GROUND
            }
        )

        // Create an image-based placemark of an aircraft above the ground with a leader-line to the surface.
        // This placemark demonstrates creation via a constructor and a convenient PlacemarkAttributes factory method.
        // The image is scaled to 1.5 times its original size.
        addRenderable(
            Placemark(Position.fromDegrees(34.260, -119.2, 5000.0)).apply {
                attributes = PlacemarkAttributes.createWithImageAndLeader(ImageSource.fromResource(MR.images.aircraft_fixwing)).apply {
                    imageScale = 1.5
                }
            }
        )

        // Create an image-based placemark with a label at Oxnard Airport, CA. This placemark demonstrates creation
        // with a constructor and a convenient PlacemarkAttributes factory method. The image is scaled to 2x
        // its original size, with the bottom center of the image anchored at the geographic position.
        addRenderable(
            Placemark(Position.fromDegrees(34.200, -119.208, 0.0)).apply {
                altitudeMode = AltitudeMode.CLAMP_TO_GROUND
                attributes = PlacemarkAttributes.createWithImage(ImageSource.fromResource(MR.images.airport_terminal)).apply {
                    imageOffset = Offset.bottomCenter()
                    imageScale = 2.0
                }
                displayName = "Oxnard Airport"
            }
        )

        // Create an image-based placemark from a bitmap. This placemark demonstrates creation with a
        // constructor and a convenient PlacemarkAttributes factory method. First, a 64x64 bitmap is loaded,
        // and then it is passed into the placemark attributes. The bottom center of the image anchored
        // at the geographic position.
        addRenderable(
            Placemark(Position.fromDegrees(34.300, -119.25, 0.0)).apply {
                altitudeMode = AltitudeMode.CLAMP_TO_GROUND
                attributes = PlacemarkAttributes.createWithImage(ImageSource.fromResource(MR.images.ehipcc)).apply {
                    imageOffset = Offset.bottomCenter()
                }
            }
        )
    }

    override fun start() {
        super.start()
        engine.layers.addLayer(layer)
        engine.cameraFromLookAt(
            LookAt(
                position = Position.fromDegrees(34.200, -119.208, 0.0),
                altitudeMode = AltitudeMode.ABSOLUTE, range = 1e4,
                heading = Angle.ZERO, tilt = 80.0.degrees, roll = Angle.ZERO
            )
        )
    }

    override fun stop() {
        super.stop()
        engine.layers.removeLayer(layer)
    }

}