package earth.worldwind.globe.geoid

import earth.worldwind.geom.Angle
import kotlinx.coroutines.test.TestScope
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.IOException
import kotlin.test.BeforeTest
import kotlin.test.assertEquals

@RunWith(JUnit4::class)
class EGM96GeoidTest {
    private lateinit var egm96: EGM96Geoid

    @BeforeTest
    fun setUp() {
        // New EGM96 instance with EGM96 dataset
        egm96 = EGM96Geoid(scope = TestScope())
    }

    /**
     * Tests the determination of the EGM offset value using a latitude value that should match a grid point.
     */
    @Suppress("unused")
    @Test
    @Throws(IOException::class)
    fun testGetOffset_VerticalInterpolationTopGridPoint() {
        // The EGM96 data has an interval of 0.25 degrees in both the horizontal and vertical dimensions. To test and
        // demonstrate the fractional value being determined is correct, this setup will isolate a vertical
        // interpolation by landing on the horizontal grid points (e.g. longitudes ending with 0.0, 0.25, 0.5, 0.75)
        val longitude = Angle.fromDegrees(-105.0)
        // This is a non-interpolated baseline latitude for the top grid point of our testing points
        val latitude = Angle.fromDegrees(38.75)
        // Find the row and column values using the identical method the getOffset method uses
        // Code below is directly copied from the getOffset method accept where static class references were added
        val lat = latitude.inDegrees
        val lon = if (longitude.inDegrees >= 0.0) longitude.inDegrees else longitude.inDegrees + 360.0
        var topRow = ((90.0 - lat) / EGM96Geoid.INTERVAL).toInt()
        if (lat <= -90.0) topRow = EGM96Geoid.NUM_ROWS - 2
        val bottomRow = topRow + 1
        // Note that the number of columns does not repeat the column at 0 longitude, so we must force the right
        // column to 0 for any longitude that's less than one interval from 360, and force the left column to the
        // last column of the grid.
        var leftCol = (lon / EGM96Geoid.INTERVAL).toInt()
        var rightCol = leftCol + 1
        if (lon >= 360.0 - EGM96Geoid.INTERVAL) {
            leftCol = EGM96Geoid.NUM_COLS - 1
            rightCol = 0
        }
        // Determine the functions determination of the top lat and left lon
        val latTop = 90.0 - topRow * EGM96Geoid.INTERVAL
        val lonLeft = leftCol * EGM96Geoid.INTERVAL
        // Ensure the top latitude matches our expected latitude
        // This shows that the method has determined a row and column to query the dataset that corresponds with our
        // latitude value
        assertEquals(latitude.inDegrees, latTop, DELTA_LAT, "latitude matches after index conversion")
        // Using the confirmed latitude value from above (via the topRow and leftCol values), find the actual node value
        val latGridPointOffset = egm96.getPostOffset(topRow, leftCol) / 100f // the other method converts to meters
        // Use the interpolation method to determine the offset value
        val latOffset = egm96.getOffset(latitude, longitude)
        // Ensure that they are equal
        assertEquals(latGridPointOffset, latOffset, DELTA, "interpolated matches actual latitude")
    }

    /**
     * Tests the determination of the EGM offset value using a latitude value between grid points. This method will use
     * the bilinear interpolation method to calculate the offset value.
     */
    @Suppress("unused")
    @Test
    @Throws(IOException::class)
    fun testGetOffset_VerticalInterpolationPoint() {
        // The EGM96 data has an interval of 0.25 degrees in both the horizontal and vertical dimensions. To test and
        // demonstrate the fractional value being determined is correct, this setup will isolate a vertical
        // interpolation by landing on the horizontal grid points (e.g. longitudes ending with .0, 0.25, 0.5, 0.75)
        val longitude = Angle.fromDegrees(-105.0)
        // This is a non-interpolated baseline latitude for the top grid point of our testing points, it is closer to
        // the top grid point
        val latitude = Angle.fromDegrees(38.72)
        // Find the row and column values using the identical method the getOffset method uses
        // Code below is directly copied from the getOffset method accept where static class references were added
        val lat = latitude.inDegrees
        val lon = if (longitude.inDegrees >= 0.0) longitude.inDegrees else longitude.inDegrees + 360.0
        var topRow = ((90.0 - lat) / EGM96Geoid.INTERVAL).toInt()
        if (lat <= -90.0) topRow = EGM96Geoid.NUM_ROWS - 2
        val bottomRow = topRow + 1
        // Note that the number of columns does not repeat the column at 0 longitude, so we must force the right
        // column to 0 for any longitude that's less than one interval from 360, and force the left column to the
        // last column of the grid.
        var leftCol = (lon / EGM96Geoid.INTERVAL).toInt()
        var rightCol = leftCol + 1
        if (lon >= 360.0 - EGM96Geoid.INTERVAL) {
            leftCol = EGM96Geoid.NUM_COLS - 1
            rightCol = 0
        }
        // Determine the functions determination of the top lat and left lon
        val latTop = 90.0 - topRow * EGM96Geoid.INTERVAL
        // Need the bottom grid value for our own linear interpolation determination
        val latBottom = 90.0 - bottomRow * EGM96Geoid.INTERVAL
        val lonLeft = leftCol * EGM96Geoid.INTERVAL
        // Find the offset values of the top and bottom grid points
        val bottomOffsetValue = egm96.getPostOffset(bottomRow, leftCol) / 100f
        val topOffsetValue = egm96.getPostOffset(topRow, leftCol) / 100f
        // Ensure the top latitude matches our expected latitude
        // This shows that the method has determined a row and column to query the dataset that corresponds with our
        // latitude value
        assertEquals(38.75, latTop, DELTA_LAT, "top latitude matches after index conversion")
        assertEquals(38.5, latBottom, DELTA_LAT, "bottom latitude matches after index conversion")
        // The calculated EGM96 offset
        val latOffset = egm96.getOffset(latitude, longitude)
        val manuallyCalculatedV = (lat - latBottom) / (latTop - latBottom)
        val manuallyCalculatedInterpolationValue =
            ((topOffsetValue - bottomOffsetValue) * manuallyCalculatedV + bottomOffsetValue).toFloat()
        // Ensure that they are equal
        assertEquals(manuallyCalculatedInterpolationValue, latOffset, DELTA, "interpolated matches actual latitude")
    }

    /**
     * Tests the determination of the EGM offset value using a longitude value that should match a grid point.
     */
    @Suppress("unused")
    @Test
    @Throws(IOException::class)
    fun testGetOffset_HorizontalInterpolationLeftGridPoint() {
        // The EGM96 data has an interval of 0.25 degrees in both the horizontal and vertical dimensions. To test and
        // demonstrate the fractional value being determined is correct, this setup will isolate a horizontal
        // interpolation by landing on the vertical grid points (e.g. latitudes ending with .0, 0.25, 0.5, 0.75)
        val latitude = Angle.fromDegrees(38.75)
        // This is a non-interpolated baseline latitude for the left grid point of our testing points
        val longitude = Angle.fromDegrees(-105.0)
        // Find the row and column values using the identical method the getOffset method uses
        // Code below is directly copied from the getOffset method accept where static class references were added
        val lat = latitude.inDegrees
        val lon = if (longitude.inDegrees >= 0.0) longitude.inDegrees else longitude.inDegrees + 360.0
        var topRow = ((90.0 - lat) / EGM96Geoid.INTERVAL).toInt()
        if (lat <= -90.0) topRow = EGM96Geoid.NUM_ROWS - 2
        val bottomRow = topRow + 1
        // Note that the number of columns does not repeat the column at 0 longitude, so we must force the right
        // column to 0 for any longitude that's less than one interval from 360, and force the left column to the
        // last column of the grid.
        var leftCol = (lon / EGM96Geoid.INTERVAL).toInt()
        var rightCol = leftCol + 1
        if (lon >= 360.0 - EGM96Geoid.INTERVAL) {
            leftCol = EGM96Geoid.NUM_COLS - 1
            rightCol = 0
        }
        // Determine the functions determination of the top lat and left lon
        val latTop = 90.0 - topRow * EGM96Geoid.INTERVAL
        val lonLeft = leftCol * EGM96Geoid.INTERVAL
        // Ensure the top latitude matches our expected latitude
        // This shows that the method has determined a row and column to query the dataset that corresponds with our
        // latitude value
        assertEquals(longitude.inDegrees + 360.0, lonLeft, DELTA_LAT, "longitude matches after index conversion")
        // Using the confirmed longitude value from above (via the topRow and leftCol values), find the actual node
        // value
        val lonGridPointOffset = egm96.getPostOffset(topRow, leftCol) / 100f // the other method converts to meters
        // Use the interpolation method to determine the offset value
        val lonOffset = egm96.getOffset(latitude, longitude)
        // Ensure that they are equal
        assertEquals(lonGridPointOffset, lonOffset, DELTA, "interpolated matches actual longitude")
    }

    /**
     * Tests the determination of the EGM offset value using a longitude value between grid points. This method will use
     * the bilinear interpolation method to calculate the offset value.
     */
    @Suppress("unused")
    @Test
    @Throws(IOException::class)
    fun testGetOffset_HorizontalInterpolationPoint() {
        // The EGM96 data has an interval of 0.25 degrees in both the horizontal and vertical dimensions. To test and
        // demonstrate the fractional value being determined is correct, this setup will isolate a horizontal
        // interpolation by landing on the vertical grid points (e.g. latitudes ending with .0, 0.25, 0.5, 0.75)
        val latitude = Angle.fromDegrees(38.75)
        // This is a baseline longitude for the left grid point of our testing points, it is closer to the left grid
        // point
        val longitude = Angle.fromDegrees(-104.9)
        // Find the row and column values using the identical method the getOffset method uses
        // Code below is directly copied from the getOffset method accept where static class references were added
        val lat = latitude.inDegrees
        val lon = if (longitude.inDegrees >= 0) longitude.inDegrees else longitude.inDegrees + 360.0
        var topRow = ((90.0 - lat) / EGM96Geoid.INTERVAL).toInt()
        if (lat <= -90.0) topRow = EGM96Geoid.NUM_ROWS - 2
        val bottomRow = topRow + 1
        // Note that the number of columns does not repeat the column at 0 longitude, so we must force the right
        // column to 0 for any longitude that's less than one interval from 360, and force the left column to the
        // last column of the grid.
        var leftCol = (lon / EGM96Geoid.INTERVAL).toInt()
        var rightCol = leftCol + 1
        if (lon >= 360.0 - EGM96Geoid.INTERVAL) {
            leftCol = EGM96Geoid.NUM_COLS - 1
            rightCol = 0
        }
        // Determine the functions determination of the top lat and left lon
        val latTop = 90 - topRow * EGM96Geoid.INTERVAL
        val lonLeft = leftCol * EGM96Geoid.INTERVAL
        // Need the right longitude for our own interpolation testing
        val lonRight = rightCol * EGM96Geoid.INTERVAL
        // Find the offset values of the top and bottom grid points
        val leftOffsetValue = egm96.getPostOffset(topRow, leftCol) / 100.0
        val rightOffsetValue = egm96.getPostOffset(topRow, rightCol) / 100.0
        // Ensure the left longitude matches our expected longitude
        // This shows that the method has determined a row and column to query the dataset that corresponds with our
        // longitude value
        assertEquals(-105.0 + 360.0, lonLeft, DELTA_LAT, "left longitude matches after index conversion")
        assertEquals(-104.75 + 360.0, lonRight, DELTA_LAT, "right longitude matches after index conversion")
        // The calculated EGM96 offset
        val lonOffset = egm96.getOffset(latitude, longitude)
        val manuallyCalculatedH = (lon - lonLeft) / (lonRight - lonLeft)
        val manuallyCalculatedInterpolationValue =
            ((rightOffsetValue - leftOffsetValue) * manuallyCalculatedH + leftOffsetValue).toFloat()
        // Ensure that they are equal
        assertEquals(manuallyCalculatedInterpolationValue, lonOffset, DELTA, "interpolated matches actual longitude")
    }

    companion object {
        /**
         * The acceptable difference two float values may have and still satisfy an `assertEquals` method.
         */
        private const val DELTA = 1e-6f
        /**
         * The acceptable difference two double values may have and still satisfy an `assertEquals` method.
         */
        private const val DELTA_LAT = 1e-6
    }
}
