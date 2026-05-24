package earth.worldwind.layer.mvt

import earth.worldwind.render.Color

/**
 * Convenience constructors for the most common expression shape — linear interpolation over
 * `(zoom, value)` stops, or a constant. Each factory returns an [MvtExpression] that fits
 * directly into a paint-property slot.
 *
 * For arbitrary Mapbox expressions (case/match/step/get/arithmetic), construct
 * [MvtExpression] subclasses directly or parse a Mapbox JSON expression via
 * [MvtExpressionParser].
 */
object MvtZoomInterp {
    /** Linear `(zoom, Float)` ramp. Clamped at both ends — no extrapolation. */
    fun floats(vararg pairs: Pair<Int, Float>): MvtExpression<Float> = MvtExpression.Interpolate(
        interpolation = MvtExpression.Interpolation.Linear,
        input = MvtExpression.Zoom,
        stops = pairs.map { (z, v) -> z.toDouble() to MvtExpression.Literal(v) },
        lerp = MvtExpression.Interpolators.FLOAT,
    )

    /** Linear `(zoom, Color)` ramp. Per-channel blend including alpha. */
    fun colors(vararg pairs: Pair<Int, Color>): MvtExpression<Color> = MvtExpression.Interpolate(
        interpolation = MvtExpression.Interpolation.Linear,
        input = MvtExpression.Zoom,
        stops = pairs.map { (z, v) -> z.toDouble() to MvtExpression.Literal(v) },
        lerp = MvtExpression.Interpolators.COLOR,
    )

    fun constant(value: Float): MvtExpression<Float> = MvtExpression.Literal(value)
    fun constant(value: Color): MvtExpression<Color> = MvtExpression.Literal(value)
}
