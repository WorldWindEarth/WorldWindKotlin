package earth.worldwind.shape

import earth.worldwind.PickedObject
import earth.worldwind.draw.DrawShapeState
import earth.worldwind.draw.Drawable
import earth.worldwind.draw.DrawableShape
import earth.worldwind.draw.DrawableSurfaceShape
import earth.worldwind.geom.*
import earth.worldwind.render.*
import earth.worldwind.render.buffer.FloatBufferObject
import earth.worldwind.render.buffer.IntBufferObject
import earth.worldwind.render.image.ImageOptions
import earth.worldwind.render.image.ImageSource
import earth.worldwind.render.image.ResamplingMode
import earth.worldwind.render.image.WrapMode
import earth.worldwind.render.program.TriangleShaderProgram
import earth.worldwind.shape.PathType.*
import earth.worldwind.util.kgl.*

open class LineSetAttributes(path : Path) {
    val isSurfaceShape: Boolean = path.isSurfaceShape
    val enableDepthTest: Boolean = path.activeAttributes.isDepthTest
    val enableDepthWrite: Boolean = path.activeAttributes.isDepthWrite
    val outlineImageSource: ImageSource? = path.activeAttributes.outlineImageSource

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LineSetAttributes) return false
        return this.isSurfaceShape == other.isSurfaceShape && this.enableDepthTest == other.enableDepthTest && this.enableDepthWrite == other.enableDepthWrite
    }

    override fun hashCode(): Int {
        var result = isSurfaceShape.hashCode()
        result = 31 * result + enableDepthTest.hashCode()
        result = 31 * result + enableDepthWrite.hashCode()
        result = 31 * result + (outlineImageSource?.hashCode() ?: 0)
        return result
    }
}

open class PathSet(private val attributes: LineSetAttributes): Boundable {

    protected var vertexArray = FloatArray(0)
    protected var colorArray = IntArray(0)
    protected var pickColorArray = IntArray(0)
    protected var widthArray = FloatArray(0)
    protected var vertexIndex = 0
    protected var colorWidthIndex = 0
    protected val outlineElements = mutableListOf<Int>()
    protected val paths: Array<Path?> = arrayOfNulls(MAX_PATHS)
    protected var pathCount: Int = 0
    protected lateinit var vertexBufferKey: Any
    protected lateinit var colorBufferKey: Any
    protected lateinit var pickColorBufferKey: Any
    protected lateinit var widthBufferKey: Any
    protected lateinit var elementBufferKey: Any
    protected val vertexOrigin = Vec3()
    protected var texCoord1d = 0.0
    private val point = Vec3()
    private val prevPoint = Vec3()
    private val intermediateLocation = Location()
    private val texCoordMatrix = Matrix3()

    override val boundingSector = Sector()
    override val boundingBox = BoundingBox()
    override val scratchPoint = Vec3()

    companion object {
        protected const val MAX_PATHS = 256
        protected const val VERTEX_STRIDE = 10
        const val NEAR_ZERO_THRESHOLD = 1.0e-10
        protected val defaultOutlineImageOptions = ImageOptions().apply {
            resamplingMode = ResamplingMode.NEAREST_NEIGHBOR
            wrapMode = WrapMode.REPEAT
        }

        protected fun nextCacheKey() = Any()
    }

    fun isFull() : Boolean
    {
        return pathCount == MAX_PATHS
    }

    fun addPath(path : Path) : Boolean {
        if (isFull()) return false
        paths[pathCount++] = path
        reset()
        return true
    }

    fun removePath(path : Path) : Boolean {
        if (pathCount == 0) return false
        val index = paths.indexOf(path)
        if (index == -1) return false
        paths[index] = paths[--pathCount]
        paths[pathCount] = null
        reset()
        return true
    }

    // Override doRender here to remove pick related logic from AbstractShape, it's handled by individual lines
     fun render(rc: RenderContext) {
        if (!isWithinProjectionLimits(rc)) return

        // Don't render anything if the shape is not visible.
        if (!intersectsFrustum(rc)) return

        // Enqueue drawables for processing on the OpenGL thread.
        makeDrawable(rc)
    }

    fun reset() {
        boundingBox.setToUnitBox()
        boundingSector.setEmpty()
        vertexArray = FloatArray(0)
        colorArray = IntArray(0)
        pickColorArray = IntArray(0)
        widthArray = FloatArray(0)
        outlineElements.clear()
    }

    protected open fun computeRepeatingTexCoordTransform(texture: Texture, metersPerPixel: Double, result: Matrix3): Matrix3 {
        val texCoordMatrix = result.setToIdentity()
        texCoordMatrix.setScale(1.0 / (texture.width * metersPerPixel), 1.0 / (texture.height * metersPerPixel))
        texCoordMatrix.multiplyByMatrix(texture.coordTransform)
        return texCoordMatrix
    }

    private fun makeDrawable(rc: RenderContext) {
        if (pathCount == 0) return  // nothing to draw

        var assembleBuffers = vertexArray.isEmpty()
        val pickColorOffset = if(rc.isPickMode) rc.reservePickedObjectIdRange(pathCount) else 0
        for (idx in 0 until pathCount ) {
            val path = paths[idx] ?: break
            if (path.positions.isEmpty()) continue

            assembleBuffers = assembleBuffers || path.forceRecreateBatch
            path.forceRecreateBatch = false

            if(rc.isPickMode) {
                rc.offerPickedObject(PickedObject.fromRenderable(pickColorOffset + idx, path, rc.currentLayer))
            }
        }

        // reset caches depending on flags
        if (assembleBuffers) {
            assembleBuffers(rc)
            vertexBufferKey = nextCacheKey()
            elementBufferKey = nextCacheKey()
            colorBufferKey = nextCacheKey()
            pickColorBufferKey = nextCacheKey()
            widthBufferKey = nextCacheKey()
        }

        // Obtain a drawable form the render context pool, and compute distance to the render camera.
        val drawable: Drawable
        val drawState: DrawShapeState
        val cameraDistance: Double
        if (attributes.isSurfaceShape) {
            val pool = rc.getDrawablePool<DrawableSurfaceShape>()
            drawable = DrawableSurfaceShape.obtain(pool)
            drawState = drawable.drawState
            cameraDistance = cameraDistanceGeographic(rc, boundingSector)
            drawable.offset = rc.globe.offset
            drawable.sector.copy(boundingSector)
        } else {
            val pool = rc.getDrawablePool<DrawableShape>()
            drawable = DrawableShape.obtain(pool)
            drawState = drawable.drawState
            cameraDistance = cameraDistanceCartesian(
                rc, vertexArray, vertexArray.size,
                VERTEX_STRIDE, vertexOrigin
            )
        }

        // Convert pickColorOffset to colorOffset
        if(rc.isPickMode) {
            drawState.pickIdOffset = pickColorOffset
        }

        // Use the basic GLSL program to draw the shape.
        drawState.program = rc.getShaderProgram { TriangleShaderProgram() }

        // Assemble the drawable's OpenGL vertex buffer object.
        val vertexBuffer = rc.getBufferObject(vertexBufferKey) { FloatBufferObject(GL_ARRAY_BUFFER, vertexArray) }
        drawState.vertexState.addAttribute(0, vertexBuffer, 4, GL_FLOAT, false, 20, 0) // pointA
        drawState.vertexState.addAttribute(1, vertexBuffer, 4, GL_FLOAT, false, 20, 40) // pointB
        drawState.vertexState.addAttribute(2, vertexBuffer, 4, GL_FLOAT, false, 20, 80) // pointC
        drawState.vertexState.addAttribute(3, vertexBuffer, 1, GL_FLOAT, false, 20,56) // texCoord

        val colorBuffer = rc.getBufferObject(colorBufferKey) { IntBufferObject(GL_ARRAY_BUFFER, colorArray) }
        val pickColorBuffer = rc.getBufferObject(pickColorBufferKey) { IntBufferObject(GL_ARRAY_BUFFER, pickColorArray) }
        drawState.vertexState.addAttribute(4, if(rc.isPickMode) pickColorBuffer else colorBuffer, 4, GL_UNSIGNED_BYTE, true, 0, 0) // color

        val widthBuffer = rc.getBufferObject(widthBufferKey) { FloatBufferObject(GL_ARRAY_BUFFER, widthArray) }
        drawState.vertexState.addAttribute(5, widthBuffer, 1, GL_FLOAT, false, 0,0) // lineWidth

        // Assemble the drawable's OpenGL element buffer object.
        drawState.elementBuffer = rc.getBufferObject(elementBufferKey) {
            val array = IntArray(outlineElements.size)
            var index = 0
            for (element in outlineElements) array[index++] = element
            IntBufferObject(GL_ELEMENT_ARRAY_BUFFER, array)
        }

        // Configure the drawable to use the outline texture when drawing the outline.
        attributes.outlineImageSource?.let { outlineImageSource ->
            rc.getTexture(outlineImageSource, defaultOutlineImageOptions)?.let { texture ->
                val metersPerPixel = rc.pixelSizeAtDistance(cameraDistance)
                computeRepeatingTexCoordTransform(texture, metersPerPixel, texCoordMatrix)
                drawState.texture(texture)
                drawState.texCoordMatrix(texCoordMatrix)
            }
        }

        // Configure the drawable to display the shape's extruded verticals.
        drawState.opacity(if (rc.isPickMode) 1f else rc.currentLayer.opacity)
        drawState.drawElements(
            GL_TRIANGLES, outlineElements.size,
            GL_UNSIGNED_INT, 0
        )

        // Configure the drawable according to the shape's attributes.
        drawState.isLine = true
        drawState.isStatic = true
        drawState.vertexOrigin.copy(vertexOrigin)
        drawState.enableCullFace = false
        drawState.enableDepthTest = attributes.enableDepthTest
        drawState.enableDepthWrite = attributes.enableDepthWrite

        // Enqueue the drawable for processing on the OpenGL thread.
        if (attributes.isSurfaceShape) rc.offerSurfaceDrawable(drawable, 0.0 /*zOrder*/)
        else rc.offerShapeDrawable(drawable, cameraDistance)
    }

    protected open fun assembleBuffers(rc: RenderContext) {
        // Determine the number of vertexes
        var vertexCount = 0
        for (idx in 0 until pathCount ) {
            val path = paths[idx] ?: break // break never gonna be hit, need something better
            val p = path.positions

            if (p.isEmpty()) continue

            val noIntermediatePoints = path.maximumIntermediatePoints <= 0 || path.pathType == LINEAR

            vertexCount += if (noIntermediatePoints) {
                p.size + 2
            } else {
                2 + p.size + (p.size - 1) * path.maximumIntermediatePoints
            }
        }

        // Clear the shape's vertex array and element arrays. These arrays will accumulate values as the shapes's
        // geometry is assembled.
        vertexIndex = 0
        colorWidthIndex = 0
        vertexArray = FloatArray(vertexCount * VERTEX_STRIDE)
        outlineElements.clear()
        colorArray = IntArray(vertexCount * 2)
        pickColorArray = IntArray(vertexCount * 2)
        widthArray = FloatArray(vertexCount * 2)

        for (idx in 0 until pathCount ) {
            val path = paths[idx] ?: break
            val positions = path.positions
            if (positions.isEmpty()) continue  // no boundary positions to assemble

            // Get colors and width
            val colorInt = path.activeAttributes.outlineColor.toColorIntRGBA()
            val lineWidth = path.activeAttributes.outlineWidth + if(attributes.isSurfaceShape) 0.5f else 0f
            val pickColor = Color(0.0f,0.0f,0.0f,0.0f)
            PickedObject.identifierToUniqueColor(idx, pickColor)
            val pickColorInt = pickColor.toColorIntRGBA()

            // Reset texCoord per path
            texCoord1d = 0.0

            // Add the first vertex.
            var begin = positions[0]
            addVertices(rc, begin.latitude, begin.longitude, begin.altitude, path.altitudeMode, lineWidth, colorInt, pickColorInt, addIndices = false, isFirstVertex =  true)
            addVertices(rc, begin.latitude, begin.longitude, begin.altitude, path.altitudeMode, lineWidth, colorInt, pickColorInt, addIndices = false, isFirstVertex = false)
            // Add the remaining vertices, inserting vertices along each edge as indicated by the path's properties.
            for (vertexIdx in 1 until positions.size) {
                val end = positions[vertexIdx]
                addIntermediateVertices(rc, begin, end, path.maximumIntermediatePoints, path.pathType, path.altitudeMode, lineWidth, colorInt, pickColorInt)
                addVertices(rc, end.latitude, end.longitude, end.altitude, path.altitudeMode, lineWidth, colorInt, pickColorInt,addIndices = true,  isFirstVertex = false)
                begin = end
            }
            addVertices(rc, begin.latitude, begin.longitude, begin.altitude, path.altitudeMode, lineWidth, colorInt, pickColorInt,addIndices = false,  isFirstVertex = false)
        }

        // Compute the shape's bounding box or bounding sector from its assembled coordinates.
        if (attributes.isSurfaceShape) {
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

    protected open fun addIntermediateVertices(rc: RenderContext, begin: Position, end: Position, maximumIntermediatePoints : Int, pathType: PathType,
                                               altitudeMode: AltitudeMode, lineWidth : Float, colorInt : Int, pickColorInt: Int) {
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
            addVertices(rc, loc.latitude, loc.longitude, alt, altitudeMode, lineWidth, colorInt, pickColorInt,true, false)
            dist += deltaDist
            alt += deltaAlt
        }
    }

    protected open fun addVertices(
        rc: RenderContext, latitude: Angle, longitude: Angle, altitude: Double, altitudeMode: AltitudeMode, width : Float, colorInt : Int, pickColorInt: Int, addIndices : Boolean, isFirstVertex : Boolean
    ) {
        val vertex = (vertexIndex / VERTEX_STRIDE - 1) * 2
        val point = rc.geographicToCartesian(latitude, longitude, altitude, altitudeMode, point)
        if (vertexIndex == 0) {
            if (attributes.isSurfaceShape) vertexOrigin.set(
                longitude.inDegrees,
                latitude.inDegrees,
                altitude
            )
            else vertexOrigin.copy(point)
            texCoord1d = 0.0
        }

        if(!isFirstVertex) {
            texCoord1d += point.distanceTo(prevPoint)
        }
        prevPoint.copy(point)

        // Duplicate  width and color because this function adds 2 vertices
        widthArray[colorWidthIndex] = width
        colorArray[colorWidthIndex] = colorInt
        pickColorArray[colorWidthIndex] = pickColorInt
        ++colorWidthIndex
        widthArray[colorWidthIndex] = width
        colorArray[colorWidthIndex] = colorInt
        pickColorArray[colorWidthIndex] = pickColorInt
        ++colorWidthIndex

        if (attributes.isSurfaceShape) {
            vertexArray[vertexIndex++] = (longitude.inDegrees - vertexOrigin.x).toFloat()
            vertexArray[vertexIndex++] = (latitude.inDegrees - vertexOrigin.y).toFloat()
            vertexArray[vertexIndex++] = (altitude - vertexOrigin.z).toFloat()
            vertexArray[vertexIndex++] = 1.0f
            vertexArray[vertexIndex++] = texCoord1d.toFloat()

            vertexArray[vertexIndex++] = (longitude.inDegrees - vertexOrigin.x).toFloat()
            vertexArray[vertexIndex++] = (latitude.inDegrees - vertexOrigin.y).toFloat()
            vertexArray[vertexIndex++] = (altitude - vertexOrigin.z).toFloat()
            vertexArray[vertexIndex++] = -1.0f
            vertexArray[vertexIndex++] = texCoord1d.toFloat()

            if (addIndices) {
                outlineElements.add(vertex - 2) // 0    0 -- 2
                outlineElements.add(vertex - 1) // 1    |  / | ----> line goes this way
                outlineElements.add(vertex) // 2        | /  | ----> line goes this way
                outlineElements.add(vertex) // 2        1 -- 3
                outlineElements.add(vertex - 1) // 1
                outlineElements.add(vertex + 1) // 3
            }
        } else {
            vertexArray[vertexIndex++] = (point.x - vertexOrigin.x).toFloat()
            vertexArray[vertexIndex++] = (point.y - vertexOrigin.y).toFloat()
            vertexArray[vertexIndex++] = (point.z - vertexOrigin.z).toFloat()
            vertexArray[vertexIndex++] = 1.0f
            vertexArray[vertexIndex++] = texCoord1d.toFloat()
            vertexArray[vertexIndex++] = (point.x - vertexOrigin.x).toFloat()
            vertexArray[vertexIndex++] = (point.y - vertexOrigin.y).toFloat()
            vertexArray[vertexIndex++] = (point.z - vertexOrigin.z).toFloat()
            vertexArray[vertexIndex++] = -1.0f
            vertexArray[vertexIndex++] = texCoord1d.toFloat()
            if (addIndices) {
                outlineElements.add(vertex - 2) // 0    0 -- 2
                outlineElements.add(vertex - 1) // 1    |  / | ----> line goes this way
                outlineElements.add(vertex) // 2        | /  | ----> line goes this way
                outlineElements.add(vertex) // 2        1 -- 3
                outlineElements.add(vertex - 1) // 1
                outlineElements.add(vertex + 1) // 3
            }
        }
    }
}