package earth.worldwind.shape

import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.fromDegrees
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Vec3
import earth.worldwind.globe.Globe
import earth.worldwind.globe.elevation.coverage.AbstractElevationCoverage
import earth.worldwind.globe.projection.MercatorProjection
import earth.worldwind.globe.terrain.BasicTerrain
import earth.worldwind.render.RenderContext
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PointMemoTest {
    companion object {
        private const val TOLERANCE = 1.0e-6 // Regrouped altitude terms agree to floating point rounding
    }

    private lateinit var rc: RenderContext
    private lateinit var globe: Globe

    @BeforeTest
    fun setUp() {
        globe = Globe()
        rc = RenderContext()
        rc.globe = globe
        rc.globeState = globe.state
        // Empty terrain: surfacePoint always misses, so clamped modes exercise the memoized fallback
        rc.terrain = BasicTerrain(emptyList(), Sector(), null)
    }

    /**
     * The memo relies on every projection being linear in altitude:
     * cartesian(lat, lon, h) == cartesian(lat, lon, 0) + normal * h.
     */
    @Test
    fun testConversionIsLinearInAltitude() {
        for (lat in intArrayOf(-75, -30, 0, 45, 80)) for (lon in intArrayOf(-150, -60, 0, 90, 179)) {
            val latitude = fromDegrees(lat.toDouble())
            val longitude = fromDegrees(lon.toDouble())
            val base = globe.geographicToCartesian(latitude, longitude, 0.0, Vec3())
            val normal = globe.geographicToCartesianNormal(latitude, longitude, Vec3())
            for (altitude in doubleArrayOf(-500.0, 150.0, 10000.0)) {
                val expected = globe.geographicToCartesian(latitude, longitude, altitude, Vec3())
                assertEquals(expected.x, base.x + normal.x * altitude, TOLERANCE, "x $lat $lon $altitude")
                assertEquals(expected.y, base.y + normal.y * altitude, TOLERANCE, "y $lat $lon $altitude")
                assertEquals(expected.z, base.z + normal.z * altitude, TOLERANCE, "z $lat $lon $altitude")
            }
        }
    }

    /**
     * Pins the memo's altitude mode handling to [RenderContext.geographicToCartesian]: a semantic
     * change in either one must fail this comparison.
     */
    @Test
    fun testMemoMatchesRenderContextInAllAltitudeModes() {
        val memo = PointMemo()
        val latitude = fromDegrees(46.5)
        val longitude = fromDegrees(30.7)
        for (mode in AltitudeMode.entries) {
            val direct = rc.geographicToCartesian(latitude, longitude, 250.0, mode, Vec3())
            // First call computes and stores, second call replays the memo
            repeat(2) {
                val memoized = memo.geographicToCartesian(rc, latitude, longitude, 250.0, mode, Vec3())
                assertEquals(direct.x, memoized.x, TOLERANCE, "$mode x")
                assertEquals(direct.y, memoized.y, TOLERANCE, "$mode y")
                assertEquals(direct.z, memoized.z, TOLERANCE, "$mode z")
            }
        }
    }

    @Test
    fun testMemoFollowsPositionChange() {
        val memo = PointMemo()
        memo.geographicToCartesian(rc, fromDegrees(10.0), fromDegrees(20.0), 0.0, AltitudeMode.ABSOLUTE, Vec3())

        val latitude = fromDegrees(11.0)
        val longitude = fromDegrees(21.0)
        val direct = rc.geographicToCartesian(latitude, longitude, 42.0, AltitudeMode.ABSOLUTE, Vec3())
        val memoized = memo.geographicToCartesian(rc, latitude, longitude, 42.0, AltitudeMode.ABSOLUTE, Vec3())
        assertEquals(direct.x, memoized.x, TOLERANCE, "x")
        assertEquals(direct.y, memoized.y, TOLERANCE, "y")
        assertEquals(direct.z, memoized.z, TOLERANCE, "z")
    }

    /** Minimal coverage with a controllable height, exposing the protected update hooks. */
    private class TestCoverage : AbstractElevationCoverage() {
        var height = 0f
        override fun clear() {}
        override fun doGetElevation(latitude: Angle, longitude: Angle, retrieve: Boolean) = height
        override fun doGetElevationGrid(gridSector: Sector, gridWidth: Int, gridHeight: Int, result: FloatArray) {}
        override fun doGetElevationLimits(sector: Sector, result: FloatArray) {}
        fun update(sector: Sector) = updateTimestamp(sector)
    }

    @Test
    fun testCachedElevationIgnoresUnrelatedUpdatesAndFollowsIntersectingOnes() {
        val coverage = TestCoverage()
        globe.elevationModel.addCoverage(coverage)
        val memo = PointMemo()
        val latitude = fromDegrees(50.0)
        val longitude = fromDegrees(30.0)

        coverage.height = 100f
        rc.elevationModelTimestamp = globe.elevationModel.timestamp
        val initial = memo.geographicToCartesian(rc, latitude, longitude, 0.0, AltitudeMode.CLAMP_TO_GROUND, Vec3())

        // An elevation update far from the location must keep the cached height
        coverage.height = 200f
        coverage.update(Sector.fromDegrees(-10.0, -10.0, 1.0, 1.0))
        rc.elevationModelTimestamp = globe.elevationModel.timestamp
        val afterFarUpdate = memo.geographicToCartesian(rc, latitude, longitude, 0.0, AltitudeMode.CLAMP_TO_GROUND, Vec3())
        assertEquals(initial.x, afterFarUpdate.x, TOLERANCE, "unrelated update x")
        assertEquals(initial.y, afterFarUpdate.y, TOLERANCE, "unrelated update y")
        assertEquals(initial.z, afterFarUpdate.z, TOLERANCE, "unrelated update z")

        // An update intersecting the location must refresh the height
        coverage.update(Sector.fromDegrees(49.5, 29.5, 1.0, 1.0))
        rc.elevationModelTimestamp = globe.elevationModel.timestamp
        val afterNearUpdate = memo.geographicToCartesian(rc, latitude, longitude, 0.0, AltitudeMode.CLAMP_TO_GROUND, Vec3())
        val expected = rc.geographicToCartesian(latitude, longitude, 0.0, AltitudeMode.CLAMP_TO_GROUND, Vec3())
        assertEquals(expected.x, afterNearUpdate.x, TOLERANCE, "intersecting update x")
        assertEquals(expected.y, afterNearUpdate.y, TOLERANCE, "intersecting update y")
        assertEquals(expected.z, afterNearUpdate.z, TOLERANCE, "intersecting update z")
    }

    /**
     * 2D continuous globes render each frame at up to three globe offsets; every offset pass must
     * get its own base point, and replaying the offsets must not evict each other's slots.
     */
    @Test
    fun testMemoTracksGlobeOffsetsIn2D() {
        val globe2d = Globe(projection = MercatorProjection())
        rc.globe = globe2d
        rc.globeState = globe2d.state
        val memo = PointMemo()
        val latitude = fromDegrees(20.0)
        val longitude = fromDegrees(170.0)
        // Two sweeps: the first computes and stores each offset's slot, the second replays them
        repeat(2) { sweep ->
            for (offset in Globe.Offset.entries) {
                globe2d.offset = offset
                val direct = rc.geographicToCartesian(latitude, longitude, 100.0, AltitudeMode.ABSOLUTE, Vec3())
                val memoized = memo.geographicToCartesian(rc, latitude, longitude, 100.0, AltitudeMode.ABSOLUTE, Vec3())
                assertEquals(direct.x, memoized.x, TOLERANCE, "sweep $sweep $offset x")
                assertEquals(direct.y, memoized.y, TOLERANCE, "sweep $sweep $offset y")
                assertEquals(direct.z, memoized.z, TOLERANCE, "sweep $sweep $offset z")
            }
        }
    }

    @Test
    fun testMemoizedNormalMatchesGlobeNormal() {
        val memo = PointMemo()
        val latitude = fromDegrees(-33.9)
        val longitude = fromDegrees(18.4)
        val direct = globe.geographicToCartesianNormal(latitude, longitude, Vec3())
        repeat(2) {
            val memoized = memo.geographicToCartesianNormal(rc, latitude, longitude, Vec3())
            assertEquals(direct.x, memoized.x, TOLERANCE, "x")
            assertEquals(direct.y, memoized.y, TOLERANCE, "y")
            assertEquals(direct.z, memoized.z, TOLERANCE, "z")
        }
    }
}
