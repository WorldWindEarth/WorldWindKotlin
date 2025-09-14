package earth.worldwind.shape

import earth.worldwind.geom.*
import earth.worldwind.globe.Globe
import earth.worldwind.render.RenderContext
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage

/**
 * Represents a 3D geographic mesh.
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
 * @param positions A two-dimensional array containing the mesh vertices.
 * Each entry of the array specifies the vertices of one row of the mesh. The arrays for all rows must
 * have the same length. There must be at least two rows, and each row must have at least two vertices.
 * There must be no more than 65536 positions.
 * @param attributes The attributes to associate with this mesh. May be null, in which case
 * default attributes are associated.
 *
 * @throws IllegalArgumentException If the specified positions array is null or undefined, the number of rows or the
 * number of vertices per row is less than 2, the array lengths are inconsistent, or too many positions are
 * specified (limit is 65536).
 */
open class GeographicMesh(
    positions: Array<Array<Position>>, attributes: ShapeAttributes = ShapeAttributes()
) : AbstractMesh(attributes), IRayIntersectable {
    /**
     * This mesh's positions. Each array in the positions array specifies the geographic positions of one
     * row of the mesh.
     */
    var positions: Array<Array<Position>> = positions
        set(value) {
            require(value.size >= 2 && value[0].size >= 2) {
                logMessage(ERROR, "GeographicMesh", "positions", "Number of positions is insufficient.")
            }

            // Check for size limit, which is the max number of available indices for a 16-bit unsigned int
            require(value.size * value[0].size <= MAX_POSITIONS) {
                logMessage(
                    ERROR, "GeographicMesh", "positions",
                    "Too many positions. Must be fewer than $MAX_POSITIONS. Try using multiple meshes."
                )
            }

            val size = value[0].size
            for (i in 1 until value.size) {
                require(value[i].size == size) {
                    logMessage(ERROR, "GeographicMesh", "positions", "Array lengths are inconsistent.")
                }
            }

            numRows = value.size
            numColumns = value[0].size

            field = value
            referencePosition.copy(determineReferencePosition(value))
            reset()
        }

    /**
     * This mesh's texture coordinates if this mesh is textured. A texture coordinate must be
     * provided for each mesh position. The texture coordinates are specified as a two-dimensional array,
     * each entry of which specifies the texture coordinates for one row of the mesh. Each texture coordinate
     * is a [Vec2] containing the s and t coordinates. If no texture coordinates are specified and
     * the attributes associated with this mesh indicate an image source, then texture coordinates are
     * automatically generated for the mesh.
     */
    var textureCoordinates: Array<Array<Vec2>>? = null
        set(value) {
            require(value != null && value.size == numRows) {
                logMessage(
                    ERROR, "GeographicMesh", "textureCoordinates",
                    "Number of texture coordinate rows is inconsistent with the currently specified positions."
                )
            }

            for (i in 0 until numRows) {
                require(value[i].size == numColumns) {
                    logMessage(
                        ERROR, "GeographicMesh", "textureCoordinates",
                        "Texture coordinate row lengths are inconsistent with the currently specified positions."
                    )
                }
            }

            field = value
            reset()
        }

    private var numRows: Int
    private var numColumns: Int

    init {
        require(positions.size >= 2 && positions[0].size >= 2) {
            logMessage(ERROR, "GeographicMesh", "constructor", "Number of positions is insufficient.")
        }

        // Check for size limit, which is the max number of available indices for a 16-bit unsigned int
        require(positions.size * positions[0].size <= MAX_POSITIONS) {
            logMessage(
                ERROR, "GeographicMesh", "constructor",
                "Too many positions. Must be fewer than $MAX_POSITIONS. Try using multiple meshes."
            )
        }

        val size = positions[0].size
        for (i in 1 until positions.size) {
            require(positions[i].size == size) {
                logMessage(ERROR, "GeographicMesh", "constructor", "Array lengths are inconsistent.")
            }
        }

        numRows = positions.size
        numColumns = positions[0].size
        referencePosition.copy(determineReferencePosition(positions))
    }

    override fun moveTo(globe: Globe, position: Position) {
        val refPos = referencePosition
        for (row in positions) for (pos in row) {
            val distance = refPos.greatCircleDistance(pos)
            val azimuth = refPos.greatCircleAzimuth(pos)
            position.greatCircleLocation(azimuth, distance, pos)
        }
        super.moveTo(globe, position)
    }

    override fun createSurfaceShape(): Polygon {
        val boundaries = mutableListOf<Position>()

        // Top row
        for (c in 0 until numColumns) {
            boundaries.add(positions[0][c])
        }

        // Right column
        for (r in 1 until numRows) {
            boundaries.add(positions[r][numColumns - 1])
        }

        // Bottom row (reversed)
        for (c in numColumns - 2 downTo 0) {
            boundaries.add(positions[numRows - 1][c])
        }

        // Left column (reversed, excluding corners)
        for (r in numRows - 2 downTo 1) {
            boundaries.add(positions[r][0])
        }

        return Polygon(boundaries).apply {
            altitudeMode = AltitudeMode.CLAMP_TO_GROUND
            isFollowTerrain = true
        }
    }

    override fun computeMeshPoints(rc: RenderContext): FloatArray {
        // Unwrap the mesh row arrays into one long array
        var k = 0
        val meshPoints = FloatArray(numRows * numColumns * VERTEX_STRIDE)
        val pt = Vec3()

        for (r in positions.indices) {
            for (c in positions[r].indices) {
                val pos = positions[r][c]
                rc.geographicToCartesian(pos.latitude, pos.longitude, pos.altitude * altitudeScale, altitudeMode, pt)
                pt.subtract(currentData.vertexOrigin)
                meshPoints[k++] = pt.x.toFloat()
                meshPoints[k++] = pt.y.toFloat()
                meshPoints[k++] = pt.z.toFloat()
            }
        }

        return meshPoints
    }

    override fun computeTexCoords() = textureCoordinates?.let { computeExplicitTexCoords(it) } ?: computeImplicitTexCoords()

    /**
     * Computes texture coordinates from explicitly specified texture coordinate arrays.
     */
    protected open fun computeExplicitTexCoords(textureCoordinates: Array<Array<Vec2>>): FloatArray {
        // Capture the texture coordinates to a single array parallel to the mesh points array
        val texCoords = FloatArray(2 * numRows * numColumns)
        var k = 0

        for (r in textureCoordinates.indices) {
            for (c in textureCoordinates[r].indices) {
                val texCoord = textureCoordinates[r][c]
                texCoords[k++] = texCoord.x.toFloat()
                texCoords[k++] = texCoord.y.toFloat()
            }
        }

        return texCoords
    }

    /**
     * Computes implicit texture coordinates that map the full image source into the full mesh.
     */
    protected open fun computeImplicitTexCoords(): FloatArray {
        // Create texture coordinates that map the full image source into the full mesh
        val texCoords = FloatArray(2 * numRows * numColumns)
        val rowDelta = 1.0 / (numRows - 1)
        val columnDelta = 1.0 / (numColumns - 1)
        var k = 0

        for (r in positions.indices) {
            val t = if (r == numRows - 1) 1.0 else r * rowDelta

            for (c in positions[r].indices) {
                val u = if (c == numColumns - 1) 1.0 else c * columnDelta
                texCoords[k++] = u.toFloat()
                texCoords[k++] = t.toFloat()
            }
        }

        return texCoords
    }

    override fun computeMeshIndices(): ShortArray {
        // Compute indices for individual triangles
        val meshIndices = ShortArray((numRows - 1) * (numColumns - 1) * 6)
        var i = 0

        for (r in 0 until numRows - 1) {
            for (c in 0 until numColumns - 1) {
                val k = r * numColumns + c

                meshIndices[i++] = k.toShort()
                meshIndices[i++] = (k + 1).toShort()
                meshIndices[i++] = (k + numColumns).toShort()
                meshIndices[i++] = (k + 1).toShort()
                meshIndices[i++] = (k + 1 + numColumns).toShort()
                meshIndices[i++] = (k + numColumns).toShort()
            }
        }

        return meshIndices
    }

    override fun computeOutlineIndices(): ShortArray {
        // Walk the mesh boundary and capture those positions for the outline
        val outlineIndices = ShortArray(2 * numRows + 2 * numColumns)
        var k = 0

        // Top row
        for (c in 0 until numColumns) outlineIndices[k++] = c.toShort()

        // Right column
        for (r in 1 until numRows) outlineIndices[k++] = ((r + 1) * numColumns - 1).toShort()

        // Bottom row (reversed)
        for (c in numRows * numColumns - 2 downTo (numRows - 1) * numColumns) outlineIndices[k++] = c.toShort()

        // Left column (reversed)
        for (r in numRows - 2 downTo 0) outlineIndices[k++] = (r * numColumns).toShort()

        return outlineIndices
    }

    /**
     * Determines all intersections between the given ray and this mesh.
     *
     * @param ray The ray to test for intersection
     * @param globe The globe for calculations
     * @returns List of Intersection objects sorted by distance from ray origin
     */
    override fun rayIntersections(ray: Line, globe: Globe): Array<Intersection> {
        // Transform grid points into triangles for intersection testing
        val transformedPoints = mutableListOf<List<Vec3>>()
        val meshPoints = currentData.vertexArray

        // Process each grid cell (consisting of two triangles)
        for (r in 0 until numRows - 1) {
            for (c in 0 until numColumns - 1) {
                // Calculate indices for the four corners of each grid cell
                val k1 = r * numColumns + c
                val k2 = k1 + 1
                val k3 = k1 + numColumns
                val k4 = k3 + 1

                // First triangle (k1, k2, k3)
                val triangle1 = listOf(
                    getVec3FromMeshPoints(meshPoints, k1),
                    getVec3FromMeshPoints(meshPoints, k2),
                    getVec3FromMeshPoints(meshPoints, k3)
                )

                // Second triangle (k2, k4, k3)
                val triangle2 = listOf(
                    getVec3FromMeshPoints(meshPoints, k2),
                    getVec3FromMeshPoints(meshPoints, k4),
                    getVec3FromMeshPoints(meshPoints, k3)
                )

                transformedPoints.add(triangle1)
                transformedPoints.add(triangle2)
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

    /**
     * Helper method to create a Vec3 from mesh points at a specific index
     */
    private fun getVec3FromMeshPoints(meshPoints: FloatArray, index: Int): Vec3 {
        val baseIndex = index * VERTEX_STRIDE
        val point = Vec3(
            meshPoints[baseIndex].toDouble(),
            meshPoints[baseIndex + 1].toDouble(),
            meshPoints[baseIndex + 2].toDouble()
        )
        // Add the reference point that was subtracted during mesh point computation
        point.add(currentData.vertexOrigin)
        return point
    }

    companion object {
        private const val MAX_POSITIONS = 65536

        /**
         * Determines the reference position for this mesh from the positions array.
         * @param positions The positions array.
         * @return The first position as the reference position.
         */
        private fun determineReferencePosition(positions: Array<Array<Position>>) = positions[0][0]

        /**
         * Creates grid indices for a mesh with the specified number of rows and columns.
         * @param nRows Number of rows in the mesh.
         * @param nCols Number of columns in the mesh.
         * @return An array of indices for individual triangles.
         */
        fun makeGridIndices(nRows: Int, nCols: Int): IntArray {
            val gridIndices = IntArray((nRows - 1) * (nCols - 1) * 6)
            var i = 0

            for (r in 0 until nRows - 1) {
                for (c in 0 until nCols - 1) {
                    val k = r * nCols + c

                    gridIndices[i++] = k
                    gridIndices[i++] = k + 1
                    gridIndices[i++] = k + nCols
                    gridIndices[i++] = k + 1
                    gridIndices[i++] = k + 1 + nCols
                    gridIndices[i++] = k + nCols
                }
            }

            return gridIndices
        }
    }
}