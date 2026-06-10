package earth.worldwind.globe.elevation.coverage.dted

import earth.worldwind.formats.dted.DTED
import earth.worldwind.formats.dted.read
import earth.worldwind.globe.elevation.ElevationSource
import earth.worldwind.util.Logger.DEBUG
import earth.worldwind.util.Logger.isLoggable
import earth.worldwind.util.Logger.log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.Buffer
import java.nio.ShortBuffer

/** Reads one tile (a sub-window of a single 1° cell) from that cell's file, on IO. */
internal class DtedTileFactory(
    private val file: File?,
    private val colFrac0: Double, private val colFrac1: Double,
    private val rowFrac0: Double, private val rowFrac1: Double,
    private val tileWidth: Int, private val tileHeight: Int,
) : ElevationSource.ElevationDataFactory {
    override suspend fun fetchElevationData(): Buffer? {
        val f = file ?: return null
        return withContext(Dispatchers.IO) {
            if (!f.isFile) return@withContext null
            val elevations = DTED.read(f, colFrac0, colFrac1, rowFrac0, rowFrac1, tileWidth, tileHeight)
            if (isLoggable(DEBUG)) log(DEBUG, "DTED tile loaded: ${f.name} ${tileWidth}x$tileHeight")
            ShortBuffer.wrap(elevations)
        }
    }

    override fun toString() = file?.toString() ?: "<missing DTED tile>"
}
