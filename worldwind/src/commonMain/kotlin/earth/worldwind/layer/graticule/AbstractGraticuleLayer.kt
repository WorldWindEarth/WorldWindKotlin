package earth.worldwind.layer.graticule

import earth.worldwind.geom.*
import earth.worldwind.geom.Angle.Companion.ZERO
import earth.worldwind.geom.Angle.Companion.normalizeLatitude
import earth.worldwind.geom.Angle.Companion.normalizeLongitude
import earth.worldwind.geom.Angle.Companion.toDegrees
import earth.worldwind.geom.Angle.Companion.toRadians
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

    // Helper variables to avoid memory leaks
    private val surfacePoint = Vec3()
    private val forwardRay = Line()
    private val lookAtPoint = Vec3()
    private val lookAtPos = Position()
    private val graticuleSupport = GraticuleSupport()

    // Update reference states
    private val lastCameraPoint = Vec3()
    private var lastCameraHeading = 0.0
    private var lastCameraTilt = 0.0
    private var lastFOV = 0.0
    private var lastVerticalExaggeration = 0.0

//    private var lastGlobe: Globe? = null
//    private var lastProjection: GeographicProjection? = null
//    private var frameTimeStamp = Instant.DISTANT_PAST // used only for 2D continuous globes to determine whether render is in same frame

    companion object {
        private const val LOOK_AT_LATITUDE_PROPERTY = "look_at_latitude"
        private const val LOOK_AT_LONGITUDE_PROPERTY = "look_at_longitude"
        private const val GRATICULE_PIXEL_SIZE_PROPERTY = "graticule_pixel_size"
        private const val GRATICULE_LABEL_OFFSET_PROPERTY = "graticule_label_offset"
    }

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
//        if (rc.isContinuous2DGlobe) {
//            if (needsToUpdate(rc)) {
//                clear(rc)
//                selectRenderables(rc)
//            }
//
//            // If the frame time stamp is the same, then this is the second or third pass of the same frame. We continue
//            // selecting renderables in these passes.
//            if (rc.frameTimeStamp === frameTimeStamp) selectRenderables(rc)
//
//            frameTimeStamp = rc.frameTimeStamp
//        } else {
        if (needsToUpdate(rc)) {
            clear(rc)
            selectRenderables(rc)
        }
//        }

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
     * altitude above ground  * the view FOV, heading or pitch have changed more than 1 degree  * vertical
     * exaggeration has changed  `RenderContext`.
     *
     * @return true if the graticule should be updated.
     */
    private fun needsToUpdate(rc: RenderContext): Boolean {
        if (lastVerticalExaggeration != rc.verticalExaggeration) return true
        if (abs(lastCameraHeading - rc.camera.heading.inDegrees) > 1) return true
        if (abs(lastCameraTilt - rc.camera.tilt.inDegrees) > 1) return true
        if (abs(lastFOV - rc.camera.fieldOfView.inDegrees) > 1) return true
        return rc.cameraPoint.distanceTo(lastCameraPoint) > computeAltitudeAboveGround(rc) / 100

        // We must test the globe and its projection to see if either changed. We can't simply use the globe state
        // key for this because we don't want a 2D globe offset change to cause an update. Offset changes don't
        // invalidate the current set of renderables.
//        if (rc.globe != lastGlobe) return true
//        if (rc.is2DGlobe) if ((rc.globe as Globe2D).projection != lastProjection) return true
    }

    protected open fun clear(rc: RenderContext) {
        removeAllRenderables()
        lastCameraPoint.copy(rc.cameraPoint)
        lastFOV = rc.camera.fieldOfView.inDegrees
        lastCameraHeading = rc.camera.heading.inDegrees
        lastCameraTilt = rc.camera.tilt.inDegrees
        lastVerticalExaggeration = rc.verticalExaggeration
//        lastGlobe = rc.globe
//        if (rc.is2DGlobe) lastProjection = (rc.globe as Globe2D).projection
    }

    fun computeLabelOffset(rc: RenderContext): Location {
        return if (hasLookAtPos(rc)) {
            val labelOffsetDegrees = getLabelOffset(rc)
            val labelPos = Location(
                getLookAtLatitude(rc).minusDegrees(labelOffsetDegrees),
                getLookAtLongitude(rc).minusDegrees(labelOffsetDegrees)
            )
            labelPos.setDegrees(
                normalizeLatitude(labelPos.latitude.inDegrees).coerceIn(-70.0, 70.0),
                normalizeLongitude(labelPos.longitude.inDegrees)
            )
            labelPos
        } else rc.camera.position
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

    fun hasLookAtPos(rc: RenderContext): Boolean {
        calculateLookAtProperties(rc)
        return rc.hasUserProperty(LOOK_AT_LATITUDE_PROPERTY) && rc.hasUserProperty(LOOK_AT_LONGITUDE_PROPERTY)
    }

    fun getLookAtLatitude(rc: RenderContext): Angle {
        calculateLookAtProperties(rc)
        return rc.getUserProperty(LOOK_AT_LATITUDE_PROPERTY) ?: ZERO
    }

    fun getLookAtLongitude(rc: RenderContext): Angle {
        calculateLookAtProperties(rc)
        return rc.getUserProperty(LOOK_AT_LONGITUDE_PROPERTY) ?: ZERO
    }

    fun getPixelSize(rc: RenderContext): Double {
        calculateLookAtProperties(rc)
        return rc.getUserProperty(GRATICULE_PIXEL_SIZE_PROPERTY) ?: 0.0
    }

    private fun getLabelOffset(rc: RenderContext): Double {
        calculateLookAtProperties(rc)
        return rc.getUserProperty(GRATICULE_LABEL_OFFSET_PROPERTY) ?: 0.0
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

    private fun calculateLookAtProperties(rc: RenderContext) {
        if (!rc.hasUserProperty(LOOK_AT_LATITUDE_PROPERTY) || !rc.hasUserProperty(LOOK_AT_LONGITUDE_PROPERTY)) {
            //rc.modelview.extractEyePoint(forwardRay.origin)
            forwardRay.origin.copy(rc.cameraPoint)
            rc.modelview.extractForwardVector(forwardRay.direction)
            val range = if (rc.terrain.intersect(forwardRay, lookAtPoint)) {
                rc.globe.cartesianToGeographic(lookAtPoint.x, lookAtPoint.y, lookAtPoint.z, lookAtPos)
                rc.putUserProperty(LOOK_AT_LATITUDE_PROPERTY, lookAtPos.latitude)
                rc.putUserProperty(LOOK_AT_LONGITUDE_PROPERTY, lookAtPos.longitude)
                lookAtPoint.distanceTo(rc.cameraPoint)
            } else {
                rc.removeUserProperty(LOOK_AT_LATITUDE_PROPERTY)
                rc.removeUserProperty(LOOK_AT_LONGITUDE_PROPERTY)
                rc.horizonDistance
            }
            val pixelSizeMeters = rc.pixelSizeAtDistance(range)
            rc.putUserProperty(GRATICULE_PIXEL_SIZE_PROPERTY, pixelSizeMeters)
            val pixelSizeDegrees = toDegrees(pixelSizeMeters / rc.globe.equatorialRadius)
            rc.putUserProperty(
                GRATICULE_LABEL_OFFSET_PROPERTY, pixelSizeDegrees * rc.viewport.width / 4
            )
        }
    }
}