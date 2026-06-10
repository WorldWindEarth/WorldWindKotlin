package earth.worldwind.globe.elevation.coverage.dted

import earth.worldwind.geom.TileMatrix
import earth.worldwind.globe.elevation.ElevationSource
import earth.worldwind.globe.elevation.ElevationSourceFactory
import java.io.File
import kotlin.math.floor

/** Builds an [ElevationSource] per pyramid tile. Level 0 is one tile per 1° cell, so every tile lies
 *  within a single cell — the source reads a windowed sub-region of that cell's file (or null = missing). */
internal class DtedSourceFactory(private val cellFiles: Map<Long, File>) : ElevationSourceFactory {
    override val contentType = "DTED"

    override fun createElevationSource(tileMatrix: TileMatrix, row: Int, column: Int): ElevationSource {
        val tileSector = tileMatrix.tileSector(row, column)
        // The tile's DTED cell + its sub-window as cell-relative [0,1] fractions; coerce guards FP edge error.
        val lonDegSW = floor(tileSector.minLongitude.inDegrees).toInt()
        val latDegSW = floor(tileSector.minLatitude.inDegrees).toInt()
        val cellNorth = latDegSW + 1.0
        val colFrac0 = (tileSector.minLongitude.inDegrees - lonDegSW).coerceIn(0.0, 1.0)
        val colFrac1 = (tileSector.maxLongitude.inDegrees - lonDegSW).coerceIn(0.0, 1.0)
        val rowFrac0 = (cellNorth - tileSector.maxLatitude.inDegrees).coerceIn(0.0, 1.0) // north edge
        val rowFrac1 = (cellNorth - tileSector.minLatitude.inDegrees).coerceIn(0.0, 1.0) // south edge
        val key = packCell(latDegSW, lonDegSW)
        return ElevationSource.fromElevationDataFactory(
            DtedTileFactory(cellFiles[key], colFrac0, colFrac1, rowFrac0, rowFrac1, tileMatrix.tileWidth, tileMatrix.tileHeight)
        )
    }

    companion object {
        /** Pack a 1° cell's SW-corner integer degrees into a single key (lat in the high 32 bits, lon in the low). */
        internal fun packCell(latDegSW: Int, lonDegSW: Int) =
            (latDegSW.toLong() and 0xFFFFFFFFL) shl 32 or (lonDegSW.toLong() and 0xFFFFFFFFL)
    }
}
