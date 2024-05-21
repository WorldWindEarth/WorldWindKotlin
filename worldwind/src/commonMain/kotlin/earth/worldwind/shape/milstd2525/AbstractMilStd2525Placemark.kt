package earth.worldwind.shape.milstd2525

import earth.worldwind.geom.Position
import earth.worldwind.shape.Placemark

/**
 * Constructs a MIL-STD-2525 Placemark with an appropriate level of detail for the current distance from the camera.
 * Shared low-fidelity images are used when far away from the camera, whereas unique high-fidelity images are used
 * when near the camera. The high-fidelity images that are no longer in view are automatically freed, if necessary,
 * to release memory resources.
 *
 * @param position The placemark's geographic position
 * @param symbolCode A 15-character alphanumeric identifier that provides the information necessary to display or
 * transmit a tactical symbol between MIL-STD-2525 compliant systems.
 * @param symbolModifiers An optional collection of unit or tactical graphic modifiers.
 * @param symbolAttributes An optional collection of rendering attributes.
 * @param lodSelector Level of detail selector
 */
abstract class AbstractMilStd2525Placemark(
    val symbolCode: String, position: Position,
    symbolModifiers: Map<String, String>?, symbolAttributes: Map<String, String>?, lodSelector: LevelOfDetailSelector
) : Placemark(position, name = symbolCode) {

    protected companion object {
        const val MINIMUM_IMAGE_SCALE = 0.5

        fun getSymbolCacheKey(
            symbolCode: String, symbolModifiers: Map<String, String>?, symbolAttributes: Map<String, String>?
        ): Int {
            var result = symbolCode.hashCode()
            result = 31 * result + (symbolModifiers?.hashCode() ?: 0)
            result = 31 * result + (symbolAttributes?.hashCode() ?: 0)
            return result
        }
    }

    var symbolModifiers = symbolModifiers
        set(value) {
            field = value
            invalidate()
        }
    var symbolAttributes = symbolAttributes
        set(value) {
            field = value
            invalidate()
        }
    var isModifiersVisible = true
        set(value) {
            // Do not invalidate state if nothing changed
            if (field != value) {
                field = value
                invalidate()
            }
        }

    init {
        levelOfDetailSelector = lodSelector
        isLeaderPickingEnabled = true
        isBillboardingEnabled = true
        isEyeDistanceScaling = true
    }

    protected open fun invalidate() {
        (levelOfDetailSelector as? MilStd2525LevelOfDetailSelector)?.invalidate()
    }
}