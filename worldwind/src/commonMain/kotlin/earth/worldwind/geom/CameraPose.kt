package earth.worldwind.geom

import earth.worldwind.globe.projection.Wgs84Projection
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Builds a world WGS84-ECEF -> image-UV 4x4 matrix for a camera at a known geographic
 * position looking at a known geographic target. The output [matrix] takes a world ECEF
 * position to image clip space; downstream code divides `xy/w` to land in image UV in
 * [0, 1] across the camera's frustum.
 *
 * Composition (right-to-left):
 *   imageProjection = NDC->UV bias  *  perspective(vFov, aspect)  *  lookAt(eye, target, up)
 *
 * The bias term folds the NDC [-1, 1] -> [0, 1] mapping into the matrix so the fragment
 * stage only needs the homogeneous divide; no additional shader-side rescale.
 *
 * Always operates in WGS84 ECEF - independent of any 2D display projection (Mercator,
 * polar, ...) the consuming Globe might use for rendering. The vertices the matrix is
 * applied to come from terrain rendering, which keeps real-Earth ECEF positions
 * regardless of display projection.
 *
 * Construction is allocation-free after the first call: scratch [Vec3]/[Matrix4]s and a
 * private [Wgs84Projection] are held as fields and reused on each [setToLookAt] invocation.
 *
 * The view matrix follows the standard right-handed `lookAt(eye, target, up)` recipe;
 * `up` is the ellipsoid surface normal at the camera position. Roll isn't applied
 * (negligible for stabilised drone gimbals; the source-image vertical flip lives in the
 * texture's coord transform).
 */
class CameraPose {

    /** Most-recently-built world-to-image matrix. Read after [setToLookAt]. */
    val matrix = Matrix4()

    /** Eye (camera) position in ECEF, set by [setToLookAt]. Useful for frustum culling. */
    val eyeWorld = Vec3()

    private val wgs84 = Wgs84Projection()
    private val targetWorld = Vec3()
    private val upWorld = Vec3()
    private val forward = Vec3()
    private val right = Vec3()
    private val trueUp = Vec3()
    private val viewMatrix = Matrix4()
    private val projMatrix = Matrix4()
    private val biasMatrix = Matrix4()
    private val localToEcef = Matrix4()
    private val bodyForward = Vec3()
    private val bodyUp = Vec3()

    /**
     * Build the world-to-image matrix for a camera at `(camera*)` looking at `(target*)`,
     * with vertical [vFov] and image [aspect] = width/height.
     */
    fun setToLookAt(
        cameraLat: Angle, cameraLon: Angle, cameraAltMeters: Double,
        targetLat: Angle, targetLon: Angle, targetAltMeters: Double,
        vFov: Angle, aspect: Double,
        near: Double = 1.0, far: Double = 100_000.0,
    ): Matrix4 {
        // 1. ECEF positions of camera and target. Wgs84Projection ignores the `offset`
        //    parameter (it only matters for 2D map projections).
        wgs84.geographicToCartesian(Ellipsoid.WGS84, cameraLat, cameraLon, cameraAltMeters, 0.0, eyeWorld)
        wgs84.geographicToCartesian(Ellipsoid.WGS84, targetLat, targetLon, targetAltMeters, 0.0, targetWorld)
        // 2. World "up" at the camera position - ellipsoid surface normal.
        wgs84.geographicToCartesianNormal(Ellipsoid.WGS84, cameraLat, cameraLon, upWorld)

        // 3. lookAt basis. Right-handed: forward is camera->target, right is forward x up,
        //    trueUp is right x forward (already unit since right and forward are unit and
        //    orthogonal).
        forward.copy(targetWorld).subtract(eyeWorld)
        val flen = sqrt(forward.x * forward.x + forward.y * forward.y + forward.z * forward.z)
        require(flen > 1e-6) { "CameraPose: camera and target are coincident" }
        forward.x /= flen; forward.y /= flen; forward.z /= flen

        cross(forward, upWorld, right)
        normalize(right)
        cross(right, forward, trueUp)

        // 4. View matrix - orthonormal transform that takes world points into a frame where
        //    `eye` is the origin, +X is `right`, +Y is `trueUp`, -Z is `forward`. Translation
        //    column = -R . eye.
        viewMatrix.set(
            right.x,    right.y,    right.z,    0.0,
            trueUp.x,   trueUp.y,   trueUp.z,   0.0,
            -forward.x, -forward.y, -forward.z, 0.0,
            0.0,        0.0,        0.0,        1.0,
        )
        viewMatrix.m[3]  = -(right.x  * eyeWorld.x + right.y  * eyeWorld.y + right.z  * eyeWorld.z)
        viewMatrix.m[7]  = -(trueUp.x * eyeWorld.x + trueUp.y * eyeWorld.y + trueUp.z * eyeWorld.z)
        viewMatrix.m[11] = -(-forward.x * eyeWorld.x - forward.y * eyeWorld.y - forward.z * eyeWorld.z)

        // 5. Perspective projection. setToPerspectiveProjection takes integer viewport
        //    dimensions; pick any width/height pair with the right ratio.
        val w = (aspect * 1000.0).toInt().coerceAtLeast(1)
        projMatrix.setToPerspectiveProjection(w, 1000, vFov, near, far)

        // 6. NDC->UV bias: maps clip-space [-1, 1]^2 into image-space [0, 1]^2 after the
        //    homogeneous divide. Folding it into the matrix here saves a per-fragment add
        //    in the shader.
        biasMatrix.set(
            0.5, 0.0, 0.0, 0.5,
            0.0, 0.5, 0.0, 0.5,
            0.0, 0.0, 0.5, 0.5,
            0.0, 0.0, 0.0, 1.0,
        )

        // 7. Compose: matrix = bias * proj * view.
        matrix.copy(biasMatrix).multiplyByMatrix(projMatrix).multiplyByMatrix(viewMatrix)
        return matrix
    }

    /**
     * Build the world-to-image matrix from a camera at `(cameraLat, cameraLon,
     * cameraAltMeters)` whose orientation is the **composition** of platform body angles
     * (body-to-NED) and sensor relative angles (camera-body-to-platform-body). This is the
     * mathematically correct entry for KLV / MISB ST 0601 streams: tags 5-7 give platform
     * heading/pitch/roll, tags 18-20 give sensor relative azimuth/elevation/roll, and the
     * camera's actual world-frame direction is `R_platform * R_sensor * forward_body`.
     *
     * Convention (aerospace 3-2-1, body-to-NED):
     *  * Body axes: +X = forward (out the nose), +Y = right wing, +Z = down.
     *  * Yaw (heading): rotation around +Down. 0 = facing North; positive = clockwise as
     *    seen from above (so 90 = East).
     *  * Pitch: rotation around the post-yaw +Right axis. 0 = level; positive = nose up.
     *  * Roll: rotation around the post-yaw-and-pitch +Forward axis. 0 = level; positive
     *    = right wing down.
     *
     * Sensor relative angles use the same convention referred to the platform body axes.
     *
     * Falls back to [setToLookAt]'s composition for the projection and bias steps - only
     * the view-matrix derivation differs.
     */
    fun setFromPlatformAndSensorPose(
        cameraLat: Angle, cameraLon: Angle, cameraAltMeters: Double,
        pYaw: Angle, pPitch: Angle, pRoll: Angle,
        sAz: Angle, sEl: Angle, sRoll: Angle,
        vFov: Angle, aspect: Double,
        near: Double = 1.0, far: Double = 100_000.0,
    ): Matrix4 {
        // 1. Camera ECEF position.
        wgs84.geographicToCartesian(Ellipsoid.WGS84, cameraLat, cameraLon, cameraAltMeters, 0.0, eyeWorld)

        // 2. Compose platform x sensor rotations applied to body-forward (+X) and body-up
        //    (-Z, since aerospace body-Z = down). Result: forward/up in NED at the camera.
        bodyForward.set(1.0, 0.0, 0.0)
        bodyUp.set(0.0, 0.0, -1.0)
        rotateBodyToParentInPlace(bodyForward, sAz.inRadians, sEl.inRadians, sRoll.inRadians)
        rotateBodyToParentInPlace(bodyUp, sAz.inRadians, sEl.inRadians, sRoll.inRadians)
        rotateBodyToParentInPlace(bodyForward, pYaw.inRadians, pPitch.inRadians, pRoll.inRadians)
        rotateBodyToParentInPlace(bodyUp, pYaw.inRadians, pPitch.inRadians, pRoll.inRadians)

        // 3. NED -> ENU swap (engine local-tangent basis is ENU): (E, N, U) = (NED.y, NED.x, -NED.z).
        val fE = bodyForward.y;  val fN = bodyForward.x;  val fU = -bodyForward.z
        val uE = bodyUp.y;       val uN = bodyUp.x;       val uU = -bodyUp.z

        // 4. ENU -> ECEF using the local-tangent basis at the camera position. The
        //    transform's columns 0/1/2 are the East/North/Up unit vectors in ECEF.
        wgs84.geographicToCartesianTransform(Ellipsoid.WGS84, cameraLat, cameraLon, cameraAltMeters, localToEcef)
        val m = localToEcef.m
        forward.x = m[0] * fE + m[1] * fN + m[2] * fU
        forward.y = m[4] * fE + m[5] * fN + m[6] * fU
        forward.z = m[8] * fE + m[9] * fN + m[10] * fU
        normalize(forward)
        // Compute right = forward x up_ecef (with up_ecef pre-orthogonalised below).
        val upEcefX = m[0] * uE + m[1] * uN + m[2] * uU
        val upEcefY = m[4] * uE + m[5] * uN + m[6] * uU
        val upEcefZ = m[8] * uE + m[9] * uN + m[10] * uU
        right.x = forward.y * upEcefZ - forward.z * upEcefY
        right.y = forward.z * upEcefX - forward.x * upEcefZ
        right.z = forward.x * upEcefY - forward.y * upEcefX
        normalize(right)
        // trueUp = right x forward; orthogonalises any numerical drift in up_ecef.
        cross(right, forward, trueUp)

        // 5. View matrix - same recipe as setToLookAt.
        viewMatrix.set(
            right.x,    right.y,    right.z,    0.0,
            trueUp.x,   trueUp.y,   trueUp.z,   0.0,
            -forward.x, -forward.y, -forward.z, 0.0,
            0.0,        0.0,        0.0,        1.0,
        )
        viewMatrix.m[3]  = -(right.x  * eyeWorld.x + right.y  * eyeWorld.y + right.z  * eyeWorld.z)
        viewMatrix.m[7]  = -(trueUp.x * eyeWorld.x + trueUp.y * eyeWorld.y + trueUp.z * eyeWorld.z)
        viewMatrix.m[11] = -(-forward.x * eyeWorld.x - forward.y * eyeWorld.y - forward.z * eyeWorld.z)

        // 6-7. Perspective + NDC->UV bias, then compose. Same as setToLookAt steps 5-7.
        val w = (aspect * 1000.0).toInt().coerceAtLeast(1)
        projMatrix.setToPerspectiveProjection(w, 1000, vFov, near, far)
        biasMatrix.set(
            0.5, 0.0, 0.0, 0.5,
            0.0, 0.5, 0.0, 0.5,
            0.0, 0.0, 0.5, 0.5,
            0.0, 0.0, 0.0, 1.0,
        )
        matrix.copy(biasMatrix).multiplyByMatrix(projMatrix).multiplyByMatrix(viewMatrix)
        return matrix
    }

    /**
     * Apply an aerospace 3-2-1 (yaw, pitch, roll) body-to-parent rotation to [v] in place.
     * Conventions: body axes +X = forward, +Y = right, +Z = down. Parent axes are NED for
     * the platform stage and platform-body-NED for the sensor stage; the math is identical.
     */
    private fun rotateBodyToParentInPlace(v: Vec3, yaw: Double, pitch: Double, roll: Double) {
        val cy = cos(yaw); val sy = sin(yaw)
        val cp = cos(pitch); val sp = sin(pitch)
        val cr = cos(roll); val sr = sin(roll)
        val r00 = cy * cp;  val r01 = cy * sp * sr - sy * cr;  val r02 = cy * sp * cr + sy * sr
        val r10 = sy * cp;  val r11 = sy * sp * sr + cy * cr;  val r12 = sy * sp * cr - cy * sr
        val r20 = -sp;      val r21 = cp * sr;                 val r22 = cp * cr
        val x = r00 * v.x + r01 * v.y + r02 * v.z
        val y = r10 * v.x + r11 * v.y + r12 * v.z
        val z = r20 * v.x + r21 * v.y + r22 * v.z
        v.set(x, y, z)
    }

    private fun cross(a: Vec3, b: Vec3, out: Vec3) {
        val x = a.y * b.z - a.z * b.y
        val y = a.z * b.x - a.x * b.z
        val z = a.x * b.y - a.y * b.x
        out.x = x; out.y = y; out.z = z
    }

    private fun normalize(v: Vec3) {
        val len = sqrt(v.x * v.x + v.y * v.y + v.z * v.z)
        if (len > 1e-12) { v.x /= len; v.y /= len; v.z /= len }
    }
}
