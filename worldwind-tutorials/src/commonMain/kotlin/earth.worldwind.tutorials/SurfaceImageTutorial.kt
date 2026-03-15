package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Location
import earth.worldwind.geom.Sector
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.image.ImageSource
import earth.worldwind.shape.SurfaceImage
import earth.worldwind.geom.Position
import earth.worldwind.render.Color
import earth.worldwind.shape.Placemark
import earth.worldwind.shape.TextureQuad
import earth.worldwind.shape.ShapeAttributes

class SurfaceImageTutorial(private val engine: WorldWind) : AbstractTutorial() {

    private val layer = RenderableLayer("Surface image").apply {
        // Configure a Surface Image to display an Android resource showing the WorldWindEarth logo.
        val sector1 = Sector.fromDegrees(37.46, 15.5, 0.5, 0.6)
        val sector2 = Sector.fromDegrees(36.46, 14.5, 1.5, 1.9)
        val sector3 = Sector.fromDegrees(51.272140, 30.010303, 0.09, 0.02)
        val sector4 = Sector.fromDegrees(51.265140, 30.010303, 0.09, 0.02)
        addRenderable(
            TextureQuad(
                Location(sector3.minLatitude, sector3.maxLongitude),
                Location(sector3.minLatitude, sector3.minLongitude),
                Location(sector4.minLatitude, sector4.minLongitude),
                Location(sector4.minLatitude, sector4.maxLongitude),
                ImageSource.fromResource(MR.images.korogode_image)
            ).apply { opacity = 0.9f }
        )

        addRenderable(
            Placemark.createWithColorAndSize(
                Position(sector3.minLatitude, sector3.maxLongitude, 0.0), Color(0f, 1f, 1f, 1f), 20
            ).apply {
                altitudeMode = AltitudeMode.CLAMP_TO_GROUND
            }
        )
//        addRenderable(
//            SurfaceImage(
//                Sector.fromDegrees(37.46, 15.5, 0.5, 0.6),
//                ImageSource.fromResource(MR.images.worldwind_logo)
//            )
//        )
//
//        // Configure a Surface Image to display a remote image showing Mount Etna erupting on July 13th, 2001.
//        addRenderable(
//            SurfaceImage(
//                Sector.fromDegrees(37.46543388598137, 14.60128369746704, 0.45360804083528, 0.75704283995502),
//                ImageSource.fromUrlString("https://worldwind.arc.nasa.gov/android/tutorials/data/etna.jpg")
//            )
//        )
    }

    override fun start() {
        super.start()
        engine.layers.addLayer(layer)
        engine.camera.set(
            37.46543388598137.degrees, 14.97980511744455.degrees, 4.0e5,
            AltitudeMode.ABSOLUTE, heading = Angle.ZERO, tilt = Angle.ZERO, roll = Angle.ZERO
        )
    }

    override fun stop() {
        super.stop()
        engine.layers.removeLayer(layer)
    }

}