package earth.worldwind.shape.milstd2525

import earth.worldwind.render.Font
import earth.worldwind.shape.TextAttributes
import earth.worldwind.shape.milstd2525.Font.Companion.getTypeString

/**
 * This utility class generates MIL-STD-2525 symbols and tactical graphics using the MIL-STD-2525 Symbol Rendering Library
 * @see <a href="https://github.com/missioncommand/mil-sym-js">https://github.com/missioncommand/mil-sym-js</a>
 */
actual object MilStd2525 {
    private const val GRAPHICS_LINE_WIDTH = 3f
    private const val GRAPHICS_OUTLINE_WIDTH = 1f

    /**
     * Controls the symbol modifiers visibility threshold
     */
    actual var modifiersThreshold = 3.2e4
    /**
     * Controls the tactical graphics labels visibility threshold
     */
    actual var labelScaleThreshold = 4.0
    actual var graphicsLineWidth = GRAPHICS_LINE_WIDTH
    var graphicsOutlineWidth = GRAPHICS_OUTLINE_WIDTH
    /**
     * The actual rendering engine for the MIL-STD-2525 graphics.
     */
    private val renderer = MilStdIconRenderer.getInstance()
    private val rendererSettings = RendererSettings.getInstance()
    private val msLookup = MSLookup.getInstance()

    init {
        // Initialize fonts
        if (!isReady()) initialize()

        // Initialize RendererSettings
        rendererSettings.setDefaultPixelSize(36)

        // Depending on screen size and DPI you may want to change the font size.
        rendererSettings.setLabelFont("Arial", "normal", 8)
        rendererSettings.setMPLabelFont("Arial", "normal", 12)

        // Configure modifier text output
        rendererSettings.setTextBackgroundMethod(RendererSettings.TextBackgroundMethod_OUTLINE)
        rendererSettings.setTextBackgroundAutoColorThreshold(75)
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
    fun renderImage(
        symbolCode: String, modifiers: Map<String, String>?, attributes: Map<String, String>?
    ): SVGSymbolInfo? = renderer.RenderSVG(
        symbolCode, modifiers?.mapKeys { Modifiers.getModifierKey(it.key) ?: "" } ?: emptyMap(), attributes ?: emptyMap()
    )

    /**
     * Get symbol text description and visual attributes like draw category, min points, max points, etc.
     */
    fun getMSLInfo(symbolID: String): MSInfo? = msLookup.getMSLInfo(symbolID)

    fun applyTextAttributes(textAttributes: TextAttributes) = textAttributes.apply {
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

    actual fun getSimplifiedSymbolID(symbolID: String) = symbolID.substring(0, 6) + "0000" + symbolID.substring(10, 16) + "0000"

    actual fun isTacticalGraphic(symbolID: String) = SymbolUtilities.isTacticalGraphic(symbolID)

    actual fun setAffiliation(symbolID: String, affiliation: String?) = affiliation?.toIntOrNull()?.let {
        SymbolID.setAffiliation(symbolID, it)
    } ?: symbolID

    actual fun setStatus(symbolID: String, status: String?) = status?.toIntOrNull()?.let {
        SymbolID.setStatus(symbolID, it)
    } ?: symbolID

    actual fun setEchelon(symbolID: String, echelon: String?) = echelon?.toIntOrNull()?.let {
        SymbolID.setAmplifierDescriptor(symbolID, it)
    } ?: symbolID

    actual fun setMobility(symbolID: String, mobility: String?) = mobility?.toIntOrNull()?.let {
        SymbolID.setAmplifierDescriptor(symbolID, it)
    } ?: symbolID

    actual fun setHQTFD(symbolID: String, hq: Boolean, taskForce: Boolean, feintDummy: Boolean): String {
        val isHQ = hq && SymbolUtilities.isHQ(symbolID)
        val isTaskForce = taskForce && SymbolUtilities.isTaskForce(symbolID)
        val isDummy = feintDummy && SymbolUtilities.hasModifier(symbolID, Modifiers.AB_FEINT_DUMMY_INDICATOR)
        val HQTFD = when {
            isDummy && !isHQ && !isTaskForce -> 1 // FEINT/DUMMY
            !isDummy && isHQ && !isTaskForce -> 2 // HEADQUARTERS
            isDummy && isHQ && !isTaskForce -> 3 // FEINT/DUMMY + HEADQUARTERS
            !isDummy && !isHQ && isTaskForce -> 4 // TASK FORCE
            isDummy && !isHQ && isTaskForce -> 5 // FEINT/DUMMY + TASK FORCE
            !isDummy && isHQ && isTaskForce -> 6 // TASK FORCE + HEADQUARTERS
            isDummy && isHQ && isTaskForce -> 7 // FEINT/DUMMY + TASK FORCE + HEADQUARTERS
            else -> 0
        }
        return SymbolID.setHQTFD(symbolID, HQTFD)
    }

    actual fun getLineColor(symbolID: String) = SymbolUtilities.getLineColorOfAffiliation(symbolID)?.toARGB()?.toInt()
        ?: rendererSettings.getFriendlyGraphicLineColor().toARGB().toInt()

    actual fun getFillColor(symbolID: String) = SymbolUtilities.getFillColorOfAffiliation(symbolID)?.toARGB()?.toInt()
        ?: rendererSettings.getFriendlyGraphicFillColor().toARGB().toInt()

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
