package earth.worldwind.shape

import earth.worldwind.geom.*
import earth.worldwind.globe.Globe
import earth.worldwind.render.RenderContext
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage

/**
 * Represents a 3D triangle mesh.
 *
 * Altitudes within the mesh's positions are interpreted according to the mesh's altitude mode, which
 * can be one of the following:
 * - [AltitudeMode.ABSOLUTE]
 * - [AltitudeMode.RELATIVE_TO_GROUND]
 * - [AltitudeMode.CLAMP_TO_GROUND]
 *
 * If the latter, the mesh positions' altitudes are ignored. (If the mesh should be draped onto the
 * terrain, you might want to use surface [Polygon] instead.)
 *
 * Meshes have separate attributes for normal display and highlighted display. They use the interior and
 * outline attributes of [ShapeAttributes]. If those attributes identify an image, that image is
 * applied to the mesh. Texture coordinates for the image may be specified, but if not specified the full
 * image is stretched over the full mesh. If texture coordinates are specified, there must be one texture
 * coordinate for each vertex in the mesh.
 *
 * @param positions An array containing the mesh vertices.
 * There must be no more than 65536 positions. Use [split] to subdivide large meshes
 * into smaller ones that fit this limit.
 * @param indices An array of integers identifying the positions of each mesh triangle.
 * Each sequence of three indices defines one triangle in the mesh. The indices identify the index of the
 * position in the associated positions array. The indices for each triangle should be in counter-clockwise
 * order to identify the triangles as front-facing.
 * @param attributes The attributes to associate with this mesh. May be null, in which case
 * default attributes are associated.
 *
 * @throws IllegalArgumentException If the specified positions array is null, empty or undefined, the number of indices
 * is less than 3 or too many positions are specified (limit is 65536).
 */
open class TriangleMesh(
    positions: Array<Position>, indices: IntArray, attributes: ShapeAttributes = ShapeAttributes()
) : AbstractMesh(attributes), IRayIntersectable {
    /**
     * This mesh's positions.
     */
    var positions: Array<Position> = positions
        set(value) {
            require(value.isNotEmpty()) {
                logMessage(ERROR, "TriangleMesh", "positions", "Missing positions")
            }
            require(value.size <= MAX_POSITIONS) {
                logMessage(
                    ERROR, "TriangleMesh", "positions",
                    "Too many positions. Must be fewer than $MAX_POSITIONS. Use TriangleMesh.split to split the shape."
                )
            }
            field = value
            referencePosition.copy(value[0])
            reset()
        }

    /**
     * The mesh indices, an array of integers identifying the indexes of each triangle. Each index in this
     * array identifies the positions of one triangle. Each group of three indices in this array identifies
     * the positions of one triangle.
     */
    var indices: IntArray = indices
        set(value) {
            require(value.size >= VERTEX_STRIDE) {
                logMessage(ERROR, "TriangleMesh", "constructor", "Too few indices.")
            }
            field = value
            reset()
        }

    /**
     * The mesh outline indices, an array of integers identifying the positions in the outline. Each index in
     * this array identifies the index of the corresponding position in the positions array of this mesh.
     * May be null, in which case no outline is drawn.
     */
    var outlineIndices: IntArray? = null
        set(value) {
            field = value
            reset()
        }

    /**
     * This mesh's texture coordinates if this mesh is textured. A texture coordinate must be
     * provided for each mesh position. If no texture coordinates are specified then texture is not applied to
     * this mesh.
     */
    var textureCoordinates: Array<Vec2>? = null
        set(value) {
            require(value != null && value.size == positions.size) {
                logMessage(
                    ERROR, "TriangleMesh", "textureCoordinates",
                    "Number of texture coordinates is inconsistent with the currently specified positions."
                )
            }
            field = value
            reset()
        }

    init {
        require(positions.isNotEmpty()) {
            logMessage(ERROR, "TriangleMesh", "constructor", "Missing positions")
        }

        // Check for size limit, which is the max number of available indices for a 16-bit unsigned int
        require(positions.size <= MAX_POSITIONS) {
            logMessage(
                ERROR, "TriangleMesh", "constructor",
                "Too many positions. Must be fewer than $MAX_POSITIONS. Use TriangleMesh.split to split the shape."
            )
        }

        require(indices.size >= VERTEX_STRIDE) {
            logMessage(ERROR, "TriangleMesh", "constructor", "Too few indices.")
        }

        referencePosition.copy(positions[0])
    }

    override fun moveTo(globe: Globe, position: Position) {
        val refPos = referencePosition
        for (pos in positions) {
            val distance = refPos.greatCircleDistance(pos)
            val azimuth = refPos.greatCircleAzimuth(pos)
            position.greatCircleLocation(azimuth, distance, pos)
        }
        super.moveTo(globe, position)
    }

    override fun createSurfaceShape(): Polygon? {
        val outlineIndices = outlineIndices ?: return null
        val boundaries = mutableListOf<Position>()
        for (i in outlineIndices.indices) boundaries.add(positions[outlineIndices[i]])
        return Polygon(boundaries).apply {
            altitudeMode = AltitudeMode.CLAMP_TO_GROUND
            isFollowTerrain = true
        }
    }

    override fun computeMeshPoints(rc: RenderContext): FloatArray {
        var k = 0
        val meshPoints = FloatArray(positions.size * VERTEX_STRIDE)
        val pt = Vec3()

        for (i in positions.indices) {
            val pos = positions[i]
            rc.geographicToCartesian(pos.latitude, pos.longitude, pos.altitude * altitudeScale, altitudeMode, pt)
            pt.subtract(currentData.vertexOrigin)
            meshPoints[k++] = pt.x.toFloat()
            meshPoints[k++] = pt.y.toFloat()
            meshPoints[k++] = pt.z.toFloat()
        }

        return meshPoints
    }

    override fun computeTexCoords(): FloatArray {
        val textureCoordinates = textureCoordinates ?: return FloatArray(0)

        // Capture the texture coordinates to a single array parallel to the mesh points array
        val texCoords = FloatArray(2 * textureCoordinates.size)
        var k = 0

        for (i in textureCoordinates.indices) {
            val texCoord = textureCoordinates[i]
            texCoords[k++] = texCoord.x.toFloat()
            texCoords[k++] = texCoord.y.toFloat()
        }

        return texCoords
    }

    override fun computeMeshIndices() = ShortArray(indices.size) { indices[it].toShort() }

    override fun computeOutlineIndices(): ShortArray {
        val outlineIndices = outlineIndices ?: return ShortArray(0)
        return ShortArray(outlineIndices.size) { outlineIndices[it].toShort() }
    }

    /**
     * Determines all intersections between the given ray and this mesh.
     *
     * @param ray The ray to test for intersection
     * @param globe The globe for calculations
     * @returns List of Intersection objects sorted by distance from ray origin
     */
    override fun rayIntersections(ray: Line, globe: Globe): Array<Intersection> {
        // Transform mesh points to Vec3 arrays for triangle intersections
        val transformedPoints = mutableListOf<List<Vec3>>()
        val meshPoints = currentData.vertexArray

        // Group points by triangles
        for (i in indices.indices step VERTEX_STRIDE) {
            if (i + 2 < indices.size) {
                val points = mutableListOf<Vec3>()

                // Add each vertex of the triangle
                for (j in 0..2) {
                    val idx = indices[i + j] * VERTEX_STRIDE
                    val point = Vec3(
                        meshPoints[idx].toDouble(), meshPoints[idx + 1].toDouble(), meshPoints[idx + 2].toDouble()
                    )
                    // Add reference point (which was subtracted during mesh point computation)
                    point.add(currentData.vertexOrigin)
                    points.add(point)
                }

                transformedPoints.add(points)
            }
        }

        // Compute intersections using RayIntersector
        val positions = RayIntersector.computeIntersections(globe, ray, transformedPoints, ray.origin)

        // Convert positions to Intersection objects with distance
        return Array(positions.size) {
            val position = positions[it]
            val vec = globe.geographicToCartesian(position.latitude, position.longitude, position.altitude, Vec3())
            Intersection(position, ray.origin.distanceTo(vec))
        }
    }

    companion object {
        private const val MAX_POSITIONS = 65536

        /**
         * Splits a triangle mesh into several meshes, each of which contains fewer than 65536 positions.
         * @param positions An array containing the mesh vertices.
         * @param indices An array of integers identifying the positions of each mesh triangle.
         * Each sequence of three indices defines one triangle in the mesh. The indices identify the index of the
         * position in the associated positions array.
         * @param textureCoords The mesh's texture coordinates.
         * @param outlineIndices The mesh's outline indices.
         * @return A list of objects, each of which defines one subdivision of the full mesh.
         */
        fun split(
            positions: Array<Position>, indices: IntArray,
            textureCoords: Array<Vec2>? = null, outlineIndices: IntArray? = null
        ): List<TriangleMesh> {
            val result = mutableListOf<TriangleMesh>()
            val splitPositions = mutableListOf<Position>()
            val splitIndices = mutableListOf<Int>()
            val indexMap = mutableMapOf<Int, Int>()
            val splitTexCoords = mutableListOf<Vec2>()

            for (i in 0..indices.size) {
                if ((i == indices.size) || ((splitPositions.size > MAX_POSITIONS - 3) && splitIndices.size % VERTEX_STRIDE == 0)) {
                    if (splitPositions.isNotEmpty()) {
                        val mesh = TriangleMesh(splitPositions.toTypedArray(), splitIndices.toIntArray())
                        if (textureCoords != null) mesh.textureCoordinates = splitTexCoords.toTypedArray()

                        if (outlineIndices != null) {
                            val splitOutline = mutableListOf<Int>()
                            for (j in outlineIndices.indices) {
                                val originalIndex = outlineIndices[j]
                                indexMap[originalIndex]?.let { mappedIndex -> splitOutline.add(mappedIndex) }
                            }
                            mesh.outlineIndices = splitOutline.toIntArray()
                        }

                        result.add(mesh)
                    }

                    if (i == indices.size) break

                    splitPositions.clear()
                    splitIndices.clear()
                    indexMap.clear()
                    //splitTexCoords.clear()
                }

                val originalIndex = indices[i]
                var mappedIndex = indexMap[originalIndex]

                if (mappedIndex == null) {
                    mappedIndex = splitPositions.size
                    indexMap[originalIndex] = mappedIndex
                    splitPositions.add(positions[originalIndex])

                    if (textureCoords != null) splitTexCoords.add(textureCoords[originalIndex])
                }

                splitIndices.add(mappedIndex)
            }

            return result
        }
    }
}