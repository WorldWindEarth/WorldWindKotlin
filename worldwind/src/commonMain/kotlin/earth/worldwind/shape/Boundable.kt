package earth.worldwind.shape

import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.BoundingBox
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Vec3
import earth.worldwind.render.RenderContext
import kotlin.math.sqrt

// Taken from AbstractShape
// TODO use class magic to refactor and use this interface in AbstractShape
interface Boundable {
    val boundingSector : Sector
    val boundingBox : BoundingBox
    val scratchPoint : Vec3

    /**
     * Indicates whether this shape is within the current globe's projection limits. Subclasses may implement
     * this method to perform the test. The default implementation returns true.
     * @param rc The current render context.
     * @returns true if this shape is within or intersects the current globe's projection limits, otherwise false.
     */
    fun isWithinProjectionLimits(rc: RenderContext) = true

    fun intersectsFrustum(rc: RenderContext) =
        (boundingBox.isUnitBox || boundingBox.intersectsFrustum(rc.frustum)) &&
                // This is a temporary solution. Surface shapes should also use bounding box.
                (boundingSector.isEmpty || boundingSector.intersects(rc.terrain.sector))

    fun cameraDistanceGeographic(rc: RenderContext, boundingSector: Sector): Double {
        val lat = rc.camera.position.latitude.inDegrees.coerceIn(
            boundingSector.minLatitude.inDegrees,
            boundingSector.maxLatitude.inDegrees
        )
        val lon = rc.camera.position.longitude.inDegrees.coerceIn(
            boundingSector.minLongitude.inDegrees,
            boundingSector.maxLongitude.inDegrees
        )
        val point = rc.geographicToCartesian(lat.degrees, lon.degrees, 0.0, AltitudeMode.CLAMP_TO_GROUND, scratchPoint)
        return point.distanceTo(rc.cameraPoint)
    }

    fun cameraDistanceCartesian(rc: RenderContext, array: FloatArray, count: Int, stride: Int, offset: Vec3): Double {
        val cx = rc.cameraPoint.x - offset.x
        val cy = rc.cameraPoint.y - offset.y
        val cz = rc.cameraPoint.z - offset.z
        var minDistance2 = Double.POSITIVE_INFINITY
        for (idx in 0 until count step stride) {
            val px = array[idx]
            val py = array[idx + 1]
            val pz = array[idx + 2]
            val dx = px - cx
            val dy = py - cy
            val dz = pz - cz
            val distance2 = dx * dx + dy * dy + dz * dz
            if (minDistance2 > distance2) minDistance2 = distance2
        }
        return sqrt(minDistance2)
    }
}
