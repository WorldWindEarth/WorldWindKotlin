package earth.worldwind.draw

import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Vec3
import earth.worldwind.globe.Globe
import earth.worldwind.sensor.Sensor
import earth.worldwind.sensor.frameTransform
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/** The tile test of the overlay against a brute force over the tile's surface, for a near-horizon camera. */
class SensorFrameReachTest {
    private val globe = Globe()

    private fun inside(m: DoubleArray, p: Vec3, params: DoubleArray): Boolean {
        val x = m[0] * p.x + m[1] * p.y + m[2] * p.z + m[3]
        val y = m[4] * p.x + m[5] * p.y + m[6] * p.z + m[7]
        val w = m[12] * p.x + m[13] * p.y + m[14] * p.z + m[15]
        if (w <= 0.0 || kotlin.math.abs(x) > w || kotlin.math.abs(y) > w) return false
        val lx = x * params[0]; val ly = y * params[1]
        return sqrt(lx * lx + ly * ly + w * w) <= params[2]
    }

    @Test
    fun `a tile with ground inside the frame is never culled`() {
        val sensor = Sensor(
            position = Position.fromDegrees(59.9283, 10.6956, 70.0),
            azimuth = 88.2.degrees, depression = 1.44.degrees,
            horizontalFov = 62.0.degrees, verticalFov = 40.0.degrees, maxRange = 5000.0,
        )
        val m = sensor.frameTransform(globe, Matrix4()).m
        val params = doubleArrayOf(kotlin.math.tan(sensor.horizontalFov.inRadians / 2), kotlin.math.tan(sensor.verticalFov.inRadians / 2), sensor.maxRange)
        var checked = 0
        val failures = mutableListOf<String>()
        for (tileDeg in listOf(0.0110, 0.0220, 0.0439, 0.0879, 0.1758)) {
            val maxElevation = 0.0 // a tile whose elevations are not known yet: the sphere is built at sea level
            val cols = (0.4 / tileDeg).toInt() + 1
            for (i in -cols..cols) for (j in -cols..cols) {
                val minLat = 59.9283 + i * tileDeg; val minLon = 10.6956 + j * tileDeg
                val sector = Sector.fromDegrees(minLat, minLon, tileDeg, tileDeg)
                val origin = globe.geographicToCartesian(sector.centroidLatitude, sector.centroidLongitude, 0.0, Vec3())
                val corner = globe.geographicToCartesian(sector.maxLatitude, sector.maxLongitude, maxElevation, Vec3())
                val radius = corner.distanceTo(origin)
                val reaches = DrawableSensorFrames.reaches(Matrix4().apply { set(m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7], m[8], m[9], m[10], m[11], m[12], m[13], m[14], m[15]) }, sensor.maxRange, origin.x, origin.y, origin.z, radius)
                // Brute force: any ground point of the tile, at any height up to the maximum, inside the frame?
                var any = false
                val p = Vec3()
                outer@ for (a in 0..24) for (b in 0..24) for (h in listOf(0.0, 50.0, 100.0, 150.0)) {
                    globe.geographicToCartesian((minLat + a * tileDeg / 24).degrees, (minLon + b * tileDeg / 24).degrees, h, p)
                    if (inside(m, p, params)) { any = true; break@outer }
                }
                checked++
                if (any && !reaches) failures += "tile ${tileDeg}° at ($minLat, $minLon) has ground in the frame but is culled"
            }
        }
        assertTrue(failures.isEmpty(), "$checked tiles checked; culled wrongly:\n" + failures.joinToString("\n"))
    }
}
