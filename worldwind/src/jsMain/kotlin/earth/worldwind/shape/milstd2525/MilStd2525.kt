package earth.worldwind.shape.milstd2525

import earth.worldwind.render.Font
import earth.worldwind.shape.TextAttributes
import earth.worldwind.shape.milstd2525.Font.Companion.getTypeString
import kotlin.js.collections.JsMap

@JsModule("@armyc2.c5isr.renderer/mil-sym-ts/data/genc.4079ea2923ffe59c6374.json")
@JsNonModule
private external val genc: dynamic

@JsModule("@armyc2.c5isr.renderer/mil-sym-ts/data/msd.08108ef3b61555d050f3.json")
@JsNonModule
private external val msd: dynamic

@JsModule("@armyc2.c5isr.renderer/mil-sym-ts/data/mse.3b58217607a1d34606dd.json")
@JsNonModule
private external val mse: dynamic

@JsModule("@armyc2.c5isr.renderer/mil-sym-ts/data/svgd.60c4f9362343eedde86c.json")
@JsNonModule
private external val svgd: dynamic

@JsModule("@armyc2.c5isr.renderer/mil-sym-ts/data/svge.ee0cc365383412da1439.json")
@JsNonModule
private external val svge: dynamic

/**
 * This utility class generates MIL-STD-2525 symbols and tactical graphics using the MIL-STD-2525 Symbol Rendering Library
 * @see <a href="https://github.com/missioncommand/mil-sym-js">https://github.com/missioncommand/mil-sym-js</a>
 */
actual object MilStd2525 {
    init {
        // Initialize resources
        GENCLookup.genc = genc
        GENCLookup.getInstance()
        MSLookup.msd = msd
        MSLookup.mse = mse
        MSLookup.getInstance()
        SVGLookup.svgd = svgd
        SVGLookup.svge = svge
        SVGLookup.getInstance()

        // Initialize RendererSettings
        val rendererSettings = RendererSettings.getInstance()
        rendererSettings.setDefaultPixelSize(36)
        rendererSettings.setOutlineSPControlMeasures(false) // Do not outline single point control measures
        rendererSettings.setTwoLabelOnly(true) // Show only two labels fo minefield
        rendererSettings.setActionPointDefaultFill(false) // Do not fill action points
        rendererSettings.setScaleMainIcon(true) // Make central icon bigger if no sector modifiers available

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
        symbolCode: String, modifiers: Map<String, String>? = null, attributes: Map<String, String>? = null
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
