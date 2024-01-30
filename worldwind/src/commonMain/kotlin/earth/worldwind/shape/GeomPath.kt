package earth.worldwind.shape

import earth.worldwind.draw.DrawShapeState
import earth.worldwind.draw.Drawable
import earth.worldwind.draw.DrawableGeomLines
import earth.worldwind.draw.DrawableShape
import earth.worldwind.draw.DrawableSurfaceShape
import earth.worldwind.geom.*
import earth.worldwind.render.*
import earth.worldwind.render.buffer.FloatBufferObject
import earth.worldwind.render.buffer.IntBufferObject
import earth.worldwind.render.image.ImageOptions
import earth.worldwind.render.image.ResamplingMode
import earth.worldwind.render.image.WrapMode
import earth.worldwind.render.program.BasicShaderProgram
import earth.worldwind.render.program.GeomLinesShaderProgram
import earth.worldwind.shape.PathType.*
import earth.worldwind.util.kgl.*
import kotlin.jvm.JvmOverloads

open class GeomPath @JvmOverloads constructor(
    positions: List<Position>, attributes: ShapeAttributes = ShapeAttributes()
): AbstractShape(attributes) {
    var positions = positions
        set(value) {
            field = value
            reset()
        }
    protected var vertexArray = FloatArray(0)
    protected var vertexIndex = 0
    // TODO Use ShortArray instead of mutableListOf<Short> to avoid unnecessary memory re-allocations
    protected val outlineElements = mutableListOf<Int>()
    protected lateinit var vertexBufferKey: Any
    protected lateinit var elementBufferKey: Any
    protected val vertexOrigin = Vec3()
    private val pointA = Vec3()
    private val pointB = Vec3()

    companion object {
        protected const val VERTICES_PER_POINT = 4;
        protected const val VERTEX_STRIDE = 4
        protected val defaultOutlineImageOptions = ImageOptions().apply {
            resamplingMode = ResamplingMode.NEAREST_NEIGHBOR
            wrapMode = WrapMode.REPEAT
        }

        protected fun nextCacheKey() = Any()
    }

    override fun reset() {
        super.reset()
        vertexArray = FloatArray(0)
        outlineElements.clear()
    }

    override fun makeDrawable(rc: RenderContext) {
        if (positions.isEmpty()) return  // nothing to draw

        if (mustAssembleGeometry(rc)) {
            assembleGeometry(rc)
            vertexBufferKey = nextCacheKey()
            elementBufferKey = nextCacheKey()
        }

        // Obtain a drawable form the render context pool, and compute distance to the render camera.
        val drawable: Drawable
        val drawState: DrawShapeState
        val cameraDistance: Double
        if (isSurfaceShape) {
            val pool = rc.getDrawablePool<DrawableSurfaceShape>()
            drawable = DrawableSurfaceShape.obtain(pool)
            drawState = drawable.drawState
            cameraDistance = cameraDistanceGeographic(rc, boundingSector)
            drawable.offset = rc.globe.offset
            drawable.sector.copy(boundingSector)
        } else {
            val pool = rc.getDrawablePool<DrawableGeomLines>()
            drawable = DrawableGeomLines.obtain(pool)
            drawState = drawable.drawState
            cameraDistance = cameraDistanceCartesian(rc, vertexArray, vertexArray.size, VERTEX_STRIDE, vertexOrigin)
        }

        // Use the basic GLSL program to draw the shape.
        drawState.program = rc.getShaderProgram { GeomLinesShaderProgram() }

        // Assemble the drawable's OpenGL vertex buffer object.
        drawState.vertexBuffer = rc.getBufferObject(vertexBufferKey) {
            FloatBufferObject(GL_ARRAY_BUFFER, vertexArray, vertexArray.size)
        }

        // Assemble the drawable's OpenGL element buffer object.
        drawState.elementBuffer = rc.getBufferObject(elementBufferKey) {
            IntBufferObject(GL_ELEMENT_ARRAY_BUFFER, (outlineElements).toIntArray())
        }

        // Configure the drawable to display the shape's outline. Increase surface shape line widths by 1/2 pixel. Lines
        // drawn indirectly offscreen framebuffer appear thinner when sampled as a texture.
        if (activeAttributes.isDrawOutline) {
            drawState.color(if (rc.isPickMode) pickColor else activeAttributes.outlineColor)
            drawState.opacity(if (rc.isPickMode) 1f else rc.currentLayer.opacity)
            drawState.lineWidth(activeAttributes.outlineWidth + if (isSurfaceShape) 0.5f else 0f)
            drawState.drawElements(
                GL_TRIANGLES, outlineElements.size,
                GL_UNSIGNED_INT, 0
            )
        }

        // Disable texturing for the remaining drawable primitives.
        drawState.texture(null)

        // Configure the drawable according to the shape's attributes.
        drawState.vertexOrigin.copy(vertexOrigin)
        drawState.vertexStride = VERTEX_STRIDE * 4 // stride in bytes
        drawState.enableCullFace = false
        drawState.enableDepthTest = activeAttributes.isDepthTest
        drawState.enableDepthWrite = activeAttributes.isDepthWrite

        // Enqueue the drawable for processing on the OpenGL thread.
        if (isSurfaceShape) rc.offerSurfaceDrawable(drawable, 0.0 /*zOrder*/)
        else rc.offerShapeDrawable(drawable, cameraDistance)
    }

    protected open fun mustAssembleGeometry(rc: RenderContext) = vertexArray.isEmpty()

    protected open fun assembleGeometry(rc: RenderContext) {
        // Determine the number of vertexes
        val vertexCount = if (maximumIntermediatePoints <= 0 || pathType == LINEAR) positions.size * VERTICES_PER_POINT
        else if(positions.isNotEmpty()) positions.size + (positions.size - 1) * maximumIntermediatePoints else 0

        // Clear the shape's vertex array and element arrays. These arrays will accumulate values as the shapes's
        // geometry is assembled.
        vertexIndex = 0
        vertexArray = if (isExtrude && !isSurfaceShape) FloatArray(vertexCount * 2 * VERTEX_STRIDE)
        else FloatArray(vertexCount * VERTEX_STRIDE)
        outlineElements.clear()

        for (idx in 0 until positions.size - 1) {
            val pointA = if(isSurfaceShape) Vec3(positions[idx].longitude.inDegrees, positions[idx].latitude.inDegrees, positions[idx].altitude) else rc.geographicToCartesian(positions[idx].latitude, positions[idx].longitude, positions[idx].altitude, altitudeMode, pointA)
            val pointB = if(isSurfaceShape) Vec3(positions[idx+1].longitude.inDegrees, positions[idx+1].latitude.inDegrees, positions[idx+1].altitude) else rc.geographicToCartesian(positions[idx+1].latitude, positions[idx+1].longitude, positions[idx+1].altitude, altitudeMode, pointB)
            addLineSegment(rc, pointA , pointB)
        }

        // Compute the shape's bounding box or bounding sector from its assembled coordinates.
        if (isSurfaceShape) {
            boundingSector.setEmpty()
            boundingSector.union(vertexArray, vertexIndex, VERTEX_STRIDE)
            boundingSector.translate(vertexOrigin.y /*latitude*/, vertexOrigin.x /*longitude*/)
            boundingBox.setToUnitBox() // Surface/geographic shape bounding box is unused
        } else {
            boundingBox.setToPoints(vertexArray, vertexIndex, VERTEX_STRIDE)
            boundingBox.translate(vertexOrigin.x, vertexOrigin.y, vertexOrigin.z)
            boundingSector.setEmpty() // Cartesian shape bounding sector is unused
        }
    }

    protected open fun addLineSegment(
        rc: RenderContext, pointA : Vec3, pointB : Vec3
    ): Int {
        val vertex = vertexIndex / VERTEX_STRIDE
        if (vertex == 0) {
            vertexOrigin.copy(pointA)
        }
        val pointALocal = pointA - vertexOrigin;
        val pointBLocal = pointB - vertexOrigin;
        vertexArray[vertexIndex++] = pointALocal.x.toFloat()
        vertexArray[vertexIndex++] = pointALocal.y.toFloat()
        vertexArray[vertexIndex++] = pointALocal.z.toFloat()
        vertexArray[vertexIndex++] = 1.0f

        vertexArray[vertexIndex++] = pointALocal.x.toFloat()
        vertexArray[vertexIndex++] = pointALocal.y.toFloat()
        vertexArray[vertexIndex++] = pointALocal.z.toFloat()
        vertexArray[vertexIndex++] = -1.0f

        vertexArray[vertexIndex++] = pointBLocal.x.toFloat()
        vertexArray[vertexIndex++] = pointBLocal.y.toFloat()
        vertexArray[vertexIndex++] = pointBLocal.z.toFloat()
        vertexArray[vertexIndex++] = 1.0f

        vertexArray[vertexIndex++] = pointBLocal.x.toFloat()
        vertexArray[vertexIndex++] = pointBLocal.y.toFloat()
        vertexArray[vertexIndex++] = pointBLocal.z.toFloat()
        vertexArray[vertexIndex++] = -1.0f

        outlineElements.add(vertex)
        outlineElements.add(vertex + 1)
        outlineElements.add(vertex + 2)
        outlineElements.add(vertex + 3)
        outlineElements.add(vertex + 2)
        outlineElements.add(vertex + 1)

        return vertex
    }
}