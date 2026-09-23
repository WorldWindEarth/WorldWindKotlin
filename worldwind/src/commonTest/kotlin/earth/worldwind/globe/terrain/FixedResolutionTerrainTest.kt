package earth.worldwind.globe.terrain

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.fromDegrees
import earth.worldwind.geom.Line
import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Vec3
import earth.worldwind.globe.Globe
import earth.worldwind.globe.elevation.ElevationModel
import earth.worldwind.globe.elevation.coverage.AbstractElevationCoverage
import earth.worldwind.globe.geoid.Geoid
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

/** Ray marching, resolution, missing data and late-arriving elevation in [FixedResolutionTerrain]. */
class FixedResolutionTerrainTest {

    /** Ground at [heightM] between two latitudes, flat elsewhere, and no data inside the gap. */
    private class BandCoverage(
        private val minLat: Double,
        private val maxLat: Double,
        private val heightM: Float,
        private val gapMinLat: Double = Double.NaN,
        private val gapMaxLat: Double = Double.NaN,
    ) : AbstractElevationCoverage() {
        private fun heightAt(latDeg: Double): Float {
            if (!gapMinLat.isNaN() && latDeg >= gapMinLat && latDeg <= gapMaxLat) return NO_DATA
            return if (latDeg in minLat..maxLat) heightM else 0f
        }

        override fun doGetElevation(latitude: Angle, longitude: Angle, retrieve: Boolean) =
            heightAt(latitude.inDegrees)

        override fun doGetElevationGrid(gridSector: Sector, gridWidth: Int, gridHeight: Int, result: FloatArray) {
            val originLat = gridSector.minLatitude.inDegrees
            val stepLat = gridSector.deltaLatitude.inDegrees / (gridHeight - 1)
            var index = 0
            for (row in 0 until gridHeight) {
                val height = heightAt(originLat + row * stepLat)
                for (column in 0 until gridWidth) result[index++] = height
            }
        }

        override fun doGetElevationLimits(sector: Sector, result: FloatArray) {
            result[0] = 0f
            result[1] = heightM
        }

        override fun clear() {}

        companion object {
            /** Mirrors ElevationCoverage.MISSING_DATA. */
            const val NO_DATA = Float.MAX_VALUE
        }
    }

    private val flatGeoid = object : Geoid {
        override val displayName = "test"
        override fun getOffset(latitude: Angle, longitude: Angle) = 0f
    }

    private fun globeWith(coverage: AbstractElevationCoverage) =
        Globe(geoid = flatGeoid).apply {
            elevationModel.addCoverage(coverage)
            if (coverage is LateCoverage) coverage.model = elevationModel
        }

    private val cameraLat = 50.0
    private val cameraLon = 30.0
    private val cameraAltitude = 2000.0

    /** A ray from the camera, heading north and [depressionDeg] below horizontal. */
    private fun rayNorth(globe: Globe, depressionDeg: Double): Line {
        val origin = Vec3()
        globe.geographicToCartesian(
            fromDegrees(cameraLat), fromDegrees(cameraLon), cameraAltitude, origin
        )
        val north = Vec3()
        val ahead = Vec3()
        globe.geographicToCartesian(fromDegrees(cameraLat + 0.01), fromDegrees(cameraLon), cameraAltitude, north)
        globe.geographicToCartesianNormal(fromDegrees(cameraLat), fromDegrees(cameraLon), ahead)
        val forward = Vec3(north.x - origin.x, north.y - origin.y, north.z - origin.z).normalize()
        val down = Vec3(-ahead.x, -ahead.y, -ahead.z).normalize()
        val radians = depressionDeg * kotlin.math.PI / 180.0
        val direction = Vec3(
            forward.x * kotlin.math.cos(radians) + down.x * kotlin.math.sin(radians),
            forward.y * kotlin.math.cos(radians) + down.y * kotlin.math.sin(radians),
            forward.z * kotlin.math.cos(radians) + down.z * kotlin.math.sin(radians),
        ).normalize()
        return Line(origin, direction)
    }

    private fun latitudeOf(globe: Globe, point: Vec3): Double {
        val position = Position()
        globe.cartesianToGeographic(point.x, point.y, point.z, position)
        return position.latitude.inDegrees
    }

    private fun altitudeOf(globe: Globe, point: Vec3): Double {
        val position = Position()
        globe.cartesianToGeographic(point.x, point.y, point.z, position)
        return position.altitude
    }

    @Test
    fun stops_on_the_ridge_rather_than_the_ground_behind_it() {
        // The ray reaches 400 m at latitude 50.0815; passing through the ridge would land at 50.102
        val globe = globeWith(BandCoverage(minLat = 50.08, maxLat = 50.10, heightM = 400f))
        val terrain = FixedResolutionTerrain(globe, resolutionM = 30.0)
        val result = Vec3()

        assertTrue(terrain.intersect(rayNorth(globe, 10.0), result))
        val latitude = latitudeOf(globe, result)
        assertTrue(latitude in 50.080..50.085, "stopped at $latitude, expected the ridge's near edge")
        assertEquals(400.0, altitudeOf(globe, result), 20.0)
    }

    @Test
    fun reaches_the_ground_beyond_when_nothing_is_in_the_way() {
        val globe = globeWith(BandCoverage(minLat = 91.0, maxLat = 92.0, heightM = 400f))
        val terrain = FixedResolutionTerrain(globe, resolutionM = 30.0)
        val result = Vec3()

        assertTrue(terrain.intersect(rayNorth(globe, 10.0), result))
        assertEquals(0.0, altitudeOf(globe, result), 20.0)
        assertTrue(latitudeOf(globe, result) > 50.100)
    }

    @Test
    fun answers_the_same_whatever_resolution_the_renderer_would_have_used() {
        val globe = globeWith(BandCoverage(minLat = 50.08, maxLat = 50.10, heightM = 400f))
        val result = Vec3()
        val fine = Vec3()

        FixedResolutionTerrain(globe, resolutionM = 30.0).intersect(rayNorth(globe, 10.0), fine)
        FixedResolutionTerrain(globe, resolutionM = 90.0).intersect(rayNorth(globe, 10.0), result)

        assertTrue(abs(latitudeOf(globe, result) - latitudeOf(globe, fine)) < 0.002)
    }

    @Test
    fun a_tighter_altitude_bound_saves_reads_without_moving_the_answer() {
        val globe = globeWith(BandCoverage(minLat = 50.08, maxLat = 50.10, heightM = 400f))
        val global = Vec3()
        val regional = Vec3()

        assertTrue(FixedResolutionTerrain(globe, resolutionM = 30.0).intersect(rayNorth(globe, 10.0), global))
        assertTrue(
            FixedResolutionTerrain(globe, resolutionM = 30.0, maxTerrainAltitude = 450.0)
                .intersect(rayNorth(globe, 10.0), regional)
        )
        assertEquals(global, regional)
    }

    @Test
    fun a_ray_starting_underground_hits_at_its_origin() {
        val globe = globeWith(BandCoverage(minLat = 50.08, maxLat = 50.10, heightM = 400f))
        val terrain = FixedResolutionTerrain(globe, resolutionM = 30.0)
        val result = Position()

        assertTrue(
            terrain.firstHitFrom(
                globe, fromDegrees(50.09), fromDegrees(cameraLon), 300.0,
                bearing = fromDegrees(0.0), depression = fromDegrees(10.0), result = result,
            )
        )
        assertEquals(50.09, result.latitude.inDegrees, 1e-9)
        assertEquals(300.0, result.altitude, 1e-3)
    }

    /** Flat ground that records every block sector read from it. */
    private class RecordingCoverage : AbstractElevationCoverage() {
        val sectors = mutableListOf<Sector>()
        override fun doGetElevation(latitude: Angle, longitude: Angle, retrieve: Boolean) = 0f
        override fun doGetElevationGrid(gridSector: Sector, gridWidth: Int, gridHeight: Int, result: FloatArray) {
            sectors += Sector(gridSector)
            result.fill(0f, 0, gridWidth * gridHeight)
        }
        override fun doGetElevationLimits(sector: Sector, result: FloatArray) { result.fill(0f) }
        override fun clear() {}
    }

    @Test
    fun blocks_keep_longitude_spacing_near_resolution_and_stay_on_the_globe() {
        val coverage = RecordingCoverage()
        val globe = globeWith(coverage)
        val terrain = FixedResolutionTerrain(globe, resolutionM = 30.0)
        val points = listOf(0.3 to 30.0, 60.5 to 30.0, -60.5 to 179.9999, 10.0 to -180.0, 89.999 to 0.0, -90.0 to 0.0)
        for ((lat, lon) in points) assertTrue(terrain.surfacePoint(fromDegrees(lat), fromDegrees(lon), Vec3()))

        assertEquals(points.size, coverage.sectors.size)
        for (sector in coverage.sectors) {
            assertTrue(sector.minLatitude.inDegrees >= -90.0 && sector.maxLatitude.inDegrees <= 90.0 + 1e-9, "$sector")
            assertTrue(sector.minLongitude.inDegrees >= -180.0 && sector.maxLongitude.inDegrees <= 180.0 + 1e-9, "$sector")
        }
        // At 60 degrees cos(lat) = 0.5, so a block spans twice as many degrees of longitude
        val sixty = coverage.sectors[1]
        val ratio = sixty.deltaLongitude.inDegrees / sixty.deltaLatitude.inDegrees
        assertTrue(ratio in 1.9..2.05, "longitude/latitude ratio $ratio at 60 degrees")
    }

    @Test
    fun says_nothing_where_the_model_has_no_data() {
        val globe = Globe(geoid = flatGeoid)
        val terrain = FixedResolutionTerrain(globe, resolutionM = 30.0)
        assertFalse(terrain.intersect(rayNorth(globe, 10.0), Vec3()))
    }

    @Test
    fun marches_through_a_hole_in_the_coverage_instead_of_stopping_in_it() {
        val globe = globeWith(
            BandCoverage(minLat = 50.08, maxLat = 50.10, heightM = 400f, gapMinLat = 50.02, gapMaxLat = 50.04)
        )
        val terrain = FixedResolutionTerrain(globe, resolutionM = 30.0)
        val result = Vec3()

        assertTrue(terrain.intersect(rayNorth(globe, 10.0), result))
        assertTrue(latitudeOf(globe, result) > 50.07)
    }

    @Test
    fun surface_point_follows_the_same_model() {
        val globe = globeWith(BandCoverage(minLat = 50.08, maxLat = 50.10, heightM = 400f))
        val terrain = FixedResolutionTerrain(globe, resolutionM = 30.0)
        val onRidge = Vec3()

        assertTrue(terrain.surfacePoint(fromDegrees(50.09), fromDegrees(cameraLon), onRidge))
        assertEquals(400.0, altitudeOf(globe, onRidge), 1.0)
    }

    /** Flat ground at [heightM] with no data until [arrive] is called. Counts grid reads. */
    private class LateCoverage(private val heightM: Float) : AbstractElevationCoverage() {
        lateinit var model: ElevationModel
        private var loaded = false
        var gridReads = 0

        /** Makes the data available and reports the change for [sector], or globally if null. */
        fun arrive(sector: Sector?) {
            afterTheModelsLastChange()
            loaded = true
            updateTimestamp(sector)
        }

        /** Reports a change in [sector] without changing this coverage's data. */
        fun arriveElsewhere(sector: Sector) {
            afterTheModelsLastChange()
            updateTimestamp(sector)
        }

        /** Timestamps are in milliseconds, so wait until the clock passes the model's last change. */
        private fun afterTheModelsLastChange() {
            val last = model.timestamp
            while (Clock.System.now().toEpochMilliseconds() <= last) { /* spins a few ms at most */ }
        }

        override fun doGetElevation(latitude: Angle, longitude: Angle, retrieve: Boolean) =
            if (loaded) heightM else BandCoverage.NO_DATA

        override fun doGetElevationGrid(gridSector: Sector, gridWidth: Int, gridHeight: Int, result: FloatArray) {
            gridReads++
            if (loaded) result.fill(heightM, 0, gridWidth * gridHeight)
        }

        override fun doGetElevationLimits(sector: Sector, result: FloatArray) {
            result[0] = 0f
            result[1] = heightM
        }

        override fun clear() {}
    }

    @Test
    fun answers_from_data_that_arrived_after_the_first_ray() {
        val coverage = LateCoverage(heightM = 400f)
        val globe = globeWith(coverage)
        val terrain = FixedResolutionTerrain(globe, resolutionM = 30.0)
        val result = Vec3()

        assertFalse(terrain.intersect(rayNorth(globe, 10.0), result))
        coverage.arrive(Sector.fromDegrees(49.0, 29.0, 2.0, 2.0))
        assertTrue(terrain.intersect(rayNorth(globe, 10.0), result))
        assertEquals(400.0, altitudeOf(globe, result), 20.0)
    }

    @Test
    fun surface_point_sees_data_that_arrived_after_it_was_first_asked() {
        val coverage = LateCoverage(heightM = 400f)
        val globe = globeWith(coverage)
        val terrain = FixedResolutionTerrain(globe, resolutionM = 30.0)
        val point = Vec3()

        assertFalse(terrain.surfacePoint(fromDegrees(50.0), fromDegrees(cameraLon), point))
        coverage.arrive(null)
        assertTrue(terrain.surfacePoint(fromDegrees(50.0), fromDegrees(cameraLon), point))
        assertEquals(400.0, altitudeOf(globe, point), 1.0)
    }

    @Test
    fun keeps_its_blocks_when_ground_elsewhere_changes() {
        val coverage = LateCoverage(heightM = 400f)
        val globe = globeWith(coverage)
        coverage.arrive(null)
        val terrain = FixedResolutionTerrain(globe, resolutionM = 30.0)

        assertTrue(terrain.intersect(rayNorth(globe, 10.0), Vec3()))
        val readsBefore = coverage.gridReads
        coverage.arriveElsewhere(Sector.fromDegrees(-40.0, -120.0, 1.0, 1.0))
        assertTrue(terrain.intersect(rayNorth(globe, 10.0), Vec3()))
        assertEquals(readsBefore, coverage.gridReads)
    }
}
