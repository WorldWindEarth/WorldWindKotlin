package earth.worldwind

import earth.worldwind.draw.DrawContext
import earth.worldwind.frame.BasicFrameController
import earth.worldwind.frame.Frame
import earth.worldwind.frame.FrameController
import earth.worldwind.frame.FrameMetrics
import earth.worldwind.geom.*
import earth.worldwind.geom.Angle.Companion.POS90
import earth.worldwind.geom.Angle.Companion.ZERO
import earth.worldwind.geom.Angle.Companion.radians
import earth.worldwind.globe.Globe
import earth.worldwind.globe.terrain.BasicTessellator
import earth.worldwind.globe.terrain.Tessellator
import earth.worldwind.layer.LayerList
import earth.worldwind.render.RenderContext
import earth.worldwind.render.RenderResourceCache
import earth.worldwind.util.Logger
import earth.worldwind.util.kgl.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.datetime.TimeZone
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic
import kotlin.math.*

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
    val renderResourceCache: RenderResourceCache,
    /**
     * Planet or celestial object approximated by a reference ellipsoid and elevation models.
     */
    var globe: Globe = Globe(),
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
    val goToAnimator = GoToAnimator(this)
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
     * Atmosphere altitude above ellipsoid. Used to control when objects are clipped by the far plain behind the globe.
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
    open fun setupViewport(width: Int, height: Int, density: Float) {
        // Keep pixel scale by adapting field of view on view port resize
        if (isKeepScale && viewport.height != 0) {
            try {
                camera.fieldOfView = (2.0 * atan(tan(camera.fieldOfView.inRadians / 2.0) * height / viewport.height)).radians
            } catch (_: IllegalArgumentException) {
                // Keep the original field of view in case new one does not fit requirements
            }
        }

        // Apply new viewport size for next frames
        viewport.set(0, 0, width, height)

        // Store current screen density factor
        densityFactor = density
    }

    /**
     * Get look at orientation and range based on current camera position and specified geographic position
     *
     * @param result Pre-allocated look at object
     * @param lookAtPosition Custom "look at" position. Terrain position on viewport center will be used by default.
     * @return Look at orientation and range based on current camera position and specified geographic position
     */
    open fun cameraAsLookAt(result: LookAt, lookAtPosition: Position? = null): LookAt {
        if (lookAtPosition != null) {
            cameraToViewingTransform(scratchModelview)
            globe.geographicToCartesian(lookAtPosition.latitude, lookAtPosition.longitude, lookAtPosition.altitude, scratchPoint)
            result.position.copy(lookAtPosition)
        } else if (viewport.isEmpty || !pickTerrainPosition(viewport.width / 2.0, viewport.height / 2.0, result.position)) {
            // Use point on horizon as a backup
            cameraToViewingTransform(scratchModelview)
            scratchModelview.extractEyePoint(scratchRay.origin)
            scratchModelview.extractForwardVector(scratchRay.direction)
            val cameraPosition = globe.getAbsolutePosition(camera.position, camera.altitudeMode)
            scratchRay.pointAt(globe.horizonDistance(cameraPosition.altitude), scratchPoint)
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
     * Set camera position and orientation, based on look at position, orientation and range
     *
     * @param lookAt Look at position, orientation and range
     */
    open fun cameraFromLookAt(lookAt: LookAt) {
        applyLookAtLimits(lookAt)
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
        val position = globe.getAbsolutePosition(camera.position, camera.altitudeMode)
        val elevation = if (globe.is2D) COLLISION_THRESHOLD
        else globe.getElevation(position.latitude, position.longitude) + COLLISION_THRESHOLD
        if (elevation > position.altitude) {
            // Set camera altitude above the surface
            position.altitude = elevation
            // Compute new camera point
            globe.geographicToCartesian(position.latitude, position.longitude, position.altitude, scratchPoint)
            // Compute look at point
            globe.geographicToCartesian(
                lookAt.position.latitude, lookAt.position.longitude, lookAt.position.altitude, scratchRay.origin
            )
            // Compute normal to globe in look at point
            globe.geographicToCartesianNormal(lookAt.position.latitude, lookAt.position.longitude, scratchRay.direction)
            // Calculate tilt angle between new camera point and look at point
            scratchPoint.subtract(scratchRay.origin).normalize()
            val dot = scratchRay.direction.dot(scratchPoint)
            if (dot >= -1 && dot <= 1) camera.tilt = acos(dot).radians
        }
    }

    /**
     * More efficient way to determine terrain position at screen point using terrain from last rendered frame.
     *
     * @param x the screen point's X coordinate
     * @param y the screen point's Y coordinate
     * @param result a pre-allocated [Position] in which to store the computed geographic position
     *
     * @return true if the screen point could be converted; false if the screen point is not on the terrain
     */
    open fun pickTerrainPosition(x: Double, y: Double, result: Position) =
        if (rayThroughScreenPoint(x, y, scratchRay) && frameController.lastTerrains.values.any {
            it.intersect(scratchRay, scratchPoint)
        }) {
            globe.cartesianToGeographic(scratchPoint.x, scratchPoint.y, scratchPoint.z, result)
            true
        } else false

    /**
     * Transforms a Cartesian coordinate point to viewport coordinates.
     * <br>
     * This stores the converted point in the result argument, and returns a boolean value indicating whether the
     * converted is successful. This returns false if the Cartesian point is clipped by either the WorldWindow's near
     * clipping plane or far clipping plane.
     *
     * @param point  the Cartesian point in meters
     * @param result a pre-allocated [Vec2] in which to return the screen point
     *
     * @return true if the transformation is successful, otherwise false
     */
    fun cartesianToScreenPoint(point: Vec3, result: Vec2) = cartesianToScreenPoint(point.x, point.y, point.z, result)

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
        if (viewport.isEmpty) return false

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
     * @param position  the geographic position
     * @param result    a pre-allocated [Vec2] in which to return the screen point
     *
     * @return true if the transformation is successful, otherwise false
     */
    fun geographicToScreenPoint(position: Position, result: Vec2) =
        geographicToScreenPoint(position.latitude, position.longitude, position.altitude, result)

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
        return cartesianToScreenPoint(scratchPoint, result)
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
        if (viewport.isEmpty) return false

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
        val tanFovY2 = tan(camera.fieldOfView.inRadians * 0.5)
        val frustumHeight = 2 * distance * tanFovY2
        return frustumHeight / viewport.height
    }

    /**
     * Returns the minimum distance from the globe's surface necessary to make the globe's extents visible in this World
     * Window.
     */
    val distanceToViewGlobeExtents get(): Double {
        val sinFovY2 = sin(camera.fieldOfView.inRadians * 0.5)
        val radius = globe.equatorialRadius
        return radius / sinFovY2 - radius
    }

    open fun renderFrame(frame: Frame): Boolean {
        // Mark the beginning of a frame render.
        val pickMode = frame.isPickMode
        if (!pickMode) frameMetrics?.beginRendering(rc)

        // Restrict camera tilt and roll in 2D
        if (globe.is2D) {
            camera.tilt = ZERO
            camera.roll = ZERO
        }

        // Set up the render context according to the WorldWindow's current state.
        rc.globe = globe
        rc.terrainTessellator = tessellator
        rc.layers = layers
        rc.camera = camera
        val cameraPosition = globe.getAbsolutePosition(camera.position, camera.altitudeMode)
        rc.horizonDistance = globe.horizonDistance(cameraPosition.altitude)
        globe.geographicToCartesian(
            cameraPosition.latitude, cameraPosition.longitude, cameraPosition.altitude, rc.cameraPoint
        )
        rc.renderResourceCache = renderResourceCache
        rc.densityFactor = densityFactor
        rc.atmosphereAltitude = atmosphereAltitude
        rc.globeState = globe.state
        rc.elevationModelTimestamp = globe.elevationModel.timestamp

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

        // Compute viewing distance and pixel size (for 3D view it will be done after terrain tessellation)
        if (globe.is2D) {
            scratchRay.origin.copy(rc.cameraPoint)
            rc.modelview.extractForwardVector(scratchRay.direction)
            rc.viewingDistance = if (globe.intersect(scratchRay, scratchPoint)) {
                rc.lookAtPosition = globe.cartesianToGeographic(scratchPoint.x, scratchPoint.y, scratchPoint.z, Position())
                scratchPoint.distanceTo(rc.cameraPoint)
            } else rc.horizonDistance
            rc.pixelSize = rc.pixelSizeAtDistance(rc.viewingDistance)
        }

        // Accumulate the Drawables in the frame's drawable queue and drawable terrain data structures.
        rc.uploadQueue = frame.uploadQueue
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

        // The matrix that transforms normal vectors in model coordinates to normal vectors in eye coordinates.
        // Typically used to transform a shape's normal vectors during lighting calculations.
        dc.modelviewNormalTransform.copy(Matrix4().invertOrthonormalMatrix(frame.modelview).upper3By3().transpose())

        // Process the drawables in the frame's drawable queue and drawable terrain data structures.
        dc.uploadQueue = frame.uploadQueue
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
        val eyeAltitude = globe.getAbsolutePosition(camera.position, camera.altitudeMode).altitude * globe.verticalExaggeration
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
        val distanceToSurface = if (globe.is2D) eyeAltitude else eyeAltitude - globe.getElevation(
            camera.position.latitude, camera.position.longitude
        ) * globe.verticalExaggeration
        if (distanceToSurface > 0) {
            val tanHalfFov = tan(0.5 * camera.fieldOfView.inRadians)
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
            AltitudeMode.ABOVE_SEA_LEVEL -> globe.geographicToCartesianTransform(
                position.latitude, position.longitude,
                position.altitude + globe.geoid.getOffset(position.latitude, position.longitude), result
            )
            AltitudeMode.CLAMP_TO_GROUND -> globe.geographicToCartesianTransform(
                position.latitude, position.longitude, globe.getElevation(position.latitude, position.longitude), result
            )
            AltitudeMode.RELATIVE_TO_GROUND -> globe.geographicToCartesianTransform(
                position.latitude, position.longitude,
                position.altitude + globe.getElevation(position.latitude, position.longitude), result
            )
        }
        return result
    }

    protected open fun applyLookAtLimits(lookAt: LookAt) {
        // Clamp latitude to between -90 and +90, and normalize longitude to between -180 and +180.
        lookAt.position.latitude = lookAt.position.latitude.clampLatitude()
        lookAt.position.longitude = lookAt.position.longitude.normalizeLongitude()

        // Clamp range to values greater than 1 in order to prevent degenerating to a first-person lookAt when
        // range is zero.
        lookAt.range = lookAt.range.coerceIn(10.0, distanceToViewGlobeExtents * 2)

        // Normalize heading to between -180 and +180.
        lookAt.heading = lookAt.heading.normalize180()

        // Clamp tilt to between 0 and +90 to prevent the viewer from going upside down.
        lookAt.tilt = lookAt.tilt.coerceIn(ZERO, POS90)

        // Normalize heading to between -180 and +180.
        lookAt.roll = lookAt.roll.normalize180()

        // Apply 2D limits when the globe is 2D.
        if (globe.is2D) {
            // Clamp range to prevent more than 360 degrees of visible longitude. Assumes a 45 degree horizontal
            // field of view.
            lookAt.range = lookAt.range.coerceIn(1.0, 2.0 * PI * globe.equatorialRadius)

            // Force tilt to 0 when in 2D mode to keep the viewer looking straight down.
            lookAt.tilt = ZERO
            lookAt.roll = ZERO
        }
    }

    companion object {
        protected const val COLLISION_THRESHOLD = 10.0 // 10m above surface

        private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 1)

        /**
         * Provides a global mechanism for broadcasting notifications within the WorldWind library.
         */
        @JvmStatic
        val events = _events.asSharedFlow()

        /**
         * Requests that all WorldWindow instances update their display. Internally, this dispatches a REQUEST_REDRAW
         * message to the WorldWind message center.
         */
        @JvmStatic
        fun requestRedraw() { _events.tryEmit(Event.RequestRedraw) }

        /**
         * Requests render resource cache to remove specified resource ID from absent list
         *
         * @param resourceId resource ID to be removed from absent list
         */
        @JvmStatic
        suspend fun unmarkResourceAbsent(resourceId: Int) { _events.emit(Event.UnmarkResourceAbsent(resourceId)) }
    }

    sealed interface Event {
        /**
         * Event requesting WorldWindow instances to update their display.
         */
        object RequestRedraw : Event
        /**
         * Event requesting RenderResourceCache to un-mark resource from absent list
         *
         * @param resourceId resource ID to be removed from absent list
         */
        data class UnmarkResourceAbsent(val resourceId: Int) : Event
    }
}