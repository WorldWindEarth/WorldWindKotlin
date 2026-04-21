package earth.worldwind.shape

import earth.worldwind.draw.DrawShapeState
import earth.worldwind.draw.Drawable
import earth.worldwind.draw.DrawableShape
import earth.worldwind.draw.DrawableSurfaceShape
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.radians
import earth.worldwind.geom.Location
import earth.worldwind.geom.Position
import earth.worldwind.geom.SphericalRotation
import earth.worldwind.geom.Vec3
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import earth.worldwind.globe.Globe
import earth.worldwind.render.RenderContext
import earth.worldwind.render.buffer.BufferObject
import earth.worldwind.render.program.TriangleShaderProgram
import earth.worldwind.shape.PathType.*
import earth.worldwind.util.NumericArray
import earth.worldwind.util.PolygonSplitter
import earth.worldwind.util.kgl.*
import earth.worldwind.util.math.encodeOrientationVector
import kotlin.jvm.JvmOverloads

open class Path @JvmOverloads constructor(
    positions: List<Position>, attributes: ShapeAttributes = ShapeAttributes()
): AbstractShape(attributes) {
    override val referencePosition: Position get() {
        // Cartesian centroid: average of vertex unit vectors, projected back to the sphere. Unlike
        // a lat/lon min/max midpoint this is rotation-equivariant — if the path is rotated, the
        // centroid rotates with it — which keeps drag (SphericalRotation in moveTo) stable across
        // successive events. Handles antimeridian crossing without special-casing.
        if (positions.isEmpty()) return Position()
        var sx = 0.0; var sy = 0.0; var sz = 0.0
        for (pos in positions) {
            val latRad = pos.latitude.inRadians
            val lonRad = pos.longitude.inRadians
            sx += cos(latRad) * cos(lonRad)
            sy += cos(latRad) * sin(lonRad)
            sz += sin(latRad)
        }
        val mag = sqrt(sx * sx + sy * sy + sz * sz)
        if (mag < 1.0e-10) return Position()
        val nx = sx / mag; val ny = sy / mag; val nz = sz / mag
        return Position(asin(nz.coerceIn(-1.0, 1.0)).radians, atan2(ny, nx).radians, 0.0)
    }
    var positions = positions
        set(value) {
            field = value
            reset()
        }
    protected val data = mutableMapOf<Globe.State?, PathData>()

    open class PathData {
        val vertexOrigin = Vec3()
        var vertexArray = FloatArray(0)
        var extrudeVertexArray = FloatArray(0)
        // TODO Use IntArray instead of mutableListOf<Int> to avoid unnecessary memory re-allocations
        val interiorElements = mutableListOf<Int>()
        val outlineElements = mutableListOf<Int>()
        val outlineChainStarts = mutableListOf<Int>()
        val verticalElements = mutableListOf<Int>()
        val extrudeVertexBufferKey = Any()
        val extrudeElementBufferKey = Any()
        val vertexBufferKey = Any()
        val elementBufferKey = Any()
        var refreshVertexArray = true
    }

    companion object {
        protected const val VERTEX_STRIDE = 5 // 5 floats
        protected const val EXTRUDE_SEGMENT_STRIDE = 2 * VERTEX_STRIDE // 2 vertices
        protected const val OUTLINE_SEGMENT_STRIDE = 4 * VERTEX_STRIDE // 4 vertices
        protected const val VERTICAL_SEGMENT_STRIDE = 4 * OUTLINE_SEGMENT_STRIDE // 4 points per 4 vertices per vertical line

        protected lateinit var currentData: PathData

        protected var vertexIndex = 0
        protected var verticalIndex = 0
        protected var extrudeIndex = 0

        protected val prevPoint = Vec3()
        protected val intermediateLocation = Location()
        protected var texCoord1d = 0.0
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
        val rotation = SphericalRotation(referencePosition, position)
        for (pos in positions) rotation.apply(pos)
        reset()
    }

    override fun makeDrawable(rc: RenderContext) {
        if (positions.size < 2) return // nothing to draw

        if (mustAssembleGeometry(rc)) {
            if (!rc.canAssembleGeometry()) return
            assembleGeometry(rc)
        }

        // Obtain a drawable form the render context pool, and compute distance to the render camera.
        val drawable: Drawable
        val drawState: DrawShapeState
        val cameraDistance: Double
        val cameraDistanceSq: Double
        if (isSurfaceShape) {
            val pool = rc.getDrawablePool(DrawableSurfaceShape.KEY)
            drawable = DrawableSurfaceShape.obtain(pool)
            drawState = drawable.drawState
            cameraDistanceSq = 0.0 // Not used by surface shape
            cameraDistance = cameraDistanceForTexture(rc, currentBoundindData.boundingSector)
            drawable.offset = rc.globe.offset
            drawable.sector.copy(currentBoundindData.boundingSector)
            drawable.version = computeVersion()
            drawable.isDynamic = isDynamic || rc.currentLayer.isDynamic
        } else {
            val pool = rc.getDrawablePool(DrawableShape.KEY)
            drawable = DrawableShape.obtain(pool)
            drawState = drawable.drawState
            cameraDistanceSq = cameraDistanceSquared(
                rc, currentData.vertexArray, currentData.vertexArray.size, OUTLINE_SEGMENT_STRIDE, currentData.vertexOrigin
            )
            cameraDistance = sqrtCameraDistanceForTexture(cameraDistanceSq)
        }

        // Use triangles mode to draw lines
        drawState.isLine = true

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
            val array = IntArray(currentData.outlineElements.size + currentData.verticalElements.size)
            var index = 0
            for (element in currentData.outlineElements) array[index++] = element
            for (element in currentData.verticalElements) array[index++] = element
            NumericArray.Ints(array)
        }

        drawOutline(rc, drawState, cameraDistance)

        // Configure the drawable according to the shape's attributes.
        drawState.vertexOrigin.copy(currentData.vertexOrigin)
        drawState.enableCullFace = false
        drawState.enableDepthTest = activeAttributes.isDepthTest
        drawState.enableDepthWrite = activeAttributes.isDepthWrite
        drawState.enableLighting = activeAttributes.isLightingEnabled

        // Enqueue the drawable for processing on the OpenGL thread.
        if (isSurfaceShape) {
            rc.offerSurfaceDrawable(drawable, zOrder)
            // For antimeridian-crossing surface paths, enqueue a secondary drawable for the other half.
            if (currentBoundindData.crossesAntimeridian) {
                val pool = rc.getDrawablePool(DrawableSurfaceShape.KEY)
                DrawableSurfaceShape.obtain(pool).also { d ->
                    d.offset = rc.globe.offset
                    d.sector.copy(currentBoundindData.additionalSector)
                    d.version = computeVersion(); d.isDynamic = isDynamic || rc.currentLayer.isDynamic
                    d.drawState.copy(drawState)
                    rc.offerSurfaceDrawable(d, zOrder)
                }
            }
        } else rc.offerShapeDrawable(drawable, cameraDistanceSq)

        drawInterior(rc, drawState, cameraDistanceSq)
    }

    protected open fun drawOutline(rc: RenderContext, drawState: DrawShapeState, cameraDistance: Double) {
        if (!activeAttributes.isDrawOutline || rc.isPickMode && !activeAttributes.isPickOutline) return

        // Configure the drawable to use the outline texture when drawing the outline.
        activeAttributes.outlineImageSource?.let { outlineImageSource ->
            rc.getTexture(outlineImageSource, defaultOutlineImageOptions)?.let { texture ->
                drawState.texture = texture
                drawState.textureLod = computeRepeatingTexCoordTransform(rc, texture, cameraDistance, drawState.texCoordMatrix)
            }
        }

        // Configure the drawable to display the shape's outline. Increase surface shape line widths by 1/2 pixel.
        // Lines drawn indirectly offscreen framebuffer appear thinner when sampled as a texture.
        drawState.color.copy(if (rc.isPickMode) pickColor else activeAttributes.outlineColor)
        drawState.opacity = if (rc.isPickMode) 1f else rc.currentLayer.opacity
        drawState.lineWidth = activeAttributes.outlineWidth + if (isSurfaceShape) 0.5f else 0f
        // Draw each sub-path chain separately to avoid the spurious connecting triangle that GL_TRIANGLE_STRIP
        // produces at the junction between antimeridian-split chains when drawn as one continuous strip.
        val chainStarts = currentData.outlineChainStarts
        for (ci in chainStarts.indices) {
            val start = chainStarts[ci]
            val end = if (ci + 1 < chainStarts.size) chainStarts[ci + 1] else currentData.outlineElements.size
            val count = end - start
            if (count > 0) drawState.drawElements(GL_TRIANGLE_STRIP, count, GL_UNSIGNED_INT, start * Int.SIZE_BYTES)
        }

        // Configure the drawable to display the shape's extruded verticals.
        if (activeAttributes.isDrawVerticals && isExtrude && !isSurfaceShape) {
            drawState.texture = null
            drawState.drawElements(
                GL_TRIANGLES, currentData.verticalElements.size,
                GL_UNSIGNED_INT, currentData.outlineElements.size * Int.SIZE_BYTES
            )
        }
    }

    protected open fun drawInterior(rc: RenderContext, drawState: DrawShapeState, cameraDistanceSq: Double) {
        if (!activeAttributes.isDrawInterior || rc.isPickMode && !activeAttributes.isPickInterior) return

        // Configure the drawable to display the shape's extruded interior.
        if (isExtrude && !isSurfaceShape) {
            val pool = rc.getDrawablePool(DrawableShape.KEY)
            val drawableExtrusion = DrawableShape.obtain(pool)
            val drawStateExtrusion = drawableExtrusion.drawState

            drawStateExtrusion.isLine = false

            // Use the basic GLSL program to draw the shape.
            drawStateExtrusion.program = rc.getShaderProgram(TriangleShaderProgram.KEY) { TriangleShaderProgram() }

            // Assemble the drawable's OpenGL vertex buffer object.
            drawStateExtrusion.vertexBuffer = rc.getBufferObject(currentData.extrudeVertexBufferKey) {
                BufferObject(GL_ARRAY_BUFFER, 0)
            }
            rc.offerGLBufferUpload(currentData.extrudeVertexBufferKey, bufferDataVersion) {
                NumericArray.Floats(currentData.extrudeVertexArray)
            }

            // Assemble the drawable's OpenGL element buffer object.
            drawStateExtrusion.elementBuffer = rc.getBufferObject(currentData.extrudeElementBufferKey) {
                BufferObject(GL_ELEMENT_ARRAY_BUFFER, 0)
            }
            rc.offerGLBufferUpload(currentData.extrudeElementBufferKey, bufferDataVersion) {
                NumericArray.Ints(currentData.interiorElements.toIntArray())
            }

            // Configure the drawable according to the shape's attributes.
            drawStateExtrusion.vertexOrigin.copy(currentData.vertexOrigin)
            drawStateExtrusion.vertexStride = VERTEX_STRIDE * 4 // stride in bytes
            drawStateExtrusion.enableCullFace = false
            drawStateExtrusion.enableDepthTest = activeAttributes.isDepthTest
            drawStateExtrusion.enableDepthWrite = activeAttributes.isDepthWrite
            drawStateExtrusion.enableLighting = activeAttributes.isLightingEnabled
            drawStateExtrusion.color.copy(if (rc.isPickMode) pickColor else activeAttributes.interiorColor)
            drawStateExtrusion.opacity = if (rc.isPickMode) 1f else rc.currentLayer.opacity
            drawStateExtrusion.texture = null
            drawStateExtrusion.texCoordAttrib.size = 2
            drawStateExtrusion.texCoordAttrib.offset = 12
            drawStateExtrusion.drawElements(GL_TRIANGLE_STRIP, currentData.interiorElements.size, GL_UNSIGNED_INT, 0)

            rc.offerShapeDrawable(drawableExtrusion, cameraDistanceSq)
        }
    }

    protected open fun mustAssembleGeometry(rc: RenderContext): Boolean {
        currentData = data[rc.globeState] ?: PathData().also { data[rc.globeState] = it }
        return currentData.refreshVertexArray
    }

    protected open fun assembleGeometry(rc: RenderContext) {
        // For surface shapes, split positions into sub-paths at antimeridian crossings.
        val subPaths: List<List<Position>>
        val crossesAntimeridian = if (isSurfaceShape) {
            val split = splitPositionsAtAntimeridian(positions)
            subPaths = split.first
            split.second
        } else {
            subPaths = listOf(positions)
            false
        }

        // Determine the total vertex count across all sub-paths.
        val noIntermediate = maximumIntermediatePoints <= 0 || pathType == LINEAR
        var totalVertexCount = 0
        for (sp in subPaths) {
            totalVertexCount += if (noIntermediate) sp.size + 2
            else sp.size + 2 + (sp.size - 1) * maximumIntermediatePoints
        }

        // Separate vertex array for interior polygon
        extrudeIndex = 0
        currentData.extrudeVertexArray = if (isExtrude && !isSurfaceShape)
            FloatArray((totalVertexCount + 2) * EXTRUDE_SEGMENT_STRIDE) else FloatArray(0)
        currentData.interiorElements.clear()

        vertexIndex = 0
        verticalIndex = if (isExtrude && !isSurfaceShape) totalVertexCount * OUTLINE_SEGMENT_STRIDE else 0
        currentData.vertexArray = if (isExtrude && !isSurfaceShape) {
            FloatArray(verticalIndex + positions.size * VERTICAL_SEGMENT_STRIDE)
        } else {
            FloatArray(totalVertexCount * OUTLINE_SEGMENT_STRIDE)
        }
        currentData.outlineElements.clear()
        currentData.outlineChainStarts.clear()
        currentData.verticalElements.clear()

        for (subPath in subPaths) {
            if (subPath.isEmpty()) continue
            currentData.outlineChainStarts.add(currentData.outlineElements.size)

            // Start each sub-path with a dummy vertex.
            var begin = subPath[0]
            calcPoint(rc, begin.latitude, begin.longitude, begin.altitude)
            addVertex(rc, begin.latitude, begin.longitude, begin.altitude, intermediate = true, addIndices = true)
            addVertex(rc, begin.latitude, begin.longitude, begin.altitude, intermediate = false, addIndices = true)

            for (idx in 1 until subPath.size) {
                val end = subPath[idx]
                addIntermediateVertices(rc, begin, end)
                val addIndices = idx != subPath.size - 1
                calcPoint(rc, end.latitude, end.longitude, end.altitude)
                addVertex(rc, end.latitude, end.longitude, end.altitude, intermediate = false, addIndices)
                begin = end
            }

            // End sub-path with a dummy vertex.
            addVertex(rc, begin.latitude, begin.longitude, begin.altitude, intermediate = true, addIndices = false)
        }

        currentData.refreshVertexArray = false

        // Compute the shape's bounding box or bounding sector.
        with(currentBoundindData) {
            if (isSurfaceShape) {
                if (crossesAntimeridian) {
                    computeAntimeridianSectors(
                        currentData.vertexArray, vertexIndex, OUTLINE_SEGMENT_STRIDE, currentData.vertexOrigin
                    )
                } else {
                    this.crossesAntimeridian = false
                    boundingSector.setEmpty()
                    boundingSector.union(currentData.vertexArray, vertexIndex, OUTLINE_SEGMENT_STRIDE)
                    boundingSector.translate(currentData.vertexOrigin.y, currentData.vertexOrigin.x)
                }
                boundingBox.setToUnitBox()
            } else {
                this.crossesAntimeridian = false
                boundingBox.setToPoints(currentData.vertexArray, vertexIndex, OUTLINE_SEGMENT_STRIDE)
                boundingBox.translate(currentData.vertexOrigin.x, currentData.vertexOrigin.y, currentData.vertexOrigin.z)
                boundingSector.setEmpty()
            }
        }
    }

    /**
     * Splits positions into sub-paths at antimeridian crossings, inserting intersection points at ±180.
     */
    protected open fun splitPositionsAtAntimeridian(positions: List<Position>): Pair<List<List<Position>>, Boolean> {
        var crossesAntimeridian = false
        val subPaths = mutableListOf<List<Position>>()
        var current = mutableListOf<Position>()
        for (i in positions.indices) {
            val pos = positions[i]
            if (current.isEmpty()) {
                current.add(pos)
                continue
            }
            val prev = current.last()
            if (Location.locationsCrossAntimeridian(listOf(prev, pos))) {
                crossesAntimeridian = true
                // Insert intersection point at ±180 for the end of the current sub-path.
                val iLat = PolygonSplitter.meridianIntersection(prev, pos, 180.0)
                    ?: (prev.latitude.inDegrees + pos.latitude.inDegrees) / 2.0
                val iLonEnd = if (prev.longitude.inDegrees > 0) 180.0 else -180.0
                current.add(Position.fromDegrees(iLat, iLonEnd, prev.altitude))
                subPaths.add(current)
                // Start a new sub-path from the other side of the antimeridian.
                current = mutableListOf()
                current.add(Position.fromDegrees(iLat, -iLonEnd, pos.altitude))
                current.add(pos)
            } else {
                current.add(pos)
            }
        }
        if (current.isNotEmpty()) subPaths.add(current)
        return Pair(subPaths, crossesAntimeridian)
    }

    protected open fun addIntermediateVertices(rc: RenderContext, begin: Position, end: Position) {
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
            calcPoint(rc, loc.latitude, loc.longitude, alt)
            addVertex(rc, loc.latitude, loc.longitude, alt, intermediate = true, addIndices = true)
            dist += deltaDist
            alt += deltaAlt
        }
    }

    protected open fun addVertex(
        rc: RenderContext, latitude: Angle, longitude: Angle, altitude: Double, intermediate: Boolean, addIndices : Boolean
    ) = with(currentData) {
        val vertex = vertexIndex / VERTEX_STRIDE
        if (vertexIndex == 0) {
            if (isSurfaceShape) vertexOrigin.set(longitude.inDegrees, latitude.inDegrees, altitude)
            else vertexOrigin.copy(point)
            texCoord1d = 0.0
        } else {
            texCoord1d += point.distanceTo(prevPoint)
        }
        prevPoint.copy(point)
        val upperLeftCorner = encodeOrientationVector(-1f, 1f)
        val lowerLeftCorner = encodeOrientationVector(-1f, -1f)
        val upperRightCorner = encodeOrientationVector(1f, 1f)
        val lowerRightCorner = encodeOrientationVector(1f, -1f)
        if (isSurfaceShape) {
            vertexArray[vertexIndex++] = (longitude.inDegrees - vertexOrigin.x).toFloat()
            vertexArray[vertexIndex++] = (latitude.inDegrees - vertexOrigin.y).toFloat()
            vertexArray[vertexIndex++] = (altitude - vertexOrigin.z).toFloat()
            vertexArray[vertexIndex++] = upperLeftCorner
            vertexArray[vertexIndex++] = texCoord1d.toFloat()

            vertexArray[vertexIndex++] = (longitude.inDegrees - vertexOrigin.x).toFloat()
            vertexArray[vertexIndex++] = (latitude.inDegrees - vertexOrigin.y).toFloat()
            vertexArray[vertexIndex++] = (altitude - vertexOrigin.z).toFloat()
            vertexArray[vertexIndex++] = lowerLeftCorner
            vertexArray[vertexIndex++] = texCoord1d.toFloat()

            vertexArray[vertexIndex++] = (longitude.inDegrees - vertexOrigin.x).toFloat()
            vertexArray[vertexIndex++] = (latitude.inDegrees - vertexOrigin.y).toFloat()
            vertexArray[vertexIndex++] = (altitude - vertexOrigin.z).toFloat()
            vertexArray[vertexIndex++] = upperRightCorner
            vertexArray[vertexIndex++] = texCoord1d.toFloat()

            vertexArray[vertexIndex++] = (longitude.inDegrees - vertexOrigin.x).toFloat()
            vertexArray[vertexIndex++] = (latitude.inDegrees - vertexOrigin.y).toFloat()
            vertexArray[vertexIndex++] = (altitude - vertexOrigin.z).toFloat()
            vertexArray[vertexIndex++] = lowerRightCorner
            vertexArray[vertexIndex++] = texCoord1d.toFloat()

            if (addIndices) {
                outlineElements.add(vertex)
                outlineElements.add(vertex + 1)
                outlineElements.add(vertex + 2)
                outlineElements.add(vertex + 3)
            }
        } else {
            vertexArray[vertexIndex++] = (point.x - vertexOrigin.x).toFloat()
            vertexArray[vertexIndex++] = (point.y - vertexOrigin.y).toFloat()
            vertexArray[vertexIndex++] = (point.z - vertexOrigin.z).toFloat()
            vertexArray[vertexIndex++] = upperLeftCorner
            vertexArray[vertexIndex++] = texCoord1d.toFloat()

            vertexArray[vertexIndex++] = (point.x - vertexOrigin.x).toFloat()
            vertexArray[vertexIndex++] = (point.y - vertexOrigin.y).toFloat()
            vertexArray[vertexIndex++] = (point.z - vertexOrigin.z).toFloat()
            vertexArray[vertexIndex++] = lowerLeftCorner
            vertexArray[vertexIndex++] = texCoord1d.toFloat()

            vertexArray[vertexIndex++] = (point.x - vertexOrigin.x).toFloat()
            vertexArray[vertexIndex++] = (point.y - vertexOrigin.y).toFloat()
            vertexArray[vertexIndex++] = (point.z - vertexOrigin.z).toFloat()
            vertexArray[vertexIndex++] = upperRightCorner
            vertexArray[vertexIndex++] = texCoord1d.toFloat()

            vertexArray[vertexIndex++] = (point.x - vertexOrigin.x).toFloat()
            vertexArray[vertexIndex++] = (point.y - vertexOrigin.y).toFloat()
            vertexArray[vertexIndex++] = (point.z - vertexOrigin.z).toFloat()
            vertexArray[vertexIndex++] = lowerRightCorner
            vertexArray[vertexIndex++] = texCoord1d.toFloat()

            if (addIndices) {
                outlineElements.add(vertex)
                outlineElements.add(vertex + 1)
                outlineElements.add(vertex + 2)
                outlineElements.add(vertex + 3)
            }
            if (isExtrude) {
                val extrudeVertex =  extrudeIndex / VERTEX_STRIDE

                extrudeVertexArray[extrudeIndex++] = (point.x - vertexOrigin.x).toFloat()
                extrudeVertexArray[extrudeIndex++] = (point.y - vertexOrigin.y).toFloat()
                extrudeVertexArray[extrudeIndex++] = (point.z - vertexOrigin.z).toFloat()
                extrudeVertexArray[extrudeIndex++] = 0f // unused
                extrudeVertexArray[extrudeIndex++] = 0f // unused

                extrudeVertexArray[extrudeIndex++] = (vertPoint.x - vertexOrigin.x).toFloat()
                extrudeVertexArray[extrudeIndex++] = (vertPoint.y - vertexOrigin.y).toFloat()
                extrudeVertexArray[extrudeIndex++] = (vertPoint.z - vertexOrigin.z).toFloat()
                extrudeVertexArray[extrudeIndex++] = 0f // unused
                extrudeVertexArray[extrudeIndex++] = 0f // unused

                interiorElements.add(extrudeVertex)
                interiorElements.add(extrudeVertex + 1)

                if (!intermediate) {
                    val index =  verticalIndex / VERTEX_STRIDE
                    
                    // first vertices, that simulate pointA for next vertices
                    vertexArray[verticalIndex++] = (point.x - vertexOrigin.x).toFloat()
                    vertexArray[verticalIndex++] = (point.y - vertexOrigin.y).toFloat()
                    vertexArray[verticalIndex++] = (point.z - vertexOrigin.z).toFloat()
                    vertexArray[verticalIndex++] = upperLeftCorner
                    vertexArray[verticalIndex++] = 0.0f

                    vertexArray[verticalIndex++] = (point.x - vertexOrigin.x).toFloat()
                    vertexArray[verticalIndex++] = (point.y - vertexOrigin.y).toFloat()
                    vertexArray[verticalIndex++] = (point.z - vertexOrigin.z).toFloat()
                    vertexArray[verticalIndex++] = lowerLeftCorner
                    vertexArray[verticalIndex++] = 0.0f

                    vertexArray[verticalIndex++] = (point.x - vertexOrigin.x).toFloat()
                    vertexArray[verticalIndex++] = (point.y - vertexOrigin.y).toFloat()
                    vertexArray[verticalIndex++] = (point.z - vertexOrigin.z).toFloat()
                    vertexArray[verticalIndex++] = upperRightCorner
                    vertexArray[verticalIndex++] = 0.0f

                    vertexArray[verticalIndex++] = (point.x - vertexOrigin.x).toFloat()
                    vertexArray[verticalIndex++] = (point.y - vertexOrigin.y).toFloat()
                    vertexArray[verticalIndex++] = (point.z - vertexOrigin.z).toFloat()
                    vertexArray[verticalIndex++] = lowerRightCorner
                    vertexArray[verticalIndex++] = 0.0f

                    // first pointB
                    vertexArray[verticalIndex++] = (point.x - vertexOrigin.x).toFloat()
                    vertexArray[verticalIndex++] = (point.y - vertexOrigin.y).toFloat()
                    vertexArray[verticalIndex++] = (point.z - vertexOrigin.z).toFloat()
                    vertexArray[verticalIndex++] = upperLeftCorner
                    vertexArray[verticalIndex++] = 0.0f

                    vertexArray[verticalIndex++] = (point.x - vertexOrigin.x).toFloat()
                    vertexArray[verticalIndex++] = (point.y - vertexOrigin.y).toFloat()
                    vertexArray[verticalIndex++] = (point.z - vertexOrigin.z).toFloat()
                    vertexArray[verticalIndex++] = lowerLeftCorner
                    vertexArray[verticalIndex++] = 0.0f

                    vertexArray[verticalIndex++] = (point.x - vertexOrigin.x).toFloat()
                    vertexArray[verticalIndex++] = (point.y - vertexOrigin.y).toFloat()
                    vertexArray[verticalIndex++] = (point.z - vertexOrigin.z).toFloat()
                    vertexArray[verticalIndex++] = upperRightCorner
                    vertexArray[verticalIndex++] = 0.0f

                    vertexArray[verticalIndex++] = (point.x - vertexOrigin.x).toFloat()
                    vertexArray[verticalIndex++] = (point.y - vertexOrigin.y).toFloat()
                    vertexArray[verticalIndex++] = (point.z - vertexOrigin.z).toFloat()
                    vertexArray[verticalIndex++] = lowerRightCorner
                    vertexArray[verticalIndex++] = 0.0f

                    // second pointB
                    vertexArray[verticalIndex++] = (vertPoint.x - vertexOrigin.x).toFloat()
                    vertexArray[verticalIndex++] = (vertPoint.y - vertexOrigin.y).toFloat()
                    vertexArray[verticalIndex++] = (vertPoint.z - vertexOrigin.z).toFloat()
                    vertexArray[verticalIndex++] = upperLeftCorner
                    vertexArray[verticalIndex++] = 0.0f

                    vertexArray[verticalIndex++] = (vertPoint.x - vertexOrigin.x).toFloat()
                    vertexArray[verticalIndex++] = (vertPoint.y - vertexOrigin.y).toFloat()
                    vertexArray[verticalIndex++] = (vertPoint.z - vertexOrigin.z).toFloat()
                    vertexArray[verticalIndex++] = lowerLeftCorner
                    vertexArray[verticalIndex++] = 0.0f

                    vertexArray[verticalIndex++] = (vertPoint.x - vertexOrigin.x).toFloat()
                    vertexArray[verticalIndex++] = (vertPoint.y - vertexOrigin.y).toFloat()
                    vertexArray[verticalIndex++] = (vertPoint.z - vertexOrigin.z).toFloat()
                    vertexArray[verticalIndex++] = upperRightCorner
                    vertexArray[verticalIndex++] = 0.0f

                    vertexArray[verticalIndex++] = (vertPoint.x - vertexOrigin.x).toFloat()
                    vertexArray[verticalIndex++] = (vertPoint.y - vertexOrigin.y).toFloat()
                    vertexArray[verticalIndex++] = (vertPoint.z - vertexOrigin.z).toFloat()
                    vertexArray[verticalIndex++] = lowerRightCorner
                    vertexArray[verticalIndex++] = 0.0f

                    // last vertices, that simulate pointC for previous vertices
                    vertexArray[verticalIndex++] = (vertPoint.x - vertexOrigin.x).toFloat()
                    vertexArray[verticalIndex++] = (vertPoint.y - vertexOrigin.y).toFloat()
                    vertexArray[verticalIndex++] = (vertPoint.z - vertexOrigin.z).toFloat()
                    vertexArray[verticalIndex++] = upperLeftCorner
                    vertexArray[verticalIndex++] = 0.0f

                    vertexArray[verticalIndex++] = (vertPoint.x - vertexOrigin.x).toFloat()
                    vertexArray[verticalIndex++] = (vertPoint.y - vertexOrigin.y).toFloat()
                    vertexArray[verticalIndex++] = (vertPoint.z - vertexOrigin.z).toFloat()
                    vertexArray[verticalIndex++] = lowerLeftCorner
                    vertexArray[verticalIndex++] = 0.0f

                    vertexArray[verticalIndex++] = (vertPoint.x - vertexOrigin.x).toFloat()
                    vertexArray[verticalIndex++] = (vertPoint.y - vertexOrigin.y).toFloat()
                    vertexArray[verticalIndex++] = (vertPoint.z - vertexOrigin.z).toFloat()
                    vertexArray[verticalIndex++] = upperRightCorner
                    vertexArray[verticalIndex++] = 0.0f

                    vertexArray[verticalIndex++] = (vertPoint.x - vertexOrigin.x).toFloat()
                    vertexArray[verticalIndex++] = (vertPoint.y - vertexOrigin.y).toFloat()
                    vertexArray[verticalIndex++] = (vertPoint.z - vertexOrigin.z).toFloat()
                    vertexArray[verticalIndex++] = lowerRightCorner
                    vertexArray[verticalIndex++] = 0.0f

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
    }
}