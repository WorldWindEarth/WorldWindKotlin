package earth.worldwind.shape

import earth.worldwind.draw.DrawShapeState
import earth.worldwind.draw.Drawable
import earth.worldwind.draw.DrawableShape
import earth.worldwind.draw.DrawableSurfaceShape
import earth.worldwind.geom.*
import earth.worldwind.geom.Angle.Companion.ZERO
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Angle.Companion.toDegrees
import earth.worldwind.globe.Globe
import earth.worldwind.render.RenderContext
import earth.worldwind.render.buffer.BufferObject
import earth.worldwind.render.program.TriangleShaderProgram
import earth.worldwind.util.ContourInfo
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.NumericArray
import earth.worldwind.util.Pole
import earth.worldwind.util.IntList
import earth.worldwind.util.PolygonSplitter
import earth.worldwind.util.ScratchPool
import earth.worldwind.util.glu.GLU
import earth.worldwind.util.glu.GLUtessellator
import earth.worldwind.util.glu.GLUtessellatorCallbackAdapter
import earth.worldwind.util.kgl.*
import kotlin.jvm.JvmOverloads
import kotlin.math.*

/**
 * Ellipse shape defined by a geographic center position and radii for the semi-major and semi-minor axes.
 * <br>
 * <h3>Axes and Heading</h3>
 * <br>
 * Ellipse axes, by default, are oriented such that the semi-major axis points East and the semi-minor axis points
 * North. Ellipse provides an optional heading, which when set to anything other than 0.0 rotates the semi-major and
 * semi-minor axes about the center position, while retaining the axes relative relationship to one another. Heading is
 * defined clockwise from North. Configuring ellipse with a heading of 45.0 results in the semi-major axis
 * pointing Southeast and the semi-minor axis pointing Northeast.
 * <br>
 * <h3>Altitude Mode and Terrain Following</h3>
 * <br>
 * Ellipse geometry displays at a constant altitude determined by the geographic center position and altitude mode. For
 * example, an ellipse with a center position altitude of 1km and altitude mode of ABSOLUTE displays at 1km above mean
 * sea level. The same ellipse with an altitude mode of RELATIVE_TO_GROUND displays at 1km above ground level, relative
 * to the ellipse's center location.
 * <br>
 * Surface ellipse geometry, where an ellipse appears draped across the terrain, may be achieved by enabling ellipse's
 * terrain following state and setting its altitude mode to CLAMP_TO_GROUND. See [isFollowTerrain] and
 * [altitudeMode].
 * <br>
 * <h3>Display Granularity</h3>
 * <br>
 * Ellipse's appearance on screen is composed of discrete segments which approximate the ellipse's geometry. This
 * approximation is chosen such that the display appears to be a continuous smooth ellipse. Applications can control the
 * maximum number of angular intervals used in this representation with [maximumIntervals].
 */
open class Ellipse @JvmOverloads constructor(
    center: Position, majorRadius: Double, minorRadius: Double, attributes: ShapeAttributes = ShapeAttributes()
): AbstractShape(attributes) {
    override val referencePosition get() = center
    /**
     * The ellipse's geographic center position.
     */
    var center = Position(center)
        set(value) {
            field.copy(value)
            reset()
        }
    /**
     * The ellipse's radius perpendicular to it's heading, in meters.
     * When the ellipse's heading is 0.0, the semi-major axis points East.
     *
     * @throws IllegalArgumentException If the radius is negative
     */
    var majorRadius = majorRadius
        set(value) {
            require(value >= 0) {
                logMessage(ERROR, "Ellipse", "setMajorRadius", "invalidRadius")
            }
            field = value
            reset()
        }
    /**
     * The ellipse's radius parallel to it's heading, in meters.
     * When the ellipse's heading is 0.0, the semi-minor axis points North.
     *
     * @throws IllegalArgumentException If the radius is negative
     */
    var minorRadius = minorRadius
        set(value) {
            require(value >= 0) {
                logMessage(ERROR, "Ellipse", "setMinorRadius", "invalidRadius")
            }
            field = value
            reset()
        }
    /**
     * The ellipse's heading clockwise from North. When ellipse's heading is 0.0,
     * the semi-major axis points East and the semi-minor axis points North.
     * Headings other than 0.0 rotate the axes about the ellipse's center position,
     * while retaining the axes relative relationship to one another.
     */
    var heading = ZERO
        set(value) {
            field = value
            reset()
        }
    /**
     * The maximum pixels a single edge interval will span before the number of intervals is increased. Increasing this
     * value will make ellipses appear coarser.
     */
    var maximumPixelsPerInterval = 50.0
        set(value) {
            require(value >= 0) {
                logMessage(ERROR, "Ellipse", "maximumPixelsPerInterval", "invalidPixelsPerInterval")
            }
            field = value
            reset()
        }
    /**
     * Sets the maximum number of angular intervals that may be used to approximate this ellipse's on screen.
     * <br>
     * Ellipse may use a minimum number of intervals to ensure that its appearance on screen at least roughly
     * approximates the ellipse's shape. When the specified number of intervals is too small, it is clamped to an
     * implementation-defined minimum number of intervals.
     * <br>
     * Ellipse may require that the number of intervals is an even multiple of some integer. When the specified number
     * of intervals does not meet this criteria, the next smallest integer that meets ellipse's criteria is used
     * instead.
     *
     * @throws IllegalArgumentException If the number of intervals is negative
     */
    var maximumIntervals = 256
        set(value) {
            require(value >= 0) {
                logMessage(ERROR, "Ellipse", "setMaximumIntervals", "invalidNumIntervals")
            }
            field = value
            reset()
        }
    /**
     * The number of intervals used for generating geometry. Clamped between MIN_INTERVALS and maximumIntervals.
     * Will always be even.
     */
    protected var activeIntervals = 0
    protected val data = mutableMapOf<Pair<Globe.State?, Int>, EllipseData>()

    init {
        require(majorRadius >= 0 && minorRadius >= 0) {
            logMessage(ERROR, "Ellipse", "constructor", "invalidRadius")
        }
    }

    open class EllipseData {
        val vertexOrigin = Vec3()
        var vertexArray = FloatArray(0)
        var lineVertexArray = FloatArray(0)
        // Primitive-int element buffers — see PolygonData for rationale.
        val verticalElements = IntList()
        val outlineElements = IntList()
        // Start index in outlineElements for each separate outline chain (used to make per-chain draw calls
        // and avoid the spurious connecting triangle between antimeridian-split chains).
        val outlineChainStarts = IntList()
        // GLU-tessellated top-face triangle indices for surface shapes. Populated via the tessellator
        // vertex callback when assembling surface geometry; drawn as GL_TRIANGLES with GL_UNSIGNED_INT.
        val topElements = IntList()
        val vertexBufferKey = Any()
        val lineVertexBufferKey = Any()
        val lineElementBufferKey = Any()
        // Per-ellipse Int-indexed element buffer for the GLU-tessellated surface fill. The shared
        // per-intervals Short-indexed buffer is still used for the non-surface parametric strip.
        val tessElementBufferKey = Any()
        var refreshVertexArray = true
        var refreshLineVertexArray = true
    }

    protected val tessCallback = object : GLUtessellatorCallbackAdapter() {
        override fun combineData(
            coords: DoubleArray, data: Array<Any?>, weight: FloatArray, outData: Array<Any?>, polygonData: Any?
        ) = tessCombine(polygonData as RenderContext, coords, data, weight, outData)

        override fun vertexData(vertexData: Any?, polygonData: Any?) = tessVertex(polygonData as RenderContext, vertexData)

        override fun edgeFlagData(boundaryEdge: Boolean, polygonData: Any?) = tessEdgeFlag(polygonData as RenderContext, boundaryEdge)

        override fun errorData(errnum: Int, polygonData: Any?) = tessError(polygonData as RenderContext, errnum)
    }

    companion object {
        protected const val VERTEX_STRIDE = 5
        protected const val OUTLINE_LINE_SEGMENT_STRIDE = 4 * VERTEX_STRIDE
        protected const val VERTICAL_LINE_SEGMENT_STRIDE = 4 * OUTLINE_LINE_SEGMENT_STRIDE // 4 points per 4 vertices per vertical line
        /**
         * The minimum number of intervals that will be used for geometry generation.
         */
        protected const val MIN_INTERVALS = 32
        /**
         * Key for the Range object in the element buffer describing the top of the Ellipse.
         */
        protected const val TOP_RANGE = 0
        /**
         * Key for the Range object in the element buffer describing the extruded sides of the Ellipse.
         */
        protected const val SIDE_RANGE = 1
        /**
         * Key for the Range object in the element buffer describing the bottom of the Ellipse.
         */
        protected const val BASE_RANGE = 2

        /**
         * Maximum longitudinal span (in degrees) of a pole-top edge before GLU tessellation
         * produces degenerate / missing triangles. When [PolygonSplitter.handleOnePole] inserts
         * pole vertices at (±90°, ±180°), the edge connecting them spans 360° in the tessellator's
         * 2D space. Breaking it into pieces no wider than this threshold gives GLU enough extra
         * vertices to cleanly tessellate the region between the pole edge and nearby perimeter
         * vertices.
         */
        protected const val POLE_EDGE_MAX_SPAN_DEGREES = 5.0

        /**
         * Simple interval count based cache of the keys for element buffers. Element buffers are dependent only on the
         * number of intervals so the keys are cached here. The element buffer object itself is in the
         * RenderResourceCache and subject to the restrictions and behavior of that cache.
         */
        protected val elementBufferKeys = mutableMapOf<Int, Any>()

        protected lateinit var currentData: EllipseData

        protected var vertexIndex = 0
        protected var lineVertexIndex = 0
        protected var verticalVertexIndex = 0

        protected val prevPoint = Vec3()
        protected val texCoord2d = Vec3()
        protected val modelToTexCoord = Matrix4()
        protected val scratchLocation = Location()
        protected var texCoord1d = 0.0
        // Sequential scratch pool for [densifyEdgeWithFloor] — eliminates per-intermediate
        // [Location] allocation on dense polar perimeters.
        protected val locationPool = ScratchPool { Location() }

        // GLU tessellator scratch state — single-threaded assembly, shared across tessellation calls.
        protected val tessCoords = DoubleArray(3)
        protected val tessVertices = IntArray(3)
        protected val tessEdgeFlags = BooleanArray(3)
        protected var tessEdgeFlag = true
        protected var tessVertexCount = 0

        protected fun assembleElements(intervals: Int, elementBuffer: BufferObject): ShortArray {
            // Pre-allocate: top = 2*intervals, side = 2*intervals+2, base = 2*intervals → total = 6*intervals+2
            val elements = ShortArray(6 * intervals + 2)
            var pos = 0

            // Generate the top element buffer with spine
            var idx = intervals.toShort()
            val offset = computeIndexOffset(intervals)

            // Add the anchor leg
            elements[pos++] = 0
            elements[pos++] = 1
            // Tessellate the interior
            for (i in 2 until intervals) {
                // Add the corresponding interior spine point if this isn't the vertex following the last vertex for the
                // negative major axis
                if (i != intervals / 2 + 1) if (i > intervals / 2) elements[pos++] = --idx else elements[pos++] = idx++
                // Add the degenerate triangle at the negative major axis in order to flip the triangle strip back towards
                // the positive axis
                if (i == intervals / 2) elements[pos++] = i.toShort()
                // Add the exterior vertex
                elements[pos++] = i.toShort()
            }
            // Complete the strip
            elements[pos++] = --idx
            elements[pos++] = 0
            val topRange = Range(0, pos)

            // Generate the side element buffer
            for (i in 0 until intervals) {
                elements[pos++] = i.toShort()
                elements[pos++] = i.plus(offset).toShort()
            }
            elements[pos++] = 0
            elements[pos++] = offset.toShort()
            val sideRange = Range(topRange.upper, pos)

            idx = intervals.plus(offset).toShort()

            // Add the anchor leg
            elements[pos++] = offset.toShort()
            elements[pos++] = 1.plus(offset).toShort()
            // Tessellate the interior
            for (i in intervals - 1 downTo 2) {
                // Add the corresponding interior spine point if this isn't the vertex following the last vertex for the
                // negative major axis
                if (i != intervals / 2 + 1) if (i > intervals / 2) elements[pos++] = --idx else elements[pos++] = idx++
                // Add the degenerate triangle at the negative major axis in order to flip the triangle strip back towards
                // the positive axis
                if (i == intervals / 2) elements[pos++] = i.plus(offset).toShort()
                // Add the exterior vertex
                elements[pos++] = i.plus(offset).toShort()
            }
            // Complete the strip
            elements[pos++] = --idx
            elements[pos++] = offset.toShort()
            val baseRange = Range(sideRange.upper, pos)

            // Generate a buffer for the element
            elementBuffer.ranges = arrayOf(topRange, sideRange, baseRange)
            return elements
        }

        protected fun computeNumberSpinePoints(intervals: Int) = intervals / 2 - 1 // intervals should be even

        protected fun computeIndexOffset(intervals: Int) = intervals + computeNumberSpinePoints(intervals)
    }

    override fun resetGlobeState(globeState: Globe.State?) {
        super.resetGlobeState(globeState)
        data.entries.forEach {
            if (it.key.first == globeState) {
                it.value.refreshVertexArray = true
                it.value.refreshLineVertexArray = true
            }
        }
    }

    override fun reset() {
        super.reset()
        data.values.forEach {
            it.refreshVertexArray = true
            it.refreshLineVertexArray = true
        }
    }

    override fun moveTo(globe: Globe, position: Position) {
        center.copy(position)
        reset()
    }

    override fun makeDrawable(rc: RenderContext) {
        if (majorRadius == 0.0 && minorRadius == 0.0) return  // nothing to draw

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
            drawable.offset = rc.globe.offset
            drawable.sector.copy(currentBoundindData.boundingSector)
            drawable.version = computeVersion()
            drawable.isDynamic = isDynamic || rc.currentLayer.isDynamic

            drawableLines = DrawableSurfaceShape.obtain(pool)
            drawStateLines = drawableLines.drawState

            // Use the basic GLSL program for texture projection.
            drawableLines.offset = rc.globe.offset
            drawableLines.sector.copy(currentBoundindData.boundingSector)
            drawableLines.version = computeVersion()
            drawableLines.isDynamic = isDynamic || rc.currentLayer.isDynamic

            cameraDistanceSq = 0.0 // Not used by surface shape
            cameraDistance = cameraDistanceForTexture(rc, currentBoundindData.boundingSector)
        } else {
            val pool = rc.getDrawablePool(DrawableShape.KEY)
            drawable = DrawableShape.obtain(pool)
            drawState = drawable.drawState

            drawableLines = DrawableShape.obtain(pool)
            drawStateLines = drawableLines.drawState

            cameraDistanceSq = currentBoundindData.boundingBox.distanceToSquared(rc.cameraPoint)
            cameraDistance = sqrtCameraDistanceForTexture(cameraDistanceSq)
        }

        // Use the basic GLSL program to draw the shape.
        drawState.program = rc.getShaderProgram(TriangleShaderProgram.KEY) { TriangleShaderProgram() }

        // Assemble the drawable's OpenGL vertex buffer object.
        drawState.vertexBuffer = rc.getBufferObject(currentData.vertexBufferKey) { BufferObject(GL_ARRAY_BUFFER, 0) }
        rc.offerGLBufferUpload(currentData.vertexBufferKey, bufferDataVersion) { NumericArray.Floats(currentData.vertexArray) }

        // Element buffer. Surface shapes use an Int-indexed, per-ellipse buffer produced by GLU
        // tessellation on the split perimeter. Non-surface shapes use the shared per-intervals
        // Short-indexed parametric strip buffer (top + side + base ranges).
        if (isSurfaceShape) {
            drawState.elementBuffer = rc.getBufferObject(currentData.tessElementBufferKey) {
                BufferObject(GL_ELEMENT_ARRAY_BUFFER, 0)
            }
            rc.offerGLBufferUpload(currentData.tessElementBufferKey, bufferDataVersion) {
                NumericArray.Ints(currentData.topElements.toIntArray())
            }
        } else {
            val elementBufferKey = elementBufferKeys[activeIntervals] ?: Any().also { elementBufferKeys[activeIntervals] = it }
            val elementBuffer = rc.getBufferObject(elementBufferKey) {
                BufferObject(GL_ELEMENT_ARRAY_BUFFER, 0)
            }.also { drawState.elementBuffer = it }
            rc.offerGLBufferUpload(elementBufferKey, 1) {
                NumericArray.Shorts(assembleElements(activeIntervals, elementBuffer))
            }
        }

        drawStateLines.isLine = true

        // Use the basic GLSL program to draw the shape.
        drawStateLines.program = rc.getShaderProgram(TriangleShaderProgram.KEY) { TriangleShaderProgram() }

        // Assemble the drawable's OpenGL vertex buffer object.
        drawStateLines.vertexBuffer = rc.getBufferObject(currentData.lineVertexBufferKey) { BufferObject(GL_ARRAY_BUFFER, 0) }
        rc.offerGLBufferUpload(currentData.lineVertexBufferKey, bufferDataVersion) { NumericArray.Floats(currentData.lineVertexArray) }

        // Assemble the drawable's OpenGL element buffer object.
        drawStateLines.elementBuffer = rc.getBufferObject(currentData.lineElementBufferKey) {
            BufferObject(GL_ELEMENT_ARRAY_BUFFER, 0)
        }
        rc.offerGLBufferUpload(currentData.lineElementBufferKey, bufferDataVersion) {
            val array = IntArray(currentData.outlineElements.size + currentData.verticalElements.size)
            val index = currentData.outlineElements.copyTo(array, 0)
            currentData.verticalElements.copyTo(array, index)
            NumericArray.Ints(array)
        }

        drawInterior(rc, drawState, cameraDistance)
        drawOutline(rc, drawStateLines, cameraDistance)

        // Configure the drawable according to the shape's attributes.
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
        if (isSurfaceShape) {
            rc.offerSurfaceDrawable(drawable, zOrder)
            rc.offerSurfaceDrawable(drawableLines, zOrder)
            // For antimeridian-crossing ellipses, enqueue secondary drawables covering the other
            // hemisphere. Because vertex offsets are raw (not normalized), both primary and
            // secondary drawables can share the same vertexOrigin — each tile renders the
            // triangles at their correct world positions and clips to its own sector.
            if (currentBoundindData.crossesAntimeridian) {
                val pool = rc.getDrawablePool(DrawableSurfaceShape.KEY)
                val version = computeVersion(); val dynamic = isDynamic || rc.currentLayer.isDynamic
                DrawableSurfaceShape.obtain(pool).also { d ->
                    d.offset = rc.globe.offset; d.sector.copy(currentBoundindData.additionalSector)
                    d.version = version; d.isDynamic = dynamic; d.drawState.copy(drawState)
                    rc.offerSurfaceDrawable(d, zOrder)
                }
                DrawableSurfaceShape.obtain(pool).also { d ->
                    d.offset = rc.globe.offset; d.sector.copy(currentBoundindData.additionalSector)
                    d.version = version; d.isDynamic = dynamic; d.drawState.copy(drawStateLines)
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

        // Configure the drawable to display the shape's interior.
        drawState.color.copy(if (rc.isPickMode) pickColor else activeAttributes.interiorColor)
        drawState.opacity = if (rc.isPickMode) 1f else rc.currentLayer.opacity
        drawState.texCoordAttrib.size = 2
        drawState.texCoordAttrib.offset = 12
        if (isSurfaceShape) {
            // Surface shapes draw GLU-tessellated triangles from the Int-indexed element buffer.
            drawState.drawElements(GL_TRIANGLES, currentData.topElements.size, GL_UNSIGNED_INT, offset = 0)
        } else {
            val ranges = drawState.elementBuffer!!.ranges!!
            val top = ranges[TOP_RANGE]
            drawState.drawElements(GL_TRIANGLE_STRIP, top.length, GL_UNSIGNED_SHORT, top.lower * Short.SIZE_BYTES)
            if (isExtrude) {
                val side = ranges[SIDE_RANGE]
                drawState.texture = null
                drawState.drawElements(GL_TRIANGLE_STRIP, side.length, GL_UNSIGNED_SHORT, side.lower * Short.SIZE_BYTES)
                if (baseAltitude != 0.0) {
                    val base = ranges[BASE_RANGE]
                    drawState.drawElements(GL_TRIANGLE_STRIP, base.length, GL_UNSIGNED_SHORT, base.lower * Short.SIZE_BYTES)
                }
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
        // Draw each outline chain as a separate strip to avoid the spurious connecting triangle
        // that would appear between antimeridian-split chains if drawn as one continuous strip.
        val chainStarts = currentData.outlineChainStarts
        val chainCount = chainStarts.size
        for (ci in 0 until chainCount) {
            val start = chainStarts[ci]
            val end = if (ci + 1 < chainCount) chainStarts[ci + 1] else currentData.outlineElements.size
            val count = end - start
            if (count > 0) drawState.drawElements(GL_TRIANGLE_STRIP, count, GL_UNSIGNED_INT, start * Int.SIZE_BYTES)
        }
        if (activeAttributes.isDrawVerticals && isExtrude && !isSurfaceShape && (!rc.isPickMode || activeAttributes.isPickOutline)) {
            drawState.texture = null
            drawState.drawElements(
                GL_TRIANGLES, currentData.verticalElements.size, GL_UNSIGNED_INT, currentData.outlineElements.size * Int.SIZE_BYTES
            )
        }
    }

    protected open fun mustAssembleGeometry(rc: RenderContext): Boolean {
        val calculatedIntervals = computeIntervals(rc)
        activeIntervals = sanitizeIntervals(calculatedIntervals)
        val dataKey = rc.globeState to activeIntervals
        currentData = data[dataKey] ?: EllipseData().also { data[dataKey] = it }
        return currentData.refreshVertexArray || isExtrude && !isSurfaceShape && currentData.refreshLineVertexArray
    }

    protected open fun assembleGeometry(rc: RenderContext) {
        // Compute a matrix that transforms from Cartesian coordinates to shape texture coordinates.
        determineModelToTexCoord(rc)

        // Use the ellipse's center position as the local origin for vertex positions.
        if (isSurfaceShape) {
            currentData.vertexOrigin.set(center.longitude.inDegrees, center.latitude.inDegrees, center.altitude)
        } else {
            rc.geographicToCartesian(center, altitudeMode, point)
            currentData.vertexOrigin.copy(point)
        }

        // Determine the number of spine points
        val spineCount = computeNumberSpinePoints(activeIntervals) // activeIntervals must be even

        // Clear the shape's vertex array. Surface shapes skip the spine (GLU produces triangles
        // directly) but reserve slack for antimeridian/pole detour vertex insertions; tessCombine
        // and pole-edge subdivision may call ensureVertexArrayCapacity to grow it further.
        vertexIndex = 0
        currentData.vertexArray = if (isExtrude && !isSurfaceShape) {
            FloatArray((activeIntervals * 2 + spineCount) * VERTEX_STRIDE)
        } else if (!isSurfaceShape) {
            FloatArray((activeIntervals + spineCount) * VERTEX_STRIDE)
        } else {
            FloatArray((activeIntervals + 8) * VERTEX_STRIDE)
        }
        lineVertexIndex = 0

        currentData.verticalElements.clear()
        currentData.outlineElements.clear()
        currentData.outlineChainStarts.clear()
        currentData.topElements.clear()

        // Check if minor radius is less than major in which case we need to flip the definitions and change the phase
        val isStandardAxisOrientation = majorRadius > minorRadius
        val headingAdjustment = if (isStandardAxisOrientation) 90.0 else 0.0

        // Vertex generation begins on the positive major axis and works ccs around the ellipse. The spine points are
        // then appended from positive major axis to negative major axis.
        val deltaRadians = 2 * PI / activeIntervals
        val majorArcRadians: Double
        val minorArcRadians: Double
        val globeRadius = max(rc.globe.equatorialRadius, rc.globe.polarRadius)
        if (isStandardAxisOrientation) {
            majorArcRadians = majorRadius / globeRadius
            minorArcRadians = minorRadius / globeRadius
        } else {
            majorArcRadians = minorRadius / globeRadius
            minorArcRadians = majorRadius / globeRadius
        }

        // Determine the offset from the top and extruded vertices
        val arrayOffset = computeIndexOffset(activeIntervals) * VERTEX_STRIDE
        // Setup spine radius values
        var spineIdx = 0
        val spineRadius = DoubleArray(spineCount)

        // Walk the ellipse perimeter. Non-surface shapes write perimeter vertices to the vertex array
        // (the parametric strip uses them). Surface shapes collect locations only and fill the vertex
        // array from the split sub-polygons below, so each GLU-tessellated triangle references the
        // correct (possibly split) vertex index.
        val perimeterLocs = mutableListOf<Location>()
        for (i in 0 until activeIntervals) {
            val radians = deltaRadians * i
            val x = cos(radians) * majorArcRadians
            val y = sin(radians) * minorArcRadians
            val azimuthDegrees = toDegrees(-atan2(y, x))
            val arcRadius = sqrt(x * x + y * y)
            val azimuth = heading.plusDegrees(azimuthDegrees + headingAdjustment)
            val loc = center.greatCircleLocation(azimuth, arcRadius, scratchLocation)
            if (!isSurfaceShape) {
                calcPoint(rc, loc.latitude, loc.longitude, center.altitude)
                addVertex(rc, loc.latitude, loc.longitude, center.altitude, arrayOffset, isExtrude)
            }
            if (i > 0 && i < activeIntervals / 2) spineRadius[spineIdx++] = x
            perimeterLocs.add(Location(loc))
        }

        // For surface shapes, densify the parametric perimeter with throttled great-circle samples
        // before splitting — but only when the ellipse actually crosses ±180° or comes near a
        // pole. Mid-latitude, non-crossing ellipses fall through with the raw parametric perimeter
        // (already 256 samples by default, plenty dense for 2D rasterization away from the
        // poles). Skips densifyPerimeter, sanitizePoleAntimeridianVertices, and the splitter walk
        // for the common case.
        val ellipseNeedsSplitter = isSurfaceShape && ellipseSurfaceNeedsSplitter(perimeterLocs)
        val splitInfo: ContourInfo? = if (ellipseNeedsSplitter) {
            val surfacePerimeter = sanitizePoleAntimeridianVertices(densifyPerimeter(perimeterLocs))
            PolygonSplitter.splitContours(listOf(surfacePerimeter)).second[0]
        } else null
        val surfacePolygons: List<List<Location>> =
            splitInfo?.polygons ?: if (isSurfaceShape) listOf(perimeterLocs) else emptyList()

        // Size the line vertex array now that the final perimeter length is known. Add slack
        // for chain splits at the pole + antimeridian (up to 2 chains × 4 pre/post dummies = 8).
        val totalSurfaceVertices = surfacePolygons.sumOf { it.size }
        val lineVertexSlots = if (isSurfaceShape) totalSurfaceVertices + 12 else activeIntervals + 12
        verticalVertexIndex = lineVertexSlots * OUTLINE_LINE_SEGMENT_STRIDE
        currentData.lineVertexArray = if (isExtrude && !isSurfaceShape) {
            FloatArray(lineVertexSlots * OUTLINE_LINE_SEGMENT_STRIDE + (activeIntervals + 1) * VERTICAL_LINE_SEGMENT_STRIDE)
        } else {
            FloatArray(lineVertexSlots * OUTLINE_LINE_SEGMENT_STRIDE)
        }

        if (isSurfaceShape) fillSurfaceViaGlu(
            rc, surfacePolygons, splitInfo?.pole ?: Pole.NONE, splitInfo?.poleIndex ?: -1
        )

        // Build outline chains. Surface shapes reuse the split sub-polygons. For pole-enclosing
        // shapes (single sub-polygon with pole-detour vertices), we emit MULTIPLE chains by
        // breaking at the pole vertices — mirrors Polygon.addOutlineChains. This keeps the outline
        // tracing only the actual perimeter and avoids drawing spurious lines up to ±90° along the
        // antimeridian (which would also then show visible ±180° horizontal segments on 2D tiles).
        if (isSurfaceShape && splitInfo != null) {
            val isPoleEnclosing = splitInfo.pole != Pole.NONE
            val isSimple = surfacePolygons.size == 1 && !isPoleEnclosing
            for ((polyIdx, subPoly) in surfacePolygons.withIndex()) {
                if (subPoly.isEmpty()) continue
                val isPolePoly = polyIdx == splitInfo.poleIndex && isPoleEnclosing
                // For pole sub-polygons, break the outline at the inserted pole vertices so we
                // don't draw the detour to ±90°. Antimeridian intersections stay in the chain so
                // the outline reaches ±180° cleanly. closeLoop only applies to a lone simple
                // sub-polygon with no breaks.
                emitOutlineChains(rc, subPoly, closeLoopIfUnbroken = isSimple) { loc ->
                    isPolePoly && abs(loc.latitude.inDegrees) >= 90.0 - 1.0e-9
                }
            }
        } else {
            currentData.outlineChainStarts.add(0)
            val firstLoc = if (perimeterLocs.isNotEmpty()) perimeterLocs[0] else null
            var firstPoint: Vec3? = null
            for (i in 0 until activeIntervals) {
                val loc = perimeterLocs[i]
                calcPoint(rc, loc.latitude, loc.longitude, center.altitude)
                if (i < 1) {
                    firstPoint = Vec3(point)
                    addLineVertex(rc, loc.latitude, loc.longitude, center.altitude, verticalVertexIndex, addIndices = true)
                }
                addLineVertex(rc, loc.latitude, loc.longitude, center.altitude, verticalVertexIndex, addIndices = true)
            }
            if (firstLoc != null && firstPoint != null) {
                point.copy(firstPoint)
                addLineVertex(rc, firstLoc.latitude, firstLoc.longitude, center.altitude, verticalVertexIndex, addIndices = false)
                addLineVertex(rc, firstLoc.latitude, firstLoc.longitude, center.altitude, verticalVertexIndex, addIndices = false)
            }
        }

        // Spine points are only needed for the non-surface parametric triangle strip.
        if (!isSurfaceShape) {
            for (i in 0 until spineCount) {
                center.greatCircleLocation(heading.plusDegrees(headingAdjustment), spineRadius[i], scratchLocation)
                calcPoint(rc, scratchLocation.latitude, scratchLocation.longitude, center.altitude, isExtrudedSkirt = false)
                addVertex(rc, scratchLocation.latitude, scratchLocation.longitude, center.altitude, arrayOffset, isExtrudedSkirt = false)
            }
        }

        // Reset update flags
        currentData.refreshVertexArray = false
        currentData.refreshLineVertexArray = false

        // Compute the shape's bounding sector from its assembled coordinates.
        with(currentBoundindData) {
            if (isSurfaceShape) {
                val count = vertexIndex
                val origin = currentData.vertexOrigin
                // Pole-enclosing polygons span all longitudes and must reach ±90° latitude — they
                // always cross the antimeridian. Non-pole shapes delegate the decision to whether
                // their perimeter has sign flips >180° apart.
                val crosses = splitInfo != null &&
                    (splitInfo.pole != Pole.NONE || Location.locationsCrossAntimeridian(perimeterLocs))
                if (crosses) {
                    computeAntimeridianSectors(
                        currentData.vertexArray, count, VERTEX_STRIDE, origin, normalizeLon = true
                    )
                } else {
                    crossesAntimeridian = false
                    boundingSector.setEmpty()
                    boundingSector.union(currentData.vertexArray, count, VERTEX_STRIDE)
                    boundingSector.translate(origin.y, origin.x)
                }
                boundingBox.setToUnitBox()
            } else {
                crossesAntimeridian = false
                boundingBox.setToPoints(currentData.vertexArray, currentData.vertexArray.size, VERTEX_STRIDE)
                boundingBox.translate(currentData.vertexOrigin.x, currentData.vertexOrigin.y, currentData.vertexOrigin.z)
                boundingSector.setEmpty()
            }
        }
    }

    /**
     * Fills a surface ellipse via GLU tessellation of the split sub-polygons. For pole
     * sub-polygons, the long pole-top edge between the two pole-detour vertices
     * (inserted by [PolygonSplitter.handleOnePole] at (±90°, ±180°)) is subdivided so GLU has
     * enough intermediate vertices to cleanly triangulate the region near the pole — without
     * subdivision GLU produces a V-shaped gap between the pole edge and the nearest perimeter
     * vertex.
     */
    protected open fun fillSurfaceViaGlu(rc: RenderContext, polygons: List<List<Location>>, pole: Pole, poleIndex: Int) {
        val tess = rc.tessellator
        GLU.gluTessNormal(tess, 0.0, 0.0, 1.0)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_COMBINE_DATA, tessCallback)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_VERTEX_DATA, tessCallback)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_EDGE_FLAG_DATA, tessCallback)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_ERROR_DATA, tessCallback)
        GLU.gluTessBeginPolygon(tess, rc)
        for ((polyIdx, subPoly) in polygons.withIndex()) {
            if (subPoly.isEmpty()) continue
            val isPolePoly = polyIdx == poleIndex && pole != Pole.NONE
            GLU.gluTessBeginContour(tess)
            for (k in subPoly.indices) {
                val curr = subPoly[k]
                // Subdivide the pole-top edge where both endpoints sit exactly at ±90°.
                if (isPolePoly && k > 0) {
                    val prev = subPoly[k - 1]
                    if (abs(prev.latitude.inDegrees) >= 90.0 - 1.0e-9 &&
                        abs(curr.latitude.inDegrees) >= 90.0 - 1.0e-9) {
                        val lonStart = prev.longitude.inDegrees
                        val lonEnd = curr.longitude.inDegrees
                        val span = abs(lonEnd - lonStart)
                        if (span > POLE_EDGE_MAX_SPAN_DEGREES) {
                            val steps = ceil(span / POLE_EDGE_MAX_SPAN_DEGREES).toInt()
                            val poleLat = prev.latitude
                            for (s in 1 until steps) {
                                val t = s.toDouble() / steps
                                val lon = (lonStart + (lonEnd - lonStart) * t).degrees
                                emitGluVertex(tess, rc, poleLat, lon)
                            }
                        }
                    }
                }
                emitGluVertex(tess, rc, curr.latitude, curr.longitude)
            }
            GLU.gluTessEndContour(tess)
        }
        GLU.gluTessEndPolygon(tess)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_COMBINE_DATA, null)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_VERTEX_DATA, null)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_EDGE_FLAG_DATA, null)
        GLU.gluTessCallback(tess, GLU.GLU_TESS_ERROR_DATA, null)
    }

    /**
     * Returns a densified copy of the ellipse perimeter with throttled great-circle samples along
     * each edge (including the wrap-around from last vertex to first). The parametric perimeter
     * has up to [maximumIntervals] vertices, but each parametric edge — even ~1.4° wide at the
     * default 256 — would otherwise rasterize as a 2D straight segment, falling visibly below
     * the true great-circle near the poles. Densifying inserts intermediates that follow the
     * great-circle. Matches [Polygon.densifyBoundariesForSurface].
     */
    /**
     * Cheap pre-check: does the parametric perimeter cross the antimeridian or come within
     * [POLE_PROXIMITY_DEG] of a pole? If neither, the densify-then-split pipeline (and the
     * sanitize+split pass that goes with it) is wasted work — the 256-sample parametric
     * perimeter rasterizes fine as 2D lat/lon segments for a mid-latitude ellipse.
     */
    protected open fun ellipseSurfaceNeedsSplitter(perimeter: List<Location>): Boolean {
        val n = perimeter.size
        if (n < 2) return false
        for (i in 0 until n) {
            val cur = perimeter[i]
            if (abs(cur.latitude.inDegrees) > POLE_PROXIMITY_DEG) return true
            val next = perimeter[(i + 1) % n]
            if (abs(cur.longitude.inDegrees - next.longitude.inDegrees) > 180.0) return true
        }
        return false
    }

    protected open fun densifyPerimeter(perimeter: List<Location>): List<Location> {
        if (maximumNumEdgeIntervals <= 0 || pathType == PathType.LINEAR || perimeter.size < 2) return perimeter
        locationPool.reset()
        val n = perimeter.size
        // Pre-size with a per-perimeter bound: the polar-throttled cap if the parametric perimeter
        // reaches above [POLE_PROXIMITY_DEG], otherwise the unthrottled cap. Avoids the
        // grow-resize churn `mutableListOf` would do.
        val perEdgeBound = perEdgeBoundForLocations(perimeter)
        val out = ArrayList<Location>(n + n * perEdgeBound)
        for (i in 0 until n) {
            val begin = perimeter[i]
            out.add(begin)
            val end = perimeter[(i + 1) % n]
            if (begin.latitude == end.latitude && begin.longitude == end.longitude) continue
            densifyEdgeWithFloor(begin, end, out)
        }
        return out
    }

    /**
     * Like [AbstractShape.densifyEdge] but floors the step count at 1 so the polar-throttle loop
     * runs at least once even for very short edges. The default 128-interval threshold rounds an
     * edge of less than ~1.4° to zero steps and skips densification — fine for user-defined Path
     * / Polygon waypoints (typically much wider apart) but fatal for the ellipse's parametric
     * perimeter, where every edge is exactly that wide and would render as a 2D straight segment
     * that visibly diverges from the great-circle near the poles (the M-shape).
     *
     * Each emitted intermediate comes from [locationPool] rather than `Location()` —
     * eliminates per-intermediate allocation on dense polar perimeters.
     */
    private fun densifyEdgeWithFloor(begin: Location, end: Location, out: MutableList<Location>) {
        if (maximumNumEdgeIntervals <= 0) return
        val length = when (pathType) {
            PathType.GREAT_CIRCLE -> begin.greatCircleDistance(end)
            PathType.RHUMB_LINE -> begin.rhumbDistance(end)
            else -> return
        }
        if (length < NEAR_ZERO_THRESHOLD) return
        val steps = (maximumNumEdgeIntervals * length / PI).roundToInt().coerceAtLeast(1)
        val dt = 1.0 / steps
        var currentLat = begin.latitude
        var t = throttledStep(dt, currentLat)
        while (t < 1.0) {
            val loc = locationPool.acquire()
            begin.interpolateAlongPath(end, pathType, t, loc)
            out.add(loc)
            currentLat = loc.latitude
            t += throttledStep(dt, currentLat)
        }
    }

    /**
     * Returns a copy of [perimeter] with two defensive cleanups applied before the contour is
     * fed to [PolygonSplitter]:
     *
     * 1. **Pole clusters collapsed.** When the ellipse's top/bottom passes close to ±90°,
     *    `greatCircleLocation`'s `asin(sinLat·cosD + cosLat·sinD·cosAz)` can pin the result to
     *    exactly ±π/2 for a range of parametric inputs (FP rounding). Those clumped vertices
     *    all sit at the same pole on the sphere but at varying longitudes in 2D, producing a
     *    horizontal cap at lat=±90° when rasterized as 2D lat/lon triangles — visible as the
     *    M-shape distortion when the pole sits on the outline. Keep only the first vertex of
     *    each contiguous pole-cluster.
     * 2. **Antimeridian nudged.** Vertices whose longitude is exactly ±180° coincide with the
     *    intersection points that [PolygonSplitter.findIntersectionAndPole] inserts when
     *    handling an antimeridian crossing, producing duplicate vertices at the same index.
     *    Nudge to ±179.999999 to break the tie without visibly shifting the outline.
     */
    protected open fun sanitizePoleAntimeridianVertices(perimeter: List<Location>): List<Location> {
        val poleEps = 1.0e-9
        val lonEps = 1.0e-6
        val out = mutableListOf<Location>()
        var lastWasPole = false
        for (loc in perimeter) {
            val lat = loc.latitude.inDegrees
            val isPole = abs(lat) >= 90.0 - poleEps
            if (isPole && lastWasPole) continue
            lastWasPole = isPole
            val lon = loc.longitude.inDegrees
            val nudgedLon = when {
                lon >= 180.0 -> 180.0 - lonEps
                lon <= -180.0 -> -180.0 + lonEps
                else -> lon
            }
            if (lon == nudgedLon && !isPole) out.add(loc)
            else out.add(Location.fromDegrees(lat, nudgedLon))
        }
        return out
    }

    private fun emitGluVertex(tess: GLUtessellator, rc: RenderContext, latitude: Angle, longitude: Angle) {
        ensureVertexArrayCapacity()
        calcPoint(rc, latitude, longitude, center.altitude, isExtrudedSkirt = false)
        val vertexIdx = addVertex(rc, latitude, longitude, center.altitude, offset = 0, isExtrudedSkirt = false)
        tessCoords[0] = longitude.inDegrees
        tessCoords[1] = latitude.inDegrees
        tessCoords[2] = center.altitude
        GLU.gluTessVertex(tess, tessCoords, 0, vertexIdx)
    }

    /**
     * Emits one or more outline chains from [subPoly], splitting the input wherever [shouldBreakAt]
     * returns true. A single unbroken chain is closed (wrapping back to its first vertex) when
     * [closeLoopIfUnbroken] is true.
     *
     * Because [subPoly] is a closed ring from PolygonSplitter (`subPoly.last() → subPoly.first()`
     * is the implicit wrap-around edge), when a break occurs and neither endpoint of [subPoly] is
     * itself a break point, the chain collected before the first break and the chain collected
     * after the last break represent two halves of one continuous outline that joins across the
     * wrap-around. Those two chains are merged into a single chain so the wrap-around edge is
     * drawn — otherwise pole-inside ellipses show a missing segment where the densified perimeter
     * wraps past [perimeterLocs][0].
     */
    protected open fun emitOutlineChains(
        rc: RenderContext,
        subPoly: List<Location>,
        closeLoopIfUnbroken: Boolean,
        shouldBreakAt: (Location) -> Boolean = { false }
    ) {
        val chains = mutableListOf<MutableList<Location>>()
        var chainBuffer: MutableList<Location>? = null
        var broken = false
        for (loc in subPoly) {
            if (shouldBreakAt(loc)) {
                chainBuffer?.takeIf { it.size >= 2 }?.let { chains.add(it) }
                chainBuffer = null
                broken = true
                continue
            }
            (chainBuffer ?: mutableListOf<Location>().also { chainBuffer = it }).add(loc)
        }
        chainBuffer?.takeIf { it.size >= 2 }?.let { chains.add(it) }
        if (chains.isEmpty()) return
        if (!broken) {
            emitOutlineChain(rc, chains[0], closeLoop = closeLoopIfUnbroken)
            return
        }
        // If [subPoly]'s first and last vertices are not themselves break points, the last chain
        // (post-final-break) and the first chain (pre-first-break) are adjacent via the ring's
        // wrap-around — join them end-to-start into one continuous chain.
        if (chains.size >= 2 &&
            subPoly.isNotEmpty() && !shouldBreakAt(subPoly.first()) && !shouldBreakAt(subPoly.last())
        ) {
            val last = chains.removeAt(chains.size - 1)
            last.addAll(chains[0])
            chains[0] = last
        }
        for (chain in chains) emitOutlineChain(rc, chain, closeLoop = false)
    }

    /**
     * Emits a single outline chain for [subPoly]. When [closeLoop] is true, the trailing dummies
     * are placed at the first vertex so the triangle strip wraps back to the starting point;
     * otherwise the chain ends at the last vertex with dummies capping the miter.
     */
    protected open fun emitOutlineChain(rc: RenderContext, subPoly: List<Location>, closeLoop: Boolean) {
        val first = subPoly[0]
        calcPoint(rc, first.latitude, first.longitude, center.altitude)
        val firstPoint = Vec3(point)
        currentData.outlineChainStarts.add(currentData.outlineElements.size)
        addLineVertex(rc, first.latitude, first.longitude, center.altitude, verticalVertexIndex, addIndices = true)
        addLineVertex(rc, first.latitude, first.longitude, center.altitude, verticalVertexIndex, addIndices = true)
        for (k in 1 until subPoly.size) {
            val loc = subPoly[k]
            calcPoint(rc, loc.latitude, loc.longitude, center.altitude)
            addLineVertex(rc, loc.latitude, loc.longitude, center.altitude, verticalVertexIndex, addIndices = true)
        }
        if (closeLoop) {
            point.copy(firstPoint)
            addLineVertex(rc, first.latitude, first.longitude, center.altitude, verticalVertexIndex, addIndices = false)
            addLineVertex(rc, first.latitude, first.longitude, center.altitude, verticalVertexIndex, addIndices = false)
        } else {
            val last = subPoly.last()
            addLineVertex(rc, last.latitude, last.longitude, center.altitude, verticalVertexIndex, addIndices = false)
            addLineVertex(rc, last.latitude, last.longitude, center.altitude, verticalVertexIndex, addIndices = false)
        }
    }
    protected open fun addLineVertex(
        rc: RenderContext, latitude: Angle, longitude: Angle, altitude: Double, offset : Int, addIndices : Boolean
    ) = with(currentData) {
        val vertex = lineVertexIndex / VERTEX_STRIDE
        if (lineVertexIndex == 0) texCoord1d = 0.0
        else texCoord1d += point.distanceTo(prevPoint)
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
        lineVertexIndex = emitLineCorners(lineVertexArray, lineVertexIndex, x, y, z, texCoord1d.toFloat())
        if (addIndices) {
            outlineElements.add(vertex)
            outlineElements.add(vertex + 1)
            outlineElements.add(vertex + 2)
            outlineElements.add(vertex + 3)
        }
        if (!isSurfaceShape && isExtrude && addIndices) {
            val index = verticalVertexIndex / VERTEX_STRIDE
            val vx = (vertPoint.x - vertexOrigin.x).toFloat()
            val vy = (vertPoint.y - vertexOrigin.y).toFloat()
            val vz = (vertPoint.z - vertexOrigin.z).toFloat()
            // pointA dummy + pointB top + pointB bottom + pointC dummy = 4 corner-quads.
            verticalVertexIndex = emitLineCorners(lineVertexArray, verticalVertexIndex, x, y, z, 0.0f)
            verticalVertexIndex = emitLineCorners(lineVertexArray, verticalVertexIndex, x, y, z, 0.0f)
            verticalVertexIndex = emitLineCorners(lineVertexArray, verticalVertexIndex, vx, vy, vz, 0.0f)
            verticalVertexIndex = emitLineCorners(lineVertexArray, verticalVertexIndex, vx, vy, vz, 0.0f)
            verticalElements.add(index + 2); verticalElements.add(index + 3); verticalElements.add(index + 4)
            verticalElements.add(index + 4); verticalElements.add(index + 3); verticalElements.add(index + 5)
        }
    }

    protected open fun addVertex(
        rc: RenderContext, latitude: Angle, longitude: Angle, altitude: Double, offset: Int, isExtrudedSkirt: Boolean
    ): Int = with(currentData) {
        val vertex = vertexIndex / VERTEX_STRIDE
        var offsetVertexIndex = vertexIndex + offset
        val texCoord2d = texCoord2d.copy(point).multiplyByMatrix(modelToTexCoord)
        if (isSurfaceShape) {
            // Raw longitude offset (not normalized). The pole-fill case needs to distinguish
            // vertices at lon=+180 vs lon=-180, which normalization collapses when the ellipse
            // center is not at longitude 0 or ±180. For antimeridian-crossing shapes, the
            // resulting wide vertex offsets still rasterize correctly per-tile via the two
            // surface drawables (east/west sectors) emitted in makeDrawable.
            vertexArray[vertexIndex++] = (longitude.inDegrees - vertexOrigin.x).toFloat()
            vertexArray[vertexIndex++] = (latitude.inDegrees - vertexOrigin.y).toFloat()
            vertexArray[vertexIndex++] = (altitude - vertexOrigin.z).toFloat()
            // reserved for future texture coordinate use
            vertexArray[vertexIndex++] = texCoord2d.x.toFloat()
            vertexArray[vertexIndex++] = texCoord2d.y.toFloat()
        } else {
            vertexArray[vertexIndex++] = (point.x - vertexOrigin.x).toFloat()
            vertexArray[vertexIndex++] = (point.y - vertexOrigin.y).toFloat()
            vertexArray[vertexIndex++] = (point.z - vertexOrigin.z).toFloat()
            vertexArray[vertexIndex++] = texCoord2d.x.toFloat()
            vertexArray[vertexIndex++] = texCoord2d.y.toFloat()
            if (isExtrudedSkirt) {
                vertexArray[offsetVertexIndex++] = (vertPoint.x - vertexOrigin.x).toFloat()
                vertexArray[offsetVertexIndex++] = (vertPoint.y - vertexOrigin.y).toFloat()
                vertexArray[offsetVertexIndex++] = (vertPoint.z - vertexOrigin.z).toFloat()
                vertexArray[offsetVertexIndex++] = 0f //unused
                vertexArray[offsetVertexIndex] = 0f //unused
            }
        }
        vertex
    }

    /**
     * GLU tessellator combine callback — fires when the tessellator needs a new vertex at an
     * edge intersection (rare for pre-split sub-polygons). Appends a new vertex to the shared
     * vertex array and returns its index in outData[0].
     */
    protected open fun tessCombine(
        rc: RenderContext, coords: DoubleArray, data: Array<Any?>, weight: FloatArray, outData: Array<Any?>
    ) {
        ensureVertexArrayCapacity()
        val latitude = coords[1].degrees
        val longitude = coords[0].degrees
        val altitude = coords[2]
        calcPoint(rc, latitude, longitude, altitude, isExtrudedSkirt = false)
        outData[0] = addVertex(rc, latitude, longitude, altitude, offset = 0, isExtrudedSkirt = false)
    }

    /**
     * GLU tessellator vertex callback — receives three vertex indices per triangle. Accumulates
     * them into [EllipseData.topElements] for the surface fill.
     */
    protected open fun tessVertex(rc: RenderContext, vertexData: Any?) = with(currentData) {
        tessVertices[tessVertexCount] = vertexData as Int
        tessEdgeFlags[tessVertexCount] = tessEdgeFlag
        if (tessVertexCount < 2) {
            tessVertexCount++
            return@with
        } else {
            tessVertexCount = 0
        }
        topElements.add(tessVertices[0])
        topElements.add(tessVertices[1])
        topElements.add(tessVertices[2])
    }

    protected open fun tessEdgeFlag(rc: RenderContext, boundaryEdge: Boolean) { tessEdgeFlag = boundaryEdge }

    protected open fun tessError(rc: RenderContext, errNum: Int) {
        val errStr = GLU.gluErrorString(errNum)
        logMessage(WARN, "Ellipse", "assembleGeometry", "Error attempting to tessellate ellipse '$errStr'")
    }

    protected open fun ensureVertexArrayCapacity() {
        val size = currentData.vertexArray.size
        // Grow when there's no room for another full stride. The old `size == vertexIndex`
        // check drifted off alignment after (size shr 1) produced non-multiples of
        // VERTEX_STRIDE (e.g. 675 → 1012), letting vertexIndex step PAST size without
        // triggering a grow — and addVertex then wrote past the end.
        if (size - vertexIndex < VERTEX_STRIDE) {
            var increment = (size shr 1).coerceAtLeast(VERTEX_STRIDE * 4)
            val rem = increment % VERTEX_STRIDE
            if (rem != 0) increment += VERTEX_STRIDE - rem
            val newArray = FloatArray(size + increment)
            currentData.vertexArray.copyInto(newArray)
            currentData.vertexArray = newArray
        }
    }

    protected open fun determineModelToTexCoord(rc: RenderContext) {
        rc.geographicToCartesian(center, altitudeMode, point)
        rc.globe.cartesianToLocalTransform(point.x, point.y, point.z, modelToTexCoord)
        modelToTexCoord.invertOrthonormal()
    }

    /**
     * Calculate the number of times to split the edges of the shape for geometry assembly.
     *
     * @param rc current RenderContext
     *
     * @return an even number of intervals
     */
    protected open fun computeIntervals(rc: RenderContext): Int {
        var intervals = MIN_INTERVALS
        if (intervals >= maximumIntervals) return intervals // use at least the minimum number of intervals
        val centerPoint = rc.geographicToCartesian(center, altitudeMode, point)
        val maxRadius = max(majorRadius, minorRadius)
        val cameraDistance = centerPoint.distanceTo(rc.cameraPoint) - maxRadius
        if (cameraDistance <= 0) return maximumIntervals // use the maximum number of intervals when the camera is very close
        val metersPerPixel = rc.pixelSizeAtDistance(cameraDistance)
        val circumferencePixels = computeCircumference() / metersPerPixel
        val circumferenceIntervals = circumferencePixels / maximumPixelsPerInterval
        val subdivisions = log2(circumferenceIntervals / intervals)
        val subdivisionCount = ceil(subdivisions).toInt().coerceAtLeast(0)
        intervals = intervals shl subdivisionCount // subdivide the base intervals to achieve the desired number of intervals
        return intervals.coerceAtMost(maximumIntervals) // don't exceed the maximum number of intervals
    }

    protected open fun sanitizeIntervals(intervals: Int) = if (intervals % 2 == 0) intervals else intervals - 1

    internal open fun computeCircumference(): Double {
        val a = majorRadius
        val b = minorRadius
        return PI * (3 * (a + b) - sqrt((3 * a + b) * (a + 3 * b)))
    }
}