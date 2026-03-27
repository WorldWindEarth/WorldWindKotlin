package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Location
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.Color
import earth.worldwind.render.image.ImageSource
import earth.worldwind.shape.ShapeAttributes
import earth.worldwind.shape.TextureQuad

class TextureQuadTutorial(private val engine: WorldWind) : AbstractTutorial() {
    private val layer = RenderableLayer("Texture quad").apply {
        // Configure a Texture quad to display an Android resource showing the novij korogod.
        addRenderable(
            TextureQuad(
                Location(51.26891660167462.degrees, 30.0096671570868.degrees),
                Location(51.27062637593827.degrees, 30.01240117718469.degrees),
                Location(51.271804026840556.degrees, 30.01029779181104.degrees),
                Location(51.270125460836965.degrees, 30.00779959572076.degrees),
                ShapeAttributes().apply {
                    interiorImageSource = ImageSource.fromResource(MR.images.korogode_image)
                    isDrawOutline = true
                    outlineWidth = 3.0f
                    outlineColor = Color(1.0f, 0.0f, 0.0f, 1f)
                }
            )
        )
    }

    override fun start() {
        super.start()
        engine.layers.addLayer(layer)
        engine.camera.set(
            51.270125460836965.degrees, 30.00989959572076.degrees, 9.0e2,
            AltitudeMode.ABSOLUTE, heading = Angle.ZERO, tilt = Angle.ZERO, roll = Angle.ZERO
        )
    }

    override fun stop() {
        super.stop()
        engine.layers.removeLayer(layer)
    }
}