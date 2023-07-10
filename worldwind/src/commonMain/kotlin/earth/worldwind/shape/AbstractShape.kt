package earth.worldwind.shape

import earth.worldwind.PickedObject
import earth.worldwind.geom.*
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.render.AbstractRenderable
import earth.worldwind.render.Color
import earth.worldwind.render.RenderContext
import earth.worldwind.render.Texture
import kotlin.jvm.JvmOverloads
import kotlin.math.sqrt

abstract class AbstractShape @JvmOverloads constructor(
    override var attributes: ShapeAttributes = ShapeAttributes()
): AbstractRenderable(), Attributable, Highlightable {
    companion object {
        const val NEAR_ZERO_THRESHOLD = 1.0e-10
    }

    var altitudeMode = AltitudeMode.ABSOLUTE
        set(value) {
            field = value
            reset()
        }
    var pathType = PathType.GREAT_CIRCLE
        set(value) {
            field = value
            reset()
        }
    /**
     * Draw sides of the shape which extend from the defined position and altitude to the ground.
     */
    var isExtrude = false
        set(value) {
            field = value
            reset()
        }
    /**
     * Determines whether this shape's geometry follows the terrain surface or is fixed at a constant altitude.
     */
    var isFollowTerrain = false
        set(value) {
            field = value
            reset()
        }
    override var highlightAttributes: ShapeAttributes? = null
    override var isHighlighted = false
    var maximumIntermediatePoints = 10
    protected lateinit var activeAttributes: ShapeAttributes
    protected var pickedObjectId = 0
    protected val pickColor = Color()
    protected val boundingSector = Sector()
    protected val boundingBox = BoundingBox()
    private val scratchPoint = Vec3()

    override fun doRender(rc: RenderContext) {
        // Don't render anything if the shape is not visible.
        if (!intersectsFrustum(rc)) return

        // Select the currently active attributes. Don't render anything if the attributes are unspecified.
        determineActiveAttributes(rc)

        // Keep track of the drawable count to determine whether this shape has enqueued drawables.
        val drawableCount = rc.drawableCount
        if (rc.isPickMode) {
            pickedObjectId = rc.nextPickedObjectId()
            PickedObject.identifierToUniqueColor(pickedObjectId, pickColor)
        }

        // Enqueue drawables for processing on the OpenGL thread.
        makeDrawable(rc)

        // Enqueue a picked object that associates the shape's drawables with its picked object ID.
        if (rc.isPickMode && rc.drawableCount != drawableCount) {
            rc.offerPickedObject(PickedObject.fromRenderable(pickedObjectId, this, rc.currentLayer))
        }
    }

    protected open fun intersectsFrustum(rc: RenderContext) = boundingBox.isUnitBox || boundingBox.intersectsFrustum(rc.frustum)

    protected open fun determineActiveAttributes(rc: RenderContext) {
        val highlightAttributes = highlightAttributes
        activeAttributes = if (isHighlighted && highlightAttributes != null) highlightAttributes else attributes
    }

    protected open fun cameraDistanceGeographic(rc: RenderContext, boundingSector: Sector): Double {
        val lat = rc.camera.position.latitude.inDegrees.coerceIn(
            boundingSector.minLatitude.inDegrees,
            boundingSector.maxLatitude.inDegrees
        )
        val lon = rc.camera.position.longitude.inDegrees.coerceIn(
            boundingSector.minLongitude.inDegrees,
            boundingSector.maxLongitude.inDegrees
        )
        val point = rc.geographicToCartesian(lat.degrees, lon.degrees, 0.0, AltitudeMode.CLAMP_TO_GROUND, scratchPoint)
        return point.distanceTo(rc.cameraPoint)
    }

    protected open fun cameraDistanceCartesian(rc: RenderContext, array: FloatArray, count: Int, stride: Int, offset: Vec3): Double {
        val cx = rc.cameraPoint.x - offset.x
        val cy = rc.cameraPoint.y - offset.y
        val cz = rc.cameraPoint.z - offset.z
        var minDistance2 = Double.POSITIVE_INFINITY
        for (idx in 0 until count step stride) {
            val px = array[idx]
            val py = array[idx + 1]
            val pz = array[idx + 2]
            val dx = px - cx
            val dy = py - cy
            val dz = pz - cz
            val distance2 = dx * dx + dy * dy + dz * dz
            if (minDistance2 > distance2) minDistance2 = distance2
        }
        return sqrt(minDistance2)
    }

    protected open fun computeRepeatingTexCoordTransform(texture: Texture, metersPerPixel: Double, result: Matrix3): Matrix3 {
        val texCoordMatrix = result.setToIdentity()
        texCoordMatrix.setScale(1.0 / (texture.width * metersPerPixel), 1.0 / (texture.height * metersPerPixel))
        texCoordMatrix.multiplyByMatrix(texture.coordTransform)
        return texCoordMatrix
    }

    protected abstract fun reset()

    protected abstract fun makeDrawable(rc: RenderContext)
}