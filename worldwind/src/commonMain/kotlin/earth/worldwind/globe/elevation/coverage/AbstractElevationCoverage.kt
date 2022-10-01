package earth.worldwind.globe.elevation.coverage

import earth.worldwind.geom.Sector
import kotlinx.datetime.Clock

abstract class AbstractElevationCoverage: ElevationCoverage {
    override var displayName: String? = null
    override var isEnabled = true
        set(value) {
            field = value
            updateTimestamp()
        }
    override var timestamp = Clock.System.now()
        protected set
    private var userProperties: MutableMap<Any, Any>? = null

    protected fun updateTimestamp() { timestamp = Clock.System.now() }

    override fun getUserProperty(key: Any) = userProperties?.get(key)

    override fun putUserProperty(key: Any, value: Any): Any? {
        val userProperties = userProperties ?: mutableMapOf<Any, Any>().also { userProperties = it }
        return userProperties.put(key, value)
    }

    override fun removeUserProperty(key: Any) = userProperties?.remove(key)

    override fun hasUserProperty(key: Any) = userProperties?.containsKey(key) == true

    override fun getHeightGrid(gridSector: Sector, gridWidth: Int, gridHeight: Int, result: FloatArray) {
        if (!isEnabled) return
        doGetHeightGrid(gridSector, gridWidth, gridHeight, result)
    }

    override fun getHeightLimits(sector: Sector, result: FloatArray) {
        if (!isEnabled) return
        doGetHeightLimits(sector, result)
    }

    protected abstract fun doGetHeightGrid(gridSector: Sector, gridWidth: Int, gridHeight: Int, result: FloatArray)

    protected abstract fun doGetHeightLimits(sector: Sector, result: FloatArray)
}