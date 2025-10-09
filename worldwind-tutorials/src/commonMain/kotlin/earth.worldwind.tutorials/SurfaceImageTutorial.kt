package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.image.ImageSource
import earth.worldwind.shape.Polygon
import earth.worldwind.shape.ShapeAttributes

class SurfaceImageTutorial(private val engine: WorldWind) : AbstractTutorial() {

    private val layer = RenderableLayer("Surface image").apply {
        // Configure a Surface Image to display an Android resource showing the WorldWindEarth logo.
        val sector1 = Sector.fromDegrees(37.46, 15.5, 0.5, 0.6)
        addRenderable(
            Polygon(
                listOf(
                    Position(sector1.minLatitude, sector1.minLongitude, 0.0),
                    Position(sector1.minLatitude, sector1.maxLongitude, 0.0),
                    Position(sector1.maxLatitude, sector1.maxLongitude, 0.0),
                    Position(sector1.maxLatitude, sector1.minLongitude, 0.0),
                ),
                ShapeAttributes().apply { interiorImageSource = ImageSource.fromResource(MR.images.worldwind_logo) }
            ).apply {
                altitudeMode = AltitudeMode.CLAMP_TO_GROUND
                isFollowTerrain = true
            }
        )

        // Configure a Surface Image to display a remote image showing Mount Etna erupting on July 13th, 2001.
        val sector2 = Sector.fromDegrees(37.46543388598137, 14.60128369746704, 0.45360804083528, 0.75704283995502)
        addRenderable(
            Polygon(
                listOf(
                    Position(sector2.minLatitude, sector2.minLongitude, 0.0),
                    Position(sector2.minLatitude, sector2.maxLongitude, 0.0),
                    Position(sector2.maxLatitude, sector2.maxLongitude, 0.0),
                    Position(sector2.maxLatitude, sector2.minLongitude, 0.0),
                ),
                ShapeAttributes().apply { interiorImageSource = ImageSource.fromUrlString("https://worldwind.arc.nasa.gov/android/tutorials/data/etna.jpg") }
            ).apply {
                altitudeMode = AltitudeMode.CLAMP_TO_GROUND
                isFollowTerrain = true
            }
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