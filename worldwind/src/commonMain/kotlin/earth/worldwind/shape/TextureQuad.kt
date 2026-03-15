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

open class TextureQuad @JvmOverloads constructor(
    bottomLeft : Location,
    bottomRight: Location,
    topRight   : Location,
    topLeft    : Location,
    imageSource: ImageSource
): AbstractShape(
ShapeAttributes().apply {
    interiorImageSource = imageSource
    } )
{
    constructor(sector: Sector, imageSource: ImageSource) : this(
        Location(sector.minLatitude, sector.minLongitude),
        Location(sector.minLatitude, sector.maxLongitude),
        Location(sector.maxLatitude, sector.maxLongitude),
        Location(sector.maxLatitude, sector.minLongitude),
        imageSource
    )

    override val referencePosition: Position get() {
        val sector = Sector()
        for (position in locations) sector.union(position)
        return Position(sector.centroidLatitude, sector.centroidLongitude, 0.0)
    }
    protected val locations = arrayOf(bottomLeft, bottomRight, topRight, topLeft)
    protected val data = mutableMapOf<Globe.State?, PolygonData>()

    open class PolygonData {
        val vertexOrigin = Vec3()
        var vertexArray = FloatArray(0)
        val vertexBufferKey = Any()
        var refreshVertexArray = true

        var A = Vec2()
        var B = Vec2()
        var C = Vec2()
        var D = Vec2()
    }

    companion object {
        protected const val VERTEX_STRIDE = 3

        protected val SHARED_INDEX_ARRAY = intArrayOf(0, 1, 2, 2, 3, 0)
        private var sharedIndexBufferKey = Any()
        private var sharedIndexBuffer: BufferObject? = null

        protected lateinit var currentData: PolygonData

        protected var vertexIndex = 0

        fun getSharedIndexBuffer(rc: RenderContext): BufferObject?
        {
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

    fun getLocations(index: Int): Array<Location> {
        return locations
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

    fun setLocations(bottomLeft : Location,
                     bottomRight: Location,
                     topRight   : Location,
                     topLeft    : Location) {
        reset()
        locations[0] = bottomLeft
        locations[1] = bottomRight
        locations[2] = topRight
        locations[3] = topLeft
    }

    override fun resetGlobeState(globeState: Globe.State?) {
        super.resetGlobeState(globeState)
        data[globeState]?.let {
            it.refreshVertexArray = true
        }
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

        if (mustAssembleGeometry(rc)) assembleGeometry(rc)

        // Obtain a drawable form the render context pool.
        val drawable: Drawable
        val drawState: DrawQuadState
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

        drawInterior(rc, drawState)

        // Configure the drawable according to the shape's attributes. Disable triangle backface culling when we're
        // displaying a polygon without extruded sides, so we want to draw the top and the bottom.
        drawState.vertexOrigin.copy(currentData.vertexOrigin)
        drawState.vertexStride = VERTEX_STRIDE * 4 // stride in bytes
        drawState.enableCullFace = isExtrude
        drawState.enableDepthTest = activeAttributes.isDepthTest
        drawState.enableLighting = activeAttributes.isLightingEnabled
        drawState.A.copy(currentData.A)
        drawState.B.copy(currentData.B)
        drawState.C.copy(currentData.C)
        drawState.D.copy(currentData.D)

        rc.offerSurfaceDrawable(drawable, zOrder)
    }

    protected open fun drawInterior(rc: RenderContext, drawState: DrawQuadState) {
        if (!activeAttributes.isDrawInterior || rc.isPickMode && !activeAttributes.isPickInterior) return

        // Configure the drawable to use the interior texture when drawing the interior.
        activeAttributes.interiorImageSource?.let { interiorImageSource ->
            rc.getTexture(interiorImageSource)?.let { texture ->
                drawState.texture = texture
                drawState.textureLod = 0;
                drawState.texCoordMatrix.copy(texture.coordTransform)
            }
        } ?: run { drawState.texture = null }

        // Configure the drawable to display the shape's interior top.
        drawState.A.copy(currentData.A)
        drawState.B.copy(currentData.B)
        drawState.C.copy(currentData.C)
        drawState.D.copy(currentData.D)
        drawState.color.copy(if (rc.isPickMode) pickColor else activeAttributes.interiorColor)
        drawState.opacity = if (rc.isPickMode) 1f else rc.currentLayer.opacity
        drawState.drawElements(GL_TRIANGLES, SHARED_INDEX_ARRAY.size, GL_UNSIGNED_INT, offset = 0)
    }

    protected open fun mustAssembleGeometry(rc: RenderContext): Boolean {
        currentData = data[rc.globeState] ?: PolygonData().also { data[rc.globeState] = it }
        return currentData.refreshVertexArray || isExtrude && !isSurfaceShape
    }

    protected open fun assembleGeometry(rc: RenderContext) {

        var vertexCount = locations.size

        // Clear the shape's vertex array and element arrays. These arrays will accumulate values as the shapes's
        // geometry is assembled.
        vertexIndex = 0
        currentData.vertexArray = FloatArray(vertexCount * VERTEX_STRIDE) // Reserve boundaries.size for combined vertexes

        computeQuadLocalCorners(rc)

        // Add the remaining boundary vertices, tessellating each edge as indicated by the polygon's properties.
        for (pos in locations) {
            addVertex(rc, pos.latitude, pos.longitude)
        }

        // Reset update flags
        currentData.refreshVertexArray = false

        // Compute the shape's bounding box or bounding sector from its assembled coordinates.
        with(currentBoundindData) {
            if (isSurfaceShape) {
                boundingSector.setEmpty()
                boundingSector.union(currentData.vertexArray, vertexIndex, VERTEX_STRIDE)
                boundingSector.translate(currentData.vertexOrigin.y, currentData.vertexOrigin.x)
                boundingBox.setToUnitBox() // Surface/geographic shape bounding box is unused
            }
            else
            {
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
    fun computeQuadLocalCorners(rc: RenderContext) {
        currentData.vertexOrigin.set(locations[0].longitude.inDegrees, locations[0].latitude.inDegrees, 1.0)
        currentData.A = Vec2(
            locations[0].longitude.inDegrees - currentData.vertexOrigin.x,
            locations[0].latitude.inDegrees - currentData.vertexOrigin.y)
        currentData.B = Vec2(
            locations[1].longitude.inDegrees - currentData.vertexOrigin.x,
            locations[1].latitude.inDegrees - currentData.vertexOrigin.y)
        currentData.C = Vec2(
            locations[2].longitude.inDegrees - currentData.vertexOrigin.x,
            locations[2].latitude.inDegrees - currentData.vertexOrigin.y)
        currentData.D = Vec2(
            locations[3].longitude.inDegrees - currentData.vertexOrigin.x,
            locations[3].latitude.inDegrees - currentData.vertexOrigin.y)
    }
}