package earth.worldwind.util

import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.BoundingBox
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Vec3
import earth.worldwind.render.RenderContext
import kotlin.math.abs
import kotlin.math.min

abstract class AbstractTile(
    /**
     * The sector spanned by this tile.
     */
    val sector: Sector
) {
    /**
     * The nearest point on the tile to the camera. Altitude value is based on the minimum height for the tile.
     */
    protected val nearestPoint = Vec3()
    /**
     * The tile's Cartesian bounding box.
     */
    protected val extent by lazy { BoundingBox() }
    protected open val heightLimits by lazy { FloatArray(2) }
    protected var heightLimitsTimestamp = 0L
    protected var extentExaggeration = 0.0f

    /**
     * Indicates whether this tile's Cartesian extent intersects a frustum.
     *
     * @param rc      the current render context
     *
     * @return true if the frustum intersects this tile's extent, otherwise false
     */
    fun intersectsFrustum(rc: RenderContext) = getExtent(rc).intersectsFrustum(rc.frustum)

    /**
     * Indicates whether this tile intersects a specified sector.
     *
     * @param sector the sector of interest
     *
     * @return true if the specified sector intersects this tile's sector, otherwise false
     */
    fun intersectsSector(sector: Sector) = this.sector.intersects(sector)

    /**
     * Calculates the distance from this tile to the camera point which ensures front to back sorting.
     *
     * @param rc the render context which provides the current camera point
     *
     * @return the L1 distance in degrees
     */
    protected open fun drawSortOrder(rc: RenderContext): Double {
        val cameraPosition = rc.camera.position
        // determine the nearest latitude
        val latAbsDifference = abs(cameraPosition.latitude.inDegrees - sector.centroidLatitude.inDegrees)
        // determine the nearest longitude and account for the antimeridian discontinuity
        val lonAbsDifference = abs(cameraPosition.longitude.inDegrees - sector.centroidLongitude.inDegrees)
        val lonAbsDifferenceCorrected = min(lonAbsDifference, 360.0 - lonAbsDifference)

        return latAbsDifference + lonAbsDifferenceCorrected // L1 distance on cylinder
    }

    /**
     * Calculates nearest point of this tile to the camera position associated with the specified render context.
     *
     * @param rc the render context which provides the current camera point
     *
     * @return the nearest point
     */
    protected open fun nearestPoint(rc: RenderContext): Vec3 {
        val cameraPosition = rc.camera.position
        // determine the nearest latitude
        val nearestLat = cameraPosition.latitude.inDegrees.coerceIn(sector.minLatitude.inDegrees, sector.maxLatitude.inDegrees)
        // determine the nearest longitude and account for the antimeridian discontinuity
        val lonDifference = cameraPosition.longitude.inDegrees - sector.centroidLongitude.inDegrees
        val nearestLon = when {
            lonDifference < -180.0 -> sector.maxLongitude.inDegrees
            lonDifference > 180.0 -> sector.minLongitude.inDegrees
            else -> cameraPosition.longitude.inDegrees.coerceIn(sector.minLongitude.inDegrees, sector.maxLongitude.inDegrees)
        }
        val minHeight = heightLimits[0] * rc.verticalExaggeration
        return rc.globe.geographicToCartesian(nearestLat.degrees, nearestLon.degrees, minHeight, nearestPoint)
    }

    protected open fun getExtent(rc: RenderContext): BoundingBox {
        val globe = rc.globe
        val timestamp = rc.elevationModelTimestamp
        if (timestamp != heightLimitsTimestamp) {
            // initialize the heights for elevation model scan
            heightLimits[0] = Float.MAX_VALUE
            heightLimits[1] = -Float.MAX_VALUE
            globe.elevationModel.getHeightLimits(sector, heightLimits)
            // check for valid height limits
            if (heightLimits[0] > heightLimits[1]) heightLimits.fill(0f)
        }
        val ve = rc.verticalExaggeration.toFloat()
        if (ve != extentExaggeration || timestamp != heightLimitsTimestamp) {
            val minHeight = heightLimits[0] * ve
            val maxHeight = heightLimits[1] * ve
            extent.setToSector(sector, globe, minHeight, maxHeight)
        }
        heightLimitsTimestamp = timestamp
        extentExaggeration = ve
        return extent
    }
}