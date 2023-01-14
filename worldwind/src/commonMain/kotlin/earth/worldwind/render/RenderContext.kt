package earth.worldwind.render

import earth.worldwind.PickedObject
import earth.worldwind.PickedObjectList
import earth.worldwind.draw.Drawable
import earth.worldwind.draw.DrawableGroup
import earth.worldwind.draw.DrawableQueue
import earth.worldwind.draw.DrawableTerrain
import earth.worldwind.geom.*
import earth.worldwind.geom.AltitudeMode.*
import earth.worldwind.globe.Globe
import earth.worldwind.globe.terrain.Terrain
import earth.worldwind.globe.terrain.Tessellator
import earth.worldwind.layer.Layer
import earth.worldwind.layer.LayerList
import earth.worldwind.render.buffer.AbstractBufferObject
import earth.worldwind.render.image.ImageOptions
import earth.worldwind.render.image.ImageSource
import earth.worldwind.render.program.AbstractShaderProgram
import earth.worldwind.shape.TextAttributes
import earth.worldwind.util.Pool
import earth.worldwind.util.SynchronizedPool
import earth.worldwind.util.glu.GLU
import earth.worldwind.util.glu.GLUtessellator
import kotlinx.coroutines.CompletableDeferred
import kotlin.math.tan

open class RenderContext {
    companion object {
        private const val MAX_PICKED_OBJECT_ID = 0xFFFFFF
    }

    var globe: Globe? = null
    var terrainTessellator: Tessellator? = null
    var terrain: Terrain? = null
    var layers: LayerList? = null
    var currentLayer: Layer? = null
    var verticalExaggeration = 1.0
    var horizonDistance = 0.0
    var camera: Camera? = null
    var cameraPoint = Vec3()
    val viewport = Viewport()
    val projection = Matrix4()
    val modelview = Matrix4()
    val modelviewProjection = Matrix4()
    val frustum = Frustum()
    var renderResourceCache: RenderResourceCache? = null
    var densityFactor = 1f
    var drawableQueue: DrawableQueue? = null
    var drawableTerrain: DrawableQueue? = null
    var pickedObjects: PickedObjectList? = null
    var pickDeferred: CompletableDeferred<PickedObjectList>? = null
    var pickViewport: Viewport? = null
    var pickPoint: Vec2? = null
    var pickRay: Line? = null
    var isPickMode = false
    var isRedrawRequested = false
        protected set
    private var pickedObjectId = 0
    private var pixelSizeFactor = 0.0
    private val userProperties = mutableMapOf<Any, Any>()
    val drawablePools = mutableMapOf<Any, Pool<*>>()
    private val textRenderer = TextRenderer(this)
    private val scratchTextCacheKey = TextCacheKey()
    private val scratchVector = Vec3()

    val tessellator: GLUtessellator by lazy { GLU.gluNewTess() }

    open fun reset() {
        globe = null
        terrainTessellator = null
        terrain = null
        layers = null
        currentLayer = null
        verticalExaggeration = 1.0
        horizonDistance = 0.0
        camera = null
        cameraPoint.set(0.0, 0.0, 0.0)
        viewport.setEmpty()
        projection.setToIdentity()
        modelview.setToIdentity()
        modelviewProjection.setToIdentity()
        frustum.setToUnitFrustum()
        renderResourceCache = null
        densityFactor = 1f
        drawableQueue = null
        drawableTerrain = null
        pickedObjects = null
        pickDeferred = null
        pickViewport = null
        pickPoint = null
        pickRay = null
        isPickMode = false
        pickedObjectId = 0
        isRedrawRequested = false
        pixelSizeFactor = 0.0
        userProperties.clear()
    }

    fun requestRedraw() { isRedrawRequested = true }

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
    fun pixelSizeAtDistance(distance: Double): Double {
        if (pixelSizeFactor == 0.0) { // cache the scaling factor used to convert distances to pixel sizes
            val fov = camera!!.fieldOfView
            val tanFov2 = tan(fov.inRadians * 0.5)
            pixelSizeFactor = 2 * tanFov2 / viewport.height
        }
        return distance * pixelSizeFactor
    }

    /**
     * Projects a Cartesian point to screen coordinates. The resultant screen point is in OpenGL screen coordinates,
     * with the origin in the bottom-left corner and axes that extend up and to the right from the origin.
     * <br>
     * This stores the projected point in the result argument, and returns a boolean value indicating whether or not the
     * projection is successful. This returns false if the Cartesian point is clipped by the near clipping plane or the
     * far clipping plane.
     *
     * @param modelPoint the Cartesian point to project
     * @param result     a pre-allocated [Vec3] in which to return the projected point
     *
     * @return true if the transformation is successful, otherwise false
     */
    fun project(modelPoint: Vec3, result: Vec3): Boolean {
        // TODO consider consolidating this with Matrix4.project and moving projectWithDepth to Matrix4
        // Transform the model point from model coordinates to eye coordinates then to clip coordinates. This
        // inverts the Z axis and stores the negative of the eye coordinate Z value in the W coordinate.
        val mx = modelPoint.x
        val my = modelPoint.y
        val mz = modelPoint.z
        val m = modelviewProjection.m
        var x = m[0] * mx + m[1] * my + m[2] * mz + m[3]
        var y = m[4] * mx + m[5] * my + m[6] * mz + m[7]
        var z = m[8] * mx + m[9] * my + m[10] * mz + m[11]
        val w = m[12] * mx + m[13] * my + m[14] * mz + m[15]
        if (w == 0.0) return false

        // Complete the conversion from model coordinates to clip coordinates by dividing by W. The resultant X, Y
        // and Z coordinates are in the range [-1,1].
        x /= w
        y /= w
        z /= w

        // Clip the point against the near and far clip planes.
        if (z < -1 || z > 1) return false

        // Convert the point from clip coordinate to the range [0,1]. This enables the X and Y coordinates to be
        // converted to screen coordinates, and the Z coordinate to represent a depth value in the range[0,1].
        x = x * 0.5 + 0.5
        y = y * 0.5 + 0.5
        z = z * 0.5 + 0.5

        // Convert the X and Y coordinates from the range [0,1] to screen coordinates.
        x = x * viewport.width + viewport.x
        y = y * viewport.height + viewport.y
        result.x = x
        result.y = y
        result.z = z
        return true
    }

    /**
     * Projects a Cartesian point to screen coordinates, applying an offset to the point's projected depth value. The
     * resultant screen point is in OpenGL screen coordinates, with the origin in the bottom-left corner and axes that
     * extend up and to the right from the origin.
     * <br>
     * This stores the projected point in the result argument, and returns a boolean value indicating whether or not the
     * projection is successful. This returns false if the Cartesian point is clipped by the near clipping plane or the
     * far clipping plane.
     * <br>
     * The depth offset may be any real number and is typically used to move the screenPoint slightly closer to the
     * user's eye in order to give it visual priority over nearby objects or terrain. An offset of zero has no effect.
     * An offset less than zero brings the screenPoint closer to the eye, while an offset greater than zero pushes the
     * projected screen point away from the eye.
     * <br>
     * Applying a non-zero depth offset has no effect on whether the model point is clipped by this method or by WebGL.
     * Clipping is performed on the original model point, ignoring the depth offset. The final depth value after
     * applying the offset is clamped to the range [0,1].
     *
     * @param modelPoint  the Cartesian point to project
     * @param depthOffset the amount of depth offset to apply
     * @param result      a pre-allocated [Vec3] in which to return the projected point
     *
     * @return true if the transformation is successful, otherwise false
     */
    fun projectWithDepth(modelPoint: Vec3, depthOffset: Double, result: Vec3): Boolean {
        // Transform the model point from model coordinates to eye coordinates. The eye coordinate and the clip
        // coordinate are transformed separately in order to reuse the eye coordinate below.
        val mx = modelPoint.x
        val my = modelPoint.y
        val mz = modelPoint.z
        val m = modelview.m
        val ex = m[0] * mx + m[1] * my + m[2] * mz + m[3]
        val ey = m[4] * mx + m[5] * my + m[6] * mz + m[7]
        val ez = m[8] * mx + m[9] * my + m[10] * mz + m[11]
        val ew = m[12] * mx + m[13] * my + m[14] * mz + m[15]

        // Transform the point from eye coordinates to clip coordinates.
        val p = projection.m
        var x = p[0] * ex + p[1] * ey + p[2] * ez + p[3] * ew
        var y = p[4] * ex + p[5] * ey + p[6] * ez + p[7] * ew
        var z = p[8] * ex + p[9] * ey + p[10] * ez + p[11] * ew
        val w = p[12] * ex + p[13] * ey + p[14] * ez + p[15] * ew
        if (w == 0.0) return false

        // Complete the conversion from model coordinates to clip coordinates by dividing by W. The resultant X, Y
        // and Z coordinates are in the range [-1,1].
        x /= w
        y /= w
        z /= w

        // Clip the point against the near and far clip planes.
        if (z < -1 || z > 1) return false

        // Transform the Z eye coordinate to clip coordinates again, this time applying a depth offset. The depth
        // offset is applied only to the matrix element affecting the projected Z coordinate, so we inline the
        // computation here instead of re-computing X, Y, Z and W in order to improve performance. See
        // Matrix4.offsetProjectionDepth for more information on the effect of this offset.
        z = p[8] * ex + p[9] * ey + p[10] * ez * (1 + depthOffset) + p[11] * ew
        z /= w

        // Clamp the point to the near and far clip planes. We know the point's original Z value is contained within
        // the clip planes, so we limit its offset z value to the range [-1, 1] in order to ensure it is not clipped
        // by WebGL. In clip coordinates the near and far clip planes are perpendicular to the Z axis and are
        // located at -1 and 1, respectively.
        z = z.coerceIn(-1.0, 1.0)

        // Convert the point from clip coordinates to the range [0, 1]. This enables the XY coordinates to be
        // converted to screen coordinates, and the Z coordinate to represent a depth value in the range [0, 1].
        x = x * 0.5 + 0.5
        y = y * 0.5 + 0.5
        z = z * 0.5 + 0.5

        // Convert the X and Y coordinates from the range [0,1] to screen coordinates.
        x = x * viewport.width + viewport.x
        y = y * viewport.height + viewport.y
        result.x = x
        result.y = y
        result.z = z
        return true
    }

    /**
     * Converts a geographic [Position] to Cartesian coordinates according to an [altitudeMode].
     * The Cartesian coordinate system is a function of this render context's current globe and its terrain surface,
     * depending on the altitude mode. In general, it is not safe to cache the Cartesian coordinates,
     * as many factors contribute to the value returned, and may change from one frame to the next.
     *
     * @param position     the specified position
     * @param altitudeMode an altitude mode indicating how to interpret the position's altitude component
     * @param result       a pre-allocated [Vec3] in which to store the computed X, Y and Z Cartesian coordinates
     *
     * @return the result argument, set to the computed Cartesian coordinates
     */
    fun geographicToCartesian(
        position: Position, altitudeMode: AltitudeMode, result: Vec3
    ) = geographicToCartesian(position.latitude, position.longitude, position.altitude, altitudeMode, result)

    /**
     * Converts a geographic position to Cartesian coordinates according to an [altitudeMode].
     * The Cartesian coordinate system is a function of this render context's current globe and its terrain surface,
     * depending on the altitude mode. In general, it is not safe to cache the Cartesian coordinates,
     * as many factors contribute to the value returned, and may change from one frame to the next.
     *
     * @param latitude     the position's latitude
     * @param longitude    the position's longitude
     * @param altitude     the position's altitude in meters
     * @param altitudeMode an altitude mode indicating how to interpret the position's altitude component
     * @param result       a pre-allocated [Vec3] in which to store the computed X, Y and Z Cartesian coordinates
     *
     * @return the result argument, set to the computed Cartesian coordinates
     */
    fun geographicToCartesian(
        latitude: Angle, longitude: Angle, altitude: Double, altitudeMode: AltitudeMode, result: Vec3
    ): Vec3 {
        when (altitudeMode) {
            ABSOLUTE -> globe?.geographicToCartesian(
                latitude, longitude, altitude * verticalExaggeration, result
            )
            CLAMP_TO_GROUND -> if (terrain?.surfacePoint(latitude, longitude, result) != true)
                // TODO use elevation model height as a fallback
                globe?.geographicToCartesian(latitude, longitude, 0.0, result)
            RELATIVE_TO_GROUND -> if (terrain?.surfacePoint(latitude, longitude, result) == true) {
                if (altitude != 0.0) {
                    // Offset along the normal vector at the terrain surface point.
                    globe?.geographicToCartesianNormal(latitude, longitude, scratchVector)?.also {
                        result.add(scratchVector.multiply(altitude))
                    }
                }
            } else {
                // TODO use elevation model height as a fallback
                globe?.geographicToCartesian(latitude, longitude, altitude * verticalExaggeration, result)
            }
        }
        return result
    }

    // TODO redesign ShaderProgram to operate as a resource accessible from DrawContext
    // TODO created automatically on OpenGL thread, unless the caller wants to explicitly create a program
    inline fun <reified T: AbstractShaderProgram> getShaderProgram(builder: () -> T): T {
        val key = T::class
        return renderResourceCache?.run{ get(key) ?: builder().also { put(key, it, it.programLength) } } as T
    }

    fun getTexture(imageSource: ImageSource, imageOptions: ImageOptions?, retrieve: Boolean = true) =
        renderResourceCache?.run { get(imageSource) ?: if (retrieve) retrieveTexture(imageSource, imageOptions) else null } as Texture?

    inline fun <reified T: AbstractBufferObject> getBufferObject(key: Any, builder: () -> T) =
        renderResourceCache?.run{ get(key) ?: builder().also { put(key, it, it.byteCount) } } as T

    fun getText(text: String?, attributes: TextAttributes, render: Boolean = true) = renderResourceCache?.run {
        scratchTextCacheKey.text = text
        scratchTextCacheKey.attributes = attributes
        // Use scratch key on get operation to avoid unnecessary object creation on each text render on each frame
        get(scratchTextCacheKey) as Texture? ?: if (render) textRenderer.renderText(text, attributes)?.also {
            // Use new text cache key and copy attributes on put operation to avoid cache issues on attributes modification
            put(TextCacheKey(text, TextAttributes(attributes)), it, it.byteCount)
        } else null
    }

    fun offerDrawable(drawable: Drawable, groupId: DrawableGroup, order: Double) {
        drawableQueue?.offerDrawable(drawable, groupId, order)
    }

    fun offerSurfaceDrawable(drawable: Drawable, zOrder: Double) {
        drawableQueue?.offerDrawable(drawable, DrawableGroup.SURFACE, zOrder)
    }

    fun offerShapeDrawable(drawable: Drawable, cameraDistance: Double) {
        drawableQueue?.offerDrawable(drawable, DrawableGroup.SHAPE, -cameraDistance) // order by descending distance to the viewer
    }

    fun offerDrawableTerrain(drawable: DrawableTerrain, sortOrder: Double) {
        drawableTerrain?.offerDrawable(drawable, DrawableGroup.SURFACE, sortOrder)
    }

    fun sortDrawables() {
        drawableQueue?.sortDrawables()
        drawableTerrain?.sortDrawables()
    }

    val drawableCount get() = drawableQueue?.count ?: 0

    @Suppress("UNCHECKED_CAST")
    inline fun <reified T : Drawable> getDrawablePool(): Pool<T> {
        val key = T::class
        // use SynchronizedPool; acquire and are release may be called in separate threads
        return drawablePools[key] as Pool<T>? ?: SynchronizedPool<T>().also { drawablePools[key] = it }
    }

    fun offerPickedObject(pickedObject: PickedObject) { pickedObjects?.offerPickedObject(pickedObject) }

    fun nextPickedObjectId(): Int {
        if (++pickedObjectId > MAX_PICKED_OBJECT_ID) pickedObjectId = 1
        return pickedObjectId
    }

    fun getUserProperty(key: Any) = userProperties[key]

    fun putUserProperty(key: Any, value: Any) = userProperties.put(key, value)

    fun removeUserProperty(key: Any) = userProperties.remove(key)

    fun hasUserProperty(key: Any) = userProperties.containsKey(key)

    protected data class TextCacheKey(
        var text: String? = null,
        var attributes: TextAttributes? = null
    )
}