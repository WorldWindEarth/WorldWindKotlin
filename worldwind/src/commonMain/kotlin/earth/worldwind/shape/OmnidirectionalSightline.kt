package earth.worldwind.shape

import earth.worldwind.PickedObject
import earth.worldwind.draw.DrawableSightline
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.POS90
import earth.worldwind.geom.BoundingSphere
import earth.worldwind.geom.Position
import earth.worldwind.geom.Vec3
import earth.worldwind.globe.Globe
import earth.worldwind.render.AbstractRenderable
import earth.worldwind.render.Color
import earth.worldwind.render.RenderContext
import earth.worldwind.render.program.SightlineProgram
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import kotlin.jvm.JvmOverloads

/**
 * Displays an omnidirectional sightline's visibility within the WorldWind scene. The sightline's placement and area of
 * potential visibility are represented by a Cartesian sphere with a center position and a range. Terrain features
 * within the sphere are considered visible if there is a direct line-of-sight between the center position and a given
 * terrain point.
 * <br>
 * OmnidirectionalSightline displays an overlay on the WorldWind terrain indicating which terrain features are visible,
 * and which are occluded. Visible terrain features, those having a direct line-of-sight to the center position, appear
 * in the sightline's normal attributes or its highlight attributes, depending on the highlight state. Occluded terrain
 * features appear in the sightline's occlude attributes, regardless of highlight state. Terrain features outside the
 * sightline's range are excluded from the overlay.
 * <br>
 * <h3>Limitations and Planned Improvements</h3> OmnidirectionalSightline is currently limited to terrain-based
 * occlusion, and does not incorporate other 3D scene elements during visibility determination. Subsequent iterations
 * will support occlusion of both terrain and 3D polygons. The visibility overlay is drawn in ShapeAttributes'
 * interior color only. Subsequent iterations will add an outline where the sightline's range intersects the scene, and
 * will display the sightline's geometry as an outline. OmnidirectionalSightline requires OpenGL ES 2.0
 * extension [GL_OES_depth_texture](https://www.khronos.org/registry/OpenGL/extensions/OES/OES_depth_texture.txt).
 * Subsequent iterations may relax this requirement.
 */
open class OmnidirectionalSightline @JvmOverloads constructor(
    /**
     * Indicates the geographic position where this sightline is centered.
     */
    position: Position,
    /**
     * Indicates this sightline's range. Range represents the sightline's transmission distance in meters from its
     * center position.
     */
    range: Double,
    /**
     * Indicates this sightline's "normal" attributes. These attributes are used for the sightline's overlay when the
     * highlighted flag is false, and there is a direct line-of-sight from the sightline's center position to a terrain
     * feature. If null and this sightline is not highlighted, visible terrain features are excluded from
     * the overlay.
     */
    override var attributes: ShapeAttributes = ShapeAttributes()
) : AbstractRenderable(), Attributable, Highlightable, Movable {
    /**
     * Indicates the geographic position where this sightline is centered.
     */
    var position = Position(position)
        set(value) {
            field.copy(value)
        }
    /**
     * Indicates this sightline's range. Range represents the sightline's transmission distance in meters from its
     * center position.
     *
     * @throws IllegalArgumentException If the range is negative
     */
    var range = range
        set(value) {
            require(value >= 0) {
                logMessage(ERROR, "OmnidirectionalSightline", "setRange", "invalidRange")
            }
            field = value
        }
    /**
     * The sightline's altitude mode. See [AltitudeMode]
     */
    override var altitudeMode = AltitudeMode.ABSOLUTE
    /**
     * Determines whether the normal or highlighted attributes should be used for visible features.
     */
    override var isHighlighted = false
    /**
     * The attributes to use for visible features, when the sightline is highlighted.
     */
    override var highlightAttributes: ShapeAttributes? = null
    /**
     * The attributes to use for occluded features.
     */
    var occludeAttributes = ShapeAttributes().apply { interiorColor.copy(Color(1f, 0f, 0f, 1f)) }
    /**
     * A position associated with the object that indicates its aggregate geographic position. For an
     * OmnidirectionalSightline, this is simply it's position property.
     */
    override val referencePosition get() = position
    /**
     * The attributes to use for visible features during the current render pass.
     */
    protected lateinit var activeAttributes: ShapeAttributes

    private val centerPoint = Vec3()
    private var pickedObjectId = 0
    private val pickColor = Color()
    private val boundingSphere = BoundingSphere()

    init {
        require(range >= 0) {
            logMessage(ERROR, "OmnidirectionalSightline", "constructor", "invalidRange")
        }
    }

    /**
     * Moves the sightline over the globe's surface.
     *
     * @param globe    not used.
     * @param position the new position of the sightline's reference position.
     */
    override fun moveTo(globe: Globe, position: Position) { this.position.copy(position) }

    override fun doRender(rc: RenderContext) {
        // Compute this sightline's center point in Cartesian coordinates.
        if (!determineCenterPoint(rc)) return

        // Don't render anything if the sightline's coverage area is not visible.
        if (!isVisible(rc)) return

        // Select the currently active attributes.
        determineActiveAttributes(rc)

        // Configure the pick color when rendering in pick mode.
        if (rc.isPickMode) {
            pickedObjectId = rc.nextPickedObjectId()
            PickedObject.identifierToUniqueColor(pickedObjectId, pickColor)
        }

        // Enqueue drawables for processing on the OpenGL thread.
        makeDrawable(rc)

        // Enqueue a picked object that associates the sightline's drawables with its picked object ID.
        if (rc.isPickMode) rc.offerPickedObject(PickedObject.fromRenderable(pickedObjectId, this, rc.currentLayer))
    }

    protected open fun determineCenterPoint(rc: RenderContext): Boolean {
        rc.geographicToCartesian(position, altitudeMode, centerPoint)
        return centerPoint.x != 0.0 && centerPoint.y != 0.0 && centerPoint.z != 0.0
    }

    protected open fun isVisible(rc: RenderContext): Boolean {
        val cameraDistance = centerPoint.distanceTo(rc.cameraPoint)
        val pixelSizeMeters = rc.pixelSizeAtDistance(cameraDistance)
        return if (range < pixelSizeMeters) false // The range is zero, or is less than one screen pixel
        else boundingSphere.set(centerPoint, range).intersectsFrustum(rc.frustum)
    }

    protected open fun determineActiveAttributes(rc: RenderContext) {
        val highlightAttributes = highlightAttributes
        activeAttributes = if (isHighlighted && highlightAttributes != null) highlightAttributes else attributes
    }

    protected open fun makeDrawable(rc: RenderContext) {
        // Obtain a pooled drawable and configure it to draw the sightline's coverage.
        val pool = rc.getDrawablePool(DrawableSightline.KEY)
        val drawable = DrawableSightline.obtain(pool)

        // Choose omnidirectional mode
        drawable.omnidirectional = true

        // Set FOV to 90
        drawable.fieldOfView = POS90

        // Compute the transform from sightline local coordinates to world coordinates.
        rc.globe.cartesianToLocalTransform(
            centerPoint.x, centerPoint.y, centerPoint.z, drawable.centerTransform
        )

        // Clamp range to max float value as OpenGL drawable operates with float range
        drawable.range = range.coerceIn(0.0, Float.MAX_VALUE.toDouble()).toFloat()

        // Configure the drawable colors according to the current attributes. When picking use a unique color associated
        // with the picked object ID. Null attributes indicate that nothing is drawn.
        drawable.visibleColor.copy(if (rc.isPickMode) pickColor else activeAttributes.interiorColor)
        drawable.occludedColor.copy(if (rc.isPickMode) pickColor else occludeAttributes.interiorColor)

        // Use the sightline GLSL program to draw the coverage.
        drawable.program = rc.getShaderProgram(SightlineProgram.KEY) { SightlineProgram() }

        // Enqueue a drawable for processing on the OpenGL thread.
        rc.offerSurfaceDrawable(drawable, zOrder)
    }
}