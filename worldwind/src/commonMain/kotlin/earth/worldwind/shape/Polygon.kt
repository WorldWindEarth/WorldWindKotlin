package earth.worldwind.shape

import earth.worldwind.draw.DrawShapeState
import earth.worldwind.draw.Drawable
import earth.worldwind.draw.DrawableShape
import earth.worldwind.draw.DrawableSurfaceShape
import earth.worldwind.geom.*
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.render.*
import earth.worldwind.render.buffer.FloatBufferObject
import earth.worldwind.render.buffer.ShortBufferObject
import earth.worldwind.render.image.ImageOptions
import earth.worldwind.render.image.ResamplingMode
import earth.worldwind.render.image.WrapMode
import earth.worldwind.render.program.BasicShaderProgram
import earth.worldwind.shape.PathType.*
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.glu.GLU
import earth.worldwind.util.glu.GLUtessellatorCallbackAdapter
import earth.worldwind.util.kgl.*
import kotlin.jvm.JvmOverloads

open class Polygon @JvmOverloads constructor(
    positions: List<Position> = listOf(), attributes: ShapeAttributes = ShapeAttributes()
): AbstractShape(attributes) {
    protected val boundaries = mutableListOf(positions)
    val boundaryCount get() = boundaries.size
    var isExtrude = false
        set(value) {
            field = value
            reset()
        }
    var followTerrain = false
        set(value) {
            field = value
            reset()
        }
    protected var vertexArray = FloatArray(0)
    protected var vertexIndex = 0
    // TODO Use ShortArray instead of mutableListOf<Short> to avoid unnecessary memory re-allocations
    protected val topElements = mutableListOf<Short>()
    protected val sideElements = mutableListOf<Short>()
    protected val outlineElements = mutableListOf<Short>()
    protected val verticalElements = mutableListOf<Short>()
    protected var vertexBufferKey = nextCacheKey()
    protected var elementBufferKey = nextCacheKey()
    protected val vertexOrigin = Vec3()
    protected var isSurfaceShape = false
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
        protected const val VERTEX_STRIDE = 6
        protected val defaultInteriorImageOptions = ImageOptions().apply { wrapMode = WrapMode.REPEAT }
        protected val defaultOutlineImageOptions = ImageOptions().apply {
            wrapMode = WrapMode.REPEAT
            resamplingMode = ResamplingMode.NEAREST_NEIGHBOR
        }
        protected const val VERTEX_ORIGINAL = 0
        protected const val VERTEX_INTERMEDIATE = 1
        protected const val VERTEX_COMBINED = 2
        protected fun nextCacheKey() = Any()
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
        vertexArray = FloatArray(0)
        topElements.clear()
        sideElements.clear()
        outlineElements.clear()
        verticalElements.clear()
    }

    override fun makeDrawable(rc: RenderContext) {
        if (boundaries.isEmpty()) return  // nothing to draw

        if (mustAssembleGeometry(rc)) {
            assembleGeometry(rc)
            vertexBufferKey = nextCacheKey()
            elementBufferKey = nextCacheKey()
        }

        // Obtain a drawable form the render context pool.
        val drawable: Drawable
        val drawState: DrawShapeState
        if (isSurfaceShape) {
            val pool = rc.getDrawablePool<DrawableSurfaceShape>()
            drawable = DrawableSurfaceShape.obtain(pool)
            drawState = drawable.drawState
            cameraDistance = cameraDistanceGeographic(rc, boundingSector)
            drawable.sector.copy(boundingSector)
        } else {
            val pool = rc.getDrawablePool<DrawableShape>()
            drawable = DrawableShape.obtain(pool)
            drawState = drawable.drawState
            cameraDistance = cameraDistanceCartesian(rc, vertexArray, vertexIndex, VERTEX_STRIDE, vertexOrigin)
        }

        // Use the basic GLSL program to draw the shape.
        drawState.program = rc.getShaderProgram { BasicShaderProgram() }

        // Assemble the drawable's OpenGL vertex buffer object.
        drawState.vertexBuffer = rc.getBufferObject(vertexBufferKey) {
            FloatBufferObject(GL_ARRAY_BUFFER, vertexArray, vertexIndex)
        }

        // Assemble the drawable's OpenGL element buffer object.
        drawState.elementBuffer = rc.getBufferObject(elementBufferKey) {
            ShortBufferObject(
                GL_ELEMENT_ARRAY_BUFFER, (topElements + sideElements + outlineElements + verticalElements).toShortArray()
            )
        }
        if (isSurfaceShape || activeAttributes.interiorColor.alpha >= 1.0) {
            drawInterior(rc, drawState)
            drawOutline(rc, drawState)
        } else {
            drawOutline(rc, drawState)
            drawInterior(rc, drawState)
        }

        // Configure the drawable according to the shape's attributes. Disable triangle backface culling when we're
        // displaying a polygon without extruded sides, so we want to draw the top and the bottom.
        drawState.vertexOrigin.copy(vertexOrigin)
        drawState.vertexStride = VERTEX_STRIDE * 4 // stride in bytes
        drawState.enableCullFace = isExtrude
        drawState.enableDepthTest = activeAttributes.isDepthTest

        // Enqueue the drawable for processing on the OpenGL thread.
        if (isSurfaceShape) rc.offerSurfaceDrawable(drawable, 0.0 /*zOrder*/)
        else rc.offerShapeDrawable(drawable, cameraDistance)
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
        drawState.texCoordAttrib(2 /*size*/, 12 /*offset in bytes*/)
        drawState.drawElements(GL_TRIANGLES, topElements.size, GL_UNSIGNED_SHORT, 0 /*offset*/)

        // Configure the drawable to display the shape's interior sides.
        if (isExtrude) {
            drawState.texture(null)
            drawState.drawElements(GL_TRIANGLES, sideElements.size, GL_UNSIGNED_SHORT, topElements.size * 2 /*offset*/)
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
        drawState.lineWidth(activeAttributes.outlineWidth)
        drawState.texCoordAttrib(1 /*size*/, 20 /*offset in bytes*/)
        drawState.drawElements(
            GL_LINES, outlineElements.size,
            GL_UNSIGNED_SHORT, topElements.size * 2 + sideElements.size * 2 /*offset*/
        )

        // Configure the drawable to display the shape's extruded verticals.
        if (activeAttributes.isDrawVerticals && isExtrude) {
            drawState.color(if (rc.isPickMode) pickColor else activeAttributes.outlineColor)
            drawState.lineWidth(activeAttributes.outlineWidth)
            drawState.texture(null)
            drawState.drawElements(
                GL_LINES, verticalElements.size,
                GL_UNSIGNED_SHORT, topElements.size * 2 + sideElements.size * 2 + outlineElements.size * 2 /*offset*/
            )
        }
    }

    protected open fun mustAssembleGeometry(rc: RenderContext) = vertexArray.isEmpty()

    protected open fun assembleGeometry(rc: RenderContext) {
        // Determine whether the shape geometry must be assembled as Cartesian geometry or as geographic geometry.
        isSurfaceShape = altitudeMode == AltitudeMode.CLAMP_TO_GROUND && followTerrain

        // Determine the number of vertexes
        val noIntermediatePoints = isSurfaceShape || maximumIntermediatePoints <= 0 || pathType == LINEAR
        val vertexCount = boundaries.sumOf { p ->
            if (noIntermediatePoints) p.size
            else if (p.isNotEmpty() && p[0] == p[p.size - 1]) p.size + (p.size - 1) * maximumIntermediatePoints
            else p.size + p.size * maximumIntermediatePoints
        }

        // Clear the shape's vertex array and element arrays. These arrays will accumulate values as the shapes's
        // geometry is assembled.
        vertexIndex = 0
        vertexArray = if (isExtrude && !isSurfaceShape) FloatArray(vertexCount * 2 * VERTEX_STRIDE)
        else if (!isSurfaceShape) FloatArray(vertexCount * VERTEX_STRIDE)
        else FloatArray((vertexCount + boundaries.size) * VERTEX_STRIDE) // Reserve boundaries.size for combined vertexes
        topElements.clear()
        sideElements.clear()
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
        for (positions in boundaries) {
            if (positions.isEmpty()) continue  // no boundary positions to assemble
            GLU.gluTessBeginContour(tess)

            // Add the boundary's first vertex.
            var begin = positions[0]
            addVertex(rc, begin.latitude, begin.longitude, begin.altitude, VERTEX_ORIGINAL /*type*/)

            // Add the remaining boundary vertices, tessellating each edge as indicated by the polygon's properties.
            for (idx in 1 until positions.size) {
                val end = positions[idx]
                addIntermediateVertices(rc, begin, end)
                addVertex(rc, end.latitude, end.longitude, end.altitude, VERTEX_ORIGINAL /*type*/)
                begin = end
            }

            // Tessellate the implicit closing edge if the boundary is not already closed.
            if (begin != positions[0]) addIntermediateVertices(rc, begin, positions[0])
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
        if (isSurfaceShape || maximumIntermediatePoints <= 0) return  // suppress intermediate vertices when configured to do so
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
            dist += deltaDist
            alt += deltaAlt
        }
    }

    protected open fun addVertex(rc: RenderContext, latitude: Angle, longitude: Angle, altitude: Double, type: Int): Int {
        val vertex = vertexIndex / VERTEX_STRIDE
        var point = rc.geographicToCartesian(latitude, longitude, altitude, altitudeMode, point)
        val texCoord2d = texCoord2d.copy(point).multiplyByMatrix(modelToTexCoord)
        if (type != VERTEX_COMBINED) {
            tessCoords[0] = longitude.degrees
            tessCoords[1] = latitude.degrees
            tessCoords[2] = altitude
            GLU.gluTessVertex(rc.tessellator, tessCoords, 0 /*coords_offset*/, vertex)
        }
        if (vertex == 0) {
            if (isSurfaceShape) vertexOrigin.set(longitude.degrees, latitude.degrees, altitude) else vertexOrigin.copy(point)
            texCoord1d = 0.0
        } else {
            texCoord1d += point.distanceTo(prevPoint)
        }
        prevPoint.copy(point)
        if (isSurfaceShape) {
            vertexArray[vertexIndex++] = (longitude.degrees - vertexOrigin.x).toFloat()
            vertexArray[vertexIndex++] = (latitude.degrees - vertexOrigin.y).toFloat()
            vertexArray[vertexIndex++] = (altitude - vertexOrigin.z).toFloat()
            vertexArray[vertexIndex++] = texCoord2d.x.toFloat()
            vertexArray[vertexIndex++] = texCoord2d.y.toFloat()
            vertexArray[vertexIndex++] = texCoord1d.toFloat()
        } else {
            vertexArray[vertexIndex++] = (point.x - vertexOrigin.x).toFloat()
            vertexArray[vertexIndex++] = (point.y - vertexOrigin.y).toFloat()
            vertexArray[vertexIndex++] = (point.z - vertexOrigin.z).toFloat()
            vertexArray[vertexIndex++] = texCoord2d.x.toFloat()
            vertexArray[vertexIndex++] = texCoord2d.y.toFloat()
            vertexArray[vertexIndex++] = texCoord1d.toFloat()
            if (isExtrude) {
                point = rc.geographicToCartesian(latitude, longitude, 0.0, AltitudeMode.CLAMP_TO_GROUND, this.point)
                vertexArray[vertexIndex++] = (point.x - vertexOrigin.x).toFloat()
                vertexArray[vertexIndex++] = (point.y - vertexOrigin.y).toFloat()
                vertexArray[vertexIndex++] = (point.z - vertexOrigin.z).toFloat()
                vertexArray[vertexIndex++] = 0f /*unused*/
                vertexArray[vertexIndex++] = 0f /*unused*/
                vertexArray[vertexIndex++] = 0f /*unused*/
            }
            if (isExtrude && type == VERTEX_ORIGINAL) {
                verticalElements.add(vertex.toShort())
                verticalElements.add(vertex.inc().toShort())
            }
        }
        return vertex
    }

    protected open fun determineModelToTexCoord(rc: RenderContext) {
        var mx = 0.0
        var my = 0.0
        var mz = 0.0
        var numPoints = 0.0
        for (positions in boundaries) {
            if (positions.isEmpty()) continue  // no boundary positions
            for (pos in positions) {
                val point = rc.geographicToCartesian(
                    pos.latitude, pos.longitude, pos.altitude, AltitudeMode.ABSOLUTE, point
                )
                mx += point.x
                my += point.y
                mz += point.z
                numPoints++
            }
        }
        mx /= numPoints
        my /= numPoints
        mz /= numPoints
        rc.globe!!.cartesianToLocalTransform(mx, my, mz, modelToTexCoord)
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
        val v0 = tessVertices[0].toShort()
        val v1 = tessVertices[1].toShort()
        val v2 = tessVertices[2].toShort()
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
        if (tessEdgeFlags[0]) {
            outlineElements.add(v0)
            outlineElements.add(v1)
        }
        if (tessEdgeFlags[1]) {
            outlineElements.add(v1)
            outlineElements.add(v2)
        }
        if (tessEdgeFlags[2]) {
            outlineElements.add(v2)
            outlineElements.add(v0)
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