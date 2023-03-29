package earth.worldwind.shape.milstd2525

import earth.worldwind.geom.*
import earth.worldwind.render.AbstractSurfaceRenderable
import earth.worldwind.render.RenderContext
import earth.worldwind.render.Renderable
import earth.worldwind.shape.Highlightable
import earth.worldwind.util.Logger
import kotlin.jvm.JvmStatic

abstract class AbstractMilStd2525TacticalGraphic(
    protected val sidc: String, locations: List<Location>, boundingSector: Sector,
    modifiers: Map<String, String>?, attributes: Map<String, String>?,
) : AbstractSurfaceRenderable(boundingSector), Highlightable {
    override var isHighlighted = false
    var modifiers = modifiers
        set(value) {
            field = value
            reset()
        }
    var attributes = attributes
        set(value) {
            field = value
            reset()
        }
    private var minScale = 0.0
    private var maxScale = 0.0
    private val lodBuffer = mutableMapOf<Int,MutableList<Renderable>>()
    private var shapes: MutableList<Renderable>? = null

    protected companion object {
        const val MAX_WIDTH_DP = 0.0005
        const val MIN_WIDTH_DP = 0.000015
        const val HIGHLIGHT_FACTOR = 2f

        private const val SCALE_PROPERTY = "scale"
        private val forwardRay = Line()
        private val lookAtPoint = Vec3()

        @JvmStatic
        fun computeScale(rc: RenderContext) = if (rc.hasUserProperty(SCALE_PROPERTY)) rc.getUserProperty(SCALE_PROPERTY) as Double else {
            // Get camera viewing vector
            //rc.modelview.extractEyePoint(forwardRay.origin)
            forwardRay.origin.copy(rc.cameraPoint)
            rc.modelview.extractForwardVector(forwardRay.direction)

            // Calculate range to viewing vector intersection point with globe model or to horizon if no intersection
            val range = if (rc.globe!!.intersect(forwardRay, lookAtPoint)) lookAtPoint.distanceTo(rc.cameraPoint) else rc.horizonDistance

            // Calculate map scale based on viewing range taking screen density into account
            rc.pixelSizeAtDistance(range) * rc.densityFactor
        }.also { rc.putUserProperty(SCALE_PROPERTY, it) }

        @JvmStatic
        fun defaultBoundingSector(locations: List<Location>) = Sector().apply { locations.forEach { l -> union(l) } }
    }

    init { setGeometry(locations, boundingSector) }

    fun setGeometry(locations: List<Location>, boundingSector: Sector = defaultBoundingSector(locations)) {
        require(locations.isNotEmpty()) {
            Logger.logMessage(Logger.ERROR, "MilStd2525TacticalGraphic", "constructor", "missingList")
        }
        transformLocations(locations)
        sector = boundingSector
        reset()
    }

    override fun doRender(rc: RenderContext) {
        val terrainSector = rc.terrain!!.sector
        if (!terrainSector.isEmpty && terrainSector.intersects(sector) && getExtent(rc).intersectsFrustum(rc.frustum)) {
            // Use shapes from previous frame during pick
            if (!rc.isPickMode) {
                // Get current map scale based on observation range.
                var currentScale = computeScale(rc)
                // Limit scale based on clipping sector diagonal size
                if (currentScale < minScale) currentScale = minScale
                else if (currentScale > maxScale) currentScale = maxScale
                // Get renderables for current LoD
                val equatorialRadius = rc.globe!!.equatorialRadius
                val lod = MilStd2525Util.computeNearestLoD(equatorialRadius, currentScale)
                shapes = lodBuffer[lod] ?: mutableListOf<Renderable>().also {
                    lodBuffer[lod] = it
                    makeRenderables(MilStd2525Util.computeLoDScale(equatorialRadius, lod), it)
                }
            }
            // Draw available shapes
            shapes?.forEach { renderable ->
                if (renderable is Highlightable) renderable.isHighlighted = isHighlighted
                renderable.render(rc)
            }
        }
    }

    override fun invalidateExtent() {
        super.invalidateExtent()
        // Recalculate scale limits according to new sector
        val diagonalDistance = Location(sector.maxLatitude, sector.minLongitude)
            .greatCircleDistance(Location(sector.minLatitude, sector.maxLongitude))
        minScale = diagonalDistance / MAX_WIDTH_DP
        maxScale = diagonalDistance / MIN_WIDTH_DP
    }

    protected open fun reset() {
        lodBuffer.clear()
        shapes = null
    }

    abstract fun transformLocations(locations: List<Location>)
    abstract fun makeRenderables(scale: Double, shapes: MutableList<Renderable>)
}