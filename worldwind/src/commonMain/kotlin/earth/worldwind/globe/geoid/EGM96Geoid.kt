package earth.worldwind.globe.geoid

import dev.icerock.moko.resources.AssetResource
import earth.worldwind.MR
import earth.worldwind.geom.Angle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope

expect val isInitialized: Boolean
expect fun loadData(offsetsFile: AssetResource, scope: CoroutineScope)
expect fun getValue(k: Int): Short

/**
 * Computes EGM96 geoid offsets.
 *
 * A file with the offset grid must be passed to the constructor. This file must have 721 rows of 1440 2-byte integer
 * values. Each row corresponding to a latitude, with the first row corresponding to +90 degrees (90 North). The integer
 * values must be in centimeters.
 *
 * @param offsetsFile a resource pointing to a file with the geoid offsets. See the class description above for a
 * description of the file.
 */
@OptIn(DelicateCoroutinesApi::class)
open class EGM96Geoid(offsetsFile: AssetResource = MR.assets.EGM96_dat, scope: CoroutineScope = GlobalScope) : Geoid {
    init {
        loadData(offsetsFile, scope)
    }

    override fun getOffset(latitude: Angle, longitude: Angle): Float {
        // Return 0 for all offsets if the file not loaded yet or failed to load.
        if (!isInitialized) return 0f

        val lat = latitude.inDegrees
        val lon = if (longitude.inDegrees >= 0.0) longitude.inDegrees else longitude.inDegrees + 360.0

        var topRow = ((90.0 - lat) / INTERVAL).toInt()
        if (lat <= -90.0) topRow = NUM_ROWS - 2
        val bottomRow = topRow + 1

        // Note that the number of columns does not repeat the column at 0 longitude, so we must force the right
        // column to 0 for any longitude that's less than one interval from 360, and force the left column to the
        // last column of the grid.
        var leftCol = (lon / INTERVAL).toInt()
        var rightCol = leftCol + 1
        if (lon >= 360.0 - INTERVAL) {
            leftCol = NUM_COLS - 1
            rightCol = 0
        }

        val latBottom = 90.0 - bottomRow * INTERVAL
        val lonLeft = leftCol * INTERVAL

        val ul = getPostOffset(topRow, leftCol)
        val ll = getPostOffset(bottomRow, leftCol)
        val lr = getPostOffset(bottomRow, rightCol)
        val ur = getPostOffset(topRow, rightCol)

        val u = (lon - lonLeft) / INTERVAL
        val v = (lat - latBottom) / INTERVAL

        val pll = (1.0 - u) * (1.0 - v)
        val plr = u * (1.0 - v)
        val pur = u * v
        val pul = (1.0 - u) * v

        val offset = pll * ll + plr * lr + pur * ur + pul * ul

        return (offset / 100.0).toFloat() // convert centimeters to meters
    }

    internal fun getPostOffset(row: Int, col: Int) = getValue(row * NUM_COLS + col)

    companion object {
        // Description of the EGMA96 offsets file:
        // See: http://earth-info.nga.mil/GandG/wgs84/gravitymod/egm96/binary/binarygeoid.html
        //    The total size of the file is 2,076,480 bytes. This file was created
        //    using an INTEGER*2 data type format and is an unformatted direct access
        //    file. The data on the file is arranged in records from north to south.
        //    There are 721 records on the file starting with record 1 at 90 N. The
        //    last record on the file is at latitude 90 S. For each record, there
        //    are 1,440 15 arc-minute geoid heights arranged by longitude from west to
        //    east starting at the Prime Meridian (0 E) and ending 15 arc-minutes west
        //    of the Prime Meridian (359.75 E). On file, the geoid heights are in units
        //    of centimeters. While retrieving the Integer*2 values on file, divide by
        //    100 and this will produce a geoid height in meters.
        internal const val INTERVAL = 15.0 / 60.0 // 15' angle delta
        internal const val NUM_ROWS = 721
        internal const val NUM_COLS = 1440
    }
}
