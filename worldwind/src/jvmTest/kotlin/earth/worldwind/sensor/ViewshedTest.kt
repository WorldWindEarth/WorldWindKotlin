package earth.worldwind.sensor

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.globe.Globe
import earth.worldwind.globe.elevation.coverage.AbstractElevationCoverage
import earth.worldwind.globe.geoid.Geoid
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The viewshed over synthetic terrain: flat ground, a single ridge, a ridge with a taller hill
 * beyond, a valley, a slope facing away, and the horizon of a flat plane, each checked at known
 * distances along the sensor's own azimuth, plus the contour tracing on its own.
 */
class ViewshedTest {
    private val lat0 = 46.19
    private val lon0 = -122.19
    private val plain = 500.0

    private object NoGeoid : Geoid {
        override val displayName = "none"
        override fun getOffset(latitude: Angle, longitude: Angle) = 0f
    }

    private class FunctionCoverage(val height: (lat: Double, lon: Double) -> Double) : AbstractElevationCoverage() {
        override fun clear() {}
        override fun doGetElevation(latitude: Angle, longitude: Angle, retrieve: Boolean) =
            height(latitude.inDegrees, longitude.inDegrees).toFloat()
        override fun doGetElevationGrid(gridSector: Sector, gridWidth: Int, gridHeight: Int, result: FloatArray) {
            val dLat = gridSector.deltaLatitude.inDegrees / (gridHeight - 1)
            val dLon = gridSector.deltaLongitude.inDegrees / (gridWidth - 1)
            for (r in 0 until gridHeight) for (c in 0 until gridWidth) {
                result[r * gridWidth + c] = height(
                    gridSector.minLatitude.inDegrees + r * dLat, gridSector.minLongitude.inDegrees + c * dLon
                ).toFloat()
            }
        }
        override fun doGetElevationLimits(sector: Sector, result: FloatArray) {}
    }

    private fun globeWith(height: (Double, Double) -> Double) = Globe(geoid = NoGeoid).apply {
        elevationModel.addCoverage(FunctionCoverage(height))
    }

    /** Metres north of the sensor. */
    private fun north(lat: Double) = (lat - lat0) * 111_320.0

    /** A ridge running east-west, [distance] metres north of the sensor, [height] metres above the plain. */
    private fun ridge(distance: Double, height: Double, sigma: Double = 150.0): (Double, Double) -> Double = { lat, _ ->
        val d = north(lat) - distance
        plain + height * exp(-d * d / (2 * sigma * sigma))
    }

    /** A sensor looking north from [altitude] metres above the plain. */
    private fun sensor(altitude: Double, depression: Double = 5.0, range: Double = 4000.0, hFov: Double = 60.0, vFov: Double = 40.0) =
        Sensor(
            position = Position.fromDegrees(lat0, lon0, plain + altitude),
            azimuth = 0.0.degrees, depression = depression.degrees,
            horizontalFov = hFov.degrees, verticalFov = vFov.degrees, maxRange = range,
        )

    /** The viewshed once every tile under the sensor is loaded. */
    private suspend fun solve(globe: Globe, sensor: Sensor): Viewshed = runBlocking {
        val solver = ViewshedSolver(DemTileCache(globe, this, resolutionMeters = 30.0))
        var input = solver.prepare(sensor)
        while (!input.isComplete) {
            delay(5)
            input = solver.prepare(sensor)
        }
        solver.sweep(input)
    }

    /** Whether the ground [distance] metres along the sensor's azimuth is seen. */
    private fun Viewshed.seenAt(distance: Double): Boolean {
        val column = ((sensor.azimuth.inRadians - startAzimuth) / columnAngle).toInt().coerceIn(0, columns - 1)
        val bin = (distance / binLength).toInt().coerceIn(0, bins - 1)
        return seen[column * bins + bin]
    }

    @Test
    fun flatGroundIsSeenToTheRangeInOneRegion() = runBlocking {
        val viewshed = solve(globeWith { _, _ -> plain }, sensor(altitude = 100.0, depression = 10.0))
        assertTrue(viewshed.isComplete)
        assertEquals(1, viewshed.regions.size, "one region")
        assertTrue(viewshed.seenAt(500.0) && viewshed.seenAt(2000.0) && viewshed.seenAt(3500.0))
        // No gaps along the axis once the frame's near edge is passed
        val column = ((viewshed.sensor.azimuth.inRadians - viewshed.startAzimuth) / viewshed.columnAngle).toInt()
        val run = (0 until viewshed.bins).map { viewshed.seen[column * viewshed.bins + it] }
        val first = run.indexOf(true)
        val last = run.lastIndexOf(true)
        assertTrue(first >= 0 && (first..last).all { run[it] }, "seen cells along the axis are contiguous")
    }

    @Test
    fun aRidgeHidesTheGroundBehindIt() = runBlocking {
        val viewshed = solve(globeWith(ridge(distance = 1500.0, height = 300.0)), sensor(altitude = 2.0, depression = 0.0))
        assertTrue(viewshed.seenAt(1000.0), "the near slope is seen")
        assertFalse(viewshed.seenAt(2500.0), "the ground behind the crest is hidden")
        assertFalse(viewshed.seenAt(3500.0))
        assertEquals(1, viewshed.regions.size)
    }

    @Test
    fun aTallerHillBeyondTheRidgeIsASecondRegion() = runBlocking {
        val terrain: (Double, Double) -> Double = { lat, lon -> ridge(1500.0, 300.0)(lat, lon) + (ridge(3200.0, 900.0, 250.0)(lat, lon) - plain) }
        val viewshed = solve(globeWith(terrain), sensor(altitude = 2.0, depression = 0.0, vFov = 60.0))
        assertTrue(viewshed.seenAt(1000.0))
        assertFalse(viewshed.seenAt(2200.0), "the dip behind the ridge is hidden")
        assertTrue(viewshed.seenAt(3100.0), "the taller hill's face shows over the ridge")
        assertEquals(2, viewshed.regions.size)
    }

    @Test
    fun aValleyShowsItsFarSlopeFromTheRim() = runBlocking {
        val valley: (Double, Double) -> Double = { lat, _ ->
            val d = north(lat) - 1200.0
            plain - 250.0 * exp(-d * d / (2 * 400.0 * 400.0))
        }
        // High enough over the rim: from lower down the near slope's shoulder hides the floor, rightly
        val viewshed = solve(globeWith(valley), sensor(altitude = 150.0, depression = 15.0, vFov = 50.0))
        assertTrue(viewshed.seenAt(600.0), "the near slope going down")
        assertTrue(viewshed.seenAt(1900.0), "the far slope coming up")
        assertTrue(viewshed.seenAt(1200.0), "the floor, from above the rim")
    }

    @Test
    fun aSlopeFacingAwayHidesEverythingPastItsCrest() = runBlocking {
        val hill: (Double, Double) -> Double = { lat, _ ->
            val d = north(lat)
            plain + if (d < 800.0) d * 0.25 else 200.0 - (d - 800.0) * 0.1
        }
        val viewshed = solve(globeWith(hill), sensor(altitude = 2.0, depression = 0.0, vFov = 60.0))
        assertTrue(viewshed.seenAt(500.0), "the slope facing the sensor")
        assertFalse(viewshed.seenAt(1500.0), "the far side of the crest")
        assertFalse(viewshed.seenAt(3000.0))
    }

    @Test
    fun theHorizonCutsAFlatPlaneWhereTheCurvatureSays() = runBlocking {
        val viewshed = solve(globeWith { _, _ -> plain }, sensor(altitude = 2.0, depression = 0.0, range = 20_000.0, vFov = 60.0))
        val horizon = sqrt(2 * 6_371_000.0 * 2.0) // about 5 km for a 2 m eye
        assertTrue(viewshed.seenAt(horizon * 0.6), "well inside the horizon")
        assertFalse(viewshed.seenAt(horizon * 1.6), "well beyond it")
    }

    @Test
    fun theSameInputGivesTheSameViewshed() = runBlocking {
        val globe = globeWith(ridge(1500.0, 300.0))
        val a = solve(globe, sensor(altitude = 20.0))
        val b = solve(globe, sensor(altitude = 20.0))
        assertContentEquals(a.seen, b.seen)
        assertEquals(a.regions, b.regions)
    }

    @Test
    fun contoursCloseAroundSeenCellsAndDropASingleCell() {
        val columns = 6
        val bins = 6
        val seen = BooleanArray(columns * bins)
        for (c in 1..2) for (b in 1..2) seen[c * bins + b] = true          // a 2 × 2 block
        seen[4 * bins + 4] = true                                           // a lone cell
        val rings = ViewshedSolver.contours(seen, columns, bins)
        assertEquals(1, rings.size, "the block gives one ring, the lone cell none")
        val ring = rings.single()
        assertTrue(ring.size >= 8)
        assertTrue(ring.all { (x, y) -> x in 0.5..3.5 && y in 0.5..3.5 }, "the ring sits around the block")
    }

    @Test
    fun theEngineSweepsOnceAndAgainOnlyForAMoveTheDataResolves() = runBlocking {
        val globe = globeWith(ridge(1500.0, 300.0))
        val engine = ViewshedEngine(globe, this, resolutionMeters = 30.0)
        val sensor = sensor(altitude = 20.0)
        var delivered = 0
        engine.add(sensor) { delivered++ }
        while (engine.update()) delay(5)
        while (delivered == 0) { engine.update(); delay(5) }
        val after = delivered
        repeat(5) { engine.update(); delay(5) }
        assertEquals(after, delivered, "a still sensor is not swept again")
        sensor.position.latitude = (lat0 + 5.0 / 111_320.0).degrees   // 5 m: less than half a cell
        repeat(5) { engine.update(); delay(5) }
        assertEquals(after, delivered, "a move the data cannot resolve is ignored")
        sensor.azimuth = 2.0.degrees   // more than a cell's angle at 4 km
        while (delivered == after) { engine.update(); delay(5) }
        assertEquals(after + 1, delivered)
        engine.clear()
    }
}
