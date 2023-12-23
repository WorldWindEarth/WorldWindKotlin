package earth.worldwind.shape.milstd2525

import earth.worldwind.geom.*
import earth.worldwind.render.AbstractSurfaceRenderable
import earth.worldwind.render.RenderContext
import earth.worldwind.render.Renderable
import earth.worldwind.shape.*
import earth.worldwind.shape.milstd2525.MilStd2525.labelScaleThreshold
import earth.worldwind.util.Logger
import kotlin.jvm.JvmStatic
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.roundToInt

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
    private val lodBuffer = mutableMapOf<Int, List<Renderable>>()

    protected companion object {
        const val MAX_WIDTH_DP = 0.0005
        const val MIN_WIDTH_DP = 0.000015
        const val HIGHLIGHT_FACTOR = 2f

        private const val ZERO_LEVEL_PX = 256
        private val forwardRay = Line()
        private val lookAtPoint = Vec3()

        @JvmStatic
        fun defaultBoundingSector(locations: List<Location>) = Sector().apply { locations.forEach { l -> union(l) } }

        private fun computeNearestLoD(equatorialRadius: Double, scale: Double) =
            (ln(2 * PI * equatorialRadius / ZERO_LEVEL_PX / scale) / ln(2.0)).roundToInt()

        private fun computeLoDScale(equatorialRadius: Double, lod: Int) =
            2 * PI * equatorialRadius / ZERO_LEVEL_PX / (1 shl lod)
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
        val terrainSector = rc.terrain.sector
        if (!terrainSector.isEmpty && terrainSector.intersects(sector) && getExtent(rc).intersectsFrustum(rc.frustum)) {
            // Get the current map scale based on observation range.
            val currentScale = rc.pixelSize * rc.densityFactor
            // Limit scale based on clipping sector diagonal size
            val limitedScale = currentScale.coerceIn(minScale, maxScale)
            // Get renderables for current LoD
            val equatorialRadius = rc.globe.equatorialRadius
            val lod = computeNearestLoD(equatorialRadius, limitedScale)
            val shapes = lodBuffer[lod] ?: makeRenderables(computeLoDScale(equatorialRadius, lod)).also { lodBuffer[lod] = it }
            // Draw available shapes
            for (renderable in shapes) {
                if (renderable is Highlightable) renderable.isHighlighted = isHighlighted
                if (renderable !is Label || isHighlighted || currentScale <= labelScaleThreshold) renderable.render(rc)
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

    protected open fun reset() = lodBuffer.clear()

    abstract fun transformLocations(locations: List<Location>)
    abstract fun makeRenderables(scale: Double): List<Renderable>

    protected fun applyShapeAttributes(shape: AbstractShape) = shape.apply {
        altitudeMode = AltitudeMode.CLAMP_TO_GROUND
        isFollowTerrain = true
        maximumIntermediatePoints = 0 // Do not draw intermediate vertices for tactical graphics
        highlightAttributes = ShapeAttributes(attributes).apply {
            outlineWidth *= HIGHLIGHT_FACTOR
        }
        pickDelegate = this@AbstractMilStd2525TacticalGraphic
    }

    protected fun applyLabelAttributes(label: Label, angle: Angle) = label.apply {
        altitudeMode = AltitudeMode.CLAMP_TO_GROUND
        rotation = angle
        rotationMode = OrientationMode.RELATIVE_TO_GLOBE
        pickDelegate = this@AbstractMilStd2525TacticalGraphic
    }
}