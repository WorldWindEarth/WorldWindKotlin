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
import earth.worldwind.shape.OmnidirectionalSightline
import earth.worldwind.shape.DirectionalSightline
import earth.worldwind.shape.Placemark
import earth.worldwind.shape.Polygon
import earth.worldwind.shape.ShapeAttributes

class SightlineTutorial(engine: WorldWind) : AbstractTutorial(engine) {

    private val omniSightline: OmnidirectionalSightline
    private val directionalSightline: DirectionalSightline

    private val layer = RenderableLayer("Sightline").apply {
        // Sightline origins. Each placemark drives its sightline's center; the sightlines
        // themselves are not pickable so the placemark is the only drag handle.
        val omnidirectionalPosition = Position.fromDegrees(46.230, -122.190, 2500.0)
        val directionalPosition = Position.fromDegrees(46.193, -122.194, 2500.0)

        omniSightline = OmnidirectionalSightline(omnidirectionalPosition, 10000.0).apply {
            isPickEnabled = false
            attributes.apply { interiorColor = Color(0f, 1f, 0f, 0.5f) }
            occludeAttributes.apply { interiorColor = Color(0.1f, 0.1f, 0.1f, 0.8f) }
        }
        addRenderable(omniSightline)

        addRenderable(object : Placemark(omnidirectionalPosition) {
            override fun moveTo(globe: Globe, position: Position) {
                super.moveTo(globe, position)
                omniSightline.position.copy(position)
            }
        }.apply {
            attributes.apply {
                imageSource = ImageSource.fromResource(MR.images.aircraft_fixwing)
                imageOffset = Offset.bottomCenter()
                imageScale = 2.0
                isDrawLeader = true
            }
        })

        directionalSightline = DirectionalSightline(
            directionalPosition, 10000.0, 40.0.degrees, 30.0.degrees
        ).apply {
            isPickEnabled = false
            attributes.apply { interiorColor = Color(0f, 0f, 1f, 0.5f) }
            occludeAttributes.apply { interiorColor = Color(0.1f, 0.1f, 0.1f, 0.8f) }
        }
        addRenderable(directionalSightline)

        addRenderable(object : Placemark(directionalPosition) {
            override fun moveTo(globe: Globe, position: Position) {
                super.moveTo(globe, position)
                directionalSightline.position.copy(position)
            }
        }.apply {
            attributes.apply {
                imageSource = ImageSource.fromResource(MR.images.aircraft_fixwing)
                imageOffset = Offset.bottomCenter()
                imageScale = 2.0
                isDrawLeader = true
            }
        })

        // A pair of extruded 3D obstacles — one inside each sightline — to demonstrate that
        // world-space shapes also cast shadows (in addition to terrain).
        val obstacleAttributes = ShapeAttributes().apply {
            interiorColor = Color(1f, 0.9f, 0.6f, 1f)
            outlineColor = Color(0.4f, 0.3f, 0.1f, 1f)
            outlineWidth = 1.5f
            isDrawVerticals = true
        }
        fun box(centerLat: Double, centerLon: Double, halfSize: Double, topAlt: Double) = Polygon(
            listOf(
                Position.fromDegrees(centerLat - halfSize, centerLon - halfSize, topAlt),
                Position.fromDegrees(centerLat - halfSize, centerLon + halfSize, topAlt),
                Position.fromDegrees(centerLat + halfSize, centerLon + halfSize, topAlt),
                Position.fromDegrees(centerLat + halfSize, centerLon - halfSize, topAlt)
            )
        ).apply {
            attributes = obstacleAttributes
            altitudeMode = AltitudeMode.RELATIVE_TO_GROUND
            isExtrude = true
        }
        addRenderable(box(46.225, -122.205, 0.004, 2200.0)) // inside the omnidirectional sphere
        addRenderable(box(46.205, -122.180, 0.003, 1800.0)) // inside the directional frustum
    }

    override fun start() {
        super.start()
        engine.layers.addLayer(layer)
        engine.cameraFromLookAt(
            LookAt(
                position = Position(46.230.degrees, (-122.190).degrees, 500.0), altitudeMode = AltitudeMode.ABSOLUTE,
                range = 1.5e4, heading = 45.0.degrees, tilt = 70.0.degrees, roll = 0.0.degrees
            )
        )
    }

    override fun stop() {
        super.stop()
        engine.layers.removeLayer(layer)
    }

}
