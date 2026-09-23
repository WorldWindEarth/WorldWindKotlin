package earth.worldwind.globe.terrain

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.fromDegrees
import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.globe.Globe
import earth.worldwind.globe.elevation.coverage.AbstractElevationCoverage
import earth.worldwind.globe.geoid.Geoid
import kotlin.math.floor
import kotlin.test.Test

/** Prints how many elevation grid fetches footprint traces cost. Reports only, asserts nothing. */
class FixedResolutionTerrainCostTest {

    /** Flat ground that counts grid fetches. */
    private class CountingCoverage : AbstractElevationCoverage() {
        var gridFetches = 0
        val sectors = mutableSetOf<String>()

        override fun doGetElevation(latitude: Angle, longitude: Angle, retrieve: Boolean) = 0f

        override fun doGetElevationGrid(gridSector: Sector, gridWidth: Int, gridHeight: Int, result: FloatArray) {
            gridFetches++
            sectors += "%.4f,%.4f".format(gridSector.minLatitude.inDegrees, gridSector.minLongitude.inDegrees)
            result.fill(0f, 0, gridWidth * gridHeight)
        }

        override fun doGetElevationLimits(sector: Sector, result: FloatArray) {
            result[0] = 0f; result[1] = 0f
        }

        override fun clear() {}
    }

    private val coverage = CountingCoverage()
    private val globe = Globe(geoid = object : Geoid {
        override val displayName = "test"
        override fun getOffset(latitude: Angle, longitude: Angle) = 0f
    }).apply { elevationModel.addCoverage(coverage) }

    private var terrain = FixedResolutionTerrain(globe, resolutionM = 30.0)
    private val scratch = Position()

    /** The five points of a frame: four corners of a 60 x 40 field, plus the centre. */
    private val frame = listOf(
        -30.0 to -20.0, 30.0 to -20.0, 30.0 to 20.0, -30.0 to 20.0, 0.0 to 0.0,
    )

    private fun traceFootprint(lat: Double, lon: Double, altitude: Double, tiltDeg: Double) {
        val centreDepression = 90.0 - tiltDeg
        for ((bearingOffset, depressionOffset) in frame) {
            terrain.firstHitFrom(
                globe = globe,
                latitude = fromDegrees(lat),
                longitude = fromDegrees(lon),
                altitude = altitude,
                bearing = fromDegrees(bearingOffset),
                depression = fromDegrees(centreDepression + depressionOffset),
                result = scratch,
            )
        }
    }

    @Test
    fun what_one_footprint_costs() {
        // Global bound, then a regional one (Ukraine's highest point is 2061 m). Each row starts cold
        for (maxTerrainAltitude in listOf(9_000.0, 2_200.0)) {
            println("FIXED RESOLUTION TERRAIN - grid fetches per footprint, 30 m spacing, 5 rays, ground bound %.0f m".format(maxTerrainAltitude))
            println("%8s %6s %10s %8s %8s %9s".format("alt m", "tilt", "range km", "fetches", "distinct", "ms"))
            for ((altitude, tilt) in listOf(300.0 to 30.0, 500.0 to 45.0, 2000.0 to 45.0, 2000.0 to 65.0, 2000.0 to 75.0)) {
                terrain = FixedResolutionTerrain(globe, resolutionM = 30.0, maxTerrainAltitude = maxTerrainAltitude)
                coverage.gridFetches = 0
                coverage.sectors.clear()
                val start = System.nanoTime()
                traceFootprint(50.0, 30.0, altitude, tilt)
                val ms = (System.nanoTime() - start) / 1e6
                val rangeKm = altitude * kotlin.math.tan(tilt * kotlin.math.PI / 180) / 1000
                val topEdge = 90.0 - tilt - 20.0
                println(
                    "%8.0f %6.0f %10.1f %8d %8d %9.2f  %s".format(
                        altitude, tilt, rangeKm, coverage.gridFetches, coverage.sectors.size, ms,
                        if (topEdge <= 0.0) "top corners above horizon" else "",
                    ),
                )
            }
            println()
        }
    }

    @Test
    fun what_thirty_frames_cost_with_the_camera_moving() {
        // A carrier moving 25 m/s, one footprint per frame at 30 Hz
        println()
        println("THIRTY FRAMES, carrier moving 25 m/s, 2000 m at 65 deg")
        coverage.gridFetches = 0
        coverage.sectors.clear()
        val start = System.nanoTime()
        for (frameIndex in 0 until 30) {
            val lat = 50.0 + frameIndex * (25.0 / 30.0) / 111_320.0
            traceFootprint(lat, 30.0, 2000.0, 65.0)
        }
        val ms = (System.nanoTime() - start) / 1e6
        println("  grid fetches over 30 frames : ${coverage.gridFetches}")
        println("  distinct blocks touched     : ${coverage.sectors.size}")
        println("  per frame                   : %.1f fetches, %.2f ms".format(coverage.gridFetches / 30.0, ms / 30))
        println("  NASADEM tiles behind them   : ${tilesFor(coverage.sectors)} (256 px at 1 arcsec = 0.0711 deg)")
    }

    /** Number of source tiles the distinct blocks span. */
    private fun tilesFor(blockKeys: Set<String>): Int {
        val tileDeg = 256.0 / 3600.0
        val blockDeg = 30.0 * 128 / 111_320.0
        val tiles = mutableSetOf<Pair<Int, Int>>()
        for (key in blockKeys) {
            val (lat, lon) = key.split(",").map { it.toDouble() }
            for (dLat in listOf(0.0, blockDeg)) for (dLon in listOf(0.0, blockDeg)) {
                tiles += floor((lat + dLat) / tileDeg).toInt() to floor((lon + dLon) / tileDeg).toInt()
            }
        }
        return tiles.size
    }
}
