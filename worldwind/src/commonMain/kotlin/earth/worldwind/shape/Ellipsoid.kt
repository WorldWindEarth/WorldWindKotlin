package earth.worldwind.shape

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.ZERO
import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Position
import earth.worldwind.globe.Globe
import earth.worldwind.render.RenderContext
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import kotlin.jvm.JvmOverloads
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 3D ellipsoid centered at a geographic position. Supports three independent semi-axes for
 * a tri-axial ellipsoid; pass `xRadius == yRadius` for a rotation spheroid (the common
 * "two-radius" case of one equatorial radius paired with a polar radius).
 *
 * Local frame at [center]: X east, Y north, Z up. [heading] rotates the ellipsoid clockwise
 * from north around Z. Lighting opt-in via [ShapeAttributes.isLightingEnabled].
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

        val cosH = cos(heading.inRadians)
        val sinH = sin(heading.inRadians)

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

                // Position on the ellipsoid in pre-heading local coords (x east, y north, z up).
                val lx0 = xRadius * sinPhi * cosTheta
                val ly0 = yRadius * sinPhi * sinTheta
                val lz0 = zRadius * cosPhi

                // Apply CW-from-north heading rotation around z. Matches [Ellipse]'s convention.
                val lx = lx0 * cosH + ly0 * sinH
                val ly = -lx0 * sinH + ly0 * cosH
                val lz = lz0

                pts[k++] = (lx * ex + ly * nx + lz * ux).toFloat()
                pts[k++] = (lx * ey + ly * ny + lz * uy).toFloat()
                pts[k++] = (lx * ez + ly * nz + lz * uz).toFloat()
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
        // Equator drawn as a single GL_LINE_STRIP. The seam vertex at i=slices is the same
        // location as i=0 so the strip closes visually.
        val nLong = slices + 1
        val jEquator = stacks / 2
        return ShortArray(nLong) { i -> (jEquator * nLong + i).toShort() }
    }

    companion object {
        const val DEFAULT_SLICES = 24
        const val DEFAULT_STACKS = 12
    }
}
