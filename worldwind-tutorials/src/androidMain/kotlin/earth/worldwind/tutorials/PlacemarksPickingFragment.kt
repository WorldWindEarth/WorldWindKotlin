package earth.worldwind.tutorials

import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import earth.worldwind.BasicWorldWindowController
import earth.worldwind.PickedObjectList
import earth.worldwind.WorldWindow
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.LookAt
import earth.worldwind.geom.Offset.Companion.bottomCenter
import earth.worldwind.geom.Position
import earth.worldwind.geom.Position.Companion.fromDegrees
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.image.ImageSource.Companion.fromResource
import earth.worldwind.shape.Highlightable
import earth.worldwind.shape.Placemark
import earth.worldwind.shape.Placemark.Companion.createWithImage
import earth.worldwind.shape.PlacemarkAttributes
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch

class PlacemarksPickingFragment: BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow (GLSurfaceView) object with a WMS Layer
     *
     * @return The WorldWindow object containing the globe.
     */
    override fun createWorldWindow(): WorldWindow {
        // Let the super class (BasicGlobeFragment) do the creation
        val wwd = super.createWorldWindow()

        // Override the WorldWindow's built-in navigation behavior by adding picking support.
        wwd.controller = PickNavigateController(wwd)

        // Add a layer for placemarks to the WorldWindow
        val layer = RenderableLayer("Placemarks")
        wwd.engine.layers.addLayer(layer)

        // Create a few placemarks with highlight attributes and add them to the layer
        layer.addRenderable(
            createAirportPlacemark(
                fromDegrees(34.2000, -119.2070, 0.0), "Oxnard Airport"
            )
        )
        layer.addRenderable(
            createAirportPlacemark(
                fromDegrees(34.2138, -119.0944, 0.0), "Camarillo Airport"
            )
        )
        layer.addRenderable(
            createAirportPlacemark(
                fromDegrees(34.1193, -119.1196, 0.0), "Pt Mugu Naval Air Station"
            )
        )
        layer.addRenderable(createAircraftPlacemark(fromDegrees(34.15, -119.15, 2000.0)))

        // Position the viewer to look near the airports
        val lookAt = LookAt(
            position = Position(34.15.degrees, (-119.15).degrees, 0.0), altitudeMode = AltitudeMode.ABSOLUTE,
            range = 2e4, heading = 0.0.degrees, tilt = 45.0.degrees, roll = 0.0.degrees
        )
        wwd.engine.cameraFromLookAt(lookAt)
        return wwd
    }

    /**
     * This inner class is a custom WorldWindController that handles both picking and navigation via a combination of
     * the native WorldWind navigation gestures and Android gestures. This class' onTouchEvent method arbitrates
     * between pick events and globe navigation events.
     */
    open inner class PickNavigateController(wwd: WorldWindow) : BasicWorldWindowController(wwd) {
        private lateinit var pickRequest: Deferred<PickedObjectList> // last picked objects from onDown event
        private var selectedObject: Any? = null // last "selected" object from single tap

        /**
         * Assign a subclassed SimpleOnGestureListener to a GestureDetector to handle the "pick" events.
         */
        private val pickGestureDetector = GestureDetector(requireContext().applicationContext, object : SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean {
                pick(event) // Pick the object(s) at the tap location
                return false // By not consuming this event, we allow it to pass on to the navigation gesture handlers
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                toggleSelection() // Highlight the picked object

                // By not consuming this event, we allow the "up" event to pass on to the navigation gestures,
                // which is required for proper zoom gestures.  Consuming this event will cause the first zoom
                // gesture to be ignored.  As an alternative, you can implement onSingleTapConfirmed and consume
                // event as you would expect, with the trade-off being a slight delay tap response.
                return false
            }
        })

        /**
         * Delegates events to the pick handler or the native WorldWind navigation handlers.
         */
        override fun onTouchEvent(event: MotionEvent): Boolean {
            // Allow pick listener to process the event first.
            val consumed = pickGestureDetector.onTouchEvent(event)

            // If event was not consumed by the pick operation, pass it on the globe navigation handlers
            // The super class performs the pan, tilt, rotate and zoom
            return if (!consumed) super.onTouchEvent(event) else true
        }

        /**
         * Performs a pick at the tap location.
         */
        fun pick(event: MotionEvent) { pickRequest = wwd.pickAsync(event.x, event.y) }

        /**
         * Toggles the selected state of a picked object.
         */
        fun toggleSelection() {
            wwd.mainScope.launch {
                // Get the top-most object for our new picked object
                val pickedObject = pickRequest.await().topPickedObject?.userObject

                // Display the highlight or normal attributes to indicate the
                // selected or unselected state respectively.
                if (pickedObject is Highlightable) {
                    val oldSelectedObject = selectedObject

                    // Determine if we've picked a "new" object so we know to deselect the previous selection
                    val isNewSelection = pickedObject !== oldSelectedObject

                    // Only one object can be selected at time, deselect any previously selected object
                    if (isNewSelection && oldSelectedObject is Highlightable) oldSelectedObject.isHighlighted = false

                    // Show the selection by showing its highlight attributes
                    pickedObject.isHighlighted = isNewSelection
                    wwd.requestRedraw()

                    // Track the selected object
                    selectedObject = if (isNewSelection) pickedObject else null
                }
            }
        }
    }

    companion object {
        private const val NORMAL_IMAGE_SCALE = 3.0
        private const val HIGHLIGHTED_IMAGE_SCALE = 4.0

        /**
         * Helper method to create aircraft placemarks.
         */
        private fun createAircraftPlacemark(position: Position): Placemark {
            val placemark = createWithImage(position, fromResource(MR.images.aircraft_fighter))
            placemark.attributes.imageOffset = bottomCenter()
            placemark.attributes.imageScale = NORMAL_IMAGE_SCALE
            placemark.attributes.isDrawLeader = true
            val highlightedAttributes = PlacemarkAttributes(placemark.attributes)
            highlightedAttributes.imageScale = HIGHLIGHTED_IMAGE_SCALE
            placemark.highlightAttributes = highlightedAttributes
            placemark.altitudeMode = AltitudeMode.ABSOLUTE
            return placemark
        }

        /**
         * Helper method to create airport placemarks.
         */
        private fun createAirportPlacemark(position: Position, airportName: String): Placemark {
            val placemark = createWithImage(position, fromResource(MR.images.airport_terminal))
            placemark.attributes.imageOffset = bottomCenter()
            placemark.attributes.imageScale = NORMAL_IMAGE_SCALE
            val highlightedAttributes = PlacemarkAttributes(placemark.attributes)
            highlightedAttributes.imageScale = HIGHLIGHTED_IMAGE_SCALE
            placemark.highlightAttributes = highlightedAttributes
            placemark.displayName = airportName
            placemark.altitudeMode = AltitudeMode.CLAMP_TO_GROUND
            return placemark
        }
    }
}