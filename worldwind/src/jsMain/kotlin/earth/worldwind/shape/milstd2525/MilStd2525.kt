package earth.worldwind.shape.milstd2525

import earth.worldwind.render.Font
import earth.worldwind.shape.TextAttributes
import earth.worldwind.shape.milstd2525.Font.Companion.getTypeString
import kotlin.js.collections.JsMap

/**
 * This utility class generates MIL-STD-2525 symbols and tactical graphics using the MIL-STD-2525 Symbol Rendering Library
 * @see <a href="https://github.com/missioncommand/mil-sym-js">https://github.com/missioncommand/mil-sym-js</a>
 */
actual object MilStd2525 {
    init {
        // Initialize resources
        initialize("/files/")

        // Initialize RendererSettings
        val rendererSettings = RendererSettings.getInstance()
        rendererSettings.setDefaultPixelSize(36)
        rendererSettings.setOutlineSPControlMeasures(false) // Do not outline single point control measures
        rendererSettings.setTwoLabelOnly(true) // Show only two labels fo minefield
        rendererSettings.setActionPointDefaultFill(false) // Do not fill action points

        // Depending on screen size and DPI you may want to change the font size.
        rendererSettings.setLabelFont("Arial", "normal", 8)
        rendererSettings.setMPLabelFont("Arial", "normal", 12)

        // Configure modifier text output
        rendererSettings.setTextBackgroundMethod(RendererSettings.TextBackgroundMethod_OUTLINE)
        rendererSettings.setTextBackgroundAutoColorThreshold(180)
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
    @OptIn(ExperimentalJsCollectionsApi::class, ExperimentalJsExport::class)
    fun renderImage(
        symbolCode: String, modifiers: Map<String, String>?, attributes: Map<String, String>?
    ): SVGSymbolInfo? = MilStdIconRenderer.getInstance().RenderSVG(
        symbolCode,
        modifiers?.mapKeys { Modifiers.getModifierKey(it.key) ?: "" }?.asJsReadonlyMapView() ?: JsMap(),
        attributes?.asJsReadonlyMapView() ?: JsMap()
    )

    fun applyTextAttributes(textAttributes: TextAttributes) = textAttributes.apply {
        val rendererSettings = RendererSettings.getInstance()
        font = Font(
            size = rendererSettings.getLabelFontSize().toInt(),
            family = rendererSettings.getLabelFontName(),
            weight = getTypeString(rendererSettings.getLabelFontType().toDouble())
        )
//        val foregroundColor = rendererSettings.getLabelForegroundColor() ?: Color(0, 0, 0)
//        val backgroundColor = rendererSettings.getLabelBackgroundColor() ?: RendererUtilities.getIdealOutlineColor(foregroundColor)
//        textColor.set(foregroundColor.toARGB().toInt())
//        outlineColor.set(backgroundColor.toARGB().toInt())
//        outlineWidth =  rendererSettings.getTextOutlineWidth().toFloat()
    }

    actual fun isTacticalGraphic(symbolID: String) = SymbolUtilities.isTacticalGraphic(symbolID)

    actual fun getUnfilledAttributes(symbolID: String) = if (isTacticalGraphic(symbolID)) {
        SymbolUtilities.getLineColorOfAffiliation(symbolID)
    } else {
        SymbolUtilities.getFillColorOfAffiliation(symbolID)
    }?.toHexString(true)?.let {
        mapOf(
            MilStdAttributes.FillColor to "00000000",
            MilStdAttributes.LineColor to it,
            MilStdAttributes.IconColor to it
        )
    } ?: emptyMap()
}
