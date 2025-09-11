package earth.worldwind.shape

import earth.worldwind.draw.DrawShapeState
import earth.worldwind.draw.Drawable
import earth.worldwind.draw.DrawableShape
import earth.worldwind.draw.DrawableSurfaceShape
import earth.worldwind.geom.*
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.globe.Globe
import earth.worldwind.render.RenderContext
import earth.worldwind.render.buffer.BufferObject
import earth.worldwind.render.program.TriangleShaderProgram
import earth.worldwind.shape.PathType.*
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.NumericArray
import earth.worldwind.util.glu.GLU
import earth.worldwind.util.glu.GLUtessellatorCallbackAdapter
import earth.worldwind.util.kgl.GL_ARRAY_BUFFER
import earth.worldwind.util.kgl.GL_ELEMENT_ARRAY_BUFFER
import earth.worldwind.util.kgl.GL_TRIANGLES
import earth.worldwind.util.kgl.GL_UNSIGNED_INT
import earth.worldwind.util.math.encodeOrientationVector
import kotlin.jvm.JvmOverloads

open class Polygon @JvmOverloads constructor(
    positions: List<Position> = emptyList(), attributes: ShapeAttributes = ShapeAttributes()
): AbstractShape(attributes) {
    override val referencePosition: Position get() {
        val sector = Sector()
        for (boundary in boundaries) for (position in boundary) sector.union(position)
        return Position(sector.centroidLatitude, sector.centroidLongitude, 0.0)
    }
    val boundaryCount get() = boundaries.size
    protected val boundaries = mutableListOf(positions)
    protected val data = mutableMapOf<Globe.State?, PolygonData>()
    protected val isPlain get() = altitudeMode == AltitudeMode.RELATIVE_TO_GROUND && isExtrude && !isFollowTerrain
    protected val tessCallback = object : GLUtessellatorCallbackAdapter() {
        override fun combineData(
            coords: DoubleArray, data: Array<Any?>, weight: FloatArray, outData: Array<Any?>, polygonData: Any?
        ) = tessCombine(polygonData as RenderContext, coords, data, weight, outData)

        override fun vertexData(vertexData: Any?, polygonData: Any?) = tessVertex(polygonData as RenderContext, vertexData)

        override fun edgeFlagData(boundaryEdge: Boolean, polygonData: Any?) = tessEdgeFlag(polygonData as RenderContext, boundaryEdge)

        override fun errorData(errnum: Int, polygonData: Any?) = tessError(polygonData as RenderContext, errnum)
    }

    open class PolygonData {
        val vertexOrigin = Vec3()
        var vertexArray = FloatArray(0)
        var lineVertexArray = FloatArray(0)
        // TODO Use IntArray instead of mutableListOf<Int> to avoid unnecessary memory re-allocations
        val topElements = mutableListOf<Int>()
        val sideElements = mutableListOf<Int>()
        val baseElements = mutableListOf<Int>()
        val outlineElements = mutableListOf<Int>()
        val verticalElements = mutableListOf<Int>()
        val vertexBufferKey = Any()
        val elementBufferKey = Any()
        val vertexLinesBufferKey = Any()
        val elementLinesBufferKey = Any()
        var refreshVertexArray = true
        var refreshLineVertexArray = true
    }

    companion object {
        protected const val VERTEX_STRIDE = 5
        protected const val OUTLINE_LINE_SEGMENT_STRIDE = 4 * VERTEX_STRIDE
        protected const val VERTICAL_LINE_SEGMENT_STRIDE = 4 * OUTLINE_LINE_SEGMENT_STRIDE // 4 points per 4 vertices per vertical line
        protected const val VERTEX_ORIGINAL = 0
        protected const val VERTEX_INTERMEDIATE = 1
        protected const val VERTEX_COMBINED = 2

        protected lateinit var currentData: PolygonData

        protected var vertexIndex = 0
        protected var lineVertexIndex = 0
        protected var verticalVertexIndex = 0

        protected val prevPoint = Vec3()
        protected val texCoord2d = Vec3()
        protected val texCoordMatrix = Matrix3()
        protected val modelToTexCoord = Matrix4()
        protected val intermediateLocation = Location()
        protected var texCoord1d = 0.0
        protected var refAlt = 0.0

        protected val tessCoords = DoubleArray(3)
        protected val tessVertices = IntArray(3)
        protected val tessEdgeFlags = BooleanArray(3)
        protected var tessEdgeFlag = true
        protected var tessVertexCount = 0
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

    override fun resetGlobeState(globeState: Globe.State?) {
        super.resetGlobeState(globeState)
        data[globeState]?.let {
            it.refreshVertexArray = true
            it.refreshLineVertexArray = true
        }
    }

    override fun reset() {
        super.reset()
        data.values.forEach { it.refreshVertexArray = true }
    }

    override fun moveTo(globe: Globe, position: Position) {
        val refPos = referencePosition
        val distance = refPos.greatCircleDistance(position)
        val azimuth = refPos.greatCircleAzimuth(position)
        for (boundary in boundaries) for (pos in boundary) pos.greatCircleLocation(azimuth, distance, pos)
    }

    override fun makeDrawable(rc: RenderContext) {
        if (boundaries.isEmpty()) return  // nothing to draw

        if (mustAssembleGeometry(rc)) assembleGeometry(rc)

        // Obtain a drawable form the render context pool.
        val drawable: Drawable
        val drawState: DrawShapeState
        val drawableLines: Drawable
        val drawStateLines: DrawShapeState
        val cameraDistance: Double
        if (isSurfaceShape) {
            val pool = rc.getDrawablePool(DrawableSurfaceShape.KEY)
            drawable = DrawableSurfaceShape.obtain(pool)
            drawState = drawable.drawState
            cameraDistance = cameraDistanceGeographic(rc, currentBoundindData.boundingSector)
            drawable.offset = rc.globe.offset
            drawable.sector.copy(currentBoundindData.boundingSector)
            drawable.version = computeVersion()
            drawable.isDynamic = isDynamic || rc.currentLayer.isDynamic

            drawableLines = DrawableSurfaceShape.obtain(pool)
            drawStateLines = drawableLines.drawState

            drawableLines.offset = rc.globe.offset
            drawableLines.sector.copy(currentBoundindData.boundingSector)
            drawableLines.version = computeVersion()
            drawableLines.isDynamic = isDynamic || rc.currentLayer.isDynamic
        } else {
            val pool = rc.getDrawablePool(DrawableShape.KEY)
            drawable = DrawableShape.obtain(pool)
            drawState = drawable.drawState

            drawableLines = DrawableShape.obtain(pool)
            drawStateLines = drawableLines.drawState

            cameraDistance = cameraDistanceCartesian(
                rc, currentData.vertexArray, currentData.vertexArray.size, VERTEX_STRIDE, currentData.vertexOrigin
            )
        }

        // Use the basic GLSL program to draw the shape.
        drawState.program = rc.getShaderProgram(TriangleShaderProgram.KEY) { TriangleShaderProgram() }

        // Assemble the drawable's OpenGL vertex buffer object.
        drawState.vertexBuffer = rc.getBufferObject(currentData.vertexBufferKey) {
            BufferObject(GL_ARRAY_BUFFER, 0)
        }
        rc.offerGLBufferUpload(currentData.vertexBufferKey, bufferDataVersion) {
            NumericArray.Floats(currentData.vertexArray)
        }

        // Assemble the drawable's OpenGL element buffer object.
        drawState.elementBuffer = rc.getBufferObject(currentData.elementBufferKey) {
            BufferObject(GL_ELEMENT_ARRAY_BUFFER, 0)
        }
        rc.offerGLBufferUpload(currentData.elementBufferKey, bufferDataVersion) {
            val array = IntArray(currentData.topElements.size + currentData.sideElements.size + currentData.baseElements.size)
            var index = 0
            for (element in currentData.topElements) array[index++] = element
            for (element in currentData.sideElements) array[index++] = element
            for (element in currentData.baseElements.asReversed()) array[index++] = element
            NumericArray.Ints(array)
        }

        // Use triangles mode to draw lines
        drawStateLines.isLine = true

        // Use the geom lines GLSL program to draw the shape.
        drawStateLines.program = rc.getShaderProgram(TriangleShaderProgram.KEY) { TriangleShaderProgram() }

        // Assemble the drawable's OpenGL vertex buffer object.
        drawStateLines.vertexBuffer = rc.getBufferObject(currentData.vertexLinesBufferKey) {
            BufferObject(GL_ARRAY_BUFFER, 0)
        }
        rc.offerGLBufferUpload(currentData.vertexLinesBufferKey, bufferDataVersion) {
            NumericArray.Floats(currentData.lineVertexArray)
        }

        // Assemble the drawable's OpenGL element buffer object.
        drawStateLines.elementBuffer = rc.getBufferObject(currentData.elementLinesBufferKey) {
            BufferObject(GL_ELEMENT_ARRAY_BUFFER, 0)
        }
        rc.offerGLBufferUpload(currentData.elementLinesBufferKey, bufferDataVersion) {
            val array = IntArray(currentData.outlineElements.size + currentData.verticalElements.size)
            var index = 0
            for (element in currentData.outlineElements) array[index++] = element
            for (element in currentData.verticalElements) array[index++] = element
            NumericArray.Ints(array)
        }

        drawInterior(rc, drawState, cameraDistance)
        drawOutline(rc, drawStateLines, cameraDistance)

        // Configure the drawable according to the shape's attributes. Disable triangle backface culling when we're
        // displaying a polygon without extruded sides, so we want to draw the top and the bottom.
        drawState.vertexOrigin.copy(currentData.vertexOrigin)
        drawState.vertexStride = VERTEX_STRIDE * 4 // stride in bytes
        drawState.enableCullFace = isExtrude
        drawState.enableDepthTest = activeAttributes.isDepthTest
        drawState.enableDepthWrite = activeAttributes.isDepthWrite

        // Configure the drawable according to the shape's attributes.
        drawStateLines.vertexOrigin.copy(currentData.vertexOrigin)
        drawStateLines.enableCullFace = false
        drawStateLines.enableDepthTest = activeAttributes.isDepthTest
        drawStateLines.enableDepthWrite = activeAttributes.isDepthWrite

        // Enqueue the drawable for processing on the OpenGL thread.
        if (isSurfaceShape|| activeAttributes.interiorColor.alpha >= 1.0) {
            rc.offerSurfaceDrawable(drawable, zOrder)
            rc.offerSurfaceDrawable(drawableLines, zOrder)
        } else {
            rc.offerShapeDrawable(drawableLines, cameraDistance)
            rc.offerShapeDrawable(drawable, cameraDistance)
        }
    }

    protected open fun drawInterior(rc: RenderContext, drawState: DrawShapeState, cameraDistance: Double) {
        if (!activeAttributes.isDrawInterior || rc.isPickMode && !activeAttributes.isPickInterior) return

        // Configure the drawable to use the interior texture when drawing the interior.
        activeAttributes.interiorImageSource?.let { interiorImageSource ->
            rc.getTexture(interiorImageSource, defaultInteriorImageOptions)?.let { texture ->
                drawState.textureLod = computeRepeatingTexCoordTransform(rc, texture, cameraDistance, texCoordMatrix)
                drawState.texture = texture
                drawState.texCoordMatrix.copy(texCoordMatrix)
            }
        } ?: run { drawState.texture = null }

        // Configure the drawable to display the shape's interior top.
        drawState.color.copy(if (rc.isPickMode) pickColor else activeAttributes.interiorColor)
        drawState.opacity = if (rc.isPickMode) 1f else rc.currentLayer.opacity
        drawState.texCoordAttrib.size = 2
        drawState.texCoordAttrib.offset = 12
        drawState.drawElements(GL_TRIANGLES, currentData.topElements.size, GL_UNSIGNED_INT, offset = 0)

        if (isExtrude && !isSurfaceShape) {
            drawState.texture = null

            // Configure the drawable to display the shape's interior sides.
            drawState.drawElements(
                GL_TRIANGLES, currentData.sideElements.size,
                GL_UNSIGNED_INT, offset = currentData.topElements.size * Int.SIZE_BYTES
            )

            // Configure the drawable to display the shape's interior bottom.
            if (baseAltitude != 0.0) {
                drawState.drawElements(
                    GL_TRIANGLES, currentData.baseElements.size,
                    GL_UNSIGNED_INT, offset = (currentData.topElements.size + currentData.sideElements.size) * Int.SIZE_BYTES
                )
            }
        }
    }

    protected open fun drawOutline(rc: RenderContext, drawState: DrawShapeState, cameraDistance: Double) {
        if (!activeAttributes.isDrawOutline || rc.isPickMode && !activeAttributes.isPickOutline) return

        // Configure the drawable to use the outline texture when drawing the outline.
        activeAttributes.outlineImageSource?.let { outlineImageSource ->
            rc.getTexture(outlineImageSource, defaultOutlineImageOptions)?.let { texture ->
                drawState.textureLod = computeRepeatingTexCoordTransform(rc, texture, cameraDistance, texCoordMatrix)
                drawState.texture = texture
                drawState.texCoordMatrix.copy(texCoordMatrix)
            }
        } ?: run { drawState.texture = null }

        // Configure the drawable to display the shape's outline.
        drawState.color.copy(if (rc.isPickMode) pickColor else activeAttributes.outlineColor)
        drawState.opacity = if (rc.isPickMode) 1f else rc.currentLayer.opacity
        drawState.lineWidth = activeAttributes.outlineWidth
        drawState.drawElements(GL_TRIANGLES, currentData.outlineElements.size, GL_UNSIGNED_INT, offset = 0)

        // Configure the drawable to display the shape's extruded verticals.
        if (activeAttributes.isDrawVerticals && isExtrude && !isSurfaceShape) {
            drawState.texture = null
            drawState.drawElements(
                GL_TRIANGLES, currentData.verticalElements.size,
                GL_UNSIGNED_INT, currentData.outlineElements.size * Int.SIZE_BYTES
            )
        }
    }

    protected open fun mustAssembleGeometry(rc: RenderContext): Boolean {
        currentData = data[rc.globeState] ?: PolygonData().also { data[rc.globeState] = it }
        return currentData.refreshVertexArray || isExtrude && !isSurfaceShape && currentData.refreshLineVertexArray
    }

    protected open fun assembleGeometry(rc: RenderContext) {
        // Determine the number of vertexes
        val noIntermediatePoints = maximumIntermediatePoints <= 0 || pathType == LINEAR
        var vertexCount = 0
        var lineVertexCount = 0
        var verticalVertexCount = 0
        for (i in boundaries.indices) {
            val p = boundaries[i]
            if (p.isEmpty()) continue
            val lastEqualsFirst = p[0] == p[p.size - 1]
            if (noIntermediatePoints) {
                vertexCount += p.size
                lineVertexCount += p.size + if (lastEqualsFirst) 2 else 3
                verticalVertexCount += p.size
            } else if (lastEqualsFirst) {
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
        currentData.vertexArray = if (isExtrude && !isSurfaceShape) FloatArray(vertexCount * 2 * VERTEX_STRIDE)
        else if (!isSurfaceShape) FloatArray(vertexCount * VERTEX_STRIDE)
        else FloatArray((vertexCount + boundaries.size) * VERTEX_STRIDE) // Reserve boundaries.size for combined vertexes
        currentData.topElements.clear()
        currentData.sideElements.clear()
        currentData.baseElements.clear()
        lineVertexIndex = 0
        verticalVertexIndex = lineVertexCount * OUTLINE_LINE_SEGMENT_STRIDE
        currentData.lineVertexArray = if (isExtrude && !isSurfaceShape) {
            FloatArray(lineVertexCount * OUTLINE_LINE_SEGMENT_STRIDE + verticalVertexCount * VERTICAL_LINE_SEGMENT_STRIDE)
        } else {
            FloatArray(lineVertexCount * OUTLINE_LINE_SEGMENT_STRIDE)
        }
        currentData.outlineElements.clear()
        currentData.verticalElements.clear()

        // Get reference point altitude
        refAlt = if (isPlain) {
            val refPos = referencePosition
            rc.globe.getElevation(refPos.latitude, refPos.longitude)
        } else 0.0

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

            // Add the boundary's first vertex. Add additional dummy vertex with the same data before the first vertex.
            val pos0 = positions[0]
            var begin = pos0
            var beginAltitude = begin.altitude + refAlt
            calcPoint(rc, begin.latitude, begin.longitude, beginAltitude, isAbsolute = isPlain)
            addVertex(rc, begin.latitude, begin.longitude, beginAltitude, type = VERTEX_ORIGINAL)
            addLineVertex(rc, begin.latitude, begin.longitude, beginAltitude, isIntermediate = true, addIndices = true)
            addLineVertex(rc, begin.latitude, begin.longitude, beginAltitude, isIntermediate = false, addIndices = true)

            // Add the remaining boundary vertices, tessellating each edge as indicated by the polygon's properties.
            for (idx in 1 until positions.size) {
                val end = positions[idx]
                val addIndices = idx != positions.size - 1 || end != pos0 // check if there is implicit closing edge
                addIntermediateVertices(rc, begin, end)
                val endAltitude = end.altitude + refAlt
                calcPoint(rc, end.latitude, end.longitude, endAltitude, isAbsolute = isPlain)
                addVertex(rc, end.latitude, end.longitude, endAltitude, type = VERTEX_ORIGINAL)
                addLineVertex(rc, end.latitude, end.longitude, endAltitude, isIntermediate = false, addIndices)
                begin = end
                beginAltitude = endAltitude
            }

            // Tessellate the implicit closing edge if the boundary is not already closed.
            if (begin != pos0) {
                addIntermediateVertices(rc, begin, pos0)
                // Add additional dummy vertex with the same data after the last vertex.
                val pos0Altitude = pos0.altitude + refAlt
                calcPoint(rc, pos0.latitude, pos0.longitude, pos0Altitude, isAbsolute = isPlain, isExtrudedSkirt = false)
                addLineVertex(rc, pos0.latitude, pos0.longitude, pos0Altitude, isIntermediate = true, addIndices = false)
                addLineVertex(rc, pos0.latitude, pos0.longitude, pos0Altitude, isIntermediate = true, addIndices = false)
            } else {
                calcPoint(rc, begin.latitude, begin.longitude, beginAltitude, isAbsolute = isPlain, isExtrudedSkirt = false)
                addLineVertex(rc, begin.latitude, begin.longitude, beginAltitude, isIntermediate = true, addIndices = false)
            }
            // Drop last six indices as they are used for connecting segments and there's no next segment for last vertices (check addLineVertex)
            currentData.outlineElements.subList(currentData.outlineElements.size - 6, currentData.outlineElements.size).clear()

            GLU.gluTessEndContour(tess)
        }
        GLU.gluTessEndPolygon(tess)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_COMBINE_DATA, null)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_VERTEX_DATA, null)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_EDGE_FLAG_DATA, null)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_ERROR_DATA, null)

        // Reset update flags
        currentData.refreshVertexArray = false
        currentData.refreshLineVertexArray = false

        // Compute the shape's bounding box or bounding sector from its assembled coordinates.
        with(currentBoundindData) {
            if (isSurfaceShape) {
                boundingSector.setEmpty()
                boundingSector.union(currentData.vertexArray, vertexIndex, VERTEX_STRIDE)
                boundingSector.translate(currentData.vertexOrigin.y, currentData.vertexOrigin.x)
                boundingBox.setToUnitBox() // Surface/geographic shape bounding box is unused
            } else {
                boundingBox.setToPoints(currentData.vertexArray, vertexIndex, VERTEX_STRIDE)
                boundingBox.translate(currentData.vertexOrigin.x, currentData.vertexOrigin.y, currentData.vertexOrigin.z)
                boundingSector.setEmpty() // Cartesian shape bounding sector is unused
            }
        }

        // Adjust final vertex array size to save memory (and fix cameraDistanceCartesian calculation)
        currentData.vertexArray = currentData.vertexArray.copyOf(vertexIndex)
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
        var alt = begin.altitude + deltaAlt + refAlt
        for (idx in 1 until numSubsegments) {
            val loc = intermediateLocation
            when (pathType) {
                GREAT_CIRCLE -> begin.greatCircleLocation(azimuth, dist, loc)
                RHUMB_LINE -> begin.rhumbLocation(azimuth, dist, loc)
                else -> {}
            }
            calcPoint(rc, loc.latitude, loc.longitude, alt, isAbsolute = isPlain)
            addVertex(rc, loc.latitude, loc.longitude, alt, type = VERTEX_INTERMEDIATE)
            addLineVertex(rc, loc.latitude, loc.longitude, alt, isIntermediate = true, addIndices = true)
            dist += deltaDist
            alt += deltaAlt
        }
    }

    protected open fun addVertex(
        rc: RenderContext, latitude: Angle, longitude: Angle, altitude: Double, type: Int
    ): Int = with(currentData) {
        val vertex = vertexIndex / VERTEX_STRIDE
        val texCoord2d = texCoord2d.copy(point).multiplyByMatrix(modelToTexCoord)
        if (type != VERTEX_COMBINED) {
            tessCoords[0] = longitude.inDegrees
            tessCoords[1] = latitude.inDegrees
            tessCoords[2] = altitude
            GLU.gluTessVertex(rc.tessellator, tessCoords, coords_offset = 0, vertex)
        }
        if (vertex == 0) {
            if (isSurfaceShape) vertexOrigin.set(longitude.inDegrees, latitude.inDegrees, altitude)
            else vertexOrigin.copy(point)
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
                vertexArray[vertexIndex++] = (vertPoint.x - vertexOrigin.x).toFloat()
                vertexArray[vertexIndex++] = (vertPoint.y - vertexOrigin.y).toFloat()
                vertexArray[vertexIndex++] = (vertPoint.z - vertexOrigin.z).toFloat()
                vertexArray[vertexIndex++] = 0f // unused
                vertexArray[vertexIndex++] = 0f // unused
            }
        }
        vertex
    }

    protected open fun addLineVertex(
        rc: RenderContext, latitude: Angle, longitude: Angle, altitude: Double, isIntermediate : Boolean, addIndices : Boolean
    ) = with(currentData) {
        val vertex = lineVertexIndex / VERTEX_STRIDE
        if (lineVertexIndex == 0) texCoord1d = 0.0 else texCoord1d += point.distanceTo(prevPoint)
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
                rc.geographicToCartesian(positions[j], AltitudeMode.ABSOLUTE, point)
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

    protected open fun tessCombine(
        rc: RenderContext, coords: DoubleArray, data: Array<Any?>, weight: FloatArray, outData: Array<Any?>
    ) {
        ensureVertexArrayCapacity() // Increment array size to fit combined vertexes
        val latitude = coords[1].degrees
        val longitude = coords[0].degrees
        val altitude = coords[2]
        calcPoint(rc, latitude, longitude, altitude, isAbsolute = isPlain)
        outData[0] = addVertex(rc, latitude, longitude, altitude, type = VERTEX_COMBINED)
    }

    protected open fun tessVertex(rc: RenderContext, vertexData: Any?) = with(currentData) {
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
        if (baseAltitude != 0.0 && isExtrude && !isSurfaceShape) {
            baseElements.add(v0.inc())
            baseElements.add(v1.inc())
            baseElements.add(v2.inc())
        }
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
        val size = currentData.vertexArray.size
        if (size == vertexIndex) {
            val increment = (size shr 1).coerceAtLeast(12)
            val newArray = FloatArray(size + increment)
            currentData.vertexArray.copyInto(newArray)
            currentData.vertexArray = newArray
        }
    }
}