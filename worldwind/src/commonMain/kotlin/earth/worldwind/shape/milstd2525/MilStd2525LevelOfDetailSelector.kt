package earth.worldwind.shape.milstd2525

import earth.worldwind.render.RenderContext
import earth.worldwind.shape.Placemark
import earth.worldwind.shape.PlacemarkAttributes

/**
 * The [MilStd2525LevelOfDetailSelector] determines which set of [PlacemarkAttributes] to use for a [MilStd2525Placemark].
 * A [MilStd2525Placemark] creates an instance of this class in its constructor, and calls
 * [Placemark.LevelOfDetailSelector.selectLevelOfDetail] in its [Placemark.doRender] method.
 */
open class MilStd2525LevelOfDetailSelector : Placemark.LevelOfDetailSelector {
    companion object {
        protected const val DEFAULT_HIGHLIGHT_SCALE = 1.3
        protected const val LOW_LEVEL_OF_DETAIL = 0
        protected const val MEDIUM_LEVEL_OF_DETAIL = 1
        protected const val HIGH_LEVEL_OF_DETAIL = 2
        protected const val HIGHEST_LEVEL_OF_DETAIL = 3
        /**
         * Controls the symbol modifiers visibility threshold
         */
        var modifiersThreshold = 5e4
    }

    protected var lastLevelOfDetail = -1
    protected var isHighlighted = false
    protected var isInvalidateRequested = false

    fun invalidate() { isInvalidateRequested = true }

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
        placemark as MilStd2525Placemark
        val isHighlightChanged = placemark.isHighlighted != isHighlighted
        isHighlighted = placemark.isHighlighted

        // Determine the normal attributes based on highlighted state and the distance from the camera to the placemark
        var placemarkAttributes: PlacemarkAttributes? = null
        if (cameraDistance > placemark.eyeDistanceScalingThreshold && !placemark.isHighlighted) {
            // Low-fidelity: use a SIDC code with affiliation code only
            if (lastLevelOfDetail != LOW_LEVEL_OF_DETAIL || isInvalidateRequested) {
                val simpleCode = if (MilStd2525.isTacticalGraphic(placemark.symbolCode))
                    MilStd2525.getSimplifiedSymbolID(placemark.symbolCode)
                else placemark.symbolCode.substring(0, 3) + "*------*****"
                placemarkAttributes = MilStd2525Placemark.getPlacemarkAttributes(
                    simpleCode, symbolAttributes = placemark.symbolAttributes
                )
                lastLevelOfDetail = LOW_LEVEL_OF_DETAIL
            }
        } else if (cameraDistance > modifiersThreshold && !placemark.isHighlighted || !placemark.isModifiersVisible) {
            // Medium-fidelity: use a simplified SIDC code without status, mobility, size and text modifiers
            if (lastLevelOfDetail != MEDIUM_LEVEL_OF_DETAIL || isInvalidateRequested) {
                val simpleCode = MilStd2525.getSimplifiedSymbolID(placemark.symbolCode)
                placemarkAttributes = MilStd2525Placemark.getPlacemarkAttributes(
                    simpleCode, symbolAttributes = placemark.symbolAttributes
                )
                lastLevelOfDetail = MEDIUM_LEVEL_OF_DETAIL
            }
        } else if (!placemark.isHighlighted) {
            // High-fidelity: use the regular SIDC code without text modifiers, except unique designation (T)
            if (lastLevelOfDetail != HIGH_LEVEL_OF_DETAIL || isInvalidateRequested) {
                val basicModifiers = placemark.symbolModifiers?.filter { (k,_) -> k == "T" }
                placemarkAttributes = MilStd2525Placemark.getPlacemarkAttributes(
                    placemark.symbolCode, basicModifiers, placemark.symbolAttributes
                )
                lastLevelOfDetail = HIGH_LEVEL_OF_DETAIL
            }
        } else {
            // Highest-fidelity: use the regular SIDC code with all available text modifiers
            if (lastLevelOfDetail != HIGHEST_LEVEL_OF_DETAIL || isInvalidateRequested || isHighlightChanged) {
                placemarkAttributes = MilStd2525Placemark.getPlacemarkAttributes(
                    placemark.symbolCode, placemark.symbolModifiers, placemark.symbolAttributes
                )
                lastLevelOfDetail = HIGHEST_LEVEL_OF_DETAIL
            }
        }

        isInvalidateRequested = false

        // Scale placemark across distance in low level of details
        placemark.isEyeDistanceScaling = lastLevelOfDetail == LOW_LEVEL_OF_DETAIL

        if (placemarkAttributes != null) placemark.attributes.apply {
            // Apply attributes
            copy(placemarkAttributes)
            // Draw leader line for flying placemark on high details level
            isDrawLeader = lastLevelOfDetail >= MEDIUM_LEVEL_OF_DETAIL
            // Set scale for highlighted placemark
            if (placemark.isHighlighted) imageScale *= DEFAULT_HIGHLIGHT_SCALE
        }
        return true
    }
}