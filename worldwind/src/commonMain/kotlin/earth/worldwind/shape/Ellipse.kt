package earth.worldwind.shape

import earth.worldwind.draw.DrawShapeState
import earth.worldwind.draw.Drawable
import earth.worldwind.draw.DrawableGeomLines
import earth.worldwind.draw.DrawableLinesState
import earth.worldwind.draw.DrawableShape
import earth.worldwind.draw.DrawableSurfaceGeomLines
import earth.worldwind.draw.DrawableSurfaceShape
import earth.worldwind.geom.*
import earth.worldwind.geom.Angle.Companion.ZERO
import earth.worldwind.geom.Angle.Companion.toDegrees
import earth.worldwind.render.*
import earth.worldwind.render.buffer.FloatBufferObject
import earth.worldwind.render.buffer.IntBufferObject
import earth.worldwind.render.buffer.ShortBufferObject
import earth.worldwind.render.image.ImageOptions
import earth.worldwind.render.image.ResamplingMode
import earth.worldwind.render.image.WrapMode
import earth.worldwind.render.program.BasicShaderProgram
import earth.worldwind.render.program.GeomLinesShaderProgram
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
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
    protected var vertexArray = FloatArray(0)
    protected var vertexIndex = 0
    protected var vertexBufferKey = Any()
    protected var lineVertexArray = FloatArray(0)
    protected var lineVertexIndex = 0
    protected var lineVertexBufferKey = Any()
    protected var lineElementBufferKey = Any()
    protected var verticalVertexIndex = 0
    protected val verticalElements = mutableListOf<Int>()
    protected val outlineElements = mutableListOf<Int>()
    protected val vertexOrigin = Vec3()
    protected var texCoord1d = 0.0
    protected val texCoord2d = Vec3()
    protected val texCoordMatrix = Matrix3()
    protected val modelToTexCoord = Matrix4()
    protected var cameraDistance = 0.0
    protected val prevPoint = Vec3()

    init {
        require(majorRadius >= 0 && minorRadius >= 0) {
            logMessage(ERROR, "Ellipse", "constructor", "invalidRadius")
        }
    }

    companion object {
        protected const val VERTEX_STRIDE = 5
        protected const val LINE_VERTEX_STRIDE = 10
        /**
         * The minimum number of intervals that will be used for geometry generation.
         */
        protected const val MIN_INTERVALS = 32
        /**
         * Key for Range object in the element buffer describing the top of the Ellipse.
         */
        protected const val TOP_RANGE = 0
        /**
         * Key for Range object in the element buffer describing the outline of the Ellipse.
         */
        protected const val OUTLINE_RANGE = 1
        /**
         * Key for Range object in the element buffer describing the extruded sides of the Ellipse.
         */
        protected const val SIDE_RANGE = 2

        protected val defaultInteriorImageOptions = ImageOptions().apply { wrapMode = WrapMode.REPEAT }
        protected val defaultOutlineImageOptions = ImageOptions().apply {
            wrapMode = WrapMode.REPEAT
            resamplingMode = ResamplingMode.NEAREST_NEIGHBOR
        }

        /**
         * Simple interval count based cache of the keys for element buffers. Element buffers are dependent only on the
         * number of intervals so the keys are cached here. The element buffer object itself is in the
         * RenderResourceCache and subject to the restrictions and behavior of that cache.
         */
        protected val elementBufferKeys = mutableMapOf<Int,Any>()

        private val scratchPosition = Position()
        private val scratchPoint = Vec3()
        private val scratchVertPoint = Vec3()

        protected fun assembleElements(intervals: Int): ShortBufferObject {
            // Create temporary storage for elements
            // TODO Use ShortArray instead of mutableListOf<Short> to avoid unnecessary memory re-allocations
            val elements = mutableListOf<Short>()

            // Generate the top element buffer with spine
            var idx = intervals.toShort()
            val offset = computeIndexOffset(intervals)

            // Add the anchor leg
            elements.add(0.toShort())
            elements.add(1.toShort())
            // Tessellate the interior
            for (i in 2 until intervals) {
                // Add the corresponding interior spine point if this isn't the vertex following the last vertex for the
                // negative major axis
                if (i != intervals / 2 + 1) if (i > intervals / 2) elements.add(--idx) else elements.add(idx++)
                // Add the degenerate triangle at the negative major axis in order to flip the triangle strip back towards
                // the positive axis
                if (i == intervals / 2) elements.add(i.toShort())
                // Add the exterior vertex
                elements.add(i.toShort())
            }
            // Complete the strip
            elements.add(--idx)
            elements.add(0.toShort())
            val topRange = Range(0, elements.size)

            // Generate the outline element buffer
            for (i in 0 until intervals) elements.add(i.toShort())
            val outlineRange = Range(topRange.upper, elements.size)

            // Generate the side element buffer
            for (i in 0 until intervals) {
                elements.add(i.toShort())
                elements.add(i.plus(offset).toShort())
            }
            elements.add(0.toShort())
            elements.add(offset.toShort())
            val sideRange = Range(outlineRange.upper, elements.size)

            // Generate a buffer for the element
            val elementBuffer = ShortBufferObject(GL_ELEMENT_ARRAY_BUFFER, elements.toShortArray())
            elementBuffer.ranges[TOP_RANGE] = topRange
            elementBuffer.ranges[OUTLINE_RANGE] = outlineRange
            elementBuffer.ranges[SIDE_RANGE] = sideRange
            return elementBuffer
        }

        protected fun computeNumberSpinePoints(intervals: Int) = intervals / 2 - 1 // intervals should be even

        protected fun computeIndexOffset(intervals: Int) = intervals + computeNumberSpinePoints(intervals)
    }

    override fun makeDrawable(rc: RenderContext) {
        if (majorRadius == 0.0 && minorRadius == 0.0) return  // nothing to draw

        if (mustAssembleGeometry(rc)) {
            assembleGeometry(rc)
            vertexBufferKey = Any()
            lineVertexBufferKey = Any()
            lineElementBufferKey = Any()
        }

        // Obtain a drawable form the render context pool.
        val drawable: Drawable
        val drawState: DrawShapeState
        val drawableLines: Drawable
        val drawStateLines: DrawableLinesState
        if (isSurfaceShape) {
            val pool = rc.getDrawablePool<DrawableSurfaceShape>()
            drawable = DrawableSurfaceShape.obtain(pool)
            drawState = drawable.drawState
            drawable.offset = rc.globe.offset
            drawable.sector.copy(boundingSector)

            val linesPool = rc.getDrawablePool<DrawableSurfaceGeomLines>()
            drawableLines = DrawableSurfaceGeomLines.obtain(linesPool)
            drawStateLines = drawableLines.drawState
            // Use the basic GLSL program to draw the shape.

            drawableLines.projShaderProgram = rc.getShaderProgram { BasicShaderProgram() }
            drawableLines.offset = rc.globe.offset
            drawableLines.sector.copy(boundingSector)

            cameraDistance = cameraDistanceGeographic(rc, boundingSector)
        } else {
            val pool = rc.getDrawablePool<DrawableShape>()
            drawable = DrawableShape.obtain(pool)
            drawState = drawable.drawState

            val linesPool = rc.getDrawablePool<DrawableGeomLines>()
            drawableLines = DrawableGeomLines.obtain(linesPool)
            drawStateLines = drawableLines.drawState

            cameraDistance = boundingBox.distanceTo(rc.cameraPoint)
        }

        // Use the basic GLSL program to draw the shape.
        drawState.program = rc.getShaderProgram { BasicShaderProgram() }

        // Assemble the drawable's OpenGL vertex buffer object.
        drawState.vertexBuffer = rc.getBufferObject(vertexBufferKey) { FloatBufferObject(GL_ARRAY_BUFFER, vertexArray) }

        // Use the basic GLSL program to draw the shape.
        drawStateLines.program = rc.getShaderProgram { GeomLinesShaderProgram() }

        // Assemble the drawable's OpenGL vertex buffer object.
        drawStateLines.vertexBuffer = rc.getBufferObject(lineVertexBufferKey) { FloatBufferObject(GL_ARRAY_BUFFER, lineVertexArray) }

        // Assemble the drawable's OpenGL element buffer object.
        drawStateLines.elementBuffer = rc.getBufferObject(lineElementBufferKey) {
            IntBufferObject(GL_ELEMENT_ARRAY_BUFFER, (outlineElements + verticalElements).toIntArray())
        }

        // Get the attributes of the element buffer
        val elementBufferKey = elementBufferKeys[activeIntervals] ?: Any().also { elementBufferKeys[activeIntervals] = it }
        drawState.elementBuffer = rc.getBufferObject(elementBufferKey) { assembleElements(activeIntervals) }
        if (isSurfaceShape) {
            drawInterior(rc, drawState)
            drawOutline(rc, drawStateLines)
        } else {
            drawOutline(rc, drawStateLines)
            drawInterior(rc, drawState)
        }

        // Configure the drawable according to the shape's attributes.
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
        if (isSurfaceShape) {
            rc.offerSurfaceDrawable(drawable, 0.0 /*zOrder*/)
            rc.offerSurfaceDrawable(drawableLines, 0.0 /*zOrder*/)
        }
        else {
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

        // Configure the drawable to display the shape's interior.
        drawState.color(if (rc.isPickMode) pickColor else activeAttributes.interiorColor)
        drawState.opacity(if (rc.isPickMode) 1f else rc.currentLayer.opacity)
        drawState.texCoordAttrib(2 /*size*/, 12 /*offset in bytes*/)
        val top = drawState.elementBuffer!!.ranges[TOP_RANGE]!!
        drawState.drawElements(GL_TRIANGLE_STRIP, top.length, GL_UNSIGNED_SHORT, top.lower * 2 /*offset*/)
        if (isExtrude) {
            val side = drawState.elementBuffer!!.ranges[SIDE_RANGE]!!
            drawState.texture(null)
            drawState.drawElements(GL_TRIANGLE_STRIP, side.length, GL_UNSIGNED_SHORT, side.lower * 2)
        }
    }

    protected open fun drawOutline(rc: RenderContext, drawState: DrawableLinesState) {
        if (!activeAttributes.isDrawOutline) return

        // Configure the drawable to display the shape's outline.
        drawState.color(if (rc.isPickMode) pickColor else activeAttributes.outlineColor)
        drawState.opacity(if (rc.isPickMode) 1f else rc.currentLayer.opacity)
        drawState.lineWidth(activeAttributes.outlineWidth)
        drawState.drawElements(
            GL_TRIANGLE_STRIP, outlineElements.size,
            GL_UNSIGNED_INT, 0 * Int.SIZE_BYTES
        )
        if (activeAttributes.isDrawVerticals && isExtrude && !isSurfaceShape) {
            drawState.color(if (rc.isPickMode) pickColor else activeAttributes.outlineColor)
            drawState.opacity(if (rc.isPickMode) 1f else rc.currentLayer.opacity)
            drawState.lineWidth(activeAttributes.outlineWidth)
            drawState.drawElements(
                GL_TRIANGLES, verticalElements.size,
                GL_UNSIGNED_INT, (outlineElements.size) * Int.SIZE_BYTES
            )
        }
    }

    protected open fun mustAssembleGeometry(rc: RenderContext): Boolean {
        val calculatedIntervals = computeIntervals(rc)
        val sanitizedIntervals = sanitizeIntervals(calculatedIntervals)
        if (vertexArray.isEmpty() || sanitizedIntervals != activeIntervals) {
            activeIntervals = sanitizedIntervals
            return true
        }
        return false
    }

    protected open fun assembleGeometry(rc: RenderContext) {
        // Compute a matrix that transforms from Cartesian coordinates to shape texture coordinates.
        determineModelToTexCoord(rc)

        // Use the ellipse's center position as the local origin for vertex positions.
        if (isSurfaceShape) {
            vertexOrigin.set(center.longitude.inDegrees, center.latitude.inDegrees, center.altitude)
        } else {
            rc.geographicToCartesian(center, altitudeMode, scratchPoint)
            vertexOrigin.set(scratchPoint.x, scratchPoint.y, scratchPoint.z)
        }

        // Determine the number of spine points
        val spineCount = computeNumberSpinePoints(activeIntervals) // activeIntervals must be even

        // Clear the shape's vertex array. The array will accumulate values as the shapes's geometry is assembled.
        vertexIndex = 0
        vertexArray = if (isExtrude && !isSurfaceShape) FloatArray((activeIntervals * 2 + spineCount) * VERTEX_STRIDE)
        else FloatArray((activeIntervals + spineCount) * VERTEX_STRIDE)

        lineVertexIndex = 0
        lineVertexArray = if (isExtrude && !isSurfaceShape) FloatArray((activeIntervals + 3 + (activeIntervals + 1) * 4) * LINE_VERTEX_STRIDE)
        else FloatArray((activeIntervals + 3) * LINE_VERTEX_STRIDE)
        verticalVertexIndex = (activeIntervals + 3) * LINE_VERTEX_STRIDE

        verticalElements.clear()
        outlineElements.clear()

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

        var firstLoc = Position()
        // Iterate around the ellipse to add vertices
        for (i in 0 until activeIntervals) {
            val radians = deltaRadians * i
            val x = cos(radians) * majorArcRadians
            val y = sin(radians) * minorArcRadians
            val azimuthDegrees = toDegrees(-atan2(y, x))
            val arcRadius = sqrt(x * x + y * y)
            // Calculate the great circle location given this activeIntervals step (azimuthDegrees) a correction value to
            // start from an east-west aligned major axis (90.0) and the user specified user heading value
            val azimuth = heading.plusDegrees(azimuthDegrees + headingAdjustment)
            val loc = center.greatCircleLocation(azimuth, arcRadius, scratchPosition)
            addVertex(rc, loc.latitude, loc.longitude, center.altitude, arrayOffset, isExtrude)
            // Add the major arc radius for the spine points. Spine points are vertically coincident with exterior
            // points. The first and middle most point do not have corresponding spine points.
            if (i > 0 && i < activeIntervals / 2) spineRadius[spineIdx++] = x

            if(i < 1)
            {
                firstLoc = Position(loc.latitude, loc.longitude, center.altitude)
                addLineVertex(rc, loc.latitude, loc.longitude, center.altitude, verticalVertexIndex,  true)
            }
            addLineVertex(rc, loc.latitude, loc.longitude, center.altitude, verticalVertexIndex,  false)
        }

        addLineVertex(rc, firstLoc.latitude, firstLoc.longitude, firstLoc.altitude, verticalVertexIndex,  false)
        addLineVertex(rc, firstLoc.latitude, firstLoc.longitude, firstLoc.altitude, verticalVertexIndex,  true)

        // Add the interior spine point vertices
        for (i in 0 until spineCount) {
            center.greatCircleLocation(heading.plusDegrees(headingAdjustment), spineRadius[i], scratchPosition)
            addVertex(rc, scratchPosition.latitude, scratchPosition.longitude, center.altitude, arrayOffset, false)
        }

        // Compute the shape's bounding sector from its assembled coordinates.
        if (isSurfaceShape) {
            boundingSector.setEmpty()
            boundingSector.union(vertexArray, vertexArray.size, VERTEX_STRIDE)
            boundingSector.translate(vertexOrigin.y /*lat*/, vertexOrigin.x /*lon*/)
            boundingBox.setToUnitBox() // Surface/geographic shape bounding box is unused
        } else {
            boundingBox.setToPoints(vertexArray, vertexArray.size, VERTEX_STRIDE)
            boundingBox.translate(vertexOrigin.x, vertexOrigin.y, vertexOrigin.z)
            boundingSector.setEmpty()
        }
    }
    protected open fun addLineVertex(
        rc: RenderContext, latitude: Angle, longitude: Angle, altitude: Double, offset : Int, firstOrLast : Boolean
    )
    {
        val vertex = (lineVertexIndex / LINE_VERTEX_STRIDE - 1) * 2
        var point = rc.geographicToCartesian(latitude, longitude, altitude, altitudeMode, scratchPoint)
        if (lineVertexIndex == 0) texCoord1d = 0.0
        else texCoord1d += point.distanceTo(prevPoint)
        prevPoint.copy(point)
        if (isSurfaceShape) {
            lineVertexArray[lineVertexIndex++] = (longitude.inDegrees - vertexOrigin.x).toFloat()
            lineVertexArray[lineVertexIndex++] = (latitude.inDegrees - vertexOrigin.y).toFloat()
            lineVertexArray[lineVertexIndex++] = (altitude - vertexOrigin.z).toFloat()
            lineVertexArray[lineVertexIndex++] = 1.0f
            lineVertexArray[lineVertexIndex++] = texCoord1d.toFloat()
            lineVertexArray[lineVertexIndex++] = (longitude.inDegrees - vertexOrigin.x).toFloat()
            lineVertexArray[lineVertexIndex++] = (latitude.inDegrees - vertexOrigin.y).toFloat()
            lineVertexArray[lineVertexIndex++] = (altitude - vertexOrigin.z).toFloat()
            lineVertexArray[lineVertexIndex++] = -1.0f
            lineVertexArray[lineVertexIndex++] = texCoord1d.toFloat()
            if(!firstOrLast) {
                outlineElements.add(vertex)
                outlineElements.add(vertex.inc())
            }
        } else {
            lineVertexArray[lineVertexIndex++] = (point.x - vertexOrigin.x).toFloat()
            lineVertexArray[lineVertexIndex++] = (point.y - vertexOrigin.y).toFloat()
            lineVertexArray[lineVertexIndex++] = (point.z - vertexOrigin.z).toFloat()
            lineVertexArray[lineVertexIndex++] = 1.0f
            lineVertexArray[lineVertexIndex++] = texCoord1d.toFloat()
            lineVertexArray[lineVertexIndex++] = (point.x - vertexOrigin.x).toFloat()
            lineVertexArray[lineVertexIndex++] = (point.y - vertexOrigin.y).toFloat()
            lineVertexArray[lineVertexIndex++] = (point.z - vertexOrigin.z).toFloat()
            lineVertexArray[lineVertexIndex++] = -1.0f
            lineVertexArray[lineVertexIndex++] = texCoord1d.toFloat()
            if(!firstOrLast) {
                outlineElements.add(vertex)
                outlineElements.add(vertex.inc())
            }
            if (isExtrude && !firstOrLast) {
                var vertPoint = rc.geographicToCartesian(latitude, longitude, 0.0, altitudeMode, scratchVertPoint)
                val index =  verticalVertexIndex / LINE_VERTEX_STRIDE * 2

                lineVertexArray[verticalVertexIndex++] = (point.x - vertexOrigin.x).toFloat()
                lineVertexArray[verticalVertexIndex++] = (point.y - vertexOrigin.y).toFloat()
                lineVertexArray[verticalVertexIndex++] = (point.z - vertexOrigin.z).toFloat()
                lineVertexArray[verticalVertexIndex++] = 1f
                lineVertexArray[verticalVertexIndex++] = 0f

                lineVertexArray[verticalVertexIndex++] = (point.x - vertexOrigin.x).toFloat()
                lineVertexArray[verticalVertexIndex++] = (point.y - vertexOrigin.y).toFloat()
                lineVertexArray[verticalVertexIndex++] = (point.z - vertexOrigin.z).toFloat()
                lineVertexArray[verticalVertexIndex++] = -1f
                lineVertexArray[verticalVertexIndex++] = 0f

                lineVertexArray[verticalVertexIndex++] = (point.x - vertexOrigin.x).toFloat()
                lineVertexArray[verticalVertexIndex++] = (point.y - vertexOrigin.y).toFloat()
                lineVertexArray[verticalVertexIndex++] = (point.z - vertexOrigin.z).toFloat()
                lineVertexArray[verticalVertexIndex++] = 1f
                lineVertexArray[verticalVertexIndex++] = 0f

                lineVertexArray[verticalVertexIndex++] = (point.x - vertexOrigin.x).toFloat()
                lineVertexArray[verticalVertexIndex++] = (point.y - vertexOrigin.y).toFloat()
                lineVertexArray[verticalVertexIndex++] = (point.z - vertexOrigin.z).toFloat()
                lineVertexArray[verticalVertexIndex++] = -1f
                lineVertexArray[verticalVertexIndex++] = 0f

                lineVertexArray[verticalVertexIndex++] = (vertPoint.x - vertexOrigin.x).toFloat()
                lineVertexArray[verticalVertexIndex++] = (vertPoint.y - vertexOrigin.y).toFloat()
                lineVertexArray[verticalVertexIndex++] = (vertPoint.z - vertexOrigin.z).toFloat()
                lineVertexArray[verticalVertexIndex++] = 1f
                lineVertexArray[verticalVertexIndex++] = 0f

                lineVertexArray[verticalVertexIndex++] = (vertPoint.x - vertexOrigin.x).toFloat()
                lineVertexArray[verticalVertexIndex++] = (vertPoint.y - vertexOrigin.y).toFloat()
                lineVertexArray[verticalVertexIndex++] = (vertPoint.z - vertexOrigin.z).toFloat()
                lineVertexArray[verticalVertexIndex++] = -1f
                lineVertexArray[verticalVertexIndex++] = 0f

                lineVertexArray[verticalVertexIndex++] = (vertPoint.x - vertexOrigin.x).toFloat()
                lineVertexArray[verticalVertexIndex++] = (vertPoint.y - vertexOrigin.y).toFloat()
                lineVertexArray[verticalVertexIndex++] = (vertPoint.z - vertexOrigin.z).toFloat()
                lineVertexArray[verticalVertexIndex++] = 1f
                lineVertexArray[verticalVertexIndex++] = 0f

                lineVertexArray[verticalVertexIndex++] = (vertPoint.x - vertexOrigin.x).toFloat()
                lineVertexArray[verticalVertexIndex++] = (vertPoint.y - vertexOrigin.y).toFloat()
                lineVertexArray[verticalVertexIndex++] = (vertPoint.z - vertexOrigin.z).toFloat()
                lineVertexArray[verticalVertexIndex++] = -1f
                lineVertexArray[verticalVertexIndex++] = 0f

                verticalElements.add(index)
                verticalElements.add(index + 1)
                verticalElements.add(index + 2)
                verticalElements.add(index)
                verticalElements.add(index + 2)
                verticalElements.add(index + 3)
            }
        }
    }

    protected open fun addVertex(
        rc: RenderContext, latitude: Angle, longitude: Angle, altitude: Double, offset: Int, isExtrudedSkirt: Boolean
    ) {
        var offsetVertexIndex = vertexIndex + offset
        var point = rc.geographicToCartesian(latitude, longitude, altitude, altitudeMode, scratchPoint)
        val texCoord2d = texCoord2d.copy(point).multiplyByMatrix(modelToTexCoord)
        prevPoint.copy(point)
        if (isSurfaceShape) {
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
                point = rc.geographicToCartesian(latitude, longitude, 0.0, AltitudeMode.CLAMP_TO_GROUND, scratchPoint)
                vertexArray[offsetVertexIndex++] = (point.x - vertexOrigin.x).toFloat()
                vertexArray[offsetVertexIndex++] = (point.y - vertexOrigin.y).toFloat()
                vertexArray[offsetVertexIndex++] = (point.z - vertexOrigin.z).toFloat()
                vertexArray[offsetVertexIndex++] = 0f //unused
                vertexArray[offsetVertexIndex] = 0f //unused
            }
        }
    }

    protected open fun determineModelToTexCoord(rc: RenderContext) {
        val point = rc.geographicToCartesian(center, altitudeMode, scratchPoint)
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
        val centerPoint = rc.geographicToCartesian(center, altitudeMode, scratchPoint)
        val maxRadius = max(majorRadius, minorRadius)
        val cameraDistance = centerPoint.distanceTo(rc.cameraPoint) - maxRadius
        if (cameraDistance <= 0) return maximumIntervals // use the maximum number of intervals when the camera is very close
        val metersPerPixel = rc.pixelSizeAtDistance(cameraDistance)
        val circumferencePixels = computeCircumference() / metersPerPixel
        val circumferenceIntervals = circumferencePixels / maximumPixelsPerInterval
        val subdivisions = ln(circumferenceIntervals / intervals) / ln(2.0)
        val subdivisionCount = ceil(subdivisions).toInt().coerceAtLeast(0)
        intervals = intervals shl subdivisionCount // subdivide the base intervals to achieve the desired number of intervals
        return intervals.coerceAtMost(maximumIntervals) // don't exceed the maximum number of intervals
    }

    protected open fun sanitizeIntervals(intervals: Int) = if (intervals % 2 == 0) intervals else intervals - 1

    open fun computeCircumference(): Double {
        val a = majorRadius
        val b = minorRadius
        return PI * (3 * (a + b) - sqrt((3 * a + b) * (a + 3 * b)))
    }

    override fun reset() {
        super.reset()
        vertexArray = FloatArray(0)
    }
}