package earth.worldwind.shape

import earth.worldwind.draw.DrawShapeState
import earth.worldwind.draw.Drawable
import earth.worldwind.draw.DrawableShape
import earth.worldwind.draw.DrawableSurfaceShape
import earth.worldwind.geom.*
import earth.worldwind.render.*
import earth.worldwind.render.buffer.FloatBufferObject
import earth.worldwind.render.buffer.ShortBufferObject
import earth.worldwind.render.image.ImageOptions
import earth.worldwind.render.image.ResamplingMode
import earth.worldwind.render.image.WrapMode
import earth.worldwind.render.program.BasicShaderProgram
import earth.worldwind.shape.PathType.*
import earth.worldwind.util.kgl.*
import kotlin.jvm.JvmOverloads

open class Path @JvmOverloads constructor(
    positions: List<Position>, attributes: ShapeAttributes = ShapeAttributes()
): AbstractShape(attributes) {
    var positions = positions
        set(value) {
            field = value
            reset()
        }
    var isExtrude = false
        set(value) {
            field = value
            reset()
        }
    var isFollowTerrain = false
        set(value) {
            field = value
            reset()
        }
    protected var vertexArray = FloatArray(0)
    protected var vertexIndex = 0
    // TODO Use ShortArray instead of mutableListOf<Short> to avoid unnecessary memory re-allocations
    protected val interiorElements = mutableListOf<Short>()
    protected val outlineElements = mutableListOf<Short>()
    protected val verticalElements = mutableListOf<Short>()
    protected lateinit var vertexBufferKey: Any
    protected lateinit var elementBufferKey: Any
    protected val vertexOrigin = Vec3()
    protected var isSurfaceShape = false
    protected var texCoord1d = 0.0
    private val point = Vec3()
    private val prevPoint = Vec3()
    private val texCoordMatrix = Matrix3()
    private val intermediateLocation = Location()

    companion object {
        protected const val VERTEX_STRIDE = 4
        protected val defaultOutlineImageOptions = ImageOptions().apply {
            resamplingMode = ResamplingMode.NEAREST_NEIGHBOR
            wrapMode = WrapMode.REPEAT
        }

        protected fun nextCacheKey() = Any()
    }

    override fun reset() {
        vertexArray = FloatArray(0)
        interiorElements.clear()
        outlineElements.clear()
        verticalElements.clear()
    }

    override fun makeDrawable(rc: RenderContext) {
        if (positions.isEmpty()) return  // nothing to draw

        if (mustAssembleGeometry(rc)) {
            assembleGeometry(rc)
            vertexBufferKey = nextCacheKey()
            elementBufferKey = nextCacheKey()
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
            ShortBufferObject(GL_ELEMENT_ARRAY_BUFFER, (interiorElements + outlineElements + verticalElements).toShortArray())
        }

        // Configure the drawable's vertex texture coordinate attribute.
        drawState.texCoordAttrib(1 /*size*/, 12 /*stride in bytes*/)

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
            drawState.lineWidth(activeAttributes.outlineWidth + if (isSurfaceShape) 0.5f else 0f)
            drawState.drawElements(
                GL_LINE_STRIP, outlineElements.size,
                GL_UNSIGNED_SHORT, interiorElements.size * 2
            )
        }

        // Disable texturing for the remaining drawable primitives.
        drawState.texture(null)

        // Configure the drawable to display the shape's extruded verticals.
        if (activeAttributes.isDrawOutline && activeAttributes.isDrawVerticals && isExtrude) {
            drawState.color(if (rc.isPickMode) pickColor else activeAttributes.outlineColor)
            drawState.lineWidth(activeAttributes.outlineWidth)
            drawState.drawElements(
                GL_LINES, verticalElements.size,
                GL_UNSIGNED_SHORT, interiorElements.size * 2 + outlineElements.size * 2
            )
        }

        // Configure the drawable to display the shape's extruded interior.
        if (activeAttributes.isDrawInterior && isExtrude) {
            drawState.color(if (rc.isPickMode) pickColor else activeAttributes.interiorColor)
            drawState.drawElements(
                GL_TRIANGLE_STRIP, interiorElements.size,
                GL_UNSIGNED_SHORT, 0
            )
        }

        // Configure the drawable according to the shape's attributes.
        drawState.vertexOrigin.copy(vertexOrigin)
        drawState.vertexStride = VERTEX_STRIDE * 4 // stride in bytes
        drawState.enableCullFace = false
        drawState.enableDepthTest = activeAttributes.isDepthTest

        // Enqueue the drawable for processing on the OpenGL thread.
        if (isSurfaceShape) rc.offerSurfaceDrawable(drawable, 0.0 /*zOrder*/)
        else rc.offerShapeDrawable(drawable, cameraDistance)
    }

    protected open fun mustAssembleGeometry(rc: RenderContext) = vertexArray.isEmpty()

    protected open fun assembleGeometry(rc: RenderContext) {
        // Determine whether the shape geometry must be assembled as Cartesian geometry or as geographic geometry.
        isSurfaceShape = altitudeMode == AltitudeMode.CLAMP_TO_GROUND && isFollowTerrain

        // Determine the number of vertexes
        val vertexCount = if (isSurfaceShape || maximumIntermediatePoints <= 0 || pathType == LINEAR) positions.size
        else if(positions.isNotEmpty()) positions.size + (positions.size - 1) * maximumIntermediatePoints else 0

        // Clear the shape's vertex array and element arrays. These arrays will accumulate values as the shapes's
        // geometry is assembled.
        vertexIndex = 0
        vertexArray = if (isExtrude && !isSurfaceShape) FloatArray(vertexCount * 2 * VERTEX_STRIDE)
        else FloatArray(vertexCount * VERTEX_STRIDE)
        interiorElements.clear()
        outlineElements.clear()
        verticalElements.clear()

        // Add the first vertex.
        var begin = positions[0]
        addVertex(rc, begin.latitude, begin.longitude, begin.altitude, false /*intermediate*/)

        // Add the remaining vertices, inserting vertices along each edge as indicated by the path's properties.
        for (idx in 1 until positions.size) {
            val end = positions[idx]
            addIntermediateVertices(rc, begin, end)
            addVertex(rc, end.latitude, end.longitude, end.altitude, false /*intermediate*/)
            begin = end
        }

        // Compute the shape's bounding box or bounding sector from its assembled coordinates.
        if (isSurfaceShape) {
            boundingSector.setEmpty()
            boundingSector.union(vertexArray, vertexIndex, VERTEX_STRIDE)
            boundingSector.translate(vertexOrigin.y /*latitude*/, vertexOrigin.x /*longitude*/)
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
            addVertex(rc, loc.latitude, loc.longitude, alt, true /*intermediate*/)
            dist += deltaDist
            alt += deltaAlt
        }
    }

    protected open fun addVertex(
        rc: RenderContext, latitude: Angle, longitude: Angle, altitude: Double, intermediate: Boolean
    ): Int {
        val vertex = vertexIndex / VERTEX_STRIDE
        var point = rc.geographicToCartesian(latitude, longitude, altitude, altitudeMode, point)
        if (vertex == 0) {
            if (isSurfaceShape) vertexOrigin.set(longitude.degrees, latitude.degrees, altitude)
            else vertexOrigin.copy(point)
            texCoord1d = 0.0
        } else {
            texCoord1d += point.distanceTo(prevPoint)
        }
        prevPoint.copy(point)
        if (isSurfaceShape) {
            vertexArray[vertexIndex++] = (longitude.degrees - vertexOrigin.x).toFloat()
            vertexArray[vertexIndex++] = (latitude.degrees - vertexOrigin.y).toFloat()
            vertexArray[vertexIndex++] = (altitude - vertexOrigin.z).toFloat()
            vertexArray[vertexIndex++] = texCoord1d.toFloat()
            outlineElements.add(vertex.toShort())
        } else {
            vertexArray[vertexIndex++] = (point.x - vertexOrigin.x).toFloat()
            vertexArray[vertexIndex++] = (point.y - vertexOrigin.y).toFloat()
            vertexArray[vertexIndex++] = (point.z - vertexOrigin.z).toFloat()
            vertexArray[vertexIndex++] = texCoord1d.toFloat()
            outlineElements.add(vertex.toShort())
            if (isExtrude) {
                point = rc.geographicToCartesian(latitude, longitude, 0.0, altitudeMode, this.point)
                vertexArray[vertexIndex++] = (point.x - vertexOrigin.x).toFloat()
                vertexArray[vertexIndex++] = (point.y - vertexOrigin.y).toFloat()
                vertexArray[vertexIndex++] = (point.z - vertexOrigin.z).toFloat()
                vertexArray[vertexIndex++] = 0f /*unused*/
                interiorElements.add(vertex.toShort())
                interiorElements.add(vertex.inc().toShort())
            }
            if (isExtrude && !intermediate) {
                verticalElements.add(vertex.toShort())
                verticalElements.add(vertex.inc().toShort())
            }
        }
        return vertex
    }
}