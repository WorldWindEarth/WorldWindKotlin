package earth.worldwind.globe.elevation

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Sector
import earth.worldwind.globe.elevation.coverage.ElevationCoverage
import earth.worldwind.globe.elevation.coverage.ElevationCoverage.Companion.MISSING_DATA

open class ElevationModel(): Iterable<ElevationCoverage> {
    protected val coverages = mutableListOf<ElevationCoverage>()
    val timestamp: Long get() {
        var maxTimestamp = 0L
        for (i in coverages.indices) {
            val timestamp = coverages[i].timestamp
            if (maxTimestamp < timestamp) maxTimestamp = timestamp
        }
        return maxTimestamp
    }
    val count get() = coverages.size

    constructor(model: ElevationModel): this() { addAllCoverages(model) }

    constructor(iterable: Iterable<ElevationCoverage>): this() { for (coverage in iterable) addCoverage(coverage) }

    fun invalidate() = coverages.forEach { coverage -> coverage.clear() }

    fun getCoverageNamed(name: String) = coverages.firstOrNull { coverage -> coverage.displayName == name }

    fun getCoverageWithProperty(key: Any, value: Any) = coverages.firstOrNull { coverage ->
        coverage.hasUserProperty(key) && coverage.getUserProperty(key) == value
    }

    fun addCoverage(coverage: ElevationCoverage) = !coverages.contains(coverage) && coverages.add(coverage)

    fun addAllCoverages(model: ElevationModel): Boolean {
        val thatList = model.coverages
        //coverages.ensureCapacity(thatList.size)
        var changed = false
        for (thatCoverage in thatList) changed = changed or addCoverage(thatCoverage)
        return changed
    }

    fun removeCoverage(coverage: ElevationCoverage) = coverages.remove(coverage)

    fun removeAllCoverages(model: ElevationModel) = coverages.removeAll(model.coverages)

    fun clearCoverages() = coverages.clear()

    override fun iterator() = coverages.iterator()

    fun getElevation(latitude: Angle, longitude: Angle, retrieve: Boolean): Float {
        // coverages composite from fine to coarse
        for (i in coverages.indices.reversed()) {
            val height = coverages[i].getElevation(latitude, longitude, retrieve)
            if (height != MISSING_DATA) return height
        }
        return 0f
    }

    fun getElevationGrid(gridSector: Sector, gridWidth: Int, gridHeight: Int, result: FloatArray) {
        // coverages composite from coarse to fine
        for (i in coverages.indices) coverages[i].getElevationGrid(gridSector, gridWidth, gridHeight, result)
    }

    fun getElevationLimits(sector: Sector, result: FloatArray) {
        // coverage order is irrelevant
        for (i in coverages.indices) coverages[i].getElevationLimits(sector, result)
    }
}