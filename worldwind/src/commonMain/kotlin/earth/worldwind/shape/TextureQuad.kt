package earth.worldwind.shape

import earth.worldwind.draw.DrawQuadState
import earth.worldwind.draw.DrawShapeState
import earth.worldwind.draw.Drawable
import earth.worldwind.draw.DrawableShape
import earth.worldwind.draw.DrawableSurfaceQuad
import earth.worldwind.draw.DrawableSurfaceShape
import earth.worldwind.geom.*
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.globe.Globe
import earth.worldwind.render.RenderContext
import earth.worldwind.render.buffer.BufferObject
import earth.worldwind.render.program.SurfaceQuadShaderProgram
import earth.worldwind.render.program.TriangleShaderProgram
import earth.worldwind.shape.PathType.*
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.NumericArray
import earth.worldwind.util.kgl.GL_ARRAY_BUFFER
import earth.worldwind.util.kgl.GL_ELEMENT_ARRAY_BUFFER
import earth.worldwind.util.kgl.GL_TRIANGLES
import earth.worldwind.util.kgl.GL_UNSIGNED_INT
import earth.worldwind.util.math.encodeOrientationVector
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
        for (boundary in boundaries) for (position in boundary) sector.union(position)
        return Position(sector.centroidLatitude, sector.centroidLongitude, 0.0)
    }
    protected val boundaries = mutableListOf(listOf(bottomLeft, bottomRight, topRight, topLeft))
    protected val data = mutableMapOf<Globe.State?, PolygonData>()

    open class PolygonData {
        val vertexOrigin = Vec3()
        var vertexArray = FloatArray(0)
        val topElements = mutableListOf<Int>()
        val vertexBufferKey = Any()
        val elementBufferKey = Any()
        var refreshVertexArray = true

        var A = Vec2()
        var B = Vec2()
        var C = Vec2()
        var D = Vec2()
    }

    companion object {
        protected const val VERTEX_STRIDE = 3
        protected const val VERTEX_ORIGINAL = 0
        protected lateinit var currentData: PolygonData

        protected var vertexIndex = 0
    }

    init {
        altitudeMode = AltitudeMode.CLAMP_TO_GROUND
        isFollowTerrain = true
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
        for (boundary in boundaries) for (pos in boundary) {
            val distance = refPos.greatCircleDistance(pos)
            val azimuth = refPos.greatCircleAzimuth(pos)
            position.greatCircleLocation(azimuth, distance, pos)
        }
        reset()
    }

    override fun makeDrawable(rc: RenderContext) {
        if (boundaries.isEmpty()) return  // nothing to draw

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

        // Assemble the drawable's OpenGL element buffer object.
        drawState.elementBuffer = rc.getBufferObject(currentData.elementBufferKey) {
            BufferObject(GL_ELEMENT_ARRAY_BUFFER, 0)
        }
        rc.offerGLBufferUpload(currentData.elementBufferKey, bufferDataVersion) {
            val array = IntArray(currentData.topElements.size)
            var index = 0
            for (element in currentData.topElements) array[index++] = element
            NumericArray.Ints(array)
        }

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
        drawState.drawElements(GL_TRIANGLES, currentData.topElements.size, GL_UNSIGNED_INT, offset = 0)
    }

    protected open fun mustAssembleGeometry(rc: RenderContext): Boolean {
        currentData = data[rc.globeState] ?: PolygonData().also { data[rc.globeState] = it }
        return currentData.refreshVertexArray || isExtrude && !isSurfaceShape
    }

    protected open fun assembleGeometry(rc: RenderContext) {
        var vertexCount = 0

        for (i in boundaries.indices) {
            val p = boundaries[i]
            if (p.isEmpty()) continue
           vertexCount += p.size
        }

        // Clear the shape's vertex array and element arrays. These arrays will accumulate values as the shapes's
        // geometry is assembled.
        vertexIndex = 0
        currentData.vertexArray = if (isExtrude && !isSurfaceShape) FloatArray(vertexCount * 2 * VERTEX_STRIDE)
        else if (!isSurfaceShape) FloatArray(vertexCount * VERTEX_STRIDE)
        else FloatArray((vertexCount + boundaries.size) * VERTEX_STRIDE) // Reserve boundaries.size for combined vertexes

        currentData.topElements.clear()
        currentData.topElements.add(0)
        currentData.topElements.add(1)
        currentData.topElements.add(2)
        currentData.topElements.add(2)
        currentData.topElements.add(3)
        currentData.topElements.add(0)

        computeQuadLocalCorners(rc)

        for (i in boundaries.indices) {
            val positions = boundaries[i]
            if (positions.isEmpty()) continue  // no boundary positions to assemble

            // Add the boundary's first vertex. Add additional dummy vertex with the same data before the first vertex.
            val pos0 = positions[0]
            var begin = pos0
            addVertex(rc, begin.latitude, begin.longitude)

            // Add the remaining boundary vertices, tessellating each edge as indicated by the polygon's properties.
            for (idx in 1 until positions.size) {
                val end = positions[idx]
                addVertex(rc, end.latitude, end.longitude)
            }
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
        val corners = boundaries[0]
        currentData.vertexOrigin.set(corners[0].longitude.inDegrees, corners[0].latitude.inDegrees, 1.0)
        currentData.A = Vec2(
            corners[0].longitude.inDegrees - currentData.vertexOrigin.x,
            corners[0].latitude.inDegrees - currentData.vertexOrigin.y)
        currentData.B = Vec2(
            corners[1].longitude.inDegrees - currentData.vertexOrigin.x,
            corners[1].latitude.inDegrees - currentData.vertexOrigin.y)
        currentData.C = Vec2(
            corners[2].longitude.inDegrees - currentData.vertexOrigin.x,
            corners[2].latitude.inDegrees - currentData.vertexOrigin.y)
        currentData.D = Vec2(
            corners[3].longitude.inDegrees - currentData.vertexOrigin.x,
            corners[3].latitude.inDegrees - currentData.vertexOrigin.y)
    }
}