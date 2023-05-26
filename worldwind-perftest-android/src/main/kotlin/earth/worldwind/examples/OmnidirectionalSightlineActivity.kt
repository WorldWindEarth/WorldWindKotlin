package earth.worldwind.examples

import android.os.Bundle
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.ZERO
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.LookAt
import earth.worldwind.geom.Offset
import earth.worldwind.geom.Position
import earth.worldwind.geom.Position.Companion.fromDegrees
import earth.worldwind.gesture.SelectDragCallback
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.Color
import earth.worldwind.render.Renderable
import earth.worldwind.render.image.ImageSource.Companion.fromResource
import earth.worldwind.shape.OmnidirectionalSightline
import earth.worldwind.shape.Placemark
import earth.worldwind.shape.ShapeAttributes

/**
 * This Activity demonstrates the OmnidirectionalSightline object which provides a visual representation of line of
 * sight from a specified origin. Terrain visible from the origin is colored differently than areas not visible from
 * the OmnidirectionalSightline origin. Line of sight is calculated as a straight line from the origin to the available
 * terrain.
 */
open class OmnidirectionalSightlineActivity: BasicGlobeActivity() {
    /**
     * The OmnidirectionalSightline object which will display areas visible using a line of sight from the origin
     */
    protected lateinit var sightline: OmnidirectionalSightline
    /**
     * A Placemark representing the origin of the sightline
     */
    protected lateinit var sightlinePlacemark: Placemark

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        aboutBoxTitle = "About the " + resources.getText(R.string.title_movable_line_of_sight)
        aboutBoxText = "Demonstrates a draggable WorldWind Omnidirectional sightline. Drag the placemark icon around the " +
                "screen to move the sightline position."

        // Initialize the OmnidirectionalSightline and Corresponding Placemark
        // The position is the line of sight origin for determining visible terrain
        val pos = fromDegrees(46.202, -122.190, 500.0)
        sightline = OmnidirectionalSightline(pos, 10000.0).apply {
            attributes = ShapeAttributes().apply { interiorColor = Color(0f, 1f, 0f, 0.5f) }
            occludeAttributes = ShapeAttributes().apply { interiorColor = Color(0.1f, 0.1f, 0.1f, 0.8f) }
            altitudeMode = AltitudeMode.RELATIVE_TO_GROUND
        }
        sightlinePlacemark = Placemark(pos).apply {
            altitudeMode = AltitudeMode.RELATIVE_TO_GROUND
            attributes.apply {
                imageSource = fromResource(R.drawable.aircraft_fixwing)
                imageScale = 2.0
                isDrawLeader = true
                imageOffset = Offset.bottomCenter()
            }
        }

        // Establish a layer to hold the sightline and placemark
        wwd.engine.layers.addLayer(
            RenderableLayer().apply {
                addRenderable(sightline)
                addRenderable(sightlinePlacemark)
            }
        )

        // Add dragging callback
        wwd.selectDragDetector.callback = object : SelectDragCallback {
            override fun canMoveRenderable(renderable: Renderable) = renderable === sightlinePlacemark
            override fun onRenderableMoved(renderable: Renderable, fromPosition: Position, toPosition: Position) {
                sightline.position = toPosition
            }
        }

        // And finally, for this demo, position the viewer to look at the sightline position
        val lookAt = LookAt(
            position = pos, altitudeMode = AltitudeMode.ABSOLUTE, range = 2e4,
            heading = ZERO, tilt = 45.0.degrees, roll = ZERO
        )
        wwd.engine.cameraFromLookAt(lookAt)
    }
}