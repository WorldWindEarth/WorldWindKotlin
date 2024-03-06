package earth.worldwind.geom

import earth.worldwind.util.Logger.makeMessage
import kotlin.jvm.JvmStatic

open class TileMatrixSet(val sector: Sector, val entries: List<TileMatrix>) {
    /**
     * Construct empty tile matrix set.
     */
    constructor(): this(Sector(), emptyList<TileMatrix>())

    companion object {
        @JvmStatic
        fun fromTilePyramid(
            sector: Sector, matrixWidth: Int, matrixHeight: Int, tileWidth: Int, tileHeight: Int, resolution: Angle
        ): TileMatrixSet {
            require(resolution.inDegrees > 0.0) {
                makeMessage("TileMatrixSet", "fromTilePyramid", "invalidResolution")
            }
            var idx = 0
            var width = matrixWidth
            var height = matrixHeight
            val tileMatrices = mutableListOf<TileMatrix>()
            do {
                val matrix = TileMatrix(sector, idx++, width, height, tileWidth, tileHeight)
                tileMatrices.add(matrix)
                width *= 2
                height *= 2
            } while (matrix.resolution.inDegrees > resolution.inDegrees)
            return TileMatrixSet(sector, tileMatrices)
        }
    }

    val maxResolution get() = entries[entries.size - 1].resolution // entries.maxOf { it.resolution }

    fun indexOfMatrixNearest(resolution: Angle): Int {
        var nearestIdx = -1
        var nearestDelta2 = Double.POSITIVE_INFINITY
        for (idx in entries.indices) {
            val delta = entries[idx].resolution - resolution
            val delta2 = delta.inDegrees * delta.inDegrees
            if (nearestDelta2 > delta2) {
                nearestDelta2 = delta2
                nearestIdx = idx
            }
        }
        return nearestIdx
    }

    /**
     * Determine the min relevant index of matrix for the sector of this matrix set
     *
     * @param maxIndex Maximum available index
     * @return Minimum relevant index
     */
    fun minRelevantIndexOfMatrix(maxIndex: Int = entries.size - 1) = sector.minLevelNumber(maxIndex)
}