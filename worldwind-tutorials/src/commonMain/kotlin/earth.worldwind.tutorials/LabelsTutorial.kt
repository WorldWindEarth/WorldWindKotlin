package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Offset
import earth.worldwind.geom.Position
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.Color
import earth.worldwind.render.Font
import earth.worldwind.render.FontWeight
import earth.worldwind.shape.Label
import earth.worldwind.shape.OrientationMode

class LabelsTutorial(private val engine: WorldWind): AbstractTutorial() {

    private val layer = RenderableLayer("Labels").apply {
        // Create a basic label with the default attributes, including the default text color (white), the default text
        // size (24 pixels), the system default font, and the default alignment (bottom center).
        addRenderable(
            Label(Position.fromDegrees(38.8977, -77.0365, 0.0), "The White House").apply {
                altitudeMode = AltitudeMode.CLAMP_TO_GROUND
            }
        )

        // Create a label with a black text color, the default text size, the system default font, the default
        // alignment, and a thick white text outline.
        addRenderable(
            Label(Position.fromDegrees(38.881389, -77.036944, 0.0), "Thomas Jefferson Memorial").apply {
                altitudeMode = AltitudeMode.CLAMP_TO_GROUND
                attributes.apply {
                    textColor = Color(0f, 0f, 0f, 1f) // black text via r,g,b,a
                    outlineColor = Color(1f, 1f, 1f, 1f) // white outline via r,g,b,a
                    outlineWidth = 5f // thicken the white outline
                }
            }
        )

        // Create a right-aligned label using a bottom-right offset.
        addRenderable(
            Label(Position.fromDegrees(38.8893, -77.050111, 0.0), "Lincoln Memorial").apply {
                altitudeMode = AltitudeMode.CLAMP_TO_GROUND
                attributes.apply { textOffset = Offset.bottomRight() }
            }
        )

        // Create a left-aligned label using a bottom-left offset.
        addRenderable(
            Label(Position.fromDegrees(38.889803, -77.009114, 0.0), "United States Capitol").apply {
                altitudeMode = AltitudeMode.CLAMP_TO_GROUND
                attributes.apply { textOffset = Offset.bottomLeft() }
            }
        )

        // Create a label with a 48 pixel text size and a bold font.
        addRenderable(
            Label(Position.fromDegrees(38.907192, -77.036871, 0.0), "Washington").apply {
                altitudeMode = AltitudeMode.CLAMP_TO_GROUND
                attributes.apply { font = Font("arial", FontWeight.BOLD, 28) }
            }
        )

        // Create a label with its orientation fixed relative to the globe.
        addRenderable(
            Label(Position.fromDegrees(38.89, -77.023611, 0.0), "National Mall").apply {
                altitudeMode = AltitudeMode.CLAMP_TO_GROUND
                rotationMode = OrientationMode.RELATIVE_TO_GLOBE
            }
        )
    }

    override fun start() {
        super.start()
        engine.layers.addLayer(layer)
        engine.camera.set(
            Angle.fromDegrees(38.89), Angle.fromDegrees(-77.023611), 10e3,
            AltitudeMode.ABSOLUTE, heading = Angle.ZERO, tilt = Angle.ZERO, roll = Angle.ZERO
        )
    }

    override fun stop() {
        super.stop()
        engine.layers.removeLayer(layer)
    }

}