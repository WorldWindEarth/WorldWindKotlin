package earth.worldwind.shape.milstd2525

import earth.worldwind.MR
import earth.worldwind.geom.Offset
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
 * to release memory resources.
 * See the [MilStd2525Placemark.getPlacemarkAttributes] for more information about resource caching/sharing.
 *
 * @param position The placemark's geographic position
 * @param symbolCode A 15-character alphanumeric identifier that provides the information necessary to display or
 * transmit a tactical symbol between MIL-STD-2525 compliant systems.
 * @param symbolModifiers An optional collection of unit or tactical graphic modifiers.
 * @param symbolAttributes An optional collection of rendering attributes.
 */
actual open class MilStd2525Placemark actual constructor(
    symbolCode: String,
    position: Position,
    symbolModifiers: Map<String, String>?,
    symbolAttributes: Map<String, String>?
) : AbstractMilStd2525Placemark(symbolCode, position, symbolModifiers, symbolAttributes) {

    actual companion object {
        /**
         * The image to use when the renderer cannot render an image.
         */
        protected val DEFAULT_IMAGE_SOURCE = ImageSource.fromResource(MR.images.default_image)
        /**
         * A cache of [PlacemarkAttributes] bundles containing MIL-STD-2525 symbols. Using a cache is essential for memory
         * management: we want to share the bitmap textures for identical symbols. The cache maintains weak references to
         * the attribute bundles so that the garbage collector can reclaim the memory when a Placemark releases an attribute
         * bundle, for instance when it changes its level-of-detail.
         */
        protected val symbolCache = mutableMapOf<String, WeakReference<PlacemarkAttributes>>()

        /**
         * Releases cached PlacemarkAttribute bundles.
         */
        @JvmStatic
        actual fun clearSymbolCache() { symbolCache.clear() }

        /**
         * Gets a [PlacemarkAttributes] bundle for the supplied symbol specification. The attribute bundle is retrieved from
         * a cache. If the symbol is not found in the cache, an attribute bundle is created and added to the cache before it
         * is returned.
         *
         * @param symbolCode A 15-character alphanumeric identifier that provides the information necessary to display or
         * transmit a tactical symbol between MIL-STD-2525 compliant systems.
         * @param symbolModifiers An optional collection of unit or tactical graphic modifiers.
         * @param symbolAttributes An optional collection of rendering attributes.
         *
         * @return Either a new or a cached [PlacemarkAttributes] bundle containing the specified symbol embedded in the
         * bundle's imageSource property.
         */
        actual fun getPlacemarkAttributes(
            symbolCode: String, symbolModifiers: Map<String, String>?, symbolAttributes: Map<String, String>?
        ): PlacemarkAttributes {
            // Generate a cache key for this symbol
            val symbolKey = symbolCode + (symbolModifiers?.toString() ?: "{}") + (symbolAttributes?.toString() ?: "{}")

            // Look for an attribute bundle in our cache and determine if the cached reference is valid
            return symbolCache[symbolKey]?.get()
            // Create the attributes bundle and add it to the cache.
                ?: createPlacemarkAttributes(symbolCode, symbolModifiers, symbolAttributes).also {
                    // Add a weak reference to the attribute bundle to our cache
                    symbolCache[symbolKey] = WeakReference(it)

                    // Perform some initialization of the bundle conducive to eye distance scaling
                    it.minimumImageScale = MINIMUM_IMAGE_SCALE
                }
        }

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
        protected fun createPlacemarkAttributes(
            symbolCode: String, symbolModifiers: Map<String, String>?, symbolAttributes: Map<String, String>?
        ) = PlacemarkAttributes().apply {
            // Create the symbol's image source
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
                val centerPoint = imageInfo.symbolCenterPoint // The center of the core symbol
                val placemarkOffset = Offset(
                    OffsetMode.PIXELS, centerPoint.x, // x offset
                    // Use billboarding or lollipopping to prevent icon clipping by terrain as described in MIL-STD-2525C APPENDIX F.5.1.1.2
                    //OffsetMode.INSET_PIXELS, centerPoint.y // y offset converted to lower-left origin
                    OffsetMode.PIXELS, 0.0 // bottom of icon
                )
                imageOffset = placemarkOffset
                imageSource = ImageSource.fromImage(imageInfo.image)
            }
            leaderAttributes.outlineWidth = MilStd2525.GRAPHICS_OUTLINE_WIDTH / 1.5f
        }
    }
}