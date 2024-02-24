package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Position
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.Color
import earth.worldwind.shape.GeomPath
import earth.worldwind.shape.Path
import earth.worldwind.shape.PathType

class PathsTutorial(private val engine: WorldWind) : AbstractTutorial() {

    private val layer = RenderableLayer("Paths").apply {
        addRenderable(
            GeomPath(
                listOf(
                    Position.fromDegrees(40.0, -180.0, 1e5),
                    Position.fromDegrees(20.0, -100.0, 1e6),
                    Position.fromDegrees(40.0, -40.0, 1e5)
                )
            )
        )
        // Create a basic path with the default attributes, the default altitude mode (ABSOLUTE),
        // and the default path type (GREAT_CIRCLE).
        addRenderable(
            GeomPath(
                listOf(
                    Position.fromDegrees(50.0, -180.0, 1e5),
                    Position.fromDegrees(30.0, -100.0, 1e6),
                    Position.fromDegrees(50.0, -40.0, 1e5)
                )
            )
        )

        // Create a terrain following path with the default attributes, and the default path type (GREAT_CIRCLE).
        addRenderable(
            GeomPath(
                listOf(
                    Position.fromDegrees(40.0, -180.0, 0.0),
                    Position.fromDegrees(20.0, -100.0, 0.0),
                    Position.fromDegrees(40.0, -40.0, 0.0)
                )
            ).apply {
                altitudeMode = AltitudeMode.CLAMP_TO_GROUND // clamp the path vertices to the ground
                isFollowTerrain = true // follow the ground between path vertices
            }
        )

        // Create an extruded path with the default attributes, the default altitude mode (ABSOLUTE),
        // and the default path type (GREAT_CIRCLE).
        addRenderable(
            GeomPath(
                listOf(
                    Position.fromDegrees(30.0, -180.0, 1e5),
                    Position.fromDegrees(10.0, -100.0, 1e6),
                    Position.fromDegrees(30.0, -40.0, 1e5)
                )
            ).apply {
                isExtrude = true // extrude the path from the ground to each path position's altitude
            }
        )

        // Create an extruded path with custom attributes that display the extruded vertical lines,
        // make the extruded interior 50% transparent, and increase the path line with.
        addRenderable(
            GeomPath(
                listOf(
                    Position.fromDegrees(20.0, -180.0, 1e5),
                    Position.fromDegrees(0.0, -100.0, 1e6),
                    Position.fromDegrees(20.0, -40.0, 1e5)
                )
            ).apply {
                attributes.apply {
                    isDrawVerticals = true // display the extruded verticals
                    interiorColor = Color(1f, 1f, 1f, 0.5f) // 50% transparent white
                    outlineWidth = 3f
                }
                isExtrude = true // extrude the path from the ground to each path position's altitude
            }
        )
    }

    override fun start() {
        super.start()
        engine.layers.addLayer(layer)
        engine.camera.set(
            30.0.degrees, (-100.0).degrees, engine.distanceToViewGlobeExtents * 1.1,
            AltitudeMode.ABSOLUTE, heading = Angle.ZERO, tilt = Angle.ZERO, roll = Angle.ZERO
        )
    }

    override fun stop() {
        super.stop()
        engine.layers.removeLayer(layer)
    }

}