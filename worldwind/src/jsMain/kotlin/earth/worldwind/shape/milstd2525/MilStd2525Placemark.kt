package earth.worldwind.shape.milstd2525

import earth.worldwind.geom.OffsetMode
import earth.worldwind.geom.Position
import earth.worldwind.render.image.ImageSource
import earth.worldwind.shape.PlacemarkAttributes
import earth.worldwind.util.Logger
import kotlinx.coroutines.await
import org.w3c.dom.Image
import kotlin.js.Promise

/**
 * Constructs a MIL-STD-2525 Placemark with an appropriate level of detail for the current distance from the camera.
 * Shared low-fidelity images are used when far away from the camera, whereas unique high-fidelity images are used
 * when near the camera. The high-fidelity images that are no longer in view are automatically freed, if necessary,
 * to release memory resources.
 * See the [MilStd2525Placemark.getPlacemarkAttributes] for more information about resource caching/sharing.
 *
 * @param position The placemark's geographic position
 * @param symbolID A 30-character numeric identifier that provides the information necessary to display or
 * transmit a tactical symbol between MIL-STD-2525 compliant systems.
 * @param symbolModifiers An optional collection of unit or tactical graphic modifiers.
 * @param symbolAttributes An optional collection of rendering attributes.
 */
actual open class MilStd2525Placemark actual constructor(
    symbolID: String,
    position: Position,
    symbolModifiers: Map<String, String>?,
    symbolAttributes: Map<String, String>?,
    lodSelector: LevelOfDetailSelector
) : AbstractMilStd2525Placemark(symbolID, position, symbolModifiers, symbolAttributes, lodSelector) {
    actual companion object {
        /**
         * A cache of PlacemarkAttribute bundles containing MIL-STD-2525 symbols. Using a cache is essential for memory
         * management: we want to share the bitmap textures for identical symbols.  The cache maintains weak references to
         * the attribute bundles so that the garbage collector can reclaim the memory when a Placemark releases an attribute
         * bundle, for instance when it changes its level-of-detail.
         */
        private val symbolCache = HashMap<Int, WeakRef<PlacemarkAttributes>>()

        /**
         * Releases cached PlacemarkAttribute bundles.
         */
        actual fun clearSymbolCache() = symbolCache.clear()

        /**
         * Creates a placemark attributes bundle containing a MIL-STD-2525 symbol using the specified modifiers and
         * attributes.
         *
         * @param symbolCode A 30-character numeric identifier that provides the information necessary to display or
         * transmit a tactical symbol between MIL-STD-2525 compliant systems.
         * @param symbolModifiers An optional collection of unit or tactical graphic modifiers.
         * @param symbolAttributes An optional collection of rendering attributes.
         *
         * @return A new [PlacemarkAttributes] bundle representing the MIL-STD-2525 symbol.
         */
        actual fun getPlacemarkAttributes(
            symbolCode: String, symbolModifiers: Map<String, String>?, symbolAttributes: Map<String, String>?
        ): PlacemarkAttributes {
            val key = getSymbolCacheKey(symbolCode, symbolModifiers, symbolAttributes)
            return symbolCache[key]?.deref() ?: PlacemarkAttributes().apply {
                SymbolFactory(symbolCode, symbolModifiers, symbolAttributes) { x, y, w, h ->
                    imageOffset.set(OffsetMode.PIXELS, x.toDouble(), OffsetMode.INSET_PIXELS, y.toDouble())
                    labelAttributes.textOffset.set(
                        OffsetMode.PIXELS, -w.toDouble() / 2.0,
                        OffsetMode.INSET_PIXELS, h.toDouble() / 2.0
                    )
                }.also {
                    imageSource = ImageSource.fromImageFactory(it)
                }
                MilStd2525.applyTextAttributes(labelAttributes)
                minimumImageScale = MINIMUM_IMAGE_SCALE
                symbolCache[key] = WeakRef(this)
            }
        }
    }

    private class SymbolFactory(
        private val symbolCode: String,
        private val symbolModifiers: Map<String, String>?,
        private val symbolAttributes: Map<String, String>?,
        private val onRender: (x: Number, y: Number, w: Number, h: Number) -> Unit
    ) : ImageSource.ImageFactory {
        override suspend fun createImage() = MilStd2525.renderImage(symbolCode, symbolModifiers, symbolAttributes)?.let {
            val symbolBounds = it.getSymbolBounds()
            val imageBounds = it.getImageBounds()
            // Apply the computed image offset after the renderer has created the image. This is essential for proper
            // placement as the offset may change depending on the level of detail, for instance, the absence or
            // presence of text modifiers.
            onRender(it.getSymbolCenterX(), it.getSymbolCenterY(), symbolBounds.getWidth(), symbolBounds.getHeight())
            Image(imageBounds.getWidth().toInt(), imageBounds.getHeight().toInt()).apply {
                src = it.getSVGDataURI()
                (asDynamic().decode() as Promise<Unit>).await() // Wait until image loaded
            }
        } ?: run {
            Logger.logMessage(
                Logger.ERROR, "MilStd2525Placemark", "createBitmap", "Failed to render image for $symbolCode"
            )
            null
        }
    }
}