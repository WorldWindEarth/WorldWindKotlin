package earth.worldwind.shape

import earth.worldwind.draw.DrawShapeState
import earth.worldwind.draw.Drawable
import earth.worldwind.draw.DrawableShape
import earth.worldwind.draw.DrawableSurfaceShape
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.radians
import earth.worldwind.geom.Location
import earth.worldwind.geom.Position
import earth.worldwind.geom.SphericalRotation
import earth.worldwind.geom.Vec3
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
import earth.worldwind.util.IntList
import earth.worldwind.util.NumericArray
import earth.worldwind.util.PolygonSplitter
import earth.worldwind.util.ScratchPool
import earth.worldwind.util.kgl.*
import kotlin.jvm.JvmOverloads

open class Path @JvmOverloads constructor(
    positions: List<Position>, attributes: ShapeAttributes = ShapeAttributes()
): AbstractShape(attributes) {
    private var cachedReferencePosition: Position? = null
    override val referencePosition: Position get() {
        // Cartesian centroid: average of vertex unit vectors, projected back to the sphere. Unlike
        // a lat/lon min/max midpoint this is rotation-equivariant — if the path is rotated, the
        // centroid rotates with it — which keeps drag (SphericalRotation in moveTo) stable across
        // successive events. Handles antimeridian crossing without special-casing. Cached and
        // invalidated on [reset].
        cachedReferencePosition?.let { return it }
        if (positions.isEmpty()) return Position().also { cachedReferencePosition = it }
        var sx = 0.0; var sy = 0.0; var sz = 0.0
        for (pos in positions) {
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
    var positions = positions
        set(value) {
            field = value
            reset()
        }
    protected val data = mutableMapOf<Globe.State?, PathData>()

    open class PathData {
        val vertexOrigin = Vec3()
        var vertexArray = FloatArray(0)
        var extrudeVertexArray = FloatArray(0)
        // Primitive-int element buffers — see PolygonData for rationale.
        val interiorElements = IntList()
        val outlineElements = IntList()
        val outlineChainStarts = IntList()
        val verticalElements = IntList()
        val extrudeVertexBufferKey = Any()
        val extrudeElementBufferKey = Any()
        val vertexBufferKey = Any()
        val elementBufferKey = Any()
        var refreshVertexArray = true
    }

    companion object {
        protected const val VERTEX_STRIDE = 5 // 5 floats
        protected const val EXTRUDE_SEGMENT_STRIDE = 2 * VERTEX_STRIDE // 2 vertices
        protected const val OUTLINE_SEGMENT_STRIDE = 4 * VERTEX_STRIDE // 4 vertices
        protected const val VERTICAL_SEGMENT_STRIDE = 4 * OUTLINE_SEGMENT_STRIDE // 4 points per 4 vertices per vertical line

        protected lateinit var currentData: PathData

        protected var vertexIndex = 0
        protected var verticalIndex = 0
        protected var extrudeIndex = 0

        protected val prevPoint = Vec3()
        protected val intermediatePosition = Position()
        protected var texCoord1d = 0.0
        // Sequential scratch pool for [densifyForSurface] — eliminates per-intermediate
        // [Position] allocation on dense polar paths.
        protected val positionPool = ScratchPool(::Position)
    }

    override fun resetGlobeState(globeState: Globe.State?) {
        super.resetGlobeState(globeState)
        data[globeState]?.refreshVertexArray = true
    }

    override fun reset() {
        super.reset()
        cachedReferencePosition = null
        data.values.forEach { it.refreshVertexArray = true }
    }

    override fun moveTo(globe: Globe, position: Position) {
        val rotation = SphericalRotation(referencePosition, position)
        for (pos in positions) rotation.apply(pos)
        reset()
    }

    override fun makeDrawable(rc: RenderContext) {
        if (positions.size < 2) return // nothing to draw
        if (!prepareGeometry(rc)) return

        // Obtain a drawable form the render context pool, and compute distance to the render camera.
        val drawable: Drawable
        val drawState: DrawShapeState
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
        } else {
            val pool = rc.getDrawablePool(DrawableShape.KEY)
            drawable = DrawableShape.obtain(pool)
            drawState = drawable.drawState
            cameraDistanceSq = cameraDistanceSquared(
                rc, currentData.vertexArray, currentData.vertexArray.size, OUTLINE_SEGMENT_STRIDE, currentData.vertexOrigin
            )
            cameraDistance = sqrtCameraDistanceForTexture(cameraDistanceSq)
        }

        // Use triangles mode to draw lines
        drawState.isLine = true

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
            val array = IntArray(currentData.outlineElements.size + currentData.verticalElements.size)
            val index = currentData.outlineElements.copyTo(array, 0)
            currentData.verticalElements.copyTo(array, index)
            NumericArray.Ints(array)
        }

        drawOutline(rc, drawState, cameraDistance)

        // Configure the drawable according to the shape's attributes.
        drawState.vertexOrigin.copy(currentData.vertexOrigin)
        drawState.boundingCenter.copy(currentBoundindData.boundingBox.center)
        drawState.boundingRadius = currentBoundindData.boundingBox.radius
        drawState.enableCullFace = false
        drawState.enableDepthTest = activeAttributes.isDepthTest
        drawState.enableDepthWrite = activeAttributes.isDepthWrite &&
            drawState.color.alpha * drawState.opacity >= 1f
        drawState.enableLighting = activeAttributes.isLightingEnabled
        drawState.shadowMode = activeAttributes.shadowMode
        drawState.isOccluderOnly = isOccluderOnly

        // Enqueue the drawable for processing on the OpenGL thread.
        if (isSurfaceShape) {
            rc.offerSurfaceDrawable(drawable, zOrder)
            // For antimeridian-crossing surface paths, enqueue a secondary drawable for the other half.
            if (currentBoundindData.crossesAntimeridian) {
                val pool = rc.getDrawablePool(DrawableSurfaceShape.KEY)
                DrawableSurfaceShape.obtain(pool).also { d ->
                    d.offset = rc.globe.offset
                    d.sector.copy(currentBoundindData.additionalSector)
                    d.version = computeVersion(); d.isDynamic = isDynamic || rc.currentLayer.isDynamic
                    d.drawState.copy(drawState)
                    rc.offerSurfaceDrawable(d, zOrder)
                }
            }
        } else rc.offerShapeDrawable(drawable, cameraDistanceSq)

        drawInterior(rc, drawState, cameraDistanceSq)
    }

    protected open fun drawOutline(rc: RenderContext, drawState: DrawShapeState, cameraDistance: Double) {
        if (!activeAttributes.isDrawOutline || rc.isPickMode && !activeAttributes.isPickOutline) return

        // Configure the drawable to use the outline texture when drawing the outline.
        activeAttributes.outlineImageSource?.let { outlineImageSource ->
            rc.getTexture(outlineImageSource, defaultOutlineImageOptions)?.let { texture ->
                drawState.texture = texture
                drawState.textureLod = computeRepeatingTexCoordTransform(rc, texture, cameraDistance, drawState.texCoordMatrix)
            }
        }

        // Configure the drawable to display the shape's outline. Increase surface shape line widths by 1/2 pixel.
        // Lines drawn indirectly offscreen framebuffer appear thinner when sampled as a texture.
        drawState.color.copy(if (rc.isPickMode) pickColor else activeAttributes.outlineColor)
        drawState.opacity = if (rc.isPickMode) 1f else rc.currentLayer.opacity
        drawState.lineWidth = activeAttributes.outlineWidth + if (isSurfaceShape) 0.5f else 0f
        // Draw each sub-path chain separately to avoid the spurious connecting triangle that GL_TRIANGLE_STRIP
        // produces at the junction between antimeridian-split chains when drawn as one continuous strip.
        val chainStarts = currentData.outlineChainStarts
        val chainCount = chainStarts.size
        for (ci in 0 until chainCount) {
            val start = chainStarts[ci]
            val end = if (ci + 1 < chainCount) chainStarts[ci + 1] else currentData.outlineElements.size
            val count = end - start
            if (count > 0) drawState.drawElements(GL_TRIANGLE_STRIP, count, GL_UNSIGNED_INT, start * Int.SIZE_BYTES)
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

    protected open fun drawInterior(rc: RenderContext, drawState: DrawShapeState, cameraDistanceSq: Double) {
        if (!activeAttributes.isDrawInterior || rc.isPickMode && !activeAttributes.isPickInterior) return

        // Configure the drawable to display the shape's extruded interior.
        if (isExtrude && !isSurfaceShape) {
            val pool = rc.getDrawablePool(DrawableShape.KEY)
            val drawableExtrusion = DrawableShape.obtain(pool)
            val drawStateExtrusion = drawableExtrusion.drawState

            drawStateExtrusion.isLine = false

            // Use the basic GLSL program to draw the shape.
            drawStateExtrusion.program = rc.getShaderProgram(TriangleShaderProgram.KEY) { TriangleShaderProgram() }

            // Assemble the drawable's OpenGL vertex buffer object.
            drawStateExtrusion.vertexBuffer = rc.getBufferObject(currentData.extrudeVertexBufferKey) {
                BufferObject(GL_ARRAY_BUFFER, 0)
            }
            rc.offerGLBufferUpload(currentData.extrudeVertexBufferKey, bufferDataVersion) {
                NumericArray.Floats(currentData.extrudeVertexArray)
            }

            // Assemble the drawable's OpenGL element buffer object.
            drawStateExtrusion.elementBuffer = rc.getBufferObject(currentData.extrudeElementBufferKey) {
                BufferObject(GL_ELEMENT_ARRAY_BUFFER, 0)
            }
            rc.offerGLBufferUpload(currentData.extrudeElementBufferKey, bufferDataVersion) {
                NumericArray.Ints(currentData.interiorElements.toIntArray())
            }

            // Configure the drawable according to the shape's attributes.
            drawStateExtrusion.vertexOrigin.copy(currentData.vertexOrigin)
            drawStateExtrusion.vertexStride = VERTEX_STRIDE * 4 // stride in bytes
            drawStateExtrusion.color.copy(if (rc.isPickMode) pickColor else activeAttributes.interiorColor)
            drawStateExtrusion.opacity = if (rc.isPickMode) 1f else rc.currentLayer.opacity
            drawStateExtrusion.enableCullFace = false
            drawStateExtrusion.enableDepthTest = activeAttributes.isDepthTest
            drawStateExtrusion.enableDepthWrite = activeAttributes.isDepthWrite &&
                drawStateExtrusion.color.alpha * drawStateExtrusion.opacity >= 1f
            drawStateExtrusion.enableLighting = activeAttributes.isLightingEnabled
            drawStateExtrusion.shadowMode = activeAttributes.shadowMode
            drawStateExtrusion.isOccluderOnly = isOccluderOnly
            drawStateExtrusion.texture = null
            drawStateExtrusion.texCoordAttrib.size = 2
            drawStateExtrusion.texCoordAttrib.offset = 12
            drawStateExtrusion.drawElements(GL_TRIANGLE_STRIP, currentData.interiorElements.size, GL_UNSIGNED_INT, 0)

            rc.offerShapeDrawable(drawableExtrusion, cameraDistanceSq)
        }
    }

    override val hasGeometry get() = currentData.vertexArray.isNotEmpty()

    override fun mustAssembleGeometry(rc: RenderContext): Boolean {
        currentData = data[rc.globeState] ?: PathData().also { data[rc.globeState] = it }
        return currentData.refreshVertexArray
    }

    override fun assembleGeometry(rc: RenderContext) {
        ++bufferDataVersion // advance so [offerGLBufferUpload] sees fresh content this frame
        // Surface shapes go through the densify-then-split pipeline only when actually needed
        // (any edge crosses the antimeridian, or any waypoint is near a pole). Mid-latitude,
        // non-crossing paths fall through to the cheap inline-densify branch — same as 3D.
        val surfaceNeedsSplitter = isSurfaceShape && pathSurfaceNeedsSplitter()
        val subPaths: List<List<Position>>
        val crossesAntimeridian = if (surfaceNeedsSplitter) {
            val split = splitPositionsAtAntimeridian(positions)
            subPaths = split.first
            split.second
        } else {
            subPaths = listOf(positions)
            false
        }

        // Determine the total vertex count across all sub-paths. The splitter branch pre-densifies,
        // so each sub-path contributes `sp.size + 2`. The non-splitter branch (3D, or surface
        // bypassing the splitter) inline-densifies via [addIntermediateVertices] — uniform stepping
        // capped at `maximumNumEdgeIntervals - 1` per edge.
        val perEdgeMax = if (surfaceNeedsSplitter || maximumNumEdgeIntervals <= 0 || pathType == LINEAR) 0
        else maximumNumEdgeIntervals - 1
        val noIntermediate = surfaceNeedsSplitter || perEdgeMax <= 0
        var totalVertexCount = 0
        for (sp in subPaths) {
            totalVertexCount += if (noIntermediate) sp.size + 2
            else sp.size + 2 + (sp.size - 1) * perEdgeMax
        }

        // Separate vertex array for interior polygon
        extrudeIndex = 0
        currentData.extrudeVertexArray = if (isExtrude && !isSurfaceShape)
            FloatArray((totalVertexCount + 2) * EXTRUDE_SEGMENT_STRIDE) else FloatArray(0)
        currentData.interiorElements.clear()

        vertexIndex = 0
        verticalIndex = if (isExtrude && !isSurfaceShape) totalVertexCount * OUTLINE_SEGMENT_STRIDE else 0
        currentData.vertexArray = if (isExtrude && !isSurfaceShape) {
            FloatArray(verticalIndex + positions.size * VERTICAL_SEGMENT_STRIDE)
        } else {
            FloatArray(totalVertexCount * OUTLINE_SEGMENT_STRIDE)
        }
        currentData.outlineElements.clear()
        currentData.outlineChainStarts.clear()
        currentData.verticalElements.clear()

        for (subPath in subPaths) {
            if (subPath.isEmpty()) continue
            currentData.outlineChainStarts.add(currentData.outlineElements.size)

            // Start each sub-path with a dummy vertex.
            var begin = subPath[0]
            calcPoint(rc, begin.latitude, begin.longitude, begin.altitude)
            addVertex(rc, begin.latitude, begin.longitude, begin.altitude, intermediate = true, addIndices = true)
            addVertex(rc, begin.latitude, begin.longitude, begin.altitude, intermediate = false, addIndices = true)

            for (idx in 1 until subPath.size) {
                val end = subPath[idx]
                // Sub-paths from the splitter branch are already densified — emit only the
                // explicit waypoints. The non-splitter branch (3D or short-circuited surface)
                // densifies inline.
                if (!surfaceNeedsSplitter) addIntermediateVertices(rc, begin, end)
                // Always add indices, including for the last sub-path vertex. The trailing dummy
                // vertex below is unindexed and serves only as miter context. With polar-throttled
                // densification a sub-path can carry zero intermediates (short edges or any edge
                // at the equator where throttledStep returns dt=1.0 and the loop never runs); if
                // the last waypoint were unindexed too the strip would end one segment short of
                // the path's actual end — visible as a dropped final segment, or as the entire
                // line missing for a 2-waypoint sub-path. Polygon (addOutlineChains) and Ellipse
                // (emitOutlineChain) already follow this pattern.
                calcPoint(rc, end.latitude, end.longitude, end.altitude)
                addVertex(rc, end.latitude, end.longitude, end.altitude, intermediate = false, addIndices = true)
                begin = end
            }

            // End sub-path with a dummy vertex.
            addVertex(rc, begin.latitude, begin.longitude, begin.altitude, intermediate = true, addIndices = false)
        }

        currentData.refreshVertexArray = false

        // Compute the shape's bounding box or bounding sector.
        with(currentBoundindData) {
            if (isSurfaceShape) {
                if (crossesAntimeridian) {
                    computeAntimeridianSectors(
                        currentData.vertexArray, vertexIndex, OUTLINE_SEGMENT_STRIDE, currentData.vertexOrigin
                    )
                } else {
                    this.crossesAntimeridian = false
                    boundingSector.setEmpty()
                    boundingSector.union(currentData.vertexArray, vertexIndex, OUTLINE_SEGMENT_STRIDE)
                    boundingSector.translate(currentData.vertexOrigin.y, currentData.vertexOrigin.x)
                }
                boundingBox.setToUnitBox()
            } else {
                this.crossesAntimeridian = false
                boundingBox.setToPoints(currentData.vertexArray, vertexIndex, OUTLINE_SEGMENT_STRIDE)
                boundingBox.translate(currentData.vertexOrigin.x, currentData.vertexOrigin.y, currentData.vertexOrigin.z)
                boundingSector.setEmpty()
            }
        }
    }

    /**
     * Cheap pre-check: does the path cross the antimeridian or come within
     * [POLE_PROXIMITY_DEG] of a pole? If neither, the densify-then-split pipeline is wasted
     * work — straight 2D lat/lon segments with inline densification rasterize fine for a
     * mid-latitude path.
     */
    protected open fun pathSurfaceNeedsSplitter(): Boolean {
        val n = positions.size
        if (n < 2) return false
        for (i in 0 until n) {
            val cur = positions[i]
            if (abs(cur.latitude.inDegrees) > POLE_PROXIMITY_DEG) return true
            if (i + 1 < n) {
                val next = positions[i + 1]
                if (abs(cur.longitude.inDegrees - next.longitude.inDegrees) > 180.0) return true
            }
        }
        return false
    }

    /**
     * Splits positions into sub-paths at antimeridian crossings, inserting intersection points at ±180.
     * Positions are densified with throttled great-circle samples before splitting so
     * [PolygonSplitter.meridianIntersection] (which linearly interpolates latitude) runs between
     * closely-spaced samples — otherwise a wide edge between two high-latitude endpoints dips
     * into a V-shape at the crossing because its linear-lat midpoint sits well below the true
     * great-circle intersection. Matches [Polygon.densifyBoundariesForSurface].
     */
    protected open fun splitPositionsAtAntimeridian(positions: List<Position>): Pair<List<List<Position>>, Boolean> {
        if (positions.isEmpty()) return Pair(emptyList(), false)
        val dense = densifyForSurface(positions)
        var crossesAntimeridian = false
        val subPaths = mutableListOf<List<Position>>()
        var current = mutableListOf<Position>()
        for (pos in dense) {
            if (current.isEmpty()) {
                current.add(pos)
                continue
            }
            val prev = current.last()
            if (Location.locationsCrossAntimeridian(listOf(prev, pos))) {
                crossesAntimeridian = true
                val iLat = PolygonSplitter.meridianIntersection(prev, pos, 180.0)
                    ?: (prev.latitude.inDegrees + pos.latitude.inDegrees) / 2.0
                val iLonEnd = if (prev.longitude.inDegrees > 0) 180.0 else -180.0
                current.add(Position.fromDegrees(iLat, iLonEnd, prev.altitude))
                subPaths.add(current)
                current = mutableListOf()
                current.add(Position.fromDegrees(iLat, -iLonEnd, pos.altitude))
                current.add(pos)
            } else {
                current.add(pos)
            }
        }
        if (current.isNotEmpty()) subPaths.add(current)
        return Pair(subPaths, crossesAntimeridian)
    }

    /**
     * Returns a densified copy of [positions] with throttled great-circle (or rhumb) samples
     * inserted along each edge. Used for surface paths so the antimeridian split operates on
     * closely-spaced vertices. LINEAR path type and disabled subdivision return [positions] as-is.
     *
     * Each emitted intermediate comes from [positionPool] rather than `Position(pos)` —
     * eliminates per-intermediate allocation. The output `ArrayList<Position>` is pre-sized
     * using a per-path bound: the polar-throttled cap if any vertex exceeds [POLE_PROXIMITY_DEG],
     * otherwise the much tighter unthrottled cap. Avoids the grow-resize churn `mutableListOf`
     * would do on dense paths.
     */
    protected open fun densifyForSurface(positions: List<Position>): List<Position> {
        if (maximumNumEdgeIntervals <= 0 || pathType == LINEAR || positions.size < 2) return positions
        positionPool.reset()
        val perEdgeBound = perEdgeBoundForLocations(positions)
        val out = ArrayList<Position>(positions.size + (positions.size - 1) * perEdgeBound)
        val pos = intermediatePosition
        out.add(positions[0])
        for (i in 1 until positions.size) {
            val begin = positions[i - 1]
            val end = positions[i]
            val length = when (pathType) {
                GREAT_CIRCLE -> begin.greatCircleDistance(end)
                RHUMB_LINE -> begin.rhumbDistance(end)
                else -> { out.add(end); continue }
            }
            if (length >= NEAR_ZERO_THRESHOLD) {
                val steps = (maximumNumEdgeIntervals * length / PI).roundToInt()
                if (steps > 0) {
                    val dt = 1.0 / steps
                    var currentLat = begin.latitude
                    var t = throttledStep(dt, currentLat)
                    while (t < 1.0) {
                        begin.interpolateAlongPath(end, pathType, t, pos)
                        out.add(positionPool.acquire().copy(pos))
                        currentLat = pos.latitude
                        t += throttledStep(dt, currentLat)
                    }
                }
            }
            out.add(end)
        }
        return out
    }

    /**
     * 3D-only path densification. Uses **uniform** stepping — no polar throttle — because the
     * 3D path is rendered as actual Cartesian geometry, not a 2D lat/lon raster, so the M-shape
     * distortion that the throttle compensates for on surface shapes doesn't apply. Vertex count
     * is still length-scaled via `maximumNumEdgeIntervals * length / PI` but distributed evenly
     * across the edge regardless of latitude.
     */
    protected open fun addIntermediateVertices(rc: RenderContext, begin: Position, end: Position) {
        if (maximumNumEdgeIntervals <= 0) return // subdivision disabled
        val length: Double = when (pathType) {
            GREAT_CIRCLE -> begin.greatCircleDistance(end)
            RHUMB_LINE -> begin.rhumbDistance(end)
            else -> return // LINEAR: no intermediate vertices
        }
        if (length < NEAR_ZERO_THRESHOLD) return // skip edges shorter than a millimeter on Earth
        val steps = (maximumNumEdgeIntervals * length / PI).roundToInt()
        if (steps <= 0) return
        val dt = 1.0 / steps
        val pos = intermediatePosition
        var t = dt
        while (t < 1.0) {
            begin.interpolateAlongPath(end, pathType, t, pos)
            calcPoint(rc, pos.latitude, pos.longitude, pos.altitude)
            addVertex(rc, pos.latitude, pos.longitude, pos.altitude, intermediate = true, addIndices = true)
            t += dt
        }
    }

    protected open fun addVertex(
        rc: RenderContext, latitude: Angle, longitude: Angle, altitude: Double, intermediate: Boolean, addIndices : Boolean
    ) = with(currentData) {
        val vertex = vertexIndex / VERTEX_STRIDE
        if (vertexIndex == 0) {
            if (isSurfaceShape) vertexOrigin.set(longitude.inDegrees, latitude.inDegrees, altitude)
            else vertexOrigin.copy(point)
            texCoord1d = 0.0
        } else {
            texCoord1d += point.distanceTo(prevPoint)
        }
        prevPoint.copy(point)
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
        vertexIndex = emitLineCorners(vertexArray, vertexIndex, x, y, z, texCoord1d.toFloat())
        if (addIndices) {
            outlineElements.add(vertex)
            outlineElements.add(vertex + 1)
            outlineElements.add(vertex + 2)
            outlineElements.add(vertex + 3)
        }
        if (!isSurfaceShape && isExtrude) {
            val extrudeVertex = extrudeIndex / VERTEX_STRIDE
            // Top + bottom of the side skirt — two vertices, no orientation tag (0,0).
            extrudeVertexArray[extrudeIndex++] = x
            extrudeVertexArray[extrudeIndex++] = y
            extrudeVertexArray[extrudeIndex++] = z
            extrudeVertexArray[extrudeIndex++] = 0f
            extrudeVertexArray[extrudeIndex++] = 0f
            extrudeVertexArray[extrudeIndex++] = (vertPoint.x - vertexOrigin.x).toFloat()
            extrudeVertexArray[extrudeIndex++] = (vertPoint.y - vertexOrigin.y).toFloat()
            extrudeVertexArray[extrudeIndex++] = (vertPoint.z - vertexOrigin.z).toFloat()
            extrudeVertexArray[extrudeIndex++] = 0f
            extrudeVertexArray[extrudeIndex++] = 0f
            interiorElements.add(extrudeVertex)
            interiorElements.add(extrudeVertex + 1)

            if (!intermediate) {
                val index = verticalIndex / VERTEX_STRIDE
                val vx = (vertPoint.x - vertexOrigin.x).toFloat()
                val vy = (vertPoint.y - vertexOrigin.y).toFloat()
                val vz = (vertPoint.z - vertexOrigin.z).toFloat()
                // pointA dummy + pointB top + pointB bottom + pointC dummy = 4 corner-quads.
                verticalIndex = emitLineCorners(vertexArray, verticalIndex, x, y, z, 0.0f)
                verticalIndex = emitLineCorners(vertexArray, verticalIndex, x, y, z, 0.0f)
                verticalIndex = emitLineCorners(vertexArray, verticalIndex, vx, vy, vz, 0.0f)
                verticalIndex = emitLineCorners(vertexArray, verticalIndex, vx, vy, vz, 0.0f)
                verticalElements.add(index + 2); verticalElements.add(index + 3); verticalElements.add(index + 4)
                verticalElements.add(index + 4); verticalElements.add(index + 3); verticalElements.add(index + 5)
            }
        }
    }
}