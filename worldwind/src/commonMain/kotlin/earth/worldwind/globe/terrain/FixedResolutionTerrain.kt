package earth.worldwind.globe.terrain

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Line
import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Vec3
import earth.worldwind.globe.Globe
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Terrain sampled from the globe's elevation model at a fixed resolution, independent of what the
 * renderer currently draws. Unlike [BasicTerrain], the same ray always gives the same answer, so
 * results are suitable for recording or sending onward. Samples read before their elevation tiles
 * loaded are re-read once the model reports a change.
 *
 * Heights are above the ellipsoid, geoid offset included. Not thread safe.
 */
class FixedResolutionTerrain(
    private val globe: Globe,
    /** Spacing between elevation samples in metres, also used as the ray marching step. */
    var resolutionM: Double = 30.0,
    /** Use each cell's highest corner instead of interpolating, so narrow crests still occlude. */
    var conservative: Boolean = false,
    /**
     * Highest possible ground in metres above the ellipsoid. Ray steps above it are not sampled.
     * Must be a true bound for the area, or ridges above it are missed.
     */
    var maxTerrainAltitude: Double = 9_000.0,
    /** Lowest possible ground in metres above the ellipsoid. Lower it for bathymetry. */
    var minTerrainAltitude: Double = -1_000.0,
    override val sector: Sector = Sector().setFullSphere(),
) : Terrain {

    private val scratchPosition = Position()
    private val blocks = Array(BLOCK_CACHE) { Block() }
    private var reads = 0L
    private var checkedTimestamp = Long.MIN_VALUE

    private class Block {
        val sector = Sector()
        var heights = FloatArray(0)
        var resolutionM = 0.0
        var lastRead = 0L
        var elevationTimestamp = 0L
    }

    /** Marches the ray in [resolutionM] steps and returns the first ground it meets. */
    override fun intersect(line: Line, result: Vec3): Boolean {
        val step = resolutionM
        if (step <= 0.0) return false
        val direction = line.direction
        val directionLength = sqrt(
            direction.x * direction.x + direction.y * direction.y + direction.z * direction.z
        )
        if (directionLength == 0.0) return false
        dropStaleBlocks()

        globe.cartesianToGeographic(line.origin.x, line.origin.y, line.origin.z, scratchPosition)
        // Horizon distance from the origin plus from the highest possible ground
        val reach = globe.horizonDistance(max(scratchPosition.altitude, 0.0)) +
            globe.horizonDistance(maxTerrainAltitude)
        if (reach <= 0.0) return false

        var previousDistance = 0.0
        var previousClearance = clearanceAt(line, directionLength, 0.0)
        var previousAltitude = scratchPosition.altitude
        var distance = 0.0
        while (distance < reach) {
            distance = min(distance + step, reach)
            val clearance = clearanceAt(line, directionLength, distance)
            // Stop once the ray climbs above all ground or sinks below it
            val altitude = scratchPosition.altitude
            if (altitude > maxTerrainAltitude && altitude > previousAltitude) return false
            if (altitude < minTerrainAltitude) return false
            previousAltitude = altitude
            if (clearance == null) {
                // No data here, so there is no surface to stop on
                previousDistance = distance
                previousClearance = null
                continue
            }
            if (clearance <= 0.0) {
                val entering = previousClearance
                val hit = when {
                    entering == null -> distance
                    entering > 0.0 -> refine(line, directionLength, previousDistance, distance)
                    else -> previousDistance // already underground at the previous step, i.e. the origin
                }
                pointAt(line, directionLength, hit, result)
                return true
            }
            previousDistance = distance
            previousClearance = clearance
        }
        return false
    }

    override fun surfacePoint(latitude: Angle, longitude: Angle, result: Vec3): Boolean {
        dropStaleBlocks()
        val height = heightAt(latitude, longitude) ?: return false
        globe.geographicToCartesian(latitude, longitude, height, result)
        return true
    }

    /** This terrain has no levels, so the model's own limits over [sector] are returned. */
    override fun heightLimits(levelNumberDepth: Int, result: FloatArray) =
        globe.getElevationLimits(sector, result)

    /** Evicts held blocks whose elevation changed since they were read. */
    private fun dropStaleBlocks() {
        val timestamp = globe.elevationModel.timestamp
        if (timestamp == checkedTimestamp) return
        checkedTimestamp = timestamp
        for (block in blocks) {
            if (block.resolutionM != 0.0 && globe.isElevationChangedSince(block.elevationTimestamp, block.sector)) {
                block.resolutionM = 0.0 // matches no request, so the slot is refilled on next use
                block.lastRead = 0L // and is the first to be reused
            }
        }
    }

    /** Bisects the bracket found by the march to locate the ground crossing. */
    private fun refine(line: Line, directionLength: Double, from: Double, to: Double): Double {
        var low = from
        var high = to
        val tolerance = max(resolutionM * REFINE_FRACTION, MIN_REFINE_M)
        while (high - low > tolerance) {
            val middle = (low + high) / 2
            val clearance = clearanceAt(line, directionLength, middle) ?: break
            if (clearance <= 0.0) high = middle else low = middle
        }
        return high
    }

    /** Height of the ray above the ground at [distance], or null where the model has no data. */
    private fun clearanceAt(line: Line, directionLength: Double, distance: Double): Double? {
        val scale = distance / directionLength
        globe.cartesianToGeographic(
            line.origin.x + line.direction.x * scale,
            line.origin.y + line.direction.y * scale,
            line.origin.z + line.direction.z * scale,
            scratchPosition,
        )
        // Above all possible ground, so no need to read it
        if (scratchPosition.altitude > maxTerrainAltitude) return Double.POSITIVE_INFINITY
        val terrain = heightAt(scratchPosition.latitude, scratchPosition.longitude) ?: return null
        return scratchPosition.altitude - terrain
    }

    private fun pointAt(line: Line, directionLength: Double, distance: Double, result: Vec3) {
        val scale = distance / directionLength
        result.x = line.origin.x + line.direction.x * scale
        result.y = line.origin.y + line.direction.y * scale
        result.z = line.origin.z + line.direction.z * scale
    }

    private fun heightAt(latitude: Angle, longitude: Angle): Double? {
        val block = blockFor(latitude, longitude)
        val blockSector = block.sector
        val blockHeights = block.heights
        val width = BLOCK_SAMPLES + 1
        val span = BLOCK_SAMPLES.toDouble()
        val deltaLat = blockSector.deltaLatitude.inDegrees
        val deltaLon = blockSector.deltaLongitude.inDegrees
        if (deltaLat <= 0.0 || deltaLon <= 0.0) return null
        val y = ((latitude.inDegrees - blockSector.minLatitude.inDegrees) / deltaLat * span)
            .coerceIn(0.0, span)
        val x = ((longitude.inDegrees - blockSector.minLongitude.inDegrees) / deltaLon * span)
            .coerceIn(0.0, span)
        val x0 = floor(x).toInt().coerceIn(0, BLOCK_SAMPLES - 1)
        val y0 = floor(y).toInt().coerceIn(0, BLOCK_SAMPLES - 1)
        // Rows run south to north, matching Globe.getElevationGrid
        val h00 = blockHeights[x0 + y0 * width]
        val h10 = blockHeights[x0 + 1 + y0 * width]
        val h01 = blockHeights[x0 + (y0 + 1) * width]
        val h11 = blockHeights[x0 + 1 + (y0 + 1) * width]
        if (!h00.isMeasured() || !h10.isMeasured() || !h01.isMeasured() || !h11.isMeasured()) return null
        if (conservative) return max(max(h00, h10), max(h01, h11)).toDouble()
        val xf = x - x0
        val yf = y - y0
        return ((1 - xf) * (1 - yf) * h00 + xf * (1 - yf) * h10 +
            (1 - xf) * yf * h01 + xf * yf * h11).toDouble()
    }

    /** Returns the held block covering this point, loading it over the least recently used slot. */
    private fun blockFor(latitude: Angle, longitude: Angle): Block {
        reads++
        var oldest = blocks[0]
        for (block in blocks) {
            if (block.resolutionM == resolutionM && block.sector.contains(latitude, longitude)) {
                block.lastRead = reads
                return block
            }
            if (block.lastRead < oldest.lastRead) oldest = block
        }

        // Blocks sit on a fixed lattice that tiles the globe exactly, so the same ground is
        // always sampled at the same points. Rows are split evenly from pole to pole, and each
        // row into columns wide enough to keep longitude spacing within resolutionM.
        val rows = ceil(180.0 / (resolutionM * BLOCK_SAMPLES / METRES_PER_DEGREE)).toInt()
        val deltaLat = 180.0 / rows
        val row = floor((latitude.inDegrees + 90.0) / deltaLat).toInt().coerceIn(0, rows - 1)
        val minLat = -90.0 + row * deltaLat
        val maxLat = minLat + deltaLat
        // Longitude spacing is widest on the row's edge nearest the equator
        val equatorward = if (minLat >= 0.0) minLat else if (maxLat <= 0.0) maxLat else 0.0
        val cosLat = cos(equatorward * PI / 180.0)
        val columns = if (cosLat * 360.0 <= deltaLat) 1 else ceil(360.0 * cosLat / deltaLat).toInt()
        val deltaLon = 360.0 / columns
        val column = floor((longitude.inDegrees + 180.0) / deltaLon).toInt().coerceIn(0, columns - 1)
        oldest.sector.setDegrees(minLat, -180.0 + column * deltaLon, deltaLat, deltaLon)

        val width = BLOCK_SAMPLES + 1
        if (oldest.heights.size != width * width) oldest.heights = FloatArray(width * width)
        // NaN marks cells no coverage writes, so they read as missing rather than sea level
        oldest.heights.fill(Float.NaN)
        // Taken before the read, so an update landing during it still marks the block stale
        oldest.elevationTimestamp = globe.elevationModel.timestamp
        globe.getElevationGrid(oldest.sector, width, width, oldest.heights)
        oldest.resolutionM = resolutionM
        oldest.lastRead = reads
        return oldest
    }

    /** False for NaN and the model's MISSING_DATA value. */
    private fun Float.isMeasured() = isFinite() && this < MAX_MEASURED_HEIGHT

    companion object {
        /** Samples along each side of a block. */
        private const val BLOCK_SAMPLES = 128
        /** Blocks held at once, 66 KB each; enough for about one footprint's rays and the next frame's. */
        private const val BLOCK_CACHE = 24
        private const val METRES_PER_DEGREE = 111_320.0
        /** Above this a sample is the model's "no data" value rather than an elevation. */
        private const val MAX_MEASURED_HEIGHT = 1e5f
        private const val REFINE_FRACTION = 0.05
        private const val MIN_REFINE_M = 0.1
    }
}
