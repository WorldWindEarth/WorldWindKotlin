package earth.worldwind.shape.milstd2525

import earth.worldwind.geom.Location
import earth.worldwind.shape.TextAttributes

/**
 * Target-agnostic facade over the per-target mil-sym-ts (`C5Ren`) + canvg bindings, expressed in
 * plain Kotlin / DOM types only, so the WorldWind-facing point-symbol code ([MilStd2525],
 * [MilStd2525Placemark]) can live in `webMain` while the generated `@JsModule` externs
 * (C5Ren / C5RenTypes / Canvg) stay per-target behind this seam.
 *
 * The signatures deliberately leak NO generated types: `Map<String, String>` (not the per-target
 * `JsMap`), `Double` (not the `int`/`number` aliases that resolve to `Number` on js vs `Double` on
 * wasm), and `HTMLCanvasElement` (a kotlinx-browser type present on both targets). Each per-target
 * adapter ([createMilStd2525Renderer]) absorbs the `Number`↔`Double` coercion, the JS-Map construction,
 * and the `@JsModule`/`@JsNonModule` difference internally.
 *
 * Multipoint tactical graphics ([MilStd2525TacticalGraphic]) are NOT routed through here — they
 * consume the `MilStdSymbol` / multipoint renderer API directly and remain per-target.
 */
interface MilStd2525Renderer {
    /**
     * Render a point symbol: the rasterized [canvas][MilStd2525Symbol.canvas] plus the symbol-center and
     * symbol-bounds geometry the placemark needs for its image/label offsets. Null if the renderer
     * produced nothing. Both maps may be empty.
     */
    fun renderSymbol(
        symbolCode: String, modifiers: Map<String, String>, attributes: Map<String, String>,
    ): MilStd2525Symbol?

    /**
     * Render a multipoint tactical graphic to a list of target-agnostic [MilStd2525Shape]s. The adapter
     * builds & configures the `MilStdSymbol` from [controlPoints], runs the multipoint renderer at the
     * given [scale]/[densityFactor] anchored at the upper-left ([pointULLon], [pointULLat]), and
     * converts every resulting shape (pixels→geo, colours, images) into plain Kotlin.
     */
    fun renderTacticalGraphic(
        symbolID: String,
        controlPoints: List<Location>,
        pointULLon: Double, pointULLat: Double,
        scale: Double, densityFactor: Float,
        modifiers: Map<String, String>, attributes: Map<String, String>,
    ): List<MilStd2525Shape>

    /** Apply the renderer's current label-font settings (family / size / weight) to [textAttributes]. */
    fun applyTextAttributes(textAttributes: TextAttributes)

    /** Whether [symbolId] denotes a tactical graphic (vs a point symbol). */
    fun isTacticalGraphic(symbolId: String): Boolean

    /** MIL-STD attribute overrides that render [symbolId] unfilled (affiliation line colour), or empty. */
    fun getUnfilledAttributes(symbolId: String): Map<String, String>
}

/** Per-target factory for the [MilStd2525Renderer] (js / wasmJs adapters over `C5Ren` + canvg). */
internal expect fun createMilStd2525Renderer(): MilStd2525Renderer
