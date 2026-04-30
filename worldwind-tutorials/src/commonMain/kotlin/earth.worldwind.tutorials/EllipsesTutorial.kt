package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Position
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.Color
import earth.worldwind.shape.Ellipse

class EllipsesTutorial(engine: WorldWind) : AbstractTutorial(engine), PickIndicatorTutorial {

    override val picker = PickResultIndicator()

    private val layer = RenderableLayer("Ellipses").apply {
        // Create a surface ellipse with the default attributes, a 500km major-radius and a 300km minor-radius. Surface
        // ellipses are configured with a CLAMP_TO_GROUND altitudeMode and followTerrain set to true.
        addRenderable(
            Ellipse(Position.fromDegrees(45.0, -120.0, 0.0), 500000.0, 300000.0).apply {
                altitudeMode = AltitudeMode.CLAMP_TO_GROUND // clamp the ellipse's center position to the terrain surface
                isFollowTerrain = true // cause the ellipse geometry to follow the terrain surface
            }
        )

        // Create a surface ellipse with custom attributes that make the interior 50% transparent and increase the
        // outline width.
        addRenderable(
            Ellipse(Position.fromDegrees(45.0, -100.0, 0.0), 500000.0, 300000.0).apply {
                attributes.apply {
                    interiorColor = Color(1f, 1f, 1f, 0.5f) // 50% transparent white
                    outlineWidth = 3f
                }
                altitudeMode = AltitudeMode.CLAMP_TO_GROUND // clamp the ellipse's center position to the terrain surface
                isFollowTerrain = true // cause the ellipse geometry to follow the terrain surface
            }
        )

        // Create a surface ellipse with a heading of 45 degrees, causing the semi-major axis to point Northeast and the
        // semi-minor axis to point Southeast.
        addRenderable(
            Ellipse(Position.fromDegrees(35.0, -120.0, 0.0), 500000.0, 300000.0).apply {
                altitudeMode = AltitudeMode.CLAMP_TO_GROUND // clamp the ellipse's center position to the terrain surface
                isFollowTerrain = true // cause the ellipse geometry to follow the terrain surface
                heading = 45.0.degrees
            }
        )

        // Create a surface circle with the default attributes and 400km radius.
        addRenderable(
            Ellipse(Position.fromDegrees(35.0, -100.0, 0.0), 400000.0, 400000.0).apply {
                altitudeMode = AltitudeMode.CLAMP_TO_GROUND // clamp the ellipse's center position to the terrain surface
                isFollowTerrain = true // cause the ellipse geometry to follow the terrain surface
            }
        )

        // Create an ellipse with the default attributes, an altitude of 200 km, and a 500km major-radius and a 300km
        // minor-radius.
        addRenderable(
            Ellipse(Position.fromDegrees(25.0, -120.0, 200e3), 500000.0, 300000.0)
        )

        // Create an ellipse with custom attributes that make the interior 50% transparent and an extruded outline with
        // vertical lines
        addRenderable(
            Ellipse(Position.fromDegrees(25.0, -100.0, 200e3), 500000.0, 300000.0).apply {
                attributes.apply {
                    interiorColor = Color(1f, 1f, 1f, 0.5f) // 50% transparent white
                    isDrawVerticals = true
                }
                isExtrude = true
            }
        )

        // Create a surface pie-slice (sector): a 120° wedge of a circle, opening to the East-Southeast.
        addRenderable(
            Ellipse(Position.fromDegrees(15.0, -120.0, 0.0), 400000.0, 400000.0).apply {
                altitudeMode = AltitudeMode.CLAMP_TO_GROUND
                isFollowTerrain = true
                startAzimuth = 60.0.degrees
                sweepAngle = 120.0.degrees
            }
        )

        // Create an extruded pie-slice ellipse: 90° wedge with the major axis aligned along NE.
        addRenderable(
            Ellipse(Position.fromDegrees(15.0, -100.0, 200e3), 500000.0, 300000.0).apply {
                attributes.apply {
                    interiorColor = Color(1f, 1f, 1f, 0.5f)
                    isDrawVerticals = true
                }
                isExtrude = true
                heading = 45.0.degrees
                startAzimuth = 0.0.degrees
                sweepAngle = 90.0.degrees
            }
        )
    }

    override fun start() {
        super.start()
        engine.layers.addLayer(layer)
        picker.attach(engine)
        engine.camera.set(
            30.0.degrees, (-110.0).degrees, engine.distanceToViewGlobeExtents * 1.1,
            AltitudeMode.ABSOLUTE, heading = Angle.ZERO, tilt = Angle.ZERO, roll = Angle.ZERO
        )
    }

    override fun stop() {
        super.stop()
        engine.layers.removeLayer(layer)
        picker.detach(engine)
    }

}