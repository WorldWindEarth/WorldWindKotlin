package earth.worldwind.shape

import earth.worldwind.draw.DrawShapeState
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.ZERO
import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Position
import earth.worldwind.globe.Globe
import earth.worldwind.render.RenderContext
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.kgl.GL_LINE_STRIP
import earth.worldwind.util.kgl.GL_UNSIGNED_SHORT
import kotlin.jvm.JvmOverloads
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 3D ellipsoid centered at a geographic position. Supports three independent semi-axes for
 * a tri-axial ellipsoid; pass `xRadius == yRadius` for a rotation spheroid (the common
 * "two-radius" case of one equatorial radius paired with a polar radius).
 *
 * Local frame at [center]: X east, Y north, Z up. Orientation is aerospace 3-2-1 intrinsic
 * (heading -> pitch -> roll) about the body axes:
 *  * [heading] rotates clockwise from north around Z (yaw).
 *  * [pitch] rotates around the body east (+X) axis; positive tilts the +Y "nose" up.
 *  * [roll] rotates around the body north (+Y) axis; positive tilts the +X "right wing" down.
 *
 * Matches the convention documented on [earth.worldwind.geom.CameraPose]. Lighting opt-in
 * via [ShapeAttributes.isLightingEnabled].
 */
open class Ellipsoid @JvmOverloads constructor(
    center: Position,
    xRadius: Double,
    yRadius: Double,
    zRadius: Double,
    attributes: ShapeAttributes = ShapeAttributes(),
) : AbstractMesh(attributes) {

    /** Geographic position of the ellipsoid's center. */
    var center = Position(center)
        set(value) {
            field.copy(value)
            referencePosition.copy(value)
            reset()
        }

    /** Semi-axis along the local east direction, in metres. */
    var xRadius = xRadius
        set(value) {
            require(value >= 0.0) { logMessage(ERROR, "Ellipsoid", "xRadius", "invalidRadius") }
            field = value
            reset()
        }

    /** Semi-axis along the local north direction, in metres. */
    var yRadius = yRadius
        set(value) {
            require(value >= 0.0) { logMessage(ERROR, "Ellipsoid", "yRadius", "invalidRadius") }
            field = value
            reset()
        }

    /** Semi-axis along the local up (polar) direction, in metres. */
    var zRadius = zRadius
        set(value) {
            require(value >= 0.0) { logMessage(ERROR, "Ellipsoid", "zRadius", "invalidRadius") }
            field = value
            reset()
        }

    /** Rotation around the local up axis, clockwise from north. No effect on rotation spheroids. */
    var heading: Angle = ZERO
        set(value) {
            field = value
            reset()
        }

    /** Rotation around the body east (+X) axis. Positive tilts the +Y axis up ("nose up"). */
    var pitch: Angle = ZERO
        set(value) {
            field = value
            reset()
        }

    /** Rotation around the body north (+Y) axis. Positive tilts the +X axis down ("right wing down"). */
    var roll: Angle = ZERO
        set(value) {
            field = value
            reset()
        }

    /** Number of longitude divisions. Must be >= 4. Higher = smoother silhouette. */
    var slices: Int = DEFAULT_SLICES
        set(value) {
            require(value >= 4) { logMessage(ERROR, "Ellipsoid", "slices", "Must be >= 4") }
            field = value
            reset()
        }

    /** Number of latitude divisions. Must be >= 2. Higher = smoother poles. */
    var stacks: Int = DEFAULT_STACKS
        set(value) {
            require(value >= 2) { logMessage(ERROR, "Ellipsoid", "stacks", "Must be >= 2") }
            field = value
            reset()
        }

    /**
     * Set of principal-plane great circles drawn when [ShapeAttributes.isDrawOutline] is enabled.
     * Each entry is rendered as an independent line strip, so any combination of the equator and
     * the two meridians can be enabled without spurious connecting segments. Defaults to
     * `{ EQUATOR }`.
     */
    var outlineCircles: Set<OutlineCircle> = setOf(OutlineCircle.EQUATOR)
        set(value) {
            field = value
            reset()
        }

    private val frame = Matrix4()

    init {
        require(xRadius >= 0.0) { logMessage(ERROR, "Ellipsoid", "constructor", "invalidRadius") }
        require(yRadius >= 0.0) { logMessage(ERROR, "Ellipsoid", "constructor", "invalidRadius") }
        require(zRadius >= 0.0) { logMessage(ERROR, "Ellipsoid", "constructor", "invalidRadius") }
        referencePosition.copy(center)
    }

    override fun moveTo(globe: Globe, position: Position) {
        center.copy(position)
        super.moveTo(globe, position)
    }

    override fun createSurfaceShape(): AbstractShape? = null

    private fun vertexCount() = (slices + 1) * (stacks + 1)

    override fun computeMeshPoints(rc: RenderContext): FloatArray {
        // Local-frame rotation at center: column 0 = east, column 1 = north, column 2 = up.
        // Translation column is ignored - vertices are stored relative to vertexOrigin
        // (already set to the center cartesian at the proper altitude in [assembleGeometry]).
        rc.globe.geographicToCartesianTransform(center.latitude, center.longitude, 0.0, frame)
        val m = frame.m
        val ex = m[0]; val ey = m[4]; val ez = m[8]
        val nx = m[1]; val ny = m[5]; val nz = m[9]
        val ux = m[2]; val uy = m[6]; val uz = m[10]

        val cH = cos(heading.inRadians); val sH = sin(heading.inRadians)
        val cP = cos(pitch.inRadians);   val sP = sin(pitch.inRadians)
        val cR = cos(roll.inRadians);    val sR = sin(roll.inRadians)

        val pts = FloatArray(vertexCount() * VERTEX_STRIDE)
        var k = 0
        for (j in 0..stacks) {
            val phi = (j.toDouble() / stacks) * PI
            val sinPhi = sin(phi)
            val cosPhi = cos(phi)
            for (i in 0..slices) {
                val theta = (i.toDouble() / slices) * 2.0 * PI
                val sinTheta = sin(theta)
                val cosTheta = cos(theta)

                // Pre-rotation body coords (X east, Y north, Z up).
                val bx = xRadius * sinPhi * cosTheta
                val by = yRadius * sinPhi * sinTheta
                val bz = zRadius * cosPhi

                // Aerospace 3-2-1 intrinsic, applied right-to-left: roll (+Y), pitch (+X),
                // heading (+Z). Convention detailed in the class doc.
                val rx = bx * cR + bz * sR
                val rz = -bx * sR + bz * cR
                val py = by * cP - rz * sP
                val pz = by * sP + rz * cP
                val lx = rx * cH + py * sH
                val ly = -rx * sH + py * cH

                pts[k++] = (lx * ex + ly * nx + pz * ux).toFloat()
                pts[k++] = (lx * ey + ly * ny + pz * uy).toFloat()
                pts[k++] = (lx * ez + ly * nz + pz * uz).toFloat()
            }
        }
        return pts
    }

    override fun computeTexCoords(): FloatArray {
        val coords = FloatArray(vertexCount() * 2)
        var k = 0
        for (j in 0..stacks) {
            val v = j.toFloat() / stacks
            for (i in 0..slices) {
                coords[k++] = i.toFloat() / slices
                coords[k++] = v
            }
        }
        return coords
    }

    override fun computeMeshIndices(): ShortArray {
        // Two outward-facing CCW triangles per quad: (v00, v10, v11) and (v00, v11, v01),
        // where v_ij index = j*(slices+1) + i and j increases from the top pole to the bottom.
        val nLong = slices + 1
        val indices = ShortArray(stacks * slices * 6)
        var k = 0
        for (j in 0 until stacks) {
            for (i in 0 until slices) {
                val v00 = (j * nLong + i).toShort()
                val v01 = (j * nLong + i + 1).toShort()
                val v10 = ((j + 1) * nLong + i).toShort()
                val v11 = ((j + 1) * nLong + i + 1).toShort()
                indices[k++] = v00
                indices[k++] = v10
                indices[k++] = v11
                indices[k++] = v00
                indices[k++] = v11
                indices[k++] = v01
            }
        }
        return indices
    }

    override fun computeOutlineIndices(): ShortArray {
        // Walk enum-declaration order so the layout is deterministic regardless of how
        // [outlineCircles] was constructed. [drawOutline] iterates the same order.
        val selected = OutlineCircle.entries.filter { it in outlineCircles }
        if (selected.isEmpty()) return ShortArray(0)
        val out = ShortArray(selected.sumOf(::circleIndexCount))
        var offset = 0
        for (circle in selected) {
            val strip = circleIndices(circle)
            strip.copyInto(out, offset)
            offset += strip.size
        }
        return out
    }

    private fun circleIndexCount(circle: OutlineCircle) = when (circle) {
        OutlineCircle.EQUATOR -> slices + 1
        OutlineCircle.MERIDIAN_YZ, OutlineCircle.MERIDIAN_XZ -> 2 * (stacks + 1)
    }

    private fun circleIndices(circle: OutlineCircle): ShortArray = when (circle) {
        OutlineCircle.EQUATOR -> equatorIndices()
        // theta = π/2 (i = slices/4) and 3π/2 (i = 3*slices/4) pierce the local y-axis.
        OutlineCircle.MERIDIAN_YZ -> meridianIndices(slices / 4, (3 * slices) / 4)
        // theta = 0 (i = 0) and π (i = slices/2) pierce the local x-axis.
        OutlineCircle.MERIDIAN_XZ -> meridianIndices(0, slices / 2)
    }

    /** Equator strip. Seam vertex i=slices coincides with i=0 so the strip closes. */
    private fun equatorIndices(): ShortArray {
        val nLong = slices + 1
        val jEquator = stacks / 2
        return ShortArray(nLong) { i -> (jEquator * nLong + i).toShort() }
    }

    /**
     * Walks one meridian from north pole to south pole, then back up the opposite meridian.
     * Both poles collapse to a single point so the strip closes visually.
     */
    private fun meridianIndices(iStart: Int, iEnd: Int): ShortArray {
        val nLong = slices + 1
        return ShortArray(2 * (stacks + 1)) { idx ->
            if (idx <= stacks) {
                (idx * nLong + iStart).toShort()
            } else {
                val j = (2 * stacks + 1) - idx
                (j * nLong + iEnd).toShort()
            }
        }
    }

    override fun drawOutline(rc: RenderContext, drawState: DrawShapeState) {
        if (outlineCircles.isEmpty()) return
        drawState.texture = null
        drawState.depthOffset = -0.001
        drawState.color.copy(if (rc.isPickMode) pickColor else activeAttributes.outlineColor)
        drawState.opacity = if (rc.isPickMode) 1f else rc.currentLayer.opacity
        drawState.lineWidth = activeAttributes.outlineWidth

        // One independent LINE_STRIP per selected circle so no segment connects them.
        var offset = currentData.meshIndices.size * Short.SIZE_BYTES
        for (circle in OutlineCircle.entries) {
            if (circle !in outlineCircles) continue
            val count = circleIndexCount(circle)
            drawState.drawElements(GL_LINE_STRIP, count, GL_UNSIGNED_SHORT, offset)
            offset += count * Short.SIZE_BYTES
        }
    }

    /**
     * One of the three principal-plane great circles. Combine via [outlineCircles] to render any
     * subset (e.g. `{ MERIDIAN_YZ }` for a Fresnel-zone profile, `{ EQUATOR, MERIDIAN_YZ,
     * MERIDIAN_XZ }` for a CAD-style three-axis silhouette).
     */
    enum class OutlineCircle {
        /** xy plane: equator (perpendicular to the local up axis). */
        EQUATOR,
        /** yz plane: meridian piercing the local +y / -y axes. */
        MERIDIAN_YZ,
        /** xz plane: meridian piercing the local +x / -x axes. */
        MERIDIAN_XZ
    }

    companion object {
        const val DEFAULT_SLICES = 24
        const val DEFAULT_STACKS = 12
    }
}
