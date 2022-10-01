package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Position
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.image.ImageSource
import earth.worldwind.shape.Ellipse
import earth.worldwind.shape.Path
import earth.worldwind.shape.Polygon
import earth.worldwind.shape.ShapeAttributes

class ShapesDashAndFillTutorial(private val engine: WorldWind) : AbstractTutorial() {

    private val layer = RenderableLayer("Dash and fill").apply {
        // Thicken all lines used in the tutorial.
        val thickenLine = ShapeAttributes().apply { outlineWidth = 4f }

        // Create a path with a simple dashed pattern generated from the ImageSource factory. The
        // ImageSource.fromLineStipple function generates a texture based on the provided factor and pattern, similar to
        // stipple parameters of OpenGL 2. The binary representation of the pattern value will be the pattern displayed,
        // where positions with a 1 appearing as opaque and a 0 as transparent.
        addRenderable(
            Path(
                listOf(
                    Position.fromDegrees(60.0, -100.0, 1e5),
                    Position.fromDegrees(30.0, -120.0, 1e5),
                    Position.fromDegrees(0.0, -100.0, 1e5)
                )
            ).apply {
                attributes.copy(thickenLine).apply {
                    outlineImageSource = ImageSource.fromLineStipple(factor = 2, pattern = 0xF0F0.toShort())
                }
            }
        )

        // Modify the factor of the pattern for comparison to first path. Only the factor is modified, not the pattern.
        addRenderable(
            Path(
                listOf(
                    Position.fromDegrees(60.0, -90.0, 5e4),
                    Position.fromDegrees(30.0, -110.0, 5e4),
                    Position.fromDegrees(0.0, -90.0, 5e4)
                )
            ).apply {
                attributes.copy(thickenLine).apply {
                    outlineImageSource = ImageSource.fromLineStipple(factor = 4, pattern = 0xF0F0.toShort())
                }
            }
        )

        // Create a path conforming to the terrain with a different pattern from the first two Paths.
        addRenderable(
            Path(
                listOf(
                    Position.fromDegrees(60.0, -80.0, 0.0),
                    Position.fromDegrees(30.0, -100.0, 0.0),
                    Position.fromDegrees(0.0, -80.0, 0.0)
                )
            ).apply {
                attributes.copy(thickenLine).apply {
                    outlineImageSource = ImageSource.fromLineStipple(factor = 8, pattern = 0xDFF6.toShort())
                }
                altitudeMode = AltitudeMode.CLAMP_TO_GROUND
                isFollowTerrain = true
            }
        )

        // Create an Ellipse using an image as a repeating fill pattern
        addRenderable(
            Ellipse(Position.fromDegrees(40.0, -70.0, 1e5), 1.5e6, 800e3).apply {
                attributes.copy(thickenLine).apply {
                    interiorImageSource = ImageSource.fromResource(MR.images.pattern_sample_houndstooth)
                }
            }
        )

        // Create a surface polygon using an image as a repeating fill pattern and a dash pattern for the outline
        // of the polygon.
        addRenderable(
            Polygon(
                listOf(
                    Position.fromDegrees(25.0, -85.0, 0.0),
                    Position.fromDegrees(10.0, -80.0, 0.0),
                    Position.fromDegrees(10.0, -60.0, 0.0),
                    Position.fromDegrees(25.0, -55.0, 0.0)
                )
            ).apply {
                attributes.copy(thickenLine).apply {
                    interiorImageSource = ImageSource.fromResource(MR.images.pattern_sample_houndstooth)
                    outlineImageSource = ImageSource.fromLineStipple(8, 0xDFF6.toShort())
                }
                altitudeMode = AltitudeMode.CLAMP_TO_GROUND
                followTerrain = true
            }
        )
    }

    override fun start() {
        super.start()
        engine.layers.addLayer(layer)
        engine.camera.set(

            Angle.fromDegrees(30.0), Angle.fromDegrees(-85.0), engine.distanceToViewGlobeExtents * 1.1,
            AltitudeMode.ABSOLUTE, heading = Angle.ZERO, tilt = Angle.ZERO, roll = Angle.ZERO
        )
    }

    override fun stop() {
        super.stop()
        engine.layers.removeLayer(layer)
    }

}