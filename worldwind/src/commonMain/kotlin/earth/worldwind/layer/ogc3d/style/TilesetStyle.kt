package earth.worldwind.layer.ogc3d.style

import earth.worldwind.render.Color
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * A parsed 3D Tiles 1.0 declarative style document. Carries the raw expressions for
 * `show`, `color`, and `meta` — actual evaluation is delegated to a pluggable
 * [TilesetStyleEvaluator] so the engine doesn't carry a mini-DSL parser in core.
 *
 * Spec reference: <https://github.com/CesiumGS/3d-tiles/tree/main/specification/Styling>.
 *
 * Typical pipeline:
 *  - App loads a style JSON document and parses it with [parse].
 *  - App sets [earth.worldwind.layer.ogc3d.Ogc3dTilesLayer.style] to the result.
 *  - The layer routes each rendered tile's batch-table feature properties through the
 *    registered [TilesetStyleEvaluator]; the evaluator returns a per-feature color +
 *    visibility, which the layer applies via per-batch-id uniform or vertex attribute.
 *
 * When no evaluator is registered (the default), every style is a no-op: features render
 * with their material's `baseColorFactor` and are all visible. The integration surface is
 * structurally complete (parse + plumb), so wiring a real evaluator later is a drop-in.
 */
@Serializable
class TilesetStyle(
    /** Boolean expression evaluated against each feature; false hides the feature. Null
     *  means "no show expression" (everything visible). */
    val show: String? = null,
    /** Color expression evaluated against each feature; result overrides the material
     *  base colour. Null means "no color expression" (material colour used as-is). */
    val color: String? = null,
    /** Arbitrary metadata key→expression pairs the app can evaluate to attach derived
     *  values to features (per spec). Engine ignores these; apps use them for picking
     *  callbacks, tooltips, analytics. */
    val meta: Map<String, String> = emptyMap(),
    /** Optional `defines` block from the spec — symbolic shorthand expressions the show /
     *  color expressions may reference via `${name}`. Engine carries these for the
     *  evaluator; doesn't expand them itself. */
    val defines: Map<String, String> = emptyMap(),
) {
    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /**
         * Parse a 3D Tiles 1.0 styling-language JSON document. Lenient — unknown fields
         * (any spec extension the engine doesn't model, app-specific extras) are dropped
         * without error.
         */
        fun parse(body: String): TilesetStyle = json.decodeFromString(serializer(), body)
    }
}

/**
 * Pluggable evaluator for the 3D Tiles styling language. Apps register a concrete
 * implementation via [earth.worldwind.layer.ogc3d.Ogc3dDecoderRegistry.tilesetStyleEvaluator]
 * once at startup. When null (default), every styled tile renders with material defaults.
 *
 * Implementations are expected to be **stateless per feature** (state across feature
 * evaluations within one style document is fine — e.g. caching parsed expression ASTs).
 *
 * The spec's expression syntax is non-trivial (literals, `${name}` property lookups, math
 * operators, function calls like `color()`, conditional `(test) ? a : b`, regex
 * predicates). A realistic implementation parses each expression into an AST once when
 * the style is bound to the layer, then evaluates the AST against feature properties on
 * every draw — far cheaper than re-parsing per feature.
 */
interface TilesetStyleEvaluator {

    /**
     * Bind this evaluator to a parsed style. The implementation typically parses each
     * expression in [style] into an AST + caches them keyed by expression text, so
     * subsequent [evaluateShow] / [evaluateColor] calls are O(features × ast-eval).
     *
     * The returned [BoundStyle] is opaque to the engine — apps hold it as long as the
     * style is in use and pass it back into the eval calls.
     */
    fun bind(style: TilesetStyle): BoundStyle

    /**
     * Evaluate the bound style's `show` expression for one feature. Returns true (visible)
     * when the expression is absent or evaluates to truthy; false hides the feature.
     */
    fun evaluateShow(bound: BoundStyle, featureProperties: Map<String, Any?>): Boolean

    /**
     * Evaluate the bound style's `color` expression for one feature. Returns null when
     * the expression is absent or evaluates to a value the engine can't apply (e.g.
     * non-color string), letting the caller fall back to the material's base colour.
     */
    fun evaluateColor(bound: BoundStyle, featureProperties: Map<String, Any?>): Color?

    /**
     * Evaluate one of the bound style's `meta` expressions by key. Returns null when the
     * key is absent or the expression evaluates to JSON null.
     */
    fun evaluateMeta(bound: BoundStyle, key: String, featureProperties: Map<String, Any?>): JsonElement?
}

/**
 * Marker for an evaluator's per-style cached state. Opaque to engine code; apps treat it
 * as a handle they pass back into the evaluate calls. Typical contents inside an
 * implementation: parsed-expression ASTs keyed by expression text.
 */
interface BoundStyle
