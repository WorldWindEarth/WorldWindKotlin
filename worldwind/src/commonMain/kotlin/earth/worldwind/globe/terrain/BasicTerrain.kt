package earth.worldwind.globe.terrain

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Line
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Vec3
import earth.worldwind.util.math.fract
import kotlin.math.max
import kotlin.math.min

open class BasicTerrain(
    tiles: List<TerrainTile>, sector: Sector, protected val triStripElements: ShortArray?
): Terrain {
    protected val tiles = tiles.toList()
    override val sector = Sector(sector)
    private val intersectPoint = Vec3()

    override fun intersect(line: Line, result: Vec3): Boolean {
        var found = false
        val triStripElements = triStripElements ?: return found

        // Nearest hit so far, as a ray parameter. Tiles are sorted by distance from the camera,
        // which is not ray order when the ray starts elsewhere, so the nearest hit must be kept.
        var nearest = Double.POSITIVE_INFINITY

        for (i in tiles.indices) {
            val tile = tiles[i]
            // Translate the line to the terrain tile's local coordinate system.
            line.origin.subtract(tile.origin)

            // Skip tiles whose bounds the ray misses or enters beyond the nearest hit so far.
            if (rayBoundsEntry(line, tile.localBounds) < nearest &&
                line.triStripIntersection(tile.points, 3, triStripElements, triStripElements.size, intersectPoint)
            ) {
                val distance = rayParameterOf(line, intersectPoint)
                if (distance < nearest) {
                    nearest = distance
                    result.copy(intersectPoint).add(tile.origin)
                    found = true
                }
            }

            // Restore the line's origin to its previous coordinate system.
            line.origin.add(tile.origin)
        }
        return found
    }

    /** Ray parameter of [point], which is assumed to lie on the ray, in [rayBoundsEntry] units. */
    private fun rayParameterOf(line: Line, point: Vec3): Double {
        val direction = line.direction
        val lengthSquared = direction.x * direction.x + direction.y * direction.y + direction.z * direction.z
        if (lengthSquared == 0.0) return Double.POSITIVE_INFINITY
        val origin = line.origin
        return ((point.x - origin.x) * direction.x + (point.y - origin.y) * direction.y +
            (point.z - origin.z) * direction.z) / lengthSquared
    }

    /**
     * Ray-slab test against tile-local bounds (minX, maxX, minY, maxY, minZ, maxZ). Returns the ray
     * parameter where the ray enters them, or [Double.POSITIVE_INFINITY] when it misses. Ignores
     * intersections behind the ray's origin, matching [Line.triStripIntersection] semantics.
     */
    private fun rayBoundsEntry(line: Line, bounds: FloatArray): Double {
        var tMin = 0.0
        var tMax = Double.MAX_VALUE
        val origin = line.origin
        val direction = line.direction
        for (axis in 0..2) {
            val o = when (axis) { 0 -> origin.x; 1 -> origin.y; else -> origin.z }
            val d = when (axis) { 0 -> direction.x; 1 -> direction.y; else -> direction.z }
            val min = bounds[axis * 2].toDouble()
            val max = bounds[axis * 2 + 1].toDouble()
            if (d == 0.0) {
                if (o < min || o > max) return Double.POSITIVE_INFINITY
            } else {
                var t0 = (min - o) / d
                var t1 = (max - o) / d
                if (t0 > t1) { val t = t0; t0 = t1; t1 = t }
                if (t0 > tMin) tMin = t0
                if (t1 < tMax) tMax = t1
                if (tMin > tMax) return Double.POSITIVE_INFINITY
            }
        }
        return tMin
    }

    override fun surfacePoint(latitude: Angle, longitude: Angle, result: Vec3): Boolean {
        for (i in tiles.indices) {
            val tile = tiles[i]
            val sector = tile.sector

            // Find the first tile that contains the specified location.
            if (sector.contains(latitude, longitude)) {
                // Compute the location's parameterized coordinates (s, t) within the tile grid, along with the
                // fractional component (sf, tf) and integral component (si, ti).
                val tileWidth = tile.level.tileWidth
                val tileHeight = tile.level.tileHeight
                val s = (longitude.inDegrees - sector.minLongitude.inDegrees) / sector.deltaLongitude.inDegrees * (tileWidth - 1)
                val t = (latitude.inDegrees - sector.minLatitude.inDegrees) / sector.deltaLatitude.inDegrees * (tileHeight - 1)
                val sf = if (s < tileWidth - 1) fract(s) else 1.0
                val tf = if (t < tileHeight - 1) fract(t) else 1.0
                val si = if (s < tileWidth - 1) (s + 1).toInt() else tileWidth - 1
                val ti = if (t < tileHeight - 1) (t + 1).toInt() else tileHeight - 1

                // Compute the location in the tile's local coordinate system. Perform a bilinear interpolation of
                // the cell's four points based on the fractional portion of the location's parameterized coordinates.
                // Tile coordinates are organized in the points array in row major order, starting at the tile's
                // Southwest corner. Account for the tile's border vertices, which are embedded in the points array but
                // must be ignored for this computation.
                val tileRowStride = tileWidth + 2
                val i00 = (si + ti * tileRowStride) * 3 // lower left coordinate
                val i10 = i00 + 3 // lower right coordinate
                val i01 = (si + (ti + 1) * tileRowStride) * 3 // upper left coordinate
                val i11 = i01 + 3 // upper right coordinate
                val f00 = (1 - sf) * (1 - tf)
                val f10 = sf * (1 - tf)
                val f01 = (1 - sf) * tf
                val f11 = sf * tf
                val points = tile.points
                result.x = points[i00] * f00 + points[i10] * f10 + points[i01] * f01 + points[i11] * f11
                result.y = points[i00 + 1] * f00 + points[i10 + 1] * f10 + points[i01 + 1] * f01 + points[i11 + 1] * f11
                result.z = points[i00 + 2] * f00 + points[i10 + 2] * f10 + points[i01 + 2] * f01 + points[i11 + 2] * f11

                // Translate the surface point from the tile's local coordinate system to Cartesian coordinates.
                result.x += tile.origin.x
                result.y += tile.origin.y
                result.z += tile.origin.z
                return true
            }
        }

        // No tile was found that contains the location.
        return false
    }

    override fun heightLimits(levelNumberDepth: Int, result: FloatArray) {
        result[0] = Float.MAX_VALUE
        result[1] = -Float.MAX_VALUE
        val maxLevelNumber = tiles.maxOf { it.level.levelNumber }
        val minLevelNumber = maxLevelNumber - levelNumberDepth
        for (tile in tiles) if (tile.level.levelNumber >= minLevelNumber) {
            result[0] = min(result[0], tile.heightLimits[0])
            result[1] = max(result[1], tile.heightLimits[1])
        }
        if (result[0] > result[1]) result.fill(0f)
    }
}