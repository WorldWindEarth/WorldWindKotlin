package earth.worldwind.shape

import earth.worldwind.draw.DrawQuadState
import earth.worldwind.draw.DrawableProjectedSurface
import earth.worldwind.draw.DrawableSurfaceQuad
import earth.worldwind.geom.*
import earth.worldwind.globe.Globe
import earth.worldwind.render.RenderContext
import earth.worldwind.render.Texture
import earth.worldwind.render.buffer.BufferObject
import earth.worldwind.render.program.Surface3DProjectionShaderProgram
import earth.worldwind.render.program.Surface3DProjectionShaderProgramOES
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
            isPickInterior = false
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
            isPickInterior = false
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

    /**
     * Optional 4x4 matrix mapping a world WGS84-ECEF position to image clip space, applied
     * per-fragment on the terrain surface. When non-null the shape switches from the planar
     * 2D-homography path (which approximates as if the four corners lie on a flat plane) to
     * a true 3D camera-frustum projection path that's mathematically correct over arbitrary
     * relief.
     *
     * Build via [earth.worldwind.geom.CameraPose.setToLookAt] (lat/lon/alt + look-at
     * target + FOV) or [earth.worldwind.geom.CameraPose.setFromPlatformAndSensorPose]
     * (KLV-style platform yaw/pitch/roll composed with sensor relative angles).
     *
     * Trade-offs:
     *  * Cost: one mat4*vec4 per fragment per terrain tile, plus a per-tile CPU recompute,
     *    versus one mat3*vec3 per fragment for the homography path. The 3D path also
     *    breaks the surface-drawable batching so each shape is its own draw call.
     *  * Quality: the homography is exact only on a flat ground plane; the 3D path is
     *    exact regardless of terrain relief. For sub-km drone footprints over typical
     *    rolling terrain the visible difference is small. For 10+ km photo footprints
     *    over mountainous terrain the homography distorts noticeably.
     *  * **JS / WebGL**: this path renders incorrectly on JS - browser GLSL compilers
     *    produce wrong per-fragment results for typical perspective `mat4 * vec4`
     *    regardless of `highp`, RTE source-shifting, fp64 emulation, or WebGL 2 +
     *    GLSL ES 3.00 migration. JS callers should leave [imageProjection] `null` and
     *    rely on the homography path (which renders the same drone footage correctly
     *    via the four ground-corner KLV tags). JVM and Android render the 3D path as
     *    written. Full investigation log and remaining options:
     *    `docs/webgl-3d-projection-investigation.md`.
     *
     * `null` (default) keeps the homography path with its lower per-frame cost.
     */
    var imageProjection: Matrix4? = null

    /**
     * 3D-projection-only soft-edge fade margin in normalised UV units (range 0..0.5). The
     * fragment shader ramps interior alpha 0..1 across the inner [0..fadeMargin] strip on
     * each side, blending the projection's frustum boundary into the surrounding terrain
     * instead of guillotining at the unit-square edge.
     *
     * 0 (default) renders a hard cutoff and is free at runtime: the shader's
     * uniform-controlled branch skips the fade math entirely. The 2D-homography path
     * ignores this value.
     */
    var fadeMargin: Float = 0f

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
        if (!isSurfaceShape) error("ProjectedMediaSurface must be surface shape")

        if (imageProjection != null) emit3DProjectionDrawable(rc) else emitHomographyDrawable(rc)

        // Outline path is shared between the two interior modes - it doesn't depend on the
        // image projection at all, just the four ground corners.
        if (activeAttributes.isDrawOutline) {
            val drawableLines = DrawableSurfaceQuad.obtain(rc.getDrawablePool(DrawableSurfaceQuad.KEY))
            drawableLines.offset = rc.globe.offset
            drawableLines.sector.copy(currentBoundindData.boundingSector)
            drawableLines.version = computeVersion()
            drawableLines.isDynamic = isDynamic || rc.currentLayer.isDynamic

            val cameraDistance = cameraDistanceGeographic(rc, currentBoundindData.boundingSector)
            drawOutline(rc, drawableLines.drawState, cameraDistance)
            rc.offerSurfaceDrawable(drawableLines, zOrder)
        }
    }

    /** Homography (2D, planar-ground) path: surface-quad with per-fragment mat3 from corners. */
    private fun emitHomographyDrawable(rc: RenderContext) {
        val pool = rc.getDrawablePool(DrawableSurfaceQuad.KEY)
        val drawable = DrawableSurfaceQuad.obtain(pool)
        drawable.offset = rc.globe.offset
        drawable.sector.copy(currentBoundindData.boundingSector)
        drawable.version = computeVersion()
        drawable.isDynamic = isDynamic || rc.currentLayer.isDynamic
        drawInterior(rc, drawable.drawState)
        rc.offerSurfaceDrawable(drawable, zOrder)
    }

    /**
     * 3D camera-frustum path: render the source texture directly against terrain triangles
     * with per-fragment image-clip math. No surface-skin stage; precision is preserved by
     * keeping the per-vertex math in tile-local space (the drawable bakes
     * `imageProjection * translation(tileOrigin)` per tile).
     */
    private fun emit3DProjectionDrawable(rc: RenderContext) {
        if (skipInterior(rc)) return
        val resolvedTexture = resolveInteriorTexture(rc) ?: return
        val matrix = imageProjection ?: return

        val drawable = DrawableProjectedSurface.obtain(rc.getDrawablePool(DrawableProjectedSurface.KEY))
        drawable.offset = rc.globe.offset
        drawable.sector.copy(currentBoundindData.boundingSector)
        drawable.program = pick3DProjectionShader(rc, resolvedTexture)
        drawable.texture = resolvedTexture
        drawable.imageProjection.copy(matrix)
        drawable.texCoordMatrix.copy(resolvedTexture.coordTransform)
        drawable.color.copy(if (rc.isPickMode) pickColor else activeAttributes.interiorColor)
        drawable.opacity = if (rc.isPickMode) 1f else rc.currentLayer.opacity
        drawable.fadeMargin = fadeMargin
        rc.offerSurfaceDrawable(drawable, zOrder)
    }

    /**
     * Resolve the interior texture: prefer the direct [texture] field over the cached
     * imageSource path. Returns `null` when neither is available (e.g. the imageSource is
     * still loading on its first reference).
     */
    private fun resolveInteriorTexture(rc: RenderContext): Texture? = texture
        ?: activeAttributes.interiorImageSource?.let { rc.getTexture(it) }

    /**
     * Whether the current frame should skip the interior pass (interior disabled, or pick
     * pass over a non-pickable interior). Shared by the homography and 3D paths.
     */
    private fun skipInterior(rc: RenderContext) =
        !activeAttributes.isDrawInterior || rc.isPickMode && !activeAttributes.isPickInterior

    /**
     * Pick the 3D-projection shader variant matching the texture target: OES external for
     * Android `SurfaceTexture` video, plain `sampler2D` for everything else.
     */
    private fun pick3DProjectionShader(rc: RenderContext, tex: Texture) =
        if (tex.target == GL_TEXTURE_EXTERNAL_OES) {
            rc.getShaderProgram(Surface3DProjectionShaderProgramOES.KEY) { Surface3DProjectionShaderProgramOES() }
        } else {
            rc.getShaderProgram(Surface3DProjectionShaderProgram.KEY) { Surface3DProjectionShaderProgram() }
        }

    protected open fun drawInterior(rc: RenderContext, drawState: DrawQuadState) {
        if (skipInterior(rc)) return

        drawState.isLine = false

        // Resolve the interior texture and pick the shader variant matching its target.
        // OES external samplers need a different shader than plain sampler2D.
        val resolvedTexture = resolveInteriorTexture(rc)
        drawState.programDrawToTexture = if (resolvedTexture?.target == GL_TEXTURE_EXTERNAL_OES) {
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
     * Solve the ground-to-image homography from the four corner correspondences
     * (bottomLeft, bottomRight, topRight, topLeft) -> unit square and write the row-major
     * matrix into [ProjectedMediaSurfaceData.homography]. The local frame is anchored at
     * bottomLeft (matches the vertex buffer's vertexOrigin), so the four corners are
     * passed relative to it - which is exactly the contract of the Heckbert utility.
     *
     * Inputs are (lon, lat) in degrees treated as a planar coordinate system; for typical
     * footprint sizes (sub-km to a few km) the curvature error is well under a metre.
     */
    private fun computeHomography() {
        val ox = locations[0].longitude.inDegrees
        val oy = locations[0].latitude.inDegrees
        currentData.homography.setToQuadToUnitSquareHomography(
            p1x = locations[1].longitude.inDegrees - ox, p1y = locations[1].latitude.inDegrees - oy,
            p2x = locations[2].longitude.inDegrees - ox, p2y = locations[2].latitude.inDegrees - oy,
            p3x = locations[3].longitude.inDegrees - ox, p3y = locations[3].latitude.inDegrees - oy,
        )
    }
}