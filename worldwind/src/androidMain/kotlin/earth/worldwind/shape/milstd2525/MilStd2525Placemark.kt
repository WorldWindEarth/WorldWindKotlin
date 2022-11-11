package earth.worldwind.shape.milstd2525

import android.util.SparseArray
import earth.worldwind.geom.Offset
import earth.worldwind.geom.OffsetMode
import earth.worldwind.geom.Position
import earth.worldwind.render.image.ImageSource
import earth.worldwind.shape.PlacemarkAttributes
import earth.worldwind.util.Logger

/**
 * Constructs a MIL-STD-2525 Placemark with an appropriate level of detail for the current distance from the camera.
 * Shared low-fidelity images are used when far away from the camera, whereas unique high-fidelity images are used
 * when near the camera. The high-fidelity images that are no longer in view are automatically freed, if necessary,
 * to release memory resources. The Placemark's symbol is lazily created (and recreated if necessary) via an
 * ImageSource.BitmapFactory.
 * See the [MilStd2525Placemark.getPlacemarkAttributes] for more information about resource caching/sharing
 * and the bitmap factory.
 *
 * @param position The placemark's geographic position
 * @param symbolCode A 15-character alphanumeric identifier that provides the information necessary to display or
 * transmit a tactical symbol between MIL-STD-2525 compliant systems.
 * @param symbolModifiers An optional collection of unit or tactical graphic modifiers. See:
 * @see <a href="https://github.com/missioncommand/mil-sym-android/blob/master/Renderer/src/main/java/armyc2/c2sd/renderer/utilities/ModifiersUnits.java">ModifiersUnits</a>
 * @see <a href="https://github.com/missioncommand/mil-sym-android/blob/master/Renderer/src/main/java/armyc2/c2sd/renderer/utilities/ModifiersTG.java">ModifiersTG</a>
 * @param symbolAttributes An optional collection of rendering attributes.
 * @see <a href="https://github.com/missioncommand/mil-sym-android/blob/master/Renderer/src/main/java/armyc2/c2sd/renderer/utilities/MilStdAttributes.java">MilStdAttributes</a>
 */
actual open class MilStd2525Placemark actual constructor(
    symbolCode: String,
    position: Position,
    symbolModifiers: Map<String, String>?,
    symbolAttributes: Map<String, String>?
) : AbstractMilStd2525Placemark(symbolCode, position, symbolModifiers, symbolAttributes) {
    constructor(
        symbolCode: String, position: Position, symbolModifiers: SparseArray<String>? = null,
        symbolAttributes: SparseArray<String>? = null
    ) : this(
        symbolCode, position, MilStd2525.symbolModifiersFromSparseArray(symbolModifiers),
        MilStd2525.attributesFromSparseArray(symbolAttributes)
    )

    actual companion object {
        /**
         * Creates a placemark attributes bundle containing a MIL-STD-2525 symbol using the specified modifiers and
         * attributes.
         *
         * @param symbolCode The 15-character SIDC (symbol identification coding scheme) code.
         * @param symbolModifiers The ModifierUnit (unit) or ModifierTG (tactical graphic) modifiers collection. May be null.
         * @param symbolAttributes The MilStdAttributes attributes collection. May be null.
         *
         * @return A new [PlacemarkAttributes] bundle representing the MIL-STD-2525 symbol.
         */
        @JvmStatic
        fun getPlacemarkAttributes(
            symbolCode: String, symbolModifiers: SparseArray<String>? = null, symbolAttributes: SparseArray<String>? = null
        ) = getPlacemarkAttributes(
            symbolCode, MilStd2525.symbolModifiersFromSparseArray(symbolModifiers),
            MilStd2525.attributesFromSparseArray(symbolAttributes)
        )

        /**
         * Creates a placemark attributes bundle containing a MIL-STD-2525 symbol using the specified modifiers and
         * attributes.
         *
         * @param symbolCode The 15-character SIDC (symbol identification coding scheme) code.
         * @param symbolModifiers The ModifierUnit (unit) or ModifierTG (tactical graphic) modifiers collection. May be null.
         * @param symbolAttributes The MilStdAttributes attributes collection. May be null.
         *
         * @return A new [PlacemarkAttributes] bundle representing the MIL-STD-2525 symbol.
         */
        actual fun getPlacemarkAttributes(
            symbolCode: String, symbolModifiers: Map<String, String>?, symbolAttributes: Map<String, String>?
        ) = PlacemarkAttributes().apply {
            // Create the symbol's bitmap
            val imageInfo = MilStd2525.renderImage(symbolCode, symbolModifiers, symbolAttributes)
            if (imageInfo == null) {
                imageSource = DEFAULT_IMAGE_SOURCE
                Logger.logMessage(
                    Logger.ERROR, "MilStd2525Placemark", "createBitmap", "Failed to render image for $symbolCode"
                )
            } else {
                // Apply the computed image offset after the renderer has created the image. This is essential for proper
                // placement as the offset may change depending on the level of detail, for instance, the absence or
                // presence of text modifiers.
                imageOffset = Offset(
                    OffsetMode.PIXELS, imageInfo.centerPoint.x.toDouble(), // x offset
                    OffsetMode.INSET_PIXELS, imageInfo.centerPoint.y.toDouble() // y offset converted to lower-left origin
                )
                imageSource = ImageSource.fromBitmap(imageInfo.image)
            }
            leaderAttributes.outlineWidth = MilStd2525.outlineWidth / 1.5f
            minimumImageScale = MINIMUM_IMAGE_SCALE
        }
    }
}