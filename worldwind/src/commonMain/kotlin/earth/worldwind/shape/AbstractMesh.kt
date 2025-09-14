package earth.worldwind.shape

import earth.worldwind.draw.DrawShapeState
import earth.worldwind.draw.DrawableMesh
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Position
import earth.worldwind.geom.Vec3
import earth.worldwind.globe.Globe
import earth.worldwind.render.RenderContext
import earth.worldwind.render.buffer.BufferObject
import earth.worldwind.render.program.BasicTextureProgram
import earth.worldwind.util.NumericArray
import earth.worldwind.util.kgl.*
import kotlinx.datetime.Clock
import kotlin.random.Random

/**
 * Provides an abstract base class for mesh shapes.
 *
 * @param attributes The attributes to associate with this mesh. May be null, in which case
 * default attributes are associated.
 */
abstract class AbstractMesh(attributes: ShapeAttributes) : AbstractShape(attributes) {
    /**
     * Indicates this shape's geographic position. The position's altitude is relative to this shape's altitude mode.
     **/
    final override val referencePosition = Position()

    /**
     * Scales the altitudes of this mesh.
     */
    var altitudeScale = 1.0
        set(value) {
            field = value
            reset()
        }

    /**
     * Indicates whether this mesh is pickable when the pick point intersects transparent pixels of the
     * image applied to this mesh. If no image is applied to this mesh, this property is ignored. If this
     * property is true and an image with fully transparent pixels is applied to the mesh, the mesh is
     * pickable at those transparent pixels, otherwise this mesh is not pickable at those transparent pixels.
     */
    var pickTransparentImagePixels = true

    protected lateinit var currentData: MeshData
    protected val data = mutableMapOf<Globe.State?, MeshData>()

    open class MeshData {
        val vertexOrigin = Vec3()
        var vertexArray = FloatArray(0)
        var normalsArray = FloatArray(0)
        var texCoords = FloatArray(0)
        var meshIndices = ShortArray(0)
        var meshOutlineIndices = ShortArray(0)
        val vertexBufferKey = Any()
        val normalsBufferKey = Any()
        val texCoordsBufferKey = Any()
        val elementBufferKey = Any()
        var refreshVertexArray = true
        var refreshNormalsArray = true
        var isDrawInterior = false
        private var expiryTime = 0L

        fun resetExpiryTime(expirationInterval: Int) {
            // The random addition in the line below prevents all shapes from regenerating during the same frame.
            expiryTime = Clock.System.now().toEpochMilliseconds() + expirationInterval + Random.nextInt(0, 1000)
        }

        /**
         * Indicates whether a specified shape data object is current. Subclasses may override this method to add
         * criteria indicating whether the shape data object is current, but must also call this method on this base
         * class. Applications do not call this method.
         * @returns true if the object is current, otherwise false.
         */
        fun isExpired() = expiryTime < Clock.System.now().toEpochMilliseconds()
    }

    companion object {
        const val VERTEX_STRIDE = 3
        /**
         * Indicates how long to use terrain-specific shape data before regenerating it, in milliseconds. A value
         * of zero specifies that shape data should be regenerated every frame. While this causes the shape to
         * adapt more frequently to the terrain, it decreases performance.
         */
        var expirationInterval = 2000
    }

    abstract fun createSurfaceShape(): AbstractShape?

    override fun reset() {
        super.reset()
        data.values.forEach {
            it.refreshVertexArray = true
            it.refreshNormalsArray = true
        }
    }

    override fun moveTo(globe: Globe, position: Position) {
        referencePosition.copy(position)
        reset()
    }

    override fun makeDrawable(rc: RenderContext) {
        if (!activeAttributes.isDrawInterior && !activeAttributes.isDrawOutline) return

        // See if the current shape data can be re-used
        if (mustAssembleGeometry(rc)) assembleGeometry(rc)

        // Obtain a drawable form the render context pool.
        val pool = rc.getDrawablePool(DrawableMesh.KEY)
        val drawable = DrawableMesh.obtain(pool)
        val drawState = drawable.drawState
        val cameraDistance = cameraDistanceCartesian(
            rc, currentData.vertexArray, currentData.vertexArray.size, VERTEX_STRIDE, currentData.vertexOrigin
        )

        // Use the basic GLSL program to draw the shape.
        drawState.program = rc.getShaderProgram(BasicTextureProgram.KEY) { BasicTextureProgram() }

        // Load the vertex data since both the interior and outline use it
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
            val array = ShortArray(currentData.meshIndices.size + currentData.meshOutlineIndices.size)
            var index = 0
            for (element in currentData.meshIndices) array[index++] = element
            for (element in currentData.meshOutlineIndices) array[index++] = element
            NumericArray.Shorts(array)
        }

        // Draw the mesh if the interior requested.
        if (activeAttributes.isDrawInterior) {
            // Apply lighting.
            if (!rc.isPickMode && activeAttributes.isLightingEnabled) {
                drawable.normalsBuffer = rc.getBufferObject(currentData.normalsBufferKey) {
                    BufferObject(GL_ARRAY_BUFFER, 0)
                }
                rc.offerGLBufferUpload(currentData.normalsBufferKey, bufferDataVersion) {
                    NumericArray.Floats(currentData.normalsArray)
                }
            }

            // Texture coordinates
            if (activeAttributes.interiorImageSource != null) {
                drawable.texCoordsBuffer = rc.getBufferObject(currentData.texCoordsBufferKey) {
                    BufferObject(GL_ARRAY_BUFFER, 0)
                }
                rc.offerGLBufferUpload(currentData.texCoordsBufferKey, bufferDataVersion) {
                    NumericArray.Floats(currentData.texCoords)
                }
            }

            drawInterior(rc, drawState)
        }

        // Draw the outline.
        if (activeAttributes.isDrawOutline) drawOutline(rc, drawState)

        // Configure the drawable according to the shape's attributes.
        drawState.vertexOrigin.copy(currentData.vertexOrigin)
        drawState.vertexStride = VERTEX_STRIDE * 4 // stride in bytes
        drawState.enableCullFace = false
        drawState.enableDepthTest = activeAttributes.isDepthTest
        drawState.enableDepthWrite = activeAttributes.isDepthWrite
        drawState.enableLighting = activeAttributes.isLightingEnabled

        // Enqueue the drawable for processing on the OpenGL thread.
        rc.offerShapeDrawable(drawable, cameraDistance)
    }

    protected open fun drawInterior(rc: RenderContext, drawState: DrawShapeState) {
        drawState.color.copy(if (rc.isPickMode) pickColor else activeAttributes.interiorColor)
        drawState.opacity = if (rc.isPickMode) 1f else rc.currentLayer.opacity

        // Configure the drawable to use the interior texture when drawing the interior.
        val imageSource = activeAttributes.interiorImageSource
        if (imageSource != null && (!rc.isPickMode || !pickTransparentImagePixels)) {
            rc.getTexture(imageSource)?.let { texture ->
                drawState.texture = texture
                drawState.texCoordMatrix.copy(texture.coordTransform)
            }
        }

        drawState.drawElements(GL_TRIANGLES, currentData.meshIndices.size, GL_UNSIGNED_SHORT, offset = 0)
    }

    protected open fun drawOutline(rc: RenderContext, drawState: DrawShapeState) {
        drawState.texture = null // We're not texturing in this clause
        drawState.depthOffset = -0.001 // Make the outline stand out from the interior
        drawState.color.copy(if (rc.isPickMode) pickColor else activeAttributes.outlineColor)
        drawState.opacity = if (rc.isPickMode) 1f else rc.currentLayer.opacity
        drawState.lineWidth = activeAttributes.outlineWidth

        drawState.drawElements(
            GL_LINE_STRIP, currentData.meshOutlineIndices.size,
            GL_UNSIGNED_SHORT, offset = currentData.meshIndices.size * Short.SIZE_BYTES
        )
    }

    /**
     * Determines whether this shape's geometry must be re-computed.
     */
    protected open fun mustAssembleGeometry(rc: RenderContext): Boolean {
        currentData = data[rc.globeState] ?: MeshData().also { data[rc.globeState] = it }
        if (currentData.refreshVertexArray) return true
        if (currentData.isDrawInterior != activeAttributes.isDrawInterior) return true
        if (activeAttributes.isLightingEnabled && currentData.refreshNormalsArray) return true
        val isTerrainDependent = altitudeMode == AltitudeMode.CLAMP_TO_GROUND || altitudeMode == AltitudeMode.RELATIVE_TO_GROUND
        return isTerrainDependent && currentData.isExpired()
    }

    protected open fun assembleGeometry(rc: RenderContext) {
        // Adjust vertex origin to correspond to the reference position
        with(referencePosition) {
            rc.geographicToCartesian(latitude, longitude, altitude * altitudeScale, altitudeMode, currentData.vertexOrigin)
        }

        // Convert the geographic coordinates to the Cartesian coordinates that will be rendered
        currentData.vertexArray = computeMeshPoints(rc)
        currentData.refreshVertexArray = false

        // Capture texture coordinates in a parallel array to the mesh points. These are associated with this
        // shape, itself, because they're independent of elevation or globe state.
        if (activeAttributes.interiorImageSource != null) currentData.texCoords = computeTexCoords()

        // Compute the mesh and outline indices. These are associated with this shape, itself, because they're
        // independent of elevation and globe state.
        currentData.meshIndices = computeMeshIndices()
        currentData.meshOutlineIndices = computeOutlineIndices()

        if (activeAttributes.isLightingEnabled) {
            currentData.normalsArray = computeNormals()
            currentData.refreshNormalsArray = false
        }

        currentData.isDrawInterior = activeAttributes.isDrawInterior // remember for validation
        currentData.resetExpiryTime(expirationInterval)

        // Create the extent from the Cartesian points. Those points are relative to this path's reference point,
        // so translate the computed extent to the reference point.
        with(currentBoundindData) {
            boundingBox.setToPoints(currentData.vertexArray, currentData.vertexArray.size, VERTEX_STRIDE)
            boundingBox.translate(currentData.vertexOrigin.x, currentData.vertexOrigin.y, currentData.vertexOrigin.z)
            boundingSector.setEmpty() // Cartesian shape bounding sector is unused
        }
    }

    /**
     * Computes this mesh's Cartesian points. Called by this abstract class during rendering to compute
     * Cartesian points from geographic positions. This method must be overridden by subclasses. An
     * exception is thrown if it is not.
     *
     * This method must also assign currentData.eyeDistance to be the minimum distance from this mesh to the
     * current eye point.
     *
     * @param rc The current render context.
     * @return The Cartesian mesh points.
     */
    protected abstract fun computeMeshPoints(rc: RenderContext): FloatArray

    /**
     * Computes the texture coordinates for this shape. Called by this abstract class during rendering to copy or
     * compute texture coordinates into a typed array. Subclasses should implement this method if the shape they
     * define has texture coordinates. The default implementation returns null.
     *
     * @return The texture coordinates.
     */
    protected abstract fun computeTexCoords(): FloatArray

    /**
     * Computes or copies the indices of this mesh into a ShortArray. Subclasses must implement this method.
     */
    protected abstract fun computeMeshIndices(): ShortArray

    /**
     * Computes or copies the outline indices of this mesh into a ShortArray. Subclasses must implement this
     * method if they have outlines. The default implementation returns empty array.
     */
    protected abstract fun computeOutlineIndices(): ShortArray

    /**
     * Computes surface normals for this mesh's triangles and stores them in the normals array.
     */
    protected open fun computeNormals(): FloatArray {
        val vertices = currentData.vertexArray
        val indices = currentData.meshIndices
        val normalsBuffer = FloatArray(currentData.vertexArray.size)
        val triPoints = arrayOf(Vec3(), Vec3(), Vec3())
        val normals = arrayOfNulls<MutableList<Vec3>>(indices.max() + 1)

        // For each triangle, compute its normal assign it to each participating index
        for (i in indices.indices step VERTEX_STRIDE) {
            for (j in 0..2) {
                val k = indices[i + j] * VERTEX_STRIDE
                triPoints[j].set(vertices[k].toDouble(), vertices[k + 1].toDouble(), vertices[k + 2].toDouble())
            }

            val normal = Vec3.computeTriangleNormal(triPoints[0], triPoints[1], triPoints[2])

            for (j in 0..2) {
                val k = indices[i + j].toInt()
                val normalsK = normals[k] ?: mutableListOf<Vec3>().also { normals[k] = it }
                normalsK.add(normal)
            }
        }

        // Average the normals associated with each index and add the result to the normals buffer
        val normal = Vec3()
        for (i in normals.indices) {
            normals[i]?.let { normalI ->
                Vec3.average(normalI, normal)
                normal.normalize()
                normalsBuffer[i * VERTEX_STRIDE] = normal.x.toFloat()
                normalsBuffer[i * VERTEX_STRIDE + 1] = normal.y.toFloat()
                normalsBuffer[i * VERTEX_STRIDE + 2] = normal.z.toFloat()
            } ?: run {
                normalsBuffer[i * VERTEX_STRIDE] = 0f
                normalsBuffer[i * VERTEX_STRIDE + 1] = 0f
                normalsBuffer[i * VERTEX_STRIDE + 2] = 0f
            }
        }

        return normalsBuffer
    }
}