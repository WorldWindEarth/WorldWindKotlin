package earth.worldwind.util

import earth.worldwind.geom.Angle.Companion.NEG180
import earth.worldwind.geom.Angle.Companion.POS180
import earth.worldwind.geom.BoundingBox
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Vec3
import earth.worldwind.globe.Globe
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
     * The tile's Cartesian bounding box.
     */
    protected val extent by lazy { BoundingBox() }
    protected open val heightLimits by lazy { FloatArray(2) }
    protected var heightLimitsTimestamp = 0L
    protected var extentGlobeState: Globe.State? = null
    protected var extentGlobeOffset: Globe.Offset? = null
    private val nearestPoint = Vec3()

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
     * Altitude value is based on the minimum height for the tile.
     *
     * @param rc the render context which provides the current camera point
     *
     * @return the nearest point
     */
    protected open fun nearestPoint(rc: RenderContext): Vec3 {
        val cameraPosition = rc.camera.position
        // determine the nearest latitude
        val nearestLat = cameraPosition.latitude.coerceIn(sector.minLatitude, sector.maxLatitude)
        // determine the nearest longitude and account for the antimeridian discontinuity
        val lonDifference = cameraPosition.longitude - sector.centroidLongitude
        val nearestLon = when {
            lonDifference < NEG180 -> sector.maxLongitude
            lonDifference > POS180 -> sector.minLongitude
            else -> cameraPosition.longitude.coerceIn(sector.minLongitude, sector.maxLongitude)
        }
        val minHeight = heightLimits[0].toDouble()
        return rc.globe.geographicToCartesian(nearestLat, nearestLon, minHeight, nearestPoint)
    }

    protected open fun getExtent(rc: RenderContext): BoundingBox {
        val globe = rc.globe
        val timestamp = rc.elevationModelTimestamp
        if (timestamp != heightLimitsTimestamp) {
            if (globe.is2D) heightLimits.fill(0f) else calcHeightLimits(globe)
        }
        val state = rc.globeState
        val offset = rc.globe.offset
        if (timestamp != heightLimitsTimestamp || state != extentGlobeState || offset != extentGlobeOffset) {
            val minHeight = heightLimits[0]
            val maxHeight = heightLimits[1]
            extent.setToSector(sector, globe, minHeight, maxHeight)
        }
        heightLimitsTimestamp = timestamp
        extentGlobeState = state
        extentGlobeOffset = offset
        return extent
    }

    protected open fun calcHeightLimits(globe: Globe) {
        // initialize the heights for elevation model scan
        heightLimits[0] = Float.MAX_VALUE
        heightLimits[1] = -Float.MAX_VALUE
        globe.getElevationLimits(sector, heightLimits)
        // check for valid height limits
        if (heightLimits[0] > heightLimits[1]) heightLimits.fill(0f)
    }
}