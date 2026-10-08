package earth.worldwind.globe.terrain

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.fromDegrees
import earth.worldwind.geom.Line
import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Vec3
import earth.worldwind.globe.Globe
import earth.worldwind.globe.geoid.Geoid
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Checks [firstHitFrom] bearings in WorldWind's Cartesian frame, where the polar axis is Y. */
class TerrainRaysTest {

    /** Ground at sea level everywhere: the ellipsoid itself. */
    private class SeaLevelTerrain(private val globe: Globe) : Terrain {
        override val sector = Sector().setFullSphere()

        override fun intersect(line: Line, result: Vec3): Boolean {
            // Squash the polar axis (Y) so the ellipsoid becomes a sphere, then solve.
            val radius = globe.equatorialRadius
            val squash = radius / globe.polarRadius
            val o = line.origin
            val d = line.direction
            val oy = o.y * squash
            val dy = d.y * squash
            val a = d.x * d.x + dy * dy + d.z * d.z
            val b = 2 * (o.x * d.x + oy * dy + o.z * d.z)
            val c = o.x * o.x + oy * oy + o.z * o.z - radius * radius
            val discriminant = b * b - 4 * a * c
            if (a == 0.0 || discriminant < 0) return false
            val t = (-b - sqrt(discriminant)) / (2 * a)
            if (t < 0) return false
            result.set(o.x + d.x * t, o.y + d.y * t, o.z + d.z * t)
            return true
        }

        override fun surfacePoint(latitude: Angle, longitude: Angle, result: Vec3): Boolean {
            globe.geographicToCartesian(latitude, longitude, 0.0, result)
            return true
        }

        override fun heightLimits(levelNumberDepth: Int, result: FloatArray) {
            result[0] = 0f
            result[1] = 0f
        }
    }

    private val globe = Globe(geoid = object : Geoid {
        override val displayName = "test"
        override fun getOffset(latitude: Angle, longitude: Angle) = 0f
    })

    private val terrain = SeaLevelTerrain(globe)

    private val originLat = 50.0
    private val originLon = 30.0
    private val originAltitude = 2000.0

    /** Ten degrees below horizontal from 2 km reaches the ground about 11.3 km away. */
    private fun castOn(bearingDeg: Double, depressionDeg: Double = 10.0): Position {
        val result = Position()
        assertTrue(
            terrain.firstHitFrom(
                globe = globe,
                latitude = fromDegrees(originLat),
                longitude = fromDegrees(originLon),
                altitude = originAltitude,
                bearing = fromDegrees(bearingDeg),
                depression = fromDegrees(depressionDeg),
                result = result,
            ),
            "no hit on bearing $bearingDeg",
        )
        return result
    }

    @Test
    fun north_increases_latitude_and_leaves_longitude_alone() {
        val hit = castOn(0.0)
        assertTrue(hit.latitude.inDegrees > originLat + 0.08, "went to ${hit.latitude.inDegrees}")
        assertEquals(originLon, hit.longitude.inDegrees, 0.01)
    }

    @Test
    fun south_decreases_latitude() {
        val hit = castOn(180.0)
        assertTrue(hit.latitude.inDegrees < originLat - 0.08, "went to ${hit.latitude.inDegrees}")
        assertEquals(originLon, hit.longitude.inDegrees, 0.01)
    }

    @Test
    fun east_increases_longitude_and_leaves_latitude_alone() {
        val hit = castOn(90.0)
        assertTrue(hit.longitude.inDegrees > originLon + 0.10, "went to ${hit.longitude.inDegrees}")
        assertEquals(originLat, hit.latitude.inDegrees, 0.01)
    }

    @Test
    fun west_decreases_longitude() {
        val hit = castOn(270.0)
        assertTrue(hit.longitude.inDegrees < originLon - 0.10, "went to ${hit.longitude.inDegrees}")
        assertEquals(originLat, hit.latitude.inDegrees, 0.01)
    }

    @Test
    fun straight_down_lands_underneath_whatever_the_bearing_says() {
        val hit = castOn(bearingDeg = 137.0, depressionDeg = 90.0)
        assertEquals(originLat, hit.latitude.inDegrees, 1e-4)
        assertEquals(originLon, hit.longitude.inDegrees, 1e-4)
        assertEquals(0.0, hit.altitude, 1.0)
    }

    @Test
    fun the_ground_it_lands_on_is_the_ground_the_terrain_describes() {
        assertEquals(0.0, castOn(0.0).altitude, 1.0)
    }
}
