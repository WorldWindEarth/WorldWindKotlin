package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Position
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.Color
import earth.worldwind.shape.Polygon

class PolygonsTutorial(private val engine: WorldWind) : AbstractTutorial() {

    private val layer = RenderableLayer("Polygons").apply {
        // Create a basic polygon with the default attributes, the default altitude mode (ABSOLUTE),
        // and the default path type (GREAT_CIRCLE).
        addRenderable(
            Polygon(
                listOf(
                    Position.fromDegrees(40.0, -135.0, 5.0e5),
                    Position.fromDegrees(45.0, -140.0, 7.0e5),
                    Position.fromDegrees(50.0, -130.0, 9.0e5),
                    Position.fromDegrees(45.0, -120.0, 7.0e5),
                    Position.fromDegrees(40.0, -125.0, 5.0e5)
                )
            )
        )

        // Create a terrain following polygon with the default attributes, and the default path type (GREAT_CIRCLE).
        addRenderable(
            Polygon(
                listOf(
                    Position.fromDegrees(40.0, -105.0, 0.0),
                    Position.fromDegrees(45.0, -110.0, 0.0),
                    Position.fromDegrees(50.0, -100.0, 0.0),
                    Position.fromDegrees(45.0, -90.0, 0.0),
                    Position.fromDegrees(40.0, -95.0, 0.0)
                )
            ).apply {
                altitudeMode = AltitudeMode.CLAMP_TO_GROUND // clamp the polygon vertices to the ground
                followTerrain = true // follow the ground between polygon vertices
            }
        )

        // Create an extruded polygon with the default attributes, the default altitude mode (ABSOLUTE),
        // and the default path type (GREAT_CIRCLE).
        addRenderable(
            Polygon(
                listOf(
                    Position.fromDegrees(20.0, -135.0, 5.0e5),
                    Position.fromDegrees(25.0, -140.0, 7.0e5),
                    Position.fromDegrees(30.0, -130.0, 9.0e5),
                    Position.fromDegrees(25.0, -120.0, 7.0e5),
                    Position.fromDegrees(20.0, -125.0, 5.0e5)
                )
            ).apply {
                isExtrude = true // extrude the polygon from the ground to each polygon position's altitude
            }
        )

        // Create an extruded polygon with custom attributes that display the extruded vertical lines,
        // make the extruded interior 50% transparent, and increase the polygon line with.
        addRenderable(
            Polygon(
                listOf(
                    Position.fromDegrees(20.0, -105.0, 5.0e5),
                    Position.fromDegrees(25.0, -110.0, 7.0e5),
                    Position.fromDegrees(30.0, -100.0, 9.0e5),
                    Position.fromDegrees(25.0, -90.0, 7.0e5),
                    Position.fromDegrees(20.0, -95.0, 5.0e5)
                )
            ).apply {
                attributes.apply {
                    isDrawVerticals = true // display the extruded verticals
                    interiorColor = Color(1f, 1f, 1f, 0.5f) // 50% transparent white
                    outlineWidth = 3f
                }
                isExtrude = true // extrude the polygon from the ground to each polygon position's altitude
            }
        )

        // Create a polygon with an inner hole by specifying multiple polygon boundaries
        addRenderable(
            Polygon().apply {
                addBoundary(
                    listOf(
                        Position.fromDegrees(0.0, -135.0, 5.0e5),
                        Position.fromDegrees(5.0, -140.0, 7.0e5),
                        Position.fromDegrees(10.0, -130.0, 9.0e5),
                        Position.fromDegrees(5.0, -120.0, 7.0e5),
                        Position.fromDegrees(0.0, -125.0, 5.0e5)
                    )
                )
                addBoundary(
                    listOf(
                        Position.fromDegrees(2.5, -130.0, 6.0e5),
                        Position.fromDegrees(5.0, -135.0, 7.0e5),
                        Position.fromDegrees(7.5, -130.0, 8.0e5),
                        Position.fromDegrees(5.0, -125.0, 7.0e5)
                    )
                )
            }
        )

        // Create an extruded polygon with an inner hole and custom attributes that display the extruded vertical lines,
        // make the extruded interior 50% transparent, and increase the polygon line with.
        addRenderable(
            Polygon(emptyList()).apply {
                attributes.apply {
                    isDrawVerticals = true // display the extruded verticals
                    interiorColor = Color(1f, 1f, 1f, 0.5f) // 50% transparent white
                    outlineWidth = 3f
                }
                addBoundary(
                    listOf(
                        Position.fromDegrees(0.0, -105.0, 5.0e5),
                        Position.fromDegrees(5.0, -110.0, 7.0e5),
                        Position.fromDegrees(10.0, -100.0, 9.0e5),
                        Position.fromDegrees(5.0, -90.0, 7.0e5),
                        Position.fromDegrees(0.0, -95.0, 5.0e5)
                    )
                )
                addBoundary(
                    listOf(
                        Position.fromDegrees(2.5, -100.0, 6.0e5),
                        Position.fromDegrees(5.0, -105.0, 7.0e5),
                        Position.fromDegrees(7.5, -100.0, 8.0e5),
                        Position.fromDegrees(5.0, -95.0, 7.0e5)
                    )
                )
                isExtrude = true // extrude the polygon from the ground to each polygon position's altitude
            }
        )
    }

    override fun start() {
        super.start()
        engine.layers.addLayer(layer)
        engine.camera.set(
            Angle.fromDegrees(30.0), Angle.fromDegrees(-115.0), engine.distanceToViewGlobeExtents * 1.1,
            AltitudeMode.ABSOLUTE, heading = Angle.ZERO, tilt = Angle.ZERO, roll = Angle.ZERO
        )
    }

    override fun stop() {
        super.stop()
        engine.layers.removeLayer(layer)
    }

}