package earth.worldwind.shape

import earth.worldwind.PickedObject
import earth.worldwind.geom.*
import earth.worldwind.geom.Angle.Companion.degrees
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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Base class for all geometric shapes.
 *
 * **Threading contract.** Geometry assembly (`makeDrawable` / `assembleGeometry` and everything
 * they call) is **single-threaded**. The companion-scoped scratch state — `point`, `vertPoint`,
 * `currentBoundindData`, `activeAttributes`, the per-shape `currentData`, and the index/cursor
 * statics in subclasses (`vertexIndex`, `lineVertexIndex`, etc.) — is shared across instances
 * and is only safe under that single-threaded assumption. Calling assembly from multiple threads
 * concurrently corrupts these scratches; calling assembly recursively (subclass code that
 * triggers another shape's assembly mid-flight) corrupts them too.
 *
 * In practice the render thread drives one shape's assembly to completion before starting the
 * next, so this is fine. If parallel assembly ever becomes desirable, the scratches need to
 * move from companion fields to a thread-local or a passed-through `RenderState` parameter.
 */
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
    /**
     * Optional lambda to control current shape visibility based on its attributes and frame render context
     */
    var isVisible: ((AbstractShape, RenderContext) -> Boolean)? = null
    override var highlightAttributes: ShapeAttributes? = null
    override var isHighlighted = false
    /**
     * Maximum number of intermediate edge intervals across a half-equator (PI radians) of arc.
     * Per-edge subdivision count is scaled by edge length: `steps = round(maximumNumEdgeIntervals * distanceRadians / PI)`,
     * then further adjusted by [polarThrottle] near the poles for surface shapes. 3D shapes use
     * the unthrottled count. A value of 0 disables intermediate vertex generation.
     *
     * Default 64 gives ~10 intermediates on a 30° edge, fewer on shorter edges, more on longer
     * ones. Increase for smoother long-edge great-circles, decrease for less assembly work in
     * dense scenes.
     */
    var maximumNumEdgeIntervals = 64
        set(value) {
            field = value
            reset()
        }
    /**
     * Pole-throttle factor: higher values shrink the subdivision step near ±90° latitude so the
     * straight 2D lon/lat segments between samples more closely follow the true great-circle path.
     * A value of 0 disables throttling (uniform step).
     */
    var polarThrottle = 10.0
        set(value) {
            field = value
            reset()
        }
    protected var isSurfaceShape = false
    // True when the shape is off-camera but inside a sightline volume; subclasses propagate
    // this onto every DrawShapeState in makeDrawable so draw() skips while drawSightlineDepth runs.
    protected var isOccluderOnly = false
    // Monotonic counter advanced by [assembleGeometry] each time it emits fresh vertex data;
    // gates the [offerGLBufferUpload] writes. Advancing here (not in [reset]) lets [prepareGeometry]
    // fall through with the prior GL buffer when the per-frame assembly budget is exhausted —
    // the version doesn't move, no re-upload happens, last frame's geometry stays on screen.
    protected var bufferDataVersion = 0
    protected val boundingData = mutableMapOf<Globe.State?, BoundingData>()
    // Single-entry cache for the most-recently-used (Globe.State, BoundingData) pair. Most
    // applications render against a single Globe.State, so the map lookup in [doRender] resolves
    // to the same entry every frame; checking the cache first turns it into a field read +
    // identity compare. Invalidated on [reset] (which clears [boundingData]).
    private var lastBoundingDataState: Globe.State? = null
    private var lastBoundingData: BoundingData? = null

    open class BoundingData {
        val boundingSector = Sector()
        val boundingBox = BoundingBox()
        var lastVE = 0.0
        var lastTimestamp = 0L
        var crossesAntimeridian = false
        val additionalSector = Sector()

        /**
         * Splits the vertex array's absolute longitudes at 0° into an east sector (stored in
         * [boundingSector]) and a west sector (stored in [additionalSector]), and sets
         * [crossesAntimeridian] = true. Pass [normalizeLon] = true for shapes that store
         * normalized offsets whose sum with the origin may fall outside [-180°, 180°].
         */
        fun computeAntimeridianSectors(
            vertexArray: FloatArray, count: Int, stride: Int, origin: Vec3, normalizeLon: Boolean = false
        ) {
            var eastMinLat = Double.MAX_VALUE; var eastMaxLat = -Double.MAX_VALUE
            var eastMinLon = Double.MAX_VALUE; var eastMaxLon = -Double.MAX_VALUE
            var westMinLat = Double.MAX_VALUE; var westMaxLat = -Double.MAX_VALUE
            var westMinLon = Double.MAX_VALUE; var westMaxLon = -Double.MAX_VALUE
            var i = 0
            while (i < count) {
                var lon = vertexArray[i].toDouble() + origin.x
                if (normalizeLon) {
                    if (lon > 180.0) lon -= 360.0 else if (lon < -180.0) lon += 360.0
                }
                val lat = vertexArray[i + 1].toDouble() + origin.y
                if (lon >= 0) {
                    if (lat < eastMinLat) eastMinLat = lat; if (lat > eastMaxLat) eastMaxLat = lat
                    if (lon < eastMinLon) eastMinLon = lon; if (lon > eastMaxLon) eastMaxLon = lon
                } else {
                    if (lat < westMinLat) westMinLat = lat; if (lat > westMaxLat) westMaxLat = lat
                    if (lon < westMinLon) westMinLon = lon; if (lon > westMaxLon) westMaxLon = lon
                }
                i += stride
            }
            crossesAntimeridian = true
            if (eastMinLat <= eastMaxLat) {
                boundingSector.minLatitude = eastMinLat.degrees; boundingSector.maxLatitude = eastMaxLat.degrees
                boundingSector.minLongitude = eastMinLon.degrees; boundingSector.maxLongitude = eastMaxLon.degrees
            } else boundingSector.setEmpty()
            if (westMinLat <= westMaxLat) {
                additionalSector.minLatitude = westMinLat.degrees; additionalSector.maxLatitude = westMaxLat.degrees
                additionalSector.minLongitude = westMinLon.degrees; additionalSector.maxLongitude = westMaxLon.degrees
            } else additionalSector.setEmpty()
        }
    }

    companion object {
        const val NEAR_ZERO_THRESHOLD = 1.0e-10
        // Latitude threshold (degrees) above which densification needs the full polar-throttled
        // bound. Below it the throttle factor is moderate (≤ ~6) and the unthrottled length-scaled
        // bound is tight enough — avoids the ~11× over-allocation the absolute-pole bound would
        // imply. Used by Polygon / Path / Ellipse densifiers for ArrayList pre-sizing and
        // surface-splitter short-circuit checks.
        const val POLE_PROXIMITY_DEG = 75.0
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
        // Pre-encoded triangle-strip line miter corners — values of
        // [encodeOrientationVector](±1, ±1) hoisted out so Polygon/Path/Ellipse don't recompute
        // them four times per [addLineVertex] invocation.
        const val OUTLINE_CORNER_UL: Float = -0.5f  // encodeOrientationVector(-1, +1)
        const val OUTLINE_CORNER_LL: Float = -1.5f  // encodeOrientationVector(-1, -1)
        const val OUTLINE_CORNER_UR: Float = 1.5f   // encodeOrientationVector(+1, +1)
        const val OUTLINE_CORNER_LR: Float = 0.5f   // encodeOrientationVector(+1, -1)
    }

    open fun reset() {
        boundingData.clear()
        lastBoundingData = null
    }

    /** True when [assembleGeometry] has previously emitted vertex data into the current GL buffer. */
    protected abstract val hasGeometry: Boolean

    /** Returns true if the shape's per-globe-state vertex data is stale and needs reassembly. */
    protected abstract fun mustAssembleGeometry(rc: RenderContext): Boolean

    /** (Re)builds the shape's vertex data. Implementations must `++bufferDataVersion` to trigger upload. */
    protected abstract fun assembleGeometry(rc: RenderContext)

    /**
     * Reassembles geometry when needed and reports whether [makeDrawable] may continue. When the
     * per-frame assembly budget is exhausted the call falls through with the existing GL buffer
     * if one exists — last frame's geometry stays on screen, avoiding visible blink on bulk
     * dynamic updates. Returns false only on the first frame, before any successful assemble.
     */
    protected fun prepareGeometry(rc: RenderContext): Boolean {
        if (!mustAssembleGeometry(rc)) return true
        if (rc.canAssembleGeometry()) { assembleGeometry(rc); return true }
        return hasGeometry
    }

    /**
     * Returns an adaptive step size that shrinks as [latitude] approaches the poles. Used by
     * edge-subdivision loops to densify sampling where straight 2D lon/lat segments most
     * visibly diverge from the true great-circle path:
     * `dt * ((1 - w) + w * cos²(lat))` where `w = polarThrottle / (1 + polarThrottle)`.
     */
    protected fun throttledStep(dt: Double, latitude: Angle): Double {
        val cosLat = cos(latitude.inRadians)
        val cosLatSq = cosLat * cosLat
        val weight = polarThrottle / (1.0 + polarThrottle)
        return dt * ((1.0 - weight) + weight * cosLatSq)
    }

    /**
     * Worst-case upper bound on intermediate vertices emitted per edge, used for buffer pre-sizing.
     * Returns 0 when subdivision is disabled. The throttle can multiply iterations by up to
     * `(1 + polarThrottle)` at the poles, so the bound is
     * `ceil(maximumNumEdgeIntervals * (1 + polarThrottle))`.
     */
    protected fun maxIntermediatesPerEdge(): Int {
        if (maximumNumEdgeIntervals <= 0 || pathType == PathType.LINEAR) return 0
        return ((maximumNumEdgeIntervals * (1.0 + polarThrottle)) + 0.5).toInt()
    }

    /**
     * Per-shape upper bound on intermediates per edge for ArrayList pre-sizing on the surface
     * densification path. Walks [locations] once for max |latitude|; returns the polar-throttled
     * cap [maxIntermediatesPerEdge] only if any vertex exceeds [POLE_PROXIMITY_DEG], otherwise
     * the much tighter unthrottled `maximumNumEdgeIntervals - 1` bound. Avoids the ~11×
     * over-allocation the absolute-pole bound would imply for mid-latitude shapes.
     */
    protected fun perEdgeBoundForLocations(locations: List<Location>): Int {
        if (maximumNumEdgeIntervals <= 0 || pathType == PathType.LINEAR) return 0
        var maxAbsLat = 0.0
        for (p in locations) {
            val lat = abs(p.latitude.inDegrees)
            if (lat > maxAbsLat) {
                maxAbsLat = lat
                if (maxAbsLat > POLE_PROXIMITY_DEG) break
            }
        }
        return if (maxAbsLat > POLE_PROXIMITY_DEG) maxIntermediatesPerEdge()
        else maximumNumEdgeIntervals - 1
    }

    /**
     * Appends throttled intermediate locations along the edge from [begin] to [end] to [out],
     * without emitting either endpoint. Uses [pathType] and [maximumNumEdgeIntervals] to choose
     * the base step count and [throttledStep] to densify near the poles. Each emitted location
     * is a freshly allocated [Location] suitable for keeping (unlike the scratch-based loops
     * in Path / Polygon).
     */
    /**
     * Emits four triangle-strip line miter corners (UL, LL, UR, LR) of a single line vertex into
     * [array] starting at [idx]. Each corner shares the same `(x, y, z)` and `texCoord`, differing
     * only by the orientation tag baked into the four `OUTLINE_CORNER_*` constants. Returns the
     * new index after writing 4 × 5 = 20 floats. Replaces the verbatim 20-line corner-emit blocks
     * that used to appear in Polygon/Path/Ellipse for every line vertex (and four times each for
     * extruded vertical strokes). The JIT inlines tight per-call helpers like this.
     */
    protected fun emitLineCorners(
        array: FloatArray, idx: Int, x: Float, y: Float, z: Float, texCoord: Float
    ): Int {
        var i = idx
        array[i++] = x; array[i++] = y; array[i++] = z; array[i++] = OUTLINE_CORNER_UL; array[i++] = texCoord
        array[i++] = x; array[i++] = y; array[i++] = z; array[i++] = OUTLINE_CORNER_LL; array[i++] = texCoord
        array[i++] = x; array[i++] = y; array[i++] = z; array[i++] = OUTLINE_CORNER_UR; array[i++] = texCoord
        array[i++] = x; array[i++] = y; array[i++] = z; array[i++] = OUTLINE_CORNER_LR; array[i++] = texCoord
        return i
    }

    protected fun densifyEdge(begin: Location, end: Location, out: MutableList<Location>) {
        if (maximumNumEdgeIntervals <= 0) return
        val length = when (pathType) {
            PathType.GREAT_CIRCLE -> begin.greatCircleDistance(end)
            PathType.RHUMB_LINE -> begin.rhumbDistance(end)
            else -> return
        }
        if (length < NEAR_ZERO_THRESHOLD) return
        val steps = (maximumNumEdgeIntervals * length / PI).roundToInt()
        if (steps <= 0) return
        val dt = 1.0 / steps
        var currentLat = begin.latitude
        var t = throttledStep(dt, currentLat)
        while (t < 1.0) {
            val loc = Location()
            begin.interpolateAlongPath(end, pathType, t, loc)
            out.add(loc)
            currentLat = loc.latitude
            t += throttledStep(dt, currentLat)
        }
    }


    override fun doRender(rc: RenderContext) {
        // Resolve the per-Globe.State BoundingData. Fast path: identity-compare against the
        // single-entry cache (lastBoundingDataState/lastBoundingData) before hitting the map.
        // Most applications render against one Globe.State, so this is a HashMap.get → field
        // read for every shape per frame.
        val state = rc.globeState
        currentBoundindData = if (lastBoundingData != null && lastBoundingDataState === state) {
            lastBoundingData!!
        } else {
            (boundingData[state] ?: BoundingData().also { boundingData[state] = it }).also {
                lastBoundingDataState = state
                lastBoundingData = it
            }
        }

        if (!isWithinProjectionLimits(rc) || isVisible?.invoke(this, rc) == false) return

        // Off-camera shapes that fall inside an active sightline are kept alive as occluder-only
        // drawables so the sightline's depth pass still picks them up via SightlineOccluder.
        val cameraVisible = intersectsFrustum(rc)
        val sightlineCaster = !cameraVisible && intersectsAnySightlineBound(rc)
        if (!cameraVisible && !sightlineCaster) return
        isOccluderOnly = sightlineCaster

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
        // 3D shapes have a real Cartesian bounding box; check it against the view frustum.
        // Surface shapes leave the box as a unit box and use the lat/lon sector instead —
        // possibly two sectors when the shape crosses the antimeridian.
        if (boundingBox.isUnitBox) {
            boundingSector.isEmpty || boundingSector.intersects(rc.terrain.sector) ||
                crossesAntimeridian && additionalSector.intersects(rc.terrain.sector)
        } else {
            boundingBox.intersectsFrustum(rc.frustum)
        }
    }

    // Surface shapes (unit-box) are skipped: they don't represent occluding volumes.
    // The OBB is approximated by its outer sphere (box.center, box.radius) for a cheap
    // sphere-sphere reject; false positives only cost an occluder-only drawable.
    protected fun intersectsAnySightlineBound(rc: RenderContext): Boolean {
        if (rc.sightlineBounds.isEmpty()) return false
        val box = currentBoundindData.boundingBox
        if (box.isUnitBox) return false
        for (sphere in rc.sightlineBounds) {
            val dx = sphere.center.x - box.center.x
            val dy = sphere.center.y - box.center.y
            val dz = sphere.center.z - box.center.z
            val maxDist = sphere.radius + box.radius
            if (dx * dx + dy * dy + dz * dz <= maxDist * maxDist) return true
        }
        return false
    }

    protected open fun determineActiveAttributes(rc: RenderContext) {
        val highlightAttributes = highlightAttributes
        activeAttributes = if (isHighlighted && highlightAttributes != null) highlightAttributes else attributes
    }

    protected open fun cameraDistanceGeographic(rc: RenderContext, boundingSector: Sector): Double {
        val camPos = rc.camera.position
        val camLatDeg = camPos.latitude.inDegrees
        val lat = when {
            camLatDeg < boundingSector.minLatitude.inDegrees -> boundingSector.minLatitude
            camLatDeg > boundingSector.maxLatitude.inDegrees -> boundingSector.maxLatitude
            else -> camPos.latitude
        }
        val camLonDeg = camPos.longitude.inDegrees
        val lon = when {
            camLonDeg < boundingSector.minLongitude.inDegrees -> boundingSector.minLongitude
            camLonDeg > boundingSector.maxLongitude.inDegrees -> boundingSector.maxLongitude
            else -> camPos.longitude
        }
        val point = rc.geographicToCartesian(lat, lon, 0.0, AltitudeMode.CLAMP_TO_GROUND, point)
        return point.distanceTo(rc.cameraPoint)
    }

    protected fun cameraDistanceForTexture(rc: RenderContext, boundingSector: Sector) =
        if (activeAttributes.interiorImageSource != null || activeAttributes.outlineImageSource != null)
            cameraDistanceGeographic(rc, boundingSector) else 0.0

    protected fun sqrtCameraDistanceForTexture(cameraDistanceSq: Double) =
        if (activeAttributes.interiorImageSource != null || activeAttributes.outlineImageSource != null)
            sqrt(cameraDistanceSq) else 0.0

    protected open fun cameraDistanceSquared(rc: RenderContext, array: FloatArray, count: Int, stride: Int, offset: Vec3): Double {
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
        return minDistance2
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