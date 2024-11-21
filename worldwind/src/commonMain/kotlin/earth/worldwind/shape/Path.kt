package earth.worldwind.shape

import earth.worldwind.draw.DrawShapeState
import earth.worldwind.draw.Drawable
import earth.worldwind.draw.DrawableShape
import earth.worldwind.draw.DrawableSurfaceShape
import earth.worldwind.geom.*
import earth.worldwind.render.*
import earth.worldwind.render.buffer.FloatBufferObject
import earth.worldwind.render.buffer.IntBufferObject
import earth.worldwind.render.image.ImageOptions
import earth.worldwind.render.image.ResamplingMode
import earth.worldwind.render.image.WrapMode
import earth.worldwind.render.program.TriangleShaderProgram
import earth.worldwind.shape.PathType.*
import earth.worldwind.util.kgl.*
import earth.worldwind.util.math.encodeOrientationVector
import kotlin.jvm.JvmOverloads

open class Path @JvmOverloads constructor(
    positions: List<Position>, attributes: ShapeAttributes = ShapeAttributes()
): AbstractShape(attributes) {
    var positions = positions
        set(value) {
            field = value
            reset()
        }
    protected var vertexArray = FloatArray(0)
    protected var vertexIndex = 0
    protected var verticalIndex = 0
    protected var extrudeVertexArray = FloatArray(0)
    protected var extrudeIndex = 0
    // TODO Use ShortArray instead of mutableListOf<Short> to avoid unnecessary memory re-allocations
    protected val interiorElements = mutableListOf<Int>()
    protected val outlineElements = mutableListOf<Int>()
    protected val verticalElements = mutableListOf<Int>()
    protected lateinit var extrudeVertexBufferKey: Any
    protected lateinit var extrudeElementBufferKey: Any
    protected lateinit var vertexBufferKey: Any
    protected lateinit var elementBufferKey: Any
    protected val vertexOrigin = Vec3()
    protected var texCoord1d = 0.0
    private val point = Vec3()
    private val verticalPoint = Vec3()
    private val prevPoint = Vec3()
    private val texCoordMatrix = Matrix3()
    private val intermediateLocation = Location()

    companion object {
        protected const val VERTEX_STRIDE = 5 // 5 floats
        protected const val EXTRUDE_SEGMENT_STRIDE = 2 * VERTEX_STRIDE // 2 vertices
        protected const val OUTLINE_SEGMENT_STRIDE = 4 * VERTEX_STRIDE // 4 vertices
        protected const val VERTICAL_SEGMENT_STRIDE = 4 * OUTLINE_SEGMENT_STRIDE // 4 points per 4 vertices per vertical line
        protected val defaultOutlineImageOptions = ImageOptions().apply {
            resamplingMode = ResamplingMode.NEAREST_NEIGHBOR
            wrapMode = WrapMode.REPEAT
        }

        protected fun nextCacheKey() = Any()
    }

    override fun reset() {
        super.reset()
        vertexArray = FloatArray(0)
        extrudeVertexArray = FloatArray(0)
        interiorElements.clear()
        outlineElements.clear()
        verticalElements.clear()
    }

    override fun makeDrawable(rc: RenderContext) {
        if (positions.size < 2) return // nothing to draw

        if (mustAssembleGeometry(rc)) {
            assembleGeometry(rc)
            vertexBufferKey = nextCacheKey()
            elementBufferKey = nextCacheKey()
            extrudeVertexBufferKey = nextCacheKey()
            extrudeElementBufferKey = nextCacheKey()
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
            val pool = rc.getDrawablePool<DrawableShape>()
            drawable = DrawableShape.obtain(pool)
            drawState = drawable.drawState
            cameraDistance = cameraDistanceCartesian(rc, vertexArray, vertexArray.size, OUTLINE_SEGMENT_STRIDE, vertexOrigin)
        }

        // Use triangles mode to draw lines
        drawState.isLine = true

        // Use the basic GLSL program to draw the shape.
        drawState.program = rc.getShaderProgram { TriangleShaderProgram() }

        // Assemble the drawable's OpenGL vertex buffer object.
        drawState.vertexBuffer = rc.getBufferObject(vertexBufferKey) {
            FloatBufferObject(GL_ARRAY_BUFFER, vertexArray, vertexArray.size)
        }

        // Assemble the drawable's OpenGL element buffer object.
        drawState.elementBuffer = rc.getBufferObject(elementBufferKey) {
            val array = IntArray(outlineElements.size + verticalElements.size)
            var index = 0
            for (element in outlineElements) array[index++] = element
            for (element in verticalElements) array[index++] = element
            IntBufferObject(GL_ELEMENT_ARRAY_BUFFER, array)
        }

        // Configure the drawable to use the outline texture when drawing the outline.
        if (activeAttributes.isDrawOutline) {
            activeAttributes.outlineImageSource?.let { outlineImageSource ->
                rc.getTexture(outlineImageSource, defaultOutlineImageOptions)?.let { texture ->
                    val metersPerPixel = rc.pixelSizeAtDistance(cameraDistance)
                    computeRepeatingTexCoordTransform(texture, metersPerPixel, texCoordMatrix)
                    drawState.texture(texture)
                    drawState.texCoordMatrix(texCoordMatrix)
                }
            }
        }

        // Configure the drawable to display the shape's outline. Increase surface shape line widths by 1/2 pixel. Lines
        // drawn indirectly offscreen framebuffer appear thinner when sampled as a texture.
        if (activeAttributes.isDrawOutline) {
            drawState.color(if (rc.isPickMode) pickColor else activeAttributes.outlineColor)
            drawState.opacity(if (rc.isPickMode) 1f else rc.currentLayer.opacity)
            drawState.lineWidth(activeAttributes.outlineWidth + if (isSurfaceShape) 0.5f else 0f)
            drawState.drawElements(
                GL_TRIANGLE_STRIP, outlineElements.size,
                GL_UNSIGNED_INT, 0
            )
        }

        // Disable texturing for the remaining drawable primitives.
        drawState.texture(null)

        // Configure the drawable to display the shape's extruded verticals.
        if (activeAttributes.isDrawOutline && activeAttributes.isDrawVerticals && isExtrude) {
            drawState.color(if (rc.isPickMode) pickColor else activeAttributes.outlineColor)
            drawState.opacity(if (rc.isPickMode) 1f else rc.currentLayer.opacity)
            drawState.lineWidth(activeAttributes.outlineWidth)
            drawState.drawElements(
                GL_TRIANGLES, verticalElements.size,
                GL_UNSIGNED_INT, outlineElements.size * Int.SIZE_BYTES
            )
        }

        // Configure the drawable according to the shape's attributes.
        drawState.vertexOrigin.copy(vertexOrigin)
        drawState.enableCullFace = false
        drawState.enableDepthTest = activeAttributes.isDepthTest
        drawState.enableDepthWrite = activeAttributes.isDepthWrite

        // Enqueue the drawable for processing on the OpenGL thread.
        if (isSurfaceShape) rc.offerSurfaceDrawable(drawable, 0.0 /*zOrder*/)
        else rc.offerShapeDrawable(drawable, cameraDistance)

        // Configure the drawable to display the shape's extruded interior.
        if (activeAttributes.isDrawInterior && isExtrude && !isSurfaceShape) {
            val pool = rc.getDrawablePool<DrawableShape>()
            val drawableExtrusion = DrawableShape.obtain(pool)
            val drawStateExtrusion = drawableExtrusion.drawState

            drawStateExtrusion.isLine = false

            // Use the basic GLSL program to draw the shape.
            drawStateExtrusion.program = rc.getShaderProgram { TriangleShaderProgram() }

            // Assemble the drawable's OpenGL vertex buffer object.
            drawStateExtrusion.vertexBuffer = rc.getBufferObject(extrudeVertexBufferKey) {
                FloatBufferObject(GL_ARRAY_BUFFER, extrudeVertexArray, extrudeVertexArray.size)
            }

            // Assemble the drawable's OpenGL element buffer object.
            drawStateExtrusion.elementBuffer = rc.getBufferObject(extrudeElementBufferKey) {
                val array = IntArray(interiorElements.size)
                var index = 0
                for (element in interiorElements) array[index++] = element
                IntBufferObject(GL_ELEMENT_ARRAY_BUFFER, array)
            }

            drawStateExtrusion.color(if (rc.isPickMode) pickColor else activeAttributes.interiorColor)
            drawStateExtrusion.opacity(if (rc.isPickMode) 1f else rc.currentLayer.opacity)
            drawStateExtrusion.drawElements(
                GL_TRIANGLE_STRIP, interiorElements.size,
                GL_UNSIGNED_INT, 0
            )

            // Configure the drawable according to the shape's attributes.
            drawStateExtrusion.texture(null)
            drawStateExtrusion.vertexOrigin.copy(vertexOrigin)
            drawStateExtrusion.vertexStride = VERTEX_STRIDE * 4 // stride in bytes
            drawStateExtrusion.enableCullFace = false
            drawStateExtrusion.enableDepthTest = activeAttributes.isDepthTest
            drawStateExtrusion.enableDepthWrite = activeAttributes.isDepthWrite

            rc.offerShapeDrawable(drawableExtrusion, cameraDistance)
        }
    }

    protected open fun mustAssembleGeometry(rc: RenderContext) = vertexArray.isEmpty()

    protected open fun assembleGeometry(rc: RenderContext) {
        // Determine the number of vertexes
        val vertexCount = if (maximumIntermediatePoints <= 0 || pathType == LINEAR) positions.size
        else if (positions.isNotEmpty()) positions.size + (positions.size - 1) * maximumIntermediatePoints else 0

        // Separate vertex array for interior polygon
        extrudeIndex = 0;
        extrudeVertexArray = if(isExtrude && !isSurfaceShape)  FloatArray((vertexCount + 2) * EXTRUDE_SEGMENT_STRIDE) else FloatArray(0)
        interiorElements.clear()

        // Clear the shape's vertex array and element arrays. These arrays will accumulate values as the shapes's
        // geometry is assembled.
        vertexIndex = 0
        verticalIndex = if (isExtrude && !isSurfaceShape) (vertexCount + 2) * OUTLINE_SEGMENT_STRIDE else 0
        vertexArray = if (isExtrude && !isSurfaceShape) FloatArray(verticalIndex + positions.size * VERTICAL_SEGMENT_STRIDE)
        else FloatArray((vertexCount + 2) * OUTLINE_SEGMENT_STRIDE)
        outlineElements.clear()
        verticalElements.clear()

        // Add the first vertex.
        var begin = positions[0]
        addVertex(rc, begin.latitude, begin.longitude, begin.altitude, true /*intermediate*/, true)
        addVertex(rc, begin.latitude, begin.longitude, begin.altitude, false /*intermediate*/, true)

        // Add the remaining vertices, inserting vertices along each edge as indicated by the path's properties.
        for (idx in 1 until positions.size) {
            val end = positions[idx]
            addIntermediateVertices(rc, begin, end)
            addVertex(rc, end.latitude, end.longitude, end.altitude, false /*intermediate*/, idx != (positions.size - 1))
            begin = end
        }
        addVertex(rc, begin.latitude, begin.longitude, begin.altitude,true /*intermediate*/, false)

        // Compute the shape's bounding box or bounding sector from its assembled coordinates.
        if (isSurfaceShape) {
            boundingSector.setEmpty()
            boundingSector.union(vertexArray, vertexIndex, OUTLINE_SEGMENT_STRIDE)
            boundingSector.translate(vertexOrigin.y /*latitude*/, vertexOrigin.x /*longitude*/)
            boundingBox.setToUnitBox() // Surface/geographic shape bounding box is unused
        } else {
            boundingBox.setToPoints(vertexArray, vertexIndex, OUTLINE_SEGMENT_STRIDE)
            boundingBox.translate(vertexOrigin.x, vertexOrigin.y, vertexOrigin.z)
            boundingSector.setEmpty() // Cartesian shape bounding sector is unused
        }
    }

    protected open fun addIntermediateVertices(rc: RenderContext, begin: Position, end: Position) {
        if (maximumIntermediatePoints <= 0) return  // suppress intermediate vertices when configured to do so
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
            else -> return  // suppress intermediate vertices when the path type is linear
        }
        if (length < NEAR_ZERO_THRESHOLD) return  // suppress intermediate vertices when the edge length less than a millimeter (on Earth)
        val numSubsegments = maximumIntermediatePoints + 1
        val deltaDist = length / numSubsegments
        val deltaAlt = (end.altitude - begin.altitude) / numSubsegments
        var dist = deltaDist
        var alt = begin.altitude + deltaAlt
        for (idx in 1 until numSubsegments) {
            val loc = intermediateLocation
            when (pathType) {
                GREAT_CIRCLE -> begin.greatCircleLocation(azimuth, dist, loc)
                RHUMB_LINE -> begin.rhumbLocation(azimuth, dist, loc)
                else -> {}
            }
            addVertex(rc, loc.latitude, loc.longitude, alt, true /*intermediate*/, true /*addIndices*/)
            dist += deltaDist
            alt += deltaAlt
        }
    }

    protected open fun addVertex(
        rc: RenderContext, latitude: Angle, longitude: Angle, altitude: Double, intermediate: Boolean, addIndices : Boolean
    ) {
        val vertex = vertexIndex / VERTEX_STRIDE
        val point = rc.geographicToCartesian(latitude, longitude, altitude, altitudeMode, point)
        if (vertexIndex == 0) {
            if (isSurfaceShape) vertexOrigin.set(longitude.inDegrees, latitude.inDegrees, altitude)
            else vertexOrigin.copy(point)
            texCoord1d = 0.0
        } else {
            texCoord1d += point.distanceTo(prevPoint)
        }
        prevPoint.copy(point)
        val upperLeftCorner = encodeOrientationVector(-1f, 1f)
        val lowerLeftCorner = encodeOrientationVector(-1f, -1f)
        val upperRightCorner = encodeOrientationVector(1f, 1f)
        val lowerRightCorner = encodeOrientationVector(1f, -1f)
        if (isSurfaceShape) {
            vertexArray[vertexIndex++] = (longitude.inDegrees - vertexOrigin.x).toFloat()
            vertexArray[vertexIndex++] = (latitude.inDegrees - vertexOrigin.y).toFloat()
            vertexArray[vertexIndex++] = (altitude - vertexOrigin.z).toFloat()
            vertexArray[vertexIndex++] = upperLeftCorner
            vertexArray[vertexIndex++] = texCoord1d.toFloat()

            vertexArray[vertexIndex++] = (longitude.inDegrees - vertexOrigin.x).toFloat()
            vertexArray[vertexIndex++] = (latitude.inDegrees - vertexOrigin.y).toFloat()
            vertexArray[vertexIndex++] = (altitude - vertexOrigin.z).toFloat()
            vertexArray[vertexIndex++] = lowerLeftCorner
            vertexArray[vertexIndex++] = texCoord1d.toFloat()

            vertexArray[vertexIndex++] = (longitude.inDegrees - vertexOrigin.x).toFloat()
            vertexArray[vertexIndex++] = (latitude.inDegrees - vertexOrigin.y).toFloat()
            vertexArray[vertexIndex++] = (altitude - vertexOrigin.z).toFloat()
            vertexArray[vertexIndex++] = upperRightCorner
            vertexArray[vertexIndex++] = texCoord1d.toFloat()

            vertexArray[vertexIndex++] = (longitude.inDegrees - vertexOrigin.x).toFloat()
            vertexArray[vertexIndex++] = (latitude.inDegrees - vertexOrigin.y).toFloat()
            vertexArray[vertexIndex++] = (altitude - vertexOrigin.z).toFloat()
            vertexArray[vertexIndex++] = lowerRightCorner
            vertexArray[vertexIndex++] = texCoord1d.toFloat()

            if (addIndices) {
                outlineElements.add(vertex)
                outlineElements.add(vertex + 1)
                outlineElements.add(vertex + 2)
                outlineElements.add(vertex + 3)
            }
        } else {
            vertexArray[vertexIndex++] = (point.x - vertexOrigin.x).toFloat()
            vertexArray[vertexIndex++] = (point.y - vertexOrigin.y).toFloat()
            vertexArray[vertexIndex++] = (point.z - vertexOrigin.z).toFloat()
            vertexArray[vertexIndex++] = upperLeftCorner
            vertexArray[vertexIndex++] = texCoord1d.toFloat()

            vertexArray[vertexIndex++] = (point.x - vertexOrigin.x).toFloat()
            vertexArray[vertexIndex++] = (point.y - vertexOrigin.y).toFloat()
            vertexArray[vertexIndex++] = (point.z - vertexOrigin.z).toFloat()
            vertexArray[vertexIndex++] = lowerLeftCorner
            vertexArray[vertexIndex++] = texCoord1d.toFloat()

            vertexArray[vertexIndex++] = (point.x - vertexOrigin.x).toFloat()
            vertexArray[vertexIndex++] = (point.y - vertexOrigin.y).toFloat()
            vertexArray[vertexIndex++] = (point.z - vertexOrigin.z).toFloat()
            vertexArray[vertexIndex++] = upperRightCorner
            vertexArray[vertexIndex++] = texCoord1d.toFloat()

            vertexArray[vertexIndex++] = (point.x - vertexOrigin.x).toFloat()
            vertexArray[vertexIndex++] = (point.y - vertexOrigin.y).toFloat()
            vertexArray[vertexIndex++] = (point.z - vertexOrigin.z).toFloat()
            vertexArray[vertexIndex++] = lowerRightCorner
            vertexArray[vertexIndex++] = texCoord1d.toFloat()

            if (addIndices) {
                outlineElements.add(vertex)
                outlineElements.add(vertex + 1)
                outlineElements.add(vertex + 2)
                outlineElements.add(vertex + 3)
            }
            if (isExtrude) {
                val vertPoint = rc.geographicToCartesian(latitude, longitude, 0.0, altitudeMode, verticalPoint)
                val extrudeVertex =  extrudeIndex / VERTEX_STRIDE

                extrudeVertexArray[extrudeIndex++] = (point.x - vertexOrigin.x).toFloat()
                extrudeVertexArray[extrudeIndex++] = (point.y - vertexOrigin.y).toFloat()
                extrudeVertexArray[extrudeIndex++] = (point.z - vertexOrigin.z).toFloat()
                extrudeVertexArray[extrudeIndex++] = 0f  /*unused*/
                extrudeVertexArray[extrudeIndex++] = 0f  /*unused*/

                extrudeVertexArray[extrudeIndex++] = (vertPoint.x - vertexOrigin.x).toFloat()
                extrudeVertexArray[extrudeIndex++] = (vertPoint.y - vertexOrigin.y).toFloat()
                extrudeVertexArray[extrudeIndex++] = (vertPoint.z - vertexOrigin.z).toFloat()
                extrudeVertexArray[extrudeIndex++] = 0f /*unused*/
                extrudeVertexArray[extrudeIndex++] = 0f  /*unused*/

                interiorElements.add(extrudeVertex)
                interiorElements.add(extrudeVertex + 1)

                if (!intermediate) {
                    val index =  verticalIndex / VERTEX_STRIDE
                    
                    // first vertices, that simulate pointA for next vertices
                    vertexArray[verticalIndex++] = (point.x - vertexOrigin.x).toFloat()
                    vertexArray[verticalIndex++] = (point.y - vertexOrigin.y).toFloat()
                    vertexArray[verticalIndex++] = (point.z - vertexOrigin.z).toFloat()
                    vertexArray[verticalIndex++] = upperLeftCorner
                    vertexArray[verticalIndex++] = 0.0f

                    vertexArray[verticalIndex++] = (point.x - vertexOrigin.x).toFloat()
                    vertexArray[verticalIndex++] = (point.y - vertexOrigin.y).toFloat()
                    vertexArray[verticalIndex++] = (point.z - vertexOrigin.z).toFloat()
                    vertexArray[verticalIndex++] = lowerLeftCorner
                    vertexArray[verticalIndex++] = 0.0f

                    vertexArray[verticalIndex++] = (point.x - vertexOrigin.x).toFloat()
                    vertexArray[verticalIndex++] = (point.y - vertexOrigin.y).toFloat()
                    vertexArray[verticalIndex++] = (point.z - vertexOrigin.z).toFloat()
                    vertexArray[verticalIndex++] = upperRightCorner
                    vertexArray[verticalIndex++] = 0.0f

                    vertexArray[verticalIndex++] = (point.x - vertexOrigin.x).toFloat()
                    vertexArray[verticalIndex++] = (point.y - vertexOrigin.y).toFloat()
                    vertexArray[verticalIndex++] = (point.z - vertexOrigin.z).toFloat()
                    vertexArray[verticalIndex++] = lowerRightCorner
                    vertexArray[verticalIndex++] = 0.0f

                    // first pointB
                    vertexArray[verticalIndex++] = (point.x - vertexOrigin.x).toFloat()
                    vertexArray[verticalIndex++] = (point.y - vertexOrigin.y).toFloat()
                    vertexArray[verticalIndex++] = (point.z - vertexOrigin.z).toFloat()
                    vertexArray[verticalIndex++] = upperLeftCorner
                    vertexArray[verticalIndex++] = 0.0f

                    vertexArray[verticalIndex++] = (point.x - vertexOrigin.x).toFloat()
                    vertexArray[verticalIndex++] = (point.y - vertexOrigin.y).toFloat()
                    vertexArray[verticalIndex++] = (point.z - vertexOrigin.z).toFloat()
                    vertexArray[verticalIndex++] = lowerLeftCorner
                    vertexArray[verticalIndex++] = 0.0f

                    vertexArray[verticalIndex++] = (point.x - vertexOrigin.x).toFloat()
                    vertexArray[verticalIndex++] = (point.y - vertexOrigin.y).toFloat()
                    vertexArray[verticalIndex++] = (point.z - vertexOrigin.z).toFloat()
                    vertexArray[verticalIndex++] = upperRightCorner
                    vertexArray[verticalIndex++] = 0.0f

                    vertexArray[verticalIndex++] = (point.x - vertexOrigin.x).toFloat()
                    vertexArray[verticalIndex++] = (point.y - vertexOrigin.y).toFloat()
                    vertexArray[verticalIndex++] = (point.z - vertexOrigin.z).toFloat()
                    vertexArray[verticalIndex++] = lowerRightCorner
                    vertexArray[verticalIndex++] = 0.0f

                    // second pointB
                    vertexArray[verticalIndex++] = (vertPoint.x - vertexOrigin.x).toFloat()
                    vertexArray[verticalIndex++] = (vertPoint.y - vertexOrigin.y).toFloat()
                    vertexArray[verticalIndex++] = (vertPoint.z - vertexOrigin.z).toFloat()
                    vertexArray[verticalIndex++] = upperLeftCorner
                    vertexArray[verticalIndex++] = 0.0f

                    vertexArray[verticalIndex++] = (vertPoint.x - vertexOrigin.x).toFloat()
                    vertexArray[verticalIndex++] = (vertPoint.y - vertexOrigin.y).toFloat()
                    vertexArray[verticalIndex++] = (vertPoint.z - vertexOrigin.z).toFloat()
                    vertexArray[verticalIndex++] = lowerLeftCorner
                    vertexArray[verticalIndex++] = 0.0f

                    vertexArray[verticalIndex++] = (vertPoint.x - vertexOrigin.x).toFloat()
                    vertexArray[verticalIndex++] = (vertPoint.y - vertexOrigin.y).toFloat()
                    vertexArray[verticalIndex++] = (vertPoint.z - vertexOrigin.z).toFloat()
                    vertexArray[verticalIndex++] = upperRightCorner
                    vertexArray[verticalIndex++] = 0.0f

                    vertexArray[verticalIndex++] = (vertPoint.x - vertexOrigin.x).toFloat()
                    vertexArray[verticalIndex++] = (vertPoint.y - vertexOrigin.y).toFloat()
                    vertexArray[verticalIndex++] = (vertPoint.z - vertexOrigin.z).toFloat()
                    vertexArray[verticalIndex++] = lowerRightCorner
                    vertexArray[verticalIndex++] = 0.0f

                    // last vertices, that simulate pointC for previous vertices
                    vertexArray[verticalIndex++] = (vertPoint.x - vertexOrigin.x).toFloat()
                    vertexArray[verticalIndex++] = (vertPoint.y - vertexOrigin.y).toFloat()
                    vertexArray[verticalIndex++] = (vertPoint.z - vertexOrigin.z).toFloat()
                    vertexArray[verticalIndex++] = upperLeftCorner
                    vertexArray[verticalIndex++] = 0.0f

                    vertexArray[verticalIndex++] = (vertPoint.x - vertexOrigin.x).toFloat()
                    vertexArray[verticalIndex++] = (vertPoint.y - vertexOrigin.y).toFloat()
                    vertexArray[verticalIndex++] = (vertPoint.z - vertexOrigin.z).toFloat()
                    vertexArray[verticalIndex++] = lowerLeftCorner
                    vertexArray[verticalIndex++] = 0.0f

                    vertexArray[verticalIndex++] = (vertPoint.x - vertexOrigin.x).toFloat()
                    vertexArray[verticalIndex++] = (vertPoint.y - vertexOrigin.y).toFloat()
                    vertexArray[verticalIndex++] = (vertPoint.z - vertexOrigin.z).toFloat()
                    vertexArray[verticalIndex++] = upperRightCorner
                    vertexArray[verticalIndex++] = 0.0f

                    vertexArray[verticalIndex++] = (vertPoint.x - vertexOrigin.x).toFloat()
                    vertexArray[verticalIndex++] = (vertPoint.y - vertexOrigin.y).toFloat()
                    vertexArray[verticalIndex++] = (vertPoint.z - vertexOrigin.z).toFloat()
                    vertexArray[verticalIndex++] = lowerRightCorner
                    vertexArray[verticalIndex++] = 0.0f

                    // indices for triangles from firstPointB secondPointB
                    verticalElements.add(index + 2)
                    verticalElements.add(index + 3)
                    verticalElements.add(index + 4)
                    verticalElements.add(index + 4)
                    verticalElements.add(index + 3)
                    verticalElements.add(index + 5)
                }
            }
        }
    }
}