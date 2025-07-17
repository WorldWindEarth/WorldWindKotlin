package earth.worldwind.shape

import earth.worldwind.draw.DrawShapeState
import earth.worldwind.draw.Drawable
import earth.worldwind.draw.DrawableShape
import earth.worldwind.draw.DrawableSurfaceShape
import earth.worldwind.geom.*
import earth.worldwind.geom.Angle.Companion.ZERO
import earth.worldwind.geom.Angle.Companion.toDegrees
import earth.worldwind.render.RenderContext
import earth.worldwind.render.buffer.BufferObject
import earth.worldwind.render.image.ImageOptions
import earth.worldwind.render.image.ResamplingMode
import earth.worldwind.render.image.WrapMode
import earth.worldwind.render.program.TriangleShaderProgram
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.NumericArray
import earth.worldwind.util.kgl.*
import earth.worldwind.util.math.encodeOrientationVector
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
    protected val vertexBufferKey = Any()
    protected val elementBufferKey = Any()
    protected var lineVertexArray = FloatArray(0)
    protected var lineVertexIndex = 0
    protected val lineVertexBufferKey = Any()
    protected val lineElementBufferKey = Any()
    protected var verticalVertexIndex = 0
    protected var bufferDataVersion = 0L
    // TODO Use ShortArray instead of mutableListOf<Short> to avoid unnecessary memory re-allocations
    protected val topElements = mutableListOf<Short>()
    protected val sideElements = mutableListOf<Short>()
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
        protected const val OUTLINE_LINE_SEGMENT_STRIDE = 4 * VERTEX_STRIDE
        protected const val VERTICAL_LINE_SEGMENT_STRIDE = 4 * OUTLINE_LINE_SEGMENT_STRIDE // 4 points per 4 vertices per vertical line
        /**
         * The minimum number of intervals that will be used for geometry generation.
         */
        protected const val MIN_INTERVALS = 32

        protected val defaultInteriorImageOptions = ImageOptions().apply { wrapMode = WrapMode.REPEAT }
        protected val defaultOutlineImageOptions = ImageOptions().apply {
            wrapMode = WrapMode.REPEAT
            resamplingMode = ResamplingMode.NEAREST_NEIGHBOR
        }

        private val scratchPosition = Position()
        private val scratchPoint = Vec3()
        private val scratchVertPoint = Vec3()

        protected fun computeNumberSpinePoints(intervals: Int) = intervals / 2 - 1 // intervals should be even

        protected fun computeIndexOffset(intervals: Int) = intervals + computeNumberSpinePoints(intervals)
    }

    override fun makeDrawable(rc: RenderContext) {
        if (majorRadius == 0.0 && minorRadius == 0.0) return  // nothing to draw

        if (mustAssembleGeometry(rc)) {
            assembleGeometry(rc)
            ++bufferDataVersion
        }

        // Obtain a drawable form the render context pool.
        val drawable: Drawable
        val drawState: DrawShapeState
        val drawableLines: Drawable
        val drawStateLines: DrawShapeState
        if (isSurfaceShape) {
            val pool = rc.getDrawablePool(DrawableSurfaceShape.KEY)
            drawable = DrawableSurfaceShape.obtain(pool)
            drawState = drawable.drawState
            drawable.offset = rc.globe.offset
            drawable.sector.copy(boundingSector)

            drawableLines = DrawableSurfaceShape.obtain(pool)
            drawStateLines = drawableLines.drawState

            // Use the basic GLSL program for texture projection.
            drawableLines.offset = rc.globe.offset
            drawableLines.sector.copy(boundingSector)

            cameraDistance = cameraDistanceGeographic(rc, boundingSector)
        } else {
            val pool = rc.getDrawablePool(DrawableShape.KEY)
            drawable = DrawableShape.obtain(pool)
            drawState = drawable.drawState

            drawableLines = DrawableShape.obtain(pool)
            drawStateLines = drawableLines.drawState

            cameraDistance = boundingBox.distanceTo(rc.cameraPoint)
        }

        // Use the basic GLSL program to draw the shape.
        drawState.program = rc.getShaderProgram(TriangleShaderProgram.KEY) { TriangleShaderProgram() }

        // Assemble the drawable's OpenGL vertex buffer object.
        drawState.vertexBuffer = rc.getBufferObject(vertexBufferKey) { BufferObject(GL_ARRAY_BUFFER, 0) }
        rc.offerGLBufferUpload(vertexBufferKey, bufferDataVersion) { NumericArray.Floats(vertexArray) }

        // Get the attributes of the element buffer
        drawState.elementBuffer = rc.getBufferObject(elementBufferKey) {
            BufferObject(GL_ELEMENT_ARRAY_BUFFER, 0)
        }
        rc.offerGLBufferUpload(elementBufferKey, bufferDataVersion) {
            val array = ShortArray(topElements.size + sideElements.size)
            var index = 0
            for (element in topElements) array[index++] = element
            for (element in sideElements) array[index++] = element
            NumericArray.Shorts(array)
        }

        drawStateLines.isLine = true

        // Use the basic GLSL program to draw the shape.
        drawStateLines.program = rc.getShaderProgram(TriangleShaderProgram.KEY) { TriangleShaderProgram() }

        // Assemble the drawable's OpenGL vertex buffer object.
        drawStateLines.vertexBuffer = rc.getBufferObject(lineVertexBufferKey) { BufferObject(GL_ARRAY_BUFFER, 0) }
        rc.offerGLBufferUpload(lineVertexBufferKey, bufferDataVersion) { NumericArray.Floats(lineVertexArray) }

        // Assemble the drawable's OpenGL element buffer object.
        drawStateLines.elementBuffer = rc.getBufferObject(lineElementBufferKey) {
            BufferObject(GL_ELEMENT_ARRAY_BUFFER, 0)
        }
        rc.offerGLBufferUpload(lineElementBufferKey, bufferDataVersion) {
            val array = IntArray(outlineElements.size + verticalElements.size)
            var index = 0
            for (element in outlineElements) array[index++] = element
            for (element in verticalElements) array[index++] = element
            NumericArray.Ints(array)
        }

        drawInterior(rc, drawState)
        drawOutline(rc, drawStateLines)

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
        } else {
            rc.offerShapeDrawable(drawableLines, cameraDistance)
            rc.offerShapeDrawable(drawable, cameraDistance)
        }
    }

    protected open fun drawInterior(rc: RenderContext, drawState: DrawShapeState) {
        if (!activeAttributes.isDrawInterior || rc.isPickMode && !activeAttributes.isPickInterior) return

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
        drawState.drawElements(GL_TRIANGLE_STRIP, topElements.size, GL_UNSIGNED_SHORT, 0 * Short.SIZE_BYTES /*offset*/)
        if (isExtrude) {
            drawState.texture(null)
            drawState.drawElements(GL_TRIANGLE_STRIP, sideElements.size, GL_UNSIGNED_SHORT, topElements.size * Short.SIZE_BYTES)
        }
    }

    protected open fun drawOutline(rc: RenderContext, drawState: DrawShapeState) {
        if (!activeAttributes.isDrawOutline || rc.isPickMode && !activeAttributes.isPickOutline) return

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
            GL_TRIANGLE_STRIP, outlineElements.size,
            GL_UNSIGNED_INT, 0 * Int.SIZE_BYTES
        )
        if (activeAttributes.isDrawVerticals && isExtrude && !isSurfaceShape && (!rc.isPickMode || activeAttributes.isPickOutline)) {
            drawState.color(if (rc.isPickMode) pickColor else activeAttributes.outlineColor)
            drawState.opacity(if (rc.isPickMode) 1f else rc.currentLayer.opacity)
            drawState.lineWidth(activeAttributes.outlineWidth)
            drawState.texture(null)
            drawState.drawElements(
                GL_TRIANGLES, verticalElements.size,
                GL_UNSIGNED_INT, (outlineElements.size) * Int.SIZE_BYTES
            )
        }
    }

    private fun assembleElements(intervals: Int) {
        // Clear elements storage
        topElements.clear()
        sideElements.clear()

        // Generate the top element buffer with spine
        var idx = intervals.toShort()
        val offset = computeIndexOffset(intervals)

        // Add the anchor leg
        topElements.add(0.toShort())
        topElements.add(1.toShort())
        // Tessellate the interior
        for (i in 2 until intervals) {
            // Add the corresponding interior spine point if this isn't the vertex following the last vertex for the
            // negative major axis
            if (i != intervals / 2 + 1) if (i > intervals / 2) topElements.add(--idx) else topElements.add(idx++)
            // Add the degenerate triangle at the negative major axis in order to flip the triangle strip back towards
            // the positive axis
            if (i == intervals / 2) topElements.add(i.toShort())
            // Add the exterior vertex
            topElements.add(i.toShort())
        }
        // Complete the strip
        topElements.add(--idx)
        topElements.add(0.toShort())

        // Generate the side element buffer
        for (i in 0 until intervals) {
            sideElements.add(i.toShort())
            sideElements.add(i.plus(offset).toShort())
        }
        sideElements.add(0.toShort())
        sideElements.add(offset.toShort())
    }

    protected open fun mustAssembleGeometry(rc: RenderContext): Boolean {
        val calculatedIntervals = computeIntervals(rc)
        val sanitizedIntervals = sanitizeIntervals(calculatedIntervals)
        if (vertexArray.isEmpty() || isExtrude && !isSurfaceShape && lineVertexArray.isEmpty() || sanitizedIntervals != activeIntervals) {
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
        verticalVertexIndex = (activeIntervals + 3) * OUTLINE_LINE_SEGMENT_STRIDE
        lineVertexArray = if (isExtrude && !isSurfaceShape) FloatArray((activeIntervals + 3) * OUTLINE_LINE_SEGMENT_STRIDE + (activeIntervals + 1) * VERTICAL_LINE_SEGMENT_STRIDE)
        else FloatArray((activeIntervals + 3) * OUTLINE_LINE_SEGMENT_STRIDE)

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
            if (i < 1) {
                firstLoc = Position(loc.latitude, loc.longitude, center.altitude)
                addLineVertex(rc, loc.latitude, loc.longitude, center.altitude, verticalVertexIndex, true)
            }
            addLineVertex(rc, loc.latitude, loc.longitude, center.altitude, verticalVertexIndex, true)
        }

        addLineVertex(rc, firstLoc.latitude, firstLoc.longitude, firstLoc.altitude, verticalVertexIndex, false)
        addLineVertex(rc, firstLoc.latitude, firstLoc.longitude, firstLoc.altitude, verticalVertexIndex, false)

        // Add the interior spine point vertices
        for (i in 0 until spineCount) {
            center.greatCircleLocation(heading.plusDegrees(headingAdjustment), spineRadius[i], scratchPosition)
            addVertex(rc, scratchPosition.latitude, scratchPosition.longitude, center.altitude, arrayOffset, false)
        }

        assembleElements(activeIntervals)

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
        rc: RenderContext, latitude: Angle, longitude: Angle, altitude: Double, offset : Int, addIndices : Boolean
    ) {
        val vertex = lineVertexIndex / VERTEX_STRIDE
        val point = rc.geographicToCartesian(latitude, longitude, altitude, altitudeMode, scratchPoint)
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
                outlineElements.add(vertex)
                outlineElements.add(vertex + 1)
                outlineElements.add(vertex + 2)
                outlineElements.add(vertex + 3)
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
                outlineElements.add(vertex)
                outlineElements.add(vertex + 1)
                outlineElements.add(vertex + 2)
                outlineElements.add(vertex + 3)
            }
            if (isExtrude && addIndices) {
                val vertPoint = rc.geographicToCartesian(latitude, longitude, 0.0, altitudeMode, scratchVertPoint)
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

    protected open fun addVertex(
        rc: RenderContext, latitude: Angle, longitude: Angle, altitude: Double, offset: Int, isExtrudedSkirt: Boolean
    ) {
        var offsetVertexIndex = vertexIndex + offset
        val altitudeMode = if (isSurfaceShape) AltitudeMode.ABSOLUTE else altitudeMode
        var point = rc.geographicToCartesian(latitude, longitude, altitude, altitudeMode, scratchPoint)
        val texCoord2d = texCoord2d.copy(point).multiplyByMatrix(modelToTexCoord)
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
        lineVertexArray = FloatArray(0)
    }
}