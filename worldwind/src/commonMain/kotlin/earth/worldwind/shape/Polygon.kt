package earth.worldwind.shape

import earth.worldwind.draw.DrawShapeState
import earth.worldwind.draw.Drawable
import earth.worldwind.draw.DrawableShape
import earth.worldwind.draw.DrawableSurfaceShape
import earth.worldwind.geom.*
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.render.*
import earth.worldwind.render.buffer.GLBufferObject
import earth.worldwind.render.image.ImageOptions
import earth.worldwind.render.image.ResamplingMode
import earth.worldwind.render.image.WrapMode
import earth.worldwind.render.program.TriangleShaderProgram
import earth.worldwind.shape.PathType.*
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.NumericArray
import earth.worldwind.util.glu.GLU
import earth.worldwind.util.glu.GLUtessellatorCallbackAdapter
import earth.worldwind.util.kgl.*
import earth.worldwind.util.math.encodeOrientationVector
import kotlin.jvm.JvmOverloads

open class Polygon @JvmOverloads constructor(
    positions: List<Position> = emptyList(), attributes: ShapeAttributes = ShapeAttributes()
): AbstractShape(attributes) {
    protected val boundaries = mutableListOf(positions)
    val boundaryCount get() = boundaries.size
    protected var vertexArray = FloatArray(0)
    protected var vertexIndex = 0
    protected var lineVertexArray = FloatArray(0)
    protected var lineVertexIndex = 0
    protected var verticalVertexIndex = 0
    // TODO Use ShortArray instead of mutableListOf<Short> to avoid unnecessary memory re-allocations
    protected val topElements = mutableListOf<Int>()
    protected val sideElements = mutableListOf<Int>()
    protected val outlineElements = mutableListOf<Int>()
    protected val verticalElements = mutableListOf<Int>()
    protected val vertexBufferKey = Any()
    protected val elementBufferKey = Any()
    protected val vertexLinesBufferKey = Any()
    protected val elementLinesBufferKey = Any()
    protected val vertexOrigin = Vec3()
    protected var cameraDistance = 0.0
    protected var texCoord1d = 0.0
    protected val tessCallback = object : GLUtessellatorCallbackAdapter() {
        override fun combineData(
            coords: DoubleArray, data: Array<Any?>, weight: FloatArray, outData: Array<Any?>, polygonData: Any
        ) = tessCombine(polygonData as RenderContext, coords, data, weight, outData)

        override fun vertexData(vertexData: Any, polygonData: Any) = tessVertex(polygonData as RenderContext, vertexData)

        override fun edgeFlagData(boundaryEdge: Boolean, polygonData: Any) = tessEdgeFlag(polygonData as RenderContext, boundaryEdge)

        override fun errorData(errnum: Int, polygonData: Any) = tessError(polygonData as RenderContext, errnum)
    }
    private val point = Vec3()
    private val prevPoint = Vec3()
    private val texCoord2d = Vec3()
    private val texCoordMatrix = Matrix3()
    private val modelToTexCoord = Matrix4()
    private val intermediateLocation = Location()
    private val tessCoords = DoubleArray(3)
    private val tessVertices = IntArray(3)
    private val tessEdgeFlags = BooleanArray(3)
    private var tessEdgeFlag = true
    private var tessVertexCount = 0

    companion object {
        protected const val VERTEX_STRIDE = 5
        protected const val OUTLINE_LINE_SEGMENT_STRIDE = 4 * VERTEX_STRIDE
        protected const val VERTICAL_LINE_SEGMENT_STRIDE = 4 * OUTLINE_LINE_SEGMENT_STRIDE // 4 points per 4 vertices per vertical line
        protected val defaultInteriorImageOptions = ImageOptions().apply { wrapMode = WrapMode.REPEAT }
        protected val defaultOutlineImageOptions = ImageOptions().apply {
            wrapMode = WrapMode.REPEAT
            resamplingMode = ResamplingMode.NEAREST_NEIGHBOR
        }
        protected const val VERTEX_ORIGINAL = 0
        protected const val VERTEX_INTERMEDIATE = 1
        protected const val VERTEX_COMBINED = 2
    }

    fun getBoundary(index: Int): List<Position> {
        require(index in boundaries.indices) {
            logMessage(ERROR, "Polygon", "getBoundary", "invalidIndex")
        }
        return boundaries[index]
    }

    fun setBoundary(index: Int, positions: List<Position>): List<Position> {
        require(index in boundaries.indices) {
            logMessage(ERROR, "Polygon", "setBoundary", "invalidIndex")
        }
        reset()
        // TODO Make deep copy of positions the same way as for single position shapes?
        return boundaries.set(index, positions)
    }

    fun addBoundary(positions: List<Position>): Boolean {
        reset()
        // TODO Make deep copy of positions the same way as for single position shapes?
        return boundaries.add(positions)
    }

    fun addBoundary(index: Int, positions: List<Position>) {
        require(index in boundaries.indices) {
            logMessage(ERROR, "Polygon", "addBoundary", "invalidIndex")
        }
        reset()
        // TODO Make deep copy of positions the same way as for single position shapes?
        boundaries.add(index, positions)
    }

    fun removeBoundary(index: Int): List<Position> {
        require(index in boundaries.indices) {
            logMessage(ERROR, "Polygon", "removeBoundary", "invalidIndex")
        }
        reset()
        return boundaries.removeAt(index)
    }

    fun clearBoundaries() {
        boundaries.clear()
        reset()
    }

    override fun reset() {
        super.reset()
        vertexArray = FloatArray(0)
        lineVertexArray = FloatArray(0)
        topElements.clear()
        sideElements.clear()
        outlineElements.clear()
        verticalElements.clear()
    }

    override fun makeDrawable(rc: RenderContext) {
        if (boundaries.isEmpty()) return  // nothing to draw

        val reassembleGeometry = mustAssembleGeometry(rc)
        if (reassembleGeometry) {
            assembleGeometry(rc)
        }

        // Obtain a drawable form the render context pool.
        val drawable: Drawable
        val drawState: DrawShapeState
        val drawableLines: Drawable
        val drawStateLines: DrawShapeState
        if (isSurfaceShape) {
            val pool = rc.getDrawablePool<DrawableSurfaceShape>()
            drawable = DrawableSurfaceShape.obtain(pool)
            drawState = drawable.drawState
            cameraDistance = cameraDistanceGeographic(rc, boundingSector)
            drawable.offset = rc.globe.offset
            drawable.sector.copy(boundingSector)

            drawableLines = DrawableSurfaceShape.obtain(pool)
            drawStateLines = drawableLines.drawState

            drawableLines.offset = rc.globe.offset
            drawableLines.sector.copy(boundingSector)
        } else {
            val pool = rc.getDrawablePool<DrawableShape>()
            drawable = DrawableShape.obtain(pool)
            drawState = drawable.drawState

            drawableLines = DrawableShape.obtain(pool)
            drawStateLines = drawableLines.drawState

            cameraDistance = cameraDistanceCartesian(rc, vertexArray, vertexIndex, VERTEX_STRIDE, vertexOrigin)
        }

        // Use the basic GLSL program to draw the shape.
        drawState.program = rc.getShaderProgram { TriangleShaderProgram() }

        // Assemble the drawable's OpenGL vertex buffer object.
        drawState.tmpVertexBuffer = rc.getGLBufferObject(vertexBufferKey) {
            GLBufferObject(GL_ARRAY_BUFFER, 0)
        }
        if(reassembleGeometry) { rc.offerGLBufferUpload(vertexBufferKey, NumericArray.Floats(vertexArray)) }

        // Assemble the drawable's OpenGL element buffer object.
        drawState.tmpElementBuffer = rc.getGLBufferObject(elementBufferKey) {
            GLBufferObject(GL_ELEMENT_ARRAY_BUFFER, 0)
        }
        if(reassembleGeometry) {
            val array = IntArray(topElements.size + sideElements.size)
            var index = 0
            for (element in topElements) array[index++] = element
            for (element in sideElements) array[index++] = element
            rc.offerGLBufferUpload(elementBufferKey, NumericArray.Ints(array))
        }

        // Use triangles mode to draw lines
        drawStateLines.isLine = true

        // Use the geom lines GLSL program to draw the shape.
        drawStateLines.program = rc.getShaderProgram { TriangleShaderProgram() }

        // Assemble the drawable's OpenGL vertex buffer object.
        drawStateLines.tmpVertexBuffer = rc.getGLBufferObject(vertexLinesBufferKey) {
            GLBufferObject(GL_ARRAY_BUFFER, 0)
        }
        if(reassembleGeometry) { rc.offerGLBufferUpload(vertexLinesBufferKey, NumericArray.Floats(lineVertexArray)) }

        // Assemble the drawable's OpenGL element buffer object.
        drawStateLines.tmpElementBuffer = rc.getGLBufferObject(elementLinesBufferKey) {
            GLBufferObject(GL_ELEMENT_ARRAY_BUFFER, 0)
        }
        if(reassembleGeometry) {
            val array = IntArray(outlineElements.size + verticalElements.size)
            var index = 0
            for (element in outlineElements) array[index++] = element
            for (element in verticalElements) array[index++] = element
            rc.offerGLBufferUpload(elementLinesBufferKey, NumericArray.Ints(array))
        }

        drawInterior(rc, drawState)
        drawOutline(rc, drawStateLines)

        // Configure the drawable according to the shape's attributes. Disable triangle backface culling when we're
        // displaying a polygon without extruded sides, so we want to draw the top and the bottom.
        drawState.vertexOrigin.copy(vertexOrigin)
        drawState.vertexStride = VERTEX_STRIDE * 4 // stride in bytes
        drawState.enableCullFace = isExtrude
        drawState.enableDepthTest = activeAttributes.isDepthTest
        drawState.enableDepthWrite = activeAttributes.isDepthWrite

        // Configure the drawable according to the shape's attributes.
        drawStateLines.vertexOrigin.copy(vertexOrigin)
        drawStateLines.enableCullFace = false
        drawStateLines.enableDepthTest = activeAttributes.isDepthTest
        drawStateLines.enableDepthWrite = activeAttributes.isDepthWrite

        // Enqueue the drawable for processing on the OpenGL thread.
        if (isSurfaceShape|| activeAttributes.interiorColor.alpha >= 1.0) {
            rc.offerSurfaceDrawable(drawable, 0.0 /*zOrder*/)
            rc.offerSurfaceDrawable(drawableLines, 0.0 /*zOrder*/)
        } else {
            rc.offerShapeDrawable(drawableLines, cameraDistance)
            rc.offerShapeDrawable(drawable, cameraDistance)
        }
    }

    protected open fun drawInterior(rc: RenderContext, drawState: DrawShapeState) {
        if (!activeAttributes.isDrawInterior) return

        // Configure the drawable to use the interior texture when drawing the interior.
        activeAttributes.interiorImageSource?.let { interiorImageSource ->
            rc.getTexture(interiorImageSource, defaultInteriorImageOptions)?.let { texture ->
                val metersPerPixel = rc.pixelSizeAtDistance(cameraDistance)
                computeRepeatingTexCoordTransform(texture, metersPerPixel, texCoordMatrix)
                drawState.texture(texture)
                drawState.texCoordMatrix(texCoordMatrix)
            }
        } ?: drawState.texture(null)

        // Configure the drawable to display the shape's interior top.
        drawState.color(if (rc.isPickMode) pickColor else activeAttributes.interiorColor)
        drawState.opacity(if (rc.isPickMode) 1f else rc.currentLayer.opacity)
        drawState.texCoordAttrib(2 /*size*/, 12 /*offset in bytes*/)
        drawState.drawElements(GL_TRIANGLES, topElements.size, GL_UNSIGNED_INT, 0 /*offset*/)

        // Configure the drawable to display the shape's interior sides.
        if (isExtrude) {
            drawState.texture(null)
            drawState.drawElements(GL_TRIANGLES, sideElements.size, GL_UNSIGNED_INT, topElements.size * Int.SIZE_BYTES /*offset*/)
        }
    }

    protected open fun drawOutline(rc: RenderContext, drawState: DrawShapeState) {
        if (!activeAttributes.isDrawOutline) return

        // Configure the drawable to use the outline texture when drawing the outline.
        activeAttributes.outlineImageSource?.let { outlineImageSource ->
            rc.getTexture(outlineImageSource, defaultOutlineImageOptions)?.let { texture ->
                val metersPerPixel = rc.pixelSizeAtDistance(cameraDistance)
                computeRepeatingTexCoordTransform(texture, metersPerPixel, texCoordMatrix)
                drawState.texture(texture)
                drawState.texCoordMatrix(texCoordMatrix)
            }
        } ?: drawState.texture(null)

        // Configure the drawable to display the shape's outline.
        drawState.color(if (rc.isPickMode) pickColor else activeAttributes.outlineColor)
        drawState.opacity(if (rc.isPickMode) 1f else rc.currentLayer.opacity)
        drawState.lineWidth(activeAttributes.outlineWidth)
        drawState.drawElements(
            GL_TRIANGLES, outlineElements.size,
            GL_UNSIGNED_INT, 0 /*offset*/
        )

        // Configure the drawable to display the shape's extruded verticals.
        if (activeAttributes.isDrawVerticals && isExtrude) {
            drawState.color(if (rc.isPickMode) pickColor else activeAttributes.outlineColor)
            drawState.opacity(if (rc.isPickMode) 1f else rc.currentLayer.opacity)
            drawState.lineWidth(activeAttributes.outlineWidth)
            drawState.texture(null)
            drawState.drawElements(
                GL_TRIANGLES, verticalElements.size,
                GL_UNSIGNED_INT, outlineElements.size * Int.SIZE_BYTES /*offset*/
            )
        }
    }

    protected open fun mustAssembleGeometry(rc: RenderContext) = vertexArray.isEmpty() || isExtrude && !isSurfaceShape && lineVertexArray.isEmpty()

    protected open fun assembleGeometry(rc: RenderContext) {
        // Determine the number of vertexes
        val noIntermediatePoints = maximumIntermediatePoints <= 0 || pathType == LINEAR

        var vertexCount = 0
        var lineVertexCount = 0
        var verticalVertexCount = 0
        for (i in boundaries.indices) {
            val p = boundaries[i]

            if (p.isEmpty()) continue

            if (noIntermediatePoints) {
                vertexCount += p.size
                lineVertexCount += (p.size + 2) // +2 is for point A and point C at the start and end if line strip
                verticalVertexCount += p.size
            } else if (p.isNotEmpty() && p[0] == p[p.size - 1]) {
                vertexCount += p.size + (p.size - 1) * maximumIntermediatePoints
                lineVertexCount += 2 + p.size + (p.size - 1) * maximumIntermediatePoints
                verticalVertexCount += p.size
            } else {
                vertexCount += p.size + p.size * maximumIntermediatePoints
                lineVertexCount += 3 + p.size + p.size * maximumIntermediatePoints
                verticalVertexCount += p.size
            }
        }

        // Clear the shape's vertex array and element arrays. These arrays will accumulate values as the shapes's
        // geometry is assembled.
        vertexIndex = 0
        vertexArray = if (isExtrude && !isSurfaceShape) FloatArray(vertexCount * 2 * VERTEX_STRIDE)
        else if (!isSurfaceShape) FloatArray(vertexCount * VERTEX_STRIDE)
        else FloatArray((vertexCount + boundaries.size) * VERTEX_STRIDE) // Reserve boundaries.size for combined vertexes
        topElements.clear()
        sideElements.clear()
        lineVertexIndex = 0
        verticalVertexIndex = lineVertexCount * OUTLINE_LINE_SEGMENT_STRIDE
        lineVertexArray = if (isExtrude && !isSurfaceShape) FloatArray(lineVertexCount * OUTLINE_LINE_SEGMENT_STRIDE + verticalVertexCount * VERTICAL_LINE_SEGMENT_STRIDE)
        else FloatArray(lineVertexCount * OUTLINE_LINE_SEGMENT_STRIDE)
        outlineElements.clear()
        verticalElements.clear()

        // Compute a matrix that transforms from Cartesian coordinates to shape texture coordinates.
        determineModelToTexCoord(rc)
        val tess = rc.tessellator
        GLU.gluTessNormal(tess, 0.0, 0.0, 1.0)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_COMBINE_DATA, tessCallback)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_VERTEX_DATA, tessCallback)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_EDGE_FLAG_DATA, tessCallback)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_ERROR_DATA, tessCallback)
        GLU.gluTessBeginPolygon(tess, rc)
        for (i in boundaries.indices) {
            val positions = boundaries[i]
            if (positions.isEmpty()) continue  // no boundary positions to assemble

            GLU.gluTessBeginContour(tess)

            // Add the boundary's first vertex.
            var begin = positions[0]
            addVertex(rc, begin.latitude, begin.longitude, begin.altitude, VERTEX_ORIGINAL /*type*/)

            addLineVertex(rc, begin.latitude, begin.longitude, begin.altitude, true, true)
            addLineVertex(rc, begin.latitude, begin.longitude, begin.altitude, false, true)

            // Add the remaining boundary vertices, tessellating each edge as indicated by the polygon's properties.
            for (idx in 1 until positions.size) {
                val end = positions[idx]
                addIntermediateVertices(rc, begin, end)
                addVertex(rc, end.latitude, end.longitude, end.altitude, VERTEX_ORIGINAL /*type*/)
                addLineVertex(rc, end.latitude, end.longitude, end.altitude, false, if (idx == (positions.size - 1)) end != positions[0] else true) // check if there is implicit closing edge
                begin = end
            }

            // Tessellate the implicit closing edge if the boundary is not already closed.
            if (begin != positions[0]) {
                addIntermediateVertices(rc, begin, positions[0])
                addLineVertex(rc, positions[0].latitude, positions[0].longitude, positions[0].altitude, true, false)
                addLineVertex(rc, positions[0].latitude, positions[0].longitude, positions[0].altitude, true, false)
            } else {
                addLineVertex(rc, begin.latitude, begin.longitude, begin.altitude, true, false)
            }
            GLU.gluTessEndContour(tess)
        }
        GLU.gluTessEndPolygon(tess)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_COMBINE_DATA, null)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_VERTEX_DATA, null)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_EDGE_FLAG_DATA, null)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_ERROR_DATA, null)

        // Compute the shape's bounding box or bounding sector from its assembled coordinates.
        if (isSurfaceShape) {
            boundingSector.setEmpty()
            boundingSector.union(vertexArray, vertexIndex, VERTEX_STRIDE)
            boundingSector.translate(vertexOrigin.y /*lat*/, vertexOrigin.x /*lon*/)
            boundingBox.setToUnitBox() // Surface/geographic shape bounding box is unused
        } else {
            boundingBox.setToPoints(vertexArray, vertexIndex, VERTEX_STRIDE)
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
            addVertex(rc, loc.latitude, loc.longitude, alt, VERTEX_INTERMEDIATE /*type*/)
            addLineVertex(rc, loc.latitude, loc.longitude, alt, true, true)
            dist += deltaDist
            alt += deltaAlt
        }
    }

    protected open fun addVertex(rc: RenderContext, latitude: Angle, longitude: Angle, altitude: Double, type: Int): Int {
        val vertex = vertexIndex / VERTEX_STRIDE
        var point = rc.geographicToCartesian(latitude, longitude, altitude, altitudeMode, point)
        val texCoord2d = texCoord2d.copy(point).multiplyByMatrix(modelToTexCoord)
        if (type != VERTEX_COMBINED) {
            tessCoords[0] = longitude.inDegrees
            tessCoords[1] = latitude.inDegrees
            tessCoords[2] = altitude
            GLU.gluTessVertex(rc.tessellator, tessCoords, 0 /*coords_offset*/, vertex)
        }
        if (vertex == 0) {
            if (isSurfaceShape) vertexOrigin.set(longitude.inDegrees, latitude.inDegrees, altitude) else vertexOrigin.copy(point)
        }
        if (isSurfaceShape) {
            vertexArray[vertexIndex++] = (longitude.inDegrees - vertexOrigin.x).toFloat()
            vertexArray[vertexIndex++] = (latitude.inDegrees - vertexOrigin.y).toFloat()
            vertexArray[vertexIndex++] = (altitude - vertexOrigin.z).toFloat()
            vertexArray[vertexIndex++] = texCoord2d.x.toFloat()
            vertexArray[vertexIndex++] = texCoord2d.y.toFloat()
        } else {
            vertexArray[vertexIndex++] = (point.x - vertexOrigin.x).toFloat()
            vertexArray[vertexIndex++] = (point.y - vertexOrigin.y).toFloat()
            vertexArray[vertexIndex++] = (point.z - vertexOrigin.z).toFloat()
            vertexArray[vertexIndex++] = texCoord2d.x.toFloat()
            vertexArray[vertexIndex++] = texCoord2d.y.toFloat()
            if (isExtrude) {
                point = rc.geographicToCartesian(latitude, longitude, 0.0, AltitudeMode.CLAMP_TO_GROUND, this.point)
                vertexArray[vertexIndex++] = (point.x - vertexOrigin.x).toFloat()
                vertexArray[vertexIndex++] = (point.y - vertexOrigin.y).toFloat()
                vertexArray[vertexIndex++] = (point.z - vertexOrigin.z).toFloat()
                vertexArray[vertexIndex++] = 0f /*unused*/
                vertexArray[vertexIndex++] = 0f /*unused*/
            }
        }
        return vertex
    }

    protected open fun addLineVertex(
        rc: RenderContext, latitude: Angle, longitude: Angle, altitude: Double, isIntermediate : Boolean, addIndices : Boolean
    ) {
        val vertex = lineVertexIndex / VERTEX_STRIDE
        val point = rc.geographicToCartesian(latitude, longitude, altitude, altitudeMode, point)
        if (lineVertexIndex == 0) texCoord1d = 0.0
        else texCoord1d += point.distanceTo(prevPoint)
        prevPoint.copy(point)
        val upperLeftCorner = encodeOrientationVector(-1f, 1f)
        val lowerLeftCorner = encodeOrientationVector(-1f, -1f)
        val upperRightCorner = encodeOrientationVector(1f, 1f)
        val lowerRightCorner = encodeOrientationVector(1f, -1f)
        if (isSurfaceShape) {
            lineVertexArray[lineVertexIndex++] = (longitude.inDegrees - vertexOrigin.x).toFloat()
            lineVertexArray[lineVertexIndex++] = (latitude.inDegrees - vertexOrigin.y).toFloat()
            lineVertexArray[lineVertexIndex++] = (altitude - vertexOrigin.z).toFloat()
            lineVertexArray[lineVertexIndex++] = upperLeftCorner
            lineVertexArray[lineVertexIndex++] = texCoord1d.toFloat()

            lineVertexArray[lineVertexIndex++] = (longitude.inDegrees - vertexOrigin.x).toFloat()
            lineVertexArray[lineVertexIndex++] = (latitude.inDegrees - vertexOrigin.y).toFloat()
            lineVertexArray[lineVertexIndex++] = (altitude - vertexOrigin.z).toFloat()
            lineVertexArray[lineVertexIndex++] = lowerLeftCorner
            lineVertexArray[lineVertexIndex++] = texCoord1d.toFloat()

            lineVertexArray[lineVertexIndex++] = (longitude.inDegrees - vertexOrigin.x).toFloat()
            lineVertexArray[lineVertexIndex++] = (latitude.inDegrees - vertexOrigin.y).toFloat()
            lineVertexArray[lineVertexIndex++] = (altitude - vertexOrigin.z).toFloat()
            lineVertexArray[lineVertexIndex++] = upperRightCorner
            lineVertexArray[lineVertexIndex++] = texCoord1d.toFloat()

            lineVertexArray[lineVertexIndex++] = (longitude.inDegrees - vertexOrigin.x).toFloat()
            lineVertexArray[lineVertexIndex++] = (latitude.inDegrees - vertexOrigin.y).toFloat()
            lineVertexArray[lineVertexIndex++] = (altitude - vertexOrigin.z).toFloat()
            lineVertexArray[lineVertexIndex++] = lowerRightCorner
            lineVertexArray[lineVertexIndex++] = texCoord1d.toFloat()
            if (addIndices) {
                // indices for triangles made from this segment vertices
                outlineElements.add(vertex)
                outlineElements.add(vertex + 1)
                outlineElements.add(vertex + 2)
                outlineElements.add(vertex + 2)
                outlineElements.add(vertex + 1)
                outlineElements.add(vertex + 3)
                // indices for triangles made from last vertices of this segment and first vertices of next segment
                outlineElements.add(vertex + 2)
                outlineElements.add(vertex + 3)
                outlineElements.add(vertex + 4)
                outlineElements.add(vertex + 4)
                outlineElements.add(vertex + 3)
                outlineElements.add(vertex + 5)
            }
        } else {
            lineVertexArray[lineVertexIndex++] = (point.x - vertexOrigin.x).toFloat()
            lineVertexArray[lineVertexIndex++] = (point.y - vertexOrigin.y).toFloat()
            lineVertexArray[lineVertexIndex++] = (point.z - vertexOrigin.z).toFloat()
            lineVertexArray[lineVertexIndex++] = upperLeftCorner
            lineVertexArray[lineVertexIndex++] = texCoord1d.toFloat()

            lineVertexArray[lineVertexIndex++] = (point.x - vertexOrigin.x).toFloat()
            lineVertexArray[lineVertexIndex++] = (point.y - vertexOrigin.y).toFloat()
            lineVertexArray[lineVertexIndex++] = (point.z - vertexOrigin.z).toFloat()
            lineVertexArray[lineVertexIndex++] = lowerLeftCorner
            lineVertexArray[lineVertexIndex++] = texCoord1d.toFloat()

            lineVertexArray[lineVertexIndex++] = (point.x - vertexOrigin.x).toFloat()
            lineVertexArray[lineVertexIndex++] = (point.y - vertexOrigin.y).toFloat()
            lineVertexArray[lineVertexIndex++] = (point.z - vertexOrigin.z).toFloat()
            lineVertexArray[lineVertexIndex++] = upperRightCorner
            lineVertexArray[lineVertexIndex++] = texCoord1d.toFloat()

            lineVertexArray[lineVertexIndex++] = (point.x - vertexOrigin.x).toFloat()
            lineVertexArray[lineVertexIndex++] = (point.y - vertexOrigin.y).toFloat()
            lineVertexArray[lineVertexIndex++] = (point.z - vertexOrigin.z).toFloat()
            lineVertexArray[lineVertexIndex++] = lowerRightCorner
            lineVertexArray[lineVertexIndex++] = texCoord1d.toFloat()
            if (addIndices) {
                // indices for triangles made from this segment vertices
                outlineElements.add(vertex)
                outlineElements.add(vertex + 1)
                outlineElements.add(vertex + 2)
                outlineElements.add(vertex + 2)
                outlineElements.add(vertex + 1)
                outlineElements.add(vertex + 3)
                // indices for triangles made from last vertices of this segment and first vertices of next segment
                outlineElements.add(vertex + 2)
                outlineElements.add(vertex + 3)
                outlineElements.add(vertex + 4)
                outlineElements.add(vertex + 4)
                outlineElements.add(vertex + 3)
                outlineElements.add(vertex + 5)
            }
            if (isExtrude && !isIntermediate) {
                var vertPoint = Vec3()
                vertPoint = rc.geographicToCartesian(latitude, longitude, 0.0, altitudeMode, vertPoint)
                val index =  verticalVertexIndex / VERTEX_STRIDE

                // first vertices, that simulate pointA for next vertices
                lineVertexArray[verticalVertexIndex++] = (point.x - vertexOrigin.x).toFloat()
                lineVertexArray[verticalVertexIndex++] = (point.y - vertexOrigin.y).toFloat()
                lineVertexArray[verticalVertexIndex++] = (point.z - vertexOrigin.z).toFloat()
                lineVertexArray[verticalVertexIndex++] = upperLeftCorner
                lineVertexArray[verticalVertexIndex++] = 0.0f

                lineVertexArray[verticalVertexIndex++] = (point.x - vertexOrigin.x).toFloat()
                lineVertexArray[verticalVertexIndex++] = (point.y - vertexOrigin.y).toFloat()
                lineVertexArray[verticalVertexIndex++] = (point.z - vertexOrigin.z).toFloat()
                lineVertexArray[verticalVertexIndex++] = lowerLeftCorner
                lineVertexArray[verticalVertexIndex++] = 0.0f

                lineVertexArray[verticalVertexIndex++] = (point.x - vertexOrigin.x).toFloat()
                lineVertexArray[verticalVertexIndex++] = (point.y - vertexOrigin.y).toFloat()
                lineVertexArray[verticalVertexIndex++] = (point.z - vertexOrigin.z).toFloat()
                lineVertexArray[verticalVertexIndex++] = upperRightCorner
                lineVertexArray[verticalVertexIndex++] = 0.0f

                lineVertexArray[verticalVertexIndex++] = (point.x - vertexOrigin.x).toFloat()
                lineVertexArray[verticalVertexIndex++] = (point.y - vertexOrigin.y).toFloat()
                lineVertexArray[verticalVertexIndex++] = (point.z - vertexOrigin.z).toFloat()
                lineVertexArray[verticalVertexIndex++] = lowerRightCorner
                lineVertexArray[verticalVertexIndex++] = 0.0f

                // first pointB
                lineVertexArray[verticalVertexIndex++] = (point.x - vertexOrigin.x).toFloat()
                lineVertexArray[verticalVertexIndex++] = (point.y - vertexOrigin.y).toFloat()
                lineVertexArray[verticalVertexIndex++] = (point.z - vertexOrigin.z).toFloat()
                lineVertexArray[verticalVertexIndex++] = upperLeftCorner
                lineVertexArray[verticalVertexIndex++] = 0.0f

                lineVertexArray[verticalVertexIndex++] = (point.x - vertexOrigin.x).toFloat()
                lineVertexArray[verticalVertexIndex++] = (point.y - vertexOrigin.y).toFloat()
                lineVertexArray[verticalVertexIndex++] = (point.z - vertexOrigin.z).toFloat()
                lineVertexArray[verticalVertexIndex++] = lowerLeftCorner
                lineVertexArray[verticalVertexIndex++] = 0.0f

                lineVertexArray[verticalVertexIndex++] = (point.x - vertexOrigin.x).toFloat()
                lineVertexArray[verticalVertexIndex++] = (point.y - vertexOrigin.y).toFloat()
                lineVertexArray[verticalVertexIndex++] = (point.z - vertexOrigin.z).toFloat()
                lineVertexArray[verticalVertexIndex++] = upperRightCorner
                lineVertexArray[verticalVertexIndex++] = 0.0f

                lineVertexArray[verticalVertexIndex++] = (point.x - vertexOrigin.x).toFloat()
                lineVertexArray[verticalVertexIndex++] = (point.y - vertexOrigin.y).toFloat()
                lineVertexArray[verticalVertexIndex++] = (point.z - vertexOrigin.z).toFloat()
                lineVertexArray[verticalVertexIndex++] = lowerRightCorner
                lineVertexArray[verticalVertexIndex++] = 0.0f

                // second pointB
                lineVertexArray[verticalVertexIndex++] = (vertPoint.x - vertexOrigin.x).toFloat()
                lineVertexArray[verticalVertexIndex++] = (vertPoint.y - vertexOrigin.y).toFloat()
                lineVertexArray[verticalVertexIndex++] = (vertPoint.z - vertexOrigin.z).toFloat()
                lineVertexArray[verticalVertexIndex++] = upperLeftCorner
                lineVertexArray[verticalVertexIndex++] = 0.0f

                lineVertexArray[verticalVertexIndex++] = (vertPoint.x - vertexOrigin.x).toFloat()
                lineVertexArray[verticalVertexIndex++] = (vertPoint.y - vertexOrigin.y).toFloat()
                lineVertexArray[verticalVertexIndex++] = (vertPoint.z - vertexOrigin.z).toFloat()
                lineVertexArray[verticalVertexIndex++] = lowerLeftCorner
                lineVertexArray[verticalVertexIndex++] = 0.0f

                lineVertexArray[verticalVertexIndex++] = (vertPoint.x - vertexOrigin.x).toFloat()
                lineVertexArray[verticalVertexIndex++] = (vertPoint.y - vertexOrigin.y).toFloat()
                lineVertexArray[verticalVertexIndex++] = (vertPoint.z - vertexOrigin.z).toFloat()
                lineVertexArray[verticalVertexIndex++] = upperRightCorner
                lineVertexArray[verticalVertexIndex++] = 0.0f

                lineVertexArray[verticalVertexIndex++] = (vertPoint.x - vertexOrigin.x).toFloat()
                lineVertexArray[verticalVertexIndex++] = (vertPoint.y - vertexOrigin.y).toFloat()
                lineVertexArray[verticalVertexIndex++] = (vertPoint.z - vertexOrigin.z).toFloat()
                lineVertexArray[verticalVertexIndex++] = lowerRightCorner
                lineVertexArray[verticalVertexIndex++] = 0.0f

                // last vertices, that simulate pointC for previous vertices
                lineVertexArray[verticalVertexIndex++] = (vertPoint.x - vertexOrigin.x).toFloat()
                lineVertexArray[verticalVertexIndex++] = (vertPoint.y - vertexOrigin.y).toFloat()
                lineVertexArray[verticalVertexIndex++] = (vertPoint.z - vertexOrigin.z).toFloat()
                lineVertexArray[verticalVertexIndex++] = upperLeftCorner
                lineVertexArray[verticalVertexIndex++] = 0.0f

                lineVertexArray[verticalVertexIndex++] = (vertPoint.x - vertexOrigin.x).toFloat()
                lineVertexArray[verticalVertexIndex++] = (vertPoint.y - vertexOrigin.y).toFloat()
                lineVertexArray[verticalVertexIndex++] = (vertPoint.z - vertexOrigin.z).toFloat()
                lineVertexArray[verticalVertexIndex++] = lowerLeftCorner
                lineVertexArray[verticalVertexIndex++] = 0.0f

                lineVertexArray[verticalVertexIndex++] = (vertPoint.x - vertexOrigin.x).toFloat()
                lineVertexArray[verticalVertexIndex++] = (vertPoint.y - vertexOrigin.y).toFloat()
                lineVertexArray[verticalVertexIndex++] = (vertPoint.z - vertexOrigin.z).toFloat()
                lineVertexArray[verticalVertexIndex++] = upperRightCorner
                lineVertexArray[verticalVertexIndex++] = 0.0f

                lineVertexArray[verticalVertexIndex++] = (vertPoint.x - vertexOrigin.x).toFloat()
                lineVertexArray[verticalVertexIndex++] = (vertPoint.y - vertexOrigin.y).toFloat()
                lineVertexArray[verticalVertexIndex++] = (vertPoint.z - vertexOrigin.z).toFloat()
                lineVertexArray[verticalVertexIndex++] = lowerRightCorner
                lineVertexArray[verticalVertexIndex++] = 0.0f

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

    protected open fun determineModelToTexCoord(rc: RenderContext) {
        var mx = 0.0
        var my = 0.0
        var mz = 0.0
        var numPoints = 0.0
        for (i in boundaries.indices) {
            val positions = boundaries[i]
            if (positions.isEmpty()) continue  // no boundary positions
            for (j in positions.indices) {
                val point = rc.geographicToCartesian(positions[j], AltitudeMode.ABSOLUTE, point)
                mx += point.x
                my += point.y
                mz += point.z
                numPoints++
            }
        }
        mx /= numPoints
        my /= numPoints
        mz /= numPoints
        rc.globe.cartesianToLocalTransform(mx, my, mz, modelToTexCoord)
        modelToTexCoord.invertOrthonormal()
    }

    protected open fun tessCombine(rc: RenderContext, coords: DoubleArray, data: Array<Any?>, weight: FloatArray, outData: Array<Any?>) {
        ensureVertexArrayCapacity() // Increment array size to fit combined vertexes
        outData[0] = addVertex(rc, coords[1].degrees /*lat*/, coords[0].degrees /*lon*/, coords[2] /*alt*/, VERTEX_COMBINED /*type*/)
    }

    protected open fun tessVertex(rc: RenderContext, vertexData: Any) {
        tessVertices[tessVertexCount] = vertexData as Int
        tessEdgeFlags[tessVertexCount] = tessEdgeFlag
        if (tessVertexCount < 2) {
            tessVertexCount++ // increment the vertex count and wait for more vertices
            return
        } else {
            tessVertexCount = 0 // reset the vertex count and process one triangle
        }
        val v0 = tessVertices[0]
        val v1 = tessVertices[1]
        val v2 = tessVertices[2]
        topElements.add(v0)
        topElements.add(v1)
        topElements.add(v2)
        if (tessEdgeFlags[0] && isExtrude && !isSurfaceShape) {
            sideElements.add(v0)
            sideElements.add(v0.inc())
            sideElements.add(v1)
            sideElements.add(v1)
            sideElements.add(v0.inc())
            sideElements.add(v1.inc())
        }
        if (tessEdgeFlags[1] && isExtrude && !isSurfaceShape) {
            sideElements.add(v1)
            sideElements.add(v1.inc())
            sideElements.add(v2)
            sideElements.add(v2)
            sideElements.add(v1.inc())
            sideElements.add(v2.inc())
        }
        if (tessEdgeFlags[2] && isExtrude && !isSurfaceShape) {
            sideElements.add(v2)
            sideElements.add(v2.inc())
            sideElements.add(v0)
            sideElements.add(v0)
            sideElements.add(v2.inc())
            sideElements.add(v0.inc())
        }
    }

    protected open fun tessEdgeFlag(rc: RenderContext, boundaryEdge: Boolean) { tessEdgeFlag = boundaryEdge }

    protected open fun tessError(rc: RenderContext, errNum: Int) {
        val errStr = GLU.gluErrorString(errNum)
        logMessage(
            WARN, "Polygon", "assembleGeometry", "Error attempting to tessellate polygon '$errStr'"
        )
    }

    protected open fun ensureVertexArrayCapacity() {
        val size = vertexArray.size
        if (size == vertexIndex) {
            val increment = (size shr 1).coerceAtLeast(12)
            val newArray = FloatArray(size + increment)
            vertexArray.copyInto(newArray)
            vertexArray = newArray
        }
    }
}