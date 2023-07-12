package earth.worldwind.render

import earth.worldwind.geom.BoundingBox
import earth.worldwind.geom.Sector

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

    protected open fun getExtent(rc: RenderContext): BoundingBox {
        val globe = rc.globe
        val heightLimits = heightLimits
        val extent = extent
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

    protected open fun invalidateExtent() {
        heightLimitsTimestamp = 0L
        extentExaggeration = 0.0f
    }
}