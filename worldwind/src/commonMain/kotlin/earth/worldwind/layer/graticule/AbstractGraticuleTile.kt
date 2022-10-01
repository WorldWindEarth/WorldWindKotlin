package earth.worldwind.layer.graticule

import earth.worldwind.geom.BoundingBox
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Sector.Companion.fromDegrees
import earth.worldwind.render.RenderContext
import kotlinx.datetime.Instant

abstract class AbstractGraticuleTile(open val layer: AbstractGraticuleLayer, val sector: Sector) {
    var gridElements: MutableList<GridElement>? = null
        private set
    private var extent: BoundingBox? = null
    private var heightLimits: FloatArray? = null
    private var heightLimitsTimestamp = Instant.DISTANT_PAST
    private var extentExaggeration = 0.0

    open fun isInView(rc: RenderContext) = getExtent(rc).intersectsFrustum(rc.frustum)

    open fun getSizeInPixels(rc: RenderContext): Double {
        val centerPoint = layer.getSurfacePoint(rc, sector.centroidLatitude, sector.centroidLongitude)
        val distance = rc.cameraPoint.distanceTo(centerPoint)
        val tileSizeMeter = sector.deltaLatitude.radians * rc.globe!!.equatorialRadius
        return tileSizeMeter / rc.pixelSizeAtDistance(distance) / rc.densityFactor
    }

    open fun selectRenderables(rc: RenderContext) {
        gridElements ?: createRenderables()
    }

    open fun clearRenderables() {
        gridElements?.clear()
        gridElements = null
    }

    open fun createRenderables() {
        gridElements = mutableListOf()
    }

    fun subdivide(div: Int): List<Sector> {
        val dLat = sector.deltaLatitude.degrees / div
        val dLon = sector.deltaLongitude.degrees / div
        val sectors = mutableListOf<Sector>()
        for (row in 0 until div) {
            for (col in 0 until div) {
                sectors += fromDegrees(
                    sector.minLatitude.degrees + dLat * row,
                    sector.minLongitude.degrees + dLon * col, dLat, dLon
                )
            }
        }
        return sectors
    }

    private fun getExtent(rc: RenderContext): BoundingBox {
        val heightLimits = heightLimits ?: FloatArray(2).also { heightLimits = it }
        val extent = extent ?: BoundingBox().also { extent = it }
        val elevationTimestamp = rc.globe!!.elevationModel.timestamp
        if (elevationTimestamp !== heightLimitsTimestamp) {
            // initialize the heights for elevation model scan
            heightLimits[0] = Float.MAX_VALUE
            heightLimits[1] = -Float.MAX_VALUE
            rc.globe!!.elevationModel.getHeightLimits(sector, heightLimits)
            // check for valid height limits
            if (heightLimits[0] > heightLimits[1]) heightLimits.fill(0f)
        }
        val verticalExaggeration = rc.verticalExaggeration
        if (verticalExaggeration != extentExaggeration || elevationTimestamp !== heightLimitsTimestamp) {
            val minHeight = (heightLimits[0] * verticalExaggeration).toFloat()
            val maxHeight = (heightLimits[1] * verticalExaggeration).toFloat()
            extent.setToSector(sector, rc.globe!!, minHeight, maxHeight)
        }
        heightLimitsTimestamp = elevationTimestamp
        extentExaggeration = verticalExaggeration
        return extent
    }
}