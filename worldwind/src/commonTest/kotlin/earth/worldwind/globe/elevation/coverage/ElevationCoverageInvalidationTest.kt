package earth.worldwind.globe.elevation.coverage

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Sector
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ElevationCoverageInvalidationTest {

    /** Minimal concrete coverage exposing the protected update hooks. */
    private class TestCoverage : AbstractElevationCoverage() {
        override fun clear() {}
        override fun doGetElevation(latitude: Angle, longitude: Angle, retrieve: Boolean) = 0f
        override fun doGetElevationGrid(gridSector: Sector, gridWidth: Int, gridHeight: Int, result: FloatArray) {}
        override fun doGetElevationLimits(sector: Sector, result: FloatArray) {}
        fun update() = updateTimestamp()
        fun update(sector: Sector) = updateTimestamp(sector)
    }

    private fun sector(minLat: Double, minLon: Double, delta: Double) =
        Sector.fromDegrees(minLat, minLon, delta, delta)

    @Test
    fun unknownPastIsAlwaysChanged() {
        val coverage = TestCoverage()
        assertTrue(coverage.isChangedSince(0L, sector(10.0, 10.0, 1.0)), "time before construction must report changed")
    }

    @Test
    fun noUpdatesSinceTimestampIsUnchanged() {
        val coverage = TestCoverage()
        assertFalse(coverage.isChangedSince(coverage.timestamp, sector(10.0, 10.0, 1.0)))
    }

    @Test
    fun scopedUpdateInvalidatesOnlyIntersectingSectors() {
        val coverage = TestCoverage()
        val before = coverage.timestamp
        coverage.update(sector(10.0, 10.0, 1.0))
        assertTrue(coverage.isChangedSince(before, sector(10.5, 10.5, 1.0)), "intersecting sector must be invalidated")
        assertFalse(coverage.isChangedSince(before, sector(50.0, 50.0, 1.0)), "disjoint sector must keep its cache")
        assertFalse(coverage.isChangedSince(coverage.timestamp, sector(10.5, 10.5, 1.0)), "up-to-date consumer stays valid")
    }

    @Test
    fun unscopedUpdateInvalidatesEverything() {
        val coverage = TestCoverage()
        val before = coverage.timestamp
        coverage.update()
        assertTrue(coverage.isChangedSince(before, sector(50.0, 50.0, 1.0)))
    }

    @Test
    fun timestampsAreStrictlyIncreasing() {
        val coverage = TestCoverage()
        var previous = coverage.timestamp
        repeat(10) {
            coverage.update(sector(0.0, 0.0, 1.0))
            assertTrue(coverage.timestamp > previous, "same-millisecond updates must still advance the timestamp")
            previous = coverage.timestamp
        }
    }

    @Test
    fun disableEnableInvalidatesEverything() {
        val coverage = TestCoverage()
        val before = coverage.timestamp
        coverage.isEnabled = false
        assertTrue(coverage.isChangedSince(before, sector(50.0, 50.0, 1.0)))
    }

    @Test
    fun logOverflowFallsBackToConservativeChanged() {
        val coverage = TestCoverage()
        val before = coverage.timestamp
        // Push far past the log capacity with updates disjoint from the queried sector
        repeat(300) { coverage.update(sector(10.0, 10.0, 1.0)) }
        assertTrue(
            coverage.isChangedSince(before, sector(50.0, 50.0, 1.0)),
            "a consumer older than the log window must recompute even for disjoint sectors"
        )
        assertFalse(
            coverage.isChangedSince(coverage.timestamp, sector(50.0, 50.0, 1.0)),
            "an up-to-date consumer must stay valid after overflow"
        )
    }

    @Test
    fun recentHistoryInsideLogWindowStaysScopedAfterOverflow() {
        val coverage = TestCoverage()
        repeat(300) { coverage.update(sector(10.0, 10.0, 1.0)) }
        val mark = coverage.timestamp
        repeat(5) { coverage.update(sector(10.0, 10.0, 1.0)) }
        assertTrue(coverage.isChangedSince(mark, sector(10.5, 10.5, 1.0)))
        assertFalse(coverage.isChangedSince(mark, sector(50.0, 50.0, 1.0)))
    }
}
