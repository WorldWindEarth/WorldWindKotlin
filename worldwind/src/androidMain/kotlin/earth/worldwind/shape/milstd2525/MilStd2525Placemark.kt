package earth.worldwind.shape.milstd2525

import android.graphics.Bitmap
import android.util.SparseArray
import earth.worldwind.geom.OffsetMode
import earth.worldwind.geom.Position
import earth.worldwind.render.image.ImageSource
import earth.worldwind.shape.PlacemarkAttributes
import earth.worldwind.util.Logger
import java.lang.ref.WeakReference

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
         * A cache of PlacemarkAttribute bundles containing MIL-STD-2525 symbols. Using a cache is essential for memory
         * management: we want to share the bitmap textures for identical symbols. The cache maintains weak references to
         * the attribute bundles so that the garbage collector can reclaim the memory when a Placemark releases an attribute
         * bundle, for instance when it changes its level-of-detail.
         */
        private val symbolCache = HashMap<Int, WeakReference<PlacemarkAttributes>>()

        /**
         * Releases cached PlacemarkAttribute bundles.
         */
        @JvmStatic
        actual fun clearSymbolCache() = symbolCache.clear()

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
        ): PlacemarkAttributes {
            val key = getSymbolCacheKey(symbolCode, symbolModifiers, symbolAttributes)
            return symbolCache[key]?.get() ?: PlacemarkAttributes().apply {
                SymbolFactory(symbolCode, symbolModifiers, symbolAttributes) { x, y ->
                    imageOffset.set(OffsetMode.PIXELS, x, OffsetMode.INSET_PIXELS, y)
                }.also {
                    imageSource = ImageSource.fromBitmapFactory(it)
                }
                leaderAttributes.outlineWidth = MilStd2525.graphicsLineWidth / 1.5f
                minimumImageScale = MINIMUM_IMAGE_SCALE
                symbolCache[key] = WeakReference(this)
            }
        }
    }

    private class SymbolFactory(
        private val symbolCode: String,
        private val symbolModifiers: Map<String, String>?,
        private val symbolAttributes: Map<String, String>?,
        private val onRender: (xOffset: Double, yOffset: Double) -> Unit
    ) : ImageSource.BitmapFactory {
        override val isRunBlocking = true

        override suspend fun createBitmap() = MilStd2525.renderImage(symbolCode, symbolModifiers, symbolAttributes)?.let {
            // Apply the computed image offset after the renderer has created the image. This is essential for proper
            // placement as the offset may change depending on the level of detail, for instance, the absence or
            // presence of text modifiers.
            onRender(it.centerPoint.x.toDouble(), it.centerPoint.y.toDouble())
            // Return a copy of bitmap to guarantee each texture will contain its own bitmap to recycle it
            Bitmap.createBitmap(it.image)//.apply { it.image.recycle() }
        } ?: run {
            Logger.logMessage(
                Logger.ERROR, "MilStd2525Placemark", "createBitmap", "Failed to render image for $symbolCode"
            )
            null
        }
    }
}