package earth.worldwind.layer

import earth.worldwind.layer.source.CachedFeatureRow
import earth.worldwind.layer.source.CachedGeometry
import earth.worldwind.shape.Polygon
import kotlin.test.Test
import kotlin.test.assertEquals

class FeatureRendererHoleTest {

    /** Closed square ring of side [side]° centered at ([cx],[cy]) as a CachedGeometry.LineString. */
    private fun square(cx: Double, cy: Double, side: Double): CachedGeometry.LineString {
        val h = side / 2
        return CachedGeometry.LineString.of(listOf(
            CachedGeometry.Point(cx - h, cy - h), CachedGeometry.Point(cx + h, cy - h),
            CachedGeometry.Point(cx + h, cy + h), CachedGeometry.Point(cx - h, cy + h),
            CachedGeometry.Point(cx - h, cy - h),
        ))
    }

    @Test
    fun visibleIslandHolesSurviveSimplification() {
        // River outer 1°×1°; island holes from clearly-visible (0.05° ≈ 25 texels) down to
        // sub-pixel (0.0008°). Tolerance 0.002° ≈ one tile texel at the demo zoom.
        val outer = square(-73.5, 45.5, 1.0)
        val holes = listOf(
            square(-73.7, 45.7, 0.05),   // big island — must stay a hole
            square(-73.6, 45.4, 0.01),   // medium — must stay a hole
            square(-73.4, 45.6, 0.004),  // ~2 texels — should stay
            square(-73.3, 45.3, 0.0008), // sub-pixel — ok to drop
        )
        val poly = CachedGeometry.Polygon(listOf(outer) + holes)
        val renderer = FeatureRenderer(simplifyToleranceDeg = 0.002)
        val out = renderer.build(CachedFeatureRow(poly, null))
        val polygon = out.filterIsInstance<Polygon>().single()
        // Every island stays a HOLE — none collapses into solid fill. A ring smaller than the
        // tolerance survives as its bbox quad (the per-tile area gate drops only sub-pixel ones
        // later), so boundaryCount = 1 outer + 4 island holes.
        assertEquals(5, polygon.boundaryCount,
            "all islands must remain holes (the bug filled them); got ${polygon.boundaryCount}")
    }
}
