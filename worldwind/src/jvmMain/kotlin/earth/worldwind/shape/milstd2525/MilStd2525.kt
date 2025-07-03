package earth.worldwind.shape.milstd2525

import armyc2.c5isr.renderer.MilStdIconRenderer
import armyc2.c5isr.renderer.utilities.*
import earth.worldwind.shape.TextAttributes
import java.awt.Font

/**
 * This utility class generates MIL-STD-2525 symbols and tactical graphics using the MIL-STD-2525 Symbol Rendering Library
 * @see <a href="https://github.com/missioncommand/mil-sym-java">https://github.com/missioncommand/mil-sym-java</a>
 */
actual object MilStd2525 {
    /**
    * Initializes the static MIL-STD-2525 symbol renderer.
    */
    init {
        // Establish the default rendering values.
        val rendererSettings = RendererSettings.getInstance()
        rendererSettings.defaultPixelSize = 36
        rendererSettings.outlineSPControlMeasures = false // Do not outline single point control measures
        rendererSettings.twoLabelOnly = true // Show only two labels fo minefield
        rendererSettings.actionPointDefaultFill = false // Do not fill action points

        // Depending on screen size and DPI you may want to change the font size.
        rendererSettings.setLabelFont("Arial", Font.BOLD, 8)
        rendererSettings.setMPLabelFont("Arial", Font.BOLD, 12)

        // Configure modifier text output
        rendererSettings.textBackgroundMethod = RendererSettings.TextBackgroundMethod_OUTLINE
        rendererSettings.textBackgroundAutoColorThreshold = 180
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
        symbolCode: String, modifiers: Map<String, String>? = null, attributes: Map<String, String>? = null
    ): ImageInfo? = MilStdIconRenderer.getInstance().RenderIcon(
        symbolCode, modifiers?.mapKeys { Modifiers.getModifierKey(it.key) ?: "" } ?: emptyMap(), attributes ?: emptyMap()
    )

    @JvmStatic
    fun applyTextAttributes(textAttributes: TextAttributes) = textAttributes.apply {
        val rendererSettings = RendererSettings.getInstance()
        font = earth.worldwind.render.Font(rendererSettings.labelFont)
//        textColor.set(rendererSettings.labelForegroundColor.rgb)
//        outlineColor.set(rendererSettings.labelBackgroundColor.rgb)
//        outlineWidth = rendererSettings.textOutlineWidth.toFloat()
    }

    @JvmStatic
    actual fun isTacticalGraphic(symbolID: String) = SymbolUtilities.isTacticalGraphic(symbolID)

    @JvmStatic
    actual fun getUnfilledAttributes(symbolID: String) = if (SymbolUtilities.isTacticalGraphic(symbolID)) {
        SymbolUtilities.getLineColorOfAffiliation(symbolID)
    } else {
        SymbolUtilities.getFillColorOfAffiliation(symbolID)
    }?.rgb?.let {
        mapOf(
            MilStdAttributes.FillColor to "00000000",
            MilStdAttributes.LineColor to Integer.toHexString(it),
            MilStdAttributes.IconColor to Integer.toHexString(it)
        )
    } ?: emptyMap()
}