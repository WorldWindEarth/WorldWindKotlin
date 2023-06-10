package earth.worldwind.shape.milstd2525

import earth.worldwind.geom.Line
import earth.worldwind.geom.Location
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Vec3
import earth.worldwind.render.AbstractRenderable
import earth.worldwind.render.RenderContext
import earth.worldwind.render.Renderable
import earth.worldwind.shape.Highlightable
import earth.worldwind.util.Logger
import kotlin.jvm.JvmStatic
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.roundToInt

abstract class AbstractMilStd2525TacticalGraphic(
    protected val sidc: String, locations: List<Location>, sector: Sector,
    modifiers: Map<String, String>?, attributes: Map<String, String>?,
) : AbstractRenderable(sidc), Highlightable {
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
    protected var sector = Sector()
        set(value) {
            field.copy(value)
            reset()
            // Recalculate scale limits according to new sector
            val diagonalDistance = Location(sector.maxLatitude, sector.minLongitude)
                .greatCircleDistance(Location(sector.minLatitude, sector.maxLongitude))
            minScale = diagonalDistance / MAX_WIDTH_DP
            maxScale = diagonalDistance / MIN_WIDTH_DP
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
        private const val ZERO_LEVEL_PX = 256
        private val forwardRay = Line()
        private val lookAtPoint = Vec3()

        @JvmStatic
        fun defaultBoundingSector(locations: List<Location>) = Sector().apply { locations.forEach { l -> union(l) } }

        private fun computeScale(rc: RenderContext) = if (rc.hasUserProperty(SCALE_PROPERTY)) rc.getUserProperty(SCALE_PROPERTY) as Double else {
            // Get camera viewing vector
            //rc.modelview.extractEyePoint(forwardRay.origin)
            forwardRay.origin.copy(rc.cameraPoint)
            rc.modelview.extractForwardVector(forwardRay.direction)

            // Calculate range to viewing vector intersection point with globe model or to horizon if no intersection
            val range = if (rc.globe.intersect(forwardRay, lookAtPoint)) lookAtPoint.distanceTo(rc.cameraPoint) else rc.horizonDistance

            // Calculate map scale based on viewing range taking screen density into account
            rc.pixelSizeAtDistance(range) * rc.densityFactor
        }.also { rc.putUserProperty(SCALE_PROPERTY, it) }

        private fun computeNearestLoD(equatorialRadius: Double, scale: Double) =
            (ln(2 * PI * equatorialRadius / ZERO_LEVEL_PX / scale) / ln(2.0)).roundToInt()

        private fun computeLoDScale(equatorialRadius: Double, lod: Int) =
            2 * PI * equatorialRadius / ZERO_LEVEL_PX / (1 shl lod)
    }

    init { setGeometry(locations, sector) }

    fun setGeometry(locations: List<Location>, boundingSector: Sector = defaultBoundingSector(locations)) {
        require(locations.isNotEmpty()) {
            Logger.logMessage(Logger.ERROR, "MilStd2525TacticalGraphic", "constructor", "missingList")
        }
        transformLocations(locations)
        sector = boundingSector
    }

    override fun doRender(rc: RenderContext) {
        if (rc.terrain.intersects(sector)) {
            // Use shapes from previous frame during pick
            if (!rc.isPickMode) {
                // Get current map scale based on observation range.
                var currentScale = computeScale(rc)
                // Limit scale based on clipping sector diagonal size
                if (currentScale < minScale) currentScale = minScale
                else if (currentScale > maxScale) currentScale = maxScale
                // Get renderables for current LoD
                val equatorialRadius = rc.globe.equatorialRadius
                val lod = computeNearestLoD(equatorialRadius, currentScale)
                shapes = lodBuffer[lod] ?: mutableListOf<Renderable>().also {
                    lodBuffer[lod] = it
                    makeRenderables(computeLoDScale(equatorialRadius, lod), it)
                }
            }
            // Draw available shapes
            shapes?.forEach { renderable ->
                if (renderable is Highlightable) renderable.isHighlighted = isHighlighted
                renderable.render(rc)
            }
        }
    }

    protected open fun reset() {
        lodBuffer.clear()
        shapes = null
    }

    abstract fun transformLocations(locations: List<Location>)
    abstract fun makeRenderables(scale: Double, shapes: MutableList<Renderable>)
}