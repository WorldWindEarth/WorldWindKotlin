package earth.worldwind.examples

import android.os.Bundle
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import earth.worldwind.BasicWorldWindowController
import earth.worldwind.WorldWindow
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.ZERO
import earth.worldwind.geom.Angle.Companion.fromDegrees
import earth.worldwind.geom.LookAt
import earth.worldwind.geom.Position.Companion.fromDegrees
import earth.worldwind.geom.Vec2
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.Color
import earth.worldwind.render.image.ImageSource.Companion.fromResource
import earth.worldwind.shape.OmnidirectionalSightline
import earth.worldwind.shape.Placemark
import earth.worldwind.shape.ShapeAttributes
import kotlinx.coroutines.launch

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
    /**
     * A custom WorldWindowController object that handles the select, drag and navigation gestures.
     */
    protected lateinit var controller: SimpleSelectDragNavigateController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        aboutBoxTitle = "About the " + resources.getText(R.string.title_movable_line_of_sight)
        aboutBoxText = "Demonstrates a draggable WorldWind Omnidirectional sightline. Drag the placemark icon around the " +
                "screen to move the sightline position."

        // Initialize attributes for the OmnidirectionalSightline
        val viewableRegions = ShapeAttributes()
        viewableRegions.interiorColor = Color(0f, 1f, 0f, 0.5f)
        val blockedRegions = ShapeAttributes()
        blockedRegions.interiorColor = Color(0.1f, 0.1f, 0.1f, 0.8f)

        // Initialize the OmnidirectionalSightline and Corresponding Placemark
        // The position is the line of sight origin for determining visible terrain
        val pos = fromDegrees(46.202, -122.190, 500.0)
        sightline = OmnidirectionalSightline(pos, 10000.0)
        sightline.attributes = viewableRegions
        sightline.occludeAttributes = blockedRegions
        sightline.altitudeMode = AltitudeMode.RELATIVE_TO_GROUND
        sightlinePlacemark = Placemark(pos)
        sightlinePlacemark.altitudeMode = AltitudeMode.RELATIVE_TO_GROUND
        sightlinePlacemark.attributes.imageSource = fromResource(R.drawable.aircraft_fixwing)
        sightlinePlacemark.attributes.imageScale = 2.0
        sightlinePlacemark.attributes.isDrawLeader = true

        // Establish a layer to hold the sightline and placemark
        val sightlineLayer = RenderableLayer()
        sightlineLayer.addRenderable(sightline)
        sightlineLayer.addRenderable(sightlinePlacemark)
        wwd.engine.layers.addLayer(sightlineLayer)

        // Override the WorldWindow's built-in navigation behavior with conditional dragging support.
        controller = SimpleSelectDragNavigateController(wwd)
        wwd.controller = controller

        // And finally, for this demo, position the viewer to look at the sightline position
        val lookAt = LookAt(
            position = pos, altitudeMode = AltitudeMode.ABSOLUTE, range = 2e4,
            heading = ZERO, tilt = fromDegrees(45.0), roll = ZERO
        )
        wwd.engine.cameraFromLookAt(lookAt)
    }

    /**
     * This inner class is based on the controller in the [PlacemarksSelectDragActivity] but has been simplified
     * for the single Placemark.
     */
    open inner class SimpleSelectDragNavigateController(wwd: WorldWindow) : BasicWorldWindowController(wwd) {
        protected var isDragging = false
        protected var isDraggingArmed = false
        private val dragRefPt = Vec2()

        /**
         * Assign a subclassed SimpleOnGestureListener to a GestureDetector to handle the drag gestures.
         */
        override val selectDragDetector = GestureDetector(applicationContext, object : SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean {
                pick(event) // Pick the object(s) at the tap location
                return false // By not consuming this event, we allow it to pass on to the navigation gesture handlers
            }

            override fun onScroll(
                downEvent: MotionEvent, moveEvent: MotionEvent, distanceX: Float, distanceY: Float
            ) = if (isDraggingArmed) drag(distanceX, distanceY) else false
        })

        /**
         * Delegates events to the select/drag handlers or the native World Wind navigation handlers.
         */
        override fun onTouchEvent(event: MotionEvent): Boolean {
            // Allow our select and drag handlers to process the event first. They'll set the state flags which will
            // either preempt or allow the event to be subsequently processed by the globe's navigation event handlers.
            var consumed = selectDragDetector.onTouchEvent(event)

            // Is a dragging operation started or in progress? Any ACTION_UP event cancels a drag operation.
            if (isDragging && event.action == MotionEvent.ACTION_UP) {
                isDragging = false
                isDraggingArmed = false
            }
            // Preempt the globe's pan navigation recognizer if we're dragging
            super.panRecognizer.isEnabled = !isDragging

            // Pass on the event on to the default globe navigation handlers
            if (!consumed) consumed = super.onTouchEvent(event)
            return consumed
        }

        /**
         * Moves the selected object to the event's screen position.
         *
         * @return true if the event was consumed
         */
        fun drag(distanceX: Float, distanceY: Float): Boolean {
            if (isDraggingArmed) {
                // Signal that dragging is in progress
                isDragging = true

                // First we compute the screen coordinates of the position's "ground" point.  We'll apply the
                // screen X and Y drag distances to this point, from which we'll compute a new position,
                // wherein we restore the original position's altitude.
                val position = sightlinePlacemark.referencePosition
                val altitude = position.altitude
                if (wwd.engine.geographicToScreenPoint(position.latitude, position.longitude, 0.0, dragRefPt)) {
                    // Update the placemark's ground position
                    if (wwd.engine.screenPointToGroundPosition(
                            dragRefPt.x - distanceX,
                            dragRefPt.y - distanceY,
                            position
                        )
                    ) {
                        // Restore the placemark's original altitude
                        position.altitude = altitude
                        // Move the sightline
                        sightline.position = position
                        // Reflect the change in position on the globe.
                        wwd.requestRedraw()
                        return true
                    }
                }
                // Probably clipped by near/far clipping plane or off the globe. The position was not updated. Stop the drag.
                isDraggingArmed = false
                return true // We consumed this event, even if dragging has been stopped.
            }
            return false
        }

        /**
         * Performs a pick at the tap location and conditionally arms the dragging flag, so that dragging can occur if
         * the next event is an onScroll event.
         */
        fun pick(event: MotionEvent) {
            // Perform the pick at the screen x, y
            val pickRequest = wwd.pickAsync(event.x, event.y)
            wwd.mainScope.launch {
                // Examine if top picked object is Placemark
                isDraggingArmed = pickRequest.await().topPickedObject?.userObject is Placemark
            }
        }
    }
}