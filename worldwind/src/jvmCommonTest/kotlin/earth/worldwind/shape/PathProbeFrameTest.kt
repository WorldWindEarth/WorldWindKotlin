package earth.worldwind.shape

import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Position
import earth.worldwind.geom.Vec3
import earth.worldwind.globe.Globe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PathProbeFrameTest {
    private val globe = Globe()
    private val start = Position.fromDegrees(48.0, 24.0, 500.0)

    /** A level line [km] kilometres due north, sampled every 100 m, at the start's altitude. */
    private fun levelLine(km: Int): List<Position> {
        val step = 100.0 / globe.equatorialRadius
        return (0..km * 10).map { i ->
            val location = start.greatCircleLocation(0.0.degrees, i * step, earth.worldwind.geom.Location())
            Position(location.latitude, location.longitude, start.altitude)
        }
    }

    private fun world(position: Position) =
        globe.geographicToCartesian(position.latitude, position.longitude, position.altitude, Vec3())

    /** Length of [path] as straight chords between its points, m. */
    private fun length(path: List<Position>) = path.zipWithNext { a, b -> world(a).distanceTo(world(b)) }.sum()

    @Test
    fun theFrameRunsAlongThePathAndShowsEarthCurvature() {
        val path = levelLine(10)
        val frame = PathProbe.SlabFrame.of(globe, path)!!
        assertEquals(0.0, frame.minX, 1e-6)
        // Along the start's tangent plane the far end sits a hair short of the path's length.
        assertEquals(length(path), frame.maxX, 1.0)
        // A level line falls away from the start's tangent plane by d^2 / 2R, about 7.8 m at 10 km.
        var lowest = 0.0
        frame.forEachLocalPoint(frame.minX, frame.maxX) { _, z -> lowest = minOf(lowest, z) }
        assertEquals(-7.8, lowest, 0.2)
    }

    @Test
    fun theCameraLooksDownOnTheCorridorWithDepthAsHeight() {
        val frame = PathProbe.SlabFrame.of(globe, levelLine(10))!!
        val projectionView = Matrix4()
        frame.projectionView(0.0, 10_000.0, -20.0, 80.0, 5.0, projectionView)
        fun ndc(x: Double, y: Double, z: Double): Vec3 {
            val world = frame.toWorld(x, y, z)
            val clip = DoubleArray(4)
            val m = projectionView.m
            for (r in 0..3) clip[r] = m[r * 4] * world.x + m[r * 4 + 1] * world.y + m[r * 4 + 2] * world.z + m[r * 4 + 3]
            return Vec3(clip[0] / clip[3], clip[1] / clip[3], clip[2] / clip[3])
        }
        // Along the path is image x, across it image y; the near plane is the top of the window.
        val corner = ndc(0.0, -5.0, 80.0)
        assertEquals(-1.0, corner.x, 1e-6)
        assertEquals(-1.0, corner.y, 1e-6)
        assertEquals(-1.0, corner.z, 1e-6)
        val far = ndc(10_000.0, 5.0, -20.0)
        assertEquals(1.0, far.x, 1e-6)
        assertEquals(1.0, far.y, 1e-6)
        assertEquals(1.0, far.z, 1e-6)
        // Higher surfaces are nearer (smaller depth), so the depth test keeps the top one.
        assertTrue(ndc(5_000.0, 0.0, 30.0).z < ndc(5_000.0, 0.0, 10.0).z)
        // Outside the corridor's width is outside the image.
        assertTrue(kotlin.math.abs(ndc(5_000.0, 6.0, 30.0).y) > 1.0)
    }

    @Test
    fun theWalkStopsAtTheFirstSolidPointBetweenTheMargins() {
        val path = levelLine(10)
        val frame = PathProbe.SlabFrame.of(globe, path)!!
        val hit = assertNotNull(frame.firstSolid(frame.minX, frame.maxX, 5.0, 0.0, 0.0) { x, _ -> x >= 6_000.0 })
        assertEquals(6_000.0, hit.x, 5.0)
        assertEquals(6_000.0, hit.distance, 6.0)
        val position = frame.toPosition(hit)
        assertEquals(hit.distance, world(start).distanceTo(world(position)), 1.0)
        // Solid only near the start and near the end, both inside the margins: the path is clear.
        assertNull(frame.firstSolid(frame.minX, frame.maxX, 5.0, 300.0, 300.0) { x, _ -> x < 200.0 || x > 9_800.0 })
    }
}
