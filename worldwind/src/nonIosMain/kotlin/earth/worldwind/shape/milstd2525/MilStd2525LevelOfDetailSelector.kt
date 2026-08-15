package earth.worldwind.shape.milstd2525

import earth.worldwind.render.RenderContext
import earth.worldwind.shape.Placemark
import earth.worldwind.shape.PlacemarkAttributes
import earth.worldwind.shape.milstd2525.MilStd2525.getUnfilledAttributes
import earth.worldwind.shape.milstd2525.MilStd2525.isTacticalGraphic
import earth.worldwind.shape.milstd2525.MilStd2525Placemark.Companion.getPlacemarkAttributes

/**
 * The [MilStd2525LevelOfDetailSelector] determines which set of [PlacemarkAttributes] to use for a [MilStd2525Placemark].
 * A [MilStd2525Placemark] creates an instance of this class in its constructor, and calls
 * [Placemark.LevelOfDetailSelector.selectLevelOfDetail] in its [Placemark.doRender] method.
 *
 * The selection is remembered per rendering window: several windows may render the same placemark in the same
 * choreographer pass with different camera distances and fill modes, and must not discard each other's selection.
 */
open class MilStd2525LevelOfDetailSelector : Placemark.LevelOfDetailSelector {
    /**
     * Level of detail selection last applied for one rendering window.
     */
    protected class WindowState {
        var levelOfDetail = -1
        var isHighlighted = false
        var isFilled = true
        var version = -1
        var attributes: PlacemarkAttributes? = null
    }

    protected var version = 0
    /**
     * Keyed by the [RenderContext] identity hash instead of the context itself to avoid retaining
     * destroyed windows' render state.
     */
    protected val windowStates = mutableMapOf<Int, WindowState>()
    protected var lastSymbolID: String? = null
    protected var unfilledAttributes: Map<String, String>? = null

    override fun invalidate() { ++version }

    /**
     * Gets the active attributes for the current distance to the camera and highlighted state.
     *
     * @param rc             The current render contents
     * @param placemark      The placemark needing a level of detail selection
     * @param cameraDistance The distance from the placemark to the camera (meters)
     *
     * @return if placemark should display or skip its rendering
     */
    override fun selectLevelOfDetail(rc: RenderContext, placemark: Placemark, cameraDistance: Double): Boolean {
        if (placemark !is MilStd2525Placemark) return true
        val isHighlighted = placemark.isHighlighted
        val isFilled = placemark.isFilled

        // Determine the level of detail based on highlighted state and the distance from the camera to the placemark
        val levelOfDetail = when {
            // Low-fidelity: use a Symbol ID with affiliation code only
            cameraDistance > placemark.eyeDistanceScalingThreshold && !isHighlighted -> LOW_LEVEL_OF_DETAIL
            // Medium-fidelity: use a simplified Symbol ID without status, mobility, size and text modifiers
            cameraDistance > modifiersThreshold && !isHighlighted || !placemark.isModifiersVisible -> MEDIUM_LEVEL_OF_DETAIL
            // High-fidelity: use the regular Symbol ID without text modifiers, except unique designation (T)
            !isHighlighted && !isForceAllModifiers -> HIGH_LEVEL_OF_DETAIL
            // Highest-fidelity: use the regular Symbol ID with all available text modifiers
            else -> HIGHEST_LEVEL_OF_DETAIL
        }

        val state = windowStates.getOrPut(rc.hashCode()) { WindowState() }
        val attributes = state.attributes?.takeIf {
            state.levelOfDetail == levelOfDetail && state.isFilled == isFilled &&
                    state.isHighlighted == isHighlighted && state.version == version
        } ?: buildAttributes(placemark, levelOfDetail).also {
            state.attributes = it
            state.levelOfDetail = levelOfDetail
            state.isFilled = isFilled
            state.isHighlighted = isHighlighted
            state.version = version
        }
        // Re-apply the selection each frame: another window may have applied its own bundle meanwhile
        placemark.attributes = attributes

        attributes.isDrawLeader = levelOfDetail >= MEDIUM_LEVEL_OF_DETAIL
        attributes.imageScale = if (isHighlighted) HIGHLIGHTED_SCALE else NORMAL_SCALE
        attributes.labelAttributes.scale = if (isHighlighted) HIGHLIGHTED_SCALE else NORMAL_SCALE

        return true
    }

    protected open fun buildAttributes(placemark: MilStd2525Placemark, levelOfDetail: Int): PlacemarkAttributes {
        val symbolID = placemark.symbolID
        return when (levelOfDetail) {
            LOW_LEVEL_OF_DETAIL -> {
                val simpleCode = if (isTacticalGraphic(symbolID)) getSimplifiedSymbolID(symbolID)
                else symbolID.substring(0, 6) + "000000000000000000000000"
                getPlacemarkAttributes(simpleCode, symbolAttributes = getAttributes(placemark))
            }

            MEDIUM_LEVEL_OF_DETAIL -> getPlacemarkAttributes(
                getSimplifiedSymbolID(symbolID), symbolAttributes = getAttributes(placemark)
            )

            HIGH_LEVEL_OF_DETAIL -> getPlacemarkAttributes(
                symbolID, placemark.symbolModifiers?.filter { (key, _) -> key == "T" }, getAttributes(placemark)
            )

            else -> getPlacemarkAttributes(symbolID, placemark.symbolModifiers, getAttributes(placemark))
        }
    }

    private fun getSimplifiedSymbolID(symbolID: String) = symbolID.substring(0, 6) + "0000" + symbolID.substring(10)

    private fun getAttributes(placemark: MilStd2525Placemark): Map<String, String>? {
        if (placemark.isFilled) return placemark.symbolAttributes

        if (lastSymbolID != placemark.symbolID) {
            unfilledAttributes = null
            lastSymbolID = placemark.symbolID
        }

        val unfilledAttributes = unfilledAttributes ?: getUnfilledAttributes(placemark.symbolID).also {
            unfilledAttributes = it
        }

        return placemark.symbolAttributes?.let { unfilledAttributes + it } ?: unfilledAttributes
    }

    companion object {
        /**
         * Controls the symbol modifiers visibility threshold
         */
        var modifiersThreshold = 3.2e4
        /**
         * Always use the highest fidelity instead of high (forces all text modifiers)
         */
        var isForceAllModifiers = false
        protected const val NORMAL_SCALE = 1.0
        protected const val HIGHLIGHTED_SCALE = 1.3
        protected const val LOW_LEVEL_OF_DETAIL = 0
        protected const val MEDIUM_LEVEL_OF_DETAIL = 1
        protected const val HIGH_LEVEL_OF_DETAIL = 2
        protected const val HIGHEST_LEVEL_OF_DETAIL = 3
    }
}
