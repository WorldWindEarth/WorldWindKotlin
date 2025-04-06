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

    /**
    * Initializes the static MIL-STD-2525 symbol renderer.
    */
    init {
        // Establish the default rendering values.
        rendererSettings.defaultPixelSize = 36

        // Depending on screen size and DPI you may want to change the font size.
        rendererSettings.setLabelFont("Arial", Font.BOLD, 8)
        rendererSettings.setMPLabelFont("Arial", Font.BOLD, 12)

        // Configure modifier text output
        rendererSettings.textBackgroundMethod = RendererSettings.TextBackgroundMethod_OUTLINE
        rendererSettings.textBackgroundAutoColorThreshold = 75
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
    ): ImageInfo? = renderer.RenderIcon(
        symbolCode, modifiers?.mapKeys { Modifiers.getModifierKey(it.key) ?: "" } ?: emptyMap(), attributes ?: emptyMap()
    )

    /**
     * Get symbol text description and visual attributes like draw category, min points, max points, etc.
     */
    @JvmStatic
    fun getMSLInfo(symbolID: String): MSInfo? = msLookup.getMSLInfo(symbolID)

    @JvmStatic
    fun applyTextAttributes(textAttributes: TextAttributes) = textAttributes.apply {
        font = earth.worldwind.render.Font(rendererSettings.labelFont)
//        textColor.set(rendererSettings.labelForegroundColor.rgb)
//        outlineColor.set(rendererSettings.labelBackgroundColor.rgb)
//        outlineWidth = rendererSettings.textOutlineWidth.toFloat()
    }

    @JvmStatic
    actual fun getSimplifiedSymbolID(symbolID: String) = symbolID.substring(0, 6) + "0000" + symbolID.substring(10, 16) + "0000"

    @JvmStatic
    actual fun isTacticalGraphic(symbolID: String) = SymbolUtilities.isTacticalGraphic(symbolID)

    @JvmStatic
    actual fun setAffiliation(symbolID: String, affiliation: String?) = affiliation?.toIntOrNull()?.let {
        SymbolID.setAffiliation(symbolID, it)
    } ?: symbolID

    @JvmStatic
    actual fun setStatus(symbolID: String, status: String?) = status?.toIntOrNull()?.let {
        SymbolID.setStatus(symbolID, it)
    } ?: symbolID

    @JvmStatic
    actual fun setEchelon(symbolID: String, echelon: String?) = echelon?.toIntOrNull()?.let {
        SymbolID.setAmplifierDescriptor(symbolID, it)
    } ?: symbolID

    @JvmStatic
    actual fun setMobility(symbolID: String, mobility: String?) = mobility?.toIntOrNull()?.let {
        SymbolID.setAmplifierDescriptor(symbolID, it)
    } ?: symbolID

    @JvmStatic
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

    @JvmStatic
    actual fun getLineColor(symbolID: String) = SymbolUtilities.getLineColorOfAffiliation(symbolID)?.rgb
        ?: rendererSettings.friendlyGraphicLineColor.rgb

    @JvmStatic
    actual fun getFillColor(symbolID: String) = SymbolUtilities.getFillColorOfAffiliation(symbolID)?.rgb
        ?: rendererSettings.friendlyGraphicFillColor.rgb

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