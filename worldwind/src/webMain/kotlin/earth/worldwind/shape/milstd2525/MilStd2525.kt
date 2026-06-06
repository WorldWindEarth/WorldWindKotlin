package earth.worldwind.shape.milstd2525

import earth.worldwind.geom.Location
import earth.worldwind.shape.TextAttributes

/**
 * Generates MIL-STD-2525 point symbols using the MIL-STD-2525 Symbol Rendering Library, reached
 * through the per-target [MilStd2525Renderer] seam (so this class lives in `webMain` while the generated
 * mil-sym-ts / canvg bindings stay per-target).
 * @see <a href="https://github.com/missioncommand/mil-sym-js">https://github.com/missioncommand/mil-sym-js</a>
 */
actual object MilStd2525 {
    private val renderer = createMilStd2525Renderer()

    /** Render [symbolCode] to a canvas + offset geometry ([MilStd2525Symbol]), or null if nothing was produced. */
    fun renderSymbol(
        symbolCode: String, modifiers: Map<String, String>, attributes: Map<String, String>
    ): MilStd2525Symbol? = renderer.renderSymbol(symbolCode, modifiers, attributes)

    /** Render a multipoint tactical graphic to a list of target-agnostic [MilStd2525Shape]s. */
    fun renderTacticalGraphic(
        symbolID: String, controlPoints: List<Location>, pointULLon: Double, pointULLat: Double,
        scale: Double, densityFactor: Float, modifiers: Map<String, String>, attributes: Map<String, String>
    ) = renderer.renderTacticalGraphic(
        symbolID, controlPoints, pointULLon, pointULLat, scale, densityFactor, modifiers, attributes
    )

    /** Apply the renderer's current label-font settings to [textAttributes]. */
    fun applyTextAttributes(textAttributes: TextAttributes) = renderer.applyTextAttributes(textAttributes)

    actual fun isTacticalGraphic(symbolID: String) = renderer.isTacticalGraphic(symbolID)

    actual fun getUnfilledAttributes(symbolID: String) = renderer.getUnfilledAttributes(symbolID)
}
