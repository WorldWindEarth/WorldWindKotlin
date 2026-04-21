package earth.worldwind.shape

import earth.worldwind.draw.DrawShapeState
import earth.worldwind.draw.Drawable
import earth.worldwind.draw.DrawableShape
import earth.worldwind.draw.DrawableSurfaceShape
import earth.worldwind.geom.*
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Angle.Companion.radians
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import earth.worldwind.globe.Globe
import earth.worldwind.render.RenderContext
import earth.worldwind.render.buffer.BufferObject
import earth.worldwind.render.program.TriangleShaderProgram
import earth.worldwind.shape.PathType.*
import earth.worldwind.util.*
import earth.worldwind.util.Intersection
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.logMessage
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
        // Cartesian centroid: average of vertex unit vectors, projected back to the sphere. Unlike
        // a lat/lon min/max midpoint this is rotation-equivariant — if the polygon is rotated,
        // the centroid rotates with it — which keeps drag (SphericalRotation in moveTo) stable
        // across successive events. Also handles antimeridian crossing without special-casing
        // and converges to the actual pole for polygons symmetric about it.
        var sx = 0.0; var sy = 0.0; var sz = 0.0
        for (boundary in boundaries) for (pos in boundary) {
            val latRad = pos.latitude.inRadians
            val lonRad = pos.longitude.inRadians
            sx += cos(latRad) * cos(lonRad)
            sy += cos(latRad) * sin(lonRad)
            sz += sin(latRad)
        }
        val mag = sqrt(sx * sx + sy * sy + sz * sz)
        if (mag < 1.0e-10) return Position()
        val nx = sx / mag; val ny = sy / mag; val nz = sz / mag
        return Position(asin(nz.coerceIn(-1.0, 1.0)).radians, atan2(ny, nx).radians, 0.0)
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
        val outlineChainStarts = mutableListOf<Int>()
        val verticalElements = mutableListOf<Int>()
        val vertexBufferKey = Any()
        val elementBufferKey = Any()
        val vertexLinesBufferKey = Any()
        val elementLinesBufferKey = Any()
        var refreshVertexArray = true
        var refreshLineVertexArray = true
        var refreshTopology = true
        var geographicVertexArray = FloatArray(0)
        var geographicVertexCount = 0
        var savedRefAlt = 0.0
        var verticalVertexArrayOffset = 0
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
        protected val modelToTexCoord = Matrix4()
        protected val intermediateLocation = Location()
        protected var texCoord1d = 0.0

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
        data.values.forEach {
            it.refreshVertexArray = true
            it.refreshTopology = true
        }
    }

    override fun moveTo(globe: Globe, position: Position) {
        val rotation = SphericalRotation(referencePosition, position)
        for (boundary in boundaries) for (pos in boundary) rotation.apply(pos)
        reset()
    }

    override fun makeDrawable(rc: RenderContext) {
        if (boundaries.isEmpty()) return  // nothing to draw

        if (mustAssembleGeometry(rc)) {
            if (!rc.canAssembleGeometry()) return
            assembleGeometry(rc)
        }

        // Obtain a drawable form the render context pool.
        val drawable: Drawable
        val drawState: DrawShapeState
        val drawableLines: Drawable
        val drawStateLines: DrawShapeState
        val cameraDistance: Double
        val cameraDistanceSq: Double
        if (isSurfaceShape) {
            val pool = rc.getDrawablePool(DrawableSurfaceShape.KEY)
            drawable = DrawableSurfaceShape.obtain(pool)
            drawState = drawable.drawState
            cameraDistanceSq = 0.0 // Not used by surface shape
            cameraDistance = cameraDistanceForTexture(rc, currentBoundindData.boundingSector)
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

            cameraDistanceSq = cameraDistanceSquared(
                rc, currentData.vertexArray, currentData.vertexArray.size, VERTEX_STRIDE, currentData.vertexOrigin
            )
            cameraDistance = sqrtCameraDistanceForTexture(cameraDistanceSq)
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
        drawState.enableLighting = activeAttributes.isLightingEnabled

        // Configure the drawable according to the shape's attributes.
        drawStateLines.vertexOrigin.copy(currentData.vertexOrigin)
        drawStateLines.enableCullFace = false
        drawStateLines.enableDepthTest = activeAttributes.isDepthTest
        drawStateLines.enableDepthWrite = activeAttributes.isDepthWrite
        drawStateLines.enableLighting = activeAttributes.isLightingEnabled

        // Enqueue the drawable for processing on the OpenGL thread.
        if (isSurfaceShape || activeAttributes.interiorColor.alpha >= 1.0) {
            rc.offerSurfaceDrawable(drawable, zOrder)
            rc.offerSurfaceDrawable(drawableLines, zOrder)
            // For antimeridian-crossing shapes, enqueue additional drawables covering the other half.
            if (isSurfaceShape && currentBoundindData.crossesAntimeridian) {
                val pool = rc.getDrawablePool(DrawableSurfaceShape.KEY)
                val version = computeVersion()
                val dynamic = isDynamic || rc.currentLayer.isDynamic
                DrawableSurfaceShape.obtain(pool).also { d ->
                    d.offset = rc.globe.offset
                    d.sector.copy(currentBoundindData.additionalSector)
                    d.version = version; d.isDynamic = dynamic
                    d.drawState.copy(drawState)
                    rc.offerSurfaceDrawable(d, zOrder)
                }
                DrawableSurfaceShape.obtain(pool).also { d ->
                    d.offset = rc.globe.offset
                    d.sector.copy(currentBoundindData.additionalSector)
                    d.version = version; d.isDynamic = dynamic
                    d.drawState.copy(drawStateLines)
                    rc.offerSurfaceDrawable(d, zOrder)
                }
            }
        } else {
            rc.offerShapeDrawable(drawableLines, cameraDistanceSq)
            rc.offerShapeDrawable(drawable, cameraDistanceSq)
        }
    }

    protected open fun drawInterior(rc: RenderContext, drawState: DrawShapeState, cameraDistance: Double) {
        if (!activeAttributes.isDrawInterior || rc.isPickMode && !activeAttributes.isPickInterior) return

        // Configure the drawable to use the interior texture when drawing the interior.
        activeAttributes.interiorImageSource?.let { interiorImageSource ->
            rc.getTexture(interiorImageSource, defaultInteriorImageOptions)?.let { texture ->
                drawState.texture = texture
                drawState.textureLod = computeRepeatingTexCoordTransform(rc, texture, cameraDistance, drawState.texCoordMatrix)
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
                drawState.texture = texture
                drawState.textureLod = computeRepeatingTexCoordTransform(rc, texture, cameraDistance, drawState.texCoordMatrix)
            }
        } ?: run { drawState.texture = null }

        // Configure the drawable to display the shape's outline.
        drawState.color.copy(if (rc.isPickMode) pickColor else activeAttributes.outlineColor)
        drawState.opacity = if (rc.isPickMode) 1f else rc.currentLayer.opacity
        drawState.lineWidth = activeAttributes.outlineWidth
        val chainStarts = currentData.outlineChainStarts
        for (ci in chainStarts.indices) {
            val start = chainStarts[ci]
            val end = if (ci + 1 < chainStarts.size) chainStarts[ci + 1] else currentData.outlineElements.size
            val count = end - start
            if (count > 0) drawState.drawElements(GL_TRIANGLES, count, GL_UNSIGNED_INT, start * Int.SIZE_BYTES)
        }

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
        if (currentData.refreshTopology) {
            assembleGeometryFull(rc)
            currentData.refreshTopology = false
        } else {
            assembleGeometryPositions(rc)
        }
        currentData.refreshVertexArray = false
        currentData.refreshLineVertexArray = false
    }

    protected open fun assembleGeometryFull(rc: RenderContext) {
        // For surface shapes, split boundaries at the antimeridian and handle pole inclusion.
        val splitContours: List<ContourInfo>?
        val splitCrossesAntimeridian = if (isSurfaceShape && boundaries.isNotEmpty()) {
            val (crosses, contours) = PolygonSplitter.splitContours(boundaries)
            splitContours = contours
            crosses
        } else {
            splitContours = null
            false
        }

        // Determine the number of vertices; account for extra intersection/pole points when split.
        val noIntermediatePoints = maximumIntermediatePoints <= 0 || pathType == LINEAR
        var vertexCount = 0
        var lineVertexCount = 0
        var verticalVertexCount = 0
        if (splitContours != null) {
            for (info in splitContours) for (poly in info.polygons) {
                val n = poly.size
                lineVertexCount += n + 3
                vertexCount += if (noIntermediatePoints) n else n + n * maximumIntermediatePoints
                verticalVertexCount += n
            }
        } else {
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
        }

        // Clear the shape's vertex array and element arrays.
        vertexIndex = 0
        currentData.vertexArray = if (isExtrude && !isSurfaceShape) FloatArray(vertexCount * 2 * VERTEX_STRIDE)
        else if (!isSurfaceShape) FloatArray(vertexCount * VERTEX_STRIDE)
        else FloatArray((vertexCount + boundaries.size) * VERTEX_STRIDE)
        currentData.geographicVertexArray = FloatArray(vertexCount * 3 + boundaries.size * 3)
        currentData.geographicVertexCount = 0
        currentData.topElements.clear()
        currentData.sideElements.clear()
        currentData.baseElements.clear()
        lineVertexIndex = 0
        verticalVertexIndex = lineVertexCount * OUTLINE_LINE_SEGMENT_STRIDE
        currentData.verticalVertexArrayOffset = verticalVertexIndex
        currentData.lineVertexArray = if (isExtrude && !isSurfaceShape) {
            FloatArray(lineVertexCount * OUTLINE_LINE_SEGMENT_STRIDE + verticalVertexCount * VERTICAL_LINE_SEGMENT_STRIDE)
        } else {
            FloatArray(lineVertexCount * OUTLINE_LINE_SEGMENT_STRIDE)
        }
        currentData.outlineElements.clear()
        currentData.outlineChainStarts.clear()
        currentData.verticalElements.clear()

        // Get reference point altitude
        currentData.savedRefAlt = if (isPlain) {
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
        if (splitContours != null) {
            // Surface shape: use split sub-polygons as tessellator contours.
            for (info in splitContours) {
                for ((polyIdx, polygon) in info.polygons.withIndex()) {
                    if (polygon.isEmpty()) continue
                    val iMap = info.iMaps[polyIdx]
                    val isPolePolygon = polyIdx == info.poleIndex

                    GLU.gluTessBeginContour(tess)

                    val savedRefAlt = currentData.savedRefAlt
                    for (loc in polygon) {
                        val alt = (if (loc is Position) loc.altitude else 0.0) + savedRefAlt
                        calcPoint(rc, loc.latitude, loc.longitude, alt, isAbsolute = isPlain)
                        addVertex(rc, loc.latitude, loc.longitude, alt, type = VERTEX_ORIGINAL)
                    }

                    addOutlineChains(rc, polygon, iMap, isPolePolygon, info.pole)

                    GLU.gluTessEndContour(tess)
                }
            }
        } else {
            // Non-surface or no-split: original logic.
            for (i in boundaries.indices) {
                val positions = boundaries[i]
                if (positions.isEmpty()) continue

                GLU.gluTessBeginContour(tess)

                val pos0 = positions[0]
                var begin = pos0
                var beginAltitude = begin.altitude + currentData.savedRefAlt
                calcPoint(rc, begin.latitude, begin.longitude, beginAltitude, isAbsolute = isPlain)
                addVertex(rc, begin.latitude, begin.longitude, beginAltitude, type = VERTEX_ORIGINAL)
                currentData.outlineChainStarts.add(currentData.outlineElements.size)
                addLineVertex(rc, begin.latitude, begin.longitude, beginAltitude, isIntermediate = true, addIndices = true)
                addLineVertex(rc, begin.latitude, begin.longitude, beginAltitude, isIntermediate = false, addIndices = true)

                for (idx in 1 until positions.size) {
                    val end = positions[idx]
                    val addIndices = idx != positions.size - 1 || end != pos0
                    addIntermediateVertices(rc, begin, end)
                    val endAltitude = end.altitude + currentData.savedRefAlt
                    calcPoint(rc, end.latitude, end.longitude, endAltitude, isAbsolute = isPlain)
                    addVertex(rc, end.latitude, end.longitude, endAltitude, type = VERTEX_ORIGINAL)
                    addLineVertex(rc, end.latitude, end.longitude, endAltitude, isIntermediate = false, addIndices)
                    begin = end
                    beginAltitude = endAltitude
                }

                if (begin != pos0) {
                    addIntermediateVertices(rc, begin, pos0)
                    val pos0Altitude = pos0.altitude + currentData.savedRefAlt
                    calcPoint(rc, pos0.latitude, pos0.longitude, pos0Altitude, isAbsolute = isPlain, isExtrudedSkirt = false)
                    addLineVertex(rc, pos0.latitude, pos0.longitude, pos0Altitude, isIntermediate = true, addIndices = false)
                    addLineVertex(rc, pos0.latitude, pos0.longitude, pos0Altitude, isIntermediate = true, addIndices = false)
                } else {
                    calcPoint(rc, begin.latitude, begin.longitude, beginAltitude, isAbsolute = isPlain, isExtrudedSkirt = false)
                    addLineVertex(rc, begin.latitude, begin.longitude, beginAltitude, isIntermediate = true, addIndices = false)
                }
                currentData.outlineElements.subList(currentData.outlineElements.size - 6, currentData.outlineElements.size).clear()

                GLU.gluTessEndContour(tess)
            }
        }

        GLU.gluTessEndPolygon(tess)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_COMBINE_DATA, null)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_VERTEX_DATA, null)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_EDGE_FLAG_DATA, null)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_ERROR_DATA, null)

        // Compute the shape's bounding box or bounding sector.
        with(currentBoundindData) {
            if (isSurfaceShape) {
                if (splitCrossesAntimeridian) {
                    computeAntimeridianSectors(
                        currentData.vertexArray, vertexIndex, VERTEX_STRIDE, currentData.vertexOrigin
                    )
                } else {
                    crossesAntimeridian = false
                    boundingSector.setEmpty()
                    boundingSector.union(currentData.vertexArray, vertexIndex, VERTEX_STRIDE)
                    boundingSector.translate(currentData.vertexOrigin.y, currentData.vertexOrigin.x)
                }
                boundingBox.setToUnitBox()
            } else {
                boundingBox.setToPoints(currentData.vertexArray, vertexIndex, VERTEX_STRIDE)
                boundingBox.translate(currentData.vertexOrigin.x, currentData.vertexOrigin.y, currentData.vertexOrigin.z)
                boundingSector.setEmpty()
                crossesAntimeridian = false
            }
        }

        currentData.vertexArray = currentData.vertexArray.copyOf(vertexIndex)
        currentData.geographicVertexArray = currentData.geographicVertexArray.copyOf(currentData.geographicVertexCount * 3)
    }

    /**
     * Builds outline chain(s) for a split sub-polygon. For pole polygons, skips the pole-connecting edges.
     * For regular split polygons, emits each point as a single continuous chain.
     */
    protected open fun addOutlineChains(
        rc: RenderContext, polygon: List<Location>, iMap: Map<Int, Intersection>,
        isPolePolygon: Boolean, pole: Pole
    ) {
        val savedRefAlt = currentData.savedRefAlt

        fun altOf(loc: Location) = (if (loc is Position) loc.altitude else 0.0) + savedRefAlt

        if (isPolePolygon && pole != Pole.NONE && !rc.globe.is2D) {
            // 3D sphere: split the outline at the pole-intersection points so the outline doesn't
            // run through the polygon's interior up to the pole. pole_A and pole_B coincide as a
            // single point in 3D, so including them would draw a visible spoke inside the fill.
            var pCount = 0
            var chainStarted = false
            for (k in polygon.indices) {
                val loc = polygon[k]
                val intersection = iMap[k]
                if (intersection != null && intersection.forPole) {
                    pCount++
                    if (pCount % 2 == 1) {
                        // End the current chain at this pole point, then close it.
                        val alt = altOf(loc)
                        calcPoint(rc, loc.latitude, loc.longitude, alt, isAbsolute = isPlain, isExtrudedSkirt = false)
                        if (chainStarted) {
                            addLineVertex(rc, loc.latitude, loc.longitude, alt, isIntermediate = false, addIndices = true)
                            addLineVertex(rc, loc.latitude, loc.longitude, alt, isIntermediate = true, addIndices = false)
                            addLineVertex(rc, loc.latitude, loc.longitude, alt, isIntermediate = true, addIndices = false)
                            currentData.outlineElements.subList(currentData.outlineElements.size - 6, currentData.outlineElements.size).clear()
                            chainStarted = false
                        }
                    }
                    // Don't draw pole-connecting segments (skip both pole points).
                    continue
                }
                if (pCount % 2 == 0) {
                    val alt = altOf(loc)
                    calcPoint(rc, loc.latitude, loc.longitude, alt, isAbsolute = isPlain)
                    if (!chainStarted) {
                        // Start a new chain with a duplicate "before-first" dummy.
                        currentData.outlineChainStarts.add(currentData.outlineElements.size)
                        addLineVertex(rc, loc.latitude, loc.longitude, alt, isIntermediate = true, addIndices = true)
                        addLineVertex(rc, loc.latitude, loc.longitude, alt, isIntermediate = false, addIndices = true)
                        chainStarted = true
                    } else {
                        addLineVertex(rc, loc.latitude, loc.longitude, alt, isIntermediate = false, addIndices = true)
                    }
                }
            }
            if (chainStarted) {
                // Close the last chain.
                val last = polygon.last()
                val alt = altOf(last)
                calcPoint(rc, last.latitude, last.longitude, alt, isAbsolute = isPlain, isExtrudedSkirt = false)
                addLineVertex(rc, last.latitude, last.longitude, alt, isIntermediate = true, addIndices = false)
                currentData.outlineElements.subList(currentData.outlineElements.size - 6, currentData.outlineElements.size).clear()
            }
        } else {
            // 2D map projection (or non-pole sub-polygon): emit as a single continuous chain. For
            // pole polygons in 2D the pole vertices trace the outline along the map's top/bottom edge,
            // closing the polygon visually; non-pole sub-polygons are just straight-through chains.
            val first = polygon[0]
            val firstAlt = altOf(first)
            calcPoint(rc, first.latitude, first.longitude, firstAlt, isAbsolute = isPlain)
            currentData.outlineChainStarts.add(currentData.outlineElements.size)
            addLineVertex(rc, first.latitude, first.longitude, firstAlt, isIntermediate = true, addIndices = true)
            addLineVertex(rc, first.latitude, first.longitude, firstAlt, isIntermediate = false, addIndices = true)

            for (k in 1 until polygon.size) {
                val loc = polygon[k]
                val alt = altOf(loc)
                calcPoint(rc, loc.latitude, loc.longitude, alt, isAbsolute = isPlain)
                addLineVertex(rc, loc.latitude, loc.longitude, alt, isIntermediate = false, addIndices = true)
            }

            val last = polygon.last()
            val lastAlt = altOf(last)
            calcPoint(rc, last.latitude, last.longitude, lastAlt, isAbsolute = isPlain, isExtrudedSkirt = false)
            addLineVertex(rc, last.latitude, last.longitude, lastAlt, isIntermediate = true, addIndices = false)
            currentData.outlineElements.subList(currentData.outlineElements.size - 6, currentData.outlineElements.size).clear()
        }
    }

    protected open fun assembleGeometryPositions(rc: RenderContext) = with(currentData) {
        // Update terrain-dependent altitude baseline for RELATIVE_TO_GROUND extruded shapes
        val newRefAlt = if (isPlain) {
            val refPos = referencePosition
            rc.globe.getElevation(refPos.latitude, refPos.longitude)
        } else 0.0
        val refAltDelta = (newRefAlt - savedRefAlt).toFloat()
        if (refAltDelta != 0f) {
            for (i in 0 until geographicVertexCount) geographicVertexArray[i * 3 + 2] += refAltDelta
            savedRefAlt = newRefAlt
        }

        determineModelToTexCoord(rc)

        // Recompute Cartesian vertex positions from cached geographic coordinates
        val vertexStride = if (isExtrude) VERTEX_STRIDE * 2 else VERTEX_STRIDE
        for (i in 0 until geographicVertexCount) {
            val geoIdx = i * 3
            val lon = geographicVertexArray[geoIdx].toDouble().degrees
            val lat = geographicVertexArray[geoIdx + 1].toDouble().degrees
            val alt = geographicVertexArray[geoIdx + 2].toDouble()
            calcPoint(rc, lat, lon, alt, isAbsolute = isPlain)
            if (i == 0) vertexOrigin.copy(point)
            val texCoord2d = texCoord2d.copy(point).multiplyByMatrix(modelToTexCoord)
            val arrayIdx = i * vertexStride
            vertexArray[arrayIdx] = (point.x - vertexOrigin.x).toFloat()
            vertexArray[arrayIdx + 1] = (point.y - vertexOrigin.y).toFloat()
            vertexArray[arrayIdx + 2] = (point.z - vertexOrigin.z).toFloat()
            vertexArray[arrayIdx + 3] = texCoord2d.x.toFloat()
            vertexArray[arrayIdx + 4] = texCoord2d.y.toFloat()
            if (isExtrude) {
                vertexArray[arrayIdx + 5] = (vertPoint.x - vertexOrigin.x).toFloat()
                vertexArray[arrayIdx + 6] = (vertPoint.y - vertexOrigin.y).toFloat()
                vertexArray[arrayIdx + 7] = (vertPoint.z - vertexOrigin.z).toFloat()
            }
        }
        vertexIndex = geographicVertexCount * vertexStride

        // Rebuild extruded line vertex positions by re-traversing boundaries
        if (isExtrude && !isSurfaceShape) {
            lineVertexIndex = 0
            verticalVertexIndex = verticalVertexArrayOffset
            for (i in boundaries.indices) {
                val positions = boundaries[i]
                if (positions.isEmpty()) continue
                val pos0 = positions[0]
                var begin = pos0
                var beginAltitude = begin.altitude + savedRefAlt
                calcPoint(rc, begin.latitude, begin.longitude, beginAltitude, isAbsolute = isPlain)
                addLineVertex(rc, begin.latitude, begin.longitude, beginAltitude, isIntermediate = true, addIndices = false)
                addLineVertex(rc, begin.latitude, begin.longitude, beginAltitude, isIntermediate = false, addIndices = false)
                for (idx in 1 until positions.size) {
                    val end = positions[idx]
                    addIntermediateLineVertices(rc, begin, end)
                    val endAltitude = end.altitude + savedRefAlt
                    calcPoint(rc, end.latitude, end.longitude, endAltitude, isAbsolute = isPlain)
                    addLineVertex(rc, end.latitude, end.longitude, endAltitude, isIntermediate = false, addIndices = false)
                    begin = end
                    beginAltitude = endAltitude
                }
                if (begin != pos0) {
                    addIntermediateLineVertices(rc, begin, pos0)
                    val pos0Altitude = pos0.altitude + savedRefAlt
                    calcPoint(rc, pos0.latitude, pos0.longitude, pos0Altitude, isAbsolute = isPlain, isExtrudedSkirt = false)
                    addLineVertex(rc, pos0.latitude, pos0.longitude, pos0Altitude, isIntermediate = true, addIndices = false)
                    addLineVertex(rc, pos0.latitude, pos0.longitude, pos0Altitude, isIntermediate = true, addIndices = false)
                } else {
                    calcPoint(rc, begin.latitude, begin.longitude, beginAltitude, isAbsolute = isPlain, isExtrudedSkirt = false)
                    addLineVertex(rc, begin.latitude, begin.longitude, beginAltitude, isIntermediate = true, addIndices = false)
                }
            }
        }

        // Update bounding box
        with(currentBoundindData) {
            boundingBox.setToPoints(currentData.vertexArray, vertexIndex, VERTEX_STRIDE)
            boundingBox.translate(currentData.vertexOrigin.x, currentData.vertexOrigin.y, currentData.vertexOrigin.z)
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
        var alt = begin.altitude + deltaAlt + currentData.savedRefAlt
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

    protected open fun addIntermediateLineVertices(rc: RenderContext, begin: Position, end: Position) {
        if (maximumIntermediatePoints <= 0) return
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
            else -> return
        }
        if (length < NEAR_ZERO_THRESHOLD) return
        val numSubsegments = maximumIntermediatePoints + 1
        val deltaDist = length / numSubsegments
        val deltaAlt = (end.altitude - begin.altitude) / numSubsegments
        var dist = deltaDist
        var alt = begin.altitude + deltaAlt + currentData.savedRefAlt
        for (idx in 1 until numSubsegments) {
            val loc = intermediateLocation
            when (pathType) {
                GREAT_CIRCLE -> begin.greatCircleLocation(azimuth, dist, loc)
                RHUMB_LINE -> begin.rhumbLocation(azimuth, dist, loc)
                else -> {}
            }
            calcPoint(rc, loc.latitude, loc.longitude, alt, isAbsolute = isPlain)
            addLineVertex(rc, loc.latitude, loc.longitude, alt, isIntermediate = true, addIndices = false)
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
        val geoIdx = geographicVertexCount * 3
        if (geoIdx + 2 >= geographicVertexArray.size) {
            val size = geographicVertexArray.size
            val newArray = FloatArray(size + (size shr 1).coerceAtLeast(6))
            geographicVertexArray.copyInto(newArray)
            geographicVertexArray = newArray
        }
        geographicVertexArray[geoIdx] = longitude.inDegrees.toFloat()
        geographicVertexArray[geoIdx + 1] = latitude.inDegrees.toFloat()
        geographicVertexArray[geoIdx + 2] = altitude.toFloat()
        geographicVertexCount++
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
            return@with
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