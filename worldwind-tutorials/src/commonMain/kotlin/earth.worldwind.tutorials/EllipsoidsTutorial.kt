package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Position
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.Color
import earth.worldwind.shape.Ellipsoid
import earth.worldwind.shape.ShapeAttributes

class EllipsoidsTutorial(engine: WorldWind) : AbstractTutorial(engine) {

    private val layer = RenderableLayer("Ellipsoids").apply {
        // 1. Pure sphere with lighting on. The default attributes draw a thin outline at the
        //    equator; turn it off here so the smooth Lambertian shading is uninterrupted.
        addRenderable(
            Ellipsoid(
                Position.fromDegrees(45.0, -120.0, 200e3),
                250e3, 250e3, 250e3
            ).apply {
                attributes.apply {
                    isLightingEnabled = true
                    isDrawOutline = false
                    interiorColor = Color(0.9f, 0.4f, 0.4f, 1f)
                }
            }
        )

        // 2. Same sphere with lighting OFF. Flat unlit fill - useful as a comparison to the
        //    one above for understanding what `isLightingEnabled` does.
        addRenderable(
            Ellipsoid(
                Position.fromDegrees(45.0, -100.0, 200e3),
                250e3, 250e3, 250e3
            ).apply {
                attributes.apply {
                    isLightingEnabled = false
                    isDrawOutline = false
                    interiorColor = Color(0.9f, 0.4f, 0.4f, 1f)
                }
            }
        )

        // 3. Oblate spheroid: equatorial radius > polar radius. Earth-like proportions
        //    exaggerated for visibility - x = y = 350 km, z = 150 km. Heading has no effect
        //    because the equatorial cross-section is a circle.
        addRenderable(
            Ellipsoid(
                Position.fromDegrees(30.0, -120.0, 200e3),
                350e3, 350e3, 150e3
            ).apply {
                attributes.apply {
                    isLightingEnabled = true
                    isDrawOutline = false
                    interiorColor = Color(0.4f, 0.7f, 0.9f, 1f)
                }
            }
        )

        // 4. Prolate spheroid: polar radius > equatorial radius. American-football shape.
        addRenderable(
            Ellipsoid(
                Position.fromDegrees(30.0, -100.0, 200e3),
                150e3, 150e3, 350e3
            ).apply {
                attributes.apply {
                    isLightingEnabled = true
                    isDrawOutline = false
                    interiorColor = Color(0.4f, 0.9f, 0.5f, 1f)
                }
            }
        )

        // 5. Tri-axial ellipsoid (xRadius != yRadius != zRadius) with a 30 degree heading
        //    rotating the long axis clockwise from north. Outline is on so you can see the
        //    equator ring.
        addRenderable(
            Ellipsoid(
                Position.fromDegrees(15.0, -120.0, 200e3),
                400e3, 200e3, 250e3
            ).apply {
                heading = 30.0.degrees
                attributes.apply {
                    isLightingEnabled = true
                    isDrawOutline = true
                    outlineColor = Color(1f, 1f, 0f, 1f)
                    outlineWidth = 2f
                    interiorColor = Color(0.8f, 0.5f, 0.9f, 1f)
                }
            }
        )

        // 6. Ground-clamped sphere. CLAMP_TO_GROUND drops the centre to the terrain surface;
        //    the sphere then half-submerges into the ground (the lower hemisphere is below
        //    sea level / terrain, the upper hemisphere shows above).
        addRenderable(
            Ellipsoid(
                Position.fromDegrees(15.0, -100.0, 0.0),
                250e3, 250e3, 250e3,
                ShapeAttributes().apply {
                    isLightingEnabled = true
                    isDrawOutline = false
                    interiorColor = Color(0.95f, 0.85f, 0.4f, 1f)
                }
            ).apply {
                altitudeMode = AltitudeMode.CLAMP_TO_GROUND
            }
        )
    }

    override fun start() {
        super.start()
        engine.layers.addLayer(layer)
        engine.camera.set(
            30.0.degrees, (-110.0).degrees, engine.distanceToViewGlobeExtents * 1.1,
            AltitudeMode.ABSOLUTE, heading = Angle.ZERO, tilt = Angle.ZERO, roll = Angle.ZERO
        )
    }

    override fun stop() {
        super.stop()
        engine.layers.removeLayer(layer)
    }
}
