package earth.worldwind.shape.milstd2525

import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Location
import earth.worldwind.geom.Sector
import earth.worldwind.render.AbstractSurfaceRenderable
import earth.worldwind.render.RenderContext
import earth.worldwind.render.Renderable
import earth.worldwind.shape.*
import earth.worldwind.shape.milstd2525.MilStd2525.labelScaleThreshold
import kotlin.jvm.JvmStatic
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.roundToInt

abstract class AbstractMilStd2525TacticalGraphic(
    protected val sidc: String, protected val boundingSector: Sector,
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
    private var minScale = Double.MIN_VALUE
    private var maxScale = Double.MAX_VALUE
    private val lodBuffer = mutableMapOf<Int, List<Renderable>>()
    private val lodSector = mutableMapOf<Int, Sector>()

    protected companion object {
        const val MAX_WIDTH_DP = 1e-3
        const val MIN_WIDTH_DP = 1e-5
        const val HIGHLIGHT_FACTOR = 2f

        private const val ZERO_LEVEL_PX = 256

        @JvmStatic
        fun defaultBoundingSector(locations: List<Location>) = Sector().apply { locations.forEach { l -> union(l) } }

        private fun computeNearestLoD(equatorialRadius: Double, scale: Double) =
            (ln(2 * PI * equatorialRadius / ZERO_LEVEL_PX / scale) / ln(2.0)).roundToInt()

        private fun computeLoDScale(equatorialRadius: Double, lod: Int) =
            2 * PI * equatorialRadius / ZERO_LEVEL_PX / (1 shl lod)
    }

    init {
        recalculateScaleLimits()
    }

    fun setBoundingSector(sector: Sector) {
        boundingSector.copy(sector)
        recalculateScaleLimits()
    }

    override fun doRender(rc: RenderContext) {
        // Get the current map scale based on observation range.
        val currentScale = rc.pixelSize * rc.densityFactor
        // Limit scale based on clipping sector diagonal size
        val limitedScale = currentScale.coerceIn(minScale, maxScale)
        // Get renderables for current LoD
        val equatorialRadius = rc.globe.equatorialRadius
        val lod = computeNearestLoD(equatorialRadius, limitedScale)
        // Set sector based on selected lod
        sector.copy(lodSector[lod] ?: boundingSector)
        // Check if tactical graphics visible
        val terrainSector = rc.terrain.sector
        if (!terrainSector.isEmpty && terrainSector.intersects(sector) && getExtent(rc).intersectsFrustum(rc.frustum)) {
            val shapes = lodBuffer[lod] ?: run {
                sector.setEmpty() // Prepare bounding box to be extended by real graphics measures
                makeRenderables(computeLoDScale(equatorialRadius, lod)).also {
                    lodBuffer[lod] = it
                    lodSector[lod] = Sector(sector) // Remember real bounding box based on LoD
                }
            }
            // Draw available shapes
            for (renderable in shapes) {
                if (renderable is Highlightable) renderable.isHighlighted = isHighlighted
                if (renderable !is Label || isHighlighted || currentScale <= labelScaleThreshold) renderable.render(rc)
            }
        }
    }

    protected abstract fun makeRenderables(scale: Double): List<Renderable> // Platform dependent implementation

    protected fun reset() {
        lodBuffer.clear()
        lodSector.clear()
    }

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

    private fun recalculateScaleLimits() {
        val diagonalDistance = Location(boundingSector.minLatitude, boundingSector.minLongitude)
            .greatCircleDistance(Location(boundingSector.maxLatitude, boundingSector.maxLongitude))
        minScale = diagonalDistance / MAX_WIDTH_DP
        maxScale = diagonalDistance / MIN_WIDTH_DP
    }
}