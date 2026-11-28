package earth.worldwind.sensor

import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Position
import earth.worldwind.geom.Vec3
import earth.worldwind.geom.Viewport
import earth.worldwind.globe.Globe
import earth.worldwind.globe.projection.EquirectangularProjection
import earth.worldwind.globe.projection.MercatorProjection
import earth.worldwind.globe.projection.PolarEquidistantProjection
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The sensor frame on a 2D globe against the same frame's rays on the 3D globe. */
class SensorFrame2DTest {
    private val globe3D = Globe()

    /** Where the frame's ray through ([x], [y]) meets the ellipsoid, found on the 3D globe; null when it does not within 500 km. */
    private fun groundHit(sensor: Sensor, x: Double, y: Double): Position? {
        val local = SensorLocalFrame().also { assertTrue(it.set(globe3D, sensor.position)) }
        val f = DoubleArray(3); val r = DoubleArray(3); val u = DoubleArray(3)
        sensorFrameAxes(sensor.azimuth.inRadians, sensor.depression.inRadians, sensor.roll.inRadians, f, r, u)
        val tanH = tan(sensor.horizontalFov.inRadians / 2); val tanV = tan(sensor.verticalFov.inRadians / 2)
        val d = DoubleArray(3) { f[it] + x * tanH * r[it] + y * tanV * u[it] }
        val w = local.toWorld
        val dx = w[0] * d[0] + w[1] * d[1] + w[2] * d[2]
        val dy = w[3] * d[0] + w[4] * d[1] + w[5] * d[2]
        val dz = w[6] * d[0] + w[7] * d[1] + w[8] * d[2]
        val o = local.origin
        val pos = Position()
        fun altitude(t: Double) = globe3D.cartesianToGeographic(o.x + t * dx, o.y + t * dy, o.z + t * dz, pos).altitude
        var lo = 0.0; var hi = 1.0
        while (altitude(hi) > 0.0) { lo = hi; hi *= 2.0; if (hi > 5e5) return null }
        repeat(80) { val mid = 0.5 * (lo + hi); if (altitude(mid) > 0.0) lo = mid else hi = mid }
        altitude(0.5 * (lo + hi))
        return Position(pos.latitude, pos.longitude, 0.0)
    }

    /** The 2D frame's coordinates of a ground position, with or without the curvature term. */
    private fun frame2D(globe: Globe, sensor: Sensor, p: Position, bent: Boolean): Pair<Double, Double> {
        val local = SensorLocalFrame().also { assertTrue(it.set(globe, sensor.position)) }
        val bend = FloatArray(12)
        val m = sensor.frameTransform(local, Matrix4(), sensor.frameNear(), sensor.frameFar(), bend, 0).m
        val v = globe.geographicToCartesian(p.latitude, p.longitude, p.altitude, Vec3())
        val en = DoubleArray(3).also { local.local(v.x, v.y, v.z, it) }
        val mono = if (bent) doubleArrayOf(en[0] * en[0], en[0] * en[1], en[1] * en[1]) else DoubleArray(3)
        fun term(c: Int) = mono[0] * bend[c] + mono[1] * bend[4 + c] + mono[2] * bend[8 + c]
        val x = m[0] * v.x + m[1] * v.y + m[2] * v.z + m[3] + term(0)
        val y = m[4] * v.x + m[5] * v.y + m[6] * v.z + m[7] + term(1)
        val w = m[12] * v.x + m[13] * v.y + m[14] * v.z + m[15] + term(3)
        assertTrue(w > 0.0, "ahead of the sensor")
        return x / w to y / w
    }

    private val globes = listOf(
        "Mercator" to Globe(projection = MercatorProjection()),
        "equirectangular" to Globe(projection = EquirectangularProjection()),
        "polar" to Globe(projection = PolarEquidistantProjection()),
    )

    @Test
    fun cornersLandWhereTheGlobesRaysDo() {
        val corners = listOf(-1.0 to 1.0, 1.0 to 1.0, 1.0 to -1.0, -1.0 to -1.0)
        var worstBent = 0.0
        var worstFlat = 0.0
        for ((name, globe) in globes) for (lat in listOf(0.0, 35.0, 60.0, 75.0)) for (depression in listOf(8.0, 20.0, 45.0, 80.0)) {
            val sensor = Sensor(
                position = Position.fromDegrees(lat, 24.0, 1500.0), azimuth = 37.0.degrees, depression = depression.degrees,
                horizontalFov = 12.0.degrees, verticalFov = 8.0.degrees, maxRange = 30_000.0, roll = 5.0.degrees,
            )
            for ((x, y) in corners) {
                val hit = groundHit(sensor, x, y)!!
                val (bx, by) = frame2D(globe, sensor, hit, bent = true)
                val (fx, fy) = frame2D(globe, sensor, hit, bent = false)
                val bent = max(abs(bx - x), abs(by - y))
                worstBent = max(worstBent, bent)
                worstFlat = max(worstFlat, max(abs(fx - x), abs(fy - y)))
                // A thousandth of the frame's half width: a tenth of a pixel of a 200-pixel-wide frame
                assertTrue(bent < 1e-3, "$name, lat $lat, depression $depression, corner ($x, $y): frame ($bx, $by)")
            }
        }
        println("2D frame corners: worst ${"%.2e".format(worstBent)} of the half width with the second-order term, ${"%.2e".format(worstFlat)} without")
        assertTrue(worstBent < worstFlat / 20, "the second-order term makes the frame land where the globe's does")
    }

    @Test
    fun nadirFrameIsExactUnderTheSensor() {
        for ((name, globe) in globes) {
            val sensor = Sensor(
                position = Position.fromDegrees(48.0, -3.0, 800.0), azimuth = 0.0.degrees, depression = 90.0.degrees,
                horizontalFov = 2.0.degrees, verticalFov = 2.0.degrees, maxRange = 5_000.0,
            )
            val (x, y) = frame2D(globe, sensor, groundHit(sensor, 1.0, 1.0)!!, bent = true)
            assertEquals(1.0, x, 1e-4, "$name x")
            assertEquals(1.0, y, 1e-4, "$name y")
        }
    }

    @Test
    fun scissorRectangleHoldsTheFrameOnTheMap() {
        val globe = Globe(projection = MercatorProjection())
        val viewport = Viewport(0, 0, 1000, 800)
        val eye = globe.geographicToCartesian(52.0.degrees, 13.0.degrees, 60_000.0, Vec3())
        val modelview = globe.cartesianToLocalTransform(eye.x, eye.y, eye.z, Matrix4()).invertOrthonormal()
        val mvp = Matrix4().setToPerspectiveProjection(1000, 800, 60.0.degrees, 100.0, 200_000.0).multiplyByMatrix(modelview)
        var checked = 0
        for (azimuth in listOf(0.0, 70.0, 160.0, 250.0)) for (depression in listOf(10.0, 30.0, 70.0)) {
            val sensor = Sensor(
                position = Position.fromDegrees(52.05, 13.1, 2000.0), azimuth = azimuth.degrees, depression = depression.degrees,
                horizontalFov = 30.0.degrees, verticalFov = 20.0.degrees, maxRange = 40_000.0,
            )
            val rect = IntArray(4)
            assertTrue(sensor.groundScreenRect(globe, mvp, viewport, -50.0, 50.0, rect, 0), "bounded")
            for (i in 0..8) {
                val x = (i % 3) - 1.0; val y = (i / 3) - 1.0
                val hit = groundHit(sensor, x, y) ?: continue
                val v = globe.geographicToCartesian(hit.latitude, hit.longitude, 0.0, Vec3())
                val m = mvp.m
                val w = m[12] * v.x + m[13] * v.y + m[14] * v.z + m[15]
                val sx = ((m[0] * v.x + m[1] * v.y + m[2] * v.z + m[3]) / w * 0.5 + 0.5) * viewport.width
                val sy = ((m[4] * v.x + m[5] * v.y + m[6] * v.z + m[7]) / w * 0.5 + 0.5) * viewport.height
                if (sx < 0 || sx > viewport.width || sy < 0 || sy > viewport.height) continue
                if (Position.fromDegrees(52.05, 13.1, 0.0).let { globe3D.geographicToCartesian(it.latitude, it.longitude, 0.0, Vec3()) }
                        .distanceTo(globe3D.geographicToCartesian(hit.latitude, hit.longitude, 0.0, Vec3())) > sensor.maxRange) continue
                checked++
                assertTrue(
                    sx >= rect[0] && sx <= rect[0] + rect[2] && sy >= rect[1] && sy <= rect[1] + rect[3],
                    "azimuth $azimuth, depression $depression: ground point ($sx, $sy) outside ${rect.toList()}"
                )
            }
        }
        assertTrue(checked > 40, "too few points checked: $checked")
    }
}
