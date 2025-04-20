package earth.worldwind.shape.milstd2525

import android.content.Context
import android.graphics.Typeface
import armyc2.c5isr.renderer.MilStdIconRenderer
import armyc2.c5isr.renderer.utilities.*
import earth.worldwind.R
import earth.worldwind.render.Font
import earth.worldwind.shape.TextAttributes

/**
 * This utility class generates MIL-STD-2525 symbols and tactical graphics using the MIL-STD-2525 Symbol Rendering Library
 * @see <a href="https://github.com/missioncommand/mil-sym-android">https://github.com/missioncommand/mil-sym-android</a>
 */
actual object MilStd2525 {
    @JvmStatic
    var isInitialized = false
        private set

    /**
     * Initializes the static MIL-STD-2525 symbol renderer.  This method must be called one time before calling
     * renderImage().
     *
     * @param context The Context used to define the location of the renderer's cache directly.
     */
    @JvmStatic
    @Synchronized
    fun initializeRenderer(context: Context) {
        if (isInitialized) return

        // Establish the default rendering values.
        val rendererSettings = RendererSettings.getInstance()
        rendererSettings.defaultPixelSize = context.resources.getDimensionPixelSize(R.dimen.default_pixel_size)
        rendererSettings.outlineSPControlMeasures = false // Do not outline single point control measures
        rendererSettings.twoLabelOnly = true // Show only two labels fo minefield
        rendererSettings.actionPointDefaultFill = false // Do not fill action points

        // Depending on screen size and DPI you may want to change the font size.
        rendererSettings.setModifierFont("Arial", Typeface.BOLD, context.resources.getDimensionPixelSize(R.dimen.modifier_font_size))
        rendererSettings.setMPLabelFont("Arial", Typeface.BOLD, context.resources.getDimensionPixelSize(R.dimen.mp_modifier_font_size))

        // Configure modifier text output
        rendererSettings.textBackgroundMethod = RendererSettings.TextBackgroundMethod_OUTLINE
        rendererSettings.textBackgroundAutoColorThreshold = 180

        // Tell the renderer where the cache folder is located which is needed to process the embedded xml files.
        MilStdIconRenderer.getInstance().init(context)
        MSLookup.getInstance().init(context)
        isInitialized = true
    }

    /**
     * Creates an MIL-STD-2525 symbol from the specified symbol code, modifiers and attributes.
     *
     * @param symbolCode The MIL-STD-2525 symbol code.
     * @param modifiers  The MIL-STD-2525 modifiers. If null, a default (empty) modifier list will be used.
     * @param attributes The MIL-STD-2525 attributes. If null, a default (empty) attribute list will be used.
     *
     * @return An ImageInfo object containing the symbol's bitmap and metadata; may be null
     */
    @JvmStatic
    fun renderImage(
        symbolCode: String, modifiers: Map<String, String>?, attributes: Map<String, String>?
    ): ImageInfo? = MilStdIconRenderer.getInstance().RenderIcon(
        symbolCode, modifiers?.mapKeys { Modifiers.getModifierKey(it.key) ?: "" } ?: emptyMap(), attributes ?: emptyMap()
    )

    @JvmStatic
    fun applyTextAttributes(textAttributes: TextAttributes) = textAttributes.apply {
        val rendererSettings = RendererSettings.getInstance()
        val modifierFont = rendererSettings.modiferFont
        font = Font(modifierFont.textSize, modifierFont.typeface)
//        textColor.set(rendererSettings.labelForegroundColor)
//        outlineColor.set(rendererSettings.labelBackgroundColor)
//        outlineWidth = rendererSettings.textOutlineWidth.toFloat()
    }

    @JvmStatic
    actual fun isTacticalGraphic(symbolID: String) = SymbolUtilities.isTacticalGraphic(symbolID)

    @JvmStatic
    actual fun getUnfilledAttributes(symbolID: String) = if (SymbolUtilities.isTacticalGraphic(symbolID)) {
        SymbolUtilities.getLineColorOfAffiliation(symbolID)
    } else {
        SymbolUtilities.getFillColorOfAffiliation(symbolID)
    }?.toHexString()?.let {
        mapOf(
            MilStdAttributes.FillColor.toString() to "00000000",
            MilStdAttributes.LineColor.toString() to it,
            MilStdAttributes.IconColor.toString() to it
        )
    } ?: emptyMap()
}