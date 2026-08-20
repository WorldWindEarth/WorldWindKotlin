package earth.worldwind.shape

import earth.worldwind.PickedObject
import earth.worldwind.draw.DrawableSightline
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.POS180
import earth.worldwind.geom.Angle.Companion.POS360
import earth.worldwind.geom.Angle.Companion.ZERO
import earth.worldwind.geom.BoundingSphere
import earth.worldwind.geom.Location
import earth.worldwind.geom.Position
import earth.worldwind.geom.Vec3
import earth.worldwind.globe.Globe
import earth.worldwind.render.AbstractRenderable
import earth.worldwind.render.Color
import earth.worldwind.render.RenderContext
import earth.worldwind.render.program.DirectionalDepthProgram
import earth.worldwind.render.program.PackedDepthProgram
import earth.worldwind.render.program.SightlineProgram
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import kotlin.jvm.JvmOverloads
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Displays a realtime sightline's visibility within the WorldWind scene, resolved every frame
 * against the rendered geometry (terrain and world-space 3D content such as 3D Tiles meshes,
 * filled shapes and COLLADA models) — unlike [ViewshedSightline], which ray-marches a static
 * elevation grid. The sightline's placement and coverage are defined by a center position, a
 * range, and a horizontal wedge given by [heading] and [fieldOfView]; the default 360-degree
 * field of view covers the full sphere (the former OmnidirectionalSightline), while narrower
 * angles model directional sensors (the former DirectionalSightline, no longer limited to 90).
 * <br>
 * Terrain and scene features within the coverage appear in the sightline's normal attributes
 * or its highlight attributes when there is a direct line-of-sight to the center position, and
 * in the occlude attributes otherwise. Features outside the range or wedge are untinted.
 * <br>
 * <h3>Limitations and Planned Improvements</h3> Surface decals and screen-space sprites such as
 * placemarks and leader lines are intentionally excluded as they do not represent occluding
 * volumes. The visibility overlay is drawn in ShapeAttributes' interior color only. Requires a
 * depth-texture-capable context (GLES3 / WebGL2 / desktop GL3+); on older contexts the
 * sightline is not rendered.
 */
open class RealtimeSightline @JvmOverloads constructor(
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
     * The sightline's heading clockwise from North. Irrelevant at the default 360-degree field of view.
     */
    var heading: Angle = ZERO,
    /**
     * The sightline's horizontal field of view about [heading]. 360 degrees covers the full sphere.
     */
    fieldOfView: Angle = POS360,
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
                logMessage(ERROR, "RealtimeSightline", "setRange", "The range $value is invalid")
            }
            field = value
        }

    /**
     * The sightline's horizontal field of view about [heading]. Elevation coverage above the
     * horizon is capped at half the field of view for angles below 180 degrees. A zero field
     * of view is an empty wedge; the sightline renders nothing.
     *
     * @throws IllegalArgumentException If the field of view is not between 0 and 360 degrees inclusive
     */
    var fieldOfView = fieldOfView
        set(value) {
            require(value >= ZERO && value <= POS360) {
                logMessage(ERROR, "RealtimeSightline", "setFieldOfView", "invalidFieldOfView")
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
     * A position associated with the object that indicates its aggregate geographic position. For a
     * RealtimeSightline, this is simply it's position property.
     */
    override val referencePosition get() = position
    override val isPointShape get() = true

    /**
     * The attributes to use for visible features during the current render pass.
     */
    protected lateinit var activeAttributes: ShapeAttributes

    private val centerPoint = Vec3()
    private val boundingBoxCenter = Vec3()
    private var pickedObjectId = 0
    private val pickColor = Color()
    private val boundingSphere = BoundingSphere()
    private val scratchLocation = Location()

    init {
        require(range >= 0) {
            logMessage(ERROR, "RealtimeSightline", "constructor", "The range $range is invalid")
        }
        require(fieldOfView >= ZERO && fieldOfView <= POS360) {
            logMessage(ERROR, "RealtimeSightline", "constructor", "invalidFieldOfView")
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

        // Publish coverage volume so AbstractShape can keep off-camera shapes alive as occluders.
        rc.sightlineBounds.add(BoundingSphere().set(boundingSphere.center, boundingSphere.radius))

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
        if (fieldOfView < POS180) {
            // Wedge coverage: center the bounding sphere along the heading, half a range out.
            val globeRadius = max(rc.globe.equatorialRadius, rc.globe.polarRadius)
            val loc = position.greatCircleLocation(heading, range / (2.0 * globeRadius), scratchLocation)
            rc.geographicToCartesian(loc.latitude, loc.longitude, position.altitude, altitudeMode, boundingBoxCenter)
        } else boundingBoxCenter.copy(centerPoint)
        return centerPoint.x != 0.0 || centerPoint.y != 0.0 || centerPoint.z != 0.0
    }

    protected open fun isVisible(rc: RenderContext): Boolean {
        if (fieldOfView == ZERO) return false // An empty wedge covers nothing
        val cameraDistance = centerPoint.distanceTo(rc.cameraPoint)
        val pixelSizeMeters = rc.pixelSizeAtDistance(cameraDistance)
        if (range < pixelSizeMeters) return false // The range is zero, or is less than one screen pixel
        val radius = if (fieldOfView < POS180) {
            // Sphere through the wedge apex and the far chord of the coverage cone.
            val chord = 2.0 * range * sin(fieldOfView.inRadians / 2.0)
            (range * range) / sqrt(4.0 * range * range - chord * chord)
        } else range
        return boundingSphere.set(boundingBoxCenter, radius).intersectsFrustum(rc.frustum)
    }

    protected open fun determineActiveAttributes(rc: RenderContext) {
        val highlightAttributes = highlightAttributes
        activeAttributes = if (isHighlighted && highlightAttributes != null) highlightAttributes else attributes
    }

    protected open fun makeDrawable(rc: RenderContext) {
        // Receiver-aware programs (Ogc3dTilesProgram et al.) gate on this to pick their
        // sightline-aware variant. Set before enqueue so the next program-fetch sees it.
        rc.hasActiveSightline = true

        // Two drawables per sightline: a depth-only pass in BACKGROUND so [DrawContext.sightlineState]
        // is populated before any receiver fragment runs (3D-tile self-shadow needs this), and an
        // overlay-only pass in SURFACE that tints terrain after opaque receivers have rendered.
        val pool = rc.getDrawablePool(DrawableSightline.KEY)
        fun configure(drawable: DrawableSightline) {
            drawable.sourceKey = this
            drawable.fieldOfView = fieldOfView
            drawable.heading = heading
            rc.globe.cartesianToLocalTransform(centerPoint.x, centerPoint.y, centerPoint.z, drawable.centerTransform)
            drawable.range = range.coerceIn(0.0, Float.MAX_VALUE.toDouble()).toFloat()
            drawable.visibleColor.copy(if (rc.isPickMode) pickColor else activeAttributes.interiorColor)
            drawable.occludedColor.copy(if (rc.isPickMode) pickColor else occludeAttributes.interiorColor)
            drawable.program = rc.getShaderProgram { SightlineProgram() }
            drawable.depthProgram = rc.getShaderProgram { DirectionalDepthProgram() }
            drawable.packedDepthProgram = rc.getShaderProgram { PackedDepthProgram() }
        }

        val depth = DrawableSightline.obtain(pool).also(::configure)
        depth.renderMode = DrawableSightline.RenderMode.DEPTH_ONLY
        rc.offerBackgroundDrawable(depth)

        val overlay = DrawableSightline.obtain(pool).also(::configure)
        overlay.renderMode = DrawableSightline.RenderMode.OVERLAY_ONLY
        rc.offerSurfaceDrawable(overlay, zOrder)
    }
}
