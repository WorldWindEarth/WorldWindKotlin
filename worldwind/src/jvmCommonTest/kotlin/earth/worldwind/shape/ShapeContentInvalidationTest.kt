package earth.worldwind.shape

import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.fromDegrees
import earth.worldwind.geom.Location
import earth.worldwind.geom.Position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Pins the two-tier geometry invalidation contract:
 * - value-typed configuration setters ([AltitudeMode], radii, flags…) compare-and-reset, so
 *   re-assigning the current value must NOT regenerate geometry;
 * - mutable geometry content (positions, boundaries, centers, corners) is fingerprinted by
 *   [AbstractShape.checkContentState] on the render path, so re-assigning identical content must
 *   NOT reset, while any real change — including in-place [Position] mutation with no setter
 *   call at all — MUST reset.
 */
class ShapeContentInvalidationTest {

    private class TestPath(positions: List<Position>) : Path(positions) {
        var resetCount = 0
        override fun reset() { resetCount++; super.reset() }
        fun checkContent() = checkContentState()
    }

    private class TestPolygon(positions: List<Position>) : Polygon(positions) {
        var resetCount = 0
        override fun reset() { resetCount++; super.reset() }
        fun checkContent() = checkContentState()
    }

    private class TestEllipse(center: Position, major: Double, minor: Double) : Ellipse(center, major, minor) {
        var resetCount = 0
        override fun reset() { resetCount++; super.reset() }
        fun checkContent() = checkContentState()
    }

    private class TestEllipsoid(center: Position) : Ellipsoid(center, 10.0, 10.0, 10.0) {
        var resetCount = 0
        override fun reset() { resetCount++; super.reset() }
        fun checkContent() = checkContentState()
    }

    private class TestMesh(positions: Array<Position>, indices: IntArray) : TriangleMesh(positions, indices) {
        var resetCount = 0
        override fun reset() { resetCount++; super.reset() }
        fun checkContent() = checkContentState()
    }

    private class TestMediaSurface : ProjectedMediaSurface(
        Location(fromDegrees(0.0), fromDegrees(0.0)), Location(fromDegrees(0.0), fromDegrees(1.0)),
        Location(fromDegrees(1.0), fromDegrees(1.0)), Location(fromDegrees(1.0), fromDegrees(0.0)),
    ) {
        var resetCount = 0
        override fun reset() { resetCount++; super.reset() }
        fun checkContent() = checkContentState()
    }

    private fun positions(vararg altitudes: Double) = altitudes.mapIndexed { i, alt ->
        Position(fromDegrees(10.0 + i), fromDegrees(20.0 + i), alt)
    }

    // ---- Value-typed setters: compare-and-reset ----

    @Test
    fun valueSettersSkipResetOnSameValue() {
        val path = TestPath(positions(0.0, 100.0))
        path.altitudeMode = AltitudeMode.ABSOLUTE // current value
        path.pathType = path.pathType
        path.isExtrude = false
        path.isFollowTerrain = false
        path.baseAltitude = 0.0
        path.maximumNumEdgeIntervals = path.maximumNumEdgeIntervals
        path.polarThrottle = path.polarThrottle
        assertEquals(0, path.resetCount, "re-assigning current values must not reset")

        path.altitudeMode = AltitudeMode.CLAMP_TO_GROUND
        assertEquals(1, path.resetCount, "a real change must reset")
    }

    @Test
    fun ellipseValueSettersSkipResetOnSameValue() {
        val ellipse = TestEllipse(Position(fromDegrees(1.0), fromDegrees(2.0), 0.0), 100.0, 50.0)
        ellipse.majorRadius = 100.0
        ellipse.minorRadius = 50.0
        ellipse.heading = ellipse.heading
        ellipse.maximumIntervals = ellipse.maximumIntervals
        assertEquals(0, ellipse.resetCount)
        ellipse.majorRadius = 200.0
        assertEquals(1, ellipse.resetCount)
    }

    @Test
    fun ellipsoidValueSettersSkipResetOnSameValue() {
        val ellipsoid = TestEllipsoid(Position(fromDegrees(1.0), fromDegrees(2.0), 0.0))
        ellipsoid.xRadius = 10.0
        ellipsoid.slices = ellipsoid.slices
        ellipsoid.outlineCircles = ellipsoid.outlineCircles.toSet() // equal but distinct instance
        assertEquals(0, ellipsoid.resetCount)
        ellipsoid.zRadius = 20.0
        assertEquals(1, ellipsoid.resetCount)
    }

    // ---- Path content fingerprint ----

    @Test
    fun pathEqualContentReassignmentDoesNotReset() {
        val path = TestPath(positions(0.0, 100.0))
        path.checkContent() // first pass stamps the fingerprint
        val initial = path.resetCount

        path.positions = positions(0.0, 100.0) // fresh list, identical content
        path.checkContent()
        assertEquals(initial, path.resetCount, "identical content must not regenerate geometry")
    }

    @Test
    fun pathChangedContentResets() {
        val path = TestPath(positions(0.0, 100.0))
        path.checkContent()
        val initial = path.resetCount

        path.positions = positions(0.0, 250.0)
        path.checkContent()
        assertEquals(initial + 1, path.resetCount, "changed content must reset")
    }

    @Test
    fun pathInPlaceMutationDetectedWithoutSetterCall() {
        val list = positions(0.0, 100.0)
        val path = TestPath(list)
        path.checkContent()
        val initial = path.resetCount

        list[1].altitude = 999.0 // mutate the Position object directly, no assignment
        path.checkContent()
        assertEquals(initial + 1, path.resetCount, "in-place mutation must be detected")

        path.checkContent()
        assertEquals(initial + 1, path.resetCount, "stable content must not keep resetting")
    }

    @Test
    fun pathReferencePositionFollowsReassignmentBeforeRender() {
        val path = TestPath(positions(0.0))
        val before = Position(path.referencePosition)
        path.positions = listOf(Position(fromDegrees(-45.0), fromDegrees(60.0), 0.0))
        val after = path.referencePosition
        assertNotEquals(before.latitude, after.latitude, "cached reference position must drop on re-assignment")
    }

    // ---- Polygon content fingerprint ----

    @Test
    fun polygonClearAndReAddIdenticalBoundaryDoesNotReset() {
        val polygon = TestPolygon(positions(0.0, 10.0, 20.0))
        polygon.checkContent()
        val initial = polygon.resetCount

        polygon.clearBoundaries()
        polygon.addBoundary(positions(0.0, 10.0, 20.0))
        polygon.checkContent()
        assertEquals(initial, polygon.resetCount, "clear+add of identical content must not reset")

        polygon.clearBoundaries()
        polygon.addBoundary(positions(0.0, 10.0, 30.0))
        polygon.checkContent()
        assertEquals(initial + 1, polygon.resetCount, "changed boundary must reset")
    }

    // ---- Center-based shapes ----

    @Test
    fun ellipseCenterMutationDetected() {
        val ellipse = TestEllipse(Position(fromDegrees(1.0), fromDegrees(2.0), 0.0), 100.0, 50.0)
        ellipse.checkContent()
        val initial = ellipse.resetCount

        ellipse.center = Position(fromDegrees(1.0), fromDegrees(2.0), 0.0) // identical value
        ellipse.checkContent()
        assertEquals(initial, ellipse.resetCount)

        ellipse.center.latitude = fromDegrees(5.0) // in-place mutation
        ellipse.checkContent()
        assertEquals(initial + 1, ellipse.resetCount)
    }

    // ---- Mesh content fingerprint ----

    @Test
    fun triangleMeshContentFingerprint() {
        val pts = arrayOf(
            Position(fromDegrees(0.0), fromDegrees(0.0), 0.0),
            Position(fromDegrees(1.0), fromDegrees(0.0), 0.0),
            Position(fromDegrees(0.0), fromDegrees(1.0), 0.0),
        )
        val mesh = TestMesh(pts, intArrayOf(0, 1, 2))
        mesh.checkContent()
        val initial = mesh.resetCount

        mesh.indices = intArrayOf(0, 1, 2) // identical content, fresh array
        mesh.checkContent()
        assertEquals(initial, mesh.resetCount)

        pts[2].altitude = 500.0 // in-place vertex mutation
        mesh.checkContent()
        assertEquals(initial + 1, mesh.resetCount)
    }

    // ---- ProjectedMediaSurface corners ----

    @Test
    fun mediaSurfaceIdenticalCornersDoNotReset() {
        val surface = TestMediaSurface()
        surface.checkContent()
        val initial = surface.resetCount

        surface.setLocations(
            Location(fromDegrees(0.0), fromDegrees(0.0)), Location(fromDegrees(0.0), fromDegrees(1.0)),
            Location(fromDegrees(1.0), fromDegrees(1.0)), Location(fromDegrees(1.0), fromDegrees(0.0)),
        )
        surface.checkContent()
        assertEquals(initial, surface.resetCount)

        surface.setLocation(0, Location(fromDegrees(-1.0), fromDegrees(0.0)))
        surface.checkContent()
        assertEquals(initial + 1, surface.resetCount)
    }
}
