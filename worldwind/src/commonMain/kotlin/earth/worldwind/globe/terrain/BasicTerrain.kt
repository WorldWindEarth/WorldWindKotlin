package earth.worldwind.globe.terrain

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Line
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Vec3
import earth.worldwind.util.math.fract

open class BasicTerrain: Terrain {
    protected val tiles = mutableListOf<TerrainTile>()
    override val sector = Sector()
    var triStripElements: ShortArray? = null
    private val intersectPoint = Vec3()

    open fun addTile(tile: TerrainTile) {
        tiles.add(tile)
        sector.union(tile.sector)
    }

    open fun clear() {
        triStripElements = null
        tiles.clear()
        sector.setEmpty()
    }

    open fun copy(terrain: BasicTerrain) {
        tiles.clear()
        tiles.addAll(terrain.tiles)
        sector.copy(terrain.sector)
        triStripElements = terrain.triStripElements
    }

    open fun sort() = tiles.sortBy { it.sortOrder }

    override fun intersect(line: Line, result: Vec3): Boolean {
        var found = false
        val triStripElements = triStripElements ?: return found

        // Tiles considered as sorted by L1 distance on cylinder from camera
        for (tile in tiles) {
            // Translate the line to the terrain tile's local coordinate system.
            line.origin.subtract(tile.origin)

            // Compute the first intersection of the terrain tile with the line. The line is interpreted as a ray;
            // intersection points behind the line's origin are ignored. Store the nearest intersection found so far
            // in the result argument.
            if (line.triStripIntersection(tile.points, 3, triStripElements, triStripElements.size, intersectPoint)) {
                result.copy(intersectPoint).add(tile.origin)
                found = true
            }

            // Restore the line's origin to its previous coordinate system.
            line.origin.add(tile.origin)

            // Do not analyze other tiles as they are sorted by distance from camera
            if (found) break
        }
        return found
    }

    override fun surfacePoint(latitude: Angle, longitude: Angle, result: Vec3): Boolean {
        for (tile in tiles) {
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
}