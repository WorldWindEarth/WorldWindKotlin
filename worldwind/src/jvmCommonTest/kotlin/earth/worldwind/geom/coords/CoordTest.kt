package earth.worldwind.geom.coords

import earth.worldwind.geom.Angle.Companion.toRadians
import earth.worldwind.geom.Location
import earth.worldwind.geom.Location.Companion.fromDegrees
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoordTest {
    companion object {
        private fun isClose(x: Double, y: Double, limit: Double) = abs(x - y) < limit

        private fun isClose(a: Location, b: Location) = isClose(a, b, toRadians(9.0e-6))

        private fun isClose(a: Location, b: Location, limit: Double) =
            (isClose(a.latitude.radians, b.latitude.radians, limit)
                    && isClose(a.longitude.radians, b.longitude.radians, limit))

        private val TEST_POSITIONS = arrayOf(
            fromDegrees(-74.37916, 155.02235),
            fromDegrees(0.0, 0.0),
            fromDegrees(0.13, -0.2324),
            fromDegrees(-45.6456, 23.3545),
            fromDegrees(-12.7650, -33.8765),
            fromDegrees(23.4578, -135.4545),
            fromDegrees(77.3450, 156.9876)
        )
        private val MGRS_ONLY_POSITIONS = arrayOf(
            fromDegrees(-89.3454, -48.9306),
            fromDegrees(-80.5434, -170.6540)
        )
        private val NO_INVERSE_POSITIONS = arrayOf(
            fromDegrees(90.0, 177.0),
            fromDegrees(-90.0, -177.0),
            fromDegrees(90.0, 3.0)
        )
        private val NO_INVERSE_TO_MGRS = arrayOf(
            "ZAH 00000 00000", "BAN 00000 00000", "ZAH 00000 00000"
        )
    }

    @Test
    fun utmConstructionTest() {
        for (input in TEST_POSITIONS) {
            val fromLocation = UTMCoord.fromLatLon(input.latitude, input.longitude)
            val utmCoord = UTMCoord.fromUTM(fromLocation.zone, fromLocation.hemisphere, fromLocation.easting, fromLocation.northing)
            val position = Location(utmCoord.latitude, utmCoord.longitude)
            assertTrue(isClose(input, position))
        }
    }

    @Test
    fun mgrsConstructionTest() {
        for (input in TEST_POSITIONS) {
            val fromLocation = MGRSCoord.fromLatLon(input.latitude, input.longitude)
            val fromString = MGRSCoord.fromString(fromLocation.toString())
            val position = Location(fromString.latitude, fromString.longitude)
            assertTrue(isClose(input, position, 0.0002))
        }
    }

    @Test
    fun mgrsOnlyConstructionTest() {
        for (input in MGRS_ONLY_POSITIONS) {
            val fromLocation = MGRSCoord.fromLatLon(input.latitude, input.longitude)
            val fromString = MGRSCoord.fromString(fromLocation.toString())
            val position = Location(fromString.latitude, fromString.longitude)
            assertTrue(isClose(input, position, 0.0002))
        }
    }

    @Test
    fun noInverseToMGRSTest() {
        for (i in NO_INVERSE_POSITIONS.indices) {
            val input = NO_INVERSE_POSITIONS[i]
            val fromLocation = MGRSCoord.fromLatLon(input.latitude, input.longitude)
            val mgrsString = fromLocation.toString().trim { it <= ' ' }
            assertEquals(mgrsString, NO_INVERSE_TO_MGRS[i])
        }
    }
}