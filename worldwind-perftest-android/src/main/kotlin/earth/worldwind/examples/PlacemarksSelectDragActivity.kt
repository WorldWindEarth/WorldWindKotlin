package earth.worldwind.examples

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.LookAt
import earth.worldwind.geom.Offset.Companion.bottomCenter
import earth.worldwind.geom.Position
import earth.worldwind.geom.Position.Companion.fromDegrees
import earth.worldwind.gesture.SelectDragCallback
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.Color
import earth.worldwind.render.Renderable
import earth.worldwind.render.image.ImageSource.Companion.fromResource
import earth.worldwind.shape.Highlightable
import earth.worldwind.shape.Placemark
import earth.worldwind.shape.Placemark.Companion.createWithImage
import earth.worldwind.shape.PlacemarkAttributes

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
    private var selectedObject: Renderable? = null // Last "selected" object from single tap or double tap

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

        // Add dragging callback
        wwd.selectDragDetector.callback = object : SelectDragCallback {
            override fun canPickRenderable(renderable: Renderable) = true //renderable.hasUserProperty(SELECTABLE)
            override fun canMoveRenderable(renderable: Renderable) = renderable === selectedObject && renderable.hasUserProperty(MOVABLE)
            override fun onRenderablePicked(renderable: Renderable, position: Position) = toggleSelection(renderable)
            override fun onRenderableContext(renderable: Renderable, position: Position) = contextMenu(renderable)
            override fun onTerrainContext(position: Position) = contextMenu()
            override fun onRenderableDoubleTap(renderable: Renderable, position: Position) {
                // Note that double-tapping should not toggle a "selected" object's selected state
                if (renderable !== selectedObject) toggleSelection(renderable) // deselects a previously selected item
                if (renderable === selectedObject) edit() // Open the placemark editor
            }

            /**
             * Toggles the selected state of the picked renderable.
             */
            private fun toggleSelection(renderable: Renderable) {
                // Test if last picked object is "selectable". If not, retain the
                // currently selected object. To discard the current selection,
                // the user must pick another selectable object or the current object.
                if (renderable.hasUserProperty(SELECTABLE)) {
                    val isNewSelection = renderable !== selectedObject

                    // Only one object can be selected at time, deselect any previously selected object
                    if (isNewSelection) (selectedObject as? Highlightable)?.isHighlighted = false

                    // Display the highlight or normal attributes to indicate the
                    // selected or unselected state respectively.
                    (renderable as? Highlightable)?.isHighlighted = isNewSelection

                    // Track the selected object
                    selectedObject = if (isNewSelection) renderable else null
                } else {
                    Toast.makeText(applicationContext, "The picked object is not selectable.", Toast.LENGTH_SHORT).show()
                }
            }

            /**
             * Shows the context information for the WorldWindow.
             */
            private fun contextMenu(renderable: Renderable? = null) {
                val so = selectedObject
                Toast.makeText(
                    applicationContext,
                    "${renderable?.displayName ?: "Nothing"} picked and ${so?.displayName ?: "nothing"} selected.",
                    Toast.LENGTH_LONG
                ).show()
            }

            /**
             * Edits the currently selected object.
             */
            private fun edit() {
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
                        "${selectedObject?.displayName ?: "Object"} is not editable.",
                        Toast.LENGTH_LONG).show()
                }
            }
        }

        // Add a layer for placemarks to the WorldWindow
        wwd.engine.layers.addLayer(
            RenderableLayer("Placemarks").apply {
                // Create some placemarks and add them to the layer
                addRenderable(
                    createAirportPlacemark(
                        fromDegrees(34.2000, -119.2070, 0.0), "Oxnard Airport"
                    )
                )
                addRenderable(
                    createAirportPlacemark(
                        fromDegrees(34.2138, -119.0944, 0.0), "Camarillo Airport"
                    )
                )
                addRenderable(
                    createAirportPlacemark(
                        fromDegrees(34.1193, -119.1196, 0.0), "Pt Mugu Naval Air Station"
                    )
                )
                addRenderable(
                    createAutomobilePlacemark(
                        fromDegrees(34.210, -119.120, 0.0), "Civilian Vehicle", automotiveTypes[1]
                    )
                )
                addRenderable(
                    createAutomobilePlacemark(
                        fromDegrees(34.210, -119.160, 0.0), "Military Vehicle", automotiveTypes[4]
                    )
                )
                addRenderable(
                    createAircraftPlacemark(
                        fromDegrees(34.200, -119.207, 1000.0), "Commercial Aircraft", aircraftTypes[1]
                    )
                )
                addRenderable(
                    createAircraftPlacemark(
                        fromDegrees(34.210, -119.150, 2000.0), "Military Aircraft", aircraftTypes[3]
                    )
                )
                addRenderable(
                    createAircraftPlacemark(
                        fromDegrees(34.150, -119.150, 500.0), "Private Aircraft", aircraftTypes[0]
                    )
                )
            }
        )

        // And finally, for this demo, position the viewer to look at the placemarks
        val lookAt = LookAt(
            position = Position(34.150.degrees, (-119.150).degrees, 0.0), altitudeMode = AltitudeMode.ABSOLUTE,
            range = 2e4, heading = 0.0.degrees, tilt = 45.0.degrees, roll = 0.0.degrees
        )
        wwd.engine.cameraFromLookAt(lookAt)
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
            val selectedObject = activity.selectedObject
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
        protected fun createAirportPlacemark(position: Position, airportName: String) =
            createWithImage(position, fromResource(R.drawable.airport_terminal)).apply {
                attributes.apply {
                    imageOffset = bottomCenter()
                    imageScale = NORMAL_IMAGE_SCALE
                }
                displayName = airportName
                altitudeMode = AltitudeMode.CLAMP_TO_GROUND
            }

        /**
         * Helper method to create aircraft placemarks. The aircraft are selectable, movable, and editable.
         */
        protected fun createAircraftPlacemark(position: Position, aircraftName: String, aircraftType: String) =
            aircraftIconMap[aircraftType]?.let { resId ->
                createWithImage(position, fromResource(resId)).apply {
                    attributes.apply {
                        imageOffset = bottomCenter()
                        imageScale = NORMAL_IMAGE_SCALE
                        isDrawLeader = true
                        leaderAttributes.outlineWidth = 4f
                    }
                    highlightAttributes = PlacemarkAttributes(attributes).apply {
                        imageScale = HIGHLIGHTED_IMAGE_SCALE
                        imageColor = Color(android.graphics.Color.YELLOW)
                    }
                    displayName = aircraftName
                    altitudeMode = AltitudeMode.ABSOLUTE
                    // The AIRCRAFT_TYPE property is used to exchange the vehicle type with the VehicleTypeDialog
                    putUserProperty(AIRCRAFT_TYPE, aircraftType)
                    // The select/drag controller will examine a placemark's "capabilities" to determine what operations are applicable:
                    putUserProperty(SELECTABLE, true)
                    putUserProperty(EDITABLE, true)
                    putUserProperty(MOVABLE, true)
                }
            } ?: throw IllegalArgumentException("$aircraftType is not valid.")

        /**
         * Helper method to create vehicle placemarks.
         */
        protected fun createAutomobilePlacemark(position: Position, name: String, automotiveType: String) =
            automotiveIconMap[automotiveType]?.let { resId ->
                createWithImage(position, fromResource(resId)).apply {
                    attributes.apply {
                        imageOffset = bottomCenter()
                        imageScale = NORMAL_IMAGE_SCALE
                    }
                    highlightAttributes = PlacemarkAttributes(attributes).apply {
                        imageScale = HIGHLIGHTED_IMAGE_SCALE
                        imageColor = Color(android.graphics.Color.YELLOW)
                    }
                    displayName = name
                    altitudeMode = AltitudeMode.CLAMP_TO_GROUND
                    // The AUTOMOTIVE_TYPE property is used to exchange the vehicle type with the VehicleTypeDialog
                    putUserProperty(AUTOMOTIVE_TYPE, automotiveType)
                    // The select/drag controller will examine a placemark's "capabilities" to determine what operations are applicable:
                    putUserProperty(SELECTABLE, true)
                    putUserProperty(EDITABLE, true)
                    putUserProperty(MOVABLE, true)
                }
            } ?: throw IllegalArgumentException("$automotiveType is not valid.")
    }
}