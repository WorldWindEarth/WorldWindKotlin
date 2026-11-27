package earth.worldwind.sensor

import earth.worldwind.draw.DrawableSensorFrameDepth
import earth.worldwind.draw.DrawableSensorFrames
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Vec3
import earth.worldwind.globe.Globe
import earth.worldwind.globe.elevation.coverage.AbstractElevationCoverage
import earth.worldwind.globe.geoid.Geoid
import earth.worldwind.render.Color
import earth.worldwind.render.program.SensorFrameProgram.Companion.MAX_FRAMES
import earth.worldwind.util.Pool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * A hundred sensors at once over a synthetic ridge-and-valley grid: the corner rays, the viewshed
 * engine's sweeps, how long sweeping them takes, and the overlay's frame list with its per-tile
 * chunks and its depth atlas, which holds fewer frames than a hundred.
 */
class HundredSensorsTest {
    private val lat0 = 46.19
    private val lon0 = -122.19

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

    /**
     * Ridges running east-west every 3 km, 800 m from valley floor to crest, crossed by a gentler
     * north-south swell every 1.7 km, so a ray can meet a near slope, pass over a crest into the
     * valley behind it, or run out of range.
     */
    private val ridgesAndValleys: (Double, Double) -> Double = { lat, lon ->
        val north = (lat - lat0) * 111_320.0
        val east = (lon - lon0) * 111_320.0 * cos(lat0 * PI / 180)
        900.0 + 400.0 * sin(2 * PI * north / 3000.0) + 120.0 * sin(2 * PI * east / 1700.0)
    }

    private fun globe() = Globe(geoid = NoGeoid).apply { elevationModel.addCoverage(FunctionCoverage(ridgesAndValleys)) }

    /**
     * A hundred sensors on a 10 × 10 grid 1 km apart, 80 to 600 m above the ground under them,
     * looking every way, 10° to 60° down, seeded so every run makes the same ones.
     */
    private fun sensors(range: Double = 4000.0): List<Sensor> {
        val random = Random(7)
        return List(100) { i ->
            val lat = lat0 + ((i / 10) - 4.5) * 1000.0 / 111_320.0
            val lon = lon0 + ((i % 10) - 4.5) * 1000.0 / (111_320.0 * cos(lat0 * PI / 180))
            Sensor(
                position = Position.fromDegrees(lat, lon, ridgesAndValleys(lat, lon) + random.nextDouble(80.0, 600.0)),
                azimuth = random.nextDouble(0.0, 360.0).degrees, depression = random.nextDouble(10.0, 60.0).degrees,
                horizontalFov = random.nextDouble(25.0, 50.0).degrees, verticalFov = random.nextDouble(15.0, 35.0).degrees,
                maxRange = range,
            )
        }
    }

    private suspend fun DemTileCache.preload(radiusDegrees: Double = 0.08) {
        while (true) {
            var missing = false
            for (r in rowOf(lat0 - radiusDegrees)..rowOf(lat0 + radiusDegrees))
                for (c in columnOf(lon0 - radiusDegrees * 1.5)..columnOf(lon0 + radiusDegrees * 1.5))
                    if (tile(r, c) == null) missing = true
            if (!missing) return
            delay(5)
        }
    }

    private fun tilesFor(globe: Globe, scope: CoroutineScope) = DemTileCache(globe, scope, resolutionMeters = 30.0)

    /**
     * Whether the ray through frame point ([x], [y]) meets the loaded posts within the sensor's
     * range, found by marching the straight 3D ray in 2 m steps, independently of the caster's
     * pyramid walk. Null when the march ends too close to the range to say.
     */
    private fun marchHits(globe: Globe, caster: RayCaster, sensor: Sensor, x: Double, y: Double): Boolean? {
        val f = DoubleArray(3); val r = DoubleArray(3); val u = DoubleArray(3)
        sensorFrameAxes(sensor.azimuth.inRadians, sensor.depression.inRadians, sensor.roll.inRadians, f, r, u)
        val tanH = tan(sensor.horizontalFov.inRadians / 2)
        val tanV = tan(sensor.verticalFov.inRadians / 2)
        val e = f[0] + x * tanH * r[0] + y * tanV * u[0]
        val n = f[1] + x * tanH * r[1] + y * tanV * u[1]
        val up = f[2] + x * tanH * r[2] + y * tanV * u[2]
        val bearing = atan2(e, n)
        val depression = atan2(-up, sqrt(e * e + n * n))
        val p = sensor.position
        val origin = globe.geographicToCartesian(p.latitude, p.longitude, p.altitude, Vec3())
        val normal = globe.geographicToCartesianNormal(p.latitude, p.longitude, Vec3())
        val north = globe.geographicToCartesian((p.latitude.inDegrees + 1e-5).degrees, p.longitude, p.altitude, Vec3()).subtract(origin)
        north.subtract(Vec3(normal).multiply(north.dot(normal))).normalize()
        val east = Vec3(north).cross(normal).normalize()
        val dir = Vec3(east).multiply(cos(depression) * sin(bearing))
            .add(Vec3(north).multiply(cos(depression) * cos(bearing)))
            .add(Vec3(normal).multiply(-sin(depression)))
        val point = Vec3()
        val geo = Position()
        var t = 0.0
        while (true) {
            t += 2.0
            point.copy(origin).add(Vec3(dir).multiply(t))
            globe.cartesianToGeographic(point.x, point.y, point.z, geo)
            val ground = caster.elevationAt(geo.latitude.inDegrees, geo.longitude.inDegrees) ?: error("posts not loaded")
            val along = p.greatCircleDistance(geo) * 6_371_000.0
            if (geo.altitude <= ground) return if (along > sensor.maxRange - 20.0) null else along <= sensor.maxRange
            if (along > sensor.maxRange + 20.0) return false
            if (along > sensor.maxRange - 20.0) return null
            if (geo.altitude > p.altitude + 2000.0) return false
        }
    }

    @Test
    fun cornersLandForEverySensorWhoseRaysMeetTheGroundAndRepeatExactly() = runBlocking {
        val globe = globe()
        val tiles = tilesFor(globe, this)
        tiles.preload()
        val cornerX = doubleArrayOf(-1.0, 1.0, 1.0, -1.0)
        val cornerY = doubleArrayOf(1.0, 1.0, -1.0, -1.0)
        val solve = { FootprintSolver(tiles).apply { minDepth = 0; maxDepth = 0 }.let { s -> sensors().map { s.solve(it) } } }
        val first = solve()
        val second = solve()
        val caster = RayCaster(tiles)
        var reachable = 0
        var mismatches = 0
        val failures = mutableListOf<String>()
        for ((i, sensor) in sensors().withIndex()) {
            val fp = first[i]
            assertTrue(fp.isComplete, "sensor $i solved over loaded tiles")
            val marched = (0 until 4).map { marchHits(globe, caster, sensor, cornerX[it], cornerY[it]) }
            for (c in 0 until 4) if (marched[c] != null && marched[c] != fp.cornerHits[c]) mismatches++
            if (marched.all { it == true }) {
                reachable++
                if (!fp.cornerHits.all { it }) failures += "sensor $i: ${fp.cornerHits}"
            }
        }
        println("HundredSensors: $reachable of 100 sensors have all four corner rays meeting the ground; $mismatches corner disagreements with the march")
        assertTrue(reachable >= 60, "the grid has mostly reachable sensors ($reachable)")
        assertTrue(failures.isEmpty(), "corners not landed:\n" + failures.joinToString("\n"))
        assertEquals(0, mismatches, "corner hits agree with the march wherever it can tell")
        for (i in 0 until 100) {
            assertEquals(first[i].corners, second[i].corners, "sensor $i corners repeat")
            assertEquals(first[i].cornerHits, second[i].cornerHits, "sensor $i hits repeat")
        }
    }

    @Test
    fun theViewshedEngineKeepsAHundredSensorsAndSweepsOnlyWhatMoved() = runBlocking {
        val globe = globe()
        val engine = ViewshedEngine(globe, this, resolutionMeters = 30.0)
        val sensors = sensors(range = 3000.0)
        val delivered = IntArray(sensors.size)
        for ((i, sensor) in sensors.withIndex()) engine.add(sensor) { delivered[i]++ }
        assertEquals(100, engine.sensorCount)
        suspend fun settle() = withTimeout(120_000) {
            // Until nothing is in flight; the engine reports busy while a sweep runs or a tile loads
            while (engine.update()) delay(5)
        }
        // update() first: it is what starts the sweeps
        withTimeout(120_000) { while (engine.update() || delivered.any { it == 0 }) delay(5) }
        settle()
        for (i in sensors.indices) assertTrue(engine.viewshed(sensors[i])?.isComplete == true, "sensor $i has a complete viewshed")
        val baseline = delivered.copyOf()
        repeat(10) { engine.update(); delay(5) }
        assertTrue(baseline.contentEquals(delivered), "still sensors are not swept again")

        val cell = tiles(engine).cellDegrees * DemTileCache.METERS_PER_DEGREE
        val moved = sensors[0]
        moved.position.latitude = (moved.position.latitude.inDegrees + 0.4 * cell / 111_320.0).degrees
        repeat(10) { engine.update(); delay(5) }
        assertEquals(baseline[0], delivered[0], "a move of 0.4 cell is not swept")
        moved.position.latitude = (moved.position.latitude.inDegrees + 0.8 * cell / 111_320.0).degrees
        withTimeout(30_000) { while (delivered[0] == baseline[0]) { engine.update(); delay(5) } }
        settle()
        assertEquals(baseline[0] + 1, delivered[0], "a move of more than half a cell is swept once")
        for (i in 1 until sensors.size) assertEquals(baseline[i], delivered[i], "sensor $i did not move and is not swept")

        val removed = sensors[1]
        engine.remove(removed)
        assertEquals(99, engine.sensorCount)
        removed.azimuth = (removed.azimuth.inDegrees + 90.0).degrees
        removed.position.latitude = (removed.position.latitude.inDegrees + 0.01).degrees
        repeat(20) { engine.update(); delay(5) }
        assertEquals(baseline[1], delivered[1], "a removed sensor is not swept")
        assertEquals(null, engine.viewshed(removed))
        engine.clear()
    }

    private fun tiles(engine: ViewshedEngine) = engine.tiles

    @Test
    fun timeToSweepAHundredSensors() = runBlocking {
        val globe = globe()
        val tiles = tilesFor(globe, this)
        val solver = ViewshedSolver(tiles)
        for (range in listOf(1500.0, 3000.0, 5000.0)) {
            // The grid spans 9 km and the ranges reach 5 km past it: about 24 tiles, within the cache
            var inputs = sensors(range).map { solver.prepare(it) }
            withTimeout(60_000) {
                while (!inputs.all { it.isComplete }) { delay(5); inputs = sensors(range).map { solver.prepare(it) } }
            }
            repeat(2) { for (input in inputs) solver.sweep(input) } // warm-up
            val started = TimeSource.Monotonic.markNow()
            var cells = 0L
            for (input in inputs) { solver.sweep(input); cells += input.columns.toLong() * input.bins }
            val ms = started.elapsedNow().inWholeMicroseconds / 1000.0
            println(
                "HundredSensors: 100 sweeps at ${range.toInt()} m on one thread: ${"%.1f".format(ms)} ms " +
                    "(${"%.2f".format(ms / 100)} ms a sensor, ${cells / 100} cells a sensor)"
            )
        }
    }

    @Test
    fun theOverlayKeepsAHundredFramesWithinItsChunksAndItsAtlas() {
        val globe = globe()
        val overlay = SensorFrameOverlay()
        // Close together, all looking at the same valley, so tiles get more frames than one draw takes
        val random = Random(11)
        val sensors = List(100) {
            Sensor(
                position = Position.fromDegrees(
                    lat0 + random.nextDouble(-0.01, 0.01), lon0 + random.nextDouble(-0.01, 0.01), 1500.0 + random.nextDouble(0.0, 500.0)
                ),
                azimuth = random.nextDouble(0.0, 360.0).degrees, depression = random.nextDouble(20.0, 60.0).degrees,
                horizontalFov = 40.0.degrees, verticalFov = 30.0.degrees, maxRange = 3000.0,
            )
        }
        for (sensor in sensors) overlay.add(sensor, Color(1f, 0f, 0f, 1f))

        // The atlas: the first MAX_CELLS drawn frames occluded, the rest drawn without
        SensorFrameOverlay.assignDepthCells(overlay.frames, occlusion = true)
        assertEquals(16, DrawableSensorFrameDepth.MAX_CELLS, "the atlas size the tutorial text quotes")
        assertTrue(overlay.frames.all { it.isDrawn })
        for ((k, frame) in overlay.frames.withIndex()) {
            assertEquals(k < DrawableSensorFrameDepth.MAX_CELLS, frame.isOccluded, "frame $k occluded")
        }
        assertEquals(100 - DrawableSensorFrameDepth.MAX_CELLS, overlay.frames.count { it.isDrawn && !it.isOccluded })
        // A disabled frame gives up its cell to the next one
        overlay.frames[3].isEnabled = false
        SensorFrameOverlay.assignDepthCells(overlay.frames, occlusion = true)
        assertFalse(overlay.frames[3].isDrawn || overlay.frames[3].isOccluded)
        assertTrue(overlay.frames[DrawableSensorFrameDepth.MAX_CELLS].isOccluded, "frame 16 takes the freed cell")
        assertFalse(overlay.frames[DrawableSensorFrameDepth.MAX_CELLS + 1].isOccluded)
        overlay.frames[3].isEnabled = true
        SensorFrameOverlay.assignDepthCells(overlay.frames, occlusion = false)
        assertTrue(overlay.frames.none { it.isOccluded }, "occlusion off: none occluded")

        // The per-tile lists, over tiles of several levels covering the frames' ground
        val pool = object : Pool<DrawableSensorFrames> {
            override fun acquire(): DrawableSensorFrames? = null
            override fun release(instance: DrawableSensorFrames?) {}
        }
        val d = DrawableSensorFrames.obtain(pool)
        for ((k, sensor) in sensors.withIndex()) {
            sensor.frameTransform(globe, d.frameMatrices[k])
            d.frameParams[4 * k + 2] = sensor.maxRange.toFloat()
        }
        d.count = sensors.size
        val reached = IntArray(DrawableSensorFrames.MAX_TOTAL)
        var tiles = 0
        var drawsNeeded = 0
        var mostFrames = 0
        for (tileDegrees in listOf(0.0055, 0.011, 0.022, 0.044)) {
            val cols = (0.06 / tileDegrees).toInt() + 1
            for (i in -cols..cols) for (j in -cols..cols) {
                val minLat = lat0 + i * tileDegrees
                val minLon = lon0 + j * tileDegrees
                val origin = globe.geographicToCartesian((minLat + tileDegrees / 2).degrees, (minLon + tileDegrees / 2).degrees, 0.0, Vec3())
                val corner = globe.geographicToCartesian((minLat + tileDegrees).degrees, (minLon + tileDegrees).degrees, 2000.0, Vec3())
                val radius = corner.distanceTo(origin)
                val n = d.framesReaching(origin.x, origin.y, origin.z, radius, reached)
                assertTrue(n <= d.count)
                for (m in 0 until n) {
                    if (m > 0) assertTrue(reached[m] > reached[m - 1], "frames in order, each once")
                    assertTrue(DrawableSensorFrames.reaches(d.frameMatrices[reached[m]], 3000.0, origin.x, origin.y, origin.z, radius))
                }
                // Every frame the culling lets through is in the list
                val expected = (0 until d.count).count {
                    DrawableSensorFrames.reaches(d.frameMatrices[it], 3000.0, origin.x, origin.y, origin.z, radius)
                }
                assertEquals(expected, n)
                var start = 0
                var draws = 0
                while (start < n) {
                    val size = DrawableSensorFrames.chunkSize(n, start)
                    assertTrue(size in 1..MAX_FRAMES, "a draw takes 1 to $MAX_FRAMES frames, not $size")
                    start += size
                    draws++
                }
                assertEquals(n, start, "the chunks cover the tile's frames")
                assertEquals((n + MAX_FRAMES - 1) / MAX_FRAMES, draws)
                tiles++
                drawsNeeded += draws
                if (n > mostFrames) mostFrames = n
            }
        }
        println("HundredSensors: $tiles tiles, most frames on one tile $mostFrames, $drawsNeeded draws in all")
        assertTrue(mostFrames > MAX_FRAMES, "some tile needs more than one draw, so the chunking is exercised")
        d.recycle()
    }
}
