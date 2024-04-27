package earth.worldwind.globe.elevation.coverage

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Sector

interface ElevationCoverage {
    /**
     * Elevation content bounding sector
     */
    val sector: Sector
    /**
     * Human-readable elevation coverage name
     */
    var displayName: String?
    /**
     * Determines if current elevation coverage should be processed during rendering
     */
    var isEnabled: Boolean
    /**
     * Last elevation update timestamp
     */
    val timestamp: Long

    /**
     * Clears elevation runtime data
     */
    fun clear()

    /**
     * Gets optional user property
     * @param key property key
     * @return property value
     */
    fun getUserProperty(key: Any): Any?

    /**
     * Puts optional user property
     * @param key property key
     * @param value property value
     * @return previous property value
     */
    fun putUserProperty(key: Any, value: Any): Any?

    /**
     * Removes optional user property
     * @param key property key
     * @return removed property value, if any exist
     */
    fun removeUserProperty(key: Any): Any?

    /**
     * Checks optional user property exists
     * @param key property key
     * @return true if property exists
     */
    fun hasUserProperty(key: Any): Boolean

    /**
     * Gets elevation at specified location
     * @param latitude location latitude
     * @param longitude location longitude
     * @param retrieve if true, then the value will be retrieved from a remote source for the next frame
     * @return elevation value at specified location
     */
    fun getHeight(latitude: Angle, longitude: Angle, retrieve: Boolean): Float?

    /**
     * Gets elevation values for the specified sector with required width and height resolution
     * @param gridSector specified sector to determine elevation values
     * @param gridWidth value matrix width
     * @param gridHeight value matrix height
     * @param result pre-allocated array for the result. Must be width * height size.
     */
    fun getHeightGrid(gridSector: Sector, gridWidth: Int, gridHeight: Int, result: FloatArray)

    /**
     * Gets elevation limits at specified sector
     * @param sector specified sector to determine elevation limits
     * @return pre-allocated array for the result. Must be size of 2.
     */
    fun getHeightLimits(sector: Sector, result: FloatArray)
}