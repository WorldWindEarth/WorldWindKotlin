package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Sector
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.image.ImageSource
import earth.worldwind.shape.ShapeAttributes
import earth.worldwind.shape.SurfaceImage
import earth.worldwind.shape.TextureQuad

class SurfaceImageTutorial(private val engine: WorldWind) : AbstractTutorial() {

    private val layer = RenderableLayer("Surface image").apply {
        // Configure a Surface Image to display an Android resource showing the WorldWindEarth logo.
        addRenderable(
            TextureQuad(
                Sector.fromDegrees(37.46, 15.5, 0.5, 0.6),
                ShapeAttributes().apply { interiorImageSource = ImageSource.fromResource(MR.images.worldwind_logo) }
            )
        )

        // Configure a Surface Image to display a remote image showing Mount Etna erupting on July 13th, 2001.
        addRenderable(
            TextureQuad(
                Sector.fromDegrees(37.46543388598137, 14.60128369746704, 0.45360804083528, 0.75704283995502),
                ShapeAttributes().apply { interiorImageSource = ImageSource.fromUrlString("https://worldwind.arc.nasa.gov/android/tutorials/data/etna.jpg") }
            )
        )
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