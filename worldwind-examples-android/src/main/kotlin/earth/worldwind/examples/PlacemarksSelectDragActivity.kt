package earth.worldwind.examples

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import earth.worldwind.BasicWorldWindowController
import earth.worldwind.PickedObjectList
import earth.worldwind.WorldWindow
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.LookAt
import earth.worldwind.geom.Offset.Companion.bottomCenter
import earth.worldwind.geom.Position
import earth.worldwind.geom.Position.Companion.fromDegrees
import earth.worldwind.geom.Vec2
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.Color
import earth.worldwind.render.Renderable
import earth.worldwind.render.image.ImageSource.Companion.fromResource
import earth.worldwind.shape.Highlightable
import earth.worldwind.shape.Placemark
import earth.worldwind.shape.Placemark.Companion.createWithImage
import earth.worldwind.shape.PlacemarkAttributes
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * This Activity demonstrates how to implement gesture detectors for picking, selecting, dragging, editing and context.
 * In this example, a custom WorldWindowController is created to handle the tap, scroll and long press gestures.  Also,
 * this example shows how to use a Renderable's "userProperty" to convey capabilities to the controller and to exchange
 * information with an editor.
 *
 *
 * This example displays a scene with three airports, three aircraft and two automobiles.  You can select, move and edit
 * the vehicles with the single tap, drag, and double-tap gestures accordingly.  The airport icons are pickable, but
 * selectable--performing a long-press on an airport will display its name.
 */
open class PlacemarksSelectDragActivity: GeneralGlobeActivity() {
    /**
     * A custom WorldWindowController object that handles the select, drag and navigation gestures.
     */
    private lateinit var controller: SelectDragNavigateController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        aboutBoxTitle = "About the " + resources.getText(R.string.title_placemarks_select_drag)
        aboutBoxText = """
    Demonstrates how to select and drag Placemarks.
    
    Single-tap an icon to toggle its selection.
    Double-tap a vehicle icon to open an editor.
    Dragging a selected vehicle icon moves it.
    Long-press displays some context information.
    
    Vehicle icons are selectable, movable, and editable.
    Airport icons are display only.
    """.trimIndent()

        // Initialize the mapping of vehicle types to their icons.
        for (i in aircraftTypes.indices) aircraftIconMap[aircraftTypes[i]] = aircraftIcons[i]
        for (i in automotiveTypes.indices) automotiveIconMap[automotiveTypes[i]] = automotiveIcons[i]

        // Override the WorldWindow's built-in navigation behavior with conditional dragging support.
        controller = SelectDragNavigateController(wwd)
        wwd.controller = controller

        // Add a layer for placemarks to the WorldWindow
        val layer = RenderableLayer("Placemarks")
        wwd.engine.layers.addLayer(layer)

        // Create some placemarks and add them to the layer
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
        layer.addRenderable(
            createAutomobilePlacemark(
                fromDegrees(34.210, -119.120, 0.0), "Civilian Vehicle", automotiveTypes[1]
            )
        )
        layer.addRenderable(
            createAutomobilePlacemark(
                fromDegrees(34.210, -119.160, 0.0), "Military Vehicle", automotiveTypes[4]
            )
        )
        layer.addRenderable(
            createAircraftPlacemark(
                fromDegrees(34.200, -119.207, 1000.0), "Commercial Aircraft", aircraftTypes[1]
            )
        )
        layer.addRenderable(
            createAircraftPlacemark(
                fromDegrees(34.210, -119.150, 2000.0), "Military Aircraft", aircraftTypes[3]
            )
        )
        layer.addRenderable(
            createAircraftPlacemark(
                fromDegrees(34.150, -119.150, 500.0), "Private Aircraft", aircraftTypes[0]
            )
        )

        // And finally, for this demo, position the viewer to look at the placemarks
        val lookAt = LookAt().setDegrees(
            latitudeDegrees = 34.150,
            longitudeDegrees = -119.150,
            altitudeMeters = 0.0,
            altitudeMode = AltitudeMode.ABSOLUTE,
            rangeMeters = 2e4,
            headingDegrees = 0.0,
            tiltDegrees = 45.0,
            rollDegrees = 0.0
        )
        wwd.engine.cameraFromLookAt(lookAt)
    }

    /**
     * This inner class is a custom WorldWindController that handles picking, dragging and globe navigation via a
     * combination of the native WorldWind navigation gestures and Android gestures. This class' onTouchEvent method
     * arbitrates between select and drag gestures and globe navigation gestures.
     */
    open inner class SelectDragNavigateController(wwd: WorldWindow) : BasicWorldWindowController(wwd) {
        protected lateinit var pickRequest: Deferred<PickedObjectList> // last picked objects from onDown event
        var selectedObject: Renderable? = null // last "selected" object from single tap or double tap
        protected var isDragging = false
        protected var isDraggingArmed = false
        private val dragRefPt = Vec2()

        /**
         * Assign a subclassed SimpleOnGestureListener to a GestureDetector to handle the selection and drag gestures.
         */
        override val selectDragDetector = GestureDetector(applicationContext, object : SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean {
                pick(event) // Pick the object(s) at the tap location
                return false // By not consuming this event, we allow it to pass on to the navigation gesture handlers
            }

            override fun onSingleTapUp(event: MotionEvent): Boolean {
                // This single-tap handler has a faster response time than onSingleTapConfirmed.
                wwd.mainScope.launch {
                    // Get top picked object
                    toggleSelection(pickRequest.await().topPickedObject?.userObject)
                }

                // We do not consume this event; we allow the "up" event to pass on to the navigation gestures,
                // which is required for proper zoom gestures.  Consuming this event will cause the first zoom
                // gesture to be ignored.
                //
                // A drawback to using this callback is that a the first tap of a double-tapping will temporarily
                // deselect an item, only to reselected on the second tap.
                //
                // As an alternative, you can implement onSingleTapConfirmed and consume event as you would expect,
                // with the trade-off being a slight delay in the tap response time.
                return false
            }

            override fun onScroll(
                downEvent: MotionEvent, moveEvent: MotionEvent, distanceX: Float, distanceY: Float
            ): Boolean {
                return if (isDraggingArmed) drag(distanceX, distanceY) else false // Move the selected object
            }

            override fun onDoubleTap(event: MotionEvent) = runBlocking {
                // Get top picked object
                val pickedObject = pickRequest.await().topPickedObject?.userObject

                // Note that double-tapping should not toggle a "selected" object's selected state
                if (pickedObject !== selectedObject) toggleSelection(pickedObject) // deselects a previously selected item
                if (pickedObject === selectedObject) {
                    edit() // Open the placemark editor
                    true
                } else false
            }

            override fun onLongPress(event: MotionEvent) {
                pick(event)
                contextMenu()
            }
        })

        /**
         * Delegates events to the select/drag handlers or the native WorldWind navigation handlers.
         */
        override fun onTouchEvent(event: MotionEvent): Boolean {
            // Allow our select and drag handlers to process the event first. They'll set the state flags which will
            // either preempt or allow the event to be subsequently processed by the globe's navigation event handlers.
            val consumed = selectDragDetector.onTouchEvent(event)

            // Is a dragging operation started or in progress? Any ACTION_UP event cancels a drag operation.
            if (isDragging && event.action == MotionEvent.ACTION_UP) {
                isDragging = false
                isDraggingArmed = false
            }
            // Preempt the globe's pan navigation recognizer if we're dragging
            super.panRecognizer.isEnabled = !isDragging

            // Pass on the event on to the default globe navigation handlers
            return if (!consumed) super.onTouchEvent(event) else true
        }

        /**
         * Performs a pick at the tap location and conditionally arms the dragging flag, so that dragging can occur if
         * the next event is an onScroll event.
         */
        fun pick(event: MotionEvent) {
            // Perform the pick at the screen x, y
            pickRequest = wwd.pickAsync(event.x, event.y)
            wwd.mainScope.launch {
                // Get top picked object
                val pickedObject = pickRequest.await().topPickedObject?.userObject

                // Determine whether the dragging flag should be "armed". The prerequisite for dragging that an object must
                // have been previously selected (via a single tap) and the selected object must manifest a "movable"
                // capability.
                isDraggingArmed = selectedObject === pickedObject && selectedObject?.hasUserProperty(MOVABLE) == true
            }
        }

        /**
         * Toggles the selected state of the picked object.
         */
        fun toggleSelection(pickedObject: Any?) {
            // Test if last picked object is "selectable".  If not, retain the
            // currently selected object. To discard the current selection,
            // the user must pick another selectable object or the current object.
            if (pickedObject is Renderable) {
                if (pickedObject.hasUserProperty(SELECTABLE)) {
                    val isNewSelection = pickedObject !== selectedObject

                    // Display the highlight or normal attributes to indicate the
                    // selected or unselected state respectively.
                    if (pickedObject is Highlightable) {
                        // Only one object can be selected at time, deselect any previously selected object
                        if (isNewSelection && selectedObject is Highlightable) (selectedObject as Highlightable).isHighlighted = false
                        (pickedObject as Highlightable).isHighlighted = isNewSelection
                        wwd.requestRedraw()
                    }
                    // Track the selected object
                    selectedObject = if (isNewSelection) pickedObject else null
                } else {
                    Toast.makeText(applicationContext, "The picked object is not selectable.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        /**
         * Moves the selected object to the event's screen position.
         *
         * @return true if the event was consumed
         */
        fun drag(distanceX: Float, distanceY: Float): Boolean {
            val selectedObject = selectedObject
            if (isDraggingArmed && selectedObject is Placemark) {
                // Signal that dragging is in progress
                isDragging = true

                // First we compute the screen coordinates of the position's "ground" point.  We'll apply the
                // screen X and Y drag distances to this point, from which we'll compute a new position,
                // wherein we restore the original position's altitude.
                val position = selectedObject.position
                val altitude = position.altitude
                if (wwd.engine.geographicToScreenPoint(position.latitude, position.longitude, 0.0, dragRefPt)) {
                    // Update the placemark's ground position
                    if (wwd.engine.screenPointToGroundPosition(dragRefPt.x - distanceX, dragRefPt.y - distanceY, position)) {
                        // Restore the placemark's original altitude
                        position.altitude = altitude
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

        fun cancelDragging() {
            isDragging = false
            isDraggingArmed = false
        }

        /**
         * Edits the currently selected object.
         */
        fun edit() {
            val selectedObject = selectedObject
            if (selectedObject is Placemark && selectedObject.hasUserProperty(EDITABLE)) {
                // Pass the current aircraft type in a Bundle
                val args = Bundle()
                args.putString("title", "Select the " + selectedObject.displayName + "'s type")
                if (selectedObject.hasUserProperty(AIRCRAFT_TYPE)) {
                    args.putString("vehicleKey", AIRCRAFT_TYPE)
                    args.putString("vehicleValue", selectedObject.getUserProperty(AIRCRAFT_TYPE) as String?)
                } else if (selectedObject.hasUserProperty(AUTOMOTIVE_TYPE)) {
                    args.putString("vehicleKey", AUTOMOTIVE_TYPE)
                    args.putString("vehicleValue", selectedObject.getUserProperty(AUTOMOTIVE_TYPE) as String?)
                }

                // The VehicleTypeDialog calls onFinished
                val dialog = VehicleTypeDialog()
                dialog.arguments = args
                dialog.show(supportFragmentManager, "aircraft_type")
            } else {
                Toast.makeText(
                    applicationContext,
                    (if (selectedObject == null) "Object" else selectedObject.displayName) + " is not editable.",
                    Toast.LENGTH_LONG).show()
            }
        }

        /**
         * Shows the context information for the WorldWindow.
         */
        fun contextMenu() {
            wwd.mainScope.launch {
                // Get top picked object
                val pickedObject = pickRequest.await().topPickedObject?.userObject
                if (pickedObject is Renderable?) {
                    val selectedObject = selectedObject
                    Toast.makeText(
                        applicationContext,
                        (if (pickedObject == null) "Nothing" else pickedObject.displayName) + " picked and "
                                + (if (selectedObject == null) "nothing" else selectedObject.displayName) + " selected.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * This inner class creates a simple Placemark editor for selecting the vehicle type.
     */
    class VehicleTypeDialog: DialogFragment() {
        private var selectedItem = -1
        private lateinit var vehicleKey: String
        private lateinit var vehicleTypes: Array<String>
        private lateinit var vehicleIcons: Map<String, Int>

        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            val args = requireArguments()
            val title = args.getString("title", "")

            // Determine type of vehicles displayed in this dialog
            vehicleKey = args.getString("vehicleKey", "")
            when (vehicleKey) {
                AIRCRAFT_TYPE -> {
                    vehicleTypes = aircraftTypes
                    vehicleIcons = aircraftIconMap
                }
                AUTOMOTIVE_TYPE -> {
                    vehicleTypes = automotiveTypes
                    vehicleIcons = automotiveIconMap
                }
            }
            // Determine the initial selection
            val type = args.getString("vehicleValue", "")
            for (i in vehicleTypes.indices) {
                if (type == vehicleTypes[i]) {
                    selectedItem = i
                    break
                }
            }

            // Create "single selection" list of aircraft vehicleTypes
            return AlertDialog.Builder(requireActivity())
                .setTitle(title)
                // The OK button will update the selected placemark's aircraft type
                .setSingleChoiceItems(vehicleTypes, selectedItem) { _: DialogInterface?, which: Int -> selectedItem = which }
                // A null handler will close the dialog
                .setPositiveButton(android.R.string.ok) { _: DialogInterface?, _: Int -> onFinished(vehicleTypes[selectedItem]) }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        }

        fun onFinished(vehicleType: String) {
            val activity = requireActivity() as PlacemarksSelectDragActivity
            val selectedObject = activity.controller.selectedObject
            if (selectedObject is Placemark) {
                val currentType = selectedObject.getUserProperty(vehicleKey) as String?
                if (vehicleType == currentType) return
                // Update the placemark's icon attributes and vehicle type property.
                val resId = vehicleIcons[vehicleType]
                if (resId != null) {
                    val imageSource = fromResource(resId)
                    selectedObject.putUserProperty(vehicleKey, vehicleType)
                    selectedObject.attributes.imageSource = imageSource
                    selectedObject.highlightAttributes?.imageSource = imageSource
                }
                // Show the change
                activity.wwd.requestRedraw()
            }
        }
    }

    companion object {
        /**
         * The EDITABLE capability, if it exists in a Placemark's user properties, allows editing with a double-tap
         */
        const val EDITABLE = "editable"
        /**
         * The MOVABLE capability, if it exists in a Placemark's user properties, allows dragging (after being selected)
         */
        const val MOVABLE = "movable"
        /**
         * The SELECTABLE capability, if it exists in a Placemark's user properties, allows selection with single-tap
         */
        const val SELECTABLE = "selectable"
        /**
         * Placemark user property vehicleKey for the type of aircraft
         */
        const val AIRCRAFT_TYPE = "aircraft_type"
        /**
         * Placemark user property vehicleKey for the type of vehicle
         */
        const val AUTOMOTIVE_TYPE = "auotomotive_type"
        // Aircraft vehicleTypes used in the Placemark editing dialog
        private val aircraftTypes = arrayOf(
            "Small Plane", "Twin Engine", "Passenger Jet", "Fighter Jet", "Bomber", "Helicopter"
        )

        // Vehicle vehicleTypes used in the Placemark editing dialog
        private val automotiveTypes = arrayOf(
            "Car", "SUV", "4x4", "Truck", "Jeep", "Tank"
        )

        // Resource IDs for aircraft icons
        private val aircraftIcons = intArrayOf(
            R.drawable.aircraft_small,
            R.drawable.aircraft_twin,
            R.drawable.aircraft_jet,
            R.drawable.aircraft_fighter,
            R.drawable.aircraft_bomber,
            R.drawable.aircraft_rotor
        )

        // Resource IDs for vehicle icons
        private val automotiveIcons = intArrayOf(
            R.drawable.vehicle_car,
            R.drawable.vehicle_suv,
            R.drawable.vehicle_4x4,
            R.drawable.vehicle_truck,
            R.drawable.vehicle_jeep,
            R.drawable.vehicle_tank
        )
        private const val NORMAL_IMAGE_SCALE = 3.0
        private const val HIGHLIGHTED_IMAGE_SCALE = 4.0

        // Aircraft vehicleTypes mapped to icons
        private val aircraftIconMap = mutableMapOf<String, Int>()
        private val automotiveIconMap = mutableMapOf<String, Int>()

        /**
         * Helper method to create airport placemarks.
         */
        protected fun createAirportPlacemark(position: Position, airportName: String): Placemark {
            val placemark = createWithImage(position, fromResource(R.drawable.airport_terminal))
            placemark.attributes.imageOffset = bottomCenter()
            placemark.attributes.imageScale = NORMAL_IMAGE_SCALE
            placemark.displayName = airportName
            placemark.altitudeMode = AltitudeMode.CLAMP_TO_GROUND
            return placemark
        }

        /**
         * Helper method to create aircraft placemarks. The aircraft are selectable, movable, and editable.
         */
        protected fun createAircraftPlacemark(position: Position, aircraftName: String, aircraftType: String): Placemark {
            val resId = aircraftIconMap[aircraftType]
            return if (resId != null) {
                val placemark = createWithImage(position, fromResource(resId))
                placemark.attributes.imageOffset = bottomCenter()
                placemark.attributes.imageScale = NORMAL_IMAGE_SCALE
                placemark.attributes.isDrawLeader = true
                placemark.attributes.leaderAttributes.outlineWidth = 4f
                val highlightedAttributes = PlacemarkAttributes(placemark.attributes)
                highlightedAttributes.imageScale = HIGHLIGHTED_IMAGE_SCALE
                highlightedAttributes.imageColor = Color(android.graphics.Color.YELLOW)
                placemark.highlightAttributes = highlightedAttributes
                placemark.displayName = aircraftName
                placemark.altitudeMode = AltitudeMode.ABSOLUTE
                // The AIRCRAFT_TYPE property is used to exchange the vehicle type with the VehicleTypeDialog
                placemark.putUserProperty(AIRCRAFT_TYPE, aircraftType)
                // The select/drag controller will examine a placemark's "capabilities" to determine what operations are applicable:
                placemark.putUserProperty(SELECTABLE, true)
                placemark.putUserProperty(EDITABLE, true)
                placemark.putUserProperty(MOVABLE, true)
                placemark
            } else {
                throw IllegalArgumentException("$aircraftType is not valid.")
            }
        }

        /**
         * Helper method to create vehicle placemarks.
         */
        protected fun createAutomobilePlacemark(position: Position, name: String, automotiveType: String): Placemark {
            val resId = automotiveIconMap[automotiveType]
            return if (resId != null) {
                val placemark = createWithImage(position, fromResource(resId))
                placemark.attributes.imageOffset = bottomCenter()
                placemark.attributes.imageScale = NORMAL_IMAGE_SCALE
                val highlightedAttributes = PlacemarkAttributes(placemark.attributes)
                highlightedAttributes.imageScale = HIGHLIGHTED_IMAGE_SCALE
                highlightedAttributes.imageColor = Color(android.graphics.Color.YELLOW)
                placemark.highlightAttributes = highlightedAttributes
                placemark.displayName = name
                placemark.altitudeMode = AltitudeMode.CLAMP_TO_GROUND
                // The AUTOMOTIVE_TYPE property is used to exchange the vehicle type with the VehicleTypeDialog
                placemark.putUserProperty(AUTOMOTIVE_TYPE, automotiveType)
                // The select/drag controller will examine a placemark's "capabilities" to determine what operations are applicable:
                placemark.putUserProperty(SELECTABLE, true)
                placemark.putUserProperty(EDITABLE, true)
                placemark.putUserProperty(MOVABLE, true)
                placemark
            } else throw IllegalArgumentException("$automotiveType is not valid.")
        }
    }
}