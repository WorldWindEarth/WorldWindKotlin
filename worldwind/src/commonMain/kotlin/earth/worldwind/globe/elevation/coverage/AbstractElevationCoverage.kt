package earth.worldwind.globe.elevation.coverage

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Sector
import earth.worldwind.globe.elevation.coverage.ElevationCoverage.Companion.MISSING_DATA
import kotlin.time.Clock

abstract class AbstractElevationCoverage: ElevationCoverage {
    final override val sector = Sector().setFullSphere() // Real data availability sector can be smaller than TMS sector
    override var displayName: String? = null
    override var isEnabled = true
        set(value) {
            field = value
            updateTimestamp()
        }
    override var timestamp = Clock.System.now().toEpochMilliseconds()
        protected set
    private var userProperties: MutableMap<Any, Any>? = null

    protected fun updateTimestamp() {
        timestamp = Clock.System.now().toEpochMilliseconds()
    }

    override fun getUserProperty(key: Any) = userProperties?.get(key)

    override fun putUserProperty(key: Any, value: Any): Any? {
        val userProperties = userProperties ?: mutableMapOf<Any, Any>().also { userProperties = it }
        return userProperties.put(key, value)
    }

    override fun removeUserProperty(key: Any) = userProperties?.remove(key)

    override fun hasUserProperty(key: Any) = userProperties?.containsKey(key) == true

    override fun getElevation(latitude: Angle, longitude: Angle, retrieve: Boolean): Float {
        return if (isEnabled) doGetElevation(latitude, longitude, retrieve) else MISSING_DATA
    }

    override fun getElevationGrid(gridSector: Sector, gridWidth: Int, gridHeight: Int, result: FloatArray) {
        if (isEnabled) doGetElevationGrid(gridSector, gridWidth, gridHeight, result)
    }

    override fun getElevationLimits(sector: Sector, result: FloatArray) {
        if (isEnabled) doGetElevationLimits(sector, result)
    }

    protected abstract fun doGetElevation(latitude: Angle, longitude: Angle, retrieve: Boolean): Float

    protected abstract fun doGetElevationGrid(gridSector: Sector, gridWidth: Int, gridHeight: Int, result: FloatArray)

    protected abstract fun doGetElevationLimits(sector: Sector, result: FloatArray)
}