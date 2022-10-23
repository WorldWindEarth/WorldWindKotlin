package earth.worldwind

import earth.worldwind.draw.DrawContext
import earth.worldwind.frame.BasicFrameController
import earth.worldwind.frame.Frame
import earth.worldwind.frame.FrameController
import earth.worldwind.frame.FrameMetrics
import earth.worldwind.geom.*
import earth.worldwind.geom.Angle.Companion.radians
import earth.worldwind.globe.Globe
import earth.worldwind.globe.projection.Wgs84Projection
import earth.worldwind.globe.terrain.BasicTessellator
import earth.worldwind.globe.terrain.Tessellator
import earth.worldwind.layer.LayerList
import earth.worldwind.render.RenderContext
import earth.worldwind.render.RenderResourceCache
import earth.worldwind.util.Logger
import earth.worldwind.util.kgl.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.datetime.TimeZone
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic
import kotlin.math.acos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Main WorldWind model, containing globe, terrain, renderable layers, camera, viewport and frame rendering logic.
 */
open class WorldWind @JvmOverloads constructor(
    /**
     * Platform-dependent OpenGL implementation
     */
    gl: Kgl,
    /**
     * Platform-dependent GPU resource cache manager.
     */
    var renderResourceCache: RenderResourceCache,
    /**
     * Planet or celestial object approximated by a reference ellipsoid and elevation models.
     */
    var globe: Globe = Globe(Ellipsoid.WGS84, Wgs84Projection()),
    /**
     * Terrain model tessellator.
     */
    var tessellator: Tessellator = BasicTessellator(),
    /**
     * Frame rendering and drawing logic implementation.
     */
    var frameController: FrameController = BasicFrameController(),
    /**
     * Helper class implementing [FrameMetrics] to measure performance.
     */
    var frameMetrics: FrameMetrics? = null
) {
    /**
     * List of renderable object layers to be displayed by this WorldWind.
     */
    var layers = LayerList()
    /**
     * Current user view point parameters: location, altitude, orientation and field of view.
     */
    var camera = Camera()
    /**
     * The [GoToAnimator] used by this WorldWindow to respond to its goTo method.
     */
    val goToAnimator = GoToAnimator(this, renderResourceCache.mainScope)
    /**
     * Screen area occupied by this WorldWind.
     */
    val viewport = Viewport()
    /**
     * Keep pixel scale when changing the height of viewport by adapting field of view
     */
    var isKeepScale = true
    /**
     * Scale of logical pixel size to hardware display pixel size. Used to adopt general level of details to screen density.
     */
    var densityFactor = 1f
        set(value) {
            require(value > 0) {
                Logger.logMessage(
                    Logger.ERROR, "WorldWind", "setDensityFactor", "invalidDensityFactor"
                )
            }
            field = value
        }
    /**
     * Vertical exaggeration (VE) is a scale that is used to emphasize vertical features, which might be too small
     * to identify relative to the horizontal scale.
     */
    var verticalExaggeration = 1.0
        set(value) {
            require(value > 0) {
                Logger.logMessage(
                    Logger.ERROR, "WorldWind", "setVerticalExaggeration", "invalidVerticalExaggeration"
                )
            }
            field = value
        }
    /**
     * Object altitude for horizon distance. Used to control when objects are clipped by the far plain behind the globe.
     */
    var atmosphereAltitude = 160000.0
    /**
     * Context related to frame rendering phase
     */
    protected val rc = RenderContext()
    /**
     * Context related to frame drawing phase
     */
    protected val dc = DrawContext(gl)
    /**
     * The number of bits in the depth buffer associated with this WorldWind.
     */
    protected var depthBits = 0
    private val scratchModelview = Matrix4()
    private val scratchProjection = Matrix4()
    private val scratchPoint = Vec3()
    private val scratchRay = Line()

    init {
        // Initialize default camera location based on user time zone
        val initLocation = Location.fromTimeZone(TimeZone.currentSystemDefault())
        // Fit globe to screen vertically with 10% margin.
        val initAltitude = distanceToViewGlobeExtents * 1.1
        camera.position.set(initLocation.latitude, initLocation.longitude, initAltitude)
    }

    /**
     * Reset internal WorldWind state to initial values.
     */
    open fun reset() {
        // Clear the render resource cache; it's entries are now invalid.
        renderResourceCache.clear()

        // Invalidate elevation model.
        globe.elevationModel.invalidate()

        // Clear the viewport dimensions.
        viewport.setEmpty()

        // Reset screen density factor.
        densityFactor = 1f
    }

    /**
     * Specify the default WorldWind OpenGL state.
     */
    open fun setupDrawContext() {
        dc.gl.enable(GL_BLEND)
        dc.gl.enable(GL_CULL_FACE)
        dc.gl.enable(GL_DEPTH_TEST)
        dc.gl.enableVertexAttribArray(0)
        dc.gl.disable(GL_DITHER)
        dc.gl.blendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
        dc.gl.depthFunc(GL_LEQUAL)

        // Clear any cached OpenGL resources and state, which are now invalid.
        dc.contextLost()

        // Set the WorldWindow's depth bits.
        depthBits = dc.gl.getParameteri(GL_DEPTH_BITS)
    }

    /**
     * Apply new viewport dimensions.
     */
    open fun setupViewport(width: Int, height: Int) {
        dc.gl.viewport(0, 0, width, height)

        // Keep pixel scale by adapting field of view on view port resize
        if (isKeepScale && viewport.height != 0) {
            try {
                camera.fieldOfView *= height / viewport.height.toDouble()
            } catch (ignore: IllegalArgumentException) {
                // Keep original field of view in case new one does not fit requirements
            }
        }

        viewport.set(0, 0, width, height)
    }

    /**
     * Get look at orientation and range based on current camera position and specified geographic position
     *
     * @param result Pre-allocated look at object
     * @param lookAtPos Geographic position to calculate look at orientation. Position at the viewport center by default.
     * @return Look at orientation and range based on current camera position and specified geographic position
     */
    @JvmOverloads
    open fun cameraAsLookAt(
        result: LookAt = LookAt(),
        lookAtPos: Position? = pickTerrainPosition(viewport.width / 2.0, viewport.height / 2.0)
    ): LookAt {
        cameraToViewingTransform(scratchModelview)
        if (lookAtPos != null) {
            // Use specified look at position
            globe.geographicToCartesian(lookAtPos.latitude, lookAtPos.longitude, lookAtPos.altitude, scratchPoint)
            result.position.copy(lookAtPos)
        } else {
            // No look at position specified - use point on horizon
            scratchModelview.extractEyePoint(scratchRay.origin)
            scratchModelview.extractForwardVector(scratchRay.direction)
            scratchRay.pointAt(globe.horizonDistance(camera.position.altitude), scratchPoint)
            globe.cartesianToGeographic(scratchPoint.x, scratchPoint.y, scratchPoint.z, result.position)
        }
        globe.cartesianToLocalTransform(scratchPoint.x, scratchPoint.y, scratchPoint.z, scratchProjection)
        scratchModelview.multiplyByMatrix(scratchProjection)
        result.range = -scratchModelview.m[11]
        result.heading = scratchModelview.extractHeading(camera.roll) // disambiguate heading and roll
        result.tilt = scratchModelview.extractTilt()
        result.roll = camera.roll // roll passes straight through
        return result
    }

    /**
     * Translate the original view's modelview matrix to account for the gesture's change.
     *
     * @param lookAt the look at position, orientation and range to be modified by translation.
     * @param fromPoint the start point of translation.
     * @param toPoint the finish point of translation.
     */
    open fun moveLookAt(lookAt: LookAt, fromPoint: Vec2, toPoint: Vec2) {
        // Convert screen points to points on the globe ellipsoid. Do not transform if any point is outside the globe.
        val from = Vec3()
        if (!rayThroughScreenPoint(fromPoint.x, fromPoint.y, scratchRay) || !globe.intersect(scratchRay, from)) return
        val to = Vec3()
        if (!rayThroughScreenPoint(toPoint.x, toPoint.y, scratchRay) || !globe.intersect(scratchRay, to)) return

        // Transform the original modelview matrix according to specified points.
        lookAtToViewingTransform(lookAt, scratchModelview)
        scratchModelview.multiplyByTranslation(to.x - from.x, to.y - from.y, to.z - from.z)

        // Compute the globe point at the screen center from the perspective of the transformed view.
        scratchModelview.extractEyePoint(scratchRay.origin)
        scratchModelview.extractForwardVector(scratchRay.direction)
        if (!globe.intersect(scratchRay, scratchPoint)) return
        globe.cartesianToGeographic(scratchPoint.x, scratchPoint.y, scratchPoint.z, lookAt.position)

        // Convert the transformed modelview matrix to view properties.
        globe.cartesianToLocalTransform(scratchPoint.x, scratchPoint.y, scratchPoint.z, scratchProjection)
        scratchModelview.multiplyByMatrix(scratchProjection)
        lookAt.range = -scratchModelview.m[11]
        lookAt.heading = scratchModelview.extractHeading(lookAt.roll) // disambiguate heading and roll
        lookAt.tilt = scratchModelview.extractTilt()
        lookAt.roll = lookAt.roll // roll passes straight through
    }

    /**
     * Set camera position and orientation, based on look at position, orientation and range
     *
     * @param lookAt Look at position, orientation and range
     */
    open fun cameraFromLookAt(lookAt: LookAt) {
        lookAtToViewingTransform(lookAt, scratchModelview)
        scratchModelview.extractEyePoint(scratchPoint)
        globe.cartesianToGeographic(scratchPoint.x, scratchPoint.y, scratchPoint.z, camera.position)
        globe.cartesianToLocalTransform(scratchPoint.x, scratchPoint.y, scratchPoint.z, scratchProjection)
        scratchModelview.multiplyByMatrix(scratchProjection)
        camera.altitudeMode = AltitudeMode.ABSOLUTE // Calculated position is absolute
        camera.heading = scratchModelview.extractHeading(lookAt.roll) // disambiguate heading and roll
        camera.tilt = scratchModelview.extractTilt()
        camera.roll = lookAt.roll // roll passes straight through

        // Check if camera altitude is not under the surface
        val position = camera.position
        if (position.altitude < COLLISION_CHECK_LIMIT * verticalExaggeration + COLLISION_THRESHOLD) {
            val elevation = globe.getElevationAtLocation(
                position.latitude, position.longitude
            ) * verticalExaggeration + COLLISION_THRESHOLD
            if (elevation > position.altitude) {
                // Set camera altitude above the surface
                position.altitude = elevation
                // Compute new camera point
                globe.geographicToCartesian(position.latitude, position.longitude, position.altitude, scratchPoint)
                // Compute look at point
                globe.geographicToCartesian(
                    lookAt.position.latitude,
                    lookAt.position.longitude,
                    lookAt.position.altitude,
                    scratchRay.origin
                )
                // Compute normal to globe in look at point
                globe.geographicToCartesianNormal(lookAt.position.latitude, lookAt.position.longitude, scratchRay.direction)
                // Calculate tilt angle between new camera point and look at point
                scratchPoint.subtract(scratchRay.origin).normalize()
                val dot = scratchRay.direction.dot(scratchPoint)
                if (dot >= -1 && dot <= 1) camera.tilt = acos(dot).radians
            }
        }
    }

    /**
     * More efficient way to determine terrain position at screen point using terrain from last rendered frame.
     *
     * @param x the screen point's X coordinate
     * @param y the screen point's Y coordinate
     * @param result a pre-allocated [Position] in which to store the computed geographic position
     *
     * @return a terrain [Position] at the screen point or null, if the screen point is not on the terrain
     */
    open fun pickTerrainPosition(x: Double, y: Double, result: Position = Position()) =
        if (rayThroughScreenPoint(x, y, scratchRay) && tessellator.lastTerrain.intersect(scratchRay, scratchPoint))
            globe.cartesianToGeographic(scratchPoint.x, scratchPoint.y, scratchPoint.z, result) else null

    /**
     * Transforms a Cartesian coordinate point to viewport coordinates.
     * <br>
     * This stores the converted point in the result argument, and returns a boolean value indicating whether the
     * converted is successful. This returns false if the Cartesian point is clipped by either the WorldWindow's near
     * clipping plane or far clipping plane.
     *
     * @param x      the Cartesian point's x component in meters
     * @param y      the Cartesian point's y component in meters
     * @param z      the Cartesian point's z component in meters
     * @param result a pre-allocated [Vec2] in which to return the screen point
     *
     * @return true if the transformation is successful, otherwise false
     */
    open fun cartesianToScreenPoint(x: Double, y: Double, z: Double, result: Vec2): Boolean {
        // Compute the WorldWindow's modelview-projection matrix.
        computeViewingTransform(scratchProjection, scratchModelview)
        scratchProjection.multiplyByMatrix(scratchModelview)

        // Transform the Cartesian point to OpenGL screen coordinates. Complete the transformation by converting to
        // viewport coordinates and discarding the screen Z component.
        if (scratchProjection.project(x, y, z, viewport, scratchPoint)) {
            result.x = scratchPoint.x
            result.y = viewport.height - scratchPoint.y
            return true
        }
        return false
    }

    /**
     * Transforms a geographic position to viewport coordinates.
     * <br>
     * This stores the converted point in the result argument, and returns a boolean value indicating whether the
     * converted is successful. This returns false if the Cartesian point is clipped by either of the WorldWindow's
     * near clipping plane or far clipping plane.
     *
     * @param latitude  the position's latitude
     * @param longitude the position's longitude
     * @param altitude  the position's altitude in meters
     * @param result    a pre-allocated [Vec2] in which to return the screen point
     *
     * @return true if the transformation is successful, otherwise false
     */
    open fun geographicToScreenPoint(
        latitude: Angle, longitude: Angle, altitude: Double, result: Vec2
    ): Boolean {
        // Convert the position from geographic coordinates to Cartesian coordinates.
        globe.geographicToCartesian(latitude, longitude, altitude, scratchPoint)

        // Convert the position from Cartesian coordinates to screen coordinates.
        return cartesianToScreenPoint(scratchPoint.x, scratchPoint.y, scratchPoint.z, result)
    }

    /**
     * Converts a screen point to the geographic coordinates on the globe ellipsoid, ignoring terrain altitude.
     *
     * @param x      the screen point's X coordinate
     * @param y      the screen point's Y coordinate
     * @param result Pre-allocated Position receives the geographic coordinates
     *
     * @return true if the screen point could be converted; false if the screen point is not on the globe
     */
    open fun screenPointToGroundPosition(x: Double, y: Double, result: Position) =
        if (rayThroughScreenPoint(x, y, scratchRay) && globe.intersect(scratchRay, scratchPoint)) {
            globe.cartesianToGeographic(scratchPoint.x, scratchPoint.y, scratchPoint.z, result)
            true
        } else false

    /**
     * Computes a Cartesian coordinate ray that passes through a screen point.
     *
     * @param x      the screen point's X coordinate
     * @param y      the screen point's Y coordinate
     * @param result a pre-allocated Line in which to return the computed ray
     *
     * @return the result set to the computed ray in Cartesian coordinates
     */
    open fun rayThroughScreenPoint(x: Double, y: Double, result: Line): Boolean {
        // Compute the inverse modelview-projection matrix corresponding to the WorldWindow's current Camera state.
        computeViewingTransform(scratchProjection, scratchModelview)
        scratchProjection.multiplyByMatrix(scratchModelview).invert()

        // Convert from viewport coordinates to OpenGL screen coordinates by inverting the Y axis.
        // Transform the screen point to Cartesian coordinates at the near and far clip planes, store the result in the
        // ray's origin and direction, respectively. Complete the ray direction by subtracting the near point from the
        // far point and normalizing.
        if (scratchProjection.unProject(x, viewport.height - y, viewport, result.origin, result.direction)) {
            result.direction.subtract(result.origin).normalize()
            return true
        }
        return false
    }

    /**
     * Returns the height of a pixel at a given distance from the eye point. This method assumes the model of a screen
     * composed of rectangular pixels, where pixel coordinates denote infinitely thin space between pixels. The units of
     * the returned size are in meters per pixel.
     * <br>
     * The result of this method is undefined if the distance is negative.
     *
     * @param distance the distance from the eye point in meters
     *
     * @return the pixel height in meters per pixel
     */
    open fun pixelSizeAtDistance(distance: Double): Double {
        val tanFovY2 = tan(camera.fieldOfView.radians * 0.5)
        val frustumHeight = 2 * distance * tanFovY2
        return frustumHeight / viewport.height
    }

    /**
     * Returns the minimum distance from the globe's surface necessary to make the globe's extents visible in this World
     * Window.
     */
    val distanceToViewGlobeExtents get(): Double {
        val sinFovY2 = sin(camera.fieldOfView.radians * 0.5)
        val radius = globe.equatorialRadius
        return radius / sinFovY2 - radius
    }

    open fun renderFrame(frame: Frame): Boolean {
        // Mark the beginning of a frame render.
        val pickMode = frame.isPickMode
        if (!pickMode) frameMetrics?.beginRendering(rc)

        // Set up the render context according to the WorldWindow's current state.
        rc.globe = globe
        rc.terrainTessellator = tessellator
        rc.layers = layers
        rc.camera = camera
        rc.horizonDistance = globe.horizonDistance(camera.position.altitude)
        globe.geographicToCartesian(
            camera.position.latitude, camera.position.longitude, camera.position.altitude, rc.cameraPoint
        )
        rc.renderResourceCache = renderResourceCache
        rc.verticalExaggeration = verticalExaggeration
        rc.densityFactor = densityFactor

        // Configure the frame's Cartesian modelview matrix and eye coordinate projection matrix.
        computeViewingTransform(frame.projection, frame.modelview)
        frame.viewport.copy(viewport)
//        frame.infiniteProjection.setToInfiniteProjection(viewport.width, viewport.height, camera.fieldOfView, 1.0)
//        frame.infiniteProjection.multiplyByMatrix(frame.modelview)
        rc.viewport.copy(frame.viewport)
        rc.projection.copy(frame.projection)
        rc.modelview.copy(frame.modelview)
        rc.modelviewProjection.setToMultiply(frame.projection, frame.modelview)
        if (pickMode) rc.frustum.setToModelviewProjection(frame.projection, frame.modelview, frame.viewport, frame.pickViewport!!)
        else rc.frustum.setToModelviewProjection(frame.projection, frame.modelview, frame.viewport)

        // Accumulate the Drawables in the frame's drawable queue and drawable terrain data structures.
        rc.drawableQueue = frame.drawableQueue
        rc.drawableTerrain = frame.drawableTerrain
        rc.pickedObjects = frame.pickedObjects
        rc.pickDeferred = frame.pickDeferred
        rc.pickViewport = frame.pickViewport
        rc.pickPoint = frame.pickPoint
        rc.pickRay = frame.pickRay
        rc.isPickMode = frame.isPickMode

        // Let the frame controller render the WorldWindow's current state.
        frameController.renderFrame(rc)

        // Propagate redraw requests submitted during rendering.
        val isRedrawRequested = !pickMode && rc.isRedrawRequested

        // Mark the end of a frame render.
        if (!pickMode) frameMetrics?.endRendering(rc)

        // Reset the render context's state in preparation for the next frame.
        rc.reset()

        return isRedrawRequested
    }

    open fun drawFrame(frame: Frame) {
        // Mark the beginning of a frame draw.
        val pickMode = frame.isPickMode
        if (!pickMode) frameMetrics?.beginDrawing(dc)

        // Set up the draw context according to the frame's current state.
        dc.eyePoint.copy(frame.modelview.extractEyePoint(dc.eyePoint))
        dc.viewport.copy(frame.viewport)
        dc.projection.copy(frame.projection)
        dc.modelview.copy(frame.modelview)
        dc.modelviewProjection.setToMultiply(frame.projection, frame.modelview)
//        dc.infiniteProjection.copy(frame.infiniteProjection)
        dc.screenProjection.setToScreenProjection(
            frame.viewport.width.toDouble(), frame.viewport.height.toDouble()
        )

        // Process the drawables in the frame's drawable queue and drawable terrain data structures.
        dc.drawableQueue = frame.drawableQueue
        dc.drawableTerrain = frame.drawableTerrain
        dc.pickedObjects = frame.pickedObjects
        dc.pickViewport = frame.pickViewport
        dc.pickPoint = frame.pickPoint
        dc.isPickMode = frame.isPickMode

        // Let the frame controller draw the frame.
        frameController.drawFrame(dc)

        // Increment render resource cache age on each frame
        renderResourceCache.incAge()

        // Release resources evicted during the previous frame.
        renderResourceCache.releaseEvictedResources(dc)

        // Mark the end of a frame draw.
        if (!pickMode) frameMetrics?.endDrawing(dc)

        // Reset the draw context's state in preparation for the next frame.
        dc.reset()
    }

    protected open fun computeViewingTransform(projection: Matrix4, modelview: Matrix4) {
        // Compute the clip plane distances. The near distance is set to a large value that does not clip the globe's
        // surface. The far distance is set to the smallest value that does not clip the atmosphere.
        val eyeAltitude = camera.position.altitude
        val eyeHorizon = globe.horizonDistance(eyeAltitude)
        val atmosphereHorizon = globe.horizonDistance(atmosphereAltitude)

        // The far distance is set to the smallest value that does not clip the atmosphere.
        var far = eyeHorizon + atmosphereHorizon
        if (far < 1e3) far = 1e3

        //The near distance is set to a large value that does not clip the globe's surface.
        val maxDepthValue = (1 shl depthBits) - 1
        val farResolution = 10.0
        var near = far / (maxDepthValue / (1 - farResolution / far) - maxDepthValue + 1)

        // Prevent the near clip plane from intersecting the terrain.
        val distanceToSurface = eyeAltitude - globe.getElevationAtLocation(
            camera.position.latitude, camera.position.longitude
        ) * verticalExaggeration
        if (distanceToSurface > 0) {
            val tanHalfFov = tan(0.5 * camera.fieldOfView.radians)
            val maxNearDistance = distanceToSurface / (2 * sqrt(2 * tanHalfFov * tanHalfFov + 1))
            if (near > maxNearDistance) near = maxNearDistance
        }
        if (near < 1) near = 1.0

        // Compute a perspective projection matrix given the WorldWindow's viewport, field of view, and clip distances.
        projection.setToPerspectiveProjection(viewport.width, viewport.height, camera.fieldOfView, near, far)

        // Compute a Cartesian transform matrix from the Camera.
        cameraToViewingTransform(modelview)
    }

    protected open fun cameraToViewingTransform(result: Matrix4): Matrix4 {
        // Transform by the local cartesian transform at the camera's position.
        geographicToCartesianTransform(camera.position, camera.altitudeMode, result)

        // Transform by the heading, tilt and roll.
        result.multiplyByRotation(0.0, 0.0, 1.0, -camera.heading) // rotate clockwise about the Z axis
        result.multiplyByRotation(1.0, 0.0, 0.0, camera.tilt) // rotate counter-clockwise about the X axis
        result.multiplyByRotation(0.0, 0.0, 1.0, camera.roll) // rotate counter-clockwise about the Z axis (again)

        // Make the transform a viewing matrix.
        result.invertOrthonormal()
        return result
    }

    protected open fun lookAtToViewingTransform(lookAt: LookAt, result: Matrix4): Matrix4 {
        // Transform by the local cartesian transform at the look-at's position.
        geographicToCartesianTransform(lookAt.position, lookAt.altitudeMode, result)

        // Transform by the heading and tilt.
        result.multiplyByRotation(0.0, 0.0, 1.0, -lookAt.heading) // rotate clockwise about the Z axis
        result.multiplyByRotation(1.0, 0.0, 0.0, lookAt.tilt) // rotate counter-clockwise about the X axis
        result.multiplyByRotation(0.0, 0.0, 1.0, lookAt.roll) // rotate counter-clockwise about the Z axis (again)

        // Transform by the range.
        result.multiplyByTranslation(0.0, 0.0, lookAt.range)

        // Make the transform a viewing matrix.
        result.invertOrthonormal()
        return result
    }

    protected open fun geographicToCartesianTransform(position: Position, altitudeMode: AltitudeMode, result: Matrix4): Matrix4 {
        when (altitudeMode) {
            AltitudeMode.ABSOLUTE -> globe.geographicToCartesianTransform(
                position.latitude, position.longitude, position.altitude, result
            )
            AltitudeMode.CLAMP_TO_GROUND -> globe.geographicToCartesianTransform(
                position.latitude, position.longitude, globe.getElevationAtLocation(
                    position.latitude, position.longitude
                ) * verticalExaggeration, result
            )
            AltitudeMode.RELATIVE_TO_GROUND -> globe.geographicToCartesianTransform(
                position.latitude, position.longitude, position.altitude + globe.getElevationAtLocation(
                    position.latitude, position.longitude
                ) * verticalExaggeration, result
            )
        }
        return result
    }

    companion object {
        protected const val COLLISION_CHECK_LIMIT = 8848.86 // Everest mountain altitude
        protected const val COLLISION_THRESHOLD = 10.0 // 10m above surface

        /**
         * Provides a global mechanism for broadcasting notifications within the WorldWind library.
         */
        @JvmStatic
        val eventBus = MutableSharedFlow<Any>(extraBufferCapacity = 1)

        /**
         * Requests that all WorldWindow instances update their display. Internally, this dispatches a REQUEST_REDRAW
         * message to the WorldWind message center.
         */
        @JvmStatic
        fun requestRedraw() { eventBus.tryEmit(RequestRedrawEvent()) }
    }

    /**
     * Event requesting WorldWindow instances to update their display.
     */
    class RequestRedrawEvent
}