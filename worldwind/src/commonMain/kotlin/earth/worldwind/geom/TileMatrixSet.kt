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
            require(resolution > Angle.ZERO) {
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
            } while (matrix.degreesPerPixel > resolution.inDegrees)
            return TileMatrixSet(sector, tileMatrices)
        }
    }

    fun indexOfMatrixNearest(degreesPerPixel: Double): Int {
        var nearestIdx = -1
        var nearestDelta2 = Double.POSITIVE_INFINITY
        for (idx in entries.indices) {
            val delta = entries[idx].degreesPerPixel - degreesPerPixel
            val delta2 = delta * delta
            if (nearestDelta2 > delta2) {
                nearestDelta2 = delta2
                nearestIdx = idx
            }
        }
        return nearestIdx
    }
}