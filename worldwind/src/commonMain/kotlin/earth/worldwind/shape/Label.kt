package earth.worldwind.shape

import earth.worldwind.PickedObject
import earth.worldwind.draw.DrawableScreenTexture
import earth.worldwind.geom.*
import earth.worldwind.geom.Angle.Companion.ZERO
import earth.worldwind.globe.Globe
import earth.worldwind.render.AbstractRenderable
import earth.worldwind.render.Color
import earth.worldwind.render.RenderContext
import earth.worldwind.render.program.BasicShaderProgram
import kotlin.jvm.JvmOverloads
import kotlin.math.sqrt

/**
 * Represents a label at a geographic position. Labels display a single line of text according to specified [TextAttributes].
 */
open class Label @JvmOverloads constructor(
    /**
     * The label's geographic position.
     */
    position: Position,
    /**
     * Indicates the text displayed by this label. The returned string may be null, indicating that this label displays
     * nothing.
     */
    var text: String? = null,
    /**
     * Indicates this label's "normal" attributes, that is the attributes used when the label's highlighted flag is
     * false. If null and this label is not highlighted, this label displays nothing.
     */
    var attributes: TextAttributes = TextAttributes()
) : AbstractRenderable(text), Highlightable, Movable {
    /**
     * The label's geographic position.
     */
    var position = Position(position)
        set(value) { field.copy(value) }
    /**
     * The label's altitude mode. See [AltitudeMode]
     */
    override var altitudeMode = AltitudeMode.ABSOLUTE
        set(value) {
            field = value
            placePointDirty = true
        }
    /**
     * Indicates the rotation applied to this label. The rotation represents clockwise clockwise degrees relative to
     * this label's labelRotationMode.
     */
    var rotation = ZERO
    /**
     * Indicates the orientation mode used to interpret this label's rotation. Label rotation may be either relative to
     * the screen or relative to the globe, as indicated by the following allowable values:
     * `OrientationMode.RELATIVE_TO_SCREEN` - The label's orientation is fixed relative to the screen. Rotation indicates
     * clockwise degrees relative to the screen's vertical axis. This is the default mode.
     * `OrientationMode.RELATIVE_TO_GLOBE` - The label's orientation is fixed relative to the globe. Rotation indicates
     * clockwise degrees relative to North.
     */
    var rotationMode = OrientationMode.RELATIVE_TO_SCREEN
    /**
     * Determines whether the normal or highlighted attributes should be used.
     */
    override var isHighlighted = false
    /**
     * The attributes used when this label's highlighted flag is true. If null and the highlighted flag is true,
     * this label's normal attributes are used. If they, too, are null, this label displays nothing.
     */
    var highlightAttributes: TextAttributes? = null
    /**
     * The attributes identified for use during the current render pass.
     */
    protected lateinit var activeAttributes: TextAttributes
    /**
     * A position associated with the object that indicates its aggregate geographic position. For a Label, this is
     * simply it's position property.
     */
    override val referencePosition get() = position
    override val isPointShape get() = true
    /**
     * Indicates whether this placemark has visual priority over other shapes in the scene.
     */
    var isAlwaysOnTop = false
    /**
     * Sets the eye altitude, in meters, above which this label is not displayed.
     */
    var visibilityThreshold = 0.0
    /**
     * Optional lambda to control current label visibility based on its attributes, frame render context and camera distance
     */
    var isVisible: ((Label, RenderContext, Double) -> Boolean)? = null
    protected val placePoint = Vec3()
    protected var placePointDirty = true
    // Flips on the first successful [placePoint] conversion and never resets — lets [doRender]
    // render with a stale placePoint when the budget refuses a recompute (avoids blinking).
    protected var hasPlacePoint = false
    protected val cachedPosition = Position()
    protected var cachedGlobeState: Globe.State? = null
    protected var cachedElevationTimestamp = 0L
    protected var cachedVerticalExaggeration = 0.0

    companion object {
        /**
         * The default amount of screen depth offset applied to the label's text during rendering. Values less than zero
         * bias depth values toward the viewer.
         */
        protected const val DEFAULT_DEPTH_OFFSET = -0.1

        /**
         * The label's properties associated with the current render pass.
         */
        private val renderData = RenderData()
    }

    /**
     * Moves the shape over the globe's surface. For a Label, this simply change its position.
     *
     * @param globe    not used.
     * @param position the new position of the shape's reference position.
     */
    override fun moveTo(globe: Globe, position: Position) { this.position.copy(position) }

    override fun doRender(rc: RenderContext) {
        if (text?.isEmpty() != false) return  // no text to render

        // Filter out renderable outside projection limits.
        if (rc.globe.projectionLimits?.contains(position) == false) return

        // Get cached state
        val globeState = rc.globeState
        val elevationTimestamp = rc.elevationModelTimestamp
        val verticalExaggeration = rc.globe.verticalExaggeration

        // Check if cartesian points should be recalculated
        val effectiveAltitudeMode = if (rc.globe.is2D) AltitudeMode.CLAMP_TO_GROUND else altitudeMode
        val isTerrainDependent = effectiveAltitudeMode == AltitudeMode.CLAMP_TO_GROUND
                || effectiveAltitudeMode == AltitudeMode.RELATIVE_TO_GROUND
        val globeChanged = globeState != cachedGlobeState
        val terrainChanged = elevationTimestamp != cachedElevationTimestamp || verticalExaggeration != cachedVerticalExaggeration
        val positionChanged = cachedPosition != position
        if (globeChanged || (isTerrainDependent && terrainChanged) || positionChanged) placePointDirty = true

        // Store cached state
        if (positionChanged) cachedPosition.copy(position)
        cachedGlobeState = globeState
        cachedElevationTimestamp = elevationTimestamp
        cachedVerticalExaggeration = verticalExaggeration

        // Compute the label's Cartesian model point — gated by the per-frame assembly budget.
        // First-time computes defer (no drawable); recomputes fall through with the stale value
        // to avoid blinking when terrain updates invalidate faster than the budget can serve.
        if (placePointDirty) {
            if (rc.canAssembleGeometry()) {
                rc.geographicToCartesian(position, effectiveAltitudeMode, placePoint)
                placePointDirty = false
                hasPlacePoint = true
            } else if (!hasPlacePoint) return
        }

        // Compute the square camera distance to the place point, the value which is used for ordering
        // the label drawable and determining the amount of depth offset to apply
        renderData.cameraDistanceSq = if (isAlwaysOnTop) 0.0
        else if (rc.globe.is2D) rc.viewingDistance * rc.viewingDistance
        else rc.cameraPoint.distanceToSquared(placePoint)

        // Do not draw labels after the specified threshold
        if (visibilityThreshold > 0.0 && renderData.cameraDistanceSq > visibilityThreshold * visibilityThreshold) return

        // Do not draw label if it does not pass external visibility check
        if (isVisible?.invoke(this, rc, sqrt(renderData.cameraDistanceSq)) == false) return

        // Compute a screen depth offset appropriate for the current viewing parameters.
        var depthOffset = 0.0
        if (renderData.cameraDistanceSq < rc.horizonDistance * rc.horizonDistance) depthOffset = DEFAULT_DEPTH_OFFSET

        // Project the label's model point to screen coordinates, using the screen depth offset to push the screen
        // point's z component closer to the eye point.
        if (!rc.projectWithDepth(placePoint, depthOffset, renderData.screenPlacePoint)) return  // clipped by the near plane or the far plane

        // Select the currently active attributes. Don't render anything if the attributes are unspecified.
        determineActiveAttributes(rc)

        // Keep track of the drawable count to determine whether this label has enqueued drawables.
        val drawableCount = rc.drawableCount
        if (rc.isPickMode) {
            renderData.pickedObjectId = rc.nextPickedObjectId()
            PickedObject.identifierToUniqueColor(renderData.pickedObjectId, renderData.pickColor)
        }

        // Enqueue drawables for processing on the OpenGL thread.
        makeDrawable(rc)

        // Enqueue a picked object that associates the label's drawables with its picked object ID.
        if (rc.isPickMode && rc.drawableCount != drawableCount) {
            rc.offerPickedObject(PickedObject.fromRenderable(renderData.pickedObjectId, this, rc.currentLayer))
        }
    }

    protected open fun determineActiveAttributes(rc: RenderContext) {
        val highlightAttributes = highlightAttributes
        activeAttributes = if (isHighlighted && highlightAttributes != null) highlightAttributes else attributes
    }

    protected open fun makeDrawable(rc: RenderContext) {
        // Render the label's texture when the label's position is in the frustum. If the label's position is outside
        // the frustum we don't do anything. This ensures that label textures are rendered only as necessary.
        val texture = rc.getText(text, activeAttributes, rc.frustum.containsPoint(placePoint)) ?: return

        // Initialize the unit square transform to the identity matrix.
        renderData.unitSquareTransform.setToIdentity()

        // Apply the label's translation according to its text size and text offset. The text offset is defined with its
        // origin at the text's bottom-left corner and axes that extend up and to the right from the origin point.
        val w = texture.width.toDouble()
        val h = texture.height.toDouble()
        val s = activeAttributes.scale
        activeAttributes.textOffset.offsetForSize(w, h, renderData.offset)
        renderData.unitSquareTransform.setTranslation(
            renderData.screenPlacePoint.x - renderData.offset.x * s,
            renderData.screenPlacePoint.y - renderData.offset.y * s,
            renderData.screenPlacePoint.z
        )

        // Apply the label's rotation according to its rotation value and orientation mode. The rotation is applied
        // such that the text rotates around the text offset point.
        val actualRotation = if (rotationMode == OrientationMode.RELATIVE_TO_GLOBE)
            rc.camera.heading - rotation else -rotation
        if (actualRotation != ZERO) {
            renderData.unitSquareTransform.multiplyByTranslation(
                renderData.offset.x, renderData.offset.y, 0.0
            )
            renderData.unitSquareTransform.multiplyByRotation(0.0, 0.0, 1.0, actualRotation)
            renderData.unitSquareTransform.multiplyByTranslation(
                -renderData.offset.x, -renderData.offset.y, 0.0
            )
        }

        // Apply the label's translation and scale according to its text size.
        renderData.unitSquareTransform.multiplyByScale(w * s, h * s, 1.0)
        renderData.unitSquareTransform.boundingRectForUnitSquare(renderData.screenBounds)
        if (!rc.frustum.intersectsViewport(renderData.screenBounds)) return  // the text is outside the viewport

        // Obtain a pooled drawable and configure it to draw the label's text.
        val pool = rc.getDrawablePool(DrawableScreenTexture.KEY)
        val drawable = DrawableScreenTexture.obtain(pool)

        // Use the basic GLSL program to draw the text.
        drawable.program = rc.getShaderProgram(BasicShaderProgram.KEY) { BasicShaderProgram() }

        // Use the text's unit square transform matrix.
        drawable.unitSquareTransform.copy(renderData.unitSquareTransform)

        // Configure the drawable according to the active attributes. Use a color appropriate for the pick mode. When
        // picking use a unique color associated with the picked object ID. Use the texture associated with the active
        // attributes' text image and its associated tex coord transform. The text texture includes the appropriate
        // color for drawing, specifying white for normal drawing ensures the color multiplication in the shader results
        // in the texture's color.
        if (rc.isPickMode) drawable.color.copy(renderData.pickColor)
        else drawable.color.set(1f, 1f, 1f, 1f)
        drawable.opacity = if (rc.isPickMode) 1f else rc.currentLayer.opacity
        drawable.texture = texture
        drawable.enableDepthTest = activeAttributes.isDepthTest

        // Enqueue a drawable for processing on the OpenGL thread.
        rc.offerShapeDrawable(drawable, renderData.cameraDistanceSq)
    }

    /**
     * Properties associated with the label during a render pass.
     */
    protected open class RenderData {
        /**
         * The screen coordinate point corresponding to the label's position.
         */
        val screenPlacePoint = Vec3()
        /**
         * The screen coordinate offset corresponding to the active attributes.
         */
        val offset = Vec2()
        /**
         * The screen coordinate transform to apply to the drawable unit square.
         */
        val unitSquareTransform = Matrix4()
        /**
         * The screen viewport indicating the label's screen bounds.
         */
        val screenBounds = Viewport()
        /**
         * Unique identifier associated with the label during picking.
         */
        var pickedObjectId = 0
        /**
         * Unique color used to display the label during picking.
         */
        val pickColor = Color()
        /**
         * The squared distance from the camera position to the label position, used for drawable ordering.
         */
        var cameraDistanceSq = 0.0
    }
}