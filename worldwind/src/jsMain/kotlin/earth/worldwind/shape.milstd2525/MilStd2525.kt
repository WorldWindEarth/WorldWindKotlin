package earth.worldwind.shape.milstd2525

import earth.worldwind.render.Font
import earth.worldwind.shape.TextAttributes

/**
 * This utility class generates MIL-STD-2525 symbols and tactical graphics using the MIL-STD-2525 Symbol Rendering Library
 * @see <a href="https://github.com/missioncommand/mil-sym-js">https://github.com/missioncommand/mil-sym-js</a>
 */
actual object MilStd2525 {
    private const val GRAPHICS_LINE_WIDTH = 3f
    private const val SYMBOL_OUTLINE_WIDTH = 1

    /**
     * Controls the symbol modifiers visibility threshold
     */
    actual var modifiersThreshold = 3.2e4
    /**
     * Controls the tactical graphics labels visibility threshold
     */
    actual var labelScaleThreshold = 4.0
    actual var graphicsLineWidth = GRAPHICS_LINE_WIDTH
    var graphicsOutlineWidth = SYMBOL_OUTLINE_WIDTH.toFloat()

    init {
        // Initialize RendererSettings
        RendererSettings.setSymbologyStandard(RendererSettings.Symbology_2525C)

        // Depending on screen size and DPI you may want to change the font size.
        RendererSettings.setMPModifierFont("Arial", 12, "normal")
        RendererSettings.setModifierFont("Arial", 8, "normal")

        // Configure modifier text output
        RendererSettings.setTextBackgroundMethod(RendererSettings.TextBackgroundMethod_OUTLINE)
        RendererSettings.setTextOutlineWidth(1) // 2 is the factory default

        // Configure Single point symbol outline width
        RendererSettings.setSinglePointSymbolOutlineWidth(SYMBOL_OUTLINE_WIDTH)
    }

// TODO Implement async fonts loading check
//  fun isReady(): Promise<String> {
//    return Promise{ resolve, _ ->
//      if (!RendererUtilities.fontsLoaded()) {
//        val time = Date.now()
//        val i = setInterval(() => {
//          if (RendererUtilities.fontsLoaded()) {
//            clearInterval(i)
//            resolve("Fonts loaded")
//          } else {
//            console.log("Fonts haven\'t been loaded after: ", ((Date.now() - time) / 1000).toFixed(2), " seconds")
//          }
//        }, 50)
//      } else {
//        resolve("Fonts loaded")
//      }
//    }
//  }

    /**
     * Creates an MIL-STD-2525 symbol from the specified symbol code, modifiers and attributes.
     *
     * @param symbolCode The MIL-STD-2525 symbol code.
     * @param modifiers  The MIL-STD-2525 modifiers. If null, a default (empty) modifier list will be used.
     * @param attributes The MIL-STD-2525 attributes. If null, a default (empty) attribute list will be used.
     *
     * @return An ImageInfo object containing the symbol's image and metadata may be null
     */
    fun renderImage(symbolCode: String, modifiers: Map<String, String>?, attributes: Map<String, String>?): ImageInfo? {
        val params = mutableMapOf<String, String>()
        if (modifiers != null) {
            params.putAll(modifiers)
        }
        if (attributes != null) {
            params.putAll(attributes)
        }
        return armyc2.c2sd.renderer.MilStdIconRenderer.Render(symbolCode, params)
    }

    /**
     * Get symbol text description and hierarchy reference.
     */
    fun getSymbolDef(sidc: String): dynamic =
        SymbolDefTable.getSymbolDef(SymbolUtilities.getBasicSymbolID(sidc), RendererSettings.getSymbologyStandard())

    /**
     * Get symbol visual attributes like draw category, min points, max points, etc.
     */
    fun getUnitDef(sidc: String): dynamic =
        UnitDefTable.getUnitDef(SymbolUtilities.getBasicSymbolID(sidc), RendererSettings.getSymbologyStandard())

    fun applyTextAttributes(textAttributes: TextAttributes) = textAttributes.apply {
        font = Font(
            size = RendererSettings.getModifierFontSize().toInt(),
            family = RendererSettings.getModifierFontName(),
            weight = RendererSettings.getModifierFontStyle()
        )
        textColor.set(RendererSettings.getLabelForegroundColor().toInt())
        outlineColor.set(RendererSettings.getLabelBackgroundColor().toInt())
        outlineWidth =  RendererSettings.getTextOutlineWidth().toFloat()
    }

    actual fun getSimplifiedSymbolID(sidc: String) =
        setAffiliation(SymbolUtilities.getBasicSymbolID(sidc), sidc.substring(1, 2))

    actual fun isTacticalGraphic(sidc: String) = SymbolUtilities.isTacticalGraphic(sidc)

    actual fun setAffiliation(sidc: String, affiliation: String?) =
        // Weather symbols has no affiliation
        if (sidc.length >= 2 && !SymbolUtilities.isWeather(sidc) && affiliation != null && affiliation.length == 1) {
            val result = sidc.substring(0, 1) + affiliation.uppercase() + sidc.substring(2)
            if (SymbolUtilities.hasValidAffiliation(result)) result else sidc
        } else sidc

    actual fun setStatus(sidc: String, status: String?) =
        // Weather symbols has no status
        if (sidc.length >= 4 && !SymbolUtilities.isWeather(sidc) && status != null && status.length == 1) {
            val result = sidc.substring(0, 3) + status.uppercase() + sidc.substring(4)
            if (SymbolUtilities.hasValidStatus(result)) result else sidc
        } else sidc

    actual fun setEchelon(sidc: String, echelon: String?): String {
        val isTG = SymbolUtilities.isTacticalGraphic(sidc)
        return if (sidc.length >= 12 && (isTG && SymbolUtilities.canSymbolHaveModifier(sidc, ModifiersTG.B_ECHELON)
                    || !isTG && SymbolUtilities.canUnitHaveModifier(sidc, ModifiersUnits.B_ECHELON))
            && echelon != null && echelon.length == 1 && SymbolUtilities.getEchelonText(echelon).isNotEmpty()
        ) sidc.substring(0, 11) + echelon.uppercase() + sidc.substring(12) else sidc
    }

    actual fun setSymbolModifier(
        sidc: String, hq: Boolean, taskForce: Boolean, feintDummy: Boolean, installation: Boolean, mobility: String?
    ): String {
        var result = sidc
        if (result.length >= 11 && !SymbolUtilities.isTacticalGraphic(result)) {
            // Check if mobility is applicable
            if (mobility != null && mobility.length == 2 && result.length >= 12
                && SymbolUtilities.canUnitHaveModifier(result, ModifiersUnits.R_MOBILITY_INDICATOR)
            ) {
                // Try applying mobility
                val sidcWithMobility = result.substring(0, 10) + mobility.uppercase() + result.substring(12)
                // Check if mobility is valid
                if (SymbolUtilities.isMobility(sidcWithMobility)) result = sidcWithMobility
            } else {
                // Check if HQ, TaskForce, Feint or Dummy symbol modifiers are applicable
                val isHQ = hq && SymbolUtilities.canUnitHaveModifier(result, ModifiersUnits.S_HQ_STAFF_OR_OFFSET_INDICATOR)
                val isTaskForce = taskForce && SymbolUtilities.canUnitHaveModifier(result, ModifiersUnits.D_TASK_FORCE_INDICATOR)
                val isDummy = feintDummy && SymbolUtilities.canUnitHaveModifier(result, ModifiersUnits.AB_FEINT_DUMMY_INDICATOR)
                val isInstallation = installation && SymbolUtilities.canUnitHaveModifier(result, ModifiersUnits.AC_INSTALLATION)
                val modifier = when {
                    !isDummy && isHQ && !isTaskForce -> 'A' // HEADQUARTERS
                    !isDummy && isHQ && isTaskForce -> 'B' // TASK FORCE + HEADQUARTERS
                    isDummy && isHQ && !isTaskForce -> 'C' // FEINT/DUMMY + HEADQUARTERS
                    isDummy && isHQ && isTaskForce -> 'D' // FEINT/DUMMY + TASK FORCE + HEADQUARTERS
                    !isDummy && !isHQ && isTaskForce -> 'E' // TASK FORCE
                    isDummy && !isHQ && !isTaskForce -> 'F' // FEINT/DUMMY
                    isDummy && !isHQ && isTaskForce -> 'G' // FEINT/DUMMY + TASK FORCE
                    else -> if (isInstallation) 'H' else null
                }
                // Apply symbol modifier
                if (modifier != null) result = result.substring(0, 10) + modifier + result.substring(11)
                // Fix Feint/Dummy modifier for installation
                if (isDummy && result.length >= 12 && SymbolUtilities.hasInstallationModifier(result)) {
                    result = result.substring(0, 11) + "B" + result.substring(12)
                }
            }
        }
        return result
    }

    actual fun setCountryCode(sidc: String, countryCode: String?) =
        if (sidc.length >= 14 && countryCode != null && countryCode.length == 2) {
            val result = sidc.substring(0, 12) + countryCode.uppercase() + sidc.substring(14)
            if (SymbolUtilities.hasValidCountryCode(result)) result else sidc
        } else sidc

    actual fun getLineColor(sidc: String) = SymbolUtilities.getLineColorOfAffiliation(sidc)?.toARGB()?.toInt()
        ?: RendererSettings.getFriendlyGraphicLineColor().toARGB().toInt()

    actual fun getFillColor(sidc: String) = SymbolUtilities.getFillColorOfAffiliation(sidc)?.toARGB()?.toInt()
        ?: RendererSettings.getFriendlyGraphicFillColor().toARGB().toInt()
}
