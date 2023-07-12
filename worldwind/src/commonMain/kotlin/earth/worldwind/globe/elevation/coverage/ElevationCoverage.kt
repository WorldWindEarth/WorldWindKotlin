package earth.worldwind.globe.elevation.coverage

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Sector

interface ElevationCoverage {
    var displayName: String?
    var isEnabled: Boolean
    val timestamp: Long
    fun invalidateTiles()
    fun getUserProperty(key: Any): Any?
    fun putUserProperty(key: Any, value: Any): Any?
    fun removeUserProperty(key: Any): Any?
    fun hasUserProperty(key: Any): Boolean
    fun getHeight(latitude: Angle, longitude: Angle, retrieve: Boolean): Float?
    fun getHeightGrid(gridSector: Sector, gridWidth: Int, gridHeight: Int, result: FloatArray)
    fun getHeightLimits(sector: Sector, result: FloatArray)
}