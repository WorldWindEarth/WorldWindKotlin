package earth.worldwind.shape.milstd2525

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.SparseArray
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
         * A cache of [PlacemarkAttributes] bundles containing MIL-STD-2525 symbols. Using a cache is essential for memory
         * management: we want to share the bitmap textures for identical symbols. The cache maintains weak references to
         * the attribute bundles so that the garbage collector can reclaim the memory when a Placemark releases an attribute
         * bundle, for instance when it changes its level-of-detail.
         */
        protected val symbolCache = mutableMapOf<String, WeakReference<PlacemarkAttributes>>()
        /**
         * The handler used to schedule runnable to be executed on the main thread.
         */
        protected val mainLoopHandler = Handler(Looper.getMainLooper())

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
         * @see <a href="https://github.com/missioncommand/mil-sym-android/blob/master/Renderer/src/main/java/armyc2/c2sd/renderer/utilities/ModifiersUnits.java">ModifiersUnits</a>
         * @see <a href="https://github.com/missioncommand/mil-sym-android/blob/master/Renderer/src/main/java/armyc2/c2sd/renderer/utilities/ModifiersTG.java">ModifiersTG</a>
         * @param symbolAttributes An optional collection of rendering attributes.
         * @see <a href="https://github.com/missioncommand/mil-sym-android/blob/master/Renderer/src/main/java/armyc2/c2sd/renderer/utilities/MilStdAttributes.java">MilStdAttributes</a>
         *
         * @return Either a new or a cached [PlacemarkAttributes] bundle containing the specified symbol embedded in the
         * bundle's imageSource property.
         */
        @JvmStatic
        fun getPlacemarkAttributes(
            symbolCode: String, symbolModifiers: SparseArray<String>? = null, symbolAttributes: SparseArray<String>? = null
        ) = getPlacemarkAttributes(
            symbolCode, MilStd2525.symbolModifiersFromSparseArray(symbolModifiers),
            MilStd2525.attributesFromSparseArray(symbolAttributes)
        )

        /**
         * Gets a [PlacemarkAttributes] bundle for the supplied symbol specification. The attribute bundle is retrieved from
         * a cache. If the symbol is not found in the cache, an attribute bundle is created and added to the cache before it
         * is returned.
         *
         * @param symbolCode A 15-character alphanumeric identifier that provides the information necessary to display or
         * transmit a tactical symbol between MIL-STD-2525 compliant systems.
         * @param symbolModifiers An optional collection of unit or tactical graphic modifiers.
         * @see <a href="https://github.com/missioncommand/mil-sym-android/blob/master/Renderer/src/main/java/armyc2/c2sd/renderer/utilities/ModifiersUnits.java">ModifiersUnits</a>
         * @see <a href="https://github.com/missioncommand/mil-sym-android/blob/master/Renderer/src/main/java/armyc2/c2sd/renderer/utilities/ModifiersTG.java">ModifiersTG</a>
         * @param symbolAttributes An optional collection of rendering attributes.
         * @see <a href="https://github.com/missioncommand/mil-sym-android/blob/master/Renderer/src/main/java/armyc2/c2sd/renderer/utilities/MilStdAttributes.java">MilStdAttributes</a>
         *
         * @return Either a new or a cached [PlacemarkAttributes] bundle containing the specified symbol embedded in the
         * bundle's imageSource property.
         */
        @JvmStatic
        actual fun getPlacemarkAttributes(
            symbolCode: String, symbolModifiers: Map<String, String>?, symbolAttributes: Map<String, String>?
        ): PlacemarkAttributes {
            // Generate a cache key for this symbol
            val symbolKey = symbolCode + (symbolModifiers?.toString() ?: "{}") + (symbolAttributes?.toString() ?: "{}")

            // Look for an attribute bundle in our cache and determine if the cached reference is valid
            return symbolCache[symbolKey]?.get()
            // Create the attributes bundle and add it to the cache.
            // The actual bitmap will be lazily (re)created using a factory.
                ?: createPlacemarkAttributes(symbolCode, symbolModifiers, symbolAttributes).also {
                    // Add a weak reference to the attribute bundle to our cache
                    symbolCache[symbolKey] = WeakReference(it)

                    // Perform some initialization of the bundle conducive to eye distance scaling
                    it.minimumImageScale = MINIMUM_IMAGE_SCALE
                }
        }

        /**
         * Creates a placemark attributes bundle containing a MIL-STD-2525 symbol using the specified modifiers and
         * attributes. The ImageSource bitmap is lazily created via an [ImageSource.BitmapFactory]. The call to the
         * factory's createBitmap method made when Placemark comes into view; it's also used to recreate the bitmap if the
         * resource was evicted from the World Wind render resource cache.
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
            val factory = SymbolBitmapFactory(symbolCode, symbolModifiers, symbolAttributes, this)
            imageSource = ImageSource.fromBitmapFactory(factory)
            leaderAttributes.outlineWidth = MilStd2525.outlineWidth / 1.5f
        }
    }

    /**
     * This [ImageSource.BitmapFactory] implementation creates MIL-STD-2525 bitmaps for use with [MilStd2525Placemark].
     * Constructs a [SymbolBitmapFactory] instance capable of creating a bitmap with the given code, modifiers and
     * attributes. The createBitmap() method will return a new instance of a bitmap and will also update the
     * associated placemarkAttributes bundle's imageOffset property based on the size of the new bitmap.
     *
     * @param symbolCode SIDC code
     * @param symbolModifiers Unit modifiers to be copied; null is permitted
     * @param symbolAttributes Rendering attributes to be copied; null is permitted
     * @param placemarkAttributes Placemark attribute bundle associated with this factory
     */
    protected open class SymbolBitmapFactory(
        protected val symbolCode: String, symbolModifiers: Map<String, String>?, symbolAttributes: Map<String, String>?,
        // Capture the values needed to (re)create the symbol bitmap
        // The MilStd2525Placemark.symbolCache maintains a WeakReference to the placemark attributes. The finalizer is
        // able to resolve the circular dependency between the PlacemarkAttributes->ImageSource->Factory->PlacemarkAttributes
        // and garbage collect the attributes a Placemark releases its attribute bundle (e.g., when switching between
        // levels-of-detail)
        protected val placemarkAttributes: PlacemarkAttributes
    ) : ImageSource.BitmapFactory {
        protected val symbolModifiers = symbolModifiers?.toMap() // Store a copy of modifiers
        protected val symbolAttributes = symbolAttributes?.toMap() // Store a copy of attributes
        protected var placemarkOffset: Offset? = null

        /**
         * Returns the MIL-STD-2525 bitmap and updates the [PlacemarkAttributes] associated with this factory instance.
         *
         * @return a new [Bitmap] rendered from the parameters given in the constructor
         */
        override fun createBitmap(): Bitmap {
            // Create the symbol's bitmap
            val imageInfo = MilStd2525.renderImage(symbolCode, symbolModifiers, symbolAttributes)
                ?: return MilStd2525.defaultImage.also {
                    Logger.logMessage(
                        Logger.ERROR, "MilStd2525Placemark", "createBitmap", "Failed to render image for $symbolCode"
                    )
                }

            // Apply the computed image offset after the renderer has created the image. This is essential for proper
            // placement as the offset may change depending on the level of detail, for instance, the absence or
            // presence of text modifiers.
            val centerPoint = imageInfo.centerPoint // The center of the core symbol
            val placemarkOffset = Offset(
                OffsetMode.PIXELS, centerPoint.x.toDouble(), // x offset
                // Use billboarding or lollipopping to prevent icon clipping by terrain as described in MIL-STD-2525C APPENDIX F.5.1.1.2
                // OffsetMode.INSET_PIXELS, centerPoint.y.toDouble() // y offset converted to lower-left origin
                OffsetMode.PIXELS, 0.0 // bottom of icon
            ).also { placemarkOffset = it }

            // Apply the placemark offset to the attributes on the main thread. This is necessary to synchronize write
            // access to placemarkAttributes from the thread that invokes this BitmapFactory and read access from the
            // main thread.
            mainLoopHandler.post { placemarkAttributes.imageOffset = placemarkOffset }

            // Return the bitmap
            return imageInfo.image
        }
    }
}