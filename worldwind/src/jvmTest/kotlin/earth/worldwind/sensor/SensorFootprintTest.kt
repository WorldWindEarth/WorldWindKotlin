package earth.worldwind.sensor

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Vec3
import earth.worldwind.globe.Globe
import earth.worldwind.globe.elevation.coverage.AbstractElevationCoverage
import earth.worldwind.globe.geoid.Geoid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Checks the ray caster against an independent brute force: a 0.25 m march along the true 3D
 * ray on the WGS84 ellipsoid (Cartesian origin plus distance times direction, converted back with
 * cartesianToGeographic), over the same loaded posts. That shares none of the caster's
 * curvature model, great-circle pieces or pyramid walk.
 */
class SensorFootprintTest {
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

    private fun globeWith(height: (Double, Double) -> Double) = Globe(geoid = NoGeoid).apply {
        elevationModel.addCoverage(FunctionCoverage(height))
    }

    private val hills: (Double, Double) -> Double = { lat, lon ->
        fun bump(cLat: Double, cLon: Double, h: Double, sigmaM: Double): Double {
            val dy = (lat - cLat) * 111_320.0
            val dx = (lon - cLon) * 111_320.0 * cos(cLat * PI / 180)
            return h * exp(-(dx * dx + dy * dy) / (2 * sigmaM * sigmaM))
        }
        600.0 + bump(46.21, -122.19, 900.0, 1500.0) + bump(46.17, -122.15, 600.0, 800.0) +
            bump(46.18, -122.24, 700.0, 1200.0) + 40.0 * sin(lat * 900) * cos(lon * 700)
    }

    private suspend fun DemTileCache.preload(radiusDegrees: Double = 0.2) {
        while (true) {
            var missing = false
            for (r in rowOf(lat0 - radiusDegrees)..rowOf(lat0 + radiusDegrees))
                for (c in columnOf(lon0 - radiusDegrees)..columnOf(lon0 + radiusDegrees))
                    if (tile(r, c) == null) missing = true
            if (!missing) return
            delay(5)
        }
    }

    /** Ground position where the true 3D ray first goes under the loaded posts, or null. */
    private fun bruteForce(
        globe: Globe, caster: RayCaster, alt: Double, bearing: Double, depression: Double, maxRange: Double,
    ): Position? {
        val origin = globe.geographicToCartesian(lat0.degrees, lon0.degrees, alt, Vec3())
        val up = globe.geographicToCartesianNormal(lat0.degrees, lon0.degrees, Vec3())
        val north = globe.geographicToCartesian((lat0 + 1e-5).degrees, lon0.degrees, alt, Vec3()).subtract(origin)
        north.subtract(Vec3(up).multiply(north.dot(up))).normalize()
        val east = Vec3(north).cross(up).normalize() // north × up = east in a right-handed frame
        val dir = Vec3(east).multiply(cos(depression) * sin(bearing))
            .add(Vec3(north).multiply(cos(depression) * cos(bearing)))
            .add(Vec3(up).multiply(-sin(depression)))
        val point = Vec3()
        val geo = Position()
        fun below(t: Double): Boolean {
            point.copy(origin).add(Vec3(dir).multiply(t))
            globe.cartesianToGeographic(point.x, point.y, point.z, geo)
            val ground = caster.elevationAt(geo.latitude.inDegrees, geo.longitude.inDegrees) ?: error("posts not loaded")
            return geo.altitude <= ground
        }
        val start = Position.fromDegrees(lat0, lon0, 0.0)
        val step = 0.25
        var t = 0.0
        while (true) {
            t += step
            if (below(t)) break
            if (start.greatCircleDistance(geo) * 6_371_000.0 > maxRange || geo.altitude > alt + 5000) return null
        }
        var lo = t - step
        var hi = t
        repeat(40) { val mid = 0.5 * (lo + hi); if (below(mid)) hi = mid else lo = mid }
        below(hi)
        return Position(geo)
    }

    private fun horizontalGap(a: Position, latDeg: Double, lonDeg: Double): Double {
        val dy = (a.latitude.inDegrees - latDeg) * 111_320.0
        val dx = (a.longitude.inDegrees - lonDeg) * 111_320.0 * cos(latDeg * PI / 180)
        return sqrt(dx * dx + dy * dy)
    }

    private fun tilesFor(globe: Globe, scope: CoroutineScope) = DemTileCache(globe, scope, resolutionMeters = 30.0)

    @Test
    fun raysMatchBruteForceOverFlatGround() = runBlocking {
        val globe = globeWith { _, _ -> 200.0 }
        val tiles = tilesFor(globe, this)
        tiles.preload()
        val caster = RayCaster(tiles)
        val hit = RayHit()
        for (bearingDeg in listOf(0.0, 45.0, 90.0, 200.0, 315.0)) for (depDeg in listOf(3.0, 10.0, 30.0, 70.0)) {
            val bearing = bearingDeg * PI / 180
            val dep = depDeg * PI / 180
            caster.cast(lat0, lon0, 1000.0, bearing, dep, 20_000.0, hit)
            val truth = bruteForce(globe, caster, 1000.0, bearing, dep, 20_000.0)
            assertTrue(hit.isHit && truth != null, "bearing $bearingDeg depression $depDeg should hit")
            // Flat ground at 1300 m below: horizontal error is vertical error / tan(depression)
            val allowed = 0.05 / tan(dep) + 0.05
            val gap = horizontalGap(truth, hit.latitude, hit.longitude)
            assertTrue(gap < allowed, "bearing $bearingDeg depression $depDeg off by $gap m (allowed $allowed)")
            assertEquals(200.0, hit.altitude, 1e-3)
        }
    }

    @Test
    fun raysMatchBruteForceOverHills() = runBlocking {
        val globe = globeWith(hills)
        val tiles = tilesFor(globe, this)
        tiles.preload()
        val caster = RayCaster(tiles)
        val hit = RayHit()
        val random = Random(7)
        var worst = 0.0
        var hits = 0
        repeat(150) {
            val bearing = random.nextDouble(0.0, 2 * PI)
            val dep = random.nextDouble(-2.0, 40.0) * PI / 180
            val alt = random.nextDouble(1000.0, 2500.0)
            caster.cast(lat0, lon0, alt, bearing, dep, 12_000.0, hit)
            val truth = bruteForce(globe, caster, alt, bearing, dep, 12_000.0)
            // The two measure the range slightly differently; leave out landings right at it
            val truthDistance = truth?.let { horizontalGap(it, lat0, lon0) }
            if (truthDistance != null && truthDistance > 11_900.0 || hit.isHit && hit.distance > 11_900.0) return@repeat
            if (truth == null) {
                assertTrue(!hit.isHit, "caster hit where the brute force did not: bearing $bearing depression $dep")
                return@repeat
            }
            assertTrue(hit.isHit, "caster missed a hit at ${truth.latitude.inDegrees}, ${truth.longitude.inDegrees}")
            hits++
            // Along-track error for an error e in height is e / tan(grazing angle with the slope);
            // allow 0.25 m plus a little more for the most grazing rays.
            val gap = horizontalGap(truth, hit.latitude, hit.longitude)
            worst = max(worst, gap)
            assertTrue(gap < 0.25 + 0.02 / tan(max(dep, 0.5 * PI / 180)), "off by $gap m: bearing $bearing depression $dep alt $alt")
        }
        assertTrue(hits > 100, "too few hits to mean anything: $hits")
        println("Hills: $hits hits, worst horizontal gap ${"%.3f".format(worst)} m")
    }

    @Test
    fun thinRidgeIsNotSteppedOver() = runBlocking {
        // A wall two cells thick, 3 km north, rising 300 m from flat ground at 0
        val wallLat = lat0 + 3000.0 / 111_320.0
        val thickness = 60.0 / 111_320.0
        val globe = globeWith { lat, _ -> if (lat in wallLat..wallLat + thickness) 300.0 else 0.0 }
        val tiles = tilesFor(globe, this)
        tiles.preload()
        val caster = RayCaster(tiles)
        // From 400 m, 1° down: over the wall the ray is about 350 m up, so it clears it - and
        // 3° down it is about 240 m up, so it must stop on the wall's face.
        val clear = caster.cast(lat0, lon0, 400.0, 0.0, 1.0 * PI / 180, 10_000.0, RayHit())
        assertTrue(!clear.isHit || clear.distance > 3100.0, "1° ray should clear the wall, stopped at ${clear.distance}")
        val blocked = caster.cast(lat0, lon0, 400.0, 0.0, 3.0 * PI / 180, 10_000.0, RayHit())
        assertTrue(blocked.isHit, "3° ray should stop on the wall")
        assertEquals(3000.0, blocked.distance, 31.0)
        assertTrue(blocked.altitude > 1.0, "should land on the wall, not the ground before it: ${blocked.altitude}")
    }

    @Test
    fun nadirFootprintOverFlatGroundIsTheFovSquare() = runBlocking {
        val globe = globeWith { _, _ -> 0.0 }
        val tiles = tilesFor(globe, this)
        tiles.preload()
        val solver = FootprintSolver(tiles)
        val sensor = Sensor(
            position = Position.fromDegrees(lat0, lon0, 1000.0), azimuth = 0.0.degrees, depression = 90.0.degrees,
            horizontalFov = 60.0.degrees, verticalFov = 40.0.degrees, maxRange = 5000.0,
        )
        val fp = solver.solve(sensor)
        val halfX = 1000.0 * tan(30.0 * PI / 180)
        val halfY = 1000.0 * tan(20.0 * PI / 180)
        // Top-left is north-west for a sensor looking straight down with north up
        val expected = listOf(-halfX to halfY, halfX to halfY, halfX to -halfY, -halfX to -halfY)
        for (i in 0 until 4) {
            val corner = fp.corners[i]
            val x = (corner.longitude.inDegrees - lon0) * 111_320.0 * cos(lat0 * PI / 180)
            val y = (corner.latitude.inDegrees - lat0) * 111_320.0
            assertTrue(fp.cornerHits[i])
            assertEquals(expected[i].first, x, 2.0, "corner $i east")
            assertEquals(expected[i].second, y, 2.0, "corner $i north")
        }
        // Flat ground needs only the even split: 4 corners + 3 midpoints a side
        assertEquals(16, fp.outline.size)
        // 4 corners and 12 rays of the even split, then one ray per piece checking it is straight
        assertEquals(32, fp.rayCount)
        assertTrue(fp.isComplete)
    }

    @Test
    fun footprintRefinesWhereTheGroundIsRough() = runBlocking {
        val globe = globeWith(hills)
        val tiles = tilesFor(globe, this)
        tiles.preload()
        val solver = FootprintSolver(tiles)
        val sensor = Sensor(
            position = Position.fromDegrees(lat0, lon0, 2800.0), azimuth = 20.0.degrees, depression = 35.0.degrees,
            horizontalFov = 40.0.degrees, verticalFov = 25.0.degrees, maxRange = 10_000.0,
        )
        val fp = solver.solve(sensor)
        assertTrue(fp.outline.size > 16, "hills should need more than the even split, got ${fp.outline.size}")
        // Every outline point is on the ground
        val caster = solver.caster
        for (p in fp.outline) {
            val ground = caster.elevationAt(p.latitude.inDegrees, p.longitude.inDegrees)!!
            assertEquals(ground, p.altitude, 0.5)
        }
    }

    @Test
    fun engineSkipsSmallMovesAndHonoursTheInterval() = runBlocking {
        val globe = globeWith { _, _ -> 100.0 }
        val engine = SensorFootprintEngine(globe, this)
        engine.tiles.preload()
        engine.minUpdateInterval = Duration.ZERO
        var solves = 0
        val sensor = Sensor(
            position = Position.fromDegrees(lat0, lon0, 1000.0), azimuth = 0.0.degrees, depression = 45.0.degrees,
            horizontalFov = 30.0.degrees, verticalFov = 20.0.degrees, maxRange = 5000.0,
        )
        engine.add(sensor) { solves++ }
        engine.update()
        assertEquals(1, solves)
        engine.update()
        assertEquals(1, solves, "nothing changed")
        sensor.position.altitude += 0.1
        engine.update()
        assertEquals(1, solves, "under the position threshold")
        sensor.azimuth = 5.0.degrees
        engine.update()
        assertEquals(2, solves, "turned")
        engine.minUpdateInterval = Duration.INFINITE
        sensor.azimuth = 10.0.degrees
        assertTrue(engine.update(), "a due footprint waiting out the interval is pending work")
        assertEquals(2, solves, "inside the interval")
    }

    @Test
    fun engineStaysWithinItsBudget() = runBlocking {
        val globe = globeWith(hills)
        val engine = SensorFootprintEngine(globe, this)
        engine.tiles.preload()
        engine.frameBudget = Duration.parse("1ms")
        val random = Random(3)
        val sensors = List(200) {
            Sensor(
                position = Position.fromDegrees(lat0 + random.nextDouble(-0.05, 0.05), lon0 + random.nextDouble(-0.05, 0.05), 3000.0),
                azimuth = random.nextDouble(0.0, 360.0).degrees, depression = 40.0.degrees,
                horizontalFov = 30.0.degrees, verticalFov = 20.0.degrees, maxRange = 8000.0,
            ).also { engine.add(it) {} }
        }
        // Warm up the JIT, then time single updates with everything due
        repeat(20) { sensors.forEach { it.azimuth = (it.azimuth.inDegrees + 1).degrees }; engine.update() }
        sensors.forEach { it.azimuth = (it.azimuth.inDegrees + 1).degrees }
        val start = System.nanoTime()
        engine.update()
        val took = (System.nanoTime() - start) / 1e6
        // One footprint past the budget at most: the budget is checked before each solve
        assertTrue(took < 1.0 + 2.0, "update took $took ms for a 1 ms budget")

        // What a footprint costs, warm, over hills: printed for sizing, not asserted
        var rays = 0
        val t0 = System.nanoTime()
        repeat(5) { round -> for (s in sensors) { s.azimuth = (s.azimuth.inDegrees + 0.5 + round).degrees; rays += engine.solver.solve(s).rayCount } }
        val ns = (System.nanoTime() - t0).toDouble()
        val footprints = 5 * sensors.size
        println("Cost: ${"%.1f".format(ns / footprints / 1000)} µs a footprint, ${"%.2f".format(ns / rays / 1000)} µs a ray, ${rays / footprints} rays a footprint")
    }

    @Test
    fun gpuFrameCornersAreWhereTheCornerRaysLand() = runBlocking {
        val globe = globeWith(hills)
        val tiles = tilesFor(globe, this)
        tiles.preload()
        val solver = FootprintSolver(tiles).apply { minDepth = 0; maxDepth = 0 }
        val sensor = Sensor(
            position = Position.fromDegrees(lat0, lon0, 2800.0), azimuth = 37.0.degrees, depression = 32.0.degrees,
            horizontalFov = 40.0.degrees, verticalFov = 25.0.degrees, maxRange = 10_000.0, roll = 8.0.degrees,
        )
        val fp = solver.solve(sensor)
        assertEquals(4, fp.outline.size)
        val frame = sensor.frameTransform(globe, earth.worldwind.geom.Matrix4())
        val expected = listOf(-1.0 to 1.0, 1.0 to 1.0, 1.0 to -1.0, -1.0 to -1.0)
        for (i in 0 until 4) {
            assertTrue(fp.cornerHits[i], "corner $i should land")
            val c = fp.corners[i]
            val p = globe.geographicToCartesian(c.latitude, c.longitude, c.altitude, Vec3())
            val m = frame.m
            val x = m[0] * p.x + m[1] * p.y + m[2] * p.z + m[3]
            val y = m[4] * p.x + m[5] * p.y + m[6] * p.z + m[7]
            val w = m[12] * p.x + m[13] * p.y + m[14] * p.z + m[15]
            assertTrue(w > 0, "corner $i ahead of the sensor")
            // The ray's landing is on the ray to centimetres, a few thousand metres out
            assertEquals(expected[i].first, x / w, 1e-5, "corner $i frame x")
            assertEquals(expected[i].second, y / w, 1e-5, "corner $i frame y")
        }
    }

    @Test
    fun scissorRectangleHoldsTheWholeGroundFootprint() = runBlocking {
        val globe = globeWith(hills)
        val tiles = tilesFor(globe, this)
        tiles.preload()
        val solver = FootprintSolver(tiles).apply { minDepth = 3; maxDepth = 3 } // 32 points round the frame
        // A view from 25 km straight above the area, 1000 × 800 pixels
        val viewport = earth.worldwind.geom.Viewport(0, 0, 1000, 800)
        val eye = globe.geographicToCartesian(lat0.degrees, lon0.degrees, 25_000.0, Vec3())
        val modelview = globe.cartesianToLocalTransform(eye.x, eye.y, eye.z, earth.worldwind.geom.Matrix4()).invertOrthonormal()
        val mvp = earth.worldwind.geom.Matrix4().setToPerspectiveProjection(1000, 800, 70.0.degrees, 100.0, 100_000.0).multiplyByMatrix(modelview)
        val random = Random(11)
        var checked = 0
        var skipped = 0
        repeat(20) {
            val sensor = Sensor(
                position = Position.fromDegrees(lat0 + random.nextDouble(-0.03, 0.03), lon0 + random.nextDouble(-0.03, 0.03), random.nextDouble(2000.0, 3500.0)),
                azimuth = random.nextDouble(0.0, 360.0).degrees, depression = random.nextDouble(15.0, 80.0).degrees,
                horizontalFov = random.nextDouble(20.0, 50.0).degrees, verticalFov = random.nextDouble(15.0, 40.0).degrees,
                maxRange = 10_000.0, roll = random.nextDouble(-10.0, 10.0).degrees,
            )
            val rect = IntArray(4)
            assertTrue(sensor.groundScreenRect(globe, mvp, viewport, 500.0, 1700.0, rect, 0), "bounded")
            for (p in solver.solve(sensor).outline) {
                // A ray that meets no ground lands below its end at the range, outside the frame's
                // volume, where the overlay does not draw either: only ground hits are checked
                if (horizontalGap(p, sensor.position.latitude.inDegrees, sensor.position.longitude.inDegrees) > 9_500.0) { skipped++; continue }
                checked++
                val v = globe.geographicToCartesian(p.latitude, p.longitude, p.altitude, Vec3())
                val m = mvp.m
                val w = m[12] * v.x + m[13] * v.y + m[14] * v.z + m[15]
                val sx = ((m[0] * v.x + m[1] * v.y + m[2] * v.z + m[3]) / w * 0.5 + 0.5) * viewport.width
                val sy = ((m[4] * v.x + m[5] * v.y + m[6] * v.z + m[7]) / w * 0.5 + 0.5) * viewport.height
                if (sx < 0 || sx > viewport.width || sy < 0 || sy > viewport.height) continue // off screen anyway
                assertTrue(
                    sx >= rect[0] && sx <= rect[0] + rect[2] && sy >= rect[1] && sy <= rect[1] + rect[3],
                    "ground point ($sx, $sy) outside the rectangle ${rect.toList()}"
                )
            }
        }
        println("Scissor: $checked ground points inside their rectangles, $skipped range-limit points skipped")
        assertTrue(checked > 300, "too few ground points checked: $checked")
    }
}
