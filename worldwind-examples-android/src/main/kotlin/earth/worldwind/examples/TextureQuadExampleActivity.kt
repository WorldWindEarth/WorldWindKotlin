package earth.worldwind.examples

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import earth.worldwind.WorldWind
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Location
import earth.worldwind.geom.LookAt
import earth.worldwind.geom.Offset.Companion.bottomCenter
import earth.worldwind.geom.Sector
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.image.ImageSource
import earth.worldwind.shape.SurfaceImage
import earth.worldwind.geom.Position
import earth.worldwind.geom.Position.Companion.fromDegrees
import earth.worldwind.gesture.SelectDragCallback
import earth.worldwind.render.Color
import earth.worldwind.render.Renderable
import earth.worldwind.render.image.ImageSource.Companion.fromResource
import earth.worldwind.shape.Highlightable
import earth.worldwind.shape.Placemark
import earth.worldwind.shape.Placemark.Companion.createWithImage
import earth.worldwind.shape.PlacemarkAttributes
import earth.worldwind.shape.TextureQuad
import earth.worldwind.shape.ShapeAttributes


class TextureQuadExampleActivity: GeneralGlobeActivity() {
    private var selectedObject: Renderable? = null // Last "selected" object from single tap or double tap

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        aboutBoxTitle = "About the " + resources.getText(R.string.title_texture_quad_example)
        aboutBoxText = """
    Demonstrates how to stretch texture quad
    """.trimIndent()

        // Initialize the mapping of vehicle types to their icons.
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
            }
            override fun onRenderableMoved(renderable: Renderable, fromPosition: Position, toPosition: Position)
            {
                val placemark = (renderable as? Placemark)
                placemark?.moveTo(wwd.engine.globe, toPosition)
                var textureQuad = renderable.getUserProperty<TextureQuad>(TEXTURE_QUAD_REF)
                val vertexIndex = renderable.getUserProperty<Int>(VERTEX_INDEX)
                vertexIndex?.let { textureQuad?.setLocation(it, Location(toPosition.latitude, toPosition.longitude)) }
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
                    val textureQuad = (renderable as? TextureQuad)
                    textureQuad?.let {
                        val arr = it.getAllLocations()

                        Toast.makeText(applicationContext, "BL:{%.6f, %.6f}\nBR:{%.6f, %.6f}\nTR:{%.6f, %.6f}\nTL:{%.6f, %.6f}".format(
                            arr[0].latitude.inDegrees, arr[0].longitude.inDegrees,
                            arr[1].latitude.inDegrees, arr[1].longitude.inDegrees,
                            arr[2].latitude.inDegrees, arr[2].longitude.inDegrees,
                            arr[3].latitude.inDegrees, arr[3].longitude.inDegrees), Toast.LENGTH_SHORT).show()
                    }
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
        }
        // Add a layer for texture quads to the WorldWindow
        wwd.engine.layers.addLayer(
            RenderableLayer("Texture quad example").apply {

                addAllRenderables(
                    createStretchableTextureQuad(
                        Location(51.26866335850095.degrees, 30.009652585537117.degrees),
                        Location(51.27061300784313.degrees, 30.0131655188949.degrees),
                        Location(51.27262574053993.degrees, 30.010067722766809.degrees),
                        Location(51.27086850182122.degrees, 30.006541266023913.degrees),
                    ImageSource.fromResource(R.drawable.korogode_image)
                    )
                )
            }
        )

        // And finally, for this demo, position the viewer to look at the placemarks
        val lookAt = LookAt(
            position = Position(51.265140.degrees, 30.020303.degrees, 4.0e3), altitudeMode = AltitudeMode.ABSOLUTE,
            range = 0.0, heading = Angle.ZERO, tilt = Angle.ZERO, roll = Angle.ZERO
        )
        wwd.engine.cameraFromLookAt(lookAt)
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

        const val VERTEX_INDEX = "vertex_index"
        const val TEXTURE_QUAD_REF = "texture_quad_ref"
        /**
         * Placemark user property vehicleKey for the type of vehicle
         */
        const val AUTOMOTIVE_TYPE = "auotomotive_type"

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
        private val automotiveIconMap = mutableMapOf<String, Int>()


        /**
         * Helper method to create vehicle placemarks.
         */
        private fun createAutomobilePlacemark(location: Location, name: String, automotiveType: String, textureQuad : TextureQuad, vertexIndex : Int) =
            automotiveIconMap[automotiveType]?.let { resId ->
                createWithImage(Position(location.latitude, location.longitude, 0.0), fromResource(resId)).apply {
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
                    putUserProperty(TEXTURE_QUAD_REF, textureQuad)
                    putUserProperty(VERTEX_INDEX, vertexIndex)
                }
            } ?: throw IllegalArgumentException("$automotiveType is not valid.")

        private fun createStretchableTextureQuad(bottomLeft : Location,
                                                   bottomRight: Location,
                                                   topRight   : Location,
                                                   topLeft    : Location,
                                                   imageSource: ImageSource) : List<Renderable>
        {
            val textureQuad = TextureQuad(bottomLeft, bottomRight, topRight, topLeft, imageSource)
            return listOf(
                createAutomobilePlacemark(bottomLeft, "Civilian Vehicle", automotiveTypes[1], textureQuad, 0),
                createAutomobilePlacemark(bottomRight, "Civilian Vehicle", automotiveTypes[1], textureQuad, 1),
                createAutomobilePlacemark(topRight, "Civilian Vehicle", automotiveTypes[1], textureQuad, 2),
                createAutomobilePlacemark(topLeft, "Civilian Vehicle", automotiveTypes[1], textureQuad, 3),
                textureQuad
            )
        }
    }
}