package earth.worldwind.shape

import earth.worldwind.draw.DrawShapeState
import earth.worldwind.draw.DrawableSurfaceShape
import earth.worldwind.geom.*
import earth.worldwind.globe.Globe
import earth.worldwind.render.RenderContext
import earth.worldwind.render.buffer.BufferObject
import earth.worldwind.render.program.TriangleShaderProgram
import earth.worldwind.util.NumericArray
import earth.worldwind.util.kgl.GL_ARRAY_BUFFER
import earth.worldwind.util.kgl.GL_ELEMENT_ARRAY_BUFFER
import earth.worldwind.util.kgl.GL_TRIANGLES
import earth.worldwind.util.kgl.GL_UNSIGNED_INT
import kotlin.jvm.JvmOverloads

open class TextureQuad @JvmOverloads constructor(
    attributes: ShapeAttributes = ShapeAttributes()
): AbstractShape(attributes) {
    override val referencePosition: Position get() {
        val sector = Sector()
        for (position in quad) sector.union(position)
        return Position(sector.centroidLatitude, sector.centroidLongitude, 0.0)
    }
    protected val quad = arrayOf(Location(), Location(), Location(), Location())
    protected val data = mutableMapOf<Globe.State?, TextureQuadData>()

    constructor(sector: Sector, attributes: ShapeAttributes = ShapeAttributes()) : this(attributes) {
        quad[0].set(sector.minLatitude, sector.minLongitude)
        quad[1].set(sector.maxLatitude, sector.minLongitude)
        quad[2].set(sector.maxLatitude, sector.maxLongitude)
        quad[3].set(sector.minLatitude, sector.maxLongitude)
    }

    init {
        altitudeMode = AltitudeMode.CLAMP_TO_GROUND
        isFollowTerrain = true
    }

    open class TextureQuadData {
        val vertexOrigin = Vec3()
        var vertexArray = FloatArray(0)
        // TODO Use IntArray instead of mutableListOf<Int> to avoid unnecessary memory re-allocations
        val topElements = mutableListOf<Int>()
        val vertexBufferKey = Any()
        val elementBufferKey = Any()
        var refreshVertexArray = true
    }

    companion object {
        protected const val VERTEX_STRIDE = 5
        protected const val VERTEX_ORIGINAL = 0

        protected lateinit var currentData: TextureQuadData

        protected var vertexIndex = 0

        protected val texCoord2d = Vec3()
        protected val modelToTexCoord = Matrix4()
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
        for (pos in quad) {
            val distance = refPos.greatCircleDistance(pos)
            val azimuth = refPos.greatCircleAzimuth(pos)
            position.greatCircleLocation(azimuth, distance, pos)
        }
        reset()
    }

    override fun makeDrawable(rc: RenderContext) {
        if (mustAssembleGeometry(rc)) assembleGeometry(rc)

        // Obtain a drawable form the render context pool.
        val pool = rc.getDrawablePool(DrawableSurfaceShape.KEY)
        val drawable = DrawableSurfaceShape.obtain(pool)
        val drawState = drawable.drawState
        val cameraDistance = cameraDistanceGeographic(rc, currentBoundindData.boundingSector)
        drawable.offset = rc.globe.offset
        drawable.sector.copy(currentBoundindData.boundingSector)
        drawable.version = computeVersion()
        drawable.isDynamic = isDynamic || rc.currentLayer.isDynamic

        // Use the basic GLSL program to draw the shape.
        drawState.program = rc.getShaderProgram(TriangleShaderProgram.KEY) { TriangleShaderProgram() }

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
            NumericArray.Ints(currentData.topElements.toIntArray())
        }

        drawInterior(rc, drawState, cameraDistance)

        // Configure the drawable according to the shape's attributes. Disable triangle backface culling when we're
        // displaying a polygon without extruded sides, so we want to draw the top and the bottom.
        drawState.vertexOrigin.copy(currentData.vertexOrigin)
        drawState.vertexStride = VERTEX_STRIDE * 4 // stride in bytes
        drawState.enableCullFace = isExtrude
        drawState.enableDepthTest = activeAttributes.isDepthTest
        drawState.enableDepthWrite = activeAttributes.isDepthWrite
        drawState.enableLighting = activeAttributes.isLightingEnabled

        // Enqueue the drawable for processing on the OpenGL thread.
        rc.offerSurfaceDrawable(drawable, zOrder)
    }

    protected open fun drawInterior(rc: RenderContext, drawState: DrawShapeState, cameraDistance: Double) {
        if (!activeAttributes.isDrawInterior || rc.isPickMode && !activeAttributes.isPickInterior) return

        // Configure the drawable to use the interior texture when drawing the interior.
        activeAttributes.interiorImageSource?.let { interiorImageSource ->
            rc.getTexture(interiorImageSource, defaultInteriorImageOptions)?.let { texture ->
                drawState.texture = texture
                drawState.texCoordMatrix.copy(texture.coordTransform)
            }
        } ?: return

        // Configure the drawable to display the shape's interior top.
        drawState.color.copy(if (rc.isPickMode) pickColor else activeAttributes.interiorColor)
        drawState.opacity = if (rc.isPickMode) 1f else rc.currentLayer.opacity
        drawState.texCoordAttrib.size = 2
        drawState.texCoordAttrib.offset = 12
        drawState.drawElements(GL_TRIANGLES, currentData.topElements.size, GL_UNSIGNED_INT, offset = 0)
    }

    protected open fun mustAssembleGeometry(rc: RenderContext): Boolean {
        currentData = data[rc.globeState] ?: TextureQuadData().also { data[rc.globeState] = it }
        return currentData.refreshVertexArray
    }

    protected open fun assembleGeometry(rc: RenderContext) {
        vertexIndex = 0
        currentData.vertexArray = FloatArray(4 * VERTEX_STRIDE)
        currentData.topElements.clear()

        // Compute a matrix that transforms from Cartesian coordinates to shape texture coordinates.
        determineModelToTexCoord(rc)

        // Add the boundary's first vertex. Add additional dummy vertex with the same data before the first vertex.
        val pos0 = quad[0]
        calcPoint(rc, pos0.latitude, pos0.longitude, 0.0, isAbsolute = false)
        addVertex(rc, pos0.latitude, pos0.longitude, 0.0, type = VERTEX_ORIGINAL)

        // Add the remaining boundary vertices, tessellating each edge as indicated by the polygon's properties.
        for (idx in 1 until quad.size) {
            val end = quad[idx]
            calcPoint(rc, end.latitude, end.longitude, 0.0, isAbsolute = false)
            addVertex(rc, end.latitude, end.longitude, 0.0, type = VERTEX_ORIGINAL)
        }

        // Reset update flags
        currentData.refreshVertexArray = false

        // Compute the shape's bounding box or bounding sector from its assembled coordinates.
        with(currentBoundindData) {
            boundingSector.setEmpty()
            boundingSector.union(currentData.vertexArray, vertexIndex, VERTEX_STRIDE)
            boundingSector.translate(currentData.vertexOrigin.y, currentData.vertexOrigin.x)
            boundingBox.setToUnitBox() // Surface/geographic shape bounding box is unused
        }

        // Adjust final vertex array size to save memory (and fix cameraDistanceCartesian calculation)
        currentData.vertexArray = currentData.vertexArray.copyOf(vertexIndex)
    }

    protected open fun addVertex(
        rc: RenderContext, latitude: Angle, longitude: Angle, altitude: Double, type: Int
    ): Int = with(currentData) {
        val vertex = vertexIndex / VERTEX_STRIDE
        val texCoord2d = texCoord2d.copy(point).multiplyByMatrix(modelToTexCoord)
        if (vertex == 0) {
            vertexOrigin.set(longitude.inDegrees, latitude.inDegrees, altitude)
        }
        vertexArray[vertexIndex++] = (longitude.inDegrees - vertexOrigin.x).toFloat()
        vertexArray[vertexIndex++] = (latitude.inDegrees - vertexOrigin.y).toFloat()
        vertexArray[vertexIndex++] = (altitude - vertexOrigin.z).toFloat()
        vertexArray[vertexIndex++] = texCoord2d.x.toFloat()
        vertexArray[vertexIndex++] = texCoord2d.y.toFloat()
        vertex
    }

    protected open fun determineModelToTexCoord(rc: RenderContext) {
        var mx = 0.0
        var my = 0.0
        var mz = 0.0
        var numPoints = 0.0
            for (j in quad.indices) {
                rc.geographicToCartesian(quad[j].latitude, quad[j].longitude, 0.0, AltitudeMode.ABSOLUTE, point)
                mx += point.x
                my += point.y
                mz += point.z
                numPoints++
            }
        mx /= numPoints
        my /= numPoints
        mz /= numPoints
        rc.globe.cartesianToLocalTransform(mx, my, mz, modelToTexCoord)
        modelToTexCoord.invertOrthonormal()
    }
}