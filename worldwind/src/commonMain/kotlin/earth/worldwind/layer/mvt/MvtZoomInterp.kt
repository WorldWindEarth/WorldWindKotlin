package earth.worldwind.layer.mvt

import earth.worldwind.render.Color

/**
 * Linear interpolation over `(zoom, value)` stops. Mirrors Mapbox GL's
 * `["interpolate", ["linear"], ["zoom"], z1, v1, z2, v2, …]` for [Float] (line widths,
 * opacity) and [Color] (per-zoom palette shifts).
 *
 * Stops are sorted by zoom ascending; values below the first stop's zoom return the first
 * value, values above the last stop's zoom return the last (clamped, no extrapolation).
 */
sealed class MvtZoomInterp<T> {

    /** Sample the interpolated value at [zoom]. */
    abstract fun valueAt(zoom: Int): T

    /** A single stop in a zoom-interpolation table. */
    class Stop<T>(val zoom: Int, val value: T)

    /**
     * Float interpolation — used for line widths, opacities, blur radii.
     *
     * @param stops sorted ascending by zoom; not validated at runtime (assumed correct at
     *              style-construction time).
     */
    class Floats(private val stops: List<Stop<Float>>) : MvtZoomInterp<Float>() {
        init { require(stops.isNotEmpty()) { "MvtZoomInterp.Floats needs at least one stop" } }

        override fun valueAt(zoom: Int): Float {
            if (zoom <= stops.first().zoom) return stops.first().value
            if (zoom >= stops.last().zoom) return stops.last().value
            // Linear search — stop lists are O(2..6) entries; binary search isn't worth it.
            for (i in 1 until stops.size) {
                val hi = stops[i]
                if (zoom <= hi.zoom) {
                    val lo = stops[i - 1]
                    val t = (zoom - lo.zoom).toFloat() / (hi.zoom - lo.zoom).toFloat()
                    return lo.value + (hi.value - lo.value) * t
                }
            }
            return stops.last().value
        }
    }

    /**
     * Color interpolation — per-channel linear blend. Alpha is interpolated alongside RGB
     * (matching Mapbox semantics: a stop with `rgba(0,0,0,0)` fades in/out smoothly).
     */
    class Colors(private val stops: List<Stop<Color>>) : MvtZoomInterp<Color>() {
        init { require(stops.isNotEmpty()) { "MvtZoomInterp.Colors needs at least one stop" } }

        override fun valueAt(zoom: Int): Color {
            if (zoom <= stops.first().zoom) return stops.first().value
            if (zoom >= stops.last().zoom) return stops.last().value
            for (i in 1 until stops.size) {
                val hi = stops[i]
                if (zoom <= hi.zoom) {
                    val lo = stops[i - 1]
                    val t = (zoom - lo.zoom).toFloat() / (hi.zoom - lo.zoom).toFloat()
                    val a = lo.value
                    val b = hi.value
                    return Color(
                        a.red + (b.red - a.red) * t,
                        a.green + (b.green - a.green) * t,
                        a.blue + (b.blue - a.blue) * t,
                        a.alpha + (b.alpha - a.alpha) * t,
                    )
                }
            }
            return stops.last().value
        }
    }

    companion object {
        /** Build a Float ramp from vararg `(zoom, value)` pairs. */
        fun floats(vararg pairs: Pair<Int, Float>): Floats =
            Floats(pairs.map { Stop(it.first, it.second) })

        /** Build a Color ramp from vararg `(zoom, value)` pairs. */
        fun colors(vararg pairs: Pair<Int, Color>): Colors =
            Colors(pairs.map { Stop(it.first, it.second) })

        /** Single-value "interpolation" (constant). Cheap wrapper for paint properties that
         *  share the same code path as zoom-varying ones. */
        fun constant(value: Float): Floats = Floats(listOf(Stop(0, value)))
        fun constant(value: Color): Colors = Colors(listOf(Stop(0, value)))
    }
}
