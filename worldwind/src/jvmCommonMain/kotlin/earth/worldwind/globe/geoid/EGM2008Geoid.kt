package earth.worldwind.globe.geoid

import dev.icerock.moko.resources.AssetResource
import earth.worldwind.geom.Angle
import earth.worldwind.util.LruMemoryCache
import earth.worldwind.util.format.format
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Allows the retrieval of geoid offsets from the EGM2008 2.5 Minute Interpolation Grid sourced from the
 * National Geospatial-Intelligence Agency Office of Geomatics (https://earth-info.nga.mil/).
 *
 * The EGM2008 data path. This data file is not included in the SDK due to its size.
 *
 * @param offsetsFile a path pointing to a file with the geoid offsets.
 */
open class EGM2008Geoid(protected val offsetsFile: AssetResource): Geoid {
    override val displayName = "EGM2008"
    protected val offsetCache = LruMemoryCache<Int, FloatArray>((CACHE_SIZE * 8) / 10, CACHE_SIZE)

    override fun getOffset(latitude: Angle, longitude: Angle) = getOffset(latitude.inDegrees, longitude.inDegrees)

    protected fun getOffset(lat: Double, lon: Double): Float {
        val latRow = getLatRow(lat)
        val lonCol = getLonCol(lon)
        val latDataArray = getLatRows(latRow)
        val baseOffset = latDataArray[0]!![lonCol + N_ROW_MARKERS / 2]
        if (latDataArray[1] == null) return baseOffset

        // Interpolate with surrounding offset cells
        val lat180 = 90.0 - lat
        var lon360 = lon
        if (lon < 0) lon360 += 360.0
        val offsetCell = GridCell(lon360, lat180)
        val baseLat = latRow * GRID_RESOLUTION
        val baseLon = lonCol * GRID_RESOLUTION
        var interpOffset = 0f
        for (x in 0..1) {
            val cellLon = baseLon + x * GRID_RESOLUTION
            for (y in 0..1) {
                val cellOffset = latDataArray[y]!![lonCol + N_ROW_MARKERS / 2 + x]
                val cellLat = baseLat + y * GRID_RESOLUTION
                val interpCell = GridCell(cellLon, cellLat)
                val intersection = offsetCell.intersect(interpCell)
                interpOffset += cellOffset * (intersection.area() / CELL_AREA).toFloat()
            }
        }
        return interpOffset
    }

    protected fun getLatRow(lat: Double): Int {
        // Compute the row in the data file corresponding to a given latitude.
        // Latitude row zero in the data corresponds to 90 degrees latitude (North Pole) and increases southward
        // Longitude column zero in the data corresponds to 0 degrees of longitude and increases eastward
        val lat180 = 90.0 - lat
        return floor(lat180 / GRID_RESOLUTION).toInt()
    }

    protected fun getLonCol(lon: Double): Int {
        // Compute the column in the data file corresponding to a given latitude and longitude.
        // Latitude row zero in the data corresponds to 90 degrees latitude (North Pole) and increases southward
        // Longitude column zero in the data corresponds to 0 degrees of longitude and increases eastward
        var lon360 = lon
        if (lon < 0.0) lon360 += 360.0
        return floor(lon360 / GRID_RESOLUTION).toInt()
    }

    protected fun getLatRows(latRow: Int): Array<FloatArray?> {
        val interpRowIndices = intArrayOf(latRow, latRow + 1)
        var retrievalRequired = false
        val latDataArray = arrayOfNulls<FloatArray>(2)
        for (i in interpRowIndices.indices) {
            if (interpRowIndices[i] < N_LATITUDE_ROWS) {
                val latData = offsetCache[interpRowIndices[i]]
                latDataArray[i] = latData
                if (latData == null) retrievalRequired = true
            }
        }
        if (retrievalRequired) runCatching {
            val offsetFile = RandomAccessFile(offsetsFile.originalPath, "r")
            for (i in interpRowIndices.indices) {
                if (interpRowIndices[i] < N_LATITUDE_ROWS && latDataArray[i] == null) {
                    offsetFile.seek(interpRowIndices[i].toLong() * N_LAT_ROW_BYTES)
                    val latByteData = ByteArray(N_LAT_ROW_BYTES)
                    offsetFile.read(latByteData)
                    val latByteBuffer = ByteBuffer.wrap(latByteData).order(ByteOrder.LITTLE_ENDIAN)
                    val latFloatBuffer = latByteBuffer.asFloatBuffer()
                    val latData = FloatArray(N_LONGITUDE_COLS)
                    latFloatBuffer.get(latData)
                    offsetCache.put(interpRowIndices[i], latData, N_LAT_ROW_BYTES)
                    latDataArray[i] = latData
                }
            }
        }
        return latDataArray
    }

    protected inner class GridCell(
        var x1: Double = 0.0,
        var y1: Double = 0.0,
        var x2: Double = x1 + GRID_RESOLUTION,
        var y2: Double = y1 + GRID_RESOLUTION
    ) {
        fun area() = (x2 - x1) * (y2 - y1)

        fun intersect(that: GridCell) = GridCell(max(x1, that.x1), max(y1, that.y1), min(x2, that.x2), min(y2, that.y2))

        override fun toString() = "%5.2f,%5.2f,%5.2f,%5.2f".format(x1, y1, x2, y2)
    }

    companion object {
        private const val N_ROW_MARKERS = 2 // The beginning and end of each row of latitude data is a flag of some sort
        private const val N_LONGITUDE_COLS = 8640 + N_ROW_MARKERS // Number of float32s in a row of data in the data file.
        private const val N_LATITUDE_ROWS = 4321 // Number of rows.
        private const val GRID_RESOLUTION = 2.5 / 60.0 // 2.5 minute grid
        private const val CELL_AREA = GRID_RESOLUTION * GRID_RESOLUTION
        private const val CACHE_SIZE = N_LONGITUDE_COLS * 4L * 45 * 15 // Cache 15 degrees worth of offsets.
        private const val N_LAT_ROW_BYTES = N_LONGITUDE_COLS * 4 // Offsets are float32
    }
}