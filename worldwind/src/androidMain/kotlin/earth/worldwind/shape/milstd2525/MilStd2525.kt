package earth.worldwind.shape.milstd2525

import android.content.Context
import android.graphics.Typeface
import android.util.SparseArray
import armyc2.c2sd.renderer.MilStdIconRenderer
import armyc2.c2sd.renderer.utilities.*
import earth.worldwind.R

/**
 * This utility class generates MIL-STD-2525 symbols and tactical graphics using the MIL-STD-2525 Symbol Rendering Library
 * @see <a href="https://github.com/missioncommand/mil-sym-android">https://github.com/missioncommand/mil-sym-android</a>
 */
actual object MilStd2525 {
    /**
     * The actual rendering engine for the MIL-STD-2525 graphics.
     */
    private val renderer = MilStdIconRenderer.getInstance()
    private val rendererSettings = RendererSettings.getInstance()
    actual var graphicsLineWidth = 0f
        private set
    var graphicsOutlineWidth = 0f
        private set
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
        // See: https://github.com/missioncommand/mil-sym-android/blob/master/Renderer/src/main/java/armyc2/c2sd/renderer/utilities/RendererSettings.java
        rendererSettings.symbologyStandard = RendererSettings.Symbology_2525C
        rendererSettings.defaultPixelSize = context.resources.getDimensionPixelSize(R.dimen.default_pixel_size)

        // Depending on screen size and DPI you may want to change the font size.
        rendererSettings.setModifierFont("Arial", Typeface.BOLD, context.resources.getDimensionPixelSize(R.dimen.modifier_font_size))
        rendererSettings.setMPModifierFont("Arial", Typeface.BOLD, context.resources.getDimensionPixelSize(R.dimen.mp_modifier_font_size))

        // Configure modifier text output
        rendererSettings.textBackgroundMethod = RendererSettings.TextBackgroundMethod_OUTLINE
        rendererSettings.textOutlineWidth = context.resources.getDimensionPixelSize(R.dimen.text_outline_width)

        // Configure Single point symbol outline width
        rendererSettings.singlePointSymbolOutlineWidth = context.resources.getDimensionPixelSize(R.dimen.symbol_outline_width)

        // Tell the renderer where the cache folder is located which is needed to process the embedded xml files.
        renderer.init(context, context.cacheDir.absolutePath)
        graphicsLineWidth = context.resources.getDimension(R.dimen.graphics_line_width)
        graphicsOutlineWidth = context.resources.getDimension(R.dimen.symbol_outline_width)
        isInitialized = true
    }

    @JvmStatic
    fun symbolModifiersToSparseArray(modifiers: Map<String, String>?): SparseArray<String> {
        val modifiersArray = SparseArray<String>()
        modifiers?.entries?.forEach { (key, value) -> modifiersArray.put(ModifiersUnits.getModifierKey(key), value) }
        return modifiersArray
    }

    @JvmStatic
    fun symbolModifiersFromSparseArray(modifiersArray: SparseArray<String>?): Map<String, String> {
        val modifiers = mutableMapOf<String, String>()
        if (modifiersArray != null) for (i in 0 until modifiersArray.size())
            modifiers[ModifiersUnits.getModifierLetterCode(modifiersArray.keyAt(i))] = modifiersArray.valueAt(i)
        return modifiers
    }

    @JvmStatic
    fun graphicModifiersToSparseArray(modifiers: Map<String, String>?): SparseArray<String> {
        val modifiersArray = SparseArray<String>()
        modifiers?.entries?.forEach { (key, value) -> modifiersArray.put(ModifiersTG.getModifierKey(key), value) }
        return modifiersArray
    }

    @JvmStatic
    fun graphicModifiersFromSparseArray(modifiersArray: SparseArray<String>?): Map<String, String> {
        val modifiers = mutableMapOf<String, String>()
        if (modifiersArray != null) for (i in 0 until modifiersArray.size())
            modifiers[ModifiersTG.getModifierLetterCode(modifiersArray.keyAt(i))] = modifiersArray.valueAt(i)
        return modifiers
    }

    @JvmStatic
    fun attributesToSparseArray(attributes: Map<String, String>?): SparseArray<String> {
        val modifiersArray = SparseArray<String>()
        attributes?.entries?.forEach { (key, value) -> modifiersArray.put(key.toInt(), value) }
        return modifiersArray
    }

    @JvmStatic
    fun attributesFromSparseArray(attributesArray: SparseArray<String>?): Map<String, String> {
        val attributes = mutableMapOf<String, String>()
        if (attributesArray != null) for (i in 0 until attributesArray.size())
            attributes[attributesArray.keyAt(i).toString()] = attributesArray.valueAt(i)
        return attributes
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
    fun renderImage(symbolCode: String, modifiers: Map<String, String>?, attributes: Map<String, String>?): ImageInfo? =
        renderer.RenderIcon(symbolCode, symbolModifiersToSparseArray(modifiers), attributesToSparseArray(attributes))

    /**
     * Get symbol text description and hierarchy reference.
     */
    @JvmStatic
    fun getSymbolDef(sidc: String): SymbolDef? = SymbolDefTable.getInstance()
        .getSymbolDef(SymbolUtilities.getBasicSymbolID(sidc), rendererSettings.symbologyStandard)

    /**
     * Get symbol visual attributes like draw category, min points, max points, etc.
     */
    @JvmStatic
    fun getUnitDef(sidc: String): UnitDef? = UnitDefTable.getInstance()
        .getUnitDef(SymbolUtilities.getBasicSymbolID(sidc), rendererSettings.symbologyStandard)

    @JvmStatic
    actual fun getSimplifiedSymbolID(sidc: String) =
        setAffiliation(SymbolUtilities.getBasicSymbolID(sidc), sidc.substring(1, 2))

    @JvmStatic
    actual fun isTacticalGraphic(sidc: String) = SymbolUtilities.isTacticalGraphic(sidc)

    @JvmStatic
    actual fun setAffiliation(sidc: String, affiliation: String?) =
        if (sidc.length >= 2 && !SymbolUtilities.isWeather(sidc) && affiliation != null && affiliation.length == 1) {
            val result = sidc.substring(0, 1) + affiliation.uppercase() + sidc.substring(2)
            if (SymbolUtilities.hasValidAffiliation(result)) result else sidc
        } else sidc

    @JvmStatic
    actual fun setStatus(sidc: String, status: String?) =
        // Weather symbols has no status
        if (sidc.length >= 4 && !SymbolUtilities.isWeather(sidc) && status != null && status.length == 1) {
            val result = sidc.substring(0, 3) + status.uppercase() + sidc.substring(4)
            if (SymbolUtilities.hasValidStatus(result)) result else sidc
        } else sidc

    @JvmStatic
    actual fun setEchelon(sidc: String, echelon: String?): String {
        val isTG = SymbolUtilities.isTacticalGraphic(sidc)
        return if (sidc.length >= 12 && (isTG && SymbolUtilities.canSymbolHaveModifier(sidc, ModifiersTG.B_ECHELON)
                    || !isTG && SymbolUtilities.canUnitHaveModifier(sidc, ModifiersUnits.B_ECHELON))
            && echelon != null && echelon.length == 1 && SymbolUtilities.getEchelonText(echelon) != null
        ) sidc.substring(0, 11) + echelon.uppercase() + sidc.substring(12) else sidc
    }

    @JvmStatic
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
                val modifier = if (hq && SymbolUtilities.canUnitHaveModifier(result, ModifiersUnits.S_HQ_STAFF_OR_OFFSET_INDICATOR)) "A" // Headquarters
                else if (installation && SymbolUtilities.canUnitHaveModifier(result, ModifiersUnits.AC_INSTALLATION)) "H" // Installation
                else if (taskForce && SymbolUtilities.canUnitHaveModifier(result, ModifiersUnits.D_TASK_FORCE_INDICATOR)
                    && feintDummy && SymbolUtilities.canUnitHaveModifier(result, ModifiersUnits.AB_FEINT_DUMMY_INDICATOR)) "D" // TaskForce/Feint/Dummy
                else if (taskForce && SymbolUtilities.canUnitHaveModifier(result, ModifiersUnits.D_TASK_FORCE_INDICATOR)) "B" // TaskForce
                else if (feintDummy && SymbolUtilities.canUnitHaveModifier(result, ModifiersUnits.AB_FEINT_DUMMY_INDICATOR)) "C" // Feint/Dummy
                else null
                // Apply symbol modifier
                if (modifier != null) result = result.substring(0, 10) + modifier + result.substring(11)
                // Fix Feint/Dummy modifier for installation
                if (feintDummy && result.length >= 12 && SymbolUtilities.hasInstallationModifier(result)
                    && SymbolUtilities.canUnitHaveModifier(result, ModifiersUnits.AB_FEINT_DUMMY_INDICATOR)
                ) result = result.substring(0, 11) + "B" + result.substring(12)
            }
        }
        return result
    }

    @JvmStatic
    actual fun setCountryCode(sidc: String, countryCode: String?) =
        if (sidc.length >= 14 && countryCode != null && countryCode.length == 2) {
            val result = sidc.substring(0, 12) + countryCode.uppercase() + sidc.substring(14)
            if (SymbolUtilities.hasValidCountryCode(result)) result else sidc
        } else sidc

    @JvmStatic
    actual fun getLineColor(sidc: String) = SymbolUtilities.getLineColorOfAffiliation(sidc)?.toARGB()
        ?: rendererSettings.friendlyGraphicLineColor.toARGB()

    @JvmStatic
    actual fun getFillColor(sidc: String) = SymbolUtilities.getFillColorOfAffiliation(sidc)?.toARGB()
        ?: rendererSettings.friendlyGraphicFillColor.toARGB()
}