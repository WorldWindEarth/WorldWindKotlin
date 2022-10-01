package earth.worldwind.examples.milstd2525

import earth.worldwind.examples.milstd2525.MilStd2525.getPlacemarkAttributes
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.render.RenderContext
import earth.worldwind.shape.Placemark
import earth.worldwind.shape.Placemark.LevelOfDetailSelector
import earth.worldwind.shape.PlacemarkAttributes
import earth.worldwind.util.Logger
import earth.worldwind.util.Logger.logMessage

/**
 * The MilStd2525LevelOfDetailSelector determines which set of PlacemarkAttributes to use for a MilStd2525Placemark. A
 * [MilStd2525Placemark] creates an instance of this class in its constructor, and calls
 * [Placemark.LevelOfDetailSelector.selectLevelOfDetail] in its doRender() method.
 */
open class MilStd2525LevelOfDetailSelector: LevelOfDetailSelector {
    protected var lastLevelOfDetail = -1
    protected var lastHighlightState = false
    protected lateinit var placemarkAttributes: PlacemarkAttributes

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
        require(placemark is MilStd2525Placemark) {
            logMessage(
                Logger.ERROR, "MilStd2525LevelOfDetailSelector", "selectLevelOfDetail",
                "The placemark is not a MilStd2525Placemark"
            )
        }

        // Determine the normal attributes based on the distance from the camera to the placemark
        val highlighted = placemark.isHighlighted
        val highlightChanged = lastHighlightState != highlighted
        if (cameraDistance > FAR_THRESHOLD) {
            // Low-fidelity: use a simplified SIDC code (without status) and no modifiers
            if (lastLevelOfDetail != LOW_LEVEL_OF_DETAIL || highlightChanged) {
                val simpleCode = placemark.symbolCode.substring(0, 3) + "*------*****" // SIDC
                placemarkAttributes = getPlacemarkAttributes(simpleCode, null, placemark.symbolAttributes)
                lastLevelOfDetail = LOW_LEVEL_OF_DETAIL
            }
        } else if (cameraDistance > NEAR_THRESHOLD) {
            // Medium-fidelity: use the regulation SIDC code but without modifiers
            if (lastLevelOfDetail != MEDIUM_LEVEL_OF_DETAIL || highlightChanged) {
                placemarkAttributes = getPlacemarkAttributes(
                    placemark.symbolCode, null, placemark.symbolAttributes
                )
                lastLevelOfDetail = MEDIUM_LEVEL_OF_DETAIL
            }
        } else {
            // High-fidelity: use the regulation SIDC code and the modifiers
            if (lastLevelOfDetail != HIGHEST_LEVEL_OF_DETAIL || highlightChanged) {
                placemarkAttributes = getPlacemarkAttributes(
                    placemark.symbolCode, placemark.symbolModifiers, placemark.symbolAttributes
                )
                lastLevelOfDetail = HIGHEST_LEVEL_OF_DETAIL
            }
        }
        if (highlightChanged) {
            // Use a distinct set of attributes when highlighted, otherwise use the shared attributes
            if (highlighted) {
                // Create a copy of the shared attributes bundle and increase the scale
                val scale = placemarkAttributes.imageScale
                placemarkAttributes = PlacemarkAttributes(placemarkAttributes)
                placemarkAttributes.imageScale = scale * 1.2
            }
        }
        lastHighlightState = highlighted

        // Draw leader line for flying objects
        placemarkAttributes.isDrawLeader = placemark.altitudeMode != AltitudeMode.CLAMP_TO_GROUND
                && lastLevelOfDetail != LOW_LEVEL_OF_DETAIL

        // Update the placemark's attributes bundle
        placemark.attributes = placemarkAttributes
        return true // Placemark is always visible
    }

    companion object {
        protected const val HIGHEST_LEVEL_OF_DETAIL = 0
        protected const val MEDIUM_LEVEL_OF_DETAIL = 1
        protected const val LOW_LEVEL_OF_DETAIL = 2
        protected var FAR_THRESHOLD = 500000.0
        protected var NEAR_THRESHOLD = 300000.0

        /**
         * Sets the far distance threshold; camera distances greater than this value use the low level of detail, and
         * distances less than this value but greater than the near threshold use the medium level of detail.
         *
         * @param farThreshold camera distance threshold in meters
         */
        fun setFarThreshold(farThreshold: Double) { FAR_THRESHOLD = farThreshold }

        /**
         * Sets the near distance threshold; camera distances greater than this value but less that the far threshold use
         * the medium level of detail, and distances less than this value use the high level of detail.
         *
         * @param nearThreshold camera distance threshold in meters
         */
        fun setNearThreshold(nearThreshold: Double) { NEAR_THRESHOLD = nearThreshold }
    }
}