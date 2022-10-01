package earth.worldwind.globe.elevation

import earth.worldwind.geom.Sector
import earth.worldwind.globe.elevation.coverage.ElevationCoverage
import kotlinx.datetime.Instant

open class ElevationModel(): Iterable<ElevationCoverage> {
    protected val coverages = mutableListOf<ElevationCoverage>()
    val timestamp: Instant get() {
        var maxTimestamp = Instant.DISTANT_PAST
        for (coverage in coverages) {
            val timestamp = coverage.timestamp
            if (maxTimestamp < timestamp) maxTimestamp = timestamp
        }
        return maxTimestamp
    }
    val count get() = coverages.size

    constructor(model: ElevationModel): this() { addAllCoverages(model) }

    constructor(iterable: Iterable<ElevationCoverage>): this() { for (coverage in iterable) addCoverage(coverage) }

    fun invalidate() = coverages.forEach { coverage -> coverage.invalidateTiles() }

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

    fun getHeightGrid(gridSector: Sector, gridWidth: Int, gridHeight: Int, result: FloatArray) {
        // coverages composite from coarse to fine
        for (coverage in coverages) coverage.getHeightGrid(gridSector, gridWidth, gridHeight, result)
    }

    fun getHeightLimits(sector: Sector, result: FloatArray) {
        // coverage order is irrelevant
        for (coverage in coverages) coverage.getHeightLimits(sector, result)
    }
}