package earth.worldwind.shape

import earth.worldwind.PickedObject
import earth.worldwind.geom.*
import earth.worldwind.globe.Globe
import earth.worldwind.render.AbstractRenderable
import earth.worldwind.render.Color
import earth.worldwind.render.RenderContext
import earth.worldwind.render.Texture
import earth.worldwind.render.image.ImageOptions
import earth.worldwind.render.image.ResamplingMode
import earth.worldwind.render.image.WrapMode
import kotlin.jvm.JvmStatic
import kotlin.math.PI
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sqrt

abstract class AbstractShape(
    override var attributes: ShapeAttributes
): AbstractRenderable(), Attributable, Highlightable, Movable {
    override var altitudeMode = AltitudeMode.ABSOLUTE
        set(value) {
            field = value
            reset()
        }
    var pathType = PathType.GREAT_CIRCLE
        set(value) {
            field = value
            reset()
        }
    /**
     * Draw sides of the shape which extend from the defined position and altitude to [baseAltitude] level.
     */
    var isExtrude = false
        set(value) {
            field = value
            reset()
        }
    /**
     * Determines whether this shape's geometry follows the terrain surface or is fixed at a constant altitude.
     */
    var isFollowTerrain = false
        set(value) {
            field = value
            reset()
        }
    /**
     * Base altitude level of shape sides. Is dependent on [altitudeMode]. Ground level by default.
     */
    var baseAltitude = 0.0
        set(value) {
            field = value
            reset()
        }
    /**
     * Determines whether surface shape should be batched to terrain textures cache or single color attachment.
     * Set this flag to true when shape editing is intended and to false when editing is finished.
     */
    var isDynamic = false
    override var highlightAttributes: ShapeAttributes? = null
    override var isHighlighted = false
    var maximumIntermediatePoints = 10
    protected var isSurfaceShape = false
    protected var bufferDataVersion = 0L
    protected val boundingData = mutableMapOf<Globe.State?, BoundingData>()

    open class BoundingData {
        val boundingSector = Sector()
        val boundingBox = BoundingBox()
        var lastVE = 0.0
        var lastTimestamp = 0L
    }

    companion object {
        const val NEAR_ZERO_THRESHOLD = 1.0e-10
        private const val ZERO_LEVEL_PX = 1024
        @JvmStatic
        protected val defaultInteriorImageOptions = ImageOptions().apply { wrapMode = WrapMode.REPEAT }
        @JvmStatic
        protected val defaultOutlineImageOptions = ImageOptions().apply {
            wrapMode = WrapMode.REPEAT
            resamplingMode = ResamplingMode.NEAREST_NEIGHBOR
        }
        @JvmStatic
        protected lateinit var currentBoundindData: BoundingData
        @JvmStatic
        protected lateinit var activeAttributes: ShapeAttributes
        @JvmStatic
        protected val pickColor = Color()
        @JvmStatic
        protected val point = Vec3()
        @JvmStatic
        protected val vertPoint = Vec3()
    }

    override fun doRender(rc: RenderContext) {
    	// Get or create available bounding data for current Globe state
        currentBoundindData = boundingData[rc.globeState] ?: BoundingData().also { boundingData[rc.globeState] = it }

        // Don't render anything if the shape is not visible.
        if (!isWithinProjectionLimits(rc) || !intersectsFrustum(rc)) return

        // Adjust to terrain changes
        checkTerrainState(rc)

        // Select the currently active attributes.
        determineActiveAttributes(rc)

        // Keep track of the drawable count to determine whether this shape has enqueued drawables.
        val drawableCount = rc.drawableCount
        var pickedObjectId = 0
        if (rc.isPickMode) {
            pickedObjectId = rc.nextPickedObjectId()
            PickedObject.identifierToUniqueColor(pickedObjectId, pickColor)
        }

        // Determine whether the shape geometry must be assembled as Cartesian geometry or as geographic geometry.
        isSurfaceShape = rc.globe.is2D || altitudeMode == AltitudeMode.CLAMP_TO_GROUND && isFollowTerrain

        // Enqueue drawables for processing on the OpenGL thread.
        makeDrawable(rc)

        // Enqueue a picked object that associates the shape's drawables with its picked object ID.
        if (rc.isPickMode && rc.drawableCount != drawableCount) {
            rc.offerPickedObject(PickedObject.fromRenderable(pickedObjectId, this, rc.currentLayer))
        }
    }

    /**
     * Indicates whether this shape is within the current globe's projection limits. Subclasses may implement
     * this method to perform the test. The default implementation returns true.
     * @param rc The current render context.
     * @returns true if this shape is within or intersects the current globe's projection limits, otherwise false.
     */
    protected open fun isWithinProjectionLimits(rc: RenderContext) = true

    protected open fun intersectsFrustum(rc: RenderContext) = with(currentBoundindData) {
        (boundingBox.isUnitBox || boundingBox.intersectsFrustum(rc.frustum)) &&
                // This is a temporary solution. Surface shapes should also use bounding box.
                (boundingSector.isEmpty || boundingSector.intersects(rc.terrain.sector))
        }

    protected open fun determineActiveAttributes(rc: RenderContext) {
        val highlightAttributes = highlightAttributes
        activeAttributes = if (isHighlighted && highlightAttributes != null) highlightAttributes else attributes
    }

    protected open fun cameraDistanceGeographic(rc: RenderContext, boundingSector: Sector): Double {
        val lat = rc.camera.position.latitude.coerceIn(boundingSector.minLatitude, boundingSector.maxLatitude)
        val lon = rc.camera.position.longitude.coerceIn(boundingSector.minLongitude, boundingSector.maxLongitude)
        val point = rc.geographicToCartesian(lat, lon, 0.0, AltitudeMode.CLAMP_TO_GROUND, point)
        return point.distanceTo(rc.cameraPoint)
    }

    protected open fun cameraDistanceCartesian(rc: RenderContext, array: FloatArray, count: Int, stride: Int, offset: Vec3): Double {
        val cx = rc.cameraPoint.x - offset.x
        val cy = rc.cameraPoint.y - offset.y
        val cz = rc.cameraPoint.z - offset.z
        var minDistance2 = Double.POSITIVE_INFINITY
        for (idx in 0 until count step stride) {
            val px = array[idx]
            val py = array[idx + 1]
            val pz = array[idx + 2]
            val dx = px - cx
            val dy = py - cy
            val dz = pz - cz
            val distance2 = dx * dx + dy * dy + dz * dz
            if (minDistance2 > distance2) minDistance2 = distance2
        }
        return sqrt(minDistance2)
    }

    protected open fun computeRepeatingTexCoordTransform(
        rc: RenderContext, texture: Texture, cameraDistance: Double, result: Matrix3
    ): Int {
        var lod = 0
        val equatorialRadius = rc.globe.equatorialRadius
        var metersPerPixel = rc.pixelSizeAtDistance(cameraDistance)
        if (isSurfaceShape && !isDynamic && !rc.currentLayer.isDynamic) {
            // Round scale to nearest terrain LoD
            lod = computeNearestLoD(equatorialRadius, metersPerPixel)
            metersPerPixel = computeLoDScale(equatorialRadius, lod)
        }
        val texCoordMatrix = result.setToIdentity()
        texCoordMatrix.setScale(1.0 / (texture.width * metersPerPixel), 1.0 / (texture.height * metersPerPixel))
        texCoordMatrix.multiplyByMatrix(texture.coordTransform)
        return lod
    }

    protected open fun computeNearestLoD(equatorialRadius: Double, scale: Double) =
        log2(2.0 * PI * equatorialRadius / ZERO_LEVEL_PX / scale).roundToInt()

    protected open fun computeLoDScale(equatorialRadius: Double, lod: Int) =
        2.0 * PI * equatorialRadius / ZERO_LEVEL_PX / (1 shl lod)

    protected open fun computeVersion() = 31 * hashCode() + bufferDataVersion.hashCode()

    protected open fun checkTerrainState(rc: RenderContext) = with(currentBoundindData) {
        val ve = rc.globe.verticalExaggeration
        val timestamp = rc.elevationModelTimestamp
        val isTerrainDependent = altitudeMode == AltitudeMode.CLAMP_TO_GROUND || altitudeMode == AltitudeMode.RELATIVE_TO_GROUND
        if (isTerrainDependent && !isSurfaceShape && (ve != lastVE || timestamp != lastTimestamp)) {
            resetGlobeState(rc.globeState)
            lastVE = ve
            lastTimestamp = timestamp
        }
    }

    protected open fun resetGlobeState(globeState: Globe.State?) {
        boundingData[globeState]?.let {
            it.boundingSector.setEmpty()
            it.boundingBox.setToUnitBox()
        }
        ++bufferDataVersion
    }

    protected open fun reset() {
        boundingData.clear()
        ++bufferDataVersion
    }

    protected open fun calcPoint(
        rc: RenderContext, latitude: Angle, longitude: Angle, altitude: Double,
        isAbsolute: Boolean = false, isExtrudedSkirt: Boolean = isExtrude
    ) {
        val baseAltitudeMode = if (isSurfaceShape) AltitudeMode.ABSOLUTE else altitudeMode
        val topAltitudeMode = if (isAbsolute) AltitudeMode.ABSOLUTE else baseAltitudeMode
        rc.geographicToCartesian(latitude, longitude, altitude, topAltitudeMode, point, useEM = true)
        if (isExtrudedSkirt && !isSurfaceShape) {
            if (altitude == baseAltitude) vertPoint.copy(point)
            else rc.geographicToCartesian(latitude, longitude, baseAltitude, baseAltitudeMode, vertPoint, useEM = true)
        }
    }

    protected abstract fun makeDrawable(rc: RenderContext)
}