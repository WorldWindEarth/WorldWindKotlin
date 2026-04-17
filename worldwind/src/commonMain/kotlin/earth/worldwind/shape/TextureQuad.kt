package earth.worldwind.shape

import earth.worldwind.draw.DrawQuadState
import earth.worldwind.draw.Drawable
import earth.worldwind.draw.DrawableSurfaceQuad
import earth.worldwind.geom.*
import earth.worldwind.globe.Globe
import earth.worldwind.render.RenderContext
import earth.worldwind.render.buffer.BufferObject
import earth.worldwind.render.program.SurfaceQuadShaderProgram
import earth.worldwind.render.program.TriangleShaderProgram
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.NumericArray
import earth.worldwind.util.kgl.GL_ARRAY_BUFFER
import earth.worldwind.util.kgl.GL_ELEMENT_ARRAY_BUFFER
import earth.worldwind.util.kgl.GL_TRIANGLES
import earth.worldwind.util.kgl.GL_UNSIGNED_INT
import kotlin.jvm.JvmOverloads
import earth.worldwind.render.image.ImageSource
import earth.worldwind.util.math.encodeOrientationVector

open class TextureQuad @JvmOverloads constructor(
    bottomLeft: Location, bottomRight: Location, topRight: Location, topLeft: Location,
    attributes: ShapeAttributes = ShapeAttributes()
) : AbstractShape(attributes) {
    constructor(
        bottomLeft: Location, bottomRight: Location, topRight: Location, topLeft: Location, imageSource: ImageSource
    ) : this(
        bottomLeft, bottomRight, topRight, topLeft, ShapeAttributes().apply {
            interiorImageSource = imageSource
            isDrawOutline = false
        }
    )

    constructor(sector: Sector, attributes: ShapeAttributes = ShapeAttributes()) : this(
        Location(sector.minLatitude, sector.minLongitude),
        Location(sector.minLatitude, sector.maxLongitude),
        Location(sector.maxLatitude, sector.maxLongitude),
        Location(sector.maxLatitude, sector.minLongitude),
        attributes
    )

    constructor(sector: Sector, imageSource: ImageSource) : this(
        Location(sector.minLatitude, sector.minLongitude),
        Location(sector.minLatitude, sector.maxLongitude),
        Location(sector.maxLatitude, sector.maxLongitude),
        Location(sector.maxLatitude, sector.minLongitude),
        ShapeAttributes().apply {
            interiorImageSource = imageSource
            isDrawOutline = false
        }
    )

    override val referencePosition: Position get() {
        val sector = Sector()
        for (position in locations) sector.union(position)
        return Position(sector.centroidLatitude, sector.centroidLongitude, 0.0)
    }
    protected val locations = arrayOf(bottomLeft, bottomRight, topRight, topLeft)
    protected val data = mutableMapOf<Globe.State?, TextureQuadData>()

    open class TextureQuadData {
        val vertexOrigin = Vec3()
        var vertexArray = FloatArray(0)
        val vertexBufferKey = Any()
        var refreshVertexArray = true

        var a = Vec2()
        var b = Vec2()
        var c = Vec2()
        var d = Vec2()

        var lineVertexArray = FloatArray(0)
        val outlineElements = mutableListOf<Int>()
        val vertexLinesBufferKey = Any()
        val elementLinesBufferKey = Any()
        var refreshLineVertexArray = true
    }

    companion object {
        protected const val VERTEX_STRIDE = 3
        protected const val VERTEX_LINE_STRIDE = 5
        protected const val OUTLINE_LINE_SEGMENT_STRIDE = 4 * VERTEX_LINE_STRIDE
        protected val SHARED_INDEX_ARRAY = intArrayOf(0, 1, 2, 2, 3, 0)
        private var sharedIndexBufferKey = Any()
        private var sharedIndexBuffer: BufferObject? = null

        protected lateinit var currentData: TextureQuadData

        protected var vertexIndex = 0

        protected var lineVertexIndex = 0
        protected val prevPoint = Vec3()
        protected var texCoord1d = 0.0

        fun getSharedIndexBuffer(rc: RenderContext): BufferObject? {
            sharedIndexBuffer = rc.getBufferObject(sharedIndexBufferKey) {
                BufferObject(GL_ELEMENT_ARRAY_BUFFER, 0)
            }

            rc.offerGLBufferUpload(sharedIndexBufferKey, 0) {
                NumericArray.Ints(SHARED_INDEX_ARRAY)
            }

            return sharedIndexBuffer
        }
    }

    init {
        altitudeMode = AltitudeMode.CLAMP_TO_GROUND
        isFollowTerrain = true
    }

    fun getAllLocations(): Array<Location> {
        return this.locations
    }

    fun getLocation(index: Int): Location {
        require(index in locations.indices) {
            logMessage(ERROR, "TextureQuad", "getLocation", "invalidIndex")
        }
        return locations[index]
    }

    fun setLocation(index: Int, location: Location) {
        require(index in locations.indices) {
            logMessage(ERROR, "TextureQuad", "setLocation", "invalidIndex")
        }
        reset()

        locations[index] = location
    }

    fun setLocations(bottomLeft: Location, bottomRight: Location, topRight: Location, topLeft: Location) {
        reset()
        locations[0] = bottomLeft
        locations[1] = bottomRight
        locations[2] = topRight
        locations[3] = topLeft
    }

    override fun resetGlobeState(globeState: Globe.State?) {
        super.resetGlobeState(globeState)
        data[globeState]?.refreshVertexArray = true
    }

    override fun reset() {
        super.reset()
        data.values.forEach { it.refreshVertexArray = true }
    }

    override fun moveTo(globe: Globe, position: Position) {
        val refPos = referencePosition
        for (pos in locations) {
            val distance = refPos.greatCircleDistance(pos)
            val azimuth = refPos.greatCircleAzimuth(pos)
            position.greatCircleLocation(azimuth, distance, pos)
        }
        reset()
    }

    override fun makeDrawable(rc: RenderContext) {
        if (locations.isEmpty()) return  // nothing to draw

        if (mustAssembleGeometry(rc)) {
            if (!rc.canAssembleGeometry()) return
            assembleGeometry(rc)
        }

        // Obtain a drawable form the render context pool.
        val drawable: Drawable
        val drawState: DrawQuadState
        val drawableLines: Drawable
        val drawStateLines: DrawQuadState
        val cameraDistance: Double
        if (isSurfaceShape) {
            val pool = rc.getDrawablePool(DrawableSurfaceQuad.KEY)
            drawable = DrawableSurfaceQuad.obtain(pool)
            drawState = drawable.drawState
            drawable.offset = rc.globe.offset
            drawable.sector.copy(currentBoundindData.boundingSector)
            drawable.version = computeVersion()
            drawable.isDynamic = isDynamic || rc.currentLayer.isDynamic
        } else {
            error("TextureQuad must be surface shape")
        }

        drawInterior(rc, drawState)
        rc.offerSurfaceDrawable(drawable, zOrder)

        if (activeAttributes.isDrawOutline) {
            drawableLines = DrawableSurfaceQuad.obtain(rc.getDrawablePool(DrawableSurfaceQuad.KEY))
            drawableLines.offset = rc.globe.offset
            drawableLines.sector.copy(currentBoundindData.boundingSector)
            drawableLines.version = computeVersion()
            drawableLines.isDynamic = isDynamic || rc.currentLayer.isDynamic

            cameraDistance = cameraDistanceGeographic(rc, currentBoundindData.boundingSector)
            drawStateLines = drawableLines.drawState

            drawOutline(rc, drawStateLines, cameraDistance)
            rc.offerSurfaceDrawable(drawableLines, zOrder)
        }
    }

    protected open fun drawInterior(rc: RenderContext, drawState: DrawQuadState) {
        if (!activeAttributes.isDrawInterior || rc.isPickMode && !activeAttributes.isPickInterior) return

        drawState.isLine = false

        // Use the basic GLSL program to draw the shape.
        drawState.programDrawToTexture = rc.getShaderProgram(SurfaceQuadShaderProgram.KEY) { SurfaceQuadShaderProgram() }
        drawState.programDrawTextureToTerrain = rc.getShaderProgram(TriangleShaderProgram.KEY) { TriangleShaderProgram() }

        // Assemble the drawable's OpenGL vertex buffer object.
        drawState.vertexBuffer = rc.getBufferObject(currentData.vertexBufferKey) {
            BufferObject(GL_ARRAY_BUFFER, 0)
        }
        rc.offerGLBufferUpload(currentData.vertexBufferKey, bufferDataVersion) {
            NumericArray.Floats(currentData.vertexArray)
        }

        // Use shared index(element) buffer for all TextureQuads
        drawState.elementBuffer = getSharedIndexBuffer(rc)

        // Configure the drawable to use the interior texture when drawing the interior.
        activeAttributes.interiorImageSource?.let { interiorImageSource ->
            rc.getTexture(interiorImageSource)?.let { texture ->
                drawState.texture = texture
                drawState.textureLod = 0
                drawState.texCoordMatrix.copy(texture.coordTransform)
            }
        } ?: run { drawState.texture = null }

        // Configure the drawable to display the shape's interior top.
        drawState.a.copy(currentData.a)
        drawState.b.copy(currentData.b)
        drawState.c.copy(currentData.c)
        drawState.d.copy(currentData.d)
        drawState.color.copy(if (rc.isPickMode) pickColor else activeAttributes.interiorColor)
        drawState.opacity = if (rc.isPickMode) 1f else rc.currentLayer.opacity
        drawState.vertexOrigin.copy(currentData.vertexOrigin)
        drawState.vertexStride = VERTEX_STRIDE * 4 // stride in bytes
        drawState.enableCullFace = isExtrude
        drawState.enableDepthTest = activeAttributes.isDepthTest
        drawState.enableLighting = activeAttributes.isLightingEnabled
        drawState.drawElements(GL_TRIANGLES, SHARED_INDEX_ARRAY.size, GL_UNSIGNED_INT, offset = 0)
    }

    protected open fun drawOutline(rc: RenderContext, drawState: DrawQuadState, cameraDistance: Double) {
        if (!activeAttributes.isDrawOutline || rc.isPickMode && !activeAttributes.isPickOutline) return

        // Use triangles mode to draw lines
        drawState.isLine = true

        // Use the geom lines GLSL program to draw the shape.
        drawState.programDrawToTexture = rc.getShaderProgram(TriangleShaderProgram.KEY) { TriangleShaderProgram() }
        drawState.programDrawTextureToTerrain = rc.getShaderProgram(TriangleShaderProgram.KEY) { TriangleShaderProgram() }

        // Assemble the drawable's OpenGL vertex buffer object.
        drawState.vertexBuffer = rc.getBufferObject(currentData.vertexLinesBufferKey) {
            BufferObject(GL_ARRAY_BUFFER, 0)
        }
        rc.offerGLBufferUpload(currentData.vertexLinesBufferKey, bufferDataVersion) {
            NumericArray.Floats(currentData.lineVertexArray)
        }

        // Assemble the drawable's OpenGL element buffer object.
        drawState.elementBuffer = rc.getBufferObject(currentData.elementLinesBufferKey) {
            BufferObject(GL_ELEMENT_ARRAY_BUFFER, 0)
        }
        rc.offerGLBufferUpload(currentData.elementLinesBufferKey, bufferDataVersion) {
            val array = IntArray(currentData.outlineElements.size)
            var index = 0
            for (element in currentData.outlineElements) array[index++] = element
            NumericArray.Ints(array)
        }

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
        drawState.vertexOrigin.copy(currentData.vertexOrigin)
        drawState.enableCullFace = false
        drawState.enableDepthTest = activeAttributes.isDepthTest
        drawState.enableLighting = activeAttributes.isLightingEnabled
        drawState.drawElements(GL_TRIANGLES, currentData.outlineElements.size, GL_UNSIGNED_INT, offset = 0)
    }

    protected open fun mustAssembleGeometry(rc: RenderContext): Boolean {
        currentData = data[rc.globeState] ?: TextureQuadData().also { data[rc.globeState] = it }
        return currentData.refreshVertexArray || isExtrude && !isSurfaceShape
    }

    protected open fun assembleGeometry(rc: RenderContext) {
        // Clear the shape's vertex array and element arrays. These arrays will accumulate values as the shape's
        // geometry is assembled.
        vertexIndex = 0
        currentData.vertexArray = FloatArray(locations.size * VERTEX_STRIDE) // Reserve boundaries.size for combined vertexes

        computeQuadLocalCorners()

        // Add the remaining boundary vertices, tessellating each edge as indicated by the polygon's properties.
        for (pos in locations) {
            addVertex(rc, pos.latitude, pos.longitude)
        }

        if (activeAttributes.isDrawOutline) {
            lineVertexIndex = 0
            val lastEqualsFirst = locations.first() == locations.last()
            val lineVertexCount = locations.size + if (lastEqualsFirst) 2 else 3
            currentData.lineVertexArray = FloatArray(lineVertexCount * OUTLINE_LINE_SEGMENT_STRIDE)
            currentData.outlineElements.clear()

            val pos0 = locations[0]
            var begin = pos0
            addLineVertex(rc, begin.latitude, begin.longitude, isIntermediate = true, addIndices = true)
            addLineVertex(rc, begin.latitude, begin.longitude, isIntermediate = false, addIndices = true)
            for (idx in 1 until locations.size) {
                val end = locations[idx]
                val addIndices = idx != locations.size - 1 || end != pos0 // check if there is implicit closing edge
                calcPoint(rc, end.latitude, end.longitude, 0.0, isAbsolute = false, isExtrudedSkirt = false)
                addLineVertex(rc, end.latitude, end.longitude, isIntermediate = false, addIndices)
                begin = end
            }

            if (begin != pos0) {
                // Add additional dummy vertex with the same data after the last vertex.
                calcPoint(rc, pos0.latitude, pos0.longitude, 0.0, isAbsolute = false, isExtrudedSkirt = false)
                addLineVertex(rc, pos0.latitude, pos0.longitude, isIntermediate = true, addIndices = false)
                addLineVertex(rc, pos0.latitude, pos0.longitude, isIntermediate = true, addIndices = false)
            } else {
                calcPoint(rc, begin.latitude, begin.longitude, 0.0, isAbsolute = false, isExtrudedSkirt = false)
                addLineVertex(rc, begin.latitude, begin.longitude, isIntermediate = true, addIndices = false)
            }
            // Drop last six indices as they are used for connecting segments and there's no next segment for last vertices (check addLineVertex)
            currentData.outlineElements.subList(currentData.outlineElements.size - 6, currentData.outlineElements.size).clear()
        }

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
                error("Texture quad must be surface shape")
            }
        }

        // Adjust final vertex array size to save memory (and fix cameraDistanceCartesian calculation)
        currentData.vertexArray = currentData.vertexArray.copyOf(vertexIndex)
    }

    protected open fun addVertex(
        rc: RenderContext, latitude: Angle, longitude: Angle
    ): Int = with(currentData) {
        val vertex = vertexIndex / VERTEX_STRIDE

        if (vertex == 0) {
            vertexOrigin.set(longitude.inDegrees, latitude.inDegrees, 0.0)
        }
        vertexArray[vertexIndex++] = (longitude.inDegrees - vertexOrigin.x).toFloat()
        vertexArray[vertexIndex++] = (latitude.inDegrees - vertexOrigin.y).toFloat()
        vertexArray[vertexIndex++] = 0.0f
        vertex
    }

    protected open fun addLineVertex(
        rc: RenderContext, latitude: Angle, longitude: Angle, isIntermediate: Boolean, addIndices: Boolean
    ) = with(currentData) {
        val vertex = lineVertexIndex / VERTEX_LINE_STRIDE
        if (lineVertexIndex == 0) texCoord1d = 0.0 else texCoord1d += point.distanceTo(prevPoint)
        prevPoint.copy(point)
        val upperLeftCorner = encodeOrientationVector(-1f, 1f)
        val lowerLeftCorner = encodeOrientationVector(-1f, -1f)
        val upperRightCorner = encodeOrientationVector(1f, 1f)
        val lowerRightCorner = encodeOrientationVector(1f, -1f)
        if (isSurfaceShape) {
            lineVertexArray[lineVertexIndex++] = (longitude.inDegrees - vertexOrigin.x).toFloat()
            lineVertexArray[lineVertexIndex++] = (latitude.inDegrees - vertexOrigin.y).toFloat()
            lineVertexArray[lineVertexIndex++] = 0.0f
            lineVertexArray[lineVertexIndex++] = upperLeftCorner
            lineVertexArray[lineVertexIndex++] = texCoord1d.toFloat()

            lineVertexArray[lineVertexIndex++] = (longitude.inDegrees - vertexOrigin.x).toFloat()
            lineVertexArray[lineVertexIndex++] = (latitude.inDegrees - vertexOrigin.y).toFloat()
            lineVertexArray[lineVertexIndex++] = 0.0f
            lineVertexArray[lineVertexIndex++] = lowerLeftCorner
            lineVertexArray[lineVertexIndex++] = texCoord1d.toFloat()

            lineVertexArray[lineVertexIndex++] = (longitude.inDegrees - vertexOrigin.x).toFloat()
            lineVertexArray[lineVertexIndex++] = (latitude.inDegrees - vertexOrigin.y).toFloat()
            lineVertexArray[lineVertexIndex++] = 0.0f
            lineVertexArray[lineVertexIndex++] = upperRightCorner
            lineVertexArray[lineVertexIndex++] = texCoord1d.toFloat()

            lineVertexArray[lineVertexIndex++] = (longitude.inDegrees - vertexOrigin.x).toFloat()
            lineVertexArray[lineVertexIndex++] = (latitude.inDegrees - vertexOrigin.y).toFloat()
            lineVertexArray[lineVertexIndex++] = 0.0f
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
        }
    }

    fun computeQuadLocalCorners() {
        currentData.vertexOrigin.set(locations[0].longitude.inDegrees, locations[0].latitude.inDegrees, 1.0)
        currentData.a = Vec2(
            locations[0].longitude.inDegrees - currentData.vertexOrigin.x,
            locations[0].latitude.inDegrees - currentData.vertexOrigin.y
        )
        currentData.b = Vec2(
            locations[1].longitude.inDegrees - currentData.vertexOrigin.x,
            locations[1].latitude.inDegrees - currentData.vertexOrigin.y
        )
        currentData.c = Vec2(
            locations[2].longitude.inDegrees - currentData.vertexOrigin.x,
            locations[2].latitude.inDegrees - currentData.vertexOrigin.y
        )
        currentData.d = Vec2(
            locations[3].longitude.inDegrees - currentData.vertexOrigin.x,
            locations[3].latitude.inDegrees - currentData.vertexOrigin.y
        )
    }
}