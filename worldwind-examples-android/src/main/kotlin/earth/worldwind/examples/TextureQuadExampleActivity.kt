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

        // Add dragging callback
        wwd.selectDragDetector.callback = object : SelectDragCallback {
            override fun canPickRenderable(renderable: Renderable) = true
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
                        Location(51.26891660167462.degrees, 30.0096671570868.degrees),
                        Location(51.27062637593827.degrees, 30.01240117718469.degrees),
                        Location(51.271804026840556.degrees, 30.01029779181104.degrees),
                        Location(51.270125460836965.degrees, 30.00779959572076.degrees),
                    ImageSource.fromResource(R.drawable.korogode_image), true
                    )
                )
            }
        )

        // And finally, for this demo, position the viewer to look at the placemarks
        val lookAt = LookAt(
            position = Position(51.270125460836965.degrees, 30.00989959572076.degrees, 9.0e2), altitudeMode = AltitudeMode.ABSOLUTE,
            range = 0.0, heading = Angle.ZERO, tilt = Angle.ZERO, roll = Angle.ZERO
        )
        wwd.engine.cameraFromLookAt(lookAt)
    }

    companion object {
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
        private const val NORMAL_IMAGE_SCALE = 3.0
        private const val HIGHLIGHTED_IMAGE_SCALE = 4.0


        /**
         * Helper method to create vehicle placemarks.
         */
        private fun createTextureQuadVertexPlacemark(location: Location, textureQuad : TextureQuad, vertexIndex : Int) =
             createWithImage(
                 Position(location.latitude, location.longitude, 0.0),
                 fromResource(R.drawable.vehicle_suv)
             ).apply {
                 attributes.apply {
                     imageOffset = bottomCenter()
                     imageScale = NORMAL_IMAGE_SCALE
                 }
                 highlightAttributes = PlacemarkAttributes(attributes).apply {
                     imageScale = HIGHLIGHTED_IMAGE_SCALE
                     imageColor = Color(android.graphics.Color.YELLOW)
                 }

                 altitudeMode = AltitudeMode.CLAMP_TO_GROUND
                 // The select/drag controller will examine a placemark's "capabilities" to determine what operations are applicable:
                 putUserProperty(SELECTABLE, true)
                 putUserProperty(MOVABLE, true)
                 putUserProperty(TEXTURE_QUAD_REF, textureQuad)
                 putUserProperty(VERTEX_INDEX, vertexIndex)
             }

        private fun createStretchableTextureQuad(bottomLeft : Location,
                                                   bottomRight: Location,
                                                   topRight   : Location,
                                                   topLeft    : Location,
                                                   imageSource: ImageSource,
                                                   drawOutline: Boolean = true) : List<Renderable>
        {
            val textureQuad = TextureQuad(bottomLeft, bottomRight, topRight, topLeft,
                ShapeAttributes().apply {
                    interiorImageSource = imageSource
                    isDrawOutline = drawOutline
                    outlineWidth = 3.0f
                    outlineColor = Color(1.0f, 0.0f, 0.0f, 1f)
                })
            return listOf(
                createTextureQuadVertexPlacemark(bottomLeft, textureQuad, 0),
                createTextureQuadVertexPlacemark(bottomRight, textureQuad, 1),
                createTextureQuadVertexPlacemark(topRight, textureQuad, 2),
                createTextureQuadVertexPlacemark(topLeft, textureQuad, 3),
                textureQuad
            )
        }
    }
}