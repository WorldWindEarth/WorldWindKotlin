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
    override val timestamp get() = updateState.timestamp
    private var userProperties: MutableMap<Any, Any>? = null
    // Single immutable snapshot keeps timestamp and log consistent for readers on other threads
    private var updateState = Clock.System.now().toEpochMilliseconds().let { UpdateState(it, it, emptyList()) }

    private class UpdateRecord(val timestamp: Long, val sector: Sector?)

    private class UpdateState(val timestamp: Long, val logStart: Long, val log: List<UpdateRecord>)

    protected fun updateTimestamp() = updateTimestamp(null)

    /** Registers a data change; [changedSector] scopes consumer invalidation, null invalidates everything */
    protected fun updateTimestamp(changedSector: Sector?) {
        val prev = updateState
        // Strictly increasing so consumers comparing cached timestamps never miss same-millisecond updates
        val time = maxOf(Clock.System.now().toEpochMilliseconds(), prev.timestamp + 1)
        val log = prev.log + UpdateRecord(time, changedSector?.let { Sector(it) })
        val overflow = log.size - UPDATE_LOG_CAPACITY
        updateState = if (overflow > 0) {
            UpdateState(time, log[overflow - 1].timestamp, log.subList(overflow, log.size).toList())
        } else UpdateState(time, prev.logStart, log)
    }

    override fun isChangedSince(time: Long, sector: Sector): Boolean {
        val state = updateState
        if (state.timestamp <= time) return false
        if (time < state.logStart) return true // updates before the log window are unknown - assume changed
        val log = state.log
        for (i in log.indices.reversed()) {
            val record = log[i]
            if (record.timestamp <= time) break // log is ordered - older records cannot match
            if (record.sector == null || record.sector.intersects(sector)) return true
        }
        return false
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

    companion object {
        private const val UPDATE_LOG_CAPACITY = 128
    }
}