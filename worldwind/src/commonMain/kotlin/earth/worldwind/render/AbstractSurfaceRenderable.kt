package earth.worldwind.render

import earth.worldwind.geom.BoundingBox
import earth.worldwind.geom.Sector
import earth.worldwind.globe.Globe

abstract class AbstractSurfaceRenderable(sector: Sector, displayName: String? = null) : AbstractRenderable(displayName) {
    var sector = Sector(sector)
        set(value) {
            field.copy(value)
            invalidateExtent()
        }
    protected val extent by lazy { BoundingBox() }
    protected val heightLimits by lazy { FloatArray(2) }
    protected var heightLimitsTimestamp = 0L
    protected var extentExaggeration = 0.0f
    protected var extentGlobeState: Globe.State? = null
    protected var extentGlobeOffset: Globe.Offset? = null

    protected open fun getExtent(rc: RenderContext): BoundingBox {
        val globe = rc.globe
        val timestamp = rc.elevationModelTimestamp
        if (timestamp != heightLimitsTimestamp) {
            if (globe.is2D) heightLimits.fill(0f) else calcHeightLimits(globe)
        }
        val ve = rc.verticalExaggeration.toFloat()
        val state = rc.globeState
        val offset = rc.globe.offset
        if (timestamp != heightLimitsTimestamp || ve != extentExaggeration
            || state != extentGlobeState || offset != extentGlobeOffset ) {
            val minHeight = heightLimits[0] * ve
            val maxHeight = heightLimits[1] * ve
            extent.setToSector(sector, globe, minHeight, maxHeight)
        }
        heightLimitsTimestamp = timestamp
        extentExaggeration = ve
        extentGlobeState = state
        extentGlobeOffset = offset
        return extent
    }

    protected open fun calcHeightLimits(globe: Globe) {
        // initialize the heights for elevation model scan
        heightLimits[0] = Float.MAX_VALUE
        heightLimits[1] = -Float.MAX_VALUE
        globe.elevationModel.getHeightLimits(sector, heightLimits)
        // check for valid height limits
        if (heightLimits[0] > heightLimits[1]) heightLimits.fill(0f)
    }

    protected open fun invalidateExtent() {
        heightLimitsTimestamp = 0L
        extentExaggeration = 0.0f
    }
}