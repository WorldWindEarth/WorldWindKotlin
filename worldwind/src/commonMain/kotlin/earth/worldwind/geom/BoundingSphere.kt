package earth.worldwind.geom

import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage

/**
 * Represents a bounding sphere in Cartesian coordinates. Typically used as a bounding volume.
 */
open class BoundingSphere {
    /**
     * The sphere's center point.
     */
    val center = Vec3()
    /**
     * The sphere's radius.
     */
    var radius = 1.0
        set(value) {
            require(value >= 0) {
                logMessage(ERROR, "BoundingSphere", "setRadius", "invalidRadius")
            }
            field = value
        }

    /**
     * Sets this bounding sphere to the specified center point and radius.
     *
     * @param center the new center point
     * @param radius the new radius
     *
     * @return This bounding sphere with its center point and radius set to the specified values
     */
    fun set(center: Vec3, radius: Double) = apply {
        this.center.copy(center)
        this.radius = radius
    }

    /**
     * Indicates whether this bounding sphere intersects a specified frustum.
     *
     * @param frustum the frustum of interest
     *
     * @return true if the specified frustum intersects this bounding sphere, otherwise false.
     */
    fun intersectsFrustum(frustum: Frustum): Boolean {
        // See if the extent's bounding sphere is within or intersects the frustum. The dot product of the extent's
        // center point with each plane's vector provides a distance to each plane. If this distance is less than
        // -radius, the extent is completely clipped by that plane and therefore does not intersect the space enclosed
        // by this Frustum.
        val nr = -radius
        return frustum.near.distanceToPoint(center) > nr && frustum.far.distanceToPoint(center) > nr
                && frustum.left.distanceToPoint(center) > nr && frustum.right.distanceToPoint(center) > nr
                && frustum.top.distanceToPoint(center) > nr && frustum.bottom.distanceToPoint(center) > nr
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BoundingSphere) return false
        return center == other.center && radius == other.radius
    }

    override fun hashCode(): Int {
        var result = center.hashCode()
        result = 31 * result + radius.hashCode()
        return result
    }

    override fun toString() = "BoundingSphere(center=$center, radius=$radius)"
}