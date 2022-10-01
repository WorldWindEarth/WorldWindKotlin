package earth.worldwind.examples.milstd2525

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.util.SparseArray
import armyc2.c2sd.renderer.MilStdIconRenderer
import armyc2.c2sd.renderer.utilities.ImageInfo
import armyc2.c2sd.renderer.utilities.RendererSettings
import earth.worldwind.geom.Offset
import earth.worldwind.geom.OffsetMode
import earth.worldwind.render.image.ImageSource
import earth.worldwind.render.image.ImageSource.Companion.fromBitmapFactory
import earth.worldwind.shape.PlacemarkAttributes
import earth.worldwind.util.Logger
import earth.worldwind.util.Logger.logMessage
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * This utility class generates PlacemarkAttributes bundles with MIL-STD-2525 symbols. The symbols are generated from
 * the MIL-STD-2525 Symbol Rendering Library ([https://github.com/missioncommand/mil-sym-android](https://github.com/missioncommand/mil-sym-android))
 * contained in the mil-sym-android module.
 */
object MilStd2525 {
    /**
     * The image to use when the renderer cannot render an image.
     */
    var defaultImage = BitmapFactory.decodeResource(Resources.getSystem(), android.R.drawable.ic_dialog_alert) // Warning triangle
    /**
     * The actual rendering engine for the MIL-STD-2525 graphics.
     */
    private val renderer = MilStdIconRenderer.getInstance()
    /**
     * A cache of PlacemarkAttribute bundles containing MIL-STD-2525 symbols. Using a cache is essential for memory
     * management: we want to share the bitmap textures for identical symbols.  The cache maintains weak references to
     * the attribute bundles so that the garbage collector can reclaim the memory when a Placemark releases an attribute
     * bundle, for instance when it changes its level-of-detail.
     */
    private val symbolCache = mutableMapOf<String, WeakReference<PlacemarkAttributes>>()
    private val emptyArray = SparseArray<String>() // may be used in a cache key
    private var initialized = false
    private const val DEFAULT_PIXEL_SIZE = 100
    private const val DEFAULT_FONT_SIZE = 18
    private const val TEXT_OUTLINE_WIDTH = 4
    private const val MINIMUM_IMAGE_SCALE = 0.25

    /**
     * Initializes the static MIL-STD-2525 symbol renderer.  This method must be called one time before calling
     * renderImage().
     *
     * @param applicationContext The Context used to define the location of the renderer's cache directly.
     */
    @Synchronized
    fun initializeRenderer(applicationContext: Context) {
        if (initialized) return

        // Tell the renderer where the cache folder is located which is needed to process the embedded xml files.
        val cacheDir = applicationContext.cacheDir.absoluteFile.absolutePath
        renderer.init(applicationContext, cacheDir)

        // Establish the default rendering values.
        // See: https://github.com/missioncommand/mil-sym-android/blob/master/Renderer/src/main/java/armyc2/c2sd/renderer/utilities/RendererSettings.java
        val rs = RendererSettings.getInstance()
        rs.symbologyStandard = RendererSettings.Symbology_2525C
        rs.defaultPixelSize = DEFAULT_PIXEL_SIZE

        // Depending on screen size and DPI you may want to change the font size.
        rs.setModifierFont("Arial", Typeface.BOLD, DEFAULT_FONT_SIZE)
        rs.setMPModifierFont("Arial", Typeface.BOLD, DEFAULT_FONT_SIZE)

        // Configure modifier text output
        rs.textBackgroundMethod = RendererSettings.TextBackgroundMethod_OUTLINE
        rs.textOutlineWidth = TEXT_OUTLINE_WIDTH // 4 is the factory default
        initialized = true
    }

    /**
     * Releases cached PlacemarkAttribute bundles.
     */
    fun clearSymbolCache() { symbolCache.clear() }

    /**
     * Gets a PlacemarkAttributes bundle for the supplied symbol specification. The attribute bundle is retrieved from a
     * cache. If the symbol is not found in the cache, an attribute bundle is created and added to the cache before it
     * is returned.
     *
     * @param symbolCode A 15-character alphanumeric identifier that provides the information necessary to display or
     * transmit a tactical symbol between MIL-STD-2525 compliant systems.
     * @param modifiers  A optional collection of unit or tactical graphic modifiers. See:
     * https://github.com/missioncommand/mil-sym-android/blob/master/Renderer/src/main/java/armyc2/c2sd/renderer/utilities/ModifiersUnits.java
     * and https://github.com/missioncommand/mil-sym-android/blob/master/Renderer/src/main/java/armyc2/c2sd/renderer/utilities/ModifiersTG.java
     * @param attributes A optional collection of rendering attributes. See https://github.com/missioncommand/mil-sym-android/blob/master/Renderer/src/main/java/armyc2/c2sd/renderer/utilities/MilStdAttributes.java
     *
     * @return Either a new or a cached PlacemarkAttributes bundle containing the specified symbol embedded in the
     * bundle's imageSource property.
     */
    fun getPlacemarkAttributes(
        symbolCode: String, modifiers: SparseArray<String>?, attributes: SparseArray<String>?
    ): PlacemarkAttributes {
        // Generate a cache key for this symbol
        val symbolKey = (symbolCode
                + (modifiers?.toString() ?: emptyArray.toString())
                + (attributes?.toString() ?: emptyArray.toString()))

        // Look for an attribute bundle in our cache and determine if the cached reference is valid
        val reference = symbolCache[symbolKey]
        var placemarkAttributes = reference?.get()

        // Create the attributes if they haven't been created yet or if they've been released
        if (placemarkAttributes == null) {
            // Create the attributes bundle and add it to the cache.
            // The actual bitmap will be lazily (re)created using a factory.
            placemarkAttributes = createPlacemarkAttributes(symbolCode, modifiers, attributes)

            // Add a weak reference to the attribute bundle to our cache
            symbolCache[symbolKey] = WeakReference(placemarkAttributes)

            // Perform some initialization of the bundle conducive to eye distance scaling
            placemarkAttributes.minimumImageScale = MINIMUM_IMAGE_SCALE
        }
        return placemarkAttributes
    }

    /**
     * Creates a placemark attributes bundle containing a MIL-STD-2525 symbol using the specified modifiers and
     * attributes.  The ImageSource bitmap is lazily created via an ImageSource.Bitmap factory. The call to the
     * factory's createBitmap method made when Placemark comes into view; it's also used to recreate the bitmap if the
     * resource was evicted from the WorldWind render resource cache.
     *
     * @param symbolCode The 15-character SIDC (symbol identification coding scheme) code.
     * @param modifiers  The ModifierUnit (unit) or ModifierTG (tactical graphic) modifiers collection. May be null.
     * @param attributes The MilStdAttributes attributes collection. May be null.
     *
     * @return A new PlacemarkAttributes bundle representing the MIL-STD-2525 symbol.
     */
    fun createPlacemarkAttributes(
        symbolCode: String, modifiers: SparseArray<String>?, attributes: SparseArray<String>?
    ): PlacemarkAttributes {
        val placemarkAttributes = PlacemarkAttributes()

        // Create a BitmapFactory instance with the values needed to create and recreate the symbol's bitmap
        val factory = SymbolBitmapFactory(symbolCode, modifiers, attributes, placemarkAttributes)
        placemarkAttributes.imageSource = fromBitmapFactory(factory)
        return placemarkAttributes
    }

    /**
     * Creates an MIL-STD-2525 symbol from the specified symbol code, modifiers and attributes.
     *
     * @param symbolCode The MIL-STD-2525 symbol code.
     * @param modifiers  The MIL-STD-2525 modifiers. If null, a default (empty) modifier list will be used.
     * @param attributes The MIL-STD-2525 attributes. If null, a default (empty) attribute list will be used.
     *
     * @return An ImageInfo object containing the symbol's bitmap and meta data; may be null
     */
    fun renderImage(
        symbolCode: String, modifiers: SparseArray<String>?, attributes: SparseArray<String>?
    ): ImageInfo? {
        check(initialized) {
            logMessage(
                Logger.ERROR, "MilStd2525", "renderImage", "renderer has not been initialized."
            )
        }
        val unitModifiers = modifiers ?: SparseArray()
        val renderAttributes = attributes ?: SparseArray()
        return if (!renderer.CanRender(symbolCode, unitModifiers, renderAttributes)) null
        else renderer.RenderIcon(symbolCode, unitModifiers, renderAttributes)
    }

    /**
     * This ImageSource.BitmapFactory implementation creates MIL-STD-2525 bitmaps for use with MilStd2525Placemark.
     */
    class SymbolBitmapFactory(
        private val symbolCode: String,
        modifiers: SparseArray<String>?,
        attributes: SparseArray<String>?,
        private val placemarkAttributes: PlacemarkAttributes
    ): ImageSource.BitmapFactory {
        private val modifiers = modifiers?.clone()
        private val attributes = attributes?.clone()
        private var placemarkOffset: Offset? = null

        /**
         * Returns the MIL-STD-2525 bitmap and updates the PlacemarkAttributes associated with this factory instance.
         *
         * @return a new bitmap rendered from the parameters given in the constructor; may be null
         */
        @OptIn(DelicateCoroutinesApi::class)
        override fun createBitmap(): Bitmap? {
            // Create the symbol's bitmap
            val imageInfo = renderImage(symbolCode, modifiers, attributes)
            if (imageInfo == null) {
                logMessage(
                    Logger.ERROR, "MilStd2525", "createBitmap",
                    "Failed to render image for $symbolCode"
                )
                // TODO: File JIRA issue - must return a valid bitmap, else the ImageRetriever repeatedly attempts to create the bitmap.
                return defaultImage
            }

            // Apply the computed image offset after the renderer has created the image. This is essential for proper
            // placement as the offset may change depending on the level of detail, for instance, the absence or
            // presence of text modifiers.
            val centerPoint = imageInfo.centerPoint // The center of the core symbol
            // val bounds = imageInfo.imageBounds // The extents of the image, including text modifiers
            placemarkOffset = Offset(
                OffsetMode.PIXELS, centerPoint.x.toDouble(),  // x offset
                // Use billboarding or lollipopping to prevent icon clipping by terrain as described in MIL-STD-2525C APPENDIX F.5.1.1.2
                OffsetMode.PIXELS, 0.0 /*bounds.height() - centerPoint.y*/ // y offset converted to lower-left origin
            ).also {
                // Apply the placemark offset to the attributes on the main thread. This is necessary to synchronize write
                // access to placemarkAttributes from the thread that invokes this BitmapFactory and read access from the
                // main thread.
                GlobalScope.launch(Dispatchers.Main) { placemarkAttributes.imageOffset = it }
            }

            // Return the bitmap
            return imageInfo.image
        }
    }
}