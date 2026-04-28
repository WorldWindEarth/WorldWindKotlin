package earth.worldwind.shape

import earth.worldwind.draw.DrawQuadState
import earth.worldwind.draw.Drawable
import earth.worldwind.draw.DrawableSurfaceQuad
import earth.worldwind.geom.*
import earth.worldwind.globe.Globe
import earth.worldwind.render.RenderContext
import earth.worldwind.render.Texture
import earth.worldwind.render.buffer.BufferObject
import earth.worldwind.render.program.SurfaceQuadShaderProgram
import earth.worldwind.render.program.SurfaceQuadShaderProgramOES
import earth.worldwind.render.program.TriangleShaderProgram
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.NumericArray
import earth.worldwind.util.kgl.GL_ARRAY_BUFFER
import earth.worldwind.util.kgl.GL_ELEMENT_ARRAY_BUFFER
import earth.worldwind.util.kgl.GL_TEXTURE_EXTERNAL_OES
import earth.worldwind.util.kgl.GL_TRIANGLES
import earth.worldwind.util.kgl.GL_UNSIGNED_INT
import kotlin.jvm.JvmOverloads
import earth.worldwind.render.image.ImageSource
import earth.worldwind.util.math.encodeOrientationVector

open class ProjectedMediaSurface @JvmOverloads constructor(
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

    /**
     * Direct GPU-backed [Texture] for the quad's interior. Takes precedence over
     * [ShapeAttributes.interiorImageSource] when set. Use this for content that's already on
     * the GPU - e.g. an Android `SurfaceTexture` driven by `MediaPlayer` (typically wrapped as
     * an [earth.worldwind.render.OesExternalTexture]) or any render-to-texture output. When the
     * texture's target is `GL_TEXTURE_EXTERNAL_OES`, the OES variant of the surface quad
     * shader is used automatically; the interior projection logic is otherwise unchanged.
     *
     * Caller owns the texture's lifecycle.
     */
    var texture: Texture? = null

    override val referencePosition: Position get() {
        val sector = Sector()
        for (position in locations) sector.union(position)
        return Position(sector.centroidLatitude, sector.centroidLongitude, 0.0)
    }
    protected val locations = arrayOf(bottomLeft, bottomRight, topRight, topLeft)
    protected val data = mutableMapOf<Globe.State?, ProjectedMediaSurfaceData>()

    open class ProjectedMediaSurfaceData {
        val vertexOrigin = Vec3()
        var vertexArray = FloatArray(0)
        val vertexBufferKey = Any()
        var refreshVertexArray = true

        /**
         * Ground-to-image homography in the local frame the vertex buffer lives in (corner
         * positions relative to [vertexOrigin]). Mapping: H * (P_x, P_y, 1)^T -> (u*w, v*w, w);
         * fragment shader divides by w to land in unit-square image space. Recomputed every
         * time the four ground corners change.
         */
        val homography = Matrix3()

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

        protected lateinit var currentData: ProjectedMediaSurfaceData

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
            logMessage(ERROR, "ProjectedMediaSurface", "getLocation", "invalidIndex")
        }
        return locations[index]
    }

    fun setLocation(index: Int, location: Location) {
        require(index in locations.indices) {
            logMessage(ERROR, "ProjectedMediaSurface", "setLocation", "invalidIndex")
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
        if (!prepareGeometry(rc)) return

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
            error("ProjectedMediaSurface must be surface shape")
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

        // Resolve the interior texture: prefer the direct [texture] field (e.g. an OES-bound
        // SurfaceTexture for video) over the cached imageSource path. The shader program is
        // chosen to match the texture's target - OES external samplers need a different
        // sampler type than 2D ones.
        val directTexture = texture
        val resolvedTexture = directTexture
            ?: activeAttributes.interiorImageSource?.let { rc.getTexture(it) }
        val isOes = resolvedTexture?.target == GL_TEXTURE_EXTERNAL_OES
        drawState.programDrawToTexture = if (isOes) {
            rc.getShaderProgram(SurfaceQuadShaderProgramOES.KEY) { SurfaceQuadShaderProgramOES() }
        } else {
            rc.getShaderProgram(SurfaceQuadShaderProgram.KEY) { SurfaceQuadShaderProgram() }
        }
        drawState.programDrawTextureToTerrain = rc.getShaderProgram(TriangleShaderProgram.KEY) { TriangleShaderProgram() }

        // Assemble the drawable's OpenGL vertex buffer object.
        drawState.vertexBuffer = rc.getBufferObject(currentData.vertexBufferKey) {
            BufferObject(GL_ARRAY_BUFFER, 0)
        }
        rc.offerGLBufferUpload(currentData.vertexBufferKey, bufferDataVersion) {
            NumericArray.Floats(currentData.vertexArray)
        }

        // Use shared index(element) buffer for all ProjectedMediaSurfaces
        drawState.elementBuffer = getSharedIndexBuffer(rc)

        // Configure the drawable to use the interior texture when drawing the interior.
        if (resolvedTexture != null) {
            drawState.texture = resolvedTexture
            drawState.textureLod = 0
            drawState.texCoordMatrix.copy(resolvedTexture.coordTransform)
        } else {
            drawState.texture = null
        }

        // Configure the drawable to display the shape's interior top.
        drawState.homography.copy(currentData.homography)
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

    override val hasGeometry get() = currentData.vertexArray.isNotEmpty()

    override fun mustAssembleGeometry(rc: RenderContext): Boolean {
        currentData = data[rc.globeState] ?: ProjectedMediaSurfaceData().also { data[rc.globeState] = it }
        return currentData.refreshVertexArray || isExtrude && !isSurfaceShape
    }

    override fun assembleGeometry(rc: RenderContext) {
        ++bufferDataVersion // advance so [offerGLBufferUpload] sees fresh content this frame
        // Clear the shape's vertex array and element arrays. These arrays will accumulate values as the shape's
        // geometry is assembled.
        vertexIndex = 0
        currentData.vertexArray = FloatArray(locations.size * VERTEX_STRIDE) // Reserve boundaries.size for combined vertexes

        computeHomography()

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
                error("ProjectedMediaSurface must be surface shape")
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

    /**
     * Solve the 3x3 ground-to-image homography from the four corner correspondences
     * (bottomLeft, bottomRight, topRight, topLeft) -> (0,0), (1,0), (1,1), (0,1) and write
     * the row-major matrix into [ProjectedMediaSurfaceData.homography].
     *
     * Approach: build the image-to-ground square-to-quad homography Hi via Heckbert's
     * closed-form (*Fundamentals of Texture Mapping and Image Warping*), then invert 3x3
     * to get the direction the fragment shader needs. The local frame is anchored at
     * bottomLeft (matches the vertex buffer's vertexOrigin), so x0 = y0 = 0, which drops
     * the third column of Hi to (0, 0, 1) and roughly halves the cofactor terms.
     *
     * Inputs are (lon, lat) in degrees treated as a planar coordinate system; for typical
     * footprint sizes (sub-km to a few km) the curvature error is well under a metre.
     */
    private fun computeHomography() {
        // Origin = bottomLeft. Local frame: (lon - ox, lat - oy). Vertex 0 is (0, 0).
        val ox = locations[0].longitude.inDegrees
        val oy = locations[0].latitude.inDegrees
        val x1 = locations[1].longitude.inDegrees - ox; val y1 = locations[1].latitude.inDegrees - oy
        val x2 = locations[2].longitude.inDegrees - ox; val y2 = locations[2].latitude.inDegrees - oy
        val x3 = locations[3].longitude.inDegrees - ox; val y3 = locations[3].latitude.inDegrees - oy

        // Image-to-ground homography Hi. Bottom row (a31, a32, 1) carries the perspective
        // term; for parallelograms it's (0, 0, 1) and the transform collapses to affine.
        val dx1 = x1 - x2; val dx2 = x3 - x2; val dx3 = x2 - x1 - x3
        val dy1 = y1 - y2; val dy2 = y3 - y2; val dy3 = y2 - y1 - y3
        val a31: Double; val a32: Double
        if (dx3 == 0.0 && dy3 == 0.0) {
            a31 = 0.0; a32 = 0.0
        } else {
            val denom = dx1 * dy2 - dy1 * dx2
            a31 = (dx3 * dy2 - dy3 * dx2) / denom
            a32 = (dx1 * dy3 - dy1 * dx3) / denom
        }
        val a11 = x1 + a31 * x1
        val a12 = x3 + a32 * x3
        val a21 = y1 + a31 * y1
        val a22 = y3 + a32 * y3

        // Invert Hi -> H (ground-to-image). With the third column (0, 0, 1), det collapses
        // to a11*a22 - a12*a21 and several cofactors fall out to 0 / 1.
        val invDet = 1.0 / (a11 * a22 - a12 * a21)
        val m = currentData.homography.m
        m[0] =  a22 * invDet;                  m[1] = -a12 * invDet;                  m[2] = 0.0
        m[3] = -a21 * invDet;                  m[4] =  a11 * invDet;                  m[5] = 0.0
        m[6] = (a21 * a32 - a22 * a31) * invDet; m[7] = (a12 * a31 - a11 * a32) * invDet; m[8] = 1.0
    }
}