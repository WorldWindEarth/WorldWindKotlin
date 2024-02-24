package earth.worldwind.shape

import earth.worldwind.draw.DrawShapeState
import earth.worldwind.draw.Drawable
import earth.worldwind.draw.DrawableGeomLines
import earth.worldwind.draw.DrawableSurfaceShape
import earth.worldwind.geom.*
import earth.worldwind.render.*
import earth.worldwind.render.buffer.FloatBufferObject
import earth.worldwind.render.buffer.IntBufferObject
import earth.worldwind.render.image.ImageOptions
import earth.worldwind.render.image.ResamplingMode
import earth.worldwind.render.image.WrapMode
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
    protected val interiorElements = mutableListOf<Int>()
    protected val verticalElements = mutableListOf<Int>()
    protected val outlineElements = mutableListOf<Int>()
    protected lateinit var vertexBufferKey: Any
    protected lateinit var elementBufferKey: Any
    protected val vertexOrigin = Vec3()
    protected val point = Vec3()

    companion object {
        protected const val VERTICES_PER_POINT = 2
        protected const val VERTEX_STRIDE = 10
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
        verticalElements.clear()
        interiorElements.clear()
    }

    override fun makeDrawable(rc: RenderContext) {
        if (positions.size < 2) return  // nothing to draw

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
            IntBufferObject(GL_ELEMENT_ARRAY_BUFFER, (interiorElements + outlineElements + verticalElements).toIntArray())
        }

        // Configure the drawable to display the shape's outline. Increase surface shape line widths by 1/2 pixel. Lines
        // drawn indirectly offscreen framebuffer appear thinner when sampled as a texture.
        if (activeAttributes.isDrawOutline) {
            drawState.color(if (rc.isPickMode) pickColor else activeAttributes.outlineColor)
            drawState.opacity(if (rc.isPickMode) 1f else rc.currentLayer.opacity)
            drawState.lineWidth(activeAttributes.outlineWidth + if (isSurfaceShape) 0.5f else 0f)
            drawState.drawElements(
                GL_TRIANGLE_STRIP, outlineElements.size,
                GL_UNSIGNED_INT, interiorElements.size * Int.SIZE_BYTES
            )
        }

        // Configure the drawable to display the shape's extruded verticals.
        if (activeAttributes.isDrawOutline && activeAttributes.isDrawVerticals && isExtrude) {
            drawState.color(if (rc.isPickMode) pickColor else activeAttributes.outlineColor)
            drawState.opacity(if (rc.isPickMode) 1f else rc.currentLayer.opacity)
            drawState.lineWidth(activeAttributes.outlineWidth)
            drawState.drawElements(
                GL_TRIANGLES, verticalElements.size,
                GL_UNSIGNED_INT, (interiorElements.size + outlineElements.size) * Int.SIZE_BYTES
            )
        }

        // Configure the drawable to display the shape's extruded interior.
        if (activeAttributes.isDrawInterior && isExtrude) {
            drawState.color(if (rc.isPickMode) pickColor else activeAttributes.interiorColor)
            drawState.opacity(if (rc.isPickMode) 1f else rc.currentLayer.opacity)
            drawState.drawElements(
                GL_TRIANGLE_STRIP, interiorElements.size,
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
        val numPointsInSegmentMinusEndPoint = if(pathType == LINEAR) 1 else maximumIntermediatePoints + 1
        // Determine the number of vertexes
        val vertexCount = ((positions.size - 1) * numPointsInSegmentMinusEndPoint + 1) * VERTICES_PER_POINT

        // Clear the shape's vertex array and element arrays. These arrays will accumulate values as the shapes's
        // geometry is assembled.
        vertexIndex = 0
        vertexArray = if (isExtrude && !isSurfaceShape) FloatArray((vertexCount * 2 + positions.size * VERTICES_PER_POINT * 2) * VERTEX_STRIDE )
        else FloatArray(vertexCount * VERTEX_STRIDE)
        outlineElements.clear()
        verticalElements.clear()
        interiorElements.clear()

        var prevPos = positions[0]
        for(idx in 0 until positions.size - 1)
        {
            prevPos = addIntermediateVertices(rc, prevPos, positions[idx], positions[idx + 1], numPointsInSegmentMinusEndPoint)
        }
        addIntermediateVertices(rc, prevPos, positions.last(), positions.last(), 1)

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

    protected open fun addIntermediateVertices(rc: RenderContext, prev: Position, begin: Position, end: Position, numPointsInSegmentMinusEndPoint: Int) : Position {
        val azimuth: Angle
        val length: Double
        when (pathType) {
            GREAT_CIRCLE -> {
                azimuth = begin.greatCircleAzimuth(end)
                length = begin.greatCircleDistance(end)
            }
            RHUMB_LINE -> {
                azimuth = begin.rhumbAzimuth(end)
                length = begin.rhumbDistance(end)
            }
            else ->
            {
                azimuth = begin.linearAzimuth(end)
                length = begin.linearDistance(end)
            }
        }
        val deltaDist = length / numPointsInSegmentMinusEndPoint
        val deltaAlt = (end.altitude - begin.altitude) / numPointsInSegmentMinusEndPoint
        var dist = deltaDist
        var alt = begin.altitude + deltaAlt

        var prevPos = prev
        var curPos = begin

        for (idx in 0 until numPointsInSegmentMinusEndPoint) {
            val tempLoc = Location()
            when (pathType) {
                GREAT_CIRCLE -> begin.greatCircleLocation(azimuth, dist, tempLoc)
                RHUMB_LINE -> begin.rhumbLocation(azimuth, dist, tempLoc)
                else -> begin.linearLocation(azimuth, dist, tempLoc)
            }
            val nextPos = Position(tempLoc.latitude, tempLoc.longitude, alt)

            var pointA = Vec3()
            if(isSurfaceShape)
                pointA = Vec3(prevPos.longitude.inDegrees, prevPos.latitude.inDegrees, prevPos.altitude)
            else
                rc.geographicToCartesian(prevPos.latitude, prevPos.longitude, prevPos.altitude, altitudeMode, pointA)

            var pointC = Vec3()
            if(isSurfaceShape)
                pointC = Vec3(nextPos.longitude.inDegrees, nextPos.latitude.inDegrees, nextPos.altitude)
            else
                rc.geographicToCartesian(nextPos.latitude, nextPos.longitude, nextPos.altitude, altitudeMode, pointC)

            addVertex(rc, pointA, curPos, pointC, idx != 0)

            prevPos = curPos
            curPos = nextPos

            dist += deltaDist
            alt += deltaAlt
        }

        return  prevPos
    }

    protected open fun addVertex(rc: RenderContext, pointA: Vec3, positionB: Position, pointC: Vec3, isIntermediate : Boolean) : Int
    {
        val vertex = vertexIndex / VERTEX_STRIDE
        if (vertex == 0) {
            vertexOrigin.copy(pointA)
        }

        var pointB = Vec3()
        if(isSurfaceShape)
            pointB = Vec3(positionB.longitude.inDegrees, positionB.latitude.inDegrees, positionB.altitude)
        else
            rc.geographicToCartesian(positionB.latitude, positionB.longitude, positionB.altitude, altitudeMode, pointB)

        val pointALocal = pointA - vertexOrigin
        val pointBLocal = pointB - vertexOrigin
        val pointCLocal = pointC - vertexOrigin
        vertexArray[vertexIndex++] = pointALocal.x.toFloat()
        vertexArray[vertexIndex++] = pointALocal.y.toFloat()
        vertexArray[vertexIndex++] = pointALocal.z.toFloat()
        vertexArray[vertexIndex++] = pointBLocal.x.toFloat()
        vertexArray[vertexIndex++] = pointBLocal.y.toFloat()
        vertexArray[vertexIndex++] = pointBLocal.z.toFloat()
        vertexArray[vertexIndex++] = pointCLocal.x.toFloat()
        vertexArray[vertexIndex++] = pointCLocal.y.toFloat()
        vertexArray[vertexIndex++] = pointCLocal.z.toFloat()
        vertexArray[vertexIndex++] = 1.0f

        vertexArray[vertexIndex++] = pointALocal.x.toFloat()
        vertexArray[vertexIndex++] = pointALocal.y.toFloat()
        vertexArray[vertexIndex++] = pointALocal.z.toFloat()
        vertexArray[vertexIndex++] = pointBLocal.x.toFloat()
        vertexArray[vertexIndex++] = pointBLocal.y.toFloat()
        vertexArray[vertexIndex++] = pointBLocal.z.toFloat()
        vertexArray[vertexIndex++] = pointCLocal.x.toFloat()
        vertexArray[vertexIndex++] = pointCLocal.y.toFloat()
        vertexArray[vertexIndex++] = pointCLocal.z.toFloat()
        vertexArray[vertexIndex++] = -1.0f

        outlineElements.add(vertex)
        outlineElements.add(vertex + 1)

        if(isExtrude)
        {
            var pointVertical = Vec3()
            pointVertical = rc.geographicToCartesian(positionB.latitude, positionB.longitude, 0.0, altitudeMode, pointVertical)
            val pointVerticalLocal = pointVertical - vertexOrigin

            vertexArray[vertexIndex++] = pointBLocal.x.toFloat()
            vertexArray[vertexIndex++] = pointBLocal.y.toFloat()
            vertexArray[vertexIndex++] = pointBLocal.z.toFloat()
            vertexArray[vertexIndex++] = pointBLocal.x.toFloat()
            vertexArray[vertexIndex++] = pointBLocal.y.toFloat()
            vertexArray[vertexIndex++] = pointBLocal.z.toFloat()
            vertexArray[vertexIndex++] = pointVerticalLocal.x.toFloat()
            vertexArray[vertexIndex++] = pointVerticalLocal.y.toFloat()
            vertexArray[vertexIndex++] = pointVerticalLocal.z.toFloat()
            vertexArray[vertexIndex++] = 0.0f

            vertexArray[vertexIndex++] = pointBLocal.x.toFloat()
            vertexArray[vertexIndex++] = pointBLocal.y.toFloat()
            vertexArray[vertexIndex++] = pointBLocal.z.toFloat()
            vertexArray[vertexIndex++] = pointVerticalLocal.x.toFloat()
            vertexArray[vertexIndex++] = pointVerticalLocal.y.toFloat()
            vertexArray[vertexIndex++] = pointVerticalLocal.z.toFloat()
            vertexArray[vertexIndex++] = pointVerticalLocal.x.toFloat()
            vertexArray[vertexIndex++] = pointVerticalLocal.y.toFloat()
            vertexArray[vertexIndex++] = pointVerticalLocal.z.toFloat()
            vertexArray[vertexIndex++] = 0.0f

            interiorElements.add(vertex + 2)
            interiorElements.add(vertex + 3)

            if(!isIntermediate)
            {
                vertexArray[vertexIndex++] = pointBLocal.x.toFloat()
                vertexArray[vertexIndex++] = pointBLocal.y.toFloat()
                vertexArray[vertexIndex++] = pointBLocal.z.toFloat()
                vertexArray[vertexIndex++] = pointBLocal.x.toFloat()
                vertexArray[vertexIndex++] = pointBLocal.y.toFloat()
                vertexArray[vertexIndex++] = pointBLocal.z.toFloat()
                vertexArray[vertexIndex++] = pointVerticalLocal.x.toFloat()
                vertexArray[vertexIndex++] = pointVerticalLocal.y.toFloat()
                vertexArray[vertexIndex++] = pointVerticalLocal.z.toFloat()
                vertexArray[vertexIndex++] = 1.0f

                vertexArray[vertexIndex++] = pointBLocal.x.toFloat()
                vertexArray[vertexIndex++] = pointBLocal.y.toFloat()
                vertexArray[vertexIndex++] = pointBLocal.z.toFloat()
                vertexArray[vertexIndex++] = pointBLocal.x.toFloat()
                vertexArray[vertexIndex++] = pointBLocal.y.toFloat()
                vertexArray[vertexIndex++] = pointBLocal.z.toFloat()
                vertexArray[vertexIndex++] = pointVerticalLocal.x.toFloat()
                vertexArray[vertexIndex++] = pointVerticalLocal.y.toFloat()
                vertexArray[vertexIndex++] = pointVerticalLocal.z.toFloat()
                vertexArray[vertexIndex++] = -1.0f

                vertexArray[vertexIndex++] = pointBLocal.x.toFloat()
                vertexArray[vertexIndex++] = pointBLocal.y.toFloat()
                vertexArray[vertexIndex++] = pointBLocal.z.toFloat()
                vertexArray[vertexIndex++] = pointVerticalLocal.x.toFloat()
                vertexArray[vertexIndex++] = pointVerticalLocal.y.toFloat()
                vertexArray[vertexIndex++] = pointVerticalLocal.z.toFloat()
                vertexArray[vertexIndex++] = pointVerticalLocal.x.toFloat()
                vertexArray[vertexIndex++] = pointVerticalLocal.y.toFloat()
                vertexArray[vertexIndex++] = pointVerticalLocal.z.toFloat()
                vertexArray[vertexIndex++] = 1.0f

                vertexArray[vertexIndex++] = pointBLocal.x.toFloat()
                vertexArray[vertexIndex++] = pointBLocal.y.toFloat()
                vertexArray[vertexIndex++] = pointBLocal.z.toFloat()
                vertexArray[vertexIndex++] = pointVerticalLocal.x.toFloat()
                vertexArray[vertexIndex++] = pointVerticalLocal.y.toFloat()
                vertexArray[vertexIndex++] = pointVerticalLocal.z.toFloat()
                vertexArray[vertexIndex++] = pointVerticalLocal.x.toFloat()
                vertexArray[vertexIndex++] = pointVerticalLocal.y.toFloat()
                vertexArray[vertexIndex++] = pointVerticalLocal.z.toFloat()
                vertexArray[vertexIndex++] = -1.0f

                verticalElements.add(vertex + 4)
                verticalElements.add(vertex + 5)
                verticalElements.add(vertex + 6)
                verticalElements.add(vertex + 4)
                verticalElements.add(vertex + 6)
                verticalElements.add(vertex + 7)
            }
        }

        return vertex
    }
}