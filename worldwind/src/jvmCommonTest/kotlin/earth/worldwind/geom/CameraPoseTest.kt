package earth.worldwind.geom

import earth.worldwind.geom.Angle.Companion.ZERO
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.globe.projection.Wgs84Projection
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [CameraPose] - the world-ECEF -> image-UV matrix builder used by the
 * 3D-projection path of [earth.worldwind.shape.ProjectedMediaSurface]. Both the
 * lookAt-based and the platform/sensor-pose-based constructors are exercised; each
 * case asserts the matrix maps the optical axis target to image center (0.5, 0.5)
 * and that points behind the camera fall on the discard side (w <= 0).
 */
class CameraPoseTest {
    private val wgs84 = Wgs84Projection()
    private val scratch = Vec3()

    /** Apply [pose].matrix to ECEF [p]. Returns (uv_x, uv_y, w); divide xy by w for UV. */
    private fun project(pose: CameraPose, p: Vec3): DoubleArray {
        val m = pose.matrix.m
        val x = m[0] * p.x + m[1] * p.y + m[2] * p.z + m[3]
        val y = m[4] * p.x + m[5] * p.y + m[6] * p.z + m[7]
        val w = m[12] * p.x + m[13] * p.y + m[14] * p.z + m[15]
        return doubleArrayOf(x, y, w)
    }

    private fun ecef(latDeg: Double, lonDeg: Double, altM: Double): Vec3 {
        wgs84.geographicToCartesian(Ellipsoid.WGS84, latDeg.degrees, lonDeg.degrees, altM, 0.0, scratch)
        return Vec3(scratch.x, scratch.y, scratch.z)
    }

    @Test
    fun setToLookAt_targetMapsToImageCenter() {
        val pose = CameraPose()
        pose.setToLookAt(
            cameraLat = 0.0.degrees, cameraLon = 0.0.degrees, cameraAltMeters = 1000.0,
            targetLat = 0.0.degrees, targetLon = 0.001.degrees, targetAltMeters = 0.0,
            vFov = 60.0.degrees, aspect = 1.5,
        )
        val target = ecef(0.0, 0.001, 0.0)
        val (x, y, w) = project(pose, target).run { Triple(this[0], this[1], this[2]) }
        assertTrue(w > 0.0, "target must be in front of camera (w > 0), got w=$w")
        assertEquals(0.5, x / w, 1e-6, "target u")
        assertEquals(0.5, y / w, 1e-6, "target v")
    }

    @Test
    fun setToLookAt_pointBehindCameraHasNonPositiveW() {
        // Camera at equator looking east; a point west of the camera should be behind.
        val pose = CameraPose()
        pose.setToLookAt(
            cameraLat = 0.0.degrees, cameraLon = 0.0.degrees, cameraAltMeters = 1000.0,
            targetLat = 0.0.degrees, targetLon = 0.001.degrees, targetAltMeters = 0.0,
            vFov = 60.0.degrees, aspect = 1.5,
        )
        val behind = ecef(0.0, -0.5, 0.0) // ~55 km west of the camera
        val w = project(pose, behind)[2]
        assertTrue(w <= 0.0, "point behind camera must have w<=0, got w=$w")
    }

    @Test
    fun setFromPlatformAndSensorPose_lookingStraightDown_groundBelowMapsToImageCenter() {
        // Platform level + heading north (yaw=0), sensor pitched down 90°. Optical axis is
        // straight down; the ground point directly under the camera should land at uv center.
        val pose = CameraPose()
        pose.setFromPlatformAndSensorPose(
            cameraLat = 0.0.degrees, cameraLon = 0.0.degrees, cameraAltMeters = 1000.0,
            pYaw = ZERO, pPitch = ZERO, pRoll = ZERO,
            sAz = ZERO, sEl = (-90.0).degrees, sRoll = ZERO,
            vFov = 60.0.degrees, aspect = 1.5,
        )
        val ground = ecef(0.0, 0.0, 0.0)
        val (x, y, w) = project(pose, ground).run { Triple(this[0], this[1], this[2]) }
        assertTrue(w > 0.0, "nadir target must be in front of camera, got w=$w")
        assertEquals(0.5, x / w, 1e-4, "nadir u")
        assertEquals(0.5, y / w, 1e-4, "nadir v")
    }

    @Test
    fun setFromPlatformAndSensorPose_levelHeadingNorth_pointNorthMapsToImageCenter() {
        // Platform heading 0 (north), level pitch, no roll; sensor boresighted to body
        // forward. A target due north of the camera at the same altitude should land at the
        // image center along the optical axis.
        val pose = CameraPose()
        pose.setFromPlatformAndSensorPose(
            cameraLat = 0.0.degrees, cameraLon = 0.0.degrees, cameraAltMeters = 1000.0,
            pYaw = ZERO, pPitch = ZERO, pRoll = ZERO,
            sAz = ZERO, sEl = ZERO, sRoll = ZERO,
            vFov = 60.0.degrees, aspect = 1.5,
        )
        val northOfCamera = ecef(0.001, 0.0, 1000.0)
        val (x, y, w) = project(pose, northOfCamera).run { Triple(this[0], this[1], this[2]) }
        assertTrue(w > 0.0, "level-forward target must be in front of camera, got w=$w")
        assertEquals(0.5, x / w, 1e-4, "level-forward u")
        assertEquals(0.5, y / w, 1e-4, "level-forward v")
    }

    @Test
    fun setFromPlatformAndSensorPose_headingEast_pointEastMapsToImageCenter() {
        // Platform yaw=90° (heading east, MISB convention: clockwise from above). A point
        // due east of the camera at the same altitude should map to image center.
        val pose = CameraPose()
        pose.setFromPlatformAndSensorPose(
            cameraLat = 0.0.degrees, cameraLon = 0.0.degrees, cameraAltMeters = 1000.0,
            pYaw = 90.0.degrees, pPitch = ZERO, pRoll = ZERO,
            sAz = ZERO, sEl = ZERO, sRoll = ZERO,
            vFov = 60.0.degrees, aspect = 1.5,
        )
        val eastOfCamera = ecef(0.0, 0.001, 1000.0)
        val (x, y, w) = project(pose, eastOfCamera).run { Triple(this[0], this[1], this[2]) }
        assertTrue(w > 0.0, "yaw=east target must be in front of camera, got w=$w")
        assertEquals(0.5, x / w, 1e-4, "yaw=east u")
        assertEquals(0.5, y / w, 1e-4, "yaw=east v")
    }

    @Test
    fun setFromPlatformAndSensorPose_pitchPositiveLooksUp() {
        // Aerospace pitch convention: positive pitch = nose up. Forward axis with pitch=+45
        // should have a clear "up" component (i.e. a point above the horizon along the
        // heading should map closer to image center than a point at horizon altitude).
        val pose = CameraPose()
        pose.setFromPlatformAndSensorPose(
            cameraLat = 0.0.degrees, cameraLon = 0.0.degrees, cameraAltMeters = 1000.0,
            pYaw = ZERO, pPitch = 45.0.degrees, pRoll = ZERO,
            sAz = ZERO, sEl = ZERO, sRoll = ZERO,
            vFov = 60.0.degrees, aspect = 1.0,
        )
        // 100 m north + 100 m above the camera altitude == direction (north, up) at 45°
        // above horizon, exactly along the optical axis at pitch=+45°.
        val northAndUp = ecef(100.0 / 111_320.0, 0.0, 1000.0 + 100.0)
        val (x, y, w) = project(pose, northAndUp).run { Triple(this[0], this[1], this[2]) }
        assertTrue(w > 0.0, "pitch-up forward target must be in front of camera, got w=$w")
        assertTrue(abs(x / w - 0.5) < 0.05, "pitch-up target near u-center, got u=${x / w}")
        assertTrue(abs(y / w - 0.5) < 0.05, "pitch-up target near v-center, got v=${y / w}")
    }

    @Test
    fun setFromPlatformAndSensorPose_compositeOfPlatformAndSensorMatchesLookAt() {
        // Sanity composition: platform yaw=30°, sensor relative az=60° -> resultant heading
        // 90° (east). Under the same camera/altitude, the optical axis should align with
        // that of `setFromPlatformAndSensorPose(pYaw=90°)` (a pure-platform heading-east
        // pose). Compare the optical axis target's UV between the two.
        val composite = CameraPose().also {
            it.setFromPlatformAndSensorPose(
                cameraLat = 0.0.degrees, cameraLon = 0.0.degrees, cameraAltMeters = 1000.0,
                pYaw = 30.0.degrees, pPitch = ZERO, pRoll = ZERO,
                sAz = 60.0.degrees, sEl = ZERO, sRoll = ZERO,
                vFov = 60.0.degrees, aspect = 1.5,
            )
        }
        val pure = CameraPose().also {
            it.setFromPlatformAndSensorPose(
                cameraLat = 0.0.degrees, cameraLon = 0.0.degrees, cameraAltMeters = 1000.0,
                pYaw = 90.0.degrees, pPitch = ZERO, pRoll = ZERO,
                sAz = ZERO, sEl = ZERO, sRoll = ZERO,
                vFov = 60.0.degrees, aspect = 1.5,
            )
        }
        val east = ecef(0.0, 0.001, 1000.0)
        val (cx, cy, cw) = project(composite, east).run { Triple(this[0], this[1], this[2]) }
        val (px, py, pw) = project(pure, east).run { Triple(this[0], this[1], this[2]) }
        assertTrue(cw > 0.0 && pw > 0.0)
        assertEquals(px / pw, cx / cw, 1e-4, "composite u matches pure-platform u")
        assertEquals(py / pw, cy / cw, 1e-4, "composite v matches pure-platform v")
    }
}
