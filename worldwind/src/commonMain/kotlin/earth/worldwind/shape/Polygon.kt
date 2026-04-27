package earth.worldwind.shape

import earth.worldwind.draw.DrawShapeState
import earth.worldwind.draw.Drawable
import earth.worldwind.draw.DrawableShape
import earth.worldwind.draw.DrawableSurfaceShape
import earth.worldwind.geom.*
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Angle.Companion.radians
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
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
import kotlin.jvm.JvmOverloads

open class Polygon @JvmOverloads constructor(
    positions: List<Position> = emptyList(), attributes: ShapeAttributes = ShapeAttributes()
): AbstractShape(attributes) {
    private var cachedReferencePosition: Position? = null
    override val referencePosition: Position get() {
        // Cartesian centroid: average of vertex unit vectors, projected back to the sphere. Unlike
        // a lat/lon min/max midpoint this is rotation-equivariant — if the polygon is rotated,
        // the centroid rotates with it — which keeps drag (SphericalRotation in moveTo) stable
        // across successive events. Also handles antimeridian crossing without special-casing
        // and converges to the actual pole for polygons symmetric about it.
        //
        // Cached and invalidated on [reset]. The computation walks every vertex, so a fresh call
        // per assemble (savedRefAlt lookup + moveTo) was allocating one Position per call and
        // re-summing across all boundaries each time.
        cachedReferencePosition?.let { return it }
        var sx = 0.0; var sy = 0.0; var sz = 0.0
        for (boundary in boundaries) for (pos in boundary) {
            val latRad = pos.latitude.inRadians
            val lonRad = pos.longitude.inRadians
            sx += cos(latRad) * cos(lonRad)
            sy += cos(latRad) * sin(lonRad)
            sz += sin(latRad)
        }
        val mag = sqrt(sx * sx + sy * sy + sz * sz)
        val result = if (mag < 1.0e-10) Position()
        else {
            val nx = sx / mag; val ny = sy / mag; val nz = sz / mag
            Position(asin(nz.coerceIn(-1.0, 1.0)).radians, atan2(ny, nx).radians, 0.0)
        }
        cachedReferencePosition = result
        return result
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
        // Primitive-int element buffers — `MutableList<Int>` here was boxing every index to a
        // JVM `Integer` on `.add`, producing thousands of allocations per assemble. [IntList]
        // is backed by a growing `IntArray`.
        val topElements = IntList()
        val sideElements = IntList()
        val baseElements = IntList()
        val outlineElements = IntList()
        val outlineChainStarts = IntList()
        val verticalElements = IntList()
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
        protected val intermediatePosition = Position()
        protected var texCoord1d = 0.0

        protected val tessCoords = DoubleArray(3)
        protected val tessVertices = IntArray(3)
        protected val tessEdgeFlags = BooleanArray(3)
        protected var tessEdgeFlag = true
        protected var tessVertexCount = 0

        // Running unwrapped longitude for [addVertex]'s 3D path. Lat/lon GLU input keeps the
        // top-plane triangulation curvature-following, but a raw +180° → −180° jump on an
        // antimeridian-crossing polygon makes GLU emit huge spurious triangles. We shift each
        // subsequent lon by ±360° as needed so the sequence stays continuous. NaN means
        // "first vertex of this polygon, no prior to compare against"; reset at the start of
        // each polygon assembly.
        protected var prevTessLon = Double.NaN
        // Sequential scratch pool of [Position] instances for [densifyEdgeWithAltitude]. Without
        // it the densifier allocates a fresh Position per intermediate sample, producing serious
        // GC pressure on dense polar polygons. Reset at the start of each densification pass.
        protected val positionPool = ScratchPool(::Position)
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
        cachedReferencePosition = null
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
        if (!prepareGeometry(rc)) return

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
            var index = currentData.topElements.copyTo(array, 0)
            index = currentData.sideElements.copyTo(array, index)
            currentData.baseElements.copyToReversed(array, index)
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
            val index = currentData.outlineElements.copyTo(array, 0)
            currentData.verticalElements.copyTo(array, index)
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
        drawState.isOccluderOnly = isOccluderOnly

        // Configure the drawable according to the shape's attributes.
        drawStateLines.vertexOrigin.copy(currentData.vertexOrigin)
        drawStateLines.enableCullFace = false
        drawStateLines.enableDepthTest = activeAttributes.isDepthTest
        drawStateLines.enableDepthWrite = activeAttributes.isDepthWrite
        drawStateLines.enableLighting = activeAttributes.isLightingEnabled
        drawStateLines.isOccluderOnly = isOccluderOnly

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
        val chainCount = chainStarts.size
        for (ci in 0 until chainCount) {
            val start = chainStarts[ci]
            val end = if (ci + 1 < chainCount) chainStarts[ci + 1] else currentData.outlineElements.size
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

    override val hasGeometry get() = currentData.vertexArray.isNotEmpty()

    override fun mustAssembleGeometry(rc: RenderContext): Boolean {
        currentData = data[rc.globeState] ?: PolygonData().also { data[rc.globeState] = it }
        return currentData.refreshVertexArray || isExtrude && !isSurfaceShape && currentData.refreshLineVertexArray
    }

    override fun assembleGeometry(rc: RenderContext) {
        ++bufferDataVersion // advance so [offerGLBufferUpload] sees fresh content this frame
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
        // Splitter is used only for SURFACE shapes that actually need it — they rasterize in
        // lat/lon tile space, where a 340° longitude jump across the antimeridian produces huge
        // spurious triangles unless the polygon is split along ±180°, and a near-pole edge bows
        // visibly below the true great-circle path unless the boundary is densified along it.
        // 3D shapes feed raw 3D Cartesian coords to the GLU tessellator so neither concern
        // applies. Surface shapes that are far from both ±180° and the poles also fall through
        // to the cheap per-boundary loop — the splitter pipeline (which densifies + walks each
        // edge for intersection tests) is wasted work for the common mid-latitude polygon.
        val surfaceNeedsSplitter = isSurfaceShape && boundaries.isNotEmpty() && surfaceNeedsSplitter()
        val surfaceBoundaries: List<List<Position>>
        val splitContours: List<ContourInfo>?
        val splitCrossesAntimeridian: Boolean
        if (!surfaceNeedsSplitter) {
            surfaceBoundaries = boundaries
            splitContours = null
            splitCrossesAntimeridian = false
        } else {
            surfaceBoundaries = densifyBoundariesForSurface()
            val (crosses, contours) = PolygonSplitter.splitContours(surfaceBoundaries)
            splitContours = contours
            splitCrossesAntimeridian = crosses
        }

        // Determine the number of vertices. The per-edge subdivision count differs by shape kind:
        // surface paths through the splitter use [maxIntermediatesPerEdge] (worst case) since
        // [densifyEdgeWithAltitude] is polar-throttled. The non-splitter branch (3D, or surface
        // that bypassed the splitter) uses uniform stepping in [addIntermediateVertices] capped
        // at `maximumNumEdgeIntervals - 1` per edge — using that bound avoids the ~11×
        // over-allocation the polar-throttle bound would imply.
        val perEdgeMax = if (splitContours != null) maxIntermediatesPerEdge()
        else if (maximumNumEdgeIntervals <= 0 || pathType == LINEAR) 0
        else maximumNumEdgeIntervals - 1
        val noIntermediatePoints = perEdgeMax <= 0
        var vertexCount = 0
        var lineVertexCount = 0
        var verticalVertexCount = 0
        if (splitContours != null) {
            // Surface with splitter: fill and outline both come from sub-polygons.
            for (info in splitContours) for (poly in info.polygons) {
                val n = poly.size
                vertexCount += n
                lineVertexCount += n + 3
                verticalVertexCount += n
            }
        } else {
            // 3D or no-split surface: fill and outline both come from raw boundaries.
            for (i in boundaries.indices) {
                val p = boundaries[i]
                if (p.isEmpty()) continue
                val lastEqualsFirst = p[0] == p[p.size - 1]
                if (noIntermediatePoints) {
                    vertexCount += p.size
                    lineVertexCount += p.size + if (lastEqualsFirst) 2 else 3
                    verticalVertexCount += p.size
                } else if (lastEqualsFirst) {
                    vertexCount += p.size + (p.size - 1) * perEdgeMax
                    lineVertexCount += 2 + p.size + (p.size - 1) * perEdgeMax
                    verticalVertexCount += p.size
                } else {
                    vertexCount += p.size + p.size * perEdgeMax
                    lineVertexCount += 3 + p.size + p.size * perEdgeMax
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
        // Both surface and 3D feed lat/lon/alt to GLU with +Z normal. Lat/lon space stays
        // well-conditioned regardless of altitude variation (keeps the top-plane triangulation
        // dense and curvature-following) and the splitter / unwrap logic below handles the
        // antimeridian. Reset the per-polygon unwrap state used by [addVertex].
        GLU.gluTessNormal(tess, 0.0, 0.0, 1.0)
        prevTessLon = Double.NaN
        GLU.gluTessCallback(tess, GLU.GLU_TESS_COMBINE_DATA, tessCallback)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_VERTEX_DATA, tessCallback)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_EDGE_FLAG_DATA, tessCallback)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_ERROR_DATA, tessCallback)
        GLU.gluTessBeginPolygon(tess, rc)
        if (splitContours != null) {
            // Surface shape: emit pre-densified, antimeridian/pole-split sub-polygons. Both fill
            // and outline come from sub-polygons because surface raster space follows lat/lon
            // boundaries that the splitter respects.
            for (info in splitContours) {
                for ((polyIdx, polygon) in info.polygons.withIndex()) {
                    if (polygon.isEmpty()) continue
                    val iMap = info.iMaps[polyIdx]
                    val isPolePolygon = polyIdx == info.poleIndex

                    GLU.gluTessBeginContour(tess)

                    val savedRefAlt = currentData.savedRefAlt
                    for ((k, loc) in polygon.withIndex()) {
                        val alt = (if (loc is Position) loc.altitude else 0.0) + savedRefAlt
                        val splitterInserted = iMap[k] != null
                        calcPoint(rc, loc.latitude, loc.longitude, alt, isAbsolute = isPlain)
                        addVertex(
                            rc, loc.latitude, loc.longitude, alt,
                            type = if (splitterInserted) VERTEX_INTERMEDIATE else VERTEX_ORIGINAL
                        )
                    }

                    addOutlineChains(rc, polygon, iMap, isPolePolygon, info.pole)

                    GLU.gluTessEndContour(tess)
                }
            }
        } else {
            // 3D or no-split surface: emit raw boundaries, with [addIntermediateVertices]
            // densifying inline. The GLU tessellator works in Cartesian space (see [addVertex])
            // so an antimeridian wrap is just a normal continuous edge in 3D.
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
                currentData.outlineElements.removeLast(6)

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
     *
     * Splitter-inserted vertices ([iMap][k] != null — antimeridian intersections and pole-detour
     * points) are emitted with `isIntermediate = true`. Surface shapes don't use the
     * `isExtrude && !isIntermediate` vertical-stroke path anyway, so this only affects the line
     * texture-coordinate handling along boundaries.
     */
    protected open fun addOutlineChains(
        rc: RenderContext, polygon: List<Location>, iMap: Map<Int, Intersection>,
        isPolePolygon: Boolean, pole: Pole
    ) {
        val savedRefAlt = currentData.savedRefAlt

        fun altOf(loc: Location) = (if (loc is Position) loc.altitude else 0.0) + savedRefAlt
        fun isOriginalAt(k: Int): Boolean = iMap[k] == null

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
                            addLineVertex(rc, loc.latitude, loc.longitude, alt, isIntermediate = true, addIndices = true)
                            addLineVertex(rc, loc.latitude, loc.longitude, alt, isIntermediate = true, addIndices = false)
                            addLineVertex(rc, loc.latitude, loc.longitude, alt, isIntermediate = true, addIndices = false)
                            currentData.outlineElements.removeLast(6)
                            chainStarted = false
                        }
                    }
                    // Don't draw pole-connecting segments (skip both pole points).
                    continue
                }
                if (pCount % 2 == 0) {
                    val alt = altOf(loc)
                    val isOrig = isOriginalAt(k)
                    calcPoint(rc, loc.latitude, loc.longitude, alt, isAbsolute = isPlain)
                    if (!chainStarted) {
                        // Start a new chain with a duplicate "before-first" dummy.
                        currentData.outlineChainStarts.add(currentData.outlineElements.size)
                        addLineVertex(rc, loc.latitude, loc.longitude, alt, isIntermediate = true, addIndices = true)
                        addLineVertex(rc, loc.latitude, loc.longitude, alt, isIntermediate = !isOrig, addIndices = true)
                        chainStarted = true
                    } else {
                        addLineVertex(rc, loc.latitude, loc.longitude, alt, isIntermediate = !isOrig, addIndices = true)
                    }
                }
            }
            if (chainStarted) {
                // Close the last chain.
                val last = polygon.last()
                val alt = altOf(last)
                calcPoint(rc, last.latitude, last.longitude, alt, isAbsolute = isPlain, isExtrudedSkirt = false)
                addLineVertex(rc, last.latitude, last.longitude, alt, isIntermediate = true, addIndices = false)
                currentData.outlineElements.removeLast(6)
            }
        } else {
            // 2D map projection (or non-pole sub-polygon): emit as a single continuous chain. For
            // pole polygons in 2D the pole vertices trace the outline along the map's top/bottom edge,
            // closing the polygon visually; non-pole sub-polygons are just straight-through chains.
            val first = polygon[0]
            val firstAlt = altOf(first)
            val firstIsOrig = isOriginalAt(0)
            calcPoint(rc, first.latitude, first.longitude, firstAlt, isAbsolute = isPlain)
            currentData.outlineChainStarts.add(currentData.outlineElements.size)
            addLineVertex(rc, first.latitude, first.longitude, firstAlt, isIntermediate = true, addIndices = true)
            addLineVertex(rc, first.latitude, first.longitude, firstAlt, isIntermediate = !firstIsOrig, addIndices = true)

            for (k in 1 until polygon.size) {
                val loc = polygon[k]
                val alt = altOf(loc)
                val isOrig = isOriginalAt(k)
                calcPoint(rc, loc.latitude, loc.longitude, alt, isAbsolute = isPlain)
                addLineVertex(rc, loc.latitude, loc.longitude, alt, isIntermediate = !isOrig, addIndices = true)
            }

            val last = polygon.last()
            val lastAlt = altOf(last)
            calcPoint(rc, last.latitude, last.longitude, lastAlt, isAbsolute = isPlain, isExtrudedSkirt = false)
            addLineVertex(rc, last.latitude, last.longitude, lastAlt, isIntermediate = true, addIndices = false)
            currentData.outlineElements.removeLast(6)
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

    /**
     * Cheap pre-check: does any boundary cross the antimeridian or come close enough to a pole
     * that the lat/lon-rasterized straight segment would visibly diverge from the great-circle?
     * If neither, the splitter pipeline is wasted work — straight 2D lat/lon segments rasterize
     * fine for a mid-latitude polygon and the original boundary can feed the per-boundary loop.
     *
     * Detects:
     *  - **Antimeridian crossing**, by an adjacent-vertex longitude jump > 180° on any edge
     *    (including the implicit closing edge).
     *  - **Pole proximity**, by any boundary vertex's latitude exceeding [POLE_PROXIMITY_DEG].
     *    The threshold is conservative (75°) so the polar throttle still has plenty of room to
     *    smooth great-circles whose endpoints are well above the equator.
     */
    protected open fun surfaceNeedsSplitter(): Boolean {
        for (boundary in boundaries) {
            val n = boundary.size
            if (n < 2) continue
            for (i in 0 until n) {
                val cur = boundary[i]
                if (abs(cur.latitude.inDegrees) > POLE_PROXIMITY_DEG) return true
                val next = boundary[if (i + 1 < n) i + 1 else 0]
                if (next === cur) continue
                if (abs(cur.longitude.inDegrees - next.longitude.inDegrees) > 180.0) return true
            }
        }
        return false
    }

    /**
     * Produces densified polygon boundaries by inserting throttled intermediate vertices along
     * each edge before polygon splitting. Closing duplicates are preserved.
     *
     * Returns [List]<[List]<[Position]>> rather than [List]<[List]<[Location]>] so densified
     * intermediates carry interpolated altitudes — a 3D extruded polygon at altitude 5e5 m would
     * otherwise drop intermediate vertices to altitude 0, producing a saw-tooth top.
     */
    protected fun densifyBoundariesForSurface(): List<List<Position>> {
        if (maximumNumEdgeIntervals <= 0 || pathType == LINEAR) return boundaries
        // Reset the pool — every Position acquired in this call is fresh, but the underlying
        // instances are reused across calls so we don't churn the GC. Earlier callers already
        // consumed the splitter result and let it go out of scope.
        positionPool.reset()
        val out = ArrayList<List<Position>>(boundaries.size)
        for (boundary in boundaries) {
            if (boundary.isEmpty()) { out.add(emptyList()); continue }
            val n = boundary.size
            val closesAlready = n >= 2 && boundary[0] == boundary[n - 1]
            // Pre-size the densified ArrayList based on a per-boundary bound. We use the
            // polar-throttled cap only if some vertex of THIS boundary exceeds [POLE_PROXIMITY_DEG]
            // — a polygon may have one polar boundary and several mid-latitude ones, and only the
            // polar boundary needs the bigger reserve. Below the threshold the throttle factor is
            // ≤ ~6, so the unthrottled cap (`maximumNumEdgeIntervals - 1`) is a tight upper bound
            // and avoids the ArrayList grow-resize churn on common cases.
            val perEdgeBound = perEdgeBoundForLocations(boundary)
            val edgeCount = if (closesAlready) n - 1 else n
            val densifiedCap = n + edgeCount * perEdgeBound
            val densified = ArrayList<Position>(densifiedCap)
            for (i in 0 until edgeCount) {
                val begin = boundary[i]
                densified.add(begin)
                val nextIdx = (i + 1) % n
                val end = boundary[nextIdx]
                if (begin.latitude == end.latitude && begin.longitude == end.longitude) continue
                densifyEdgeWithAltitude(begin, end, densified)
            }
            if (closesAlready) densified.add(boundary[n - 1])
            out.add(densified)
        }
        return out
    }

    /**
     * Position-preserving variant of [AbstractShape.densifyEdge]. Uses [Position.interpolateAlongPath]
     * so the resulting intermediate vertices carry an interpolated altitude rather than defaulting
     * to 0 — required for 3D extruded polygons where intermediate altitudes must match the
     * boundary's altitude profile, not drop to ground level.
     *
     * Each emitted [Position] comes from [positionPool], eliminating per-intermediate allocations
     * on the surface densification path. The pool is reset at the start of
     * [densifyBoundariesForSurface].
     */
    private fun densifyEdgeWithAltitude(begin: Position, end: Position, out: MutableList<Position>) {
        if (maximumNumEdgeIntervals <= 0) return
        val length = when (pathType) {
            GREAT_CIRCLE -> begin.greatCircleDistance(end)
            RHUMB_LINE -> begin.rhumbDistance(end)
            else -> return
        }
        if (length < NEAR_ZERO_THRESHOLD) return
        val steps = (maximumNumEdgeIntervals * length / PI).roundToInt()
        if (steps <= 0) return
        val dt = 1.0 / steps
        val pos = intermediatePosition
        var currentLat = begin.latitude
        var t = throttledStep(dt, currentLat)
        while (t < 1.0) {
            begin.interpolateAlongPath(end, pathType, t, pos)
            out.add(positionPool.acquire().copy(pos))
            currentLat = pos.latitude
            t += throttledStep(dt, currentLat)
        }
    }

    /**
     * Inserts intermediate fill+line vertices along a 3D polygon edge with **uniform** stepping
     * and a **fixed per-edge count** of [maximumNumEdgeIntervals] (no length scaling, no polar
     * throttle). GLU triangulates the polygon top using only the boundary vertices we feed it,
     * with chord-flat triangles between non-adjacent samples; if those samples are sparse the
     * cap visibly facets. Length-scaling produced near-zero intermediates for the typical
     * few-degree edge — fixed count keeps the cap bowing with the globe at the cost of more
     * vertices on long edges.
     */
    protected open fun addIntermediateVertices(rc: RenderContext, begin: Position, end: Position) {
        if (maximumNumEdgeIntervals <= 0) return // subdivision disabled
        val length = when (pathType) {
            GREAT_CIRCLE -> begin.greatCircleDistance(end)
            RHUMB_LINE -> begin.rhumbDistance(end)
            else -> return // LINEAR: no intermediate vertices
        }
        if (length < NEAR_ZERO_THRESHOLD) return
        val steps = maximumNumEdgeIntervals
        val dt = 1.0 / steps
        val pos = intermediatePosition
        var t = dt
        while (t < 1.0) {
            begin.interpolateAlongPath(end, pathType, t, pos)
            val alt = pos.altitude + currentData.savedRefAlt
            calcPoint(rc, pos.latitude, pos.longitude, alt, isAbsolute = isPlain)
            addVertex(rc, pos.latitude, pos.longitude, alt, type = VERTEX_INTERMEDIATE)
            addLineVertex(rc, pos.latitude, pos.longitude, alt, isIntermediate = true, addIndices = true)
            t += dt
        }
    }

    /** Line-only counterpart of [addIntermediateVertices]; same fixed-count rationale. */
    protected open fun addIntermediateLineVertices(rc: RenderContext, begin: Position, end: Position) {
        if (maximumNumEdgeIntervals <= 0) return
        val length = when (pathType) {
            GREAT_CIRCLE -> begin.greatCircleDistance(end)
            RHUMB_LINE -> begin.rhumbDistance(end)
            else -> return
        }
        if (length < NEAR_ZERO_THRESHOLD) return
        val steps = maximumNumEdgeIntervals
        val dt = 1.0 / steps
        val pos = intermediatePosition
        var t = dt
        while (t < 1.0) {
            begin.interpolateAlongPath(end, pathType, t, pos)
            val alt = pos.altitude + currentData.savedRefAlt
            calcPoint(rc, pos.latitude, pos.longitude, alt, isAbsolute = isPlain)
            addLineVertex(rc, pos.latitude, pos.longitude, alt, isIntermediate = true, addIndices = false)
            t += dt
        }
    }

    protected open fun addVertex(
        rc: RenderContext, latitude: Angle, longitude: Angle, altitude: Double, type: Int
    ): Int = with(currentData) {
        val vertex = vertexIndex / VERTEX_STRIDE
        if (vertex == 0) {
            if (isSurfaceShape) vertexOrigin.set(longitude.inDegrees, latitude.inDegrees, altitude)
            else vertexOrigin.copy(point)
        }
        val texCoord2d = texCoord2d.copy(point).multiplyByMatrix(modelToTexCoord)
        if (type != VERTEX_COMBINED) {
            // Both shape kinds feed lat/lon/alt to GLU. For 3D, unwrap the lon to keep the
            // sequence continuous across the antimeridian — calcPoint produces the same
            // Cartesian for 185° and −175° so the unwrap is invisible to the rendered mesh.
            // Surface shapes use the splitter, which already cuts at ±180°, so no unwrap.
            var lonForTess = longitude.inDegrees
            if (!isSurfaceShape) {
                if (!prevTessLon.isNaN()) {
                    while (lonForTess - prevTessLon > 180.0) lonForTess -= 360.0
                    while (lonForTess - prevTessLon < -180.0) lonForTess += 360.0
                }
                prevTessLon = lonForTess
            }
            tessCoords[0] = lonForTess
            tessCoords[1] = latitude.inDegrees
            tessCoords[2] = altitude
            GLU.gluTessVertex(rc.tessellator, tessCoords, coords_offset = 0, vertex)
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
        // Pick the source coords: surface shapes store lat/lon/alt offsets, 3D shapes store
        // Cartesian point relative to vertexOrigin.
        val x: Float; val y: Float; val z: Float
        if (isSurfaceShape) {
            x = (longitude.inDegrees - vertexOrigin.x).toFloat()
            y = (latitude.inDegrees - vertexOrigin.y).toFloat()
            z = (altitude - vertexOrigin.z).toFloat()
        } else {
            x = (point.x - vertexOrigin.x).toFloat()
            y = (point.y - vertexOrigin.y).toFloat()
            z = (point.z - vertexOrigin.z).toFloat()
        }
        lineVertexIndex = emitLineCorners(lineVertexArray, lineVertexIndex, x, y, z, texCoord1d.toFloat())
        if (addIndices) {
            // Triangles from this segment's four corners and the bridge to the next segment's
            // first two corners.
            outlineElements.add(vertex);     outlineElements.add(vertex + 1); outlineElements.add(vertex + 2)
            outlineElements.add(vertex + 2); outlineElements.add(vertex + 1); outlineElements.add(vertex + 3)
            outlineElements.add(vertex + 2); outlineElements.add(vertex + 3); outlineElements.add(vertex + 4)
            outlineElements.add(vertex + 4); outlineElements.add(vertex + 3); outlineElements.add(vertex + 5)
        }
        // Extruded vertical strokes: 4 quads (16 corners total) — pointA dummy, pointB top,
        // pointB bottom, pointC dummy — plus the 6 indices for the visible pointB→pointB face.
        if (!isSurfaceShape && isExtrude && !isIntermediate) {
            val index = verticalVertexIndex / VERTEX_STRIDE
            val vx = (vertPoint.x - vertexOrigin.x).toFloat()
            val vy = (vertPoint.y - vertexOrigin.y).toFloat()
            val vz = (vertPoint.z - vertexOrigin.z).toFloat()
            verticalVertexIndex = emitLineCorners(lineVertexArray, verticalVertexIndex, x, y, z, 0.0f)
            verticalVertexIndex = emitLineCorners(lineVertexArray, verticalVertexIndex, x, y, z, 0.0f)
            verticalVertexIndex = emitLineCorners(lineVertexArray, verticalVertexIndex, vx, vy, vz, 0.0f)
            verticalVertexIndex = emitLineCorners(lineVertexArray, verticalVertexIndex, vx, vy, vz, 0.0f)
            verticalElements.add(index + 2); verticalElements.add(index + 3); verticalElements.add(index + 4)
            verticalElements.add(index + 4); verticalElements.add(index + 3); verticalElements.add(index + 5)
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
        // tessCoords are lat/lon/alt for both surface and 3D (see [addVertex]). For 3D the lon
        // may be the unwrapped variant (e.g. 185° for an antimeridian-crossing polygon);
        // calcPoint produces the same Cartesian for 185° and −175° so the unwrap is invisible
        // to the rendered geometry.
        val longitude = coords[0].degrees
        val latitude = coords[1].degrees
        val altitude = coords[2]
        calcPoint(rc, latitude, longitude, altitude, isAbsolute = isPlain)
        val combinedIdx = addVertex(rc, latitude, longitude, altitude, type = VERTEX_COMBINED)
        outData[0] = combinedIdx
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
        // Need room for VERTEX_STRIDE * 2 floats (top + bottom for extruded shapes); use the
        // worst-case stride to keep the buffer aligned to the addVertex emit pattern.
        val perVertex = if (isExtrude && !isSurfaceShape) VERTEX_STRIDE * 2 else VERTEX_STRIDE
        if (size - vertexIndex < perVertex) {
            var increment = (size shr 1).coerceAtLeast(perVertex * 4)
            // Round increment up to a multiple of `perVertex` so vertexIndex (always a multiple of
            // perVertex) never steps past the array length without triggering another grow.
            val rem = increment % perVertex
            if (rem != 0) increment += perVertex - rem
            val newArray = FloatArray(size + increment)
            currentData.vertexArray.copyInto(newArray)
            currentData.vertexArray = newArray
        }
    }
}