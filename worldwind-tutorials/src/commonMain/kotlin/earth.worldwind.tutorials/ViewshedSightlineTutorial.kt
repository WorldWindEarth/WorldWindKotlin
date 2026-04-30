package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.LookAt
import earth.worldwind.geom.Offset
import earth.worldwind.geom.Position
import earth.worldwind.globe.Globe
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.Color
import earth.worldwind.render.image.ImageSource
import earth.worldwind.shape.Placemark
import earth.worldwind.shape.ViewshedArea
import earth.worldwind.shape.ViewshedSightline

class ViewshedSightlineTutorial(engine: WorldWind) : AbstractTutorial(engine) {

    private val circleViewshed: ViewshedSightline
    private val rectangleViewshed: ViewshedSightline

    private val layer = RenderableLayer("Viewshed Sightline").apply {
        // Two viewshed footprints around Mount St. Helens — one circular, one rectangular,
        // chosen to highlight contrasting line-of-sight scenarios:
        //   * The circle observer hovers ~120 m above MSH's south crater rim (rim ~2549 m
        //     MSL, observer at 2650 m HAE in ABSOLUTE mode) at the geometric center of the
        //     crater, 15 km radius — looking down into the crater and outward over the
        //     surrounding ridges.
        //   * The rectangle observer sits 30 m above the surface of Swift Reservoir (the
        //     Lewis River impoundment ~15 km south-southeast of MSH summit). The 14 km × 4
        //     km rectangle is oriented E-W to track the long axis of the reservoir, so
        //     visibility forms a bright corridor along the water while the canyon walls
        //     to the north and south cast clean shadows perpendicular to the reservoir.
        // Drag either placemark to a peak and the visible region grows to the full AOI;
        // that's correct line-of-sight from a summit observer, not a kernel bug.
        val circlePosition = Position.fromDegrees(46.200, -122.188, 2650.0)
        val rectanglePosition = Position.fromDegrees(46.050, -122.100, 30.0)

        circleViewshed = ViewshedSightline(
            circlePosition, ViewshedArea.Circle(radius = 15000.0)
        ).apply {
            isPickEnabled = false
            altitudeMode = AltitudeMode.ABSOLUTE
            attributes.apply { interiorColor = Color(0f, 1f, 0f, 0.5f) }
            occludeAttributes.apply { interiorColor = Color(0.1f, 0.1f, 0.1f, 0.8f) }
        }
        addRenderable(circleViewshed)

        addRenderable(object : Placemark(circlePosition) {
            override fun moveTo(globe: Globe, position: Position) {
                super.moveTo(globe, position)
                circleViewshed.position.copy(position)
            }
        }.apply {
            altitudeMode = AltitudeMode.ABSOLUTE
            attributes.apply {
                imageSource = ImageSource.fromResource(MR.images.aircraft_fixwing)
                imageOffset = Offset.bottomCenter()
                imageScale = 2.0
                isDrawLeader = true
            }
        })

        rectangleViewshed = ViewshedSightline(
            rectanglePosition, ViewshedArea.Rectangle(widthMeters = 14000.0, heightMeters = 4000.0)
        ).apply {
            isPickEnabled = false
            altitudeMode = AltitudeMode.RELATIVE_TO_GROUND
            attributes.apply { interiorColor = Color(0f, 0f, 1f, 0.5f) }
            occludeAttributes.apply { interiorColor = Color(0.1f, 0.1f, 0.1f, 0.8f) }
        }
        addRenderable(rectangleViewshed)

        addRenderable(object : Placemark(rectanglePosition) {
            override fun moveTo(globe: Globe, position: Position) {
                super.moveTo(globe, position)
                rectangleViewshed.position.copy(position)
            }
        }.apply {
            altitudeMode = AltitudeMode.RELATIVE_TO_GROUND
            attributes.apply {
                imageSource = ImageSource.fromResource(MR.images.aircraft_fixwing)
                imageOffset = Offset.bottomCenter()
                imageScale = 2.0
                isDrawLeader = true
            }
        })
    }

    override fun start() {
        super.start()
        engine.layers.addLayer(layer)
        // Camera framed due south of the scene looking north (heading = 0°). The lookAt point
        // sits about midway between Swift Reservoir (foreground at ~46.06°N) and the MSH
        // crater (background at ~46.20°N), so both viewsheds — the blue corridor along the
        // reservoir and the green crater disc above it — are visible at once.
        engine.cameraFromLookAt(
            LookAt(
                position = Position(46.130.degrees, (-122.130).degrees, 0.0),
                altitudeMode = AltitudeMode.ABSOLUTE,
                range = 2.3e4, heading = 0.0.degrees, tilt = 60.0.degrees, roll = 0.0.degrees
            )
        )
    }

    override fun stop() {
        super.stop()
        engine.layers.removeLayer(layer)
    }

}
