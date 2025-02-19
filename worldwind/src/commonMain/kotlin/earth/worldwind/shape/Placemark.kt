package earth.worldwind.shape

import earth.worldwind.PickedObject
import earth.worldwind.draw.DrawableLines
import earth.worldwind.draw.DrawableScreenTexture
import earth.worldwind.geom.*
import earth.worldwind.geom.Angle.Companion.ZERO
import earth.worldwind.globe.Globe
import earth.worldwind.render.AbstractRenderable
import earth.worldwind.render.Color
import earth.worldwind.render.RenderContext
import earth.worldwind.render.Texture
import earth.worldwind.render.image.ImageSource
import earth.worldwind.render.program.BasicShaderProgram
import earth.worldwind.render.program.TriangleShaderProgram
import earth.worldwind.util.math.encodeOrientationVector
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic
import kotlin.math.abs
import kotlin.math.sin

/**
 * Represents a Placemark shape. A placemark displays an image, a label and a leader connecting the placemark's
 * geographic position to the ground. All three of these items are optional. By default, the leader is not pickable. See
 * [Placemark.isLeaderPickingEnabled].
 * <br>
 * Placemarks may be drawn with either an image or as single-color square with a specified size. When the placemark
 * attributes indicate a valid image, the placemark's image is drawn as a rectangle in the image's original dimensions,
 * scaled by the image scale attribute. Otherwise, the placemark is drawn as a square with width and height equal to the
 * value of the image scale attribute, in pixels, and color equal to the image color attribute.
 */
open class Placemark @JvmOverloads constructor(
    /**
     * The placemark's geographic position.
     */
    position: Position,
    /**
     * The placemark's normal attributes.
     */
    var attributes: PlacemarkAttributes = PlacemarkAttributes(),
    /**
     * The label text to draw near the placemark.
     */
    var label: String? = null
) : AbstractRenderable(if (label.isNullOrEmpty()) "Placemark" else label), Highlightable, Movable {
    /**
     * The placemark's geographic position.
     */
    var position = Position(position)
        set(value) {
            field.copy(value)
        }
    /**
     * The placemark's altitude mode. See [AltitudeMode]
     */
    override var altitudeMode = AltitudeMode.ABSOLUTE
    /**
     * The attributes to use when the placemark is highlighted.
     */
    var highlightAttributes: PlacemarkAttributes? = null
    /**
     * Determines whether the normal or highlighted attributes should be used.
     */
    override var isHighlighted = false
    /**
     * Sets the optional level-of-detail selector used to inject logic for selecting PlacemarkAttributes based on
     * the camera distance and highlighted attribute.  If set to null, the normal and highlight attribute bundles used
     * respectfully for the normal and highlighted states.
     */
    var levelOfDetailSelector: LevelOfDetailSelector? = null
    /**
     * Enables or disables the eye distance scaling feature for this placemark. When enabled, the placemark's size is
     * reduced at higher eye distances. If true, this placemark's size is scaled inversely proportional to the eye
     * distance if the eye distance is greater than the value of the [Placemark.eyeDistanceScalingThreshold]
     * property. When the eye distance is below the threshold, this placemark is scaled only according to the [PlacemarkAttributes.imageScale].
     */
    var isEyeDistanceScaling = false
    /**
     * Sets the eye distance above which to reduce the size of this placemark, in meters.
     * If [isEyeDistanceScaling] is true, this placemark's image, label and leader sizes are reduced as the eye
     * distance increases beyond this threshold.
     */
    var eyeDistanceScalingThreshold = DEFAULT_EYE_DISTANCE_SCALING_THRESHOLD
    /**
     * Sets the eye altitude, in meters, above which this placemark's label is not displayed.
     */
    var eyeDistanceScalingLabelThreshold = 1.5 * DEFAULT_EYE_DISTANCE_SCALING_THRESHOLD
    /**
     * Indicates whether this placemark's leader, if any, is pickable.
     */
    var isLeaderPickingEnabled = false
    /**
     * Enable additional altitude offset (billboarding) to prevent clipping Placamerk by terrain on tilt.
     */
    var isBillboardingEnabled = false
    /**
     * Indicates whether this placemark has visual priority over other shapes in the scene.
     */
    var isAlwaysOnTop = false
    /**
     * The amount of rotation to apply to the image, measured clockwise and relative to this placemark's
     * [Placemark.imageRotationReference].
     */
    var imageRotation = ZERO
    /**eyeDistanceScaling
     * Sets the type of rotation to apply if the [Placemark.imageRotation] is not zero. This value indicates
     * whether to apply this placemark's image rotation relative to the screen or the globe.
     * <br>
     * If [OrientationMode.RELATIVE_TO_SCREEN], this placemark's image is rotated in the plane of the screen and its
     * orientation relative to the globe changes as the view changes.
     * If [OrientationMode.RELATIVE_TO_GLOBE], this placemark's image is rotated in a plane tangent to the globe at
     * this placemark's position and retains its orientation relative to the globe.
     */
    var imageRotationReference = OrientationMode.RELATIVE_TO_SCREEN
    /**
     * Sets the amount of tilt to apply to the image, measured away from the eye point and relative to this
     * placemark's [Placemark.imageTiltReference]. While any positive or negative number may be specified,
     * values outside the range [0. 90] cause some or all of the image to be clipped.
     */
    var imageTilt = ZERO
    /**
     * Sets the type tilt to apply when [Placemark.imageTilt] is non-zero. This value indicates whether to
     * apply this placemark's image tilt relative to the screen or the globe.
     * <br>
     * If [OrientationMode.RELATIVE_TO_SCREEN], this placemark's image is tilted inwards (for positive tilts) relative
     * to the plane of the screen, and its orientation relative to the globe changes as the view changes.
     * If [OrientationMode.RELATIVE_TO_GLOBE], this placemark's image is tilted towards the globe's surface, and retains its
     * orientation relative to the surface.
     */
    var imageTiltReference = OrientationMode.RELATIVE_TO_SCREEN
    /**
     * A position associated with the object that indicates its aggregate geographic position. For a Placemark, this is
     * simply it's position property.
     *
     * @return [Placemark.position]
     */
    override val referencePosition get() = position
    /**
     * The attributes identified for use during the current render pass.
     */
    protected lateinit var activeAttributes: PlacemarkAttributes
    /**
     * The picked object ID associated with the placemark during the current render pass.
     */
    protected var pickedObjectId = 0
    protected val pickColor = Color()
    /**
     * The distance from the camera to the placemark in meters.
     */
    protected var cameraDistance = 0.0

    /**
     * Presents an interfaced for dynamically determining the PlacemarkAttributes based on the distance between the
     * placemark and the camera.
     */
    interface LevelOfDetailSelector {
        /**
         * Gets the active attributes for the current distance to the camera and highlighted state.
         *
         * @param rc             The current render context
         * @param placemark      The placemark needing a level of detail selection
         * @param cameraDistance The distance from the placemark to the camera (meters)
         *
         * @return if placemark should display or skip its rendering
         */
        fun selectLevelOfDetail(rc: RenderContext, placemark: Placemark, cameraDistance: Double): Boolean

        /**
         * Forces level of details regeneration
         */
        fun invalidate() {}
    }

    /**
     * Moves the shape over the globe's surface. For a Placemark, this simply set [Placemark.position].
     *
     * @param globe    not used.
     * @param position the new position of the shape's reference position.
     */
    override fun moveTo(globe: Globe, position: Position) { this.position = position }

    /**
     * Performs the rendering; called by the public render method.
     *
     * @param rc the current render context
     */
    override fun doRender(rc: RenderContext) {
        // Filter out renderable outside projection limits.
        if (rc.globe.projectionLimits?.contains(position) == false) return

        // Compute the placemark's Cartesian model point and corresponding distance to the eye point. If the placemark's
        // position is terrain-dependent but off the terrain, then compute it ABSOLUTE so that we have a point for the
        // placemark and are thus able to draw it. Otherwise, its image and label portion that are potentially over the
        // terrain won't get drawn, and would disappear as soon as there is no terrain at the placemark's position. This
        // can occur at the window edges.
        val altitudeMode = if (rc.globe.is2D) AltitudeMode.CLAMP_TO_GROUND else altitudeMode
        rc.geographicToCartesian(position, altitudeMode, placePoint)

        // Compute the camera distance to the place point, the value which is used for ordering the placemark drawable
        // and determining the amount of depth offset to apply.
        cameraDistance = if (isAlwaysOnTop) 0.0 else if (rc.globe.is2D) rc.viewingDistance else rc.cameraPoint.distanceTo(placePoint)

        // Allow the placemark to adjust the level of detail based on distance to the camera
        if (levelOfDetailSelector?.selectLevelOfDetail(rc, this, cameraDistance) == false) return // skip rendering

        // Determine the attributes to use for the current render pass.
        determineActiveAttributes(rc)

        // Perform point based culling for placemarks who's textures haven't been loaded yet.
        // If the texture hasn't been loaded yet, then perform point-based culling to avoid
        // loading textures for placemarks that are 'probably' outside the viewing frustum.
        // There are cases where a placemark's texture would be partially visible if it at the
        // edge of the screen were loaded. In these cases the placemark will "pop" into view when
        // the placePoint enters the view frustum.
        val activeTexture = activeAttributes.imageSource?.let {
            rc.getTexture(it, null, rc.frustum.containsPoint(placePoint))
        }

        // Compute a camera-position proximity scaling factor, so that distant placemarks can be scaled smaller than
        // nearer placemarks.
        val visibilityScale = if (isEyeDistanceScaling)
            (eyeDistanceScalingThreshold / cameraDistance).coerceIn(activeAttributes.minimumImageScale, 1.0) else 1.0

        // Apply the icon's translation and scale according to the image size, image offset and image scale. The image
        // offset is defined with its origin at the image's bottom-left corner and axes that extend up and to the right
        // from the origin point. When the placemark has no active texture the image scale defines the image size and no
        // other scaling is applied.
        val offsetX: Double
        val offsetY: Double
        val scaleX: Double
        val scaleY: Double
        if (activeTexture != null) {
            val w = activeTexture.width.toDouble()
            val h = activeTexture.height.toDouble()
            val s = activeAttributes.imageScale * visibilityScale // * rc.densityFactor
            activeAttributes.imageOffset.offsetForSize(w, h, offset)
            offsetX = offset.x * s
            offsetY = offset.y * s
            scaleX = w * s
            scaleY = h * s
        } else {
            // This branch serves both non-textured attributes and also textures that haven't been loaded yet.
            // We set the size for non-loaded textures to the typical size of a contemporary "small" icon (24px)
            var size = if (activeAttributes.imageSource != null) 24.0 else activeAttributes.imageScale
            size *= visibilityScale // * rc.densityFactor
            activeAttributes.imageOffset.offsetForSize(size, size, offset)
            offsetX = offset.x
            offsetY = offset.y
            scaleY = size
            scaleX = scaleY
        }

        // Offset along the normal vector to avoid collision with terrain.
        if (isBillboardingEnabled && offsetY != 0.0) {
            rc.globe.geographicToCartesianNormal(position.latitude, position.longitude, scratchVector).also {
                // Use real camera distance in billboarding
                val distance = if (isAlwaysOnTop) rc.cameraPoint.distanceTo(placePoint) else cameraDistance
                val altitude = rc.pixelSizeAtDistance(distance) * sin(rc.camera.tilt.inRadians)
                placePoint.add(scratchVector.multiply(offsetY * altitude))
            }
        }

        // Compute a screen depth offset appropriate for the current viewing parameters.
        var depthOffset = 0.0
        val absTilt = abs(rc.camera.tilt.inDegrees)
        if (cameraDistance < rc.horizonDistance && absTilt <= 90) {
            depthOffset = (1 - absTilt / 90) * DEFAULT_DEPTH_OFFSET
        }

        // Project the placemark's model point to screen coordinates, using the screen depth offset to push the screen
        // point's z component closer to the eye point.
        if (!rc.projectWithDepth(placePoint, depthOffset, screenPlacePoint)) return // clipped by the near plane or the far plane

        // Keep track of the drawable count to determine whether this placemark has enqueued drawables.
        val drawableCount = rc.drawableCount
        if (rc.isPickMode) {
            pickedObjectId = rc.nextPickedObjectId()
            PickedObject.identifierToUniqueColor(pickedObjectId, pickColor)
        }

        // Prepare a drawable for the placemark's leader, if requested. Enqueue the leader drawable before the icon
        // drawable in order to give the icon visual priority over the leader.
        if (mustDrawLeader(rc)) {
            // Compute the placemark's Cartesian ground point.
            rc.geographicToCartesian(position, AltitudeMode.CLAMP_TO_GROUND, groundPoint)

            // If the leader is visible, enqueue a drawable leader for processing on the OpenGL thread.
            if (rc.frustum.intersectsSegment(groundPoint, placePoint)) {
                val pool = rc.getDrawablePool<DrawableLines>()
                val drawable = DrawableLines.obtain(pool)
                prepareDrawableLeader(rc, drawable)
                rc.offerShapeDrawable(drawable, cameraDistance)
            }
        }

        // Prepare image transformation matrix
        prepareImageTransform(rc.camera, offsetX, offsetY, scaleX, scaleY)

        // If the placemark's icon is visible, enqueue a drawable icon for processing on the OpenGL thread.
        imageTransform.boundingRectForUnitSquare(imageBounds)
        if (rc.frustum.intersectsViewport(imageBounds)) {
            val pool = rc.getDrawablePool<DrawableScreenTexture>()
            val drawable = DrawableScreenTexture.obtain(pool)
            prepareDrawableIcon(rc, drawable, activeTexture)
            rc.offerShapeDrawable(drawable, cameraDistance)
        }

        // If there's a label, perform these same operations for the label texture.
        if ((!isEyeDistanceScaling || cameraDistance <= eyeDistanceScalingLabelThreshold) && mustDrawLabel(rc)) {
            // Render the label's texture when the label's position is in the frustum. If the label's position is outside
            // the frustum we don't do anything. This ensures that label textures are rendered only as necessary.
            rc.getText(label, activeAttributes.labelAttributes, rc.frustum.containsPoint(placePoint))?.let { labelTexture ->
                val w = labelTexture.width.toDouble()
                val h = labelTexture.height.toDouble()
                val s = activeAttributes.labelAttributes.scale * visibilityScale
                activeAttributes.labelAttributes.textOffset.offsetForSize(w, h, offset)
                labelTransform.setTranslation(
                    screenPlacePoint.x - offset.x * s,
                    screenPlacePoint.y - offset.y * s,
                    screenPlacePoint.z
                )
                labelTransform.setScale(w * s, h * s, 1.0)
                labelTransform.boundingRectForUnitSquare(labelBounds)
                if (rc.frustum.intersectsViewport(labelBounds)) {
                    val pool = rc.getDrawablePool<DrawableScreenTexture>()
                    val drawable = DrawableScreenTexture.obtain(pool)
                    prepareDrawableLabel(rc, drawable, labelTexture)
                    rc.offerShapeDrawable(drawable, cameraDistance)
                }
            }
        }

        // Enqueue a picked object that associates the placemark's icon and leader with its picked object ID.
        if (rc.isPickMode && rc.drawableCount != drawableCount) {
            rc.offerPickedObject(PickedObject.fromRenderable(pickedObjectId, this, rc.currentLayer))
        }
    }

    /**
     * Determines the placemark attributes to use for the current render pass.
     *
     * @param rc the current render context
     */
    protected open fun determineActiveAttributes(rc: RenderContext) {
        val highlightAttributes = highlightAttributes
        activeAttributes = if (isHighlighted && highlightAttributes != null) highlightAttributes else attributes
    }

    /**
     * Prepare image transform matrix according to specified parameters
     *
     * @param camera current camera view
     * @param offsetX offset along X axis
     * @param offsetY offset along X axis
     * @param scaleX scaled width
     * @param scaleY scaled height
     */
    protected open fun prepareImageTransform(
        camera: Camera, offsetX: Double, offsetY: Double, scaleX: Double, scaleY: Double
    ) {
        // Initialize the unit square transform to the identity matrix.
        imageTransform.setToIdentity()

        // Position image on screen
        imageTransform.multiplyByTranslation(
            screenPlacePoint.x, screenPlacePoint.y, screenPlacePoint.z
        )

        // Divide Z by 2^24 to prevent texture clipping when tilting (where 24 is depth buffer bit size).
        // Doing so will limit depth range to (diagonal length)/2^24 and make its value within 0..1 range.
        imageTransform.multiplyByScale(1.0, 1.0, 1.0 / (1 shl 24))

        // Perform the tilt so that the image tilts back from its base into the view volume
        val actualTilt = if (imageTiltReference == OrientationMode.RELATIVE_TO_GLOBE)
            camera.tilt + imageTilt else imageTilt
        if (actualTilt.inDegrees != 0.0) imageTransform.multiplyByRotation(-1.0, 0.0, 0.0, actualTilt)

        // Perform image rotation
        val actualRotation = if (imageRotationReference == OrientationMode.RELATIVE_TO_GLOBE)
            camera.heading - imageRotation else -imageRotation
        if (actualRotation.inDegrees != 0.0) imageTransform.multiplyByRotation(0.0, 0.0, 1.0, actualRotation)

        // Apply pivot translation
        imageTransform.multiplyByTranslation(-offsetX, -offsetY, 0.0)

        // Apply scale
        imageTransform.multiplyByScale(scaleX, scaleY, 1.0)
    }

    /**
     * Prepares this placemark's icon or symbol for processing in a subsequent drawing pass. Implementations must be
     * careful not to leak resources from Placemark into the Drawable.
     *
     * @param rc       the current render context
     * @param drawable the Drawable to be prepared
     */
    protected open fun prepareDrawableIcon(rc: RenderContext, drawable: DrawableScreenTexture, activeTexture: Texture?) {
        // Use the basic GLSL program to draw the placemark's icon.
        drawable.program = rc.getShaderProgram { BasicShaderProgram() }

        // Use the plaemark's unit square transform matrix.
        drawable.unitSquareTransform.copy(imageTransform)

        // Configure the drawable according to the placemark's active attributes. Use a color appropriate for the pick
        // mode. When picking use a unique color associated with the picked object ID. Use the texture associated with
        // the active attributes' image source and its associated tex coord transform. If the texture is not specified
        // or not available, draw a simple colored square.
        drawable.color.copy(if (rc.isPickMode) pickColor else activeAttributes.imageColor)
        drawable.opacity = if (rc.isPickMode) 1f else rc.currentLayer.opacity
        drawable.texture = activeTexture
        drawable.enableDepthTest = activeAttributes.isDepthTest
    }

    /**
     * Prepares this placemark's label for processing in a subsequent drawing pass. Implementations must be
     * careful not to leak resources from Placemark into the Drawable.
     *
     * @param rc       the current render context
     * @param drawable the Drawable to be prepared
     */
    protected open fun prepareDrawableLabel(rc: RenderContext, drawable: DrawableScreenTexture, labelTexture: Texture) {
        // Use the basic GLSL program to draw the placemark's label.
        drawable.program = rc.getShaderProgram { BasicShaderProgram() }

        // Use the label's unit square transform matrix.
        drawable.unitSquareTransform.copy(labelTransform)

        // Configure the drawable according to the active label attributes. Use a color appropriate for the pick mode. When
        // picking use a unique color associated with the picked object ID. Use the texture associated with the active
        // attributes' text image and its associated tex coord transform. The text texture includes the appropriate
        // color for drawing, specifying white for normal drawing ensures the color multiplication in the shader results
        // in the texture's color.
        if (rc.isPickMode) drawable.color.copy(pickColor) else drawable.color.set(1f, 1f, 1f, 1f)
        drawable.opacity = if (rc.isPickMode) 1f else rc.currentLayer.opacity
        drawable.texture = labelTexture
        drawable.enableDepthTest = activeAttributes.labelAttributes.isDepthTest
    }

    /**
     * Prepares this placemark's leader for drawing in a subsequent drawing pass. Implementations must be careful not to
     * leak resources from Placemark into the Drawable.
     *
     * @param rc       the current render context
     * @param drawable the Drawable to be prepared
     */
    protected open fun prepareDrawableLeader(rc: RenderContext, drawable: DrawableLines) {
        // Use the basic GLSL program to draw the placemark's leader.
        drawable.program = rc.getShaderProgram { TriangleShaderProgram() }

        var vertexIndex = 0
        val upperLeftCorner = encodeOrientationVector(-1f, 1f)
        val lowerLeftCorner = encodeOrientationVector(-1f, -1f)
        drawable.vertexPoints[vertexIndex++] = (placePoint.x - groundPoint.x).toFloat()
        drawable.vertexPoints[vertexIndex++] = (placePoint.y - groundPoint.y).toFloat()
        drawable.vertexPoints[vertexIndex++] = (placePoint.z - groundPoint.z).toFloat()
        drawable.vertexPoints[vertexIndex++] = upperLeftCorner
        drawable.vertexPoints[vertexIndex++] = 0f

        drawable.vertexPoints[vertexIndex++] = (placePoint.x - groundPoint.x).toFloat()
        drawable.vertexPoints[vertexIndex++] = (placePoint.y - groundPoint.y).toFloat()
        drawable.vertexPoints[vertexIndex++] = (placePoint.z - groundPoint.z).toFloat()
        drawable.vertexPoints[vertexIndex++] = lowerLeftCorner
        drawable.vertexPoints[vertexIndex++] = 0f

        drawable.vertexPoints[vertexIndex++] = (placePoint.x - groundPoint.x).toFloat()
        drawable.vertexPoints[vertexIndex++] = (placePoint.y - groundPoint.y).toFloat()
        drawable.vertexPoints[vertexIndex++] = (placePoint.z - groundPoint.z).toFloat()
        drawable.vertexPoints[vertexIndex++] = upperLeftCorner
        drawable.vertexPoints[vertexIndex++] = 0f

        drawable.vertexPoints[vertexIndex++] = (placePoint.x - groundPoint.x).toFloat()
        drawable.vertexPoints[vertexIndex++] = (placePoint.y - groundPoint.y).toFloat()
        drawable.vertexPoints[vertexIndex++] = (placePoint.z - groundPoint.z).toFloat()
        drawable.vertexPoints[vertexIndex++] = lowerLeftCorner
        drawable.vertexPoints[vertexIndex++] = 0f

        drawable.vertexPoints[vertexIndex++] = 0.0f
        drawable.vertexPoints[vertexIndex++] = 0.0f
        drawable.vertexPoints[vertexIndex++] = 0.0f
        drawable.vertexPoints[vertexIndex++] = upperLeftCorner
        drawable.vertexPoints[vertexIndex++] = 0f

        drawable.vertexPoints[vertexIndex++] = 0.0f
        drawable.vertexPoints[vertexIndex++] = 0.0f
        drawable.vertexPoints[vertexIndex++] = 0.0f
        drawable.vertexPoints[vertexIndex++] = lowerLeftCorner
        drawable.vertexPoints[vertexIndex++] = 0f

        drawable.vertexPoints[vertexIndex++] = 0.0f
        drawable.vertexPoints[vertexIndex++] = 0.0f
        drawable.vertexPoints[vertexIndex++] = 0.0f
        drawable.vertexPoints[vertexIndex++] = upperLeftCorner
        drawable.vertexPoints[vertexIndex++] = 0f

        drawable.vertexPoints[vertexIndex++] = 0.0f
        drawable.vertexPoints[vertexIndex++] = 0.0f
        drawable.vertexPoints[vertexIndex++] = 0.0f
        drawable.vertexPoints[vertexIndex++] = lowerLeftCorner
        drawable.vertexPoints[vertexIndex] = 0f

        // Compute the drawable's modelview-projection matrix, relative to the placemark's ground point.
        drawable.mvpMatrix.copy(rc.modelviewProjection)
        drawable.mvpMatrix.multiplyByTranslation(groundPoint.x, groundPoint.y, groundPoint.z)

        // Configure the drawable according to the placemark's active leader attributes. Use a color appropriate for the
        // pick mode. When picking use a unique color associated with the picked object ID.
        drawable.color.copy(if (rc.isPickMode) pickColor else activeAttributes.leaderAttributes.outlineColor)
        drawable.opacity = if (rc.isPickMode) 1f else rc.currentLayer.opacity
        drawable.lineWidth = activeAttributes.leaderAttributes.outlineWidth
        drawable.enableDepthTest = activeAttributes.leaderAttributes.isDepthTest
    }

    /**
     * Determines if a label should and can be drawn.
     *
     * @return True if there is a valid label and label attributes.
     */
    protected open fun mustDrawLabel(rc: RenderContext) = activeAttributes.isDrawLabel && !label.isNullOrEmpty()

    /**
     * Determines if a leader-line should and can be drawn.
     *
     * @return True if leader-line directive is enabled and there are valid leader-line attributes.
     */
    protected open fun mustDrawLeader(rc: RenderContext) =
        activeAttributes.isDrawLeader && (isLeaderPickingEnabled || !rc.isPickMode) &&
                altitudeMode != AltitudeMode.CLAMP_TO_GROUND && !rc.globe.is2D

    companion object {
        /**
         * The default eye distance above which to reduce the size of this placemark, in meters.
         * If [Placemark.isEyeDistanceScaling] is true, this placemark's image, label and leader sizes are reduced as
         * the eye distance increases beyond this threshold.
         */
        const val DEFAULT_EYE_DISTANCE_SCALING_THRESHOLD = 4e5
        protected const val DEFAULT_DEPTH_OFFSET = -0.03
        private val placePoint = Vec3()
        private val scratchVector = Vec3()
        private val screenPlacePoint = Vec3()
        private val groundPoint = Vec3()
        private val offset = Vec2()
        private val imageTransform = Matrix4()
        private val labelTransform = Matrix4()
        private val imageBounds = Viewport()
        private val labelBounds = Viewport()

        /**
         * This factory method creates a Placemark and an associated PlacemarkAttributes bundle that draws a simple square
         * centered on the supplied position with the given size and color.
         *
         * @param position  The geographic position where the placemark is drawn.
         * @param color     The color of the placemark.
         * @param pixelSize The width and height of the placemark.
         *
         * @return A new Placemark with a PlacemarkAttributes bundle.
         */
        @JvmStatic
        fun createWithColorAndSize(position: Position, color: Color, pixelSize: Int) =
            Placemark(position, PlacemarkAttributes().apply {
                imageColor = color
                imageScale = pixelSize.toDouble()
            })

        /**
         * This factory method creates a Placemark and an associated PlacemarkAttributes bundle that draws the given image
         * centered on the supplied position.
         *
         * @param position    The geographic position with the placemark is drawn.
         * @param imageSource The object containing the image that is drawn.
         *
         * @return A new Placemark with a PlacemarkAttributes bundle.
         */
        @JvmStatic
        fun createWithImage(position: Position, imageSource: ImageSource) =
            Placemark(position, PlacemarkAttributes.createWithImage(imageSource))

        /**
         * This factory method creates a Placemark and an associated PlacemarkAttributes bundle (with TextAttributes) that
         * draws the given image centered on the supplied position with a nearby label.
         *
         * @param position    The geographic position with the placemark is drawn.
         * @param imageSource The object containing the image that is drawn.
         * @param label       The text that is drawn near the image. This parameter becomes the placemark's displayName
         *                    property.
         *
         * @return A new Placemark with a PlacemarkAttributes bundle containing TextAttributes.
         */
        @JvmStatic
        fun createWithImageAndLabel(
            position: Position, imageSource: ImageSource, label: String
        ) = Placemark(position, PlacemarkAttributes.createWithImage(imageSource), label)
    }
}