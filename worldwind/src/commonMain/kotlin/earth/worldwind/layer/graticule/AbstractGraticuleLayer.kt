package earth.worldwind.layer.graticule

import earth.worldwind.geom.*
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Angle.Companion.toDegrees
import earth.worldwind.geom.Angle.Companion.toRadians
import earth.worldwind.globe.Globe
import earth.worldwind.layer.AbstractLayer
import earth.worldwind.render.Color
import earth.worldwind.render.Font
import earth.worldwind.render.RenderContext
import earth.worldwind.render.Renderable
import earth.worldwind.shape.Label
import earth.worldwind.shape.Path
import earth.worldwind.shape.PathType
import kotlin.math.abs
import kotlin.math.sign

/**
 * Displays a graticule.
 */
abstract class AbstractGraticuleLayer(name: String): AbstractLayer(name) {
    override var isPickEnabled = false
    private val graticuleSupport = GraticuleSupport()
    private val surfacePoint = Vec3()
    private val lastCameraPoint = Vec3()
    private var lastCameraHeading = 0.0
    private var lastCameraTilt = 0.0
    private var lastFOV = 0.0
    private var lastVerticalExaggeration = 0.0
    private var lastGlobeState: Globe.State? = null
    private var lastGlobeOffset: Globe.Offset? = null

    init {
        this.initRenderingParams()
    }

    protected abstract fun initRenderingParams()

    /**
     * Returns whether graticule lines will be rendered.
     *
     * @param key the rendering parameters key.
     *
     * @return true if graticule lines will be rendered; false otherwise.
     */
    fun isDrawGraticule(key: String) = getRenderingParams(key).isDrawLines

    /**
     * Sets whether graticule lines will be rendered.
     *
     * @param drawGraticule true to render graticule lines; false to disable rendering.
     * @param key           the rendering parameters key.
     */
    fun setDrawGraticule(drawGraticule: Boolean, key: String) { getRenderingParams(key).isDrawLines = drawGraticule }

    /**
     * Returns the graticule line Color.
     *
     * @param key the rendering parameters key.
     *
     * @return Color used to render graticule lines.
     */
    fun getGraticuleLineColor(key: String) = getRenderingParams(key).lineColor

    /**
     * Sets the graticule line Color.
     *
     * @param color Color that will be used to render graticule lines.
     * @param key   the rendering parameters key.
     */
    fun setGraticuleLineColor(color: Color, key: String) { getRenderingParams(key).lineColor = color }

    /**
     * Returns the graticule line width.
     *
     * @param key the rendering parameters key.
     *
     * @return width of the graticule lines.
     */
    fun getGraticuleLineWidth(key: String) = getRenderingParams(key).lineWidth

    /**
     * Sets the graticule line width.
     *
     * @param lineWidth width of the graticule lines.
     * @param key       the rendering parameters key.
     */
    fun setGraticuleLineWidth(lineWidth: Float, key: String) { getRenderingParams(key).lineWidth = lineWidth }

    /**
     * Returns the graticule line rendering style.
     *
     * @param key the rendering parameters key.
     *
     * @return rendering style of the graticule lines.
     */
    fun getGraticuleLineStyle(key: String) = getRenderingParams(key).lineStyle

    /**
     * Sets the graticule line rendering style.
     *
     * @param lineStyle rendering style of the graticule lines.
     * One of [LineStyle.SOLID], [LineStyle.DASHED], [LineStyle.DOTTED] or [LineStyle.DASH_DOTTED].
     * @param key the rendering parameters key.
     */
    fun setGraticuleLineStyle(lineStyle: LineStyle, key: String) { getRenderingParams(key).lineStyle = lineStyle }

    /**
     * Returns whether graticule labels will be rendered.
     *
     * @param key the rendering parameters key.
     *
     * @return true if graticule labels will be rendered; false otherwise.
     */
    fun isDrawLabels(key: String) = getRenderingParams(key).isDrawLabels

    /**
     * Sets whether graticule labels will be rendered.
     *
     * @param drawLabels true to render graticule labels; false to disable rendering.
     * @param key        the rendering parameters key.
     */
    fun setDrawLabels(drawLabels: Boolean, key: String) { getRenderingParams(key).isDrawLabels = drawLabels }

    /**
     * Returns the graticule label Color.
     *
     * @param key the rendering parameters key.
     *
     * @return Color used to render graticule labels.
     */
    fun getLabelColor(key: String) = getRenderingParams(key).labelColor

    /**
     * Sets the graticule label Color.
     *
     * @param color Color that will be used to render graticule labels.
     * @param key   the rendering parameters key.
     */
    fun setLabelColor(color: Color, key: String) { getRenderingParams(key).labelColor = color }

    /**
     * Returns the Font used for graticule labels.
     *
     * @param key the rendering parameters key.
     *
     * @return Font used to render graticule labels.
     */
    fun getLabelFont(key: String) = getRenderingParams(key).labelFont

    /**
     * Sets the Font used for graticule labels.
     *
     * @param font Font that will be used to render graticule labels.
     * @param key  the rendering parameters key.
     */
    fun setLabelFont(font: Font, key: String) { getRenderingParams(key).labelFont = font }

    fun getRenderingParams(key: String) = graticuleSupport.getRenderingParams(key)

    fun setRenderingParams(key: String, renderingParams: GraticuleRenderingParams) {
        graticuleSupport.setRenderingParams(key, renderingParams)
    }

    fun addRenderable(renderable: Renderable, paramsKey: String) { graticuleSupport.addRenderable(renderable, paramsKey) }

    private fun removeAllRenderables() { graticuleSupport.removeAllRenderables() }

    public override fun doRender(rc: RenderContext) {
        if (needsToUpdate(rc)) {
            clear(rc)
            selectRenderables(rc)
        } else if (rc.globe.offset != lastGlobeOffset) {
            // Continue selecting renderables for additional globe offsets.
            selectRenderables(rc)
        }
        lastGlobeOffset = rc.globe.offset

        // Render
        graticuleSupport.render(rc, opacity)
    }

    /**
     * Select the visible grid elements
     *
     * @param rc the current `RenderContext`.
     */
    protected abstract fun selectRenderables(rc: RenderContext)
    protected abstract val orderedTypes: List<String>
    abstract fun getTypeFor(resolution: Double): String

    /**
     * Determines whether the grid should be updated. It returns true if:   * the eye has moved more than 1% of its
     * altitude above ground * the view FOV, heading or pitch have changed more than 1 degree  * vertical
     * exaggeration has changed  `RenderContext`.
     *
     * @return true if the graticule should be updated.
     */
    private fun needsToUpdate(rc: RenderContext): Boolean {
        if (lastVerticalExaggeration != rc.verticalExaggeration) return true
        if (abs(lastCameraHeading - rc.camera.heading.inDegrees) > 1) return true
        if (abs(lastCameraTilt - rc.camera.tilt.inDegrees) > 1) return true
        if (abs(lastFOV - rc.camera.fieldOfView.inDegrees) > 1) return true
        if (rc.cameraPoint.distanceTo(lastCameraPoint) > computeAltitudeAboveGround(rc) / 100) return true
        if (rc.globeState != lastGlobeState) return true
        return false
    }

    protected open fun clear(rc: RenderContext) {
        removeAllRenderables()
        lastCameraPoint.copy(rc.cameraPoint)
        lastFOV = rc.camera.fieldOfView.inDegrees
        lastCameraHeading = rc.camera.heading.inDegrees
        lastCameraTilt = rc.camera.tilt.inDegrees
        lastVerticalExaggeration = rc.verticalExaggeration
        lastGlobeState = rc.globeState
    }

    fun computeLabelOffset(rc: RenderContext): Location {
        return rc.lookAtPosition?.let {
            val labelOffset = toDegrees(rc.pixelSize / rc.globe.equatorialRadius * rc.viewport.width / 4)
            Location(
                it.latitude.minusDegrees(labelOffset).normalizeLatitude().coerceIn(MIN_LAT, MAX_LAT),
                it.longitude.minusDegrees(labelOffset).normalizeLongitude()
            )
        } ?: rc.camera.position
    }

    fun createLineRenderable(positions: List<Position>, pathType: PathType) = Path(positions).apply {
        this.pathType = pathType
        altitudeMode = AltitudeMode.CLAMP_TO_GROUND
        isFollowTerrain = true
    }

    @Suppress("UNUSED_PARAMETER")
    fun createTextRenderable(position: Position, label: String, resolution: Double) = Label(position, label).apply {
        altitudeMode = AltitudeMode.CLAMP_TO_GROUND
        // priority = resolution * 1e6 // TODO Implement priority
    }

    fun getSurfacePoint(rc: RenderContext, latitude: Angle, longitude: Angle): Vec3 {
        if (!rc.terrain.surfacePoint(latitude, longitude, surfacePoint))
            rc.globe.geographicToCartesian(
                latitude, longitude, rc.globe.getElevation(latitude, longitude)
                        * rc.verticalExaggeration, surfacePoint
            )
        return surfacePoint
    }

    fun computeAltitudeAboveGround(rc: RenderContext): Double {
        val surfacePoint = getSurfacePoint(rc, rc.camera.position.latitude, rc.camera.position.longitude)
        return rc.cameraPoint.distanceTo(surfacePoint)
    }

    fun computeTruncatedSegment(p1: Position, p2: Position, sector: Sector, positions: MutableList<Position>) {
        val p1In = sector.contains(p1.latitude, p1.longitude)
        val p2In = sector.contains(p2.latitude, p2.longitude)
        if (!p1In && !p2In) return  // the whole segment is (likely) outside
        if (p1In && p2In) {
            // the whole segment is (likely) inside
            positions.add(p1)
            positions.add(p2)
        } else {
            // segment does cross the boundary
            var outPoint = if (!p1In) p1 else p2
            val inPoint = if (p1In) p1 else p2
            for (i in 1..2) {
                // there may be two intersections
                var intersection: Location? = null
                if (outPoint.longitude.inDegrees > sector.maxLongitude.inDegrees
                    || sector.maxLongitude.inDegrees == 180.0 && outPoint.longitude.inDegrees < 0.0) {
                    // intersect with east meridian
                    intersection = greatCircleIntersectionAtLongitude(
                        inPoint, outPoint, sector.maxLongitude
                    )
                } else if (outPoint.longitude.inDegrees < sector.minLongitude.inDegrees
                    || sector.minLongitude.inDegrees == -180.0 && outPoint.longitude.inDegrees > 0.0) {
                    // intersect with west meridian
                    intersection = greatCircleIntersectionAtLongitude(
                        inPoint, outPoint, sector.minLongitude
                    )
                } else if (outPoint.latitude.inDegrees > sector.maxLatitude.inDegrees) {
                    // intersect with top parallel
                    intersection = greatCircleIntersectionAtLatitude(
                        inPoint, outPoint, sector.maxLatitude
                    )
                } else if (outPoint.latitude.inDegrees < sector.minLatitude.inDegrees) {
                    // intersect with bottom parallel
                    intersection = greatCircleIntersectionAtLatitude(
                        inPoint, outPoint, sector.minLatitude
                    )
                }
                outPoint = if (intersection != null) Position(
                    intersection.latitude,
                    intersection.longitude,
                    outPoint.altitude
                ) else break
            }
            positions.add(inPoint)
            positions.add(outPoint)
        }
    }

    /**
     * Computes the intersection point position between a great circle segment and a meridian.
     *
     * @param p1        the great circle segment start position.
     * @param p2        the great circle segment end position.
     * @param longitude the meridian longitude `Angle`
     *
     * @return the intersection `Position` or null if there was no intersection found.
     */
    private fun greatCircleIntersectionAtLongitude(p1: Location, p2: Location, longitude: Angle): Location? {
        if (p1.longitude == longitude) return p1
        if (p2.longitude == longitude) return p2
        var pos: Location? = null
        val deltaLon = getDeltaLongitude(p1, p2.longitude)
        if (getDeltaLongitude(p1, longitude) < deltaLon && getDeltaLongitude(p2, longitude) < deltaLon) {
            var count = 0
            val precision = 1.0 / Ellipsoid.WGS84.semiMajorAxis // 1m angle in radians
            var a = p1
            var b = p2
            var midPoint = greatCircleMidPoint(a, b)
            while (toRadians(getDeltaLongitude(midPoint, longitude)) > precision && count <= 20) {
                count++
                if (getDeltaLongitude(a, longitude) < getDeltaLongitude(b, longitude)) b = midPoint else a = midPoint
                midPoint = greatCircleMidPoint(a, b)
            }
            pos = midPoint
        }
        // Adjust final longitude for an exact match
        if (pos != null) pos = Location(pos.latitude, longitude)
        return pos
    }

    /**
     * Computes the intersection point position between a great circle segment and a parallel.
     *
     * @param p1       the great circle segment start position.
     * @param p2       the great circle segment end position.
     * @param latitude the parallel latitude `Angle`
     *
     * @return the intersection `Position` or null if there was no intersection found.
     */
    private fun greatCircleIntersectionAtLatitude(p1: Location, p2: Location, latitude: Angle): Location? {
        var pos: Location? = null
        if (sign(p1.latitude.inDegrees - latitude.inDegrees) != sign(p2.latitude.inDegrees - latitude.inDegrees)) {
            var count = 0
            val precision = 1.0 / Ellipsoid.WGS84.semiMajorAxis // 1m angle in radians
            var a = p1
            var b = p2
            var midPoint = greatCircleMidPoint(a, b)
            while (abs(midPoint.latitude.inRadians - latitude.inRadians) > precision && count <= 20) {
                count++
                if (sign(a.latitude.inDegrees - latitude.inDegrees) != sign(midPoint.latitude.inDegrees - latitude.inDegrees))
                    b = midPoint else a = midPoint
                midPoint = greatCircleMidPoint(a, b)
            }
            pos = midPoint
        }
        // Adjust final latitude for an exact match
        if (pos != null) pos = Location(latitude, pos.longitude)
        return pos
    }

    private fun greatCircleMidPoint(p1: Location, p2: Location): Location {
        val azimuth = p1.greatCircleAzimuth(p2)
        val distance = p1.greatCircleDistance(p2)
        return p1.greatCircleLocation(azimuth, distance / 2, Location())
    }

    private fun getDeltaLongitude(p1: Location, longitude: Angle): Double {
        val deltaLon = abs(p1.longitude.inDegrees - longitude.inDegrees)
        return if (deltaLon < 180) deltaLon else 360 - deltaLon
    }

    companion object {
        private val MIN_LAT = (-70.0).degrees
        private val MAX_LAT = 70.0.degrees
    }
}