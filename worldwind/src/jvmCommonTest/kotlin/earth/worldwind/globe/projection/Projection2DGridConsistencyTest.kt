package earth.worldwind.globe.projection

import earth.worldwind.geom.Angle.Companion.radians
import earth.worldwind.geom.Ellipsoid
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Sector.Companion.fromDegrees
import earth.worldwind.geom.Vec3
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Characterization test for the 2D projections' bulk grid/border generators: every point the grid
 * (and border) emits must equal the single-point [GeographicProjection.geographicToCartesian] for
 * the same latitude/longitude. That single-point projection is the reference the bulk generators
 * exist to vectorize, so this pins the bulk output against it — catching any transcription error
 * when the shared grid/border loop is refactored. Sectors stay inside each projection's limits so
 * the grid's clamping matches the (also-clamping, for those projections) single-point path.
 */
class Projection2DGridConsistencyTest {
    private val ellipsoid = Ellipsoid.WGS84

    // (projection, sector) pairs kept inside each projection's valid band.
    private val cases: List<Pair<GeographicProjection, Sector>> = listOf(
        EquirectangularProjection() to fromDegrees(-40.0, -40.0, 80.0, 80.0),
        SinusoidalProjection() to fromDegrees(-40.0, -40.0, 80.0, 80.0),
        ModifiedSinusoidalProjection() to fromDegrees(-40.0, -40.0, 80.0, 80.0),
        MercatorProjection() to fromDegrees(-40.0, -40.0, 80.0, 80.0),
        GnomonicProjection(isNorth = true) to fromDegrees(40.0, -40.0, 40.0, 80.0),
        UpsProjection(isNorth = true) to fromDegrees(20.0, -40.0, 60.0, 80.0),
        PolarEquidistantProjection(isNorth = true) to fromDegrees(40.0, -40.0, 40.0, 80.0),
        TransverseMercatorProjection() to fromDegrees(-40.0, -20.0, 80.0, 40.0),
    )

    private val numLat = 5
    private val numLon = 6
    private val tolerance = 2.0 // metres; Float rounding at planetary scale is ~1 m

    private fun expected(proj: GeographicProjection, latRad: Double, lonRad: Double): Vec3 =
        proj.geographicToCartesian(ellipsoid, latRad.radians, lonRad.radians, 0.0, 0.0, Vec3())

    @Test
    fun grid_matches_single_point_projection() {
        for ((proj, sector) in cases) {
            val grid = proj.geographicToCartesianGrid(
                ellipsoid, sector, numLat, numLon, null, 1.0, null, 0.0,
                FloatArray(numLat * numLon * 3), 0, 0,
            )
            val minLat = sector.minLatitude.inRadians
            val maxLat = sector.maxLatitude.inRadians
            val minLon = sector.minLongitude.inRadians
            val maxLon = sector.maxLongitude.inRadians
            val deltaLat = (maxLat - minLat) / if (numLat > 1) numLat - 1 else 1
            val deltaLon = (maxLon - minLon) / if (numLon > 1) numLon - 1 else 1
            // Replicate the grid's exact lat/lon accumulation so Float rounding lines up.
            var lat = minLat
            for (i in 0 until numLat) {
                if (i == numLat - 1) lat = maxLat
                var lon = minLon
                for (j in 0 until numLon) {
                    if (j == numLon - 1) lon = maxLon
                    val exp = expected(proj, lat, lon)
                    val base = (i * numLon + j) * 3
                    assertClose(proj, "x[$i,$j]", grid[base].toDouble(), exp.x)
                    assertClose(proj, "y[$i,$j]", grid[base + 1].toDouble(), exp.y)
                    assertClose(proj, "z[$i,$j]", grid[base + 2].toDouble(), exp.z)
                    lon += deltaLon
                }
                lat += deltaLat
            }
        }
    }

    @Test
    fun border_corners_match_single_point_projection() {
        for ((proj, sector) in cases) {
            val border = proj.geographicToCartesianBorder(
                ellipsoid, sector, numLat, numLon, 0f, null, 0.0, FloatArray(numLat * numLon * 3),
            )
            // The border's first emitted point is (minLat, minLon).
            val exp = expected(proj, sector.minLatitude.inRadians, sector.minLongitude.inRadians)
            assertClose(proj, "border x[0]", border[0].toDouble(), exp.x)
            assertClose(proj, "border y[0]", border[1].toDouble(), exp.y)
        }
    }

    private fun assertClose(proj: GeographicProjection, what: String, actual: Double, expected: Double) {
        assertTrue(
            kotlin.math.abs(actual - expected) <= tolerance,
            "${proj.displayName} $what: expected $expected, was $actual",
        )
    }
}
